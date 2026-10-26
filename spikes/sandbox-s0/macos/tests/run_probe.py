#!/usr/bin/env python3 -I
"""Runs isolation-probe in every sandbox variant (ADR-0002 §4.4 / §13). usage: run_probe.py <build-dir>"""
import os, socket, subprocess, sys

build = sys.argv[1]
srv = socket.socket(); srv.bind(("127.0.0.1", 0)); srv.listen(5)  # a reachable local listener: connect must still fail
port = srv.getsockname()[1]
secret = os.path.expanduser("~/.zshrc")
if not os.path.exists(secret):
    secret = os.path.expanduser("~/.profile"); open(secret, "a").close()
env = dict(os.environ, SHINSOU_SECRET_TOKEN="topsecret")
variants = [
    ("control: no sandbox", "isolation-probe", "none"),
    ("Seatbelt sandbox_init (deny-all profile)", "isolation-probe", "seatbelt"),
    ("App Sandbox entitlement only", "isolation-probe-appsandbox", "none"),
    ("App Sandbox entitlement + Seatbelt", "isolation-probe-appsandbox", "seatbelt"),
]
results = {}
for title, exe, mode in variants:
    r = subprocess.run([os.path.join(build, exe), f"--sandbox={mode}", str(port), secret, "/tmp"],
                       env=env, capture_output=True, text=True, timeout=30)
    print(f"===== {title} (exit {r.returncode})")
    print(r.stdout.rstrip() or r.stderr.rstrip() or "(no output; signal %d)" % -r.returncode)
    results[title] = r.returncode
# "App Sandbox + Seatbelt" is expected to fail with exit 3: sandbox_init() returns EPERM inside an App Sandbox process.
ok = results["Seatbelt sandbox_init (deny-all profile)"] == 0 and results["control: no sandbox"] != 0
print("\nSEATBELT PROBE:", "PASS" if results["Seatbelt sandbox_init (deny-all profile)"] == 0 else "FAIL")
sys.exit(0 if ok else 1)
