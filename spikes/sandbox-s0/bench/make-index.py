"""Builds <fixture-dir>/index.json for the Rhino baseline from `BENCH_TRACE=1 plugin_bench` output.

    BENCH_TRACE=1 plugin_bench <bootstrap> <plugin.js> <fixture-dir> 1 | python3 -I make-index.py <fixture-dir>
"""
import json
import pathlib
import sys

index = {}
for line in sys.stdin:
    if not line.startswith("REQ "):
        continue
    key, rest = line[4:].split(" ", 1)
    request = json.loads(rest)
    method, args = request["method"], request["args"]
    body = args[1] if method.startswith("httpPost") and len(args) > 1 else None
    index[key] = {"method": method, "url": args[0], "body": body}
out = pathlib.Path(sys.argv[1]) / "index.json"
out.write_text(json.dumps(index, ensure_ascii=False, indent=1))
print(f"{out}: {len(index)} requests")
