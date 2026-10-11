#!/usr/bin/env python3
"""Prepare an offline replay from local PRTS caches without changing the source files."""

import argparse
import collections
import datetime as dt
import hashlib
import json
from pathlib import Path


def read(path):
    return json.loads(path.read_text(encoding="utf-8"))


def local_time(value):
    if isinstance(value, (int, float)):
        value = dt.datetime.fromtimestamp(value / 1000, dt.timezone.utc)
    else:
        value = dt.datetime.fromisoformat(value.replace("Z", "+00:00"))
    if value.tzinfo is not None:
        value = value.astimezone(dt.timezone(dt.timedelta(hours=8))).replace(tzinfo=None)
    return value.isoformat()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--snapshot", type=Path, required=True, help="Snapshot containing complete actions")
    parser.add_argument("--summaries", type=Path, required=True, help="Snapshot containing votes and upload dates")
    parser.add_argument("--operators", type=Path, required=True, help="Frontend generated/operators.json")
    parser.add_argument("--levels", type=Path, required=True, help="Saved /arknights/level response")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    snapshot = read(args.snapshot)
    summaries = {row["id"]: row for row in read(args.summaries)["homeworks"]}
    counts = collections.Counter()
    rows = []
    for row in snapshot["homeworks"]:
        counts["snapshotRows"] += 1
        content = row["content"]
        if "actions" not in content:
            counts["missingActions"] += 1
            continue
        summary = summaries.get(row["id"])
        if summary is None:
            counts["missingSummary"] += 1
            continue
        first_upload = content.get("first_upload_time")
        published = first_upload or summary.get("uploadedAt")
        if not published:
            counts["missingDate"] += 1
            continue
        counts["firstUploadKnown" if first_upload else "uploadDateProxy"] += 1
        rows.append({
            "id": row["id"], "stageId": row["stageId"], "content": content,
            "publishedAt": local_time(published),
            "title": content.get("doc", {}).get("title", ""),
            "likes": summary["likes"], "dislikes": summary["dislikes"], "views": summary["views"],
        })
    if not rows:
        parser.error("No complete operations with matching feedback and dates")
    module_types = {"": 0, "X": 1, "Y": 2, "A": 3, "D": 4}
    professions = {"PIONEER", "WARRIOR", "TANK", "SNIPER", "CASTER", "MEDIC", "SUPPORT", "SPECIAL"}
    operators = [
        {"id": op["id"], "name": op["name"], "role": op["prof"].title(), "rarity": op["rarity"],
         "modules": [module_types.get(value) for value in op.get("modules", [])]}
        for op in read(args.operators)["OPERATORS"]
        if op["prof"] in professions and op.get("subProf") != "notchar1" and op["id"] != "char_512_aprot"
    ]
    levels = read(args.levels)
    if isinstance(levels, dict):
        levels = levels["data"]
    assert isinstance(levels, list) and levels, "Expected a nonempty level list"
    args.output.mkdir(parents=True)
    with (args.output / "inputs.jsonl").open("x", encoding="utf-8") as output:
        for row in rows:
            output.write(json.dumps(row, ensure_ascii=False, separators=(",", ":")) + "\n")
    for name, data in [("operators", operators), ("levels", levels)]:
        (args.output / f"{name}.json").write_text(json.dumps(data, ensure_ascii=False), encoding="utf-8")
    report = {
        "counts": dict(counts), "replayedRows": len(rows), "levels": len(levels),
        "sourceSha256": {name: hashlib.sha256(getattr(args, name).read_bytes()).hexdigest()
                         for name in ("snapshot", "summaries", "operators", "levels")},
        "limitations": "Upload dates substitute for unknown first publication dates; positive-rating dates are absent. "
                       "Use this replay for performance and invariants, not production ranking calibration.",
    }
    (args.output / "manifest.json").write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
