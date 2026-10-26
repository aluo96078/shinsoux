"""Host-side driver for shinsou-sandbox (ADR-0002 S0): SandboxProtocol v1 over stdin/stdout pipes."""
import json, os, queue, struct, subprocess, threading, time

MAX_FRAME = 1 << 20


class Helper:
    def __init__(self, exe, args=(), env=None):
        self.p = subprocess.Popen([exe, *args], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                  stderr=subprocess.PIPE, env=env or {}, close_fds=True)
        self.frames = queue.Queue()
        self.next_call = 0
        self.stderr = b""
        threading.Thread(target=self._read, daemon=True).start()
        threading.Thread(target=self._read_err, daemon=True).start()

    def _read(self):
        out = self.p.stdout
        while True:
            hdr = out.read(4)
            if len(hdr) < 4:
                self.frames.put(None)
                return
            (n,) = struct.unpack(">I", hdr)
            self.frames.put(json.loads(out.read(n).decode("utf-8")))

    def _read_err(self):
        self.stderr = self.p.stderr.read()

    def send(self, obj):
        b = json.dumps(obj, separators=(",", ":")).encode("utf-8")
        assert len(b) <= MAX_FRAME
        self.p.stdin.write(struct.pack(">I", len(b)) + b)
        self.p.stdin.flush()

    def recv(self, timeout=10):
        return self.frames.get(timeout=timeout)

    def init(self, bootstrap, script, limits=None, timeout=15):
        self.send({"t": "Init", "v": 1, "bootstrap": bootstrap, "script": script, "limits": limits or {}})
        f = self.recv(timeout)
        return f

    def invoke(self, method, args=None, bridge=None, fetch=None, on_frame=None, timeout=60, call_id=None):
        """Runs one invocation, answering BridgeCall via `bridge(op, args)` and AsyncBridgeCall via
        `fetch(opId, args) -> (delay_s, value)`. Returns (final_frame, logs, async_cancels)."""
        self.next_call += 1
        cid = call_id or self.next_call
        self.send({"t": "Invoke", "callId": cid, "method": method, "args": args or []})
        logs, cancels, timers = [], [], []
        deadline = time.time() + timeout
        while True:
            f = self.recv(max(0.1, deadline - time.time()))
            if f is None:
                return None, logs, cancels
            if on_frame:
                on_frame(f, cid)
            t = f["t"]
            if t in ("Result", "Failure") and f.get("callId") == cid:
                for tm in timers:
                    tm.cancel()
                return f, logs, cancels
            if t == "Log":
                logs.append(f["message"])
            elif t == "AsyncCancel":
                cancels.append(f["opId"])
            elif t == "BridgeCall":
                ok, val = True, None
                if bridge:
                    val = bridge(f["op"], f["args"])
                self.send({"t": "BridgeResult", "seq": f["seq"], "ok": ok, "value": val})
            elif t == "AsyncBridgeCall":
                delay, val = fetch(f["opId"], f["args"]) if fetch else (0, {"status": 200, "body": "x"})
                op = f["opId"]
                tm = threading.Timer(delay, lambda op=op, val=val: self._safe_send(
                    {"t": "AsyncBridgeResult", "opId": op, "ok": True, "value": val}))
                tm.start()
                timers.append(tm)

    def _safe_send(self, obj):
        try:
            self.send(obj)
        except (BrokenPipeError, ValueError, OSError):
            pass

    def cancel(self, call_id):
        self.send({"t": "Cancel", "callId": call_id})

    def rss_kb(self):
        out = subprocess.run(["ps", "-o", "rss=", "-p", str(self.p.pid)], capture_output=True, text=True).stdout.strip()
        return int(out) if out else 0

    def close(self):
        try:
            self.p.stdin.close()
        except Exception:
            pass
        try:
            self.p.wait(timeout=5)
        except subprocess.TimeoutExpired:
            self.p.kill()
            self.p.wait()
