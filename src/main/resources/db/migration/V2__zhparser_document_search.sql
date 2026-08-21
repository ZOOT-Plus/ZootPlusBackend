-- 安全的 zhparser 兜底初始化：
-- 1. 仅当镜像/服务器提供 zhparser 扩展时才创建扩展；
-- 2. 扩展不存在（如官方 postgres 镜像、embedded PG 测试环境）时整段跳过，不影响其他迁移；
-- 3. 应用连接的数据库账号若无 CREATE EXTENSION 权限，这里会显式失败，避免带病启动。

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_available_extensions WHERE name = 'zhparser') THEN
        EXECUTE 'CREATE EXTENSION IF NOT EXISTS zhparser';
    END IF;
END
$$;

-- 复用 abcfy2/zhparser 镜像自带的 chinese_zh 配置；已有库 init 脚本不会执行，
-- 所以这里做一次幂等创建，并确保映射与镜像默认保持一致。
-- 领域词库 arknights.txt 中所有词的词性已统一为 n，
-- 因此只需映射 n/v/a/i/e/l/t，不映射自定义词默认的 x。
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
            DROP MAPPING IF EXISTS FOR n, v, a, i, e, l, t;

        ALTER TEXT SEARCH CONFIGURATION chinese_zh
            ADD MAPPING FOR n, v, a, i, e, l, t WITH simple;
    END IF;
END
$$;

-- 对 title/details 的 zhparser 表达式建 GIN 索引。
-- 查询条件必须与这里的表达式完全一致（见 CopilotRepository.COPILOT_DOCUMENT_TSV_EXPR）。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM pg_ts_config
        WHERE cfgname = 'chinese_zh'
          AND cfgnamespace = 'public'::regnamespace
    ) THEN
        CREATE INDEX IF NOT EXISTS idx_copilot_document_tsv
            ON copilot
            USING gin (
                to_tsvector(
                    'chinese_zh',
                    coalesce(title, '') || ' ' || coalesce(details, '')
                )
            );
    END IF;
END
$$;
