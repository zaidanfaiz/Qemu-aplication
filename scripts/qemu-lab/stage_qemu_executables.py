#!/usr/bin/env python3
"""Stage verified Android PIE executables for an incomplete APK test.

QEMU was built from upstream and previously verified as Bionic AArch64 ELF.
A targetSdk 29+ app must package executable code in nativeLibraryDir, not
extract executables into writable app-private files.
"""
from pathlib import Path
import json
import re
import shutil
import subprocess
import sys

if len(sys.argv) != 3:
    sys.exit("Usage: stage_qemu_executables.py EXTRACTED_RUNTIME APP_JNILIBS_DIR")
root = Path(sys.argv[1]).resolve()
dst = Path(sys.argv[2]).resolve()
srcbin = root / "extracted/data/data/com.termux/files/usr/bin"
auditdir = Path("native-runtime-audit")
auditdir.mkdir(exist_ok=True)
dst.mkdir(parents=True, exist_ok=True)
system_libs = {"libc.so", "libm.so", "libdl.so"}
report = {
    "status": "INCOMPLETE; NOT BOOTABLE",
    "source": "qemu/QEMU v11.1.2 minimal Android ARM64 build",
    "model": "Android native PIE executable / ProcessBuilder",
    "warning": "Shared libraries, firmware and Termux-prefixed paths not relocated",
    "binaries": {},
}
for guest in ("x86_64", "aarch64"):
    source = srcbin / f"qemu-system-{guest}"
    if not source.is_file() or source.stat().st_size < 1024:
        sys.exit(f"Missing native runtime: {source}")
    header = subprocess.check_output(["readelf", "-h", str(source)], text=True)
    program = subprocess.check_output(["readelf", "-l", str(source)], text=True)
    dynamic = subprocess.check_output(["readelf", "-d", str(source)], text=True)
    if not re.search(r"Machine:\s*AArch64", header):
        sys.exit(f"Wrong Android HOST architecture: {source}")
    if "INTERP" not in program or "/system/bin/linker64" not in program:
        sys.exit(f"Not an Android Bionic executable: {source}")
    target = dst / f"libqemu-system-{guest}.so"
    if target.exists():
        sys.exit(f"Refusing to overwrite existing library: {target}")
    shutil.copy2(source, target)
    deps = re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", dynamic)
    runpath = re.findall(r"\(RUNPATH\)\s+Library runpath: \[([^\]]+)\]", dynamic)
    missing = sorted(set(deps) - system_libs)
    report["binaries"][guest] = {
        "filename": target.name, "bytes": source.stat().st_size,
        "ELF": "AArch64 ET_DYN PIE with PT_INTERP",
        "needed": deps, "still_missing": missing, "runpath": runpath,
    }
    print(f"STAGED {guest}: {target.name} - {len(deps)} deps, {len(missing)} non-system missing")
(auditdir / "STAGING_AUDIT.json").write_text(json.dumps(report, indent=2))
(auditdir / "NOT_BOOTABLE.txt").write_text(
    "EXPERIMENTAL PIE-PACKAGING APK ONLY. Not a functioning QEMU app.\n"
    "Missing dependency closure, firmware and path relocation; see STAGING_AUDIT.json.\n"
)
if not any(report["binaries"][a]["still_missing"] for a in report["binaries"]):
    sys.exit("Unexpected lack of missing deps: re-evaluate audit assumptions.")
