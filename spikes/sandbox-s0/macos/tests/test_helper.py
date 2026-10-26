#!/usr/bin/env python3 -I
"""Scenario tests for shinsou-sandbox (mirrors core/test/core_test.c over the real pipe protocol).
usage: test_helper.py <build-dir> [--sandbox=seatbelt|none]"""
import os, signal, struct, subprocess, sys, threading, time

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from host import Helper

build = sys.argv[1]
mode = next((a for a in sys.argv[2:] if a.startswith("--sandbox=")), "--sandbox=seatbelt")
exe = os.path.join(build, next((a[6:] for a in sys.argv[2:] if a.startswith("--exe=")), "shinsou-sandbox"))
bootstrap = open(os.path.join(build, "bootstrap.js"), encoding="utf-8").read()
fails = []


def check(cond, msg):
    if not cond:
        fails.append(msg)
        print("  FAIL", msg)


SCRIPT = r"""var source={
 ok:function(){return 'ok';},
 echo:function(a){return a;},
 parse:function(){var d=Jsoup.parse(bridge.httpGet('https://x.test/'),'https://x.test/');
   return new MangasPage(d.select('.item a').map(function(a){var m=SManga.create();m.title=a.text();m.url=a.attr('href');return m;}),false);},
 loop:function(){for(;;){}},
 swallow:function(){for(;;){try{for(;;){}}catch(e){}}},
 memory:function(){var a=[];for(;;)a.push('x'.repeat(1024)+a.length);},
 memoryCaught:function(){var a=[];try{for(;;)a.push(new Array(1e5).fill(1));}catch(e){a=null;for(;;)Math.random();}},
 recurse:function(){function f(){return f()+1;}return f();},
 regex:function(){return /(a+)+$/.test('a'.repeat(40)+'!');},
 bridgeFlood:function(){for(;;){try{bridge.getPreference('x');}catch(e){}}},
 big:function(){return 'y'.repeat(2<<20);},
 globals:function(){return [typeof std,typeof os,typeof require,typeof process,typeof fetch,typeof XMLHttpRequest].join(',');},
 syncloop:function(n){var s=0;for(var i=0;i<n;i++){s+=bridge.httpGet('u').length;}return s;},
 logs:function(){bridge.log('hello');return 1;},
 fetchMany:async function(){var r=await Promise.all([1,2,3,4,5,6,7,8,9,10].map(function(i){return bridge.net.fetch('https://x.test/'+i);}));return r.map(function(x){return x.text();});},
 queueFull:async function(){var ps=[];for(var i=0;i<71;i++)ps.push(bridge.net.fetch('https://x.test/'+i).then(function(){return 'ok';},function(e){return e.code;}));var r=await Promise.all(ps);return r.filter(function(x){return x==='ASYNC_QUEUE_FULL';}).length;},
 leak:function(){bridge.net.fetch('https://x.test/late');return 'returned';},
 stalled:function(){return new Promise(function(){});},
 mixed:async function(){var a=bridge.net.fetch('https://x.test/a');var s=bridge.httpGet('https://x.test/');var r=await a;return [r.text(),s.length>0];},
 waitFetch:async function(){var r=await bridge.net.fetch('https://x.test/slow');return r.text();},
};"""
LIMITS = {"heapLimitBytes": 16 << 20, "stackLimitBytes": 1 << 20, "initTimeoutMs": 5000, "invokeTimeoutMs": 400,
          "maxBridgeCalls": 4096, "maxAsyncInflight": 6, "maxAsyncQueue": 64, "maxResultBytes": 1 << 20}
HTML = '<div class="item"><a href="/m/1">One</a></div><div class="item"><a href="/m/2">Two</a></div>'


def bridge(op, args):
    return HTML if op == "httpGet" else None


def new_helper(limits=LIMITS):
    h = Helper(exe, [mode])
    r = h.init(bootstrap, SCRIPT, limits)
    check(r and r["t"] == "InitOk", f"init: {r}")
    return h


def run(h, method, label, want=None, **kw):
    t0 = time.time()
    f, logs, cancels = h.invoke(method, bridge=kw.pop("bridge", bridge), **kw)
    ms = (time.time() - t0) * 1000
    got = None if f is None else (f["t"] if f["t"] == "Result" else f["code"])
    print(f"  {label:<38} -> {got} ({ms:.0f} ms)")
    if want:
        check(got == want, f"{label}: want {want} got {got} {f}")
    return f, logs, cancels


print(f"[helper={exe} {mode}]")
h = new_helper()
print("[functional]")
f, _, _ = run(h, "parse", "sync bridge + DOM")
check(f["t"] == "Result" and f["value"]["mangas"][1]["title"] == "Two" if f["t"] == "Result" and "mangas" in f["value"] else f["t"] == "Result" and "Two" in str(f["value"]), f"parse: {f}")
f, _, _ = run(h, "globals", "forbidden globals")
check(f["t"] == "Result" and f["value"] == "undefined,undefined,undefined,undefined,undefined,undefined", f"globals {f}")
f, logs, _ = run(h, "logs", "log frame")
check(logs == ['["hello"]'], f"logs {logs}")
f, _, _ = run(h, "echo", "args round trip (unicode)", args=[{"a": "日本語\u0000\"\\é😀", "n": [1, 2.5, None]}])
check(f["t"] == "Result" and f["value"]["a"] == "日本語\u0000\"\\é😀", f"echo {f}")

print("[limits; helper must keep serving afterwards]")
for method, code, label in [
    ("loop", "LIMIT_TIME", "infinite loop"), ("swallow", "LIMIT_TIME", "try/catch swallowing interrupt"),
    ("regex", "LIMIT_TIME", "catastrophic regex"), ("recurse", "LIMIT_STACK", "unbounded recursion"),
    ("memory", "LIMIT_MEMORY", "memory bomb"), ("memoryCaught", "LIMIT_TIME", "OOM caught then spin"),
    ("bridgeFlood", "LIMIT_BRIDGE", "bridge flood"), ("big", "LIMIT_RESULT", "oversized result"),
    ("missing", "NO_SUCH_HOOK", "missing hook")]:
    run(h, method, label, code, bridge=lambda op, a: None)
    run(h, "ok", "  next invocation", "Result")
check(h.p.poll() is None, "helper survived all limit cases")

print("[sync bridge when the host answers slowly / never]")
f, _, _ = run(h, "parse", "bridge reply still works", "Result")
t0 = time.time()
f = None
def never(op, a):
    time.sleep(0); return None
h.next_call += 1
cid = h.next_call
h.send({"t": "Invoke", "callId": cid, "method": "parse", "args": []})
fr = h.recv(5)
check(fr["t"] == "BridgeCall", f"expected BridgeCall {fr}")
fr = h.recv(10)  # we never answer; helper must give up at the invocation deadline
print(f"  unanswered BridgeCall -> {fr and fr['t']} after {(time.time()-t0)*1000:.0f} ms (bridge gave up at the invocation deadline)")
check(fr and fr["t"] in ("Failure", "Result"), f"unanswered bridge must end the invocation: {fr}")
run(h, "ok", "  next invocation", "Result")

print("[cancel]")
def cancel_after(delay, hh=None):
    hh = hh or h
    cid = hh.next_call + 1
    threading.Timer(delay, lambda: hh._safe_send({"t": "Cancel", "callId": cid})).start()
cancel_h = new_helper({**LIMITS, "invokeTimeoutMs": 30000})
cancel_after(0.1, cancel_h)
t0 = time.time()
f, _, _ = run(cancel_h, "loop", "cancel busy loop", "CANCELLED")
check(time.time() - t0 < 1.5, "cancel latency")
run(cancel_h, "ok", "  next invocation", "Result")
cancel_after(0.15, cancel_h)
f, _, c = run(cancel_h, "waitFetch", "cancel while waiting on fetch", "CANCELLED", fetch=lambda op, a: (30, {"status": 200}))
check(len(c) == 1, f"AsyncCancel forwarded for pending op: {c}")
run(cancel_h, "ok", "  next invocation", "Result")
# Cancel while blocked in a sync bridge call that the host never answers.
cid = cancel_h.next_call + 1; cancel_h.next_call = cid
cancel_h.send({"t": "Invoke", "callId": cid, "method": "parse", "args": []})
check(cancel_h.recv(5)["t"] == "BridgeCall", "BridgeCall")
cancel_h.cancel(cid)
fr = cancel_h.recv(5)
print(f"  cancel while blocked in sync bridge -> {fr.get('code')}")
check(fr["t"] == "Failure" and fr["code"] == "CANCELLED", f"{fr}")
# Cancel that arrives before the invocation: must not poison the next one.
cancel_h.cancel(9999)
run(cancel_h, "ok", "  unrelated stale cancel ignored", "Result")
cancel_h.close()

print("[async]")
inflight, peak = [0], [0]
lock = threading.Lock()
def tracked(f, cid):
    with lock:
        if f["t"] == "AsyncBridgeCall":
            inflight[0] += 1; peak[0] = max(peak[0], inflight[0])
        elif f["t"] == "AsyncCancel":
            inflight[0] -= 1
def slow_fetch(op, a):
    def done():
        time.sleep(0)
    return (0.1, {"status": 200, "body": f"op-{op}"})
t0 = time.time()
orig_safe = h._safe_send
def counting_send(obj):
    if obj.get("t") == "AsyncBridgeResult":
        with lock: inflight[0] -= 1
    orig_safe(obj)
h._safe_send = counting_send
f, _, _ = run(h, "fetchMany", "10 parallel bridge.net.fetch", "Result", fetch=slow_fetch, on_frame=tracked)
el = time.time() - t0
print(f"  peak in flight {peak[0]}, wall {el*1000:.0f} ms (serial would be >= 1000 ms)")
check(peak[0] <= 6, f"in-flight cap {peak[0]}")
check(el < 0.8, "fetches ran concurrently")
check(f["t"] == "Result" and len(f["value"]) == 10, f"{f}")
h._safe_send = orig_safe
f, _, _ = run(h, "queueFull", "queue overflow rejects", "Result", fetch=lambda op, a: (0, {"status": 200}))
check(f["value"] == 1, f"queueFull {f}")
f, _, c = run(h, "leak", "pending op at invocation end", "Result", fetch=lambda op, a: (5, {"status": 200}))
check(len(c) == 1, f"AsyncCancel sent for leaked op {c}")
run(h, "ok", "  next invocation (late result dropped)", "Result")
f, _, _ = run(h, "mixed", "sync + async interleaved", "Result", fetch=lambda op, a: (0.05, {"status": 200, "body": "A"}))
check(f["value"] == ["A", True], f"mixed {f}")
run(h, "stalled", "never-settling promise", "PROMISE_STALLED")
run(h, "ok", "  next invocation", "Result")

print("[protocol hardening]")
for label, payload in [("oversized length", struct.pack(">I", (1 << 20) + 1)), ("malformed json", struct.pack(">I", 3) + b"{x}"),
                       ("zero length", struct.pack(">I", 0))]:
    hh = new_helper()
    hh.p.stdin.write(payload); hh.p.stdin.flush()
    try:
        hh.p.wait(timeout=3); code = hh.p.returncode
    except subprocess.TimeoutExpired:
        code = None
    print(f"  {label:<20} -> helper exit {code}")
    check(code == 1, f"{label}: helper must exit with 1, got {code}")
hh = Helper(exe, [mode])
hh.send({"t": "Init", "v": 2, "bootstrap": bootstrap, "script": SCRIPT})
r = hh.recv(5)
print(f"  unknown protocol version -> {r}")
check(r["t"] == "Failure" and r["code"] == "BAD_PROTOCOL_VERSION", "fail closed on version")
hh.close()

print("[lifecycle]")
def alive(pid):
    try:
        os.kill(pid, 0); return True
    except ProcessLookupError:
        return False
def wait_gone(pid, t=3):
    end = time.time() + t
    while time.time() < end:
        if not alive(pid): return True
        time.sleep(0.02)
    return False
# 1. host closes stdin (normal shutdown)
hh = new_helper(); pid = hh.p.pid
hh.p.stdin.close(); t0 = time.time()
hh.p.wait(timeout=3)
print(f"  stdin EOF while idle -> exit {hh.p.returncode} in {(time.time()-t0)*1000:.0f} ms")
check(hh.p.returncode == 0, "idle EOF exit code")
# 2. EOF while the helper is stuck in an infinite loop
hh = new_helper({**LIMITS, "invokeTimeoutMs": 60000}); pid = hh.p.pid
hh.send({"t": "Invoke", "callId": 1, "method": "loop", "args": []}); time.sleep(0.3)
hh.p.stdin.close(); t0 = time.time()
try:
    hh.p.wait(timeout=3)
except subprocess.TimeoutExpired:
    pass
print(f"  stdin EOF while busy-looping -> exit {hh.p.returncode} in {(time.time()-t0)*1000:.0f} ms")
check(hh.p.returncode is not None, "EOF must terminate a busy helper")
if hh.p.returncode is None: hh.p.kill()
# 3. helper killed: host unaffected
hh = new_helper(); pid = hh.p.pid
os.kill(pid, signal.SIGKILL)
fr = hh.frames.get(timeout=3) if True else None
print(f"  helper SIGKILL -> host sees EOF ({fr}), host still running")
check(fr is None, "host observes helper death as EOF")
hh.p.wait()
check(hh.p.returncode == -signal.SIGKILL, "returncode")
# 4. host SIGKILLed: no orphan helper
spawner = r'''
import subprocess, sys, time, struct, json
p = subprocess.Popen([sys.argv[1], sys.argv[2]], stdin=subprocess.PIPE, stdout=subprocess.PIPE, env={})
b = json.dumps({"t":"Init","v":1,"bootstrap":open(sys.argv[3]).read(),"script":"var source={loop:function(){for(;;){}}};"}).encode()
p.stdin.write(struct.pack(">I", len(b)) + b); p.stdin.flush(); p.stdout.read(4)
b = json.dumps({"t":"Invoke","callId":1,"method":"loop","args":[]}).encode()
p.stdin.write(struct.pack(">I", len(b)) + b); p.stdin.flush()
print(p.pid, flush=True)
time.sleep(60)
'''
sp = subprocess.Popen([sys.executable, "-I", "-c", spawner, exe, mode, os.path.join(build, "bootstrap.js")], stdout=subprocess.PIPE, text=True)
helper_pid = int(sp.stdout.readline())
time.sleep(0.3)
check(alive(helper_pid), "helper alive while host alive")
sp.kill(); sp.wait()
gone = wait_gone(helper_pid)
print(f"  host SIGKILL while helper busy-looping -> helper {'gone' if gone else 'ORPHANED'}")
check(gone, "no orphan helper after host death")
if not gone: os.kill(helper_pid, signal.SIGKILL)

h.close()
cancel_h.close()
print("\n" + (f"{len(fails)} FAILURE(S)" if fails else "ALL PASSED"))
sys.exit(1 if fails else 0)
