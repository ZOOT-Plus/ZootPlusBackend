package plus.maa.backend.config.external

import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.NestedConfigurationProperty
import org.springframework.stereotype.Component

@Component
@ConfigurationProperties("maa-copilot")
data class MaaCopilotProperties(
    @NestedConfigurationProperty
    var jwt: Jwt = Jwt(),
    @NestedConfigurationProperty
    var github: Github = Github(),
    @NestedConfigurationProperty
    var info: Info = Info(),
    @NestedConfigurationProperty
    var vcode: Vcode = Vcode(),
    @NestedConfigurationProperty
    var cache: Cache = Cache(),
    @NestedConfigurationProperty
    var taskCron: TaskCron = TaskCron(),
    @NestedConfigurationProperty
    var backup: CopilotBackup = CopilotBackup(),
    @NestedConfigurationProperty
    var mail: Mail = Mail(),
    @NestedConfigurationProperty
    var sensitiveWord: SensitiveWord = SensitiveWord(),
    @NestedConfigurationProperty
    var copilot: Copilot = Copilot(),
    @NestedConfigurationProperty
    var segmentInfo: SegmentInfo = SegmentInfo(),
    @NestedConfigurationProperty
    var webhook: Webhook = Webhook(),
)
