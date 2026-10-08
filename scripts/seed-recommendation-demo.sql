-- Synthetic operations for local UI testing; these scripts cannot clear real stages.
-- Run only against the local zoot_dev database. Re-running preserves existing rows.
BEGIN;

DO $guard$
BEGIN
    IF current_database() <> 'zoot_dev' THEN
        RAISE EXCEPTION 'Recommendation demo data may only be inserted into zoot_dev';
    END IF;
END;
$guard$;

INSERT INTO ark_level (id, level_id, stage_id, sha, cat_one, cat_two, cat_three, name, width, height, is_open, updated_at)
VALUES
    (900000001, 'main/recommendation_demo_1', 'recommendation_demo_1', 'manual-demo', '主题曲', '手动测试章节', '测试-1', '推荐样例一', 3, 3, true, CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai'),
    (900000002, 'main/recommendation_demo_2', 'recommendation_demo_2', 'manual-demo', '主题曲', '手动测试章节', '测试-2', '推荐样例二', 3, 3, true, CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai'),
    (900000003, 'main/recommendation_demo_3', 'recommendation_demo_3', 'manual-demo', '主题曲', '手动测试章节', '测试-3', '推荐样例三', 3, 3, true, CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai'),
    (900000004, 'activities/recommendation_demo_4', 'recommendation_demo_4', 'manual-demo', '活动关卡', '已关闭的手动测试活动', '测试-4', '关闭活动样例', 3, 3, false, CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai'),
    (900000005, 'activities/recommendation_demo_5', 'recommendation_demo_5', 'manual-demo', '活动关卡', '开放的手动测试活动', '测试-5', '备选干员样例', 3, 3, true, CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai')
ON CONFLICT (id) DO NOTHING;

WITH examples (id, stage, name, skill, elite, level, skill_level, module, likes, dislikes, views, age_days, title, alternative) AS (
    VALUES
        (900000001, 1, '银灰', 3, 2, 60, 7, 0, 90, 10, 100, 10, '银灰基础配置', false),
        (900000002, 2, '银灰', 3, 2, 80, 10, 1, 90, 10, 100, 10, '银灰高专精配置', false),
        (900000003, 3, '银灰', 3, 2, 90, 9, 1, 90, 10, 100, 10, '银灰高等级配置', false),
        (900000004, 1, '银灰', 3, 2, 60, 7, 0, 90, 10, 100, 1, '银灰基础配置的复制版', false),
        (900000005, 1, '棘刺', 3, 2, 90, 10, 1, 90, 10, 100, 900, '只有历史支持的棘刺', false),
        (900000006, 2, '史尔特尔', 3, 2, 90, 10, 1, 10, 90, 1000000, 10, '高浏览量但低好评的史尔特尔', false),
        (900000007, 3, '山', 2, 2, 60, 7, 1, 0, 0, 10, 1, '尚无评价的山', false),
        (900000008, 1, '夜莺', 3, NULL, NULL, NULL, 1, 90, 10, 100, 10, '单关适用且练度不完整的夜莺', false),
        (900000009, 4, '能天使', 3, 2, 90, 10, 2, 90, 10, 100, 10, '关闭活动中的能天使', false),
        (900000010, 5, '阿米娅', 3, NULL, NULL, NULL, NULL, 90, 10, 100, 10, '阿米娅或能天使的备选位置', true)
), documents AS (
    SELECT *, '[手动测试] ' || title AS display_title,
        jsonb_build_object(
            'stage_name', 'recommendation_demo_' || stage,
            'opers', CASE WHEN alternative THEN '[]'::jsonb ELSE jsonb_build_array(
                jsonb_build_object('name', name, 'skill', skill, 'requirements', jsonb_strip_nulls(
                    jsonb_build_object('elite', elite, 'level', level, 'skill_level', skill_level, 'module', module)
                ))
            ) END,
            'groups', CASE WHEN alternative THEN '[{"name":"测试备选位","opers":[{"name":"阿米娅","role":"Caster","skill":3},{"name":"能天使","role":"Sniper","skill":3}]}]'::jsonb ELSE '[]'::jsonb END,
            'actions', jsonb_build_array(jsonb_build_object(
                'type', 'Deploy', 'name', CASE WHEN alternative THEN '测试备选位' ELSE name END,
                'location', jsonb_build_array(0, 0), 'direction', 'Up'
            )),
            'doc', jsonb_build_object('title', '[手动测试] ' || title, 'details', '仅用于本地页面验收，不能用于实际游戏关卡。')
        )::text AS content
    FROM examples
)
INSERT INTO copilot (
    copilot_id, type, stage_name, uploader_id, views, rating_level, rating_ratio,
    like_count, dislike_count, hot_score, title, details, first_upload_time,
    upload_time, content, status, comment_status, "delete", notification
)
SELECT id, 'PRTS', 'recommendation_demo_' || stage, 0, views, 0,
    CASE WHEN likes + dislikes = 0 THEN 0 ELSE likes::double precision / (likes + dislikes) END,
    likes, dislikes, 0, display_title, '仅用于本地页面验收，不能用于实际游戏关卡。',
    (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai') - age_days * INTERVAL '1 day',
    CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Shanghai', content, 'PUBLIC', 'ENABLED', false, false
FROM documents
ON CONFLICT (copilot_id) DO NOTHING;

WITH members AS (
    SELECT c.copilot_id, member ->> 'name' AS name
    FROM copilot c, LATERAL jsonb_array_elements(c.content::jsonb -> 'opers') member
    WHERE c.copilot_id BETWEEN 900000001 AND 900000010 AND c.title LIKE '[手动测试] %'
    UNION
    SELECT c.copilot_id, member ->> 'name'
    FROM copilot c, LATERAL jsonb_array_elements(c.content::jsonb -> 'groups') slot,
        LATERAL jsonb_array_elements(slot -> 'opers') member
    WHERE c.copilot_id BETWEEN 900000001 AND 900000010 AND c.title LIKE '[手动测试] %'
)
INSERT INTO copilot_operator (copilot_id, name)
SELECT members.copilot_id, members.name FROM members
WHERE NOT EXISTS (
    SELECT 1 FROM copilot_operator existing
    WHERE existing.copilot_id = members.copilot_id AND existing.name = members.name
);

COMMIT;

SELECT count(*) AS demo_operations FROM copilot
WHERE copilot_id BETWEEN 900000001 AND 900000010 AND title LIKE '[手动测试] %';
