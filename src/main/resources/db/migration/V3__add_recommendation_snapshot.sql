CREATE TABLE IF NOT EXISTS recommendation_snapshot (
    id smallint PRIMARY KEY CHECK (id = 1),
    format_version integer NOT NULL,
    generated_at timestamp(6) NOT NULL,
    evidence jsonb NOT NULL
);

COMMENT ON TABLE recommendation_snapshot IS 'Latest complete daily public recommendation evidence';
