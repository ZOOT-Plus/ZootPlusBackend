package plus.maa.backend.config.security

import org.springframework.http.HttpStatus
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.Authentication
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import org.springframework.web.server.ResponseStatusException
import plus.maa.backend.common.utils.IpUtil
import plus.maa.backend.service.jwt.JwtAuthToken
import plus.maa.backend.service.model.LoginUser
import kotlin.properties.ReadOnlyProperty

/**
 * Auth 助手，统一 auth 的设置和获取
 */
@Component
class AuthenticationHelper {
    /**
     * 设置当前 auth， 是 SecurityContextHolder.getContext().setAuthentication(authentication) 的集中调用
     *
     * @param authentication 当前的 auth
     */
    fun setAuthentication(authentication: Authentication?) {
        SecurityContextHolder.getContext().authentication = authentication
    }

    /**
     * 要求用户 id ，否则抛出异常
     *
     * @return 已经验证的用户 id
     * @throws ResponseStatusException 用户未通过验证
     */
    @Throws(ResponseStatusException::class)
    fun requireUserId(): String = obtainUserId() ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    val userId by readonly {
        requireUserId().toLong()
    }

    fun <T> readonly(block: () -> T): ReadOnlyProperty<Any?, T> = ReadOnlyProperty { _, _ -> block() }

    /**
     * 获取用户 id
     *
     * @return 用户 id，如未验证则返回 null
     */
    fun obtainUserId(): String? {
        val auth = SecurityContextHolder.getContext().authentication ?: return null
        if (auth is UsernamePasswordAuthenticationToken) {
            val user = auth.principal as? LoginUser
            return user?.userId
        } else if (auth is JwtAuthToken) {
            return auth.subject
        }
        return null
    }

    /**
     * 获取已验证用户 id 或者未验证用户 ip 地址。在 HTTP request 之外调用该方法获取 ip 会抛出 [IllegalStateException]
     *
     * @return 用户 id 或者 ip 地址
     */
    fun obtainUserIdOrIpAddress(): String {
        val id = obtainUserId()
        if (id != null) return id

        val request = (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
        return checkNotNull(request).run(IpUtil::getIpAddr)
    }
}
