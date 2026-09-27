package plus.maa.backend.repository

import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import plus.maa.backend.config.external.MaaCopilotProperties

/**
 * GitHub API 连通性检查：需要真实网络与可用的 token 配置（占位 token 会退化为匿名，
 * 受 60 次/小时的速率限制），因此在 CI 中被排除（见 .github/workflows/test.yml）。
 */
@Tag("integration")
@SpringBootTest
class GithubRepositoryTest(
    @Autowired val repository: GithubRepository,
    @Autowired val properties: MaaCopilotProperties,
) {
    @Test
    fun testGetTrees() {
        repository.getTrees(properties.github.token, "d989739981db071e80df1c66e473c729b50e8073")
    }

    @Test
    fun testGetCommits() {
        val commits = repository.getCommits(properties.github.token)
        check(commits.isNotEmpty())
    }

    @Test
    fun testGetContents() {
        val contents = repository.getContents(properties.github.token, "")
        check(contents.isNotEmpty())
    }
}
