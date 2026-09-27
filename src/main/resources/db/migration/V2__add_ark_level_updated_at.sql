-- ark_level.updated_at：该行从上游新同步进来的时刻，用于 /arknights/level/v2 的 lite 变体判据
-- （cat_one = '活动关卡' AND updated_at >= now() - interval '3 months'）。
--
-- 刻意不写 default now()：PG 11+ 的 add column ... default 会把存量行一并填成迁移时刻，
-- 于是迁移后 3 个月内全部 2177 行活动关卡都落在 lite 窗口里（实测体积 49.9K gzip 而非 2.2K），
-- 到期当天再断崖式下跌。存量行因此保持 NULL（含义：尚未回填），由 ArkLevelService.backfillUpdatedAt
-- 依据上游 git 历史一次性回填；新行由实体默认值（LocalDateTime.now()）在 INSERT 时写入。
alter table ark_level add column if not exists updated_at timestamp(3);
comment on column ark_level.updated_at is '该行从上游同步进来的时刻，NULL 表示尚未回填';
-- lite 变体的查询谓词是 (cat_one, updated_at) 组合，按此建索引
create index if not exists idx_ark_level_cat_one_updated_at on ark_level (cat_one, updated_at);
