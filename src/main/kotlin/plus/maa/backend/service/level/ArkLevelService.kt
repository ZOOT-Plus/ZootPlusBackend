package plus.maa.backend.service.level

import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.springframework.cache.annotation.Cacheable
import org.springframework.data.domain.Pageable
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.util.DefaultUriBuilderFactory
import plus.maa.backend.common.extensions.awaitString
import plus.maa.backend.common.extensions.meetAll
import plus.maa.backend.common.extensions.traceRun
import plus.maa.backend.common.utils.converter.ArkLevelConverter
import plus.maa.backend.common.utils.converter.ArkLevelEntityConverter
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.controller.response.copilot.ArkLevelInfo
import plus.maa.backend.repository.GithubRepository
import plus.maa.backend.repository.RedisCache
import plus.maa.backend.repository.entity.ArkLevel
import plus.maa.backend.repository.entity.ArkLevelEntity
import plus.maa.backend.repository.entity.gamedata.ArkTilePos
import plus.maa.backend.repository.entity.gamedata.MaaArkStage
import plus.maa.backend.repository.entity.github.GithubCommit
import plus.maa.backend.repository.entity.github.GithubTree
import plus.maa.backend.repository.ktorm.ArkLevelRepository
import reactor.netty.http.client.HttpClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.hours

/**
 * @author dragove
 * created on 2022/12/23
 */
@Service
class ArkLevelService(
    properties: MaaCopilotProperties,
    private val githubRepo: GithubRepository,
    private val redisCache: RedisCache,
    private val arkLevelRepo: ArkLevelRepository,
    json: Json,
    private val arkLevelConverter: ArkLevelConverter,
    private val arkLevelEntityConverter: ArkLevelEntityConverter,
) {
    @OptIn(ExperimentalSerializationApi::class)
    private val json = Json(from = json) {
        namingStrategy = null
    }
    private val log = KotlinLogging.logger { }
    private val github = properties.github
    private val webClient =
        WebClient.builder().uriBuilderFactory(
            DefaultUriBuilderFactory().apply {
                encodingMode = DefaultUriBuilderFactory.EncodingMode.NONE
            },
        )
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create().proxyWithSystemProperties()
                        .responseTimeout(Duration.ofSeconds(30)),
                ),
            )
            .build()

    /**
     * 游戏数据快照的当前值。null 表示尚未成功抓取过。
     */
    @Volatile
    private var dataHolder: ArkGameDataHolder? = null

    /**
     * 快照抓取锁。同步任务、每日任务、启动回填都可能触发全量抓取（6 张表合计约 2.4 MB），
     * 串行化以避免并发重复下载。
     *
     * 注意：持锁期间会发起网络请求（单请求 30s 超时），故已缓存的读路径不取锁。
     */
    private val dataHolderMutex = Mutex()

    /**
     * 取游戏数据快照。
     *
     * @param refresh 是否强制重新抓取。为 false 时返回进程内已缓存的快照，未缓存则抓取一次。
     * @return 快照；强制刷新失败时回退到旧快照（可能为 null 表示从未成功抓取过）。
     *
     * 抓取失败不抛异常：调用方（回填、开放状态更新）都是兜底任务，用旧快照继续好过整个任务失败。
     */
    private suspend fun dataHolder(refresh: Boolean = false): ArkGameDataHolder? {
        if (!refresh) dataHolder?.let { return it }
        return dataHolderMutex.withLock {
            val cached = dataHolder
            // 双重检查：并发进入时，先到者已抓取完成则直接复用
            if (cached != null && !refresh) return@withLock cached
            try {
                ArkGameDataHolder.fetch(webClient).also { dataHolder = it }
            } catch (e: CancellationException) {
                // 协程取消必须继续传播，否则被取消的任务会继续往下跑
                throw e
            } catch (e: Exception) {
                log.error(e) { "[LEVEL] 获取游戏数据快照失败" }
                cached
            }
        }
    }

    @get:Cacheable("arkLevelInfos")
    val arkLevelInfos: List<ArkLevelInfo>
        get() {
            val entities = arkLevelRepo.findAll()
            return arkLevelConverter.convert(entities)
        }

    @Cacheable("arkLevel")
    fun findByLevelIdFuzzy(levelId: String): ArkLevel? {
        val entities = arkLevelRepo.findByLevelIdFuzzy(levelId)
        return entities.firstOrNull()?.let { arkLevelEntityConverter.convertFromEntity(it) }
    }

    fun queryLevelInfosByKeyword(keyword: String): List<ArkLevelInfo> {
        val entities = arkLevelRepo.queryLevelByKeyword(keyword)
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
                val shaSet = withContext(Dispatchers.IO) { arkLevelRepo.findAllShaBy() }.map { it.sha }.toSet()
                val filtered = trees.filter { !shaSet.contains(it.sha) }

                // 有新地图文件时刷新游戏数据快照：快照与进程同生命周期，不刷新的话新活动的活动名
                // 在进程重启前拿不到（缺失活动名的成因之一）。无新文件时不做任何下载。
                if (filtered.isEmpty()) {
                    logI { "无新增地图数据" }
                } else {
                    val holder = dataHolder(refresh = true) ?: error("游戏数据快照不可用，无法解析地图数据")
                    downloadAndSaveLevelDatum(filtered, ArkLevelParserDelegate(holder))

                    // 新增文件解析完立即回填一次，让新活动的活动名尽快就位
                    repairMissingActivityNames(LevelNameRepairSource.SYNC)
                }
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
                withContext(Dispatchers.IO) { arkLevelRepo.save(entity) }
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
        val holder = dataHolder() ?: error("游戏数据快照不可用，无法更新危机合约开放状态")
        ArkGameDataHolder.updateCrisisV2Info(holder, webClient)
        val nowTime = LocalDateTime.now()

        updateLevelsOfTypeInBatch(ArkLevelType.RUNE) { entity ->
            val info = holder.findCrisisV2InfoById(entity.stageId) ?: return@updateLevelsOfTypeInBatch
            entity.closeTime = LocalDateTime.ofEpochSecond(info.endTs, 0, ZoneOffset.UTC)
            entity.isOpen = entity.closeTime?.isAfter(nowTime)
        }
        logI { "开放状态更新完毕" }
    }

    /**
     * 回填空缺的活动名（`cat_two` 为空的活动关卡行），见 [LevelNameRepairSource]。
     *
     * **幂等**：只处理当前仍为空的行，解析不出活动名时不写库。因此「历史数据的一次性回填」与
     * 「后续增量兜底」是同一段代码的首次与后续执行，可安全地挂在多个触发点上重复调用。
     *
     * 执行顺序：节流（仅启动触发）→ 廉价门禁（一条 COUNT）→ 取/刷新快照 → 逐行重建地图数据并解析。
     * 门禁在取快照之前，因此无空值行时既不触网也不占锁。
     */
    suspend fun repairMissingActivityNames(source: LevelNameRepairSource): LevelNameRepairStat = log.traceRun("LEVEL-NAME-REPAIR") {
        try {
            if (source == LevelNameRepairSource.STARTUP && redisCache.getCache<String>(REPAIR_LAST_RUN_KEY) != null) {
                logI { "近期已执行过活动名回填，跳过本次启动触发" }
                return@traceRun LevelNameRepairStat(0, 0, 0)
            }
            val blank = withContext(Dispatchers.IO) {
                arkLevelRepo.countBlankCatTwoByCatOne(ArkLevelType.ACTIVITIES.display)
            }
            if (blank == 0L) {
                logI { "无缺失活动名，无需回填" }
                return@traceRun LevelNameRepairStat(0, 0, 0)
            }
            // 每日兜底强制刷新快照：活动名可能晚于地图文件发布，用旧快照重解析拿不到名字
            val holder = dataHolder(refresh = source == LevelNameRepairSource.DAILY)
            if (holder == null) {
                logI { "游戏数据快照不可用（$blank 行待回填），留待下次执行" }
                return@traceRun LevelNameRepairStat(blank.toInt(), 0, blank.toInt())
            }
            val stat = repairMissingActivityNames(holder)
            if (source == LevelNameRepairSource.STARTUP) {
                redisCache.setCache(REPAIR_LAST_RUN_KEY, "1", 24.hours)
            }
            stat
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            logE(e) { "回填缺失活动名失败" }
            LevelNameRepairStat(0, 0, 0)
        }
    }

    /**
     * 用给定快照执行一次回填。与 [repairMissingActivityNames] 的差别仅在触发来源相关的
     * 节流与快照刷新，实体逻辑完全一致，便于测试。
     */
    internal suspend fun repairMissingActivityNames(holder: ArkGameDataHolder): LevelNameRepairStat = log.traceRun("LEVEL-NAME-REPAIR") {
        val blanks = withContext(Dispatchers.IO) {
            arkLevelRepo.findAllBlankCatTwoByCatOne(ArkLevelType.ACTIVITIES.display)
        }
        if (blanks.isEmpty()) return@traceRun LevelNameRepairStat(0, 0, 0)

        val parser = ArkLevelParserDelegate(holder)
        var repaired = 0
        var stillEmpty = 0
        blanks.forEach { entity ->
            val name = resolveActivityName(parser, entity)
            if (name.isNullOrBlank()) {
                stillEmpty++
            } else {
                withContext(Dispatchers.IO) { arkLevelRepo.updateCatTwoById(entity.id, name) }
                repaired++
            }
        }
        logI { "活动名回填完成：扫描 ${blanks.size}，修复 $repaired，仍缺失 $stillEmpty" }
        LevelNameRepairStat(blanks.size, repaired, stillEmpty)
    }

    /**
     * 由数据库行重建地图数据并重新解析出活动名。
     *
     * 所有 parser 只读取地图文件的 code/levelId/stageId/name/width/height 六个字段（不读地图格子
     * `tiles`/`view`），这六个字段 `ark_level` 表均有保存，故无需下载地图文件。前提是
     * `cat_three == 地图文件的 code`，该等式**只对活动关卡成立**（其它分类的 parser 会覆写
     * cat_three，见 [ArkLevelParserDelegate]），因此调用方必须把范围限定为活动关卡。
     *
     * 单行解析异常（例如历史脏数据导致 parser 的空断言失败）不上抛：批量回填不应因一行而中断。
     */
    private fun resolveActivityName(parser: ArkLevelParserDelegate, entity: ArkLevelEntity): String? {
        val tilePos = ArkTilePos(
            code = entity.catThree,
            levelId = entity.levelId,
            name = entity.name,
            stageId = entity.stageId,
            width = entity.width,
            height = entity.height,
        )
        return try {
            parser.parseLevel(tilePos, entity.sha)?.catTwo
        } catch (e: Exception) {
            log.error(e) { "[LEVEL-NAME-REPAIR] 重建地图数据失败: id=${entity.id}, levelId=${entity.levelId}" }
            null
        }
    }

    suspend fun updateLevelsOfTypeInBatch(
        catOne: ArkLevelType,
        batchSize: Int = 1000,
        block: (plus.maa.backend.repository.entity.ArkLevelEntity) -> Unit,
    ) {
        var pageable = Pageable.ofSize(batchSize)
        do {
            val page = withContext(Dispatchers.IO) { arkLevelRepo.findAllByCatOne(catOne.display, pageable) }
            page.forEach(block)
            withContext(Dispatchers.IO) { arkLevelRepo.saveAll(page.content) }
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
        return json.decodeFromString(text)
    }

    private suspend fun <T> workIfStale(key: String, requiredValue: String, block: suspend () -> T): Boolean {
        val c = redisCache.getCache<String>(key)
        if (c == requiredValue) return false
        block.invoke()
        redisCache.setData(key, requiredValue)
        return true
    }

    companion object {
        /**
         * 活动名回填的启动节流键。应用频繁重启时避免反复触发全量回填。
         */
        private const val REPAIR_LAST_RUN_KEY = "level:name-repair:last-run"
    }
}
