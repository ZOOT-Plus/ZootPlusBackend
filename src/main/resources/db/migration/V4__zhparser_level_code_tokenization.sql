-- 关卡号归一化：给每段 ASCII 字母数字两侧补空格，让 `H17` / `S3` / `IW-EX-1` 这类
-- 关卡号无论前后是中文、括号还是连字符都切成同一个词元（见 CopilotRepository.ASCII_RUN_PAD）。
--
-- 起因：SCWS 的切词随上下文漂移——裸 'H17' 切成 'h17'，而 '[H17-4]' 切成 'h' + '17' + '4'，
-- 两者永不相等，于是迁移后「搜 H17」匹配不到标题 '[H17-4]'；'S3磨难' 里紧邻中文的 ASCII
-- 段甚至会被整段丢弃。
--
-- V3 已建过不带归一化的索引。表达式变了必须删掉重建（REINDEX 不够）：查询条件与索引表达式
-- 不再匹配时规划器不会使用该索引，搜索会退化为全表顺序扫描。
-- 若该库的索引已由 DBA 按新表达式预建（CREATE INDEX CONCURRENTLY），下面的 IF NOT EXISTS 会跳过。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_indexes
        WHERE schemaname = current_schema()
          AND indexname = 'idx_copilot_document_tsv'
          AND indexdef NOT LIKE '%regexp_replace%'
    ) THEN
        DROP INDEX idx_copilot_document_tsv;
    END IF;
END
$$;

DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_ts_config
        WHERE cfgname = 'chinese_zh'
          AND cfgnamespace = 'public'::regnamespace
    ) THEN
        -- 并行 maintenance worker 里 zhparser 的 extra_dicts 不生效（会打出
        --   WARNING: parameter "zhparser.extra_dicts" cannot be set during a parallel operation），
        -- 索引会按「无自定义词典」的口径切词，与查询时的口径不一致 → 词典长词（单核/阿米娅）漏 posting，
        -- 表现为「一部分能搜到、另一部分静默漏召回」。建索引前必须关掉并行。
        PERFORM set_config('max_parallel_maintenance_workers', '0', true);
        CREATE INDEX IF NOT EXISTS idx_copilot_document_tsv
            ON copilot
            USING gin (
                to_tsvector(
                    'chinese_zh',
                    regexp_replace(
                        coalesce(title, '') || ' ' || coalesce(details, ''),
                        '([A-Za-z0-9]+)',
                        ' \1 ',
                        'g'
                    )
                )
            );
    END IF;
END
$$;
