package plus.maa.backend.service.level

import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.test.util.ReflectionTestUtils
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import plus.maa.backend.common.serialization.defaultJson
import plus.maa.backend.config.external.MaaCopilotProperties
import plus.maa.backend.service.recommendation.RecommendationOperator
import plus.maa.backend.service.recommendation.recommendationOperators
import reactor.core.publisher.Mono
import java.time.Duration
import kotlin.test.assertFailsWith

class ArkGameDataHolderTest {
    private fun holder() = ArkGameDataHolder(emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap(), emptyMap())

    private fun service() = ArkLevelService(
        MaaCopilotProperties(),
        mockk(),
        mockk(),
        mockk(),
        defaultJson,
        mockk(),
        mockk(),
        mockk(),
    )

    @Test
    fun `resource tables provide operator identities stars and ordered module types`() = runTest {
        val tables = mapOf(
            "stage_table.json" to """{"stages":{}}""",
            "zone_table.json" to """{"zones":{}}""",
            "activity_table.json" to """{"zoneToActivity":{},"basicInfo":{}}""",
            "climb_tower_table.json" to """{"towers":{}}""",
            "crisis_v2_table.json" to """{"seasonInfoDataMap":{}}""",
            "character_table.json" to """{
                "char_103_angel":{"name":"能天使","profession":"SNIPER","rarity":5},
                "char_285_medic2":{"name":"Lancet-2","profession":"MEDIC","rarity":0},
                "char_002_amiya":{"name":"阿米娅","profession":"CASTER","rarity":4},
                "char_1001_amiya2":{"name":"阿米娅","profession":"WARRIOR","rarity":4},
                "char_512_aprot":{"name":"暮落","profession":"SPECIAL","rarity":4},
                "char_000_npc":{"name":"召唤物","profession":"SNIPER","rarity":0,"subProfessionId":"notchar1"},
                "trap_000_trap":{"name":"陷阱","profession":"TRAP","rarity":0}
            }""",
            "uniequip_table.json" to """{"equipDict":{
                "x":{"charId":"char_103_angel","typeName1":"MAR","typeName2":"X","charEquipOrder":3},
                "original":{"charId":"char_103_angel","typeName1":"ORIGINAL","typeName2":null,"charEquipOrder":0},
                "unknown":{"charId":"char_103_angel","typeName1":"MAR","typeName2":"Z","charEquipOrder":2},
                "y":{"charId":"char_103_angel","typeName1":"MAR","typeName2":"Y","charEquipOrder":1},
                "duplicate":{"charId":"char_103_angel","typeName1":"MAR","typeName2":"X","charEquipOrder":4}
            }}""",
        )
        val client = WebClient.builder().exchangeFunction { request ->
            Mono.just(
                ClientResponse.create(
                    HttpStatus.OK,
                ).header("Content-Type", "application/json").body(tables.getValue(request.url().path.substringAfterLast('/'))).build(),
            )
        }.build()
        val data = ArkGameDataHolder.fetch(client)
        assertEquals("能天使", data.findCharacter("mem_angel")?.name)
        assertEquals(
            listOf(
                RecommendationOperator("char_103_angel", "能天使", "Sniper", 6, listOf(0, 2, null, 1)),
                RecommendationOperator("char_285_medic2", "Lancet-2", "Medic", 1),
                RecommendationOperator("char_002_amiya", "阿米娅", "Caster", 5),
                RecommendationOperator("char_1001_amiya2", "阿米娅", "Warrior", 5),
            ),
            recommendationOperators(data),
        )
    }

    @Test
    fun `game resources share concurrent loads and refresh after one day`() = runTest {
        mockkObject(ArkGameDataHolder)
        try {
            val service = service()
            val first = holder()
            coEvery { ArkGameDataHolder.fetch(any()) } coAnswers {
                delay(10)
                first
            }
            assertEquals(listOf(first, first), listOf(async { service.gameData() }, async { service.gameData() }).awaitAll())
            assertSame(first, service.gameData())
            coVerify(exactly = 1) { ArkGameDataHolder.fetch(any()) }
            ReflectionTestUtils.setField(service, "dataHolderLoadedAt", System.nanoTime() - Duration.ofDays(2).toNanos())
            val updated = holder()
            coEvery { ArkGameDataHolder.fetch(any()) } returns updated
            assertSame(updated, service.gameData())
            assertSame(updated, service.gameData())
            coVerify(exactly = 2) { ArkGameDataHolder.fetch(any()) }
        } finally {
            unmockkObject(ArkGameDataHolder)
        }
    }

    @Test
    fun `failed resource refresh keeps the previous snapshot and cancellation propagates`() = runTest {
        mockkObject(ArkGameDataHolder)
        try {
            val service = service()
            coEvery { ArkGameDataHolder.fetch(any()) } throws IllegalStateException("offline")
            assertFailsWith<IllegalStateException> { service.gameData() }
            val previous = holder()
            coEvery { ArkGameDataHolder.fetch(any()) } returns previous
            assertSame(previous, service.gameData())
            ReflectionTestUtils.setField(service, "dataHolderLoadedAt", System.nanoTime() - Duration.ofDays(2).toNanos())
            coEvery { ArkGameDataHolder.fetch(any()) } throws IllegalStateException("offline")
            assertSame(previous, service.gameData())
            coEvery { ArkGameDataHolder.fetch(any()) } throws CancellationException("cancelled")
            assertFailsWith<CancellationException> { service.gameData() }
        } finally {
            unmockkObject(ArkGameDataHolder)
        }
    }
}
