#!/usr/bin/env bash
#
# Copilot 搜索接口 oha 性能基准
#
# 目标接口（当前代码分词版本）:
#   GET /copilot/query?page=1&limit=10&document=逻各斯&desc=true&orderBy=hot
#
# 用法:
#   ./bench/copilot-query-bench.sh [run-label]
#
# 环境变量:
#   BASE_URL     被测服务地址（默认 http://127.0.0.1:8848）
#   CONCURRENCY  oha -c，默认 1（顺序请求，看单请求延迟；调大看吞吐）
#   WARMUP       每个案例预热请求数（默认 10，不计入结果）
#   LIMIT        每页条数（默认 10）
#
# 输出:
#   bench/results/<run-label>/summary.tsv   各案例 p50/p90/p99/rps
#   bench/results/<run-label>/<NN-case>.json  oha 原始 JSON
#
# 案例抽样自本地验证库（dbx 连接 [local]zoot），按词频从高到低取代表：
#   SELECT w, (SELECT count(*) FROM copilot c
#              WHERE c."delete" = false
#                AND (c.title LIKE '%' || w || '%' OR c.details LIKE '%' || w || '%'))
#   FROM unnest(ARRAY['挂机','作业','突袭','模组','单核','低配','磨难','标准',
#                     '银灰','逻各斯','史尔特尔','锡兰']) AS w
#   ORDER BY 2 DESC;
#
# 基线（2026-10-02，PG 18.1，无任何全文检索扩展）：
#   copilot 全表 84080 行，未删除 61376，其中 PUBLIC 42075。
#
# 对比 PG 原生分词版本时以相同 label 方式各跑一次，直接对比 summary.tsv。
#
set -euo pipefail

BENCH_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BASE_URL="${BASE_URL:-http://127.0.0.1:8848}"
ENDPOINT="$BASE_URL/copilot/query"
CONCURRENCY="${CONCURRENCY:-1}"
WARMUP="${WARMUP:-10}"
LIMIT="${LIMIT:-10}"
LABEL="${1:-$(date +%Y%m%d-%H%M%S)}"
OUT_DIR="$BENCH_DIR/results/$LABEL"

for bin in oha jq curl; do
    command -v "$bin" >/dev/null || { echo "缺少依赖: $bin" >&2; exit 1; }
done

# 格式: 名称|document|page|请求数|基线 total|说明
# total 为当前数据下的基线值，用于校验两版实现返回结果一致（不一致会标 MISMATCH）
CASES=(
    "挂机-高频词|挂机|1|60|12648|最大 IN 列表 ~12.6k"
    "作业-高频词|作业|1|60|11294|高频词 ~11.3k"
    "突袭-高频词|突袭|1|60|7222|高频词 ~7.2k"
    "模组-高频词|模组|1|60|6684|高频词 ~6.7k"
    "单核-中频词|单核|1|80|3819|中高频 ~3.8k"
    "低配-中频词|低配|1|80|2487|中频 ~2.5k"
    "磨难-中频词|磨难|1|80|2328|中频 ~2.3k"
    "标准-中频词|标准|1|100|1276|中频 ~1.3k"
    "银灰-低频词|银灰|1|100|635|低频 ~0.6k"
    "逻各斯-低频词|逻各斯|1|120|394|示例关键词 ~0.4k"
    "史尔特尔-低频词|史尔特尔|1|120|98|低频 ~100"
    "锡兰-极低频词|锡兰|1|150|5|极少命中"
    "蜘蛛侠-无命中|蜘蛛侠|1|150|0|空交集早返回，不查库"
    "单核 挂机-双词|单核 挂机|1|100|863|分词后双词交集"
    "低配 突袭-双词|低配 突袭|1|120|227|双词交集"
    "逻各斯 模组-双词|逻各斯 模组|1|120|129|双词交集"
    "银灰 单核-双词|银灰 单核|1|120|27|双词交集"
    "ZT-EX-8-关卡号|ZT-EX-8|1|120|41|英文数字关键词"
    "令-单字符旁路|令|1|80|42075|单字符跳过分词，不过滤只排序"
    "无关键词基线||4|80|42075|空关键词 page=4 绕开 Redis 首页缓存"
)

mkdir -p "$OUT_DIR"
SUMMARY="$OUT_DIR/summary.tsv"
printf 'case\tdocument\tpage\tn\texpected_total\tactual_total\tp50_ms\tp90_ms\tp99_ms\trps\tsuccess_rate\tnote\n' > "$SUMMARY"

echo "== Copilot 搜索基准 =="
echo "target      : $ENDPOINT"
echo "label       : $LABEL"
echo "concurrency : $CONCURRENCY (oha -c)"
echo "warmup/case : $WARMUP"
echo "output      : $OUT_DIR"
echo

# 服务可用性检查：page=4 避免写 Redis 首页缓存
curl -sf -o /dev/null "$ENDPOINT?page=4&limit=1&desc=true&orderBy=hot" ||
    { echo "服务不可用: $ENDPOINT" >&2; exit 1; }

total=${#CASES[@]}
idx=0
for case in "${CASES[@]}"; do
    idx=$((idx + 1))
    IFS='|' read -r name doc page n expected note <<<"$case"

    qs="page=$page&limit=$LIMIT"
    if [[ -n "$doc" ]]; then
        qs+="&document=$(jq -rn --arg v "$doc" '$v|@uri')"
    fi
    qs+="&desc=true&orderBy=hot"
    url="$ENDPOINT?$qs"
    slug=$(printf '%02d-%s' "$idx" "${name// /_}")

    actual=$(curl -s "$url" | jq -r '.data.total // "ERR"')
    flag="ok"
    [[ "$actual" == "$expected" ]] || flag="MISMATCH"

    oha -n "$WARMUP" -c "$CONCURRENCY" --no-tui --output-format quiet "$url" >/dev/null 2>&1 || true

    json_file="$OUT_DIR/$slug.json"
    if ! oha -n "$n" -c "$CONCURRENCY" --no-tui --output-format json "$url" \
        >"$json_file" 2>"$OUT_DIR/$slug.err"; then
        echo "[$idx/$total] $name: oha 执行失败，见 $slug.err" >&2
        printf '%s\t%s\t%s\t%s\t%s\t%s\t-1\t-1\t-1\t-1\t0\t%s\n' \
            "$name" "$doc" "$page" "$n" "$expected" "$actual" "$note" >>"$SUMMARY"
        continue
    fi

    read -r p50 p90 p99 rps ok <<<"$(
        jq -r '[(.latencyPercentiles.p50 * 1000 | round),
                (.latencyPercentiles.p90 * 1000 | round),
                (.latencyPercentiles.p99 * 1000 | round),
                ((.summary.requestsPerSec * 100 | round) / 100),
                .summary.successRate] | @tsv' "$json_file"
    )"

    printf '[%02d/%02d] %-14s total=%-6s(%-8s) p50=%4sms p90=%4sms p99=%4sms rps=%-6s ok=%s\n' \
        "$idx" "$total" "$name" "$actual" "$flag" "$p50" "$p90" "$p99" "$rps" "$ok"
    printf '%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\t%s\n' \
        "$name" "$doc" "$page" "$n" "$expected" "$actual" "$p50" "$p90" "$p99" "$rps" "$ok" "$note" >>"$SUMMARY"
done

echo
echo "== summary =="
column -t -s $'\t' "$SUMMARY" 2>/dev/null || cat "$SUMMARY"
echo
echo "结果目录: $OUT_DIR"
