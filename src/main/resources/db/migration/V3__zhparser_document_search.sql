-- 安全的 zhparser 兜底初始化：
-- 1. 仅当镜像/服务器提供 zhparser 扩展时才创建扩展；
-- 2. 扩展不存在（如官方 postgres 镜像、embedded PG 测试环境）时整段跳过，不影响其他迁移；
-- 3. 应用连接的数据库账号若无 CREATE EXTENSION 权限，这里会显式失败，避免带病启动。
--
-- 2026-10 未发布前的文件合并：原 V4__zhparser_level_code_tokenization.sql（关卡号归一化索引）
-- 已并入本文件末尾。已应用过拆分前 V3/V4 的本地库需删除 flyway_schema_history 里 version 3/4
-- 两行让 V3 重新应用（幂等），详见 docs/zhparser-migration.md 第 4 节。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'zhparser') THEN
        EXECUTE 'CREATE EXTENSION IF NOT EXISTS zhparser';
    END IF;
END
$$;

-- 复用 abcfy2/zhparser 镜像自带的 chinese_zh 配置；已有库 init 脚本不会执行，
-- 所以这里做一次幂等创建。
-- 映射集合在镜像默认的 a/e/i/l/n/t/v 之外补上 d(副词)/r(代词)/m(数词)：
-- zhparser 共声明 26 种 token type，未映射的类型会被 to_tsvector 静默丢弃，
-- 而作业标题/描述里「非常」「这个」「二」这类成分很常见——尤其 m，
-- 缺了它「精二」只会剩下「精」。
-- 领域词库 arknights.txt 的词性已统一为 n，所以自定义词不依赖 x；
-- 助词(u)/标点(w)/介词(p)/连词(c) 等纯功能词保持不映射，避免无意义词元进入索引。
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_extension WHERE extname = 'zhparser') THEN
        IF NOT EXISTS (
            SELECT 1
            FROM pg_ts_config
            WHERE cfgname = 'chinese_zh'
              AND cfgnamespace = 'public'::regnamespace
        ) THEN
            CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser);
        END IF;

        ALTER TEXT SEARCH CONFIGURATION chinese_zh
            DROP MAPPING IF EXISTS FOR n, v, a, i, e, l, t, d, r, m;

        ALTER TEXT SEARCH CONFIGURATION chinese_zh
            ADD MAPPING FOR n, v, a, i, e, l, t, d, r, m WITH simple;
    END IF;
END
$$;

-- 对 title/details 的 zhparser 表达式建 GIN 索引，表达式含「ASCII 段补空格」归一化。
-- 查询条件必须与这里的表达式完全一致（见 CopilotRepository.COPILOT_DOCUMENT_TSV_EXPR）。
--
-- 归一化：给每段 ASCII 字母数字两侧补空格，让 `H17` / `S3` / `IW-EX-1` 这类关卡号无论前后
-- 是中文、括号还是连字符都切成同一个词元（见 CopilotRepository.ASCII_RUN_PAD）。
-- 起因：SCWS 的切词随上下文漂移——裸 'H17' 切成 'h17'，而 '[H17-4]' 切成 'h' + '17' + '4'，
-- 两者永不相等，于是「搜 H17」匹配不到标题 '[H17-4]'；'S3磨难' 里紧邻中文的 ASCII 段
-- 甚至会被整段丢弃。
--
-- 建索引前必须关掉并行 maintenance worker：zhparser 的 extra_dicts 在并行 worker 里不生效
--   （WARNING: parameter "zhparser.extra_dicts" cannot be set during a parallel operation），
-- 索引会按「无自定义词典」的口径切词，与查询时的口径不一致 → 词典长词（单核/阿米娅）漏 posting，
-- 表现为「一部分能搜到、另一部分静默漏召回」。
--
-- 表达式变化后 REINDEX 不够：查询条件与索引表达式不匹配时规划器不会使用该索引，
-- 搜索会退化为全表顺序扫描，所以先删掉旧的（未归一化）索引再重建。
-- 若该库的索引已由 DBA 按新表达式预建（CREATE INDEX CONCURRENTLY），下面的 IF NOT EXISTS 会跳过。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_ts_config
        WHERE cfgname = 'chinese_zh'
          AND cfgnamespace = 'public'::regnamespace
    ) THEN
        IF EXISTS (
            SELECT 1
            FROM pg_indexes
            WHERE schemaname = current_schema()
              AND indexname = 'idx_copilot_document_tsv'
              AND indexdef NOT LIKE '%regexp_replace%'
        ) THEN
            DROP INDEX idx_copilot_document_tsv;
        END IF;

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
