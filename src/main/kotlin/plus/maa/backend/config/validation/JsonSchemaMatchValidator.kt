package plus.maa.backend.config.validation

import com.fasterxml.jackson.core.JsonLocation
import com.fasterxml.jackson.core.JsonProcessingException
import com.networknt.schema.InputFormat
import com.networknt.schema.JsonSchema
import com.networknt.schema.JsonSchemaFactory
import com.networknt.schema.SchemaLocation
import com.networknt.schema.SpecVersion
import jakarta.validation.ConstraintValidator
import jakarta.validation.ConstraintValidatorContext

class JsonSchemaMatchValidator : ConstraintValidator<JsonSchemaMatch, String> {
    private lateinit var schema: String
    override fun initialize(constraintAnnotation: JsonSchemaMatch) {
        super.initialize(constraintAnnotation)
        schema = constraintAnnotation.schema
    }

    override fun isValid(text: String?, ctx: ConstraintValidatorContext): Boolean {
        if (text == null) return true
        val validator = validators[schema] ?: return false
        // content 不是合法 JSON 时，网络层校验器抛出含糊的 "Invalid input"，
        // 其 cause 是带行/列/原因的 Jackson JsonParseException，这里展开成友好报错并转为 400
        val assertions = try {
            validator.validate(text, InputFormat.JSON)
        } catch (e: Exception) {
            ctx.disableDefaultConstraintViolation()
            ctx.buildConstraintViolationWithTemplate("作业内容不是合法的 JSON: ${friendlyParseError(e)}")
                .addConstraintViolation()
            return false
        }
        if (assertions.isEmpty()) {
            return true
        } else {
            ctx.disableDefaultConstraintViolation()
            ctx.buildConstraintViolationWithTemplate(assertions.joinToString("\n") { it.message })
                .addConstraintViolation()
            return false
        }
    }

    companion object {
        const val COPILOT_SCHEMA_JSON = "static/templates/maa-copilot-schema.json"
        const val COPILOT_VIDEO_SCHEMA_JSON = "static/templates/maa-copilot-video-schema.json"
        val validators = mapOf(
            loadSchema(COPILOT_SCHEMA_JSON),
            loadSchema(COPILOT_VIDEO_SCHEMA_JSON),
        )

        @Suppress("SameParameterValue")
        private fun loadSchema(path: String): Pair<String, JsonSchema> {
            val jsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            val schema = jsonSchemaFactory.getSchema(SchemaLocation.of("classpath:$path"))
            return path to schema
        }

        /**
         * 从异常链中找出 Jackson 的 [JsonProcessingException]，返回带行/列与原因的友好信息；
         * 找不到时回退到原始 message。
         */
        private fun friendlyParseError(e: Throwable): String {
            var cur: Throwable? = e
            while (cur != null) {
                if (cur is JsonProcessingException) {
                    val reason = cur.originalMessage?.trim()?.takeIf { it.isNotEmpty() }
                        ?: cur.message ?: "无法解析"
                    val loc: JsonLocation? = cur.location
                    return if (loc != null) "第 ${loc.lineNr} 行 第 ${loc.columnNr} 列: $reason" else reason
                }
                cur = cur.cause
            }
            return e.message ?: "无法解析"
        }
    }
}