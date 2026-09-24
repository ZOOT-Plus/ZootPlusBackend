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
