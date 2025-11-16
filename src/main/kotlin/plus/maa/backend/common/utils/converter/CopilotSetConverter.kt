package plus.maa.backend.common.utils.converter

import org.mapstruct.Mapper
import plus.maa.backend.controller.response.copilotset.CopilotSetListRes
import plus.maa.backend.controller.response.copilotset.CopilotSetRes
import plus.maa.backend.repository.entity.CopilotSetEntity
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

    // 手动转换方法用于处理CopilotSetEntity
    fun convert(copilotSetEntity: CopilotSetEntity, creator: String): CopilotSetListRes {
        return CopilotSetListRes(
            id = copilotSetEntity.id,
            name = copilotSetEntity.name,
            description = copilotSetEntity.description,
            creatorId = copilotSetEntity.creatorId.toString(),
            creator = creator,
            status = copilotSetEntity.status,
            createTime = copilotSetEntity.createTime,
            updateTime = copilotSetEntity.updateTime,
            copilotIds = copilotSetEntity.copilotIds,
        )
    }

    fun convertDetail(copilotSetEntity: CopilotSetEntity, creator: String): CopilotSetRes {
        return CopilotSetRes(
            id = copilotSetEntity.id,
            name = copilotSetEntity.name,
            description = copilotSetEntity.description,
            copilotIds = copilotSetEntity.copilotIds,
            creatorId = copilotSetEntity.creatorId.toString(),
            creator = creator,
            createTime = copilotSetEntity.createTime,
            updateTime = copilotSetEntity.updateTime,
            status = copilotSetEntity.status,
        )
    }
}
