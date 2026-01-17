package plus.maa.backend.service.level

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Pageable
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.DefaultUriBuilderFactory
import plus.maa.backend.common.extensions.awaitString
import plus.maa.backend.common.extensions.lazySuspend
import plus.maa.backend.common.extensions.meetAll
import plus.maa.backend.common.extensions.traceRun
import plus.maa.backend.common.utils.converter.ArkLevelConverter
import plus.maa.backend.common.utils.converter.ArkLevelEntityConverter
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.controller.response.copilot.ArkLevelInfo
import plus.maa.backend.repository.GithubRepository
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.entity.ArkLevel
import plus.maa.backend.repository.entity.gamedata.ArkTilePos
import plus.maa.backend.repository.entity.gamedata.MaaArkStage
import plus.maa.backend.repository.entity.github.GithubCommit
import plus.maa.backend.repository.entity.github.GithubTree
import plus.maa.backend.repository.ktorm.ArkLevelKtormRepository
import reactor.netty.http.client.HttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/**
 * @author dragove
 * created on 2022/12/23
 */
@Service
class ArkLevelService(
    properties: MaaCopilotProperties,
    private val githubRepo: GithubRepository,
    private val redisCache: RedisCache,
    private val arkLevelKtormRepo: ArkLevelKtormRepository,
    private val mapper: ObjectMapper,
    private val arkLevelConverter: ArkLevelConverter,
    private val arkLevelEntityConverter: ArkLevelEntityConverter,
    webClientBuilder: WebClient.Builder,
) {
    private val log = KotlinLogging.logger { }
    private val github = properties.github
    private val webClient =
        webClientBuilder.uriBuilderFactory(DefaultUriBuilderFactory().apply { encodingMode = DefaultUriBuilderFactory.EncodingMode.NONE })
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create().proxyWithSystemProperties()
                        .responseTimeout(Duration.ofSeconds(30))
                )
            )
            .build()
    private val fetchDataHolder = lazySuspend { ArkGameDataHolder.fetch(webClient) }
    private val fetchLevelParser = lazySuspend { ArkLevelParserDelegate(fetchDataHolder()) }

    @get:Cacheable("arkLevelInfos")
    val arkLevelInfos: List<ArkLevelInfo>
        get() {
            val entities = arkLevelKtormRepo.findAll()
            return arkLevelConverter.convert(entities)
        }

    @Cacheable("arkLevel")
    fun findByLevelIdFuzzy(levelId: String): ArkLevel? {
        val entities = arkLevelKtormRepo.findByLevelIdFuzzy(levelId)
        return entities.firstOrNull()?.let { arkLevelEntityConverter.convertFromEntity(it) }
    }

    fun queryLevelInfosByKeyword(keyword: String): List<ArkLevelInfo> {
        val entities = arkLevelKtormRepo.queryLevelByKeyword(keyword)
        return arkLevelConverter.convert(entities)
    }

    /**
     * 地图数据更新任务
     */
    suspend fun syncLevelData() = log.traceRun("LEVEL") {
        try {
            logI { "开始同步地图数据" }
            // 获取地图文件夹最新的 commit, 与缓存的 commit 比较，如果相同则不更新
            val commit = getGithubCommits().firstOrNull()
            checkNotNull(commit) { "获取地图数据最新 commit 失败" }

            val stale = workIfStale("level:commit", commit.sha) {
                val trees = fetchTilePosGithubTreesToUpdate(commit)
                logI { "已发现 ${trees.size} 份地图数据" }

                // 根据 sha 筛选无需更新的地图
                val shaSet = withContext(Dispatchers.IO) { arkLevelKtormRepo.findAllShaBy() }.map { it.sha }.toSet()
                val filtered = trees.filter { !shaSet.contains(it.sha) }

                val parser = fetchLevelParser()
                downloadAndSaveLevelDatum(filtered, parser)
            }
            if (!stale) logI { "地图数据已是最新" }
        } catch (e: Exception) {
            logE(e) { "同步地图数据失败" }
        }
    }

    private suspend fun fetchTilePosGithubTreesToUpdate(commit: GithubCommit): List<GithubTree> {
        val segments = github.tilePosPath.split("/").filter(String::isNotEmpty)
        var folder = getGithubTree(commit.sha)
        for (s in segments) {
            val targetTree = folder.tree.firstOrNull { it.path == s && it.type == "tree" }
                ?: throw Exception("地图数据获取失败, 未找到文件夹 ${github.tilePosPath}")
            folder = getGithubTree(targetTree.sha)
        }
        // 根据后缀筛选地图文件列表,排除 overview 文件、肉鸽、训练关卡和 Guide? 不知道是啥
        return folder.tree.filter {
            meetAll(
                it.type == "blob",
                it.path.endsWith(".json"),
                it.path != "overview.json",
                !it.path.contains("roguelike"),
                !it.path.startsWith("tr_"),
                !it.path.startsWith("guide_"),
            )
        }
    }

    private suspend fun downloadAndSaveLevelDatum(trees: List<GithubTree>, parser: ArkLevelParserDelegate) = log.traceRun("LEVEL") {
        val total = trees.size
        logI { " $total 份地图数据需要更新" }

        val success = AtomicInteger(0)
        val fail = AtomicInteger(0)
        val pass = AtomicInteger(0)
        fun current() = success.get() + fail.get() + pass.get()

        val startTime = System.currentTimeMillis()
        fun duration() = (System.currentTimeMillis() - startTime) / 1000
        fun entryInfo(path: String, result: String) = "[${current()}/$total][${duration()}s] 更新 $path $result"

        val semaphore = Semaphore(20)
        suspend fun downloadAndSave(tree: GithubTree) = try {
            semaphore.acquire()
            val fileName = URLEncoder.encode(tree.path, StandardCharsets.UTF_8)
            val url = "https://raw.githubusercontent.com/${github.repoAndBranch}/${github.tilePosPath}/$fileName"
            val tilePos = getTextAsEntity<ArkTilePos>(url)
            val level = parser.parseLevel(tilePos, tree.sha)
            checkNotNull(level) {
                "地图数据解析失败, code: ${tilePos.code}, levelId: ${tilePos.levelId}," +
                    " name: ${tilePos.name}, stageId: ${tilePos.stageId}"
            }
            if (level === ArkLevel.EMPTY) {
                pass.incrementAndGet()
                logI { entryInfo(tree.path, "未知类型，跳过") }
            } else {
                val entity = arkLevelEntityConverter.convertToEntityWithAutoId(level)
                withContext(Dispatchers.IO) { arkLevelKtormRepo.save(entity) }
                success.incrementAndGet()
                logI { entryInfo(tree.path, "成功") }
            }
        } catch (e: Exception) {
            fail.incrementAndGet()
            logE(e) { entryInfo(tree.path, "失败") }
        } finally {
            semaphore.release()
        }

        coroutineScope { trees.map { async { downloadAndSave(it) } }.awaitAll() }
        logI { "地图数据更新完成, 成功:${success.get()}, 失败:${fail.get()}, 跳过:${pass.get()}, 总用时 ${duration()}s" }
    }

    /**
     * 更新活动地图开放状态
     */
    suspend fun updateActivitiesOpenStatus() = log.traceRun("ACTIVITIES-OPEN-STATUS") {
        try {
            logI { "准备更新" }
            val content = getGithubContent("resource").firstOrNull { it.isFile && "stages.json" == it.name }
            val downloadUrl = checkNotNull(content?.downloadUrl) { "数据不存在" }

            val stale = workIfStale("level:stages:sha", content.sha) {
                logI { "开始下载数据" }
                val openStages = getTextAsEntity<List<MaaArkStage>>(downloadUrl)
                val openStageKeys = openStages.map { ArkLevelUtil.getKeyInfoById(it.stageId) }.toSet()
                val now = LocalDateTime.now()

                logI { "下载完成，开始更新" }
                updateLevelsOfTypeInBatch(ArkLevelType.ACTIVITIES) { entity ->
                    entity.isOpen = ArkLevelUtil.getKeyInfoById(entity.stageId) in openStageKeys
                    entity.closeTime = if (entity.isOpen ?: false) null else entity.closeTime ?: now
                }
                logI { "更新完成" }
            }
            if (!stale) logI { "已是最新" }
        } catch (e: Exception) {
            log.error(e) { "[ACTIVITIES-OPEN-STATUS] 更新失败" }
        }
    }

    /**
     * 更新危机合约开放状态
     */
    suspend fun updateCrisisV2OpenStatus() = log.traceRun("CRISIS-V2-OPEN-STATUS") {
        logI { "准备更新开放状态" }
        val holder = ArkGameDataHolder.updateCrisisV2Info(fetchDataHolder(), webClient)
        val nowTime = LocalDateTime.now()

        updateLevelsOfTypeInBatch(ArkLevelType.RUNE) { entity ->
            val info = holder.findCrisisV2InfoById(entity.stageId) ?: return@updateLevelsOfTypeInBatch
            entity.closeTime = LocalDateTime.ofEpochSecond(info.endTs, 0, ZoneOffset.UTC)
            entity.isOpen = entity.closeTime?.isAfter(nowTime)
        }
        logI { "开放状态更新完毕" }
    }

    suspend fun updateLevelsOfTypeInBatch(
        catOne: ArkLevelType,
        batchSize: Int = 1000,
        block: (plus.maa.backend.repository.entity.ArkLevelEntity) -> Unit,
    ) {
        var pageable = Pageable.ofSize(batchSize)
        do {
            val page = withContext(Dispatchers.IO) { arkLevelKtormRepo.findAllByCatOne(catOne.display, pageable) }
            page.forEach(block)
            withContext(Dispatchers.IO) { arkLevelKtormRepo.saveAll(page.content) }
            pageable = page.nextPageable()
        } while (page.hasNext())
    }

    private suspend fun getGithubCommits() = withContext(Dispatchers.IO) { githubRepo.getCommits(github.token) }
    private suspend fun getGithubTree(sha: String) = withContext(Dispatchers.IO) { githubRepo.getTrees(github.token, sha) }
    private suspend fun getGithubContent(path: String) = withContext(Dispatchers.IO) { githubRepo.getContents(github.token, path) }

    /**
     * Fetch a resource as text, parse as JSON and convert it to entity.
     *
     * GithubContents returns responses of `text/html` normally
     */
    private suspend inline fun <reified T> getTextAsEntity(uri: String): T {
        val text = webClient.get().uri(uri).retrieve().awaitString()
        return mapper.readValue<T>(text)
    }

    private suspend fun <T> workIfStale(key: String, requiredValue: String, block: suspend () -> T): Boolean {
        val c = redisCache.getCache(key, String::class.java)
        if (c == requiredValue) return false
        block.invoke()
        redisCache.setData(key, requiredValue)
        return true
    }
}
