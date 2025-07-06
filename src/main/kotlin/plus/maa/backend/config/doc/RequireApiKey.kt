package plus.maa.backend.config.doc

import io.swagger.v3.oas.annotations.security.SecurityRequirement
import java.lang.annotation.Inherited

/**
 * 指示需要 API key 认证
 */
@Target(
    AnnotationTarget.FUNCTION,
    AnnotationTarget.PROPERTY_GETTER,
    AnnotationTarget.PROPERTY_SETTER,
    AnnotationTarget.CLASS,
    AnnotationTarget.ANNOTATION_CLASS,
)
@Retention(
    AnnotationRetention.RUNTIME,
)
@Inherited
@SecurityRequirement(name = SpringDocConfig.SECURITY_SCHEME_API_KEY)
annotation class RequireApiKey
