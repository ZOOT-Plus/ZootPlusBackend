package plus.maa.backend.task

import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.PostConstruct
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.api.errors.GitAPIException
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.Transport
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder
import org.eclipse.jgit.util.FS
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.ktorm.CopilotKtormRepository
import plus.maa.backend.service.level.ArkLevelService
import plus.maa.backend.service.model.CopilotSetStatus
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.Objects

private val log = KotlinLogging.logger { }

/**
 * CopilotBackupTask
 */
@Component
class CopilotBackupTask(
    private val config: MaaCopilotProperties,
    private val copilotKtormRepository: CopilotKtormRepository,
    private val levelService: ArkLevelService,
) {
    private lateinit var git: Git

    /**
     * 初始化Git对象，如果目录已经存在且存在文件，则直接当作git仓库，如果不存在则clone仓库
     */
    @PostConstruct
    fun initGit() {
        val backup = config.backup
        if (backup.disabled) {
            log.info { "Copilot backup disabled" }
            return
        }
        val repoDir = File(backup.dir)
        if (repoDir.mkdirs()) {
            log.info { "directory not exist, created: ${backup.dir}" }
        } else {
            log.info { "directory already exists, dir: ${backup.dir}" }
        }
        if (!repoDir.isDirectory) {
            return
        }
        try {
            Files.list(repoDir.toPath()).use { fileList ->
                git = if (fileList.findFirst().isEmpty) {
                    // 不存在文件则初始化
                    Git.cloneRepository().setURI(backup.uri).setDirectory(repoDir).setTransportConfigCallback(sshCallback).call()
                } else {
                    Git.open(repoDir)
                }
            }
        } catch (e: IOException) {
            log.error { "init copilot backup repo failed, repoDir: $repoDir, $e" }
        } catch (e: GitAPIException) {
            log.error { "init copilot backup repo failed, repoDir: $repoDir, $e" }
        }
    }

    /**
     * copilot数据同步定时任务，每天执行一次
     */
    @Scheduled(cron = "\${maa-copilot.task-cron.copilot-update:-}", zone = "Asia/Shanghai")
    fun backupCopilots() {
        if (config.backup.disabled || Objects.isNull(git)) {
            return
        }
        try {
            git.pull().call()
        } catch (e: GitAPIException) {
            log.error { "git pull execute failed, msg: ${e.message}, $e" }
        }

        val monthAgo = LocalDateTime.now().minusDays(60L)
        val baseDirectory = git.repository.workTree
        val copilots = copilotKtormRepository.findAllByUploadTimeAfterOrDeleteTimeAfter(monthAgo, monthAgo)
        copilots.forEach { copilot: CopilotEntity ->
            val level = levelService.findByLevelIdFuzzy(copilot.stageName) ?: return@forEach
            // 暂时使用 copilotId 作为文件名
            val filePath = File(
                java.lang.String.join(
                    File.separator,
                    baseDirectory.path,
                    level.catOne,
                    level.catTwo,
                    level.catThree,
                    copilot.copilotId.toString() + ".json",
                ),
            )
            val content = copilot.content
            if (copilot.delete || copilot.status == CopilotSetStatus.PRIVATE) {
                // 删除文件
                deleteCopilot(filePath)
            } else {
                // 创建或者修改文件
                upsertCopilot(filePath, content)
            }
        }

        doCommitAndPush()
    }

    private fun upsertCopilot(file: File, content: String) {
        if (!file.exists()) {
            if (!file.parentFile.mkdirs()) {
                log.warn { "folder may exists, mkdir failed" }
            }
        }
        try {
            Files.writeString(file.toPath(), content)
        } catch (e: IOException) {
            log.error { "write file failed, path: ${file.path}, message: ${e.message}, $e" }
        }
    }

    private fun deleteCopilot(file: File) {
        if (file.exists()) {
            if (file.delete()) {
                log.info { "delete copilot file: ${file.path}" }
            } else {
                log.error { "delete copilot failed, file: ${file.path}" }
            }
        } else {
            log.info { "file does not exists, no need to delete" }
        }
    }

    private fun doCommitAndPush() {
        try {
            val status = git.status().call()
            if (status.added.isEmpty() &&
                status.changed.isEmpty() &&
                status.removed.isEmpty() &&
                status.untracked.isEmpty() &&
                status.modified.isEmpty() &&
                status.added.isEmpty()
            ) {
                log.info { "copilot backup with no new added or changes" }
                return
            }
            git.add().addFilepattern(".").call()
            val backup = config.backup
            val committer = PersonIdent(backup.username, backup.email)
            git.commit().setCommitter(committer)
                .setMessage(LocalDate.now().toString())
                .call()
            git.push()
                .setTransportConfigCallback(sshCallback)
                .call()
        } catch (e: GitAPIException) {
            log.error { "git committing failed, msg: ${e.message}, $e" }
        }
    }

    companion object {
        private val DEFAULT_SSH_DIR = File(FS.DETECTED.userHome(), "/.ssh")

        private val sshCallback = TransportConfigCallback { transport: Transport ->
            if (transport is SshTransport) {
                transport.sshSessionFactory = SshdSessionFactoryBuilder()
                    .setPreferredAuthentications("publickey")
                    .setHomeDirectory(FS.DETECTED.userHome())
                    .setSshDirectory(DEFAULT_SSH_DIR)
                    .build(null)
            }
        }
    }
}
