package plus.maa.backend.config.validation

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
        val assertions = validator.validate(text, InputFormat.JSON)
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
        val validators = mapOf(
            loadSchema(COPILOT_SCHEMA_JSON),
        )


        @Suppress("SameParameterValue")
        private fun loadSchema(path: String): Pair<String, JsonSchema> {
            val jsonSchemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012)
            val schema = jsonSchemaFactory.getSchema(SchemaLocation.of("classpath:$path"))
            return path to schema
        }
    }
}
