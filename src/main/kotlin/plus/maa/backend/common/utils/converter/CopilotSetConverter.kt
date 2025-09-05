package plus.maa.backend.common.utils.converter

import org.mapstruct.Mapper
import org.mapstruct.Mapping
import plus.maa.backend.controller.request.copilotset.CopilotSetCreateReq
import plus.maa.backend.controller.response.copilotset.CopilotSetListRes
import plus.maa.backend.controller.response.copilotset.CopilotSetRes
import plus.maa.backend.repository.entity.CopilotSet
import java.time.LocalDateTime

/**
 * @author dragove
 * create on 2024-01-01
 */
@Mapper(
    componentModel = "spring",
    imports = [LocalDateTime::class],
)
interface CopilotSetConverter {
    @Mapping(target = "delete", ignore = true)
    @Mapping(target = "deleteTime", ignore = true)
    @Mapping(target = "copilotIds", expression = "java(createReq.distinctIdsAndCheck())")
    @Mapping(target = "createTime", expression = "java(LocalDateTime.now())")
    @Mapping(target = "updateTime", expression = "java(LocalDateTime.now())")
    @Mapping(target = "views", constant = "0L")
    @Mapping(target = "hotScore", constant = "0.0")
    fun convert(createReq: CopilotSetCreateReq, id: Long, creatorId: String): CopilotSet

    fun convert(copilotSet: CopilotSet, creator: String): CopilotSetListRes

    fun convertDetail(copilotSet: CopilotSet, creator: String): CopilotSetRes
}
