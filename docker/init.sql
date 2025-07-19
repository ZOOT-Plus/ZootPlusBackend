CREATE DATABASE zoot;
CREATE USER zoot WITH PASSWORD '1234';
GRANT ALL PRIVILEGES ON DATABASE zoot TO zoot;

\c zoot
GRANT USAGE, CREATE ON SCHEMA public TO zoot;
GRANT ALL PRIVILEGES ON ALL TABLES IN SCHEMA public TO zoot;
GRANT ALL PRIVILEGES ON ALL SEQUENCES IN SCHEMA public TO zoot;
GRANT ALL PRIVILEGES ON ALL FUNCTIONS IN SCHEMA public TO zoot;

ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON TABLES TO zoot;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON SEQUENCES TO zoot;
ALTER DEFAULT PRIVILEGES IN SCHEMA public GRANT ALL PRIVILEGES ON FUNCTIONS TO zoot;

create table if not exists "user"
(
  user_id         text         not null
    primary key,
  user_name       text         not null,
  email           text         not null,
  password        text         not null,
  status          integer      not null,
  pwd_update_time timestamp(3) not null,
  following_count integer      not null,
  fans_count      integer      not null
);

create index if not exists idx_user_user_name
  on "user" (user_name);

create unique index if not exists idx_user_user_email
  on "user" (email);

create table if not exists copilot
(
  copilot_id        serial
    primary key,
  stage_name        text                         not null,
  uploader_id       text                         not null,
  views             bigint                       not null,
  rating_level      integer                      not null,
  rating_ratio      double precision             not null,
  like_count        bigint                       not null,
  dislike_count     bigint                       not null,
  hot_score         double precision             not null,
  title             text                         not null,
  details           text,
  first_upload_time timestamp(3),
  upload_time       timestamp(3),
  content           text                         not null,
  status            text default 'PUBLIC'::text  not null,
  comment_status    text default 'ENABLED'::text not null,
  delete            boolean,
  delete_time       timestamp(3),
  notification      boolean
);

comment on column copilot.copilot_id is '自增数字ID';
comment on column copilot.stage_name is '关卡名';
comment on column copilot.uploader_id is '上传者id';
comment on column copilot.views is '查看次数';
comment on column copilot.rating_level is '评级';
comment on column copilot.rating_ratio is '评级比率 十分之一代表半星';
comment on column copilot.hot_score is '热度';
comment on column copilot.title is '指定干员@Cascade(["copilot_id"], ["copilot_id"])var opers: List<OperatorEntity>?,文档字段，用于搜索，提取到Copilot类型上';
comment on column copilot.first_upload_time is '首次上传时间';
comment on column copilot.upload_time is '更新时间';
comment on column copilot.content is '原始数据';
comment on column copilot.delete is '作业状态，后端默认设置为公开以兼容历史逻辑[plus.maa.backend.service.model.CopilotSetStatus]';

create index if not exists idx_copilot_stage_name
  on copilot (stage_name);

create index if not exists idx_copilot_view
  on copilot (views);

create index if not exists idx_hot_score
  on copilot (hot_score);

create table if not exists copilot_operator
(
  id         serial
    primary key,
  copilot_id bigint not null,
  name       text   not null
);

alter table copilot_operator
  owner to zoot;

create index if not exists idx_operator_copilot_id
  on copilot_operator (copilot_id);

create index if not exists idx_operator_name
  on copilot_operator (name);

