package plus.maa.backend.config.doc

import cn.hutool.core.text.NamingCase
import io.swagger.v3.oas.models.Components
import io.swagger.v3.oas.models.ExternalDocumentation
import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.info.License
import io.swagger.v3.oas.models.media.Schema
import io.swagger.v3.oas.models.security.SecurityScheme
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import plus.maa.backend.config.external.MaaCopilotProperties

/**
 * @author AnselYuki
 */
@Configuration
class SpringDocConfig(
    properties: MaaCopilotProperties,
) {
    private val info = properties.info
    private val jwt = properties.jwt

    @Bean
    fun emergencyLogistics(): OpenAPI = OpenAPI().apply {
        info(
            Info().apply {
                title(this@SpringDocConfig.info.title)
                description(this@SpringDocConfig.info.description)
                version(this@SpringDocConfig.info.version)
                license(
                    License().apply {
                        name("GNU Affero General Public License v3.0")
                        url("https://www.gnu.org/licenses/agpl-3.0.html")
                    },
                )
            },
        )
        externalDocs(
            ExternalDocumentation().apply {
                description("GitHub repo")
                url("https://github.com/ZOOT-Plus/ZootPlusBackend")
            },
        )
        components(
            Components().apply {
                addSecuritySchemes(
                    SECURITY_SCHEME_JWT,
                    SecurityScheme().apply {
                        type(SecurityScheme.Type.HTTP)
                        scheme("bearer")
                        `in`(SecurityScheme.In.HEADER)
                        name(jwt.header)
                        val s =
                            "JWT Authorization header using the Bearer scheme. Raw head example: " +
                                "\"${jwt.header}: Bearer {token}\""
                        description(s)
                    },
                )
                addSecuritySchemes(
                    SECURITY_SCHEME_API_KEY,
                    SecurityScheme().apply {
                        type(SecurityScheme.Type.APIKEY)
                        name("X-API-Key")
                        `in`(SecurityScheme.In.HEADER)
                        description("X-API-Key: your API Key")
                    },
                )
            },
        )
    }

    /**
     * Drop the [data] property from the bare [MaaResult] schema.
     *
     * Kotlin's [MaaResult<Nothing>] (used for error responses) causes SpringDoc
     * to emit `"type": "null"` for [data].  The typescript-fetch generator does
     * not support the Null data type and would otherwise produce broken imports
     * for a non-existent `Null` model.
     *
     * Only the bare MaaResult (used by GET /) is affected; typed variants like
     * MaaResultCopilotInfo have their own schemas and are left untouched.
     */
    @Bean
    fun removeMaaResultNullData(): OpenApiCustomizer = OpenApiCustomizer { api ->
        val schemas = api.components?.schemas ?: return@OpenApiCustomizer
        schemas["MaaResult"]?.properties?.remove("data")
    }

    /**
     * Convert all schema property names from camelCase to snake_case so that
     * the generated OpenAPI spec matches the runtime JSON naming strategy.
     *
     * SpringDoc introspects Kotlin classes via Jackson (Swagger-core), which
     * defaults to camelCase. But the runtime uses kotlinx-serialization with
     * JsonNamingStrategy.SnakeCase, so the actual API returns snake_case JSON.
     *
     * This customizer applies the same camelCase-to-snake_case conversion that
     * kotlinx-serialization uses internally, so the OpenAPI spec -- and the
     * client code generated from it -- matches what the API actually serves.
     */
    @Bean
    fun enforceSnakeCasePropertyNaming(): OpenApiCustomizer = OpenApiCustomizer { api ->
        val schemas = api.components?.schemas ?: return@OpenApiCustomizer
        schemas.forEach { (_, schema) ->
            renamePropertiesToSnakeCase(schema)
        }
    }

    companion object {
        const val SECURITY_SCHEME_JWT: String = "Jwt"
        const val SECURITY_SCHEME_API_KEY: String = "API_Key"

        /**
         * Recursively rename properties of a schema and its nested schemas
         * from camelCase to snake_case.
         */
        private fun renamePropertiesToSnakeCase(schema: Schema<*>) {
            schema.properties?.let { props ->
                val renamed = LinkedHashMap<String, Schema<*>>()
                props.forEach { (name, propSchema) ->
                    renamed[NamingCase.toUnderlineCase(name)] = propSchema
                    renamePropertiesToSnakeCase(propSchema)
                }
                schema.properties = renamed
            }

            schema.items?.let { renamePropertiesToSnakeCase(it) }

            schema.oneOf?.forEach { renamePropertiesToSnakeCase(it) }
            schema.anyOf?.forEach { renamePropertiesToSnakeCase(it) }
            schema.allOf?.forEach { renamePropertiesToSnakeCase(it) }
        }

    }
}
