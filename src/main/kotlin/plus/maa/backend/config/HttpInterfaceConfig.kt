package plus.maa.backend.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.http.codec.json.KotlinSerializationJsonEncoder
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.repository.GithubRepository

@Configuration
class HttpInterfaceConfig {
    @Bean
    fun githubRepository(): GithubRepository {
        val json = defaultJson()

        val client = WebClient.builder()
            .baseUrl("https://api.github.com")
            .exchangeStrategies(
                ExchangeStrategies
                    .builder()
                    .codecs { codecs: ClientCodecConfigurer ->
                        codecs.defaultCodecs()
                            .kotlinSerializationJsonEncoder(KotlinSerializationJsonEncoder(json))
                        // 最大 20MB
                        codecs.defaultCodecs().maxInMemorySize(20 * 1024 * 1024)
                    }
                    .build(),
            )
            .defaultHeaders { headers: HttpHeaders ->
                headers.add("Accept", "application/vnd.github+json")
                headers.add("X-GitHub-Api-Version", "2022-11-28")
            }
            .build()
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client))
            .build()
            .createClient(GithubRepository::class.java)
    }
}
