package plus.maa.backend.repository

import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.service.annotation.GetExchange
import plus.maa.backend.repository.entity.github.GithubCommit
import plus.maa.backend.repository.entity.github.GithubContent
import plus.maa.backend.repository.entity.github.GithubTrees

/**
 * @author dragove
 * created on 2022/12/23
 */
interface GithubRepository {
    /**
     * api doc: [git trees api](https://docs.github.com/en/rest/git/trees?apiVersion=2022-11-28#get-a-tree)
     */
    @GetExchange(value = "/repos/MaaAssistantArknights/MaaAssistantArknights/git/trees/{sha}")
    fun getTrees(@RequestHeader("Authorization") token: String, @PathVariable("sha") sha: String): GithubTrees

    @GetExchange(value = "/repos/MaaAssistantArknights/MaaAssistantArknights/commits")
    fun getCommits(@RequestHeader("Authorization") token: String): List<GithubCommit>

    /**
     * 指定路径在 `until` 之前（含）的提交，**按时间倒序**，最新的一条在 `[0]`。
     *
     * 供 [plus.maa.backend.service.level.ArkLevelService.backfillUpdatedAt] 定位「3 个月前」的历史 tree：
     * 配合 [getTrees] 逐级下钻即可拿到那一刻的地图文件清单，无需遍历整个提交历史。
     *
     * @param path 仓库内路径（目录亦可，GitHub 会返回改动过该目录下任意文件的提交）
     * @param until ISO-8601 时间戳（如 `2026-06-25T14:30:00Z`），GitHub 侧按提交时间过滤
     */
    @GetExchange(value = "/repos/MaaAssistantArknights/MaaAssistantArknights/commits")
    fun getCommitsOfPath(
        @RequestHeader("Authorization") token: String,
        @RequestParam("path") path: String,
        @RequestParam("until") until: String,
    ): List<GithubCommit>

    @GetExchange(value = "/repos/MaaAssistantArknights/MaaAssistantArknights/contents/{path}")
    fun getContents(@RequestHeader("Authorization") token: String, @PathVariable("path") path: String): List<GithubContent>
}
