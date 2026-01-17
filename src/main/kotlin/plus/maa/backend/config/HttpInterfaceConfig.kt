package plus.maa.backend.config

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders
import org.springframework.http.client.reactive.ReactorClientHttpConnector
import org.springframework.http.codec.ClientCodecConfigurer
import org.springframework.http.codec.json.Jackson2JsonDecoder
import org.springframework.http.codec.json.Jackson2JsonEncoder
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder
import org.springframework.web.reactive.function.client.ExchangeStrategies
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.support.WebClientAdapter
import org.springframework.web.service.invoker.HttpServiceProxyFactory
import org.springframework.web.service.invoker.createClient
import plus.maa.backend.repository.GithubRepository
import reactor.netty.http.client.HttpClient
import java.time.Duration

@Configuration
class HttpInterfaceConfig {
    @Bean
    fun githubRepository(): GithubRepository {
        val mapper = Jackson2ObjectMapperBuilder.json()
            .propertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
            .build<ObjectMapper>()

        val client = WebClient.builder()
            .baseUrl("https://api.github.com")
            .exchangeStrategies(
                ExchangeStrategies
                    .builder()
                    .codecs { codecs: ClientCodecConfigurer ->
                        codecs.defaultCodecs()
                            .jackson2JsonEncoder(Jackson2JsonEncoder(mapper))
                        codecs.defaultCodecs()
                            .jackson2JsonDecoder(Jackson2JsonDecoder(mapper))
                        // 最大 20MB
                        codecs.defaultCodecs().maxInMemorySize(20 * 1024 * 1024)
                    }
                    .build(),
            )
            .clientConnector(
                ReactorClientHttpConnector(
                    HttpClient.create().proxyWithSystemProperties()
                        .responseTimeout(Duration.ofSeconds(30))
                )
            )
            .defaultHeaders { headers: HttpHeaders ->
                headers.add("Accept", "application/vnd.github+json")
                headers.add("X-GitHub-Api-Version", "2022-11-28")
            }
            .build()
        return HttpServiceProxyFactory.builderFor(WebClientAdapter.create(client))
            .build()
            .createClient<GithubRepository>()
    }
}
