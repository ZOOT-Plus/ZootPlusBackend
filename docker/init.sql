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


create index if not exists idx_operator_copilot_id
  on copilot_operator (copilot_id);

create index if not exists idx_operator_name
  on copilot_operator (name);

-- 评论区表
create table if not exists comments_area
(
  id              text                        not null
    primary key,
  copilot_id      bigint                      not null,
  from_comment_id text,
  uploader_id     text                        not null,
  message         text                        not null,
  like_count      bigint    default 0         not null,
  dislike_count   bigint    default 0         not null,
  upload_time     timestamp(3)                not null,
  topping         boolean   default false     not null,
  delete          boolean   default false     not null,
  delete_time     timestamp(3),
  main_comment_id text,
  notification    boolean   default false     not null
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
  id        text         not null
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
  id          bigint                          not null
    primary key,
  name        text                            not null,
  description text                            not null,
  copilot_ids text                            not null,
  creator_id  text                            not null,
  create_time timestamp(3)                    not null,
  update_time timestamp(3)                    not null,
  status      text      default 'PUBLIC'::text not null,
  delete      boolean   default false         not null
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

-- 用户关注表
create table if not exists user_following
(
  id          text         not null
    primary key,
  user_id     text         not null,
  following_id text         not null,
  create_time timestamp(3) not null
);

comment on table user_following is '用户关注表';
comment on column user_following.user_id is '关注者用户ID';
comment on column user_following.following_id is '被关注者用户ID';
comment on column user_following.create_time is '关注时间';

-- 确保一个用户不能重复关注同一个人
create unique index if not exists idx_user_following_unique
  on user_following (user_id, following_id);

create index if not exists idx_user_following_user_id
  on user_following (user_id);

create index if not exists idx_user_following_following_id
  on user_following (following_id);

-- 用户粉丝表
create table if not exists user_fans
(
  id          text         not null
    primary key,
  user_id     text         not null,
  fans_id     text         not null,
  create_time timestamp(3) not null
);

comment on table user_fans is '用户粉丝表';
comment on column user_fans.user_id is '被关注者用户ID';
comment on column user_fans.fans_id is '粉丝用户ID';
comment on column user_fans.create_time is '关注时间';

-- 确保一个用户不能重复成为同一个人的粉丝
create unique index if not exists idx_user_fans_unique
  on user_fans (user_id, fans_id);

create index if not exists idx_user_fans_user_id
  on user_fans (user_id);

create index if not exists idx_user_fans_fans_id
  on user_fans (fans_id);

-- 关卡表
create table if not exists ark_level
(
  id         text         not null
    primary key,
  level_id   text,
  stage_id   text,
  sha        text         not null,
  cat_one    text,
  cat_two    text,
  cat_three  text,
  name       text,
  width      integer      not null,
  height     integer      not null,
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

