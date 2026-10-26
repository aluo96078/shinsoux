"""Records the HTTP responses an official extension requests, for offline benchmarking.

    python3 -I record.py <plugin_bench> <bootstrap.js> <plugin.js> <fixture-dir>

Runs plugin_bench repeatedly; every `MISS` line is fetched once and stored as
<key>.status / <key>.body.json, until a run has no misses. Only public catalogue pages are
requested; no cookies or credentials are sent or stored.
"""
import json
import pathlib
import ssl
import subprocess
import sys
import urllib.request

FORBIDDEN_HEADERS = {"cookie", "authorization", "proxy-authorization"}


def fnv1a(method: str, args: str) -> str:
    h = 1469598103934665603
    for b in method.encode() + b"\n" + args.encode():
        h ^= b
        h = (h * 1099511628211) & 0xFFFFFFFFFFFFFFFF
    return f"{h:016x}"


def fetch(method: str, args: list):
    url = args[0]
    body = None
    headers = {}
    if method.startswith("httpPost"):
        body = str(args[1]).encode() if len(args) > 1 else b""
        headers = args[2] if len(args) > 2 and isinstance(args[2], dict) else {}
    elif len(args) > 1 and isinstance(args[1], dict):
        headers = args[1]
    headers = {k: str(v) for k, v in headers.items() if k.lower() not in FORBIDDEN_HEADERS}
    headers.setdefault("User-Agent", "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.0 Safari/605.1.15")
    req = urllib.request.Request(url, data=body, headers=headers, method="POST" if body is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=25, context=ssl.create_default_context()) as resp:
            return resp.status, resp.read().decode("utf-8", "replace")
    except urllib.error.HTTPError as e:
        return e.code, e.read().decode("utf-8", "replace")


def main():
    bench, boot, plugin, out = sys.argv[1:5]
    out_dir = pathlib.Path(out)
    out_dir.mkdir(parents=True, exist_ok=True)
    for attempt in range(12):
        proc = subprocess.run([bench, boot, plugin, out, "1"], capture_output=True, text=True, timeout=300)
        misses = [json.loads(line[5:]) for line in proc.stdout.splitlines() if line.startswith("MISS ")]
        if not misses:
            print(proc.stdout.strip())
            return 0
        for miss in misses:
            method, args = miss["method"], miss["args"]
            key = fnv1a(method, json.dumps(args, ensure_ascii=False, separators=(",", ":")))
            raw_key = None
            # The bench hashes the exact argsJson emitted by the bootstrap; re-derive it from the line.
            for line in proc.stdout.splitlines():
                if line.startswith("MISS ") and json.loads(line[5:]) == miss:
                    raw = line[5:]
                    raw_args = raw[raw.index(',"args":') + len(',"args":'):-1]
                    raw_key = fnv1a(method, raw_args)
                    break
            key = raw_key or key
            if (out_dir / f"{key}.body.json").exists():
                continue
            try:
                status, text = fetch(method, args)
            except Exception as e:  # network failure: store a 599 so the plugin sees a failure
                status, text = 599, ""
                print(f"  fetch failed {args[0]}: {type(e).__name__}", file=sys.stderr)
            (out_dir / f"{key}.status").write_text(str(status))
            (out_dir / f"{key}.body.json").write_text(json.dumps(text, ensure_ascii=False))
            print(f"  recorded {status} {method} {str(args[0])[:100]}", file=sys.stderr)
    print("gave up after 12 attempts", file=sys.stderr)
    return 1


if __name__ == "__main__":
    sys.exit(main())
