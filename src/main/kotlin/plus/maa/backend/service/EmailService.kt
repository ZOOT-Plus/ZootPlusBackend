package plus.maa.backend.service

import cn.hutool.extra.mail.MailAccount
import cn.hutool.extra.mail.MailUtil
import io.github.oshai.kotlinlogging.KotlinLogging
import jakarta.annotation.Resource
import org.apache.commons.lang3.RandomStringUtils
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.task.AsyncTaskExecutor
import org.springframework.stereotype.Service
import plus.maa.backend.common.utils.FreeMarkerUtils
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.controller.response.MaaResultException
import plus.maa.backend.repository.RedisCache
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

/**
 * @author LoMu
 * Date 2022-12-24 11:05
 */
@Service
class EmailService(
    private val maaCopilotProperties: MaaCopilotProperties,
    // 测试用 flag，本地测试时可以开启并在控制台查看验证码
    @param:Value($$"${debug.email.no-send:false}")
    private val flagNoSend: Boolean = false,
    private val redisCache: RedisCache,
    @Resource(name = "emailTaskExecutor")
    private val emailTaskExecutor: AsyncTaskExecutor,
) {
    private val log = KotlinLogging.logger { }
    private val mails = maaCopilotProperties.mails
    private val currentMailIdx = AtomicInteger()
    private val mailAccounts = mails.map {
        MailAccount()
            .setHost(it.host)
            .setPort(it.port)
            .setFrom(it.from)
            .setUser(it.user)
            .setPass(it.pass)
            .setSslEnable(it.ssl)
            .setStarttlsEnable(it.starttls)
    }
    private val timeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    private fun nextMailAccount() = mailAccounts[currentMailIdx.getAndIncrement() % mailAccounts.size]

    /**
     * 发送验证码
     * 以email作为 redis key
     * vcode(验证码)作为 redis value
     *
     * @param email 邮箱
     */
    fun sendVCode(email: String) {
        // 一个过期周期最多重发十条，记录已发送的邮箱以及间隔时间
        val timeout = maaCopilotProperties.vcode.expire / 10
        if (!redisCache.setCacheIfAbsent("HasBeenSentVCode:$email", timeout, timeout.seconds)) {
            // 设置失败，说明 key 已存在
            throw MaaResultException(403, "发送验证码的请求至少需要间隔 $timeout 秒")
        }
        // 执行异步任务
        doSendVCode(email)
    }

    private fun doSendVCode(email: String) {
        // 6位随机数验证码
        val vCode = RandomStringUtils.insecure().next(6, true, true).uppercase(Locale.getDefault())
        if (flagNoSend) {
            log.warn { "Email not sent, no-send enabled, vcode is $vCode" }
        } else {
            val subject = "Maa Backend Center 验证码"
            val dataModel = mapOf(
                "content" to "mail-vCode.ftlh",
                "obj" to vCode,
            )
            val content = FreeMarkerUtils.parseData("mail-includeHtml.ftlh", dataModel)
            log.info { "try send email to $email" }
            try {
                MailUtil.send(nextMailAccount(), listOf(email), subject, content, true)
                log.info { "send email to $email successfully" }
            } catch (e: Exception) {
                log.error(e) { "send email failed, msg: ${e.message}" }
                throw IllegalStateException("邮件服务异常，请稍后再试或联系管理员")
            }
        }
        // 存redis
        redisCache.setCache("vCodeEmail:$email", vCode, maaCopilotProperties.vcode.expire.seconds)
    }

    /**
     * 检验验证码并抛出异常
     * @param email 邮箱
     * @param vcode 验证码
     * @throws MaaResultException 验证码错误
     */
    fun verifyVCode(email: String, vcode: String) {
        if (!redisCache.removeKVIfEquals("vCodeEmail:$email", vcode.uppercase(Locale.getDefault()))) {
            throw MaaResultException(401, "验证码错误")
        }
    }

    fun sendCommentNotification(
        receiverEmail: String,
        receiverName: String,
        targetMessage: String,
        replierName: String,
        message: String,
        subLink: String = "",
        timeStr: String = LocalDateTime.now().format(timeFormatter),
    ) = emailTaskExecutor.execute {
        if (!maaCopilotProperties.mail.notification) return@execute
        val limit = 25
        val title = targetMessage.run { if (length > limit) take(limit - 4) + "...." else this }

        val subject = "收到新回复 来自用户@$replierName Re: $title"

        val dataModel = mapOf(
            "content" to "mail-comment-notification.ftlh",
            "authorName" to receiverName,
            "frontendLink" to "${maaCopilotProperties.info.frontendDomain}/${subLink.removePrefix("/")}",
            "reName" to replierName,
            "date" to timeStr,
            "title" to title,
            "reMessage" to message,
        )
        val content = FreeMarkerUtils.parseData("mail-includeHtml.ftlh", dataModel)
        MailUtil.send(nextMailAccount(), listOf(receiverEmail), subject, content, true)
    }
}
