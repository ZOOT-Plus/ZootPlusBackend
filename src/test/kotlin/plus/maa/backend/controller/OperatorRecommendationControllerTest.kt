package plus.maa.backend.controller

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.http.converter.json.KotlinSerializationJsonHttpMessageConverter
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean
import plus.maa.backend.config.SerializationConfig
import plus.maa.backend.config.security.AuthenticationHelper
import plus.maa.backend.service.CopilotService
import plus.maa.backend.service.recommendation.OperatorRecommendationService
import plus.maa.backend.service.recommendation.RecommendationQuery
import plus.maa.backend.service.recommendation.RecommendationResult
import plus.maa.backend.service.recommendation.RecommendationScope

class OperatorRecommendationControllerTest {
    private val recommendations = mockk<OperatorRecommendationService>()
    private lateinit var mvc: MockMvc

    @BeforeEach
    fun setup() {
        val controller =
            CopilotController(mockk<CopilotService>(), mockk<AuthenticationHelper>(), MockHttpServletResponse(), recommendations)
        val validator = LocalValidatorFactoryBean().apply { afterPropertiesSet() }
        mvc = MockMvcBuilders.standaloneSetup(controller)
            .setValidator(validator)
            .setMessageConverters(KotlinSerializationJsonHttpMessageConverter(SerializationConfig().kotlinJson()))
            .build()
        every { recommendations.recommend(any()) } returns RecommendationResult(
            generatedAt = "2026-07-10T12:00:00",
            operationCount = 0,
            familyCount = 0,
            invalidCount = 0,
            categories = emptyList(),
            activities = emptyList(),
            stages = emptyList(),
            recommendations = emptyList(),
        )
    }

    @Test
    fun `query constructor defaults bind and response is serializable`() {
        mvc.perform(get("/copilot/recommendations"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.data.algorithm_version").value(1))
        verify { recommendations.recommend(RecommendationQuery()) }
        mvc.perform(get("/copilot/recommendations").param("scope", "HISTORY").param("days", "0").param("coverage", "0.9"))
            .andExpect(status().isOk)
        verify { recommendations.recommend(RecommendationQuery(scope = RecommendationScope.HISTORY, days = 0, coverage = 0.9)) }
    }

    @Test
    fun `invalid coverage and unbounded windows are rejected before calculation`() {
        mvc.perform(get("/copilot/recommendations").param("coverage", "0.1")).andExpect(status().isBadRequest)
        mvc.perform(get("/copilot/recommendations").param("days", "-1")).andExpect(status().isBadRequest)
        mvc.perform(get("/copilot/recommendations").param("scope", "bad")).andExpect(status().isBadRequest)
        verify(exactly = 0) { recommendations.recommend(any()) }
    }
}
