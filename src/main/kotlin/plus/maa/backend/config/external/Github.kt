package plus.maa.backend.config.external

data class Github(
    /**
     * GitHub api token, 从 [tokens](https://github.com/settings/tokens) 获取
     */
    var token: String = "github_pat_xxx",
    /**
     * maa 主仓库和分支
     */
    var repoAndBranch: String = "MaaAssistantArknights/MaaResource/main",
    /**
     * 地图数据所在路径（相对于分支内容）
     */
    var tilePosPath: String = "resource/Arknights-Tile-Pos",
)
