#!/usr/bin/env python3 -I
"""Runs isolation_probe with and without the helper's lockdown (ADR-0002 §13, S3).

usage: run_isolation_probe.py <isolation_probe binary>
The probe gets a reachable local TCP listener, a readable user file, a writable directory, a secret
in its environment and an inherited descriptor or handle. On Windows it also gets the pid of a
confined sandbox of another package. The control run (no lockdown) must be able to use every one of
them, which shows the probes work; the locked-down run must use none.
"""
import os
import socket
import subprocess
import sys
import tempfile

WINDOWS = os.name == "nt"
probe = os.path.abspath(sys.argv[1])
work = tempfile.mkdtemp(prefix="ssc-probe-")
secret = os.path.join(work, "secret.txt")
with open(secret, "w") as f:
    f.write("secret")
inherited = os.open(secret, os.O_RDONLY)
if WINDOWS:
    import msvcrt

    handle = msvcrt.get_osfhandle(inherited)
    os.set_handle_inheritable(handle, True)
    extra = [str(handle), str(os.getpid())]
elif inherited != 3:
    # Descriptor 3 (before anything else takes that number): the lockdown must close it.
    os.dup2(inherited, 3)
listener = socket.socket()
listener.bind(("127.0.0.1", 0))
listener.listen(8)
port = str(listener.getsockname()[1])
env = dict(os.environ, SHINSOU_SECRET_TOKEN="topsecret")

sibling = None
if WINDOWS:
    # A sandbox of another package, confined the same way: the probe must not be able to open it.
    sibling = subprocess.Popen([probe, "--package-key", "probesibling", "--hang"],
                               stdin=subprocess.PIPE, stdout=subprocess.PIPE, text=True)
    line = sibling.stdout.readline()
    if not line.startswith("PID "):
        code = sibling.wait(timeout=30)
        sys.exit(f"the sibling sandbox did not start: {line!r} (exit {code} = {code & 0xFFFFFFFF:#010x})")
    extra.append(line.split()[1])


STATUS_INVALID_HANDLE = 0xC0000008


def run(*mode):
    args = [probe, *mode, port, secret, work] + (extra if WINDOWS else [])
    kwargs = dict(close_fds=False) if WINDOWS else dict(pass_fds=(3,))
    r = subprocess.run(args, env=env, capture_output=True, text=True, timeout=60, **kwargs)
    if not WINDOWS:
        return r.returncode, r.stdout
    # The inherited handle is probed in a run of its own (see isolation_probe.c): strict handle
    # checks end the confined copy at the first use of a handle it does not have.
    h = subprocess.run([probe, *mode, "--handle", extra[0]], env=env, capture_output=True, text=True,
                       timeout=60, **kwargs)
    line = next((l for l in h.stdout.splitlines() if l.startswith("inherited handle ")), None)
    if line is None and (h.returncode & 0xFFFFFFFF) == STATUS_INVALID_HANDLE:
        line = "inherited handle BLOCKED error=STATUS_INVALID_HANDLE (strict handle checks)"
    if line is None:
        line = f"inherited handle ALLOWED (no verdict, exit {h.returncode & 0xFFFFFFFF:#010x})"
    lines = [l for l in r.stdout.splitlines() if not l.startswith("PROBES ")]
    if " ALLOWED" in line:
        lines = [l.replace("RESULT PASS", "RESULT FAIL") for l in lines]
    return r.returncode, "\n".join([line, *lines, f"PROBES {count(r.stdout) + 1}"]) + "\n"


def count(out):
    return next((int(line.split()[1]) for line in out.splitlines() if line.startswith("PROBES ")), -1)


try:
    code, out = run("--control")
    print("===== control (no lockdown), exit", code)
    print(out.rstrip())
    allowed = [line for line in out.splitlines() if " ALLOWED " in line]
    total = count(out)
    control_ok = total > 0 and len(allowed) == total  # each probe can detect a leak
    code, out = run(*(["--package-key", "probe"] if WINDOWS else []))
    print("===== helper lockdown, exit", code, f"({code & 0xFFFFFFFF:#010x})")
    print(out.rstrip())
    locked_ok = code == 0 and "RESULT PASS" in out and count(out) == total
finally:
    if sibling:
        sibling.stdin.close()
        sibling.wait(timeout=30)
print("\nISOLATION PROBE:", "PASS" if locked_ok and control_ok else "FAIL",
      f"(control allowed {len(allowed)} of {total} probes)")
sys.exit(0 if locked_ok and control_ok else 1)
