#!/usr/bin/env python3 -I
"""Runs isolation_probe with and without the helper's lockdown (ADR-0002 §13, S3).

usage: run_isolation_probe.py <isolation_probe binary>
The probe gets a reachable local TCP listener, a readable user file, a writable directory, a secret
in its environment and an inherited descriptor 3. The control run (no lockdown) must be able to
use every one of them, which shows the probes work; the locked-down run must use none.
"""
import os
import socket
import subprocess
import sys
import tempfile

probe = sys.argv[1]
work = tempfile.mkdtemp(prefix="ssc-probe-")
secret = os.path.join(work, "secret.txt")
with open(secret, "w") as f:
    f.write("secret")
# Descriptor 3 first (before anything else takes that number): the lockdown must close it.
inherited = os.open(secret, os.O_RDONLY)
if inherited != 3:
    os.dup2(inherited, 3)
listener = socket.socket()
listener.bind(("127.0.0.1", 0))
listener.listen(8)
port = str(listener.getsockname()[1])
env = dict(os.environ, SHINSOU_SECRET_TOKEN="topsecret")


def run(*mode):
    r = subprocess.run(
        [probe, *mode, port, secret, work], env=env, capture_output=True, text=True, timeout=30,
        pass_fds=(3,),
    )
    return r.returncode, r.stdout


code, out = run("--control")
print("===== control (no lockdown), exit", code)
print(out.rstrip())
allowed = [line.split()[0] for line in out.splitlines() if " ALLOWED " in line]
control_ok = len(allowed) == 14  # every probe succeeds without the lockdown, so each one can detect a leak
code, out = run()
print("===== helper lockdown, exit", code)
print(out.rstrip())
locked_ok = code == 0 and "RESULT PASS" in out
print("\nISOLATION PROBE:", "PASS" if locked_ok and control_ok else "FAIL",
      f"(control allowed {len(allowed)} probes)")
sys.exit(0 if locked_ok and control_ok else 1)
