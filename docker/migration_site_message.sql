alter table user_follow
  add column if not exists special_follow boolean default false not null;

create index if not exists idx_user_follow_special_author on user_follow (follow_user_id, special_follow);

create table if not exists site_message
(
  id          bigserial primary key,
  receiver_id bigint       not null,
  sender_id   bigint       not null,
  sender_name text         not null,
  type        text         not null,
  title       text         not null,
  content     text         not null,
  copilot_id  bigint,
  read_at     timestamp(3),
  created_at  timestamp(3) not null
);

comment on table site_message is '站内信表';
comment on column site_message.receiver_id is '接收用户ID';
comment on column site_message.sender_id is '发送/触发用户ID';
comment on column site_message.sender_name is '发送/触发用户名称快照';
comment on column site_message.type is '站内信类型';
comment on column site_message.title is '站内信标题';
comment on column site_message.content is '站内信内容';
comment on column site_message.copilot_id is '关联作业ID';
comment on column site_message.read_at is '已读时间';
comment on column site_message.created_at is '创建时间';

create index if not exists idx_site_message_receiver_created on site_message (receiver_id, created_at desc);
create index if not exists idx_site_message_receiver_read on site_message (receiver_id, read_at);
