package plus.maa.backend.config.security

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.authentication.AuthenticationManager
import org.springframework.security.config.Customizer
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configurers.CsrfConfigurer
import org.springframework.security.config.annotation.web.configurers.ExceptionHandlingConfigurer
import org.springframework.security.config.annotation.web.configurers.SessionManagementConfigurer
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.web.SecurityFilterChain
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter

/**
 * @author AnselYuki
 */
@Configuration
class SecurityConfig(
    private val authenticationConfiguration: AuthenticationConfiguration,
    private val jwtAuthenticationTokenFilter: JwtAuthenticationTokenFilter,
    private val authenticationEntryPoint: AuthenticationEntryPointImpl,
    private val accessDeniedHandler: AccessDeniedHandlerImpl,
) {
    @Bean
    fun passwordEncoder() = BCryptPasswordEncoder()

    @Bean
    @Throws(Exception::class)
    fun authenticationManager(): AuthenticationManager = authenticationConfiguration.authenticationManager

    @Bean
    @Throws(Exception::class)
    fun filterChain(http: HttpSecurity): SecurityFilterChain {
        // 关闭CSRF,设置无状态连接
        http
            .csrf { obj: CsrfConfigurer<HttpSecurity> -> obj.disable() } // 不通过Session获取SecurityContext
            .sessionManagement { sessionManagement: SessionManagementConfigurer<HttpSecurity?> ->
                sessionManagement.sessionCreationPolicy(
                    SessionCreationPolicy.STATELESS,
                )
            }

        // 允许匿名访问的接口，如果是测试想要方便点就把这段全注释掉
        http.authorizeHttpRequests { authorize ->
            authorize
                .requestMatchers(*URL_WHITELIST)
                .anonymous()
                .requestMatchers(*URL_PERMIT_ALL)
                .permitAll() // 权限 0 未激活 1 激活  等等.. (拥有权限1必然拥有权限0 拥有权限2必然拥有权限1、0)
                // 指定接口需要指定权限才能访问 如果不开启RBAC注释掉这一段即可
                .requestMatchers(*URL_AUTHENTICATION_1)
                .hasAuthority("1") // 此处用于管理员操作接口
                .requestMatchers(*URL_AUTHENTICATION_2)
                .hasAuthority("2")
                .anyRequest()
                .authenticated()
        }
        // 添加过滤器
        http.addFilterBefore(jwtAuthenticationTokenFilter, UsernamePasswordAuthenticationFilter::class.java)

        // 配置异常处理器，处理认证失败的JSON响应
        http.exceptionHandling { exceptionHandling: ExceptionHandlingConfigurer<HttpSecurity?> ->
            exceptionHandling
                .authenticationEntryPoint(
                    authenticationEntryPoint,
                ).accessDeniedHandler(accessDeniedHandler)
        }

        // 开启跨域请求
        http.cors(Customizer.withDefaults())
        return http.build()
    }

    companion object {
        /**
         * 添加放行接口在此处
         */
        private val URL_WHITELIST =
            arrayOf(
                "/user/login",
                "/user/register",
                "/user/sendRegistrationToken",
            )

        private val URL_PERMIT_ALL =
            arrayOf(
                "/",
                "/error",
                "/version",
                "/cache/stat",
                "/user/info",
                "/user/search",
                "/user/activateAccount",
                "/user/password/reset_request",
                "/user/password/reset",
                "/user/refresh",
                "/swagger-ui.html",
                "/v3/api-docs/**",
                "/swagger-ui/**",
                "/arknights/level",
                "/copilot/query",
                "/set/query",
                "/set/get",
                "/copilot/get/**",
                "/copilot/rating",
                "/comments/query",
                "/comments/rating",
                "/copilot/ban",
                "/webhook/**",
            )

        // 添加需要权限1才能访问的接口
        private val URL_AUTHENTICATION_1 =
            arrayOf(
                "/copilot/delete",
                "/copilot/update",
                "/copilot/upload",
                "/copilot/status",
                "/comments/add",
                "/comments/delete",
                "/comments/status",
            )

        private val URL_AUTHENTICATION_2 =
            arrayOf(
                "/file/download/**",
                "/file/download/",
                "/file/disable",
                "/file/enable",
                "/file/upload_ability",
            )
    }
}
