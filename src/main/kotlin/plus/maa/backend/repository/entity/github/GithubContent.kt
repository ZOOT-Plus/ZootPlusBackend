package plus.maa.backend.repository.entity.github

import kotlinx.serialization.Serializable

/**
 * @author dragove
 * created on 2022/12/23
 */
@Serializable
data class GithubContent(
    // 文件名
    val name: String,
    // 路径
    val path: String,
    val sha: String,
    // 文件大小(Byte)
    val size: Long,
    // 路径类型 file-文件 dir-目录
    val type: String,
    // 下载地址
    val downloadUrl: String?,
    // 访问地址
    val htmlUrl: String,
    // 对应commit地址
    val gitUrl: String,
) {
    val isFile: Boolean
        /**
         * 仿照File类，判断是否问类型
         *
         * @return 如果是文件类型，则返回 true，目录类型则返回 false
         */
        get() = type == "file"
}
