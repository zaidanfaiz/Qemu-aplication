#!/usr/bin/env python3
"""Build checked out official qemu/QEMU with existing Android-Bionic Termux patches."""
import re
import sys
from pathlib import Path

if len(sys.argv) != 3:
    sys.exit("Usage: prepare_termux_recipe.py RECIPE UPSTREAM_DIR")
recipe, upstream = map(lambda s: Path(s).resolve(), sys.argv[1:])
if not recipe.is_file() or not (upstream / "configure").is_file():
    sys.exit("Missing QEMU recipe or official checked-out source")
original = recipe.read_text()
if 'TERMUX_PKG_VERSION="1:11.1.2"' not in original:
    sys.exit("Termux recipe QEMU version is not v11.1.2; refusing incompatible patch set")
modified = original
patterns = [
    (r"(?m)^TERMUX_PKG_SRCURL=.*$",
     "TERMUX_PKG_SRCURL=file:///home/builder/termux-packages/sources/qemu"),
    (r"(?m)^TERMUX_PKG_SHA256=.*$", "TERMUX_PKG_SHA256=SKIP_CHECKSUM"),
    (r'(?s)\tlocal QEMU_TARGETS=""\n.*?(?=\tCFLAGS\+=)',
     '\tlocal QEMU_TARGETS="aarch64-softmmu,x86_64-softmmu"\n\n'),
]
for pattern, replacement in patterns:
    modified, n = re.subn(pattern, lambda _m, value=replacement: value, modified)
    if n != 1:
        sys.exit(f"Patch conflict: expected one match for {pattern!r}, got {n}")
recipe.write_text(modified)
print("Prepared upstream qemu/QEMU 11.1.2 for Android ARM64 cross-compile")
