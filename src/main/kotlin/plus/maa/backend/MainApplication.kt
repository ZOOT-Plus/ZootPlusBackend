package plus.maa.backend

import io.github.oshai.kotlinlogging.KotlinLogging
import org.babyfish.jimmer.sql.kt.KSqlClient
import org.babyfish.jimmer.sql.kt.exists
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.cache.annotation.EnableCaching
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.annotation.EnableScheduling
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.stereotype.Component
import plus.maa.backend.repository.CopilotRepository
import plus.maa.backend.repository.UserRepository
import plus.maa.backend.repository.entity.CopilotEntity
import plus.maa.backend.repository.entity.OperatorEntity
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.service.model.CommentStatus
import java.time.LocalDateTime

@EnableAsync
@EnableCaching
@EnableScheduling
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableMethodSecurity
class MainApplication

fun main(args: Array<String>) {
    runApplication<MainApplication>(*args)
}

@Component
class DataMigration(
    val userRepository: UserRepository,
    val copilotRepository: CopilotRepository,
    val sqlClient: KSqlClient
) {
    private val log = KotlinLogging.logger { }

    @EventListener(ApplicationReadyEvent::class)
    fun doMigration() {
        migrateUser()
        migrateCopilot()
    }

    fun migrateUser() {
        val exists = sqlClient.exists(UserEntity::class)
        if (exists) {
            // 已经完成迁移
            log.info { "用户对象已经完成迁移" }
            return
        }
        val users = userRepository.findAllBy()
        log.info { "迁移用户" }
        var migratedSize = 0
        val chunkList = ArrayList<UserEntity>()

        users.forEach { user ->
            if (chunkList.size == 400) {
                migratedSize += 400
                log.info { "迁移用户，当前处理：${migratedSize}" }
                sqlClient.saveEntities(chunkList)
                chunkList.clear()
            }

            val u = UserEntity {
                userId = user.userId!!
                userName = user.userName
                email = user.email
                password = user.password
                pwdUpdateTime = user.pwdUpdateTime
                status = user.status
                followingCount = user.followingCount
                fansCount = user.fansCount
            }
            chunkList.add(u)
        }
        // 插入剩余数据
        sqlClient.saveEntities(chunkList)
    }

    fun migrateCopilot() {
        val exists = sqlClient.exists(CopilotEntity::class)
        if (exists) {
            log.info { "作业已经全部完成迁移" }
            return
        }
        val copilots = copilotRepository.findByContentIsNotNull()
        log.info { "开始迁移作业列表" }
        var migratedSize = 0
        val chunkList = ArrayList<CopilotEntity>()
        copilots.forEach { copilot ->
            if (chunkList.size == 400) {
                migratedSize += 400
                log.info { "迁移作业列表，当前处理：${migratedSize}" }
                sqlClient.saveEntities(chunkList)
                chunkList.clear()
            }
            chunkList.add(
                CopilotEntity {
                    copilotId = copilot.copilotId!!
                    stageName = copilot.stageName ?: ""
                    uploaderId = copilot.uploaderId ?: ""
                    views = copilot.views
                    ratingLevel = copilot.ratingLevel
                    ratingRatio = copilot.ratingRatio
                    likeCount = copilot.likeCount
                    dislikeCount = copilot.dislikeCount
                    hotScore = copilot.hotScore
                    title = copilot.doc?.title ?: ""
                    details = copilot.doc?.details
                    firstUploadTime = copilot.firstUploadTime ?: LocalDateTime.of(0, 0, 0, 0, 0)
                    uploadTime = copilot.uploadTime ?: LocalDateTime.of(0, 0, 0, 0, 0)
                    content = copilot.content ?: "{}"
                    status = copilot.status
                    commentStatus = copilot.commentStatus ?: CommentStatus.ENABLED
                    delete = copilot.delete
                    deleteTime = copilot.deleteTime
                    notification = copilot.notification ?: false
                    opers = copilot.opers?.map {
                        OperatorEntity {
                            name = it.name ?: ""
                        }
                    } ?: emptyList()
                })
        }
        sqlClient.saveEntities(chunkList)
        log.info { "migration successfully" }
    }

}
