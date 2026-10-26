#!/usr/bin/env python3 -I
"""S0 measurements for shinsou-sandbox. usage: bench.py <build-dir> [--exe=name] [--sandbox=seatbelt|none]"""
import os, statistics, subprocess, sys, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from host import Helper

build = sys.argv[1]
exe = os.path.join(build, next((a[6:] for a in sys.argv[2:] if a.startswith("--exe=")), "shinsou-sandbox"))
mode = next((a for a in sys.argv[2:] if a.startswith("--sandbox=")), "--sandbox=seatbelt")
bootstrap = open(os.path.join(build, "bootstrap.js"), encoding="utf-8").read()
SCRIPT = "var source={ok:function(){return 1;},sync:function(n){var s=0;for(var i=0;i<n;i++){s+=bridge.httpGet('u').length;}return s;}};"
HTML = "<div>x</div>"


def reply(op, args):
    return HTML


def ms(x):
    return f"{x*1000:.3f}"


print(f"# {exe} {mode}")
# cold start: spawn -> Init (sandbox + bootstrap eval) -> InitOk; and spawn -> first byte (process only)
cold, spawn_only = [], []
for _ in range(20):
    t0 = time.perf_counter()
    h = Helper(exe, [mode])
    t1 = time.perf_counter()
    r = h.init(bootstrap, SCRIPT)
    assert r["t"] == "InitOk", r
    f, _, _ = h.invoke("ok")
    t2 = time.perf_counter()
    assert f["t"] == "Result"
    cold.append(t2 - t0); spawn_only.append(t1 - t0)
    h.close()
print(f"cold start spawn->first Result (20 runs): median {ms(statistics.median(cold))} ms, min {ms(min(cold))}, max {ms(max(cold))}")

# idle RSS before bootstrap (spawn, Init with empty bootstrap) and after
h = Helper(exe, [mode])
time.sleep(0.3)
rss_idle = h.rss_kb()
h.init("", "var source={ok:function(){return 1;}};")
time.sleep(0.1)
rss_empty_ctx = h.rss_kb()
h.close()
h = Helper(exe, [mode])
h.init(bootstrap, SCRIPT)
h.invoke("ok")
time.sleep(0.3)
rss_boot = h.rss_kb()

# empty invoke round trip
N = 2000
for _ in range(100):
    h.invoke("ok")
ts = []
for _ in range(N):
    t0 = time.perf_counter()
    h.invoke("ok")
    ts.append(time.perf_counter() - t0)
ts.sort()
print(f"empty invoke round trip ({N}): mean {ms(statistics.mean(ts))} ms, p50 {ms(ts[N//2])}, p99 {ms(ts[int(N*.99)])}")

# 1000 sync bridge calls inside one invocation
runs = []
for _ in range(10):
    t0 = time.perf_counter()
    f, _, _ = h.invoke("sync", args=[1000], bridge=reply)
    runs.append(time.perf_counter() - t0)
    assert f["t"] == "Result", f
per = [r / 1000 for r in runs]
print(f"sync bridge call round trip (1000 calls x 10): mean {ms(statistics.mean(per))} ms/call, best run {ms(min(per))}, worst run {ms(max(per))}")
time.sleep(0.2)
rss_after = h.rss_kb()
print(f"RSS: spawned & idle (no Init) {rss_idle/1024:.1f} MiB; Init without bootstrap {rss_empty_ctx/1024:.1f} MiB; bootstrap loaded + warm {rss_boot/1024:.1f} MiB; after 20k bridge calls {rss_after/1024:.1f} MiB")
h.close()

# RSS after a memory bomb at the ADR default heap limit (64 MiB): the helper must stay below the 128 MiB process budget.
h = Helper(exe, [mode])
h.init(bootstrap, "var source={mem:function(){var a=[];for(;;)a.push('x'.repeat(1024)+a.length);}};", {"heapLimitBytes": 64 << 20, "invokeTimeoutMs": 10000})
f, _, _ = h.invoke("mem", timeout=30)
time.sleep(0.2)
print(f"memory bomb @64 MiB heap limit -> {f.get('code')}; RSS afterwards {h.rss_kb()/1024:.1f} MiB (allocator may retain freed pages)")
h.close()
