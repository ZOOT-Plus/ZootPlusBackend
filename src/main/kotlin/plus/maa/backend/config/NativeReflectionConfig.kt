package plus.maa.backend.config

import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.context.annotation.Configuration
import plus.maa.backend.controller.request.copilot.CopilotContentDTO
import plus.maa.backend.controller.request.copilot.CopilotDeleteRequest
import plus.maa.backend.controller.request.copilot.PrtsCUDRequest
import plus.maa.backend.controller.request.copilot.PrtsDTO
import plus.maa.backend.controller.request.copilot.VideoCUDRequest
import plus.maa.backend.controller.request.copilot.VideoDTO
import plus.maa.backend.repository.entity.gamedata.ArkActivity
import plus.maa.backend.repository.entity.gamedata.ArkCharacter
import plus.maa.backend.repository.entity.gamedata.ArkStage
import plus.maa.backend.repository.entity.gamedata.ArkTilePos
import plus.maa.backend.repository.entity.gamedata.ArkTilePos.Tile
import plus.maa.backend.repository.entity.gamedata.ArkTower
import plus.maa.backend.repository.entity.gamedata.ArkZone

/**
 * 添加所有需要用到反射的类到此处，用于 native image
 * 等个大佬修缮
 *
 * @author dragove
 * created on 2023/08/18
 */
@Configuration
@RegisterReflectionForBinding(
    ArkActivity::class,
    ArkCharacter::class,
    ArkStage::class,
    ArkTilePos::class,
    Tile::class,
    ArkTower::class,
    ArkZone::class,
    CopilotContentDTO::class,
    PrtsDTO::class,
    VideoDTO::class,
    PrtsCUDRequest::class,
    VideoCUDRequest::class,
    CopilotDeleteRequest::class,
)
class NativeReflectionConfig
