#!/usr/bin/env python3
"""Start QEMU on GitHub Linux runner, exchange QMP messages, quit cleanly."""
from pathlib import Path
import json
import subprocess
import sys

if len(sys.argv) != 2:
    sys.exit("Usage: qmp_smoke.py BUILD_DIR")
build = Path(sys.argv[1])
for arch, machine in [("aarch64", "virt"), ("x86_64", "pc")]:
    binary = build / f"qemu-system-{arch}"
    version = subprocess.run([str(binary), "--version"], capture_output=True, text=True, timeout=20)
    if version.returncode:
        sys.exit(f"Version failed for {arch}: {version.stderr}")
    print(version.stdout.splitlines()[0])
    script = "\n".join(json.dumps({"execute": s}) for s in
                       ("qmp_capabilities", "query-status", "quit")) + "\n"
    command = [str(binary), "-machine", machine, "-accel", "tcg",
               "-m", "128M", "-nodefaults", "-nic", "none", "-display", "none",
               "-monitor", "none", "-serial", "none", "-S", "-qmp", "stdio"]
    run = subprocess.run(command, input=script, capture_output=True, text=True, timeout=30)
    if run.returncode != 0 or '"QMP"' not in run.stdout or '"return"' not in run.stdout:
        sys.exit(f"QMP failed for {arch} (rc={run.returncode})\n{run.stdout[-1000:]}\n{run.stderr[-1000:]}")
    print(f"PASS: {arch} QEMU/QMP")
