package plus.maa.backend

import io.github.oshai.kotlinlogging.KotlinLogging
import org.ktorm.database.Database
import org.ktorm.dsl.batchInsert
import org.ktorm.entity.any
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
import plus.maa.backend.repository.entity.Copilots
import plus.maa.backend.repository.entity.OperatorEntity
import plus.maa.backend.repository.entity.Operators
import plus.maa.backend.repository.entity.UserEntity
import plus.maa.backend.repository.entity.Users
import plus.maa.backend.repository.entity.copilots
import plus.maa.backend.repository.entity.users
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


/**
 * FIXME: 完成迁移后删除该部分代码
 */
@Component
class DataMigration(
    val userRepository: UserRepository,
    val copilotRepository: CopilotRepository,
    val database: Database
) {
    private val log = KotlinLogging.logger { }

    @EventListener(ApplicationReadyEvent::class)
    fun doMigration() {
        migrateUser()
        migrateCopilot()
    }

    fun migrateUser() {
        val alreadyExists = database.users.any()
        if (alreadyExists) {
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
                batchInsertUsers(chunkList)
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
        batchInsertUsers(chunkList)
        // 插入剩余数据
    }

    fun batchInsertUsers(users: List<UserEntity>) {
        database.batchInsert(Users) {
            users.forEach { user ->
                item {
                    set(it.userId, user.userId)
                    set(it.userName, user.userName)
                    set(it.email, user.email)
                    set(it.password, user.password)
                    set(it.status, user.status)
                    set(it.pwdUpdateTime, user.pwdUpdateTime)
                    set(it.followingCount, user.followingCount)
                    set(it.fansCount, user.fansCount)
                }
            }
        }
    }

    fun migrateCopilot() {
        val alreadyExists = database.copilots.any()
        if (alreadyExists) {
            log.info { "作业已经全部完成迁移" }
            return
        }
        val copilots = copilotRepository.findByContentIsNotNull()
        log.info { "开始迁移作业列表" }
        var migratedSize = 0
        val copilotsChunk = ArrayList<CopilotEntity>()
        val operatorsChunk = ArrayList<OperatorEntity>()
        copilots.forEach { originCopilot ->
            if (copilotsChunk.size == 400) {
                migratedSize += 400
                log.info { "迁移作业列表，当前处理：${migratedSize}" }
                batchInsertCopilots(copilotsChunk, operatorsChunk)
                copilotsChunk.clear()
                operatorsChunk.clear()
            }
            val copilotEntity = CopilotEntity {
                copilotId = originCopilot.copilotId!!
                stageName = originCopilot.stageName ?: ""
                uploaderId = originCopilot.uploaderId ?: ""
                views = originCopilot.views
                ratingLevel = originCopilot.ratingLevel
                ratingRatio = originCopilot.ratingRatio
                likeCount = originCopilot.likeCount
                dislikeCount = originCopilot.dislikeCount
                hotScore = originCopilot.hotScore
                title = originCopilot.doc?.title ?: ""
                details = originCopilot.doc?.details
                firstUploadTime = originCopilot.firstUploadTime ?: LocalDateTime.of(0, 0, 0, 0, 0)
                uploadTime = originCopilot.uploadTime ?: LocalDateTime.of(0, 0, 0, 0, 0)
                content = originCopilot.content ?: "{}"
                status = originCopilot.status
                commentStatus = originCopilot.commentStatus ?: CommentStatus.ENABLED
                delete = originCopilot.delete
                deleteTime = originCopilot.deleteTime
                notification = originCopilot.notification ?: false
            }
            copilotsChunk.add(copilotEntity)
            originCopilot.opers?.map { op ->
                OperatorEntity {
                    copilot = copilotEntity
                    name = op.name!!
                }
            }?.forEach { operatorsChunk.add(it) }

        }
        batchInsertCopilots(copilotsChunk, operatorsChunk)
        log.info { "migration successfully" }
    }

    fun batchInsertCopilots(copilots: List<CopilotEntity>, operators: List<OperatorEntity>) {
        database.batchInsert(Copilots) {
            copilots.forEach { copilot ->
                item {
                    set(it.copilotId, copilot.copilotId)
                    set(it.stageName, copilot.stageName)
                    set(it.uploaderId, copilot.uploaderId)
                    set(it.views, copilot.views)
                    set(it.ratingLevel, copilot.ratingLevel)
                    set(it.ratingRatio, copilot.ratingRatio)
                    set(it.likeCount, copilot.likeCount)
                    set(it.dislikeCount, copilot.dislikeCount)
                    set(it.hotScore, copilot.hotScore)
                    set(it.title, copilot.title)
                    set(it.details, copilot.details)
                    set(it.firstUploadTime, copilot.firstUploadTime)
                    set(it.uploadTime, copilot.uploadTime)
                    set(it.content, copilot.content)
                    set(it.status, copilot.status)
                    set(it.commentStatus, copilot.commentStatus)
                    set(it.delete, copilot.delete)
                    set(it.deleteTime, copilot.deleteTime)
                    set(it.notification, copilot.notification)
                }
            }
        }
        if (operators.isNotEmpty()) {
            database.batchInsert(Operators) {
                operators.forEach { operator ->
                    item {
                        set(it.copilotId, operator.copilot.copilotId)
                        set(it.name, operator.name)
                    }
                }
            }
        }
    }

}

