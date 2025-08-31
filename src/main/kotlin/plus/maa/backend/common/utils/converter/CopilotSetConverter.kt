package plus.maa.backend.common.utils.converter

import org.mapstruct.Mapper
import plus.maa.backend.controller.response.copilotset.CopilotSetListRes
import plus.maa.backend.controller.response.copilotset.CopilotSetRes
import plus.maa.backend.repository.entity.CopilotSetEntity
import plus.maa.backend.repository.entity.copilotIdsList
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
    // 旧的CopilotSet相关方法已废弃，转为手动处理

    // 手动转换方法用于处理CopilotSetEntity
    fun convert(copilotSetEntity: CopilotSetEntity, creator: String): CopilotSetListRes {
        return CopilotSetListRes(
            id = copilotSetEntity.id,
            name = copilotSetEntity.name,
            description = copilotSetEntity.description,
            creatorId = copilotSetEntity.creatorId,
            creator = creator,
            status = copilotSetEntity.status,
            createTime = copilotSetEntity.createTime,
            updateTime = copilotSetEntity.updateTime,
            copilotIds = copilotSetEntity.copilotIdsList,
        )
    }

    fun convertDetail(copilotSetEntity: CopilotSetEntity, creator: String): CopilotSetRes {
        return CopilotSetRes(
            id = copilotSetEntity.id,
            name = copilotSetEntity.name,
            description = copilotSetEntity.description,
            copilotIds = copilotSetEntity.copilotIdsList,
            creatorId = copilotSetEntity.creatorId,
            creator = creator,
            createTime = copilotSetEntity.createTime,
            updateTime = copilotSetEntity.updateTime,
            status = copilotSetEntity.status,
        )
    }
}
