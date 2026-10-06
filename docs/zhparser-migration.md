# zhparser 全文搜索迁移手册

本文档用于将作业搜索从「IK 分词 + 应用内存倒排索引」迁移到「PostgreSQL 18 + zhparser」。
应用侧代码已改为直接通过 `plainto_tsquery('chinese_zh', ...)` 查询 `copilot.title/details`，
不再需要 `SegmentService`、`arknights.txt` 应用内词典加载和启动全量索引构建。

## 1. 目标架构

- PG 镜像：`abcfy2/zhparser:18-alpine`
- PG 数据目录：`/var/lib/postgresql/18/docker`
- 文本搜索配置：`chinese_zh`（parser = zhparser，复用镜像默认配置，不存在时由 V3 创建）
- 领域词典：`arknights.txt`，每个词统一标记为名词 `n`
- 检索口径：`zhparser.multi_duality=on`（长词子串召回，见 1.2）
- 单字检索：`zhparser.multi_zmain=on`（单字召回与 LIKE 子串一致，见 1.3）

### 1.1 词典词条维护规则（2026-08 重建后补充）

- **收录范围**：只收能提升 `copilot.title` / `copilot.details` 检索的词汇 —— ① 干员名、干员绰号 ② 关卡名（含活动关、集成战略关卡/区域、危机合约赛季与行动地点）③ 职业与分支 ④ 作战机制与属性术语 ⑤ 社区黑话与练度术语（单核 / 满潜 / 专三 / 好抄 …）⑥ MAA 任务名与站内界面用词。
- **不收录**（与作业战斗检索无关）：敌人/怪兽名、道具材料与家具、勋章、基建技能与房间、寻访名、模组名、技能名、肉鸽收藏品/结局/分队、世界观与种族（国家/种族/阵营/势力）、NPC。
- 收录判据：该词在 `plainto_tsquery('chinese_zh', 词)` 下必须成为**单个词元**；已收录或本就能切成单词元的不重复收录（如 `单核` 曾被切成空词元、`练度` 被切成 `练`，这类词会让检索漏召回或大量误召回）。
- **不要收录与已收录词子串重叠的长词**：SCWS 按最长匹配切分，加入长词 X 会让含 X 的文档不再命中其子串 Y 的查询（`引航者试炼` 吞掉 `试炼`、`简单好抄` 吞掉 `好抄`、`蚀刻章` 吞掉 `刻章`）。若拆开分词时检索已经准确，就不要加长词。
- 词条一律 `词 1.0 1.0 n`，只追加、不重排；自定义词典在 backend 首次使用时加载，改词典后需重建连接（生产为重启/重建容器），并 `REINDEX INDEX CONCURRENTLY idx_copilot_document_tsv` 让存量行按新词典重新分词（重建前必须关并行，见第 6 节）。
- 校验方式：在挂载新词典的 zhparser 实例上，对全部词条执行 `to_tsvector('chinese_zh', 词)::text`，要求结果等于该词本身；再用真实语料对比 `plainto_tsquery` 命中数与字面命中数，确认没有把碎片写进词典。
- 索引：`copilot.title` + `copilot.details` 的表达式 GIN 索引
- 查询：`plainto_tsquery`，分词后所有词按 AND 匹配（任意位置、与顺序无关）；
  输入中的 `or` / `-` / 引号等**不会**被解释成运算符，而是当作普通文本分词

### 1.2 检索口径：长词吞短词与 `multi_duality`（2026-10 补充）

词典里的长词会吞掉子串：收录了 `挂机流` 之后 SCWS 按最长匹配把 `挂机流` 切成一个词元，查询 `挂机` 反而命不中这些作业。
开 `zhparser.multi_duality=on` 后索引里同时保留长词元与相邻二元组：

```sql
SET zhparser.multi_duality = on;  -- 仅用于观察；线上必须走启动参数
SELECT to_tsvector('chinese_zh', '摆完挂机 挂机流 凛御银灰 低配单核 银灰专三');
-- '摆完挂机' '摆完' '完挂' '挂机' '挂机流' '凛御银灰' '御银' '银灰' '低配' '单核' '专三'
```

PUBLIC 语料实测（2026-10-06，42075 行；括号内为 LIKE 子串口径）：

| 查询 | 关 | 开 |
| --- | --- | --- |
| 挂机 | 4152 | 12646（12648） |
| 银灰 | 412 | 635（635） |
| 作业 | 11011 | 11084（11294） |
| 突袭 | 7193 | 7207（7222） |

代价与约束：

- 索引平均词元数 35.6 → 42.8（+20%）；
- **查询侧同样加二元组**：4 字以上查询变成「长词 AND 各二元组」
  （`plainto_tsquery('chinese_zh','摆完挂机')` = `'摆完挂机' & '摆完' & '完挂' & '挂机'`），
  2 字查询不受影响；`作业` 这类被切成单字的词仍有约 200 行漏召回，属分词语义本身的偏差；
- 该参数改的是切词口径：**改完必须 REINDEX**，且只能由服务端启动参数指定
  （会话级 `SET` 会被并行 worker 忽略，后果见第 6 节）。

### 1.3 单字检索与 `multi_zmain`（2026-10 补充）

只靠 1.2 的二元组，单字查询几乎搜不到东西：SCWS 只在「这个字被单独切出来」时才产生单字词元，
`希望` → `'希望'`、`军用望远镜` → `'望远镜' '望远' '远镜'`，都**没有** `'望'`。

```sql
SELECT to_tsvector('chinese_zh', '希望');        -- 开 multi_zmain 后：'希':2 '希望':1 '望':3
SELECT to_tsvector('chinese_zh', '军用望远镜');  -- '军':1 '望':6 '望远':4 '望远镜':3 '用':2 '远镜':5 '镜':7
```

不开 `multi_zmain` 时的实测（PUBLIC 全部 57709 行，FTS 走索引 vs LIKE 子串）：

| 查询 | 无 zmain | LIKE |
| --- | --- | --- |
| 望 | 993 | 1401 |
| 陈 | 71 | 392 |
| 空 | 93 | 1438 |
| 循 | 1 | 168 |
| 希 | 5 | 519 |

（词典里的单字词条只有 10 个 —— `陈 山 年 空 梅 拐 令 夕 孑 轴`，其他单字都得靠 zmain 补。）

开 `zhparser.multi_zmain=on` 后（应用口径 PUBLIC + 未删除，索引 vs LIKE 逐项一致）：

| 查询 | FTS | LIKE |
| --- | --- | --- |
| 望 | 1035 | 1035 |
| 陈 | 281 | 281 |
| 令 | 1226 | 1226 |
| 循 | 113 | 113 |
| 希 | 398 | 398 |
| 空 | 1110 | 1110 |
| 山 | 1651 | 1651 |

代价与约束：

- 索引平均词元数 47.8 → 59.0（+23%）；`multi_zall` 是 70.4（+47%）但没有额外收益，别开；
- 多字查询完全不受影响（`挂机` 12646、`单核 挂机` 862、`逻各斯 模组` 129 都不变），
  因为查询侧的多字词元不会退化成单字；
- **停用字仍然搜不到**：`plainto_tsquery('chinese_zh','的')` / `('和')` 是空查询（NOTICE: contains only stop words），
  返回 0 行，而 LIKE 各有 12006 / 2425 行。单字查询命中 0 行时先看是不是这一类；
- 与 1.2 一样只能由服务端启动参数指定，**改完必须 REINDEX**（见第 6 节）。

## 2. 本次代码变更摘要

- 删除 `ik-analyzer` 依赖；
- 删除 `SegmentService` / `SegmentInfo` / `MaaCopilotProperties.segmentInfo`；
- 上传、编辑、查询不再维护内存分词索引；
- `CopilotRepository.queryCopilots` 新增 `documentKeyword` 条件，使用：
  `to_tsvector('chinese_zh', regexp_replace(coalesce(title,'') || ' ' || coalesce(details,''), '[A-Za-z0-9]+' 两侧补空格)) @@ plainto_tsquery('chinese_zh', regexp_replace(?, 同上))`
  （文档与查询关键字使用同一「ASCII 段补空格」归一化，见 4.7 与 V4 迁移）；
- 新增 Flyway `V3__zhparser_document_search.sql`、`V4__zhparser_level_code_tokenization.sql`；
- `arknights.txt` 每行追加 `1.0 1.0 n`，作为 zhparser 自定义词典使用；
- `docker/docker-compose.yml`、`dev-docker/docker-compose.yml` 的 PG 镜像更新为 zhparser 镜像并挂载词典；
- PG 启动参数增加 `-c zhparser.multi_duality=on`（检索口径见 1.2）、`-c zhparser.multi_zmain=on`
  （单字检索见 1.3），两个开关变更后都必须重建索引。

## 3. Flyway V3 兜底逻辑

`V3__zhparser_document_search.sql` 按以下顺序执行：

1. 仅当 `pg_available_extensions` 中存在 `zhparser` 时执行
   `CREATE EXTENSION IF NOT EXISTS zhparser`；
   官方 PG 镜像 / 本地无 zhparser 的环境会自动跳过，不影响启动。
2. 扩展存在时，创建 `chinese_zh`，并映射 token 类型
   `n, v, a, i, e, l, t, d, r, m`（后三个 d 副词 / r 代词 / m 数词是相对镜像默认新增的）。
   zhparser 共声明 26 种 token type，未映射的类型会被 `to_tsvector` 静默丢弃：
   缺 `m` 时「精二」只剩「精」；助词(u)/标点(w)/介词(p)/连词(c) 等纯功能词则有意不映射。
   词典词性已统一为 `n`，无需映射 `x`。
3. 创建表达式 GIN 索引（表达式与 `CopilotRepository.COPILOT_DOCUMENT_TSV_EXPR` 必须逐字一致）：

```sql
CREATE INDEX IF NOT EXISTS idx_copilot_document_tsv
    ON copilot
    USING gin (
        to_tsvector(
            'chinese_zh',
            regexp_replace(
                coalesce(title, '') || ' ' || coalesce(details, ''),
                '([A-Za-z0-9]+)',
                ' \1 ',
                'g'
            )
        )
    );
```

> 如果应用数据库账号不是超级用户且没有 `CREATE EXTENSION` 权限，V3 会显式失败。
> 此时应先在维护操作中由超级用户创建扩展，再启动应用，V3 会复用已有扩展。
>
> 注意：Flyway 每个版本只执行一次。若某个库先在无 zhparser 的环境跑过 V3（迁移被跳过），
> 之后再切换到 zhparser 镜像，需要手动执行本文 4.5/4.6 中的建配置和建索引 SQL，
> 或新增一个迁移版本补建。线上正式迁移时应在**切换 PG 镜像之后、首次启动新版应用之前**完成环境准备。
>
> 归一化（`V4__zhparser_level_code_tokenization.sql`）：给每段 ASCII 字母数字补空格，
> 让 `H17` / `S3` / `IW-EX-1` 这类关卡号成为独立词元，原因与自查 SQL 见 4.7。
>
> 映射类型会直接影响 `to_tsvector` 的结果，而 PG 不会自动重建表达式索引：
> 只有在索引尚未创建时（即 V3 首次生效前）调整映射才是安全的；
> 若某个库已经建好 `idx_copilot_document_tsv` 之后又改了映射，
> 必须 `REINDEX INDEX idx_copilot_document_tsv;`，否则已有行仍是按旧映射算出来的词元。
> 改的是**表达式**（例如 V4 的归一化）则 `REINDEX` 不够，必须删掉索引重建，
> 否则查询条件与索引表达式不匹配，规划器不会使用该索引。

## 4. 线上数据库迁移步骤

> 线上是**分开部署的多份 compose**（valkey / postgres / 应用各一份，不在同一个目录、不是同一个 compose project）：
> 本节命令默认都在 **PG 那份 compose 的目录**里执行，服务名 `database`、数据目录 `./data/`；
> 应用侧命令（`stop` / `up` 应用）要切到**应用那份 compose 的目录**再执行。
> 仓库内的 `docker/docker-compose.yml` 服务名是 `db`、挂载路径也不同，同样不是同一份文件，请勿混用。

线上 compose 目前为：

```yaml
services:
  database:
    image: postgres:18-alpine
    container_name: postgres
    restart: always
    volumes:
      - ./data/:/var/lib/postgresql/
    environment:
      POSTGRES_PASSWORD: ...
    ports:
      - 5432:5432
```

### 4.1 预检

```bash
# 确认当前镜像与数据路径
docker exec postgres sh -c 'echo "$PGDATA $PG_VERSION"'

# 宿主机上，实际集群应位于：
ls -l ./data/18/docker/PG_VERSION
cat ./data/18/docker/PG_VERSION
```

如果只存在 `./data/data/PG_VERSION`，说明当前数据是旧布局，**不要继续**，
先按官方 PG18 镜像的旧数据目录说明迁移路径，或联系维护者处理。

### 4.2 备份

先停止应用写入，再备份数据库和数据目录：

```bash
docker compose stop <app服务名>   # 在应用那份 compose 的目录执行，与 PG 不是同一个 project

docker compose exec -T database pg_dumpall -U postgres > backup-$(date +%F).sql

docker compose stop database
sudo tar -czf ./data-backup-$(date +%F).tar.gz ./data
docker compose start database
```

### 4.3 数据副本 dry-run

```bash
sudo cp -a ./data ./data-dryrun

# 从项目目录复制词典到 compose 所在目录（如果使用相对挂载）
cp src/main/resources/arknights.txt ./arknights.txt

docker run -d --name pg-zh-dryrun \
  -e POSTGRES_PASSWORD='与线上一致' \
  -v "$PWD/data-dryrun:/var/lib/postgresql" \
  -v "$PWD/arknights.txt:/usr/local/share/postgresql/tsearch_data/arknights.txt:ro" \
  -p 127.0.0.1:55432:5432 \
  docker.io/abcfy2/zhparser:18-alpine \
  postgres -c zhparser.extra_dicts=arknights.txt -c zhparser.multi_duality=on -c zhparser.multi_zmain=on

docker logs pg-zh-dryrun | grep -E 'Skipping initialization|ready to accept'

docker exec pg-zh-dryrun psql -U postgres -d <业务库名> \
  -c 'SELECT count(*) FROM copilot;'
```

确认数据完整后删除 dry-run：

```bash
docker rm -f pg-zh-dryrun
sudo rm -rf ./data-dryrun
```

### 4.4 正式切换 PG 镜像

停止数据库，修改 compose：

```yaml
services:
  database:
    image: docker.io/abcfy2/zhparser:18-alpine
    container_name: postgres
    restart: always
    command: ["postgres", "-c", "zhparser.extra_dicts=arknights.txt", "-c", "zhparser.multi_duality=on", "-c", "zhparser.multi_zmain=on"]
    volumes:
      - ./data/:/var/lib/postgresql/
      - ./arknights.txt:/usr/local/share/postgresql/tsearch_data/arknights.txt:ro
    environment:
      POSTGRES_PASSWORD: ...
    ports:
      - 5432:5432
```

其中 `./arknights.txt` 来自本仓库 `src/main/resources/arknights.txt`。
**本次一并开了 `multi_duality` 与 `multi_zmain`，它们也是切词口径的一部分：首次建索引必须先让 PG 带着这两个参数启动；
已有库里现存的索引仍是旧口径，必须手工 REINDEX 一次（SQL 见第 6 节），否则会出现「命中莫名变少」、单字搜不到。**

执行：

```bash
docker compose stop database
docker compose pull database
docker compose up -d database
```

日志必须出现：

```text
PostgreSQL Database directory appears to contain a database; Skipping initialization
```

### 4.5 启动应用，执行 Flyway

先确认 PG 已就绪：

```bash
docker compose exec -T database pg_isready -U postgres
```

再启动/部署应用。Flyway V3/V4 将：

- 在存在 zhparser 时创建扩展（如果应用账号有权限）；
- 创建 `chinese_zh`；
- 创建 GIN 索引（V3）；
- 若库里已有未归一化的旧索引，先删掉再按新表达式重建（V4，见 4.7）。

> 本文件定稿前若某个库已应用过旧版 V4（文件后来改过），Flyway 会以
> `Migration checksum mismatch for migration version 4` 拒绝启动：删掉该库
> `flyway_schema_history` 里 `version = '4'` 的一行让它重新应用（V4 幂等），或执行 `flyway repair`。
> 已发布给其他人的版本不会再改，不存在这个问题。

如果应用账号没有扩展权限，先由超级用户执行：

```sql
CREATE EXTENSION IF NOT EXISTS zhparser;
```

然后启动应用。

### 4.6 验证

```sql
-- 检索口径开关：都应为 on（off/空 = 只改了会话或没重启）
SHOW zhparser.multi_duality;
SHOW zhparser.multi_zmain;

-- 长词吞短词已缓解：结果里应同时出现 '挂机流' 与 '挂机'
SELECT to_tsvector('chinese_zh', '挂机流');

-- 单字检索已补齐：结果里应出现单字词元 '望'
SELECT to_tsvector('chinese_zh', '希望');

-- 配置与索引存在
SELECT cfgname FROM pg_ts_config WHERE cfgname = 'chinese_zh';
SELECT indexname FROM pg_indexes WHERE indexname = 'idx_copilot_document_tsv';

-- 词典词性应为 n
SELECT * FROM ts_debug('chinese_zh', '阿米娅 危机合约 龙门币');

-- 映射集应为 a,d,e,i,l,m,n,r,t,v（含 V3 新增的 d 副词 / r 代词 / m 数词）
SELECT t.alias, m.maptokentype
FROM pg_ts_config_map m
JOIN pg_ts_config c ON c.oid = m.mapcfg
JOIN pg_ts_parser p ON p.prsname = 'zhparser'
JOIN LATERAL ts_token_type(p.oid) t ON t.tokid = m.maptokentype
WHERE c.cfgname = 'chinese_zh'
ORDER BY t.alias;

-- 语义确认（二）：补上 m 映射后「精二」应切成 '精' & '二'，而不是只剩 '精'
SELECT plainto_tsquery('chinese_zh', '精二');

-- 搜索验证
SELECT copilot_id, title
FROM copilot
WHERE "delete" = FALSE
  AND to_tsvector('chinese_zh', regexp_replace(coalesce(title,'') || ' ' || coalesce(details,''), '([A-Za-z0-9]+)', ' \1 ', 'g'))
      @@ plainto_tsquery('chinese_zh', regexp_replace('阿米娅', '([A-Za-z0-9]+)', ' \1 ', 'g'));

-- 语义确认：应输出 '阿米娅' & '挂机'（AND，而非 <-> 短语），
-- 即关键字「阿米娅挂机」能命中标题为「阿米娅精二挂机」的作业
SELECT plainto_tsquery('chinese_zh', '阿米娅挂机');

-- 确认使用索引
SET enable_seqscan = off;
EXPLAIN (COSTS OFF)
SELECT copilot_id
FROM copilot
WHERE "delete" = FALSE
  AND to_tsvector('chinese_zh', regexp_replace(coalesce(title,'') || ' ' || coalesce(details,''), '([A-Za-z0-9]+)', ' \1 ', 'g'))
      @@ plainto_tsquery('chinese_zh', regexp_replace('阿米娅', '([A-Za-z0-9]+)', ' \1 ', 'g'));
```

应看到 `Bitmap Index Scan on idx_copilot_document_tsv`。

### 4.7 关卡号 / 字母数字检索回归校验

SCWS 的切词结果依赖上下文：裸 `H17` 切成 `h17`，而 `[H17-4]` 切成 `h` + `17` + `4`，
两者永不相等（旧实现用内存倒排 + LIKE 能命中，迁移后就会「搜 H17 找不到 [H17-4]」）；
`S3磨难` 这种紧邻中文的 ASCII 段甚至会被整段丢弃。
因此文档表达式和查询关键字都先用
`regexp_replace(..., '([A-Za-z0-9]+)', ' \1 ', 'g')` 给每段 ASCII 字母数字补空格，
让关卡号成为独立词元。改动分词相关代码或迁移后按下面两条自查：

```sql
-- 1) 检索侧：FTS 命中数应与 ILIKE 子串命中数接近（ILIKE 会命中 URL 里的字母数字噪声，略高属正常）
WITH q AS (
    SELECT plainto_tsquery(
        'chinese_zh',
        regexp_replace('H17', '([A-Za-z0-9]+)', ' \1 ', 'g')
    ) AS tsq
)
SELECT count(*) FILTER (
           WHERE to_tsvector(
               'chinese_zh',
               regexp_replace(coalesce(title, '') || ' ' || coalesce(details, ''), '([A-Za-z0-9]+)', ' \1 ', 'g')
           ) @@ q.tsq
       ) AS fts_hits,
       count(*) FILTER (WHERE title ILIKE '%H17%' OR details ILIKE '%H17%') AS substring_hits
FROM copilot, q
WHERE "delete" = FALSE;

-- 2) 索引侧：表达式任何改动都必须重建索引，否则规划器不再使用它（退化为全表顺序扫描）
SET enable_seqscan = off;
EXPLAIN (COSTS OFF)
SELECT copilot_id
FROM copilot
WHERE "delete" = FALSE
  AND to_tsvector('chinese_zh', regexp_replace(coalesce(title, '') || ' ' || coalesce(details, ''), '([A-Za-z0-9]+)', ' \1 ', 'g'))
      @@ plainto_tsquery('chinese_zh', regexp_replace('H17', '([A-Za-z0-9]+)', ' \1 ', 'g'));
```

把 `H17` 换成其它关卡号（`IW-EX-1` / `S3` / `CE-5` / `1-7`）再跑一次；`fts_hits` 为 0 说明归一化没生效。

## 5. 手工补建（仅限 V3 已在无 zhparser 环境执行过的情况）

如果某个库已经跑过 V3 且当时跳过了 zhparser 初始化，之后才切换到 zhparser 镜像，
由超级用户执行（索引表达式照抄 V3/V4，含归一化）：

```sql
CREATE EXTENSION IF NOT EXISTS zhparser;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_ts_config
        WHERE cfgname = 'chinese_zh'
          AND cfgnamespace = 'public'::regnamespace
    ) THEN
        CREATE TEXT SEARCH CONFIGURATION chinese_zh (PARSER = zhparser);
    END IF;
END
$$;

ALTER TEXT SEARCH CONFIGURATION chinese_zh
    DROP MAPPING IF EXISTS FOR n, v, a, i, e, l, t, d, r, m;
ALTER TEXT SEARCH CONFIGURATION chinese_zh
    ADD MAPPING FOR n, v, a, i, e, l, t, d, r, m WITH simple;

CREATE INDEX IF NOT EXISTS idx_copilot_document_tsv
    ON copilot
    USING gin (
        to_tsvector(
            'chinese_zh',
            regexp_replace(
                coalesce(title, '') || ' ' || coalesce(details, ''),
                '([A-Za-z0-9]+)',
                ' \1 ',
                'g'
            )
        )
    );
```

## 6. 词典维护

- 词典文件为 `src/main/resources/arknights.txt`，每行格式：
  `词 1.0 1.0 n`
- 部署时将该文件挂载到
  `/usr/local/share/postgresql/tsearch_data/arknights.txt`；
- 修改词典后替换文件，并让新连接生效；
- **不要在会话里 `SET zhparser.extra_dicts = ...` 做验证**：并行 worker 拿不到会话级设置
  （会打出 `WARNING: parameter "zhparser.extra_dicts" cannot be set during a parallel operation`），
  同一条查询在并行 / 非并行计划下可能按两套词典切词，看起来像「命中结果坏了」。
  词典只通过启动参数（`-c zhparser.extra_dicts=arknights.txt`）或 `postgresql.conf` 指定；
- `zhparser.multi_duality` 同样只能由启动参数（`-c`）或 `postgresql.conf` 指定：
  会话级 `SET` 在并行计划里会被 worker 忽略，索引与查询会按两套口径计算，表现为「命中莫名变少」。
  2026-10 实测：索引按 off 建、查询按 on 跑时 `逻各斯 模组` 从 129 行变成 0 行
  （`SET enable_seqscan=off` 强制走索引时同样是 0 行）；
- `zhparser.multi_zmain` 与 `multi_duality` 同性质，同样只能由启动参数指定：
  不开它时单字只命中「该字被单独切出来」的行（`望` 993/1401、`空` 93/1438），
  开了之后单字与 LIKE 完全一致（见 1.3），且多字查询不受影响（实测 47.8 → 59.0 词元/行）；
- **改 `multi_duality` / `multi_zmain` 与改词典一样必须 REINDEX**；先按 4.6 用 `SHOW` 确认
  `zhparser.extra_dicts`、`zhparser.multi_duality`、`zhparser.multi_zmain` 都已在服务端生效，避免「按错的开关重建」；
- **建索引 / `REINDEX` 同样受并行影响**：并行 maintenance worker 按「无额外词典」的口径写入 posting，
  索引与查询口径不一致。2026-10 实测：并行 `REINDEX` 后 `单核` / `阿米娅` 的 posting 全缺，
  `单核` 走索引 0 行、走顺序扫描 5955 行；关并行重建后两种计划命中行集合完全一致。
  所以 `REINDEX` 前必须先 `SET max_parallel_maintenance_workers = 0;`；
- 改**切词口径**用上面的 `REINDEX`；改**表达式**（如 V4 的归一化）必须删掉索引重建，原因见第 3 节说明；
- **词表按「不修改」处理**：`arknights.txt` 只在容器创建时挂载，PG 启动时才加载，
  所以仓库不再提供重建脚本，也不走「改词典」流程；
- **只有两种情况需要重建索引**：把旧库的存量索引切到新口径（`multi_duality` 等启动参数变了），
  或将来真的换了词典。手工执行，**必须先关并行**：

```sql
SET max_parallel_maintenance_workers = 0;  -- 必须，见上一条
REINDEX INDEX CONCURRENTLY idx_copilot_document_tsv;
```

  让启动参数生效需要重启 PG；容器若由 systemd quadlet 管理（本机 dev 环境即如此），用
  `systemctl --user restart postgres.service`：`podman restart postgres` 对 quadlet 容器会报
  `no container with name or ID "postgres" found`。

  重建后自检不能只比 `count(*)`：必须让「走索引」和「走顺序扫描」各跑一遍，比较命中行集合
  （如 `md5(string_agg(copilot_id::text, ',' ORDER BY copilot_id))`）；
  若索引没被用上（表达式与索引不符、映射改了没重建），两次结果会不一致。可直接跑这段：

```bash
podman exec -i postgres psql -U postgres -d zoot -X -A -F'|' -f - <<'SQL'
SET enable_seqscan = off;   -- 强制走表达式索引
SELECT count(*) AS n, md5(string_agg(copilot_id::text, ',' ORDER BY copilot_id)) AS h
FROM copilot WHERE status='PUBLIC' AND "delete"=false
  AND to_tsvector('chinese_zh', regexp_replace(coalesce(title,'')||' '||coalesce(details,''),'([A-Za-z0-9]+)',' \1 ','g'))
      @@ plainto_tsquery('chinese_zh', regexp_replace('望','([A-Za-z0-9]+)',' \1 ','g'));
SET enable_seqscan = on;    -- 同一句再跑一次，n 与 h 必须逐字相同
SQL
```

  （把 `'望'` 换成 `'挂机'`、`'ZT-EX-8'` 等多字/字母数字关键词再各跑一遍。）

## 7. 回滚

### 仅切换镜像、尚未启动新版应用

把 compose 中 PG 镜像改回 `postgres:18-alpine` 并启动即可。PG18 同大版本的数据目录可以互相打开。

### 已创建扩展和索引

先停应用，再：

```sql
DROP INDEX IF EXISTS idx_copilot_document_tsv;
DROP TEXT SEARCH CONFIGURATION IF EXISTS chinese_zh;
DROP EXTENSION IF EXISTS zhparser CASCADE;
```

然后切回官方镜像；或直接使用 4.2 中的 `./data` 备份整体恢复。

## 8. 风险与注意事项

1. **已有数据库不会执行镜像 initdb 脚本**。zhparser 的初始化依赖 Flyway V3
   或手动 SQL，镜像 init 脚本只对新建空库生效。
2. **`CREATE INDEX` 会锁写并消耗磁盘**。`copilot` 数据量很大时，建议在维护窗口
   由 DBA 先执行 `CREATE INDEX CONCURRENTLY`，Flyway 中的 `IF NOT EXISTS` 会跳过。
   手工建索引同样要先 `SET max_parallel_maintenance_workers = 0;`（V4 迁移内已设置）。
3. **词典或 `zhparser.multi_duality` / `multi_zmain` 变化后必须 REINDEX**，且 `REINDEX` 前要先 `SET max_parallel_maintenance_workers = 0;`，
   否则索引按另一套切词口径生成，命中会静默变少（自检 SQL 见第 6 节）。
4. **生产镜像建议固定 digest 或自行构建**。`abcfy2/zhparser:18-alpine` 是浮动 tag，
   每周跟随上游重建。
5. 永远不要在未备份的情况下切换镜像；不要删除 `./data`。
