create table if not exists "user"
(
  user_id         bigserial
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

create table if not exists "user_follow"
(
  user_id        bigint,
  follow_user_id bigint       not null,
  updated_at     timestamp(3) not null,
  primary key (user_id, follow_user_id)
);


create table if not exists copilot
(
  copilot_id        bigserial
    primary key,
  stage_name        text                         not null,
  uploader_id       bigint                       not null,
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
  id         bigserial
    primary key,
  copilot_id bigint not null,
  name       text   not null
);


create index if not exists idx_operator_copilot_id
  on copilot_operator (copilot_id);

create index if not exists idx_operator_name
  on copilot_operator (name);

-- 评论区表
create table if not exists comments_area
(
  id              bigserial
    primary key,
  copilot_id      bigint                not null,
  from_comment_id bigint,
  uploader_id     bigint                not null,
  message         text                  not null,
  like_count      bigint  default 0     not null,
  dislike_count   bigint  default 0     not null,
  upload_time     timestamp(3)          not null,
  topping         boolean default false not null,
  delete          boolean default false not null,
  delete_time     timestamp(3),
  main_comment_id bigint,
  notification    boolean default false not null
);

comment on table comments_area is '评论区表';
comment on column comments_area.copilot_id is '关联的作业ID';
comment on column comments_area.from_comment_id is '回复的评论ID';
comment on column comments_area.uploader_id is '评论者用户ID';
comment on column comments_area.message is '评论内容';
comment on column comments_area.like_count is '点赞数';
comment on column comments_area.dislike_count is '点踩数';
comment on column comments_area.upload_time is '评论时间';
comment on column comments_area.topping is '是否置顶';
comment on column comments_area.delete is '是否删除';
comment on column comments_area.delete_time is '删除时间';
comment on column comments_area.main_comment_id is '主评论ID(如果自身为主评论则为null)';
comment on column comments_area.notification is '邮件通知';

create index if not exists idx_comments_copilot_id
  on comments_area (copilot_id);

create index if not exists idx_comments_uploader_id
  on comments_area (uploader_id);

create index if not exists idx_comments_main_comment_id
  on comments_area (main_comment_id);

-- 评分表
create table if not exists rating
(
  id        bigserial
    primary key,
  type      text         not null,
  key       text         not null,
  user_id   text         not null,
  rating    text         not null,
  rate_time timestamp(3) not null
);

comment on table rating is '评分表';
comment on column rating.type is '评级类型 (COPILOT/COMMENT)';
comment on column rating.key is '被评级对象的唯一标识';
comment on column rating.user_id is '评级的用户ID';
comment on column rating.rating is '评级 (Like/Dislike/None)';
comment on column rating.rate_time is '评级时间';

-- 复合唯一索引，一个用户对一个对象只能有一种评级
create unique index if not exists idx_rating_unique
  on rating (type, key, user_id);

create index if not exists idx_rating_user_id
  on rating (user_id);

-- 作业集表
create table if not exists copilot_set
(
  id          bigserial
    primary key,
  name        text                           not null,
  description text                           not null,
  copilot_ids jsonb                          not null,
  creator_id  bigint                         not null,
  create_time timestamp(3)                   not null,
  update_time timestamp(3)                   not null,
  status      text    default 'PUBLIC'::text not null,
  delete      boolean default false          not null
);

comment on table copilot_set is '作业集表';
comment on column copilot_set.name is '作业集名称';
comment on column copilot_set.description is '额外描述';
comment on column copilot_set.copilot_ids is 'JSON格式存储的作业ID列表';
comment on column copilot_set.creator_id is '创建者用户ID';
comment on column copilot_set.create_time is '创建时间';
comment on column copilot_set.update_time is '更新时间';
comment on column copilot_set.status is '状态';
comment on column copilot_set.delete is '是否删除';

create index if not exists idx_copilot_set_creator_id
  on copilot_set (creator_id);

create index if not exists idx_copilot_set_status
  on copilot_set (status);


-- 关卡表
create table if not exists ark_level
(
  id         bigserial
    primary key,
  level_id   text,
  stage_id   text,
  sha        text    not null,
  cat_one    text,
  cat_two    text,
  cat_three  text,
  name       text,
  width      integer not null,
  height     integer not null,
  is_open    boolean,
  close_time timestamp(3)
);

comment on table ark_level is '明日方舟关卡表';
comment on column ark_level.id is '关卡唯一标识';
comment on column ark_level.level_id is '关卡ID';
comment on column ark_level.stage_id is '阶段ID';
comment on column ark_level.sha is 'SHA哈希值';
comment on column ark_level.cat_one is '分类一';
comment on column ark_level.cat_two is '分类二';
comment on column ark_level.cat_three is '分类三';
comment on column ark_level.name is '关卡名称';
comment on column ark_level.width is '宽度';
comment on column ark_level.height is '高度';
comment on column ark_level.is_open is '是否开放';
comment on column ark_level.close_time is '关闭时间';

create index if not exists idx_ark_level_level_id
  on ark_level (level_id);

create index if not exists idx_ark_level_stage_id
  on ark_level (stage_id);

create index if not exists idx_ark_level_name
  on ark_level (name);

create index if not exists idx_ark_level_is_open
  on ark_level (is_open);

-- 设置自增序列的起始值
-- user 表的 user_id 从 1 开始
ALTER SEQUENCE IF EXISTS user_user_id_seq RESTART WITH 1;

-- copilot 表的 copilot_id 从 1 开始 (需要调整，迁移时修改即可)
ALTER SEQUENCE IF EXISTS copilot_copilot_id_seq RESTART WITH 1;

-- copilot_operator 表的 id 从 1 开始
ALTER SEQUENCE IF EXISTS copilot_operator_id_seq RESTART WITH 1;

-- rating 表的 id 从 1 开始
ALTER SEQUENCE IF EXISTS rating_id_seq RESTART WITH 1;

-- comments_area 表的 id 从 1 开始
ALTER SEQUENCE IF EXISTS comments_area_id_seq RESTART WITH 1;

-- copilot_set 表的 id 从 1 开始
ALTER SEQUENCE IF EXISTS copilot_set_id_seq RESTART WITH 1;

-- ark_level 表的 id 从 1 开始
ALTER SEQUENCE IF EXISTS ark_level_id_seq RESTART WITH 1;
