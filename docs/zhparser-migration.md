# zhparser 全文搜索迁移手册

本文档用于将作业搜索从「IK 分词 + 应用内存倒排索引」迁移到「PostgreSQL 18 + zhparser」。
应用侧代码已改为直接通过 `websearch_to_tsquery('chinese_zh', ...)` 查询 `copilot.title/details`，
不再需要 `SegmentService`、`arknights.txt` 应用内词典加载和启动全量索引构建。

## 1. 目标架构

- PG 镜像：`abcfy2/zhparser:18-alpine`
- PG 数据目录：`/var/lib/postgresql/18/docker`
- 文本搜索配置：`chinese_zh`（parser = zhparser，复用镜像默认配置，不存在时由 V2 创建）
- 领域词典：`arknights.txt`，每个词统一标记为名词 `n`
- 索引：`copilot.title` + `copilot.details` 的表达式 GIN 索引
- 查询：`websearch_to_tsquery`，空格分隔词为 AND 语义

## 2. 本次代码变更摘要

- 删除 `ik-analyzer` 依赖；
- 删除 `SegmentService` / `SegmentInfo` / `MaaCopilotProperties.segmentInfo`；
- 上传、编辑、查询不再维护内存分词索引；
- `CopilotRepository.queryCopilots` 新增 `documentKeyword` 条件，使用：
  `to_tsvector('chinese_zh', coalesce(title,'') || ' ' || coalesce(details,'')) @@ websearch_to_tsquery('chinese_zh', ?)`；
- 新增 Flyway `V2__zhparser_document_search.sql`；
- `arknights.txt` 每行追加 `1.0 1.0 n`，作为 zhparser 自定义词典使用；
- `docker/docker-compose.yml`、`dev-docker/docker-compose.yml` 的 PG 镜像更新为 zhparser 镜像并挂载词典。

## 3. Flyway V2 兜底逻辑

`V2__zhparser_document_search.sql` 按以下顺序执行：

1. 仅当 `pg_available_extensions` 中存在 `zhparser` 时执行
   `CREATE EXTENSION IF NOT EXISTS zhparser`；
   官方 PG 镜像 / 本地无 zhparser 的环境会自动跳过，不影响启动。
2. 扩展存在时，创建 `chinese_zh`，并映射 token 类型
   `n, v, a, i, e, l, t`。词典词性已统一为 `n`，无需映射 `x`。
3. 创建表达式 GIN 索引：

```sql
CREATE INDEX IF NOT EXISTS idx_copilot_document_tsv
    ON copilot
    USING gin (
        to_tsvector(
            'chinese_zh',
            coalesce(title, '') || ' ' || coalesce(details, '')
        )
    );
```

> 如果应用数据库账号不是超级用户且没有 `CREATE EXTENSION` 权限，V2 会显式失败。
> 此时应先在维护操作中由超级用户创建扩展，再启动应用，V2 会复用已有扩展。
>
> 注意：Flyway 每个版本只执行一次。若某个库先在无 zhparser 的环境跑过 V2（迁移被跳过），
> 之后再切换到 zhparser 镜像，需要手动执行本文 4.5/4.6 中的建配置和建索引 SQL，
> 或新增一个迁移版本补建。线上正式迁移时应在**切换 PG 镜像之后、首次启动新版应用之前**完成环境准备。

## 4. 线上数据库迁移步骤

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
docker compose stop <app服务名>

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
  postgres -c zhparser.extra_dicts=arknights.txt

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
    command: ["postgres", "-c", "zhparser.extra_dicts=arknights.txt"]
    volumes:
      - ./data/:/var/lib/postgresql/
      - ./arknights.txt:/usr/local/share/postgresql/tsearch_data/arknights.txt:ro
    environment:
      POSTGRES_PASSWORD: ...
    ports:
      - 5432:5432
```

其中 `./arknights.txt` 来自本仓库 `src/main/resources/arknights.txt`。

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

再启动/部署应用。Flyway V2 将：

- 在存在 zhparser 时创建扩展（如果应用账号有权限）；
- 创建 `chinese_zh`；
- 创建 GIN 索引。

如果应用账号没有扩展权限，先由超级用户执行：

```sql
CREATE EXTENSION IF NOT EXISTS zhparser;
```

然后启动应用。

### 4.6 验证

```sql
-- 配置与索引存在
SELECT cfgname FROM pg_ts_config WHERE cfgname = 'chinese_zh';
SELECT indexname FROM pg_indexes WHERE indexname = 'idx_copilot_document_tsv';

-- 词典词性应为 n
SELECT * FROM ts_debug('chinese_zh', '阿米娅 危机合约 龙门币');

-- 搜索验证
SELECT copilot_id, title
FROM copilot
WHERE "delete" = FALSE
  AND to_tsvector('chinese_zh', coalesce(title,'') || ' ' || coalesce(details,''))
      @@ websearch_to_tsquery('chinese_zh', '阿米娅');

-- 确认使用索引
SET enable_seqscan = off;
EXPLAIN (COSTS OFF)
SELECT copilot_id
FROM copilot
WHERE "delete" = FALSE
  AND to_tsvector('chinese_zh', coalesce(title,'') || ' ' || coalesce(details,''))
      @@ websearch_to_tsquery('chinese_zh', '阿米娅');
```

应看到 `Bitmap Index Scan on idx_copilot_document_tsv`。

## 5. 手工补建（仅限 V2 已在无 zhparser 环境执行过的情况）

如果某个库已经跑过 V2 且当时跳过了 zhparser 初始化，之后才切换到 zhparser 镜像，
由超级用户执行：

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
    DROP MAPPING IF EXISTS FOR n, v, a, i, e, l, t;
ALTER TEXT SEARCH CONFIGURATION chinese_zh
    ADD MAPPING FOR n, v, a, i, e, l, t WITH simple;

CREATE INDEX IF NOT EXISTS idx_copilot_document_tsv
    ON copilot
    USING gin (
        to_tsvector(
            'chinese_zh',
            coalesce(title, '') || ' ' || coalesce(details, '')
        )
    );
```

## 6. 词典维护

- 词典文件为 `src/main/resources/arknights.txt`，每行格式：
  `词 1.0 1.0 n`
- 部署时将该文件挂载到
  `/usr/local/share/postgresql/tsearch_data/arknights.txt`；
- 修改词典后替换文件，并让新连接生效；
- **更新词典后必须重建索引**，否则旧 tsvector 不会自动更新：

```sql
REINDEX INDEX CONCURRENTLY idx_copilot_document_tsv;
```

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

1. **已有数据库不会执行镜像 initdb 脚本**。zhparser 的初始化依赖 Flyway V2
   或手动 SQL，镜像 init 脚本只对新建空库生效。
2. **`CREATE INDEX` 会锁写并消耗磁盘**。`copilot` 数据量很大时，建议在维护窗口
   由 DBA 先执行 `CREATE INDEX CONCURRENTLY`，Flyway 中的 `IF NOT EXISTS` 会跳过。
3. **词典变化后必须 REINDEX**。
4. **生产镜像建议固定 digest 或自行构建**。`abcfy2/zhparser:18-alpine` 是浮动 tag，
   每周跟随上游重建。
5. 永远不要在未备份的情况下切换镜像；不要删除 `./data`。
