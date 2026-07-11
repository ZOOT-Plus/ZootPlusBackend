package plus.maa.backend.controller.request.copilot

import kotlinx.serialization.InternalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PolymorphicKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.descriptors.buildSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonEncoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 兼容旧客户端的自定义序列化器：kotlinx 原生 sealed 多态要求请求体必须带 `type` 判别字段，
 * 否则反序列化失败。旧 MAA 客户端上传/更新时不携带 `type`，此处将其默认为 PRTS，
 * 使旧客户端无需改动即可继续作为自动化脚本作业工作。
 *
 * - deserialize: 读 JSON 对象，缺失/为 null 的 `type` 视作 PRTS，再按对应子类型反序列化。
 * - serialize: 输出 `type` 判别字段 + 子类型字段（仅测试/内部回写会用到）。
 */
@OptIn(InternalSerializationApi::class)
object CopilotCUDRequestSerializer : KSerializer<CopilotCUDRequest> {
    override val descriptor: SerialDescriptor =
        buildSerialDescriptor("plus.maa.backend.controller.request.copilot.CopilotCUDRequest", PolymorphicKind.SEALED)

    override fun deserialize(decoder: Decoder): CopilotCUDRequest {
        val jsonDecoder = decoder as? JsonDecoder
            ?: throw SerializationException("CopilotCUDRequest 仅支持从 JSON 反序列化")
        val element: JsonElement = jsonDecoder.decodeJsonElement()
        val obj = element as? JsonObject
            ?: throw SerializationException("CopilotCUDRequest 请求体必须是 JSON 对象")
        val typeElement = obj["type"]
        val type = when {
            typeElement == null || typeElement is JsonNull -> "PRTS" // 兼容：未传 type 默认 PRTS
            else -> typeElement.jsonPrimitive.content
        }
        val subSerializer = when (type) {
            "PRTS" -> PrtsCUDRequest.serializer()
            "VIDEO" -> VideoCUDRequest.serializer()
            else -> throw SerializationException("未知的作业类型: $type")
        }
        // 子类型序列化器忽略多余的 type 字段 (ignoreUnknownKeys)
        return jsonDecoder.json.decodeFromJsonElement(subSerializer, obj)
    }

    override fun serialize(encoder: Encoder, value: CopilotCUDRequest) {
        val jsonEncoder = encoder as? JsonEncoder
            ?: throw SerializationException("CopilotCUDRequest 仅支持序列化为 JSON")
        val (subObj, typeName) = when (value) {
            is PrtsCUDRequest ->
                jsonEncoder.json.encodeToJsonElement(PrtsCUDRequest.serializer(), value).jsonObject to "PRTS"
            is VideoCUDRequest ->
                jsonEncoder.json.encodeToJsonElement(VideoCUDRequest.serializer(), value).jsonObject to "VIDEO"
        }
        val merged = buildJsonObject {
            put("type", JsonPrimitive(typeName))
            subObj.forEach { (k, v) -> if (k != "type") put(k, v) }
        }
        jsonEncoder.encodeJsonElement(merged)
    }
}