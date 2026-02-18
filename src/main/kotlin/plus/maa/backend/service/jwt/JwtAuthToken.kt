package plus.maa.backend.service.jwt

import org.springframework.security.core.Authentication
import org.springframework.security.core.GrantedAuthority
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.util.StringUtils
import java.time.Instant

/**
 * 基于 JWT 的 AuthToken. 本类实现了 Authentication， 可直接用于 Spring Security
 * 的认证流程
 */
class JwtAuthToken : JwtToken, Authentication {
    private var authenticated = false

    /**
     * 从 jwt 构建 token
     *
     * @param jwt jwt
     * @param key 签名密钥
     * @throws JwtInvalidException jwt 未通过签名验证或不符合要求
     */
    constructor(jwt: String, key: ByteArray) : super(jwt, TYPE, key)

    constructor(
        sub: String,
        jti: String?,
        iat: Instant,
        exp: Instant,
        nbf: Instant,
        authorities: Collection<GrantedAuthority>,
        key: ByteArray,
    ) : super(sub, jti, iat, exp, nbf, TYPE, key) {
        this.authorities = authorities
    }

    override fun getAuthorities(): Collection<GrantedAuthority> {
        val authorityStrings = jwt.payloads.getStr(CLAIM_AUTHORITIES)
        return StringUtils.commaDelimitedListToSet(authorityStrings)
            .map { role: String -> SimpleGrantedAuthority(role) }
    }

    fun setAuthorities(authorities: Collection<GrantedAuthority>) {
        val authorityStrings = authorities.stream().map { obj: GrantedAuthority -> obj.authority }.toList()
        val encodedAuthorities = StringUtils.collectionToCommaDelimitedString(authorityStrings)
        jwt.setPayload(CLAIM_AUTHORITIES, encodedAuthorities)
    }

    /**
     * @return credentials，采用 jwt 的 id
     * @inheritDoc
     */
    override fun getCredentials(): Any = jwtId!!

    override fun getDetails(): Any? = null

    /**
     * @return principal，采用 jwt 的 subject
     * @inheritDoc
     */
    override fun getPrincipal(): Any = subject

    override fun isAuthenticated(): Boolean = this.authenticated

    @Throws(IllegalArgumentException::class)
    override fun setAuthenticated(isAuthenticated: Boolean) {
        this.authenticated = isAuthenticated
    }

    override fun getName(): String = subject

    companion object {
        /**
         * AuthToken 类型值
         */
        const val TYPE: String = "auth"
        private const val CLAIM_AUTHORITIES = "Authorities"
    }
}
