#!/usr/bin/env python3
"""Reduce optional QEMU runtime link dependencies for an Android-only headless VM trial.

Run AFTER prepare_termux_recipe.py, on the pinned 11.1.2 Termux recipe.
Preserves VNC and both softmmu targets. Does not make the result APK/JNI-ready.
"""
from pathlib import Path
import sys

if len(sys.argv) != 2:
    sys.exit("Usage: minimize_termux_recipe.py path/to/build.sh")
path = Path(sys.argv[1])
original = path.read_text()
if 'TERMUX_PKG_VERSION="1:11.1.2"' not in original:
    sys.exit("QEMU recipe version must be v11.1.2")
features_to_disable = [
    "gnutls", "nettle", "curses", "vnc-jpeg", "png", "virtfs",
    "curl", "lzo", "bzip2", "libssh", "bochs", "cloop", "dmg",
    "parallels", "qed", "slirp", "spice", "libusb", "usb-redir",
    "kvm",
]
# Version 2: remove optional host audio, debug unwind and zstd linkage.
# QEMU's Termux recipe forces --audio-drv-list=pa, which must not conflict
# with --disable-pa. QEMU keeps the generic no-audio backend.
extra_to_disable = ["alsa", "pa", "jack", "libdw", "zstd"]
audio_lines = [line for line in original.splitlines(keepends=True)
               if "--audio-drv-list=pa" in line]
if len(audio_lines) != 1:
    sys.exit("Forced PulseAudio configure flag changed; recipe needs re-audit")
modified = original.replace(audio_lines[0], "")
for feature in features_to_disable:
    previous = f"--enable-{feature}"
    if previous not in modified:
        sys.exit(f"Termux recipe changed: expected {previous!r}; refusing silent fallback")
    modified = modified.replace(previous, f"--disable-{feature}")
# The original Termux recipe does NOT explicitly enable these optional flags.
# Add disable options to QEMU configure instead of expecting --enable-FOO lines.
target_arg = '--target-list="$QEMU_TARGETS"'
if modified.count(target_arg) != 1:
    sys.exit("Cannot locate QEMU configure target-list option")
modified = modified.replace(target_arg,
    " ".join("--disable-" + feature for feature in extra_to_disable) + " " + target_arg)
# Keep VNC, direct kernel boot, basic RAW disks and device-tree support.
for required in ["--enable-vnc", "--enable-fdt=system", "aarch64-softmmu,x86_64-softmmu"]:
    if required not in modified:
        sys.exit(f"Essential QEMU feature missing: {required}")
path.write_text(modified)
print("Disabled optional features:", ", ".join(features_to_disable + extra_to_disable))
print("Keeping VNC + arm64/x86_64 system emulation + FDT support")
print("No JNI/shared-library format conversion is performed.")
