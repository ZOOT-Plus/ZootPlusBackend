package plus.maa.backend.repository.ktorm

/**
 * 作业标题/描述的 zhparser 检索口径：文档表达式、ASCII 归一化、查询条件收在一处。
 *
 * 与 db/migration/V4__zhparser_document_search.sql 的索引表达式各改一份时不会报错，
 * 只会让规划器放弃索引、静默退化成全表扫描，故 CopilotRepositoryTest 里有一条比对两者文本的测试。
 */
internal object CopilotDocumentSearch {
    /** 沿用 abcfy2/zhparser 镜像自带的配置名。 */
    const val CONFIG = "chinese_zh"

    /**
     * 关卡号归一化：给每段 ASCII 字母数字两侧补空格，使它无论前后是中文、括号还是连字符
     * 都切成同一个词元（`regexp_replace` 是 IMMUTABLE，可用于表达式索引）。
     *
     * SCWS 的切词随上下文漂移：裸 `H17` → `h17`，而 `[H17-4]` → `h` + `17` + `4`，两者永不相等，
     * 于是「搜 H17」匹配不到标题 `[H17-4]`；`S3磨难` 里紧邻中文的 ASCII 段甚至会被整段丢弃。
     */
    const val ASCII_RUN_PAD = "'([A-Za-z0-9]+)', ' \\1 ', 'g'"

    /** 文档侧 tsvector 表达式，必须与 V4 迁移里的 GIN 索引表达式逐字一致。 */
    const val DOCUMENT_TSV =
        "to_tsvector('$CONFIG', regexp_replace(coalesce(title, '') || ' ' || coalesce(details, ''), $ASCII_RUN_PAD))"

    /**
     * 查询侧条件。用 `plainto_tsquery` 而不是 `websearch_to_tsquery`：无空格中文会被 zhparser 切成多个词，
     * websearch 会把它们拼成 `<->` 短语（「阿米娅挂机」匹配不到「阿米娅精二挂机」），
     * 并会把 or/-/引号解释成运算符；plainto 把分词结果按 AND 组合，词出现在任意位置即命中。
     */
    const val QUERY_CONDITION =
        "$DOCUMENT_TSV @@ plainto_tsquery('$CONFIG', regexp_replace(?, $ASCII_RUN_PAD))"

    /** 探测当前库是否存在 [CONFIG]：无 zhparser 的官方镜像、本地开发环境都没有。 */
    const val AVAILABLE_SQL =
        "SELECT EXISTS (SELECT 1 FROM pg_ts_config WHERE cfgname = '$CONFIG' AND cfgnamespace = current_schema()::regnamespace)"

    /**
     * 无 [CONFIG] 时的降级条件：`title`/`details` 的 LIKE 子串匹配，让这类环境仍能搜到东西。
     *
     * 与 FTS 的语义差异：子串匹配而非分词 AND；不区分大小写；%/_ 按字面处理（见 [likePattern]）。
     *
     * 转义字符用 `!` 而不是默认的反斜杠：jdbi 的词法器把「反斜杠 + 引号」当作转义引号，
     * `ESCAPE '\'` 会吞掉紧随其后的 `?` 占位符（实测报 Superfluous positional param）。
     */
    const val FALLBACK_CONDITION =
        "(coalesce(title, '') ILIKE ? ESCAPE '!' OR coalesce(details, '') ILIKE ? ESCAPE '!')"

    /** 降级用的 LIKE 模式串：转义元字符后两侧补 %，与 [FALLBACK_CONDITION] 的 ESCAPE '!' 配套。 */
    fun likePattern(keyword: String): String = "%" + keyword.replace("!", "!!").replace("%", "!%").replace("_", "!_") + "%"
}
