CREATE TABLE IF NOT EXISTS recommendation_snapshot (
    id smallint PRIMARY KEY CHECK (id = 1),
    format_version integer NOT NULL,
    generated_at timestamp(6) NOT NULL,
    evidence jsonb NOT NULL
);

COMMENT ON TABLE recommendation_snapshot IS 'Latest complete daily public recommendation evidence';

-- 重建时批量取每个作业的最近点赞时间：评分表 1284 万行，原索引要回表，加这个部分索引后 18.6s → 1.4s。
-- 不用 CONCURRENTLY：迁移期间有 idle in transaction 连接会永久等待。
CREATE INDEX IF NOT EXISTS idx_rating_latest_like ON rating (key, rate_time)
    WHERE type = 'COPILOT' AND rating = 'LIKE';
