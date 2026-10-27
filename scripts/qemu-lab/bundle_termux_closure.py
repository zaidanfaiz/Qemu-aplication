#!/usr/bin/env python3
"""Experimental Android ARM64 dependency closure from official Termux apt packages.

Inputs: extracted upstream QEMU ARM64 minimal artifact, staging JNI library dir.
Downloads package indices over HTTPS, verifies .deb SHA256 against index, and
finds libraries recursively using DT_NEEDED. Renames/patchelf-rewrites the
closure to Android APK-compatible lib*.so names.

This does not verify device boot or upstream GPL/LGPL binary distribution.
"""
import gzip
import hashlib
import io
import json
import os
from pathlib import Path
from rewrite_elf_inplace import aliases, rewrite
import re
import shutil
import subprocess
import sys
import urllib.request

if len(sys.argv) != 4:
    sys.exit("Usage: bundle_termux_closure.py EXTRACTED_QEMU_ROOT JNI_DIR REPORT_DIR")
src, jni, audit = map(Path, sys.argv[1:])
src, jni, audit = src.resolve(), jni.resolve(), audit.resolve()
jni.mkdir(parents=True, exist_ok=True)
audit.mkdir(parents=True, exist_ok=True)
cache = audit / "package-cache"
cache.mkdir(exist_ok=True)
extracted = audit / "package-extract"
extracted.mkdir(exist_ok=True)
qbin = src / "extracted/data/data/com.termux/files/usr/bin"

MIRROR = "https://packages.termux.dev/apt/termux-main/"
ALT_MIRROR = "https://termux.librehat.com/apt/termux-main/"
CONTENTS = "dists/stable/Contents-aarch64.gz"
PACKAGES = "dists/stable/main/binary-aarch64/Packages.gz"
SYSTEM = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libandroid.so"}
MAX_DOWNLOAD = 70 * 1024 * 1024
MAX_PACKAGES = 55

def fetch(relative, cap=MAX_DOWNLOAD):
    if relative.startswith("/") or ".." in relative.split("/"):
        raise RuntimeError("Bad repository-relative path")
    errors = []
    for mirror in (MIRROR, ALT_MIRROR):
        try:
            url = mirror + relative
            with urllib.request.urlopen(
                urllib.request.Request(url, headers={"User-Agent": "QemuApplication-CI/0.7"}),
                timeout=45,
            ) as response:
                data = response.read(cap + 1)
                if len(data) > cap:
                    raise RuntimeError("Repository response exceeds cap")
                return data
        except Exception as e:
            errors.append(str(e))
    raise RuntimeError(f"Failed downloading {relative}: {' / '.join(errors)}")

def parse_packages(raw):
    result = {}
    for block in raw.split("\n\n"):
        fields = {}
        for line in block.splitlines():
            if line and not line.startswith(" ") and ": " in line:
                key, value = line.split(": ", 1)
                fields[key] = value.strip()
        if fields.get("Architecture") in ("aarch64", "all") and all(
            key in fields for key in ("Package", "Filename", "SHA256")
        ):
            result[fields["Package"]] = fields
    return result

def parse_contents(raw):
    results = {}
    for line in raw.splitlines():
        columns = line.split()
        if len(columns) < 2:
            continue
        path, providers = columns[0], columns[-1]
        # Contents also indexes vendored copies in e.g. awscli/python modules.
        # Only accept files installed directly into the system Termux lib dir.
        base = "data/data/com.termux/files/usr/lib/"
        if not path.lstrip("./").startswith(base):
            continue
        remainder = path.lstrip("./")[len(base):]
        if "/" in remainder or ".so" not in remainder:
            continue
        name = remainder
        if not name.startswith("lib"):
            continue
        for provider in providers.split(","):
            results.setdefault(name, set()).add(provider.split("/")[-1])
    return results

def needed(path):
    out = subprocess.check_output(["readelf", "-d", str(path)], text=True)
    return re.findall(r"\(NEEDED\)\s+Shared library: \[([^\]]+)\]", out)

def is_android_arm64(path):
    header = subprocess.check_output(["readelf", "-h", str(path)], text=True)
    return bool(re.search(r"Machine:\s*AArch64", header))

def source_library(soname):
    parent = extracted / "data/data/com.termux/files/usr/lib"
    if not parent.is_dir():
        return None
    match = parent / soname
    if not match.exists():
        return None
    # Follow regular relative symlinks; absolute legacy prefix symlinks
    # are resolved inside the extracted package tree, never on the host.
    visited = set()
    while match.is_symlink():
        if match in visited:
            raise RuntimeError(f"Loop in symlink for {soname}")
        visited.add(match)
        dest = os.readlink(match)
        match = (extracted / dest.lstrip("/")) if dest.startswith("/") else match.parent / dest
        match = match.absolute()
    if not match.is_file():
        return None
    return match

print("Retrieving Termux aarch64 repository package index...")
package_records = parse_packages(gzip.decompress(fetch(PACKAGES)).decode("utf-8"))
contents = parse_contents(gzip.decompress(fetch(CONTENTS, 90 * 1024 * 1024)).decode("utf-8"))
print(f"Index contains {len(package_records)} packages / {len(contents)} library entries")
installed = {}
downloaded = []
queue = []
for guest in ("aarch64", "x86_64"):
    binary = qbin / f"qemu-system-{guest}"
    if not binary.is_file():
        sys.exit(f"Missing QEMU binary: {binary}")
    if not is_android_arm64(binary):
        sys.exit(f"QEMU executable has wrong host architecture: {binary}")
    queue.append(binary)

deps = {}
while queue:
    obj = queue.pop(0)
    label = obj.name
    deps[label] = needed(obj)
    for name in deps[label]:
        if name in SYSTEM or name in installed:
            continue
        provider_file = source_library(name)
        if provider_file is None:
            # Prefer known official packages for direct QEMU ELF dependencies.
            official = {
                "libz.so.1": "zlib",
                "libpixman-1.so": "libpixman",
                "libgio-2.0.so.0": "glib",
                "libgobject-2.0.so.0": "glib",
                "libglib-2.0.so.0": "glib",
                "libgmodule-2.0.so.0": "glib",
                "libfdt.so": "dtc",
            }
            providers = sorted(contents.get(name, set()))
            preferred = official.get(name)
            if preferred:
                providers = [preferred] + [p for p in providers if p != preferred]
            if not providers:
                raise RuntimeError(f"No official Termux ARM64 provider for {name}")
            for provider in providers:
                if provider not in package_records:
                    continue
                if provider not in downloaded:
                    if len(downloaded) >= MAX_PACKAGES:
                        raise RuntimeError("Refusing more than 55 runtime packages")
                    record = package_records[provider]
                    relative = record["Filename"].lstrip("/")
                    print(f"Downloading {provider}: {name} ({relative})", flush=True)
                    package_bytes = fetch(relative)
                    digest = hashlib.sha256(package_bytes).hexdigest()
                    if digest.lower() != record["SHA256"].lower():
                        raise RuntimeError(f"Package SHA256 mismatch: {provider}")
                    debfile = cache / (provider + ".deb")
                    debfile.write_bytes(package_bytes)
                    subprocess.run(["dpkg-deb", "--extract", str(debfile), str(extracted)], check=True)
                    downloaded.append(provider)
                provider_file = source_library(name)
                if provider_file is not None:
                    break
        if provider_file is None or not provider_file.is_file():
            raise RuntimeError(f"Library {name} not found after installing {downloaded}")
        if not is_android_arm64(provider_file):
            raise RuntimeError(f"Non-AArch64 library {name}")
        installed[name] = provider_file
        queue.append(provider_file)

# Short alias names fit inside the ORIGINAL ELF dynamic strings.
# patchelf relocated .dynstr and ELF segments; Android linker on a real phone
# emitted normalize_path warnings containing function-name fragments. We now
# preserve the original ELF layout and edit only existing SONAME/NEEDED/RUNPATH
# bytes, without changing program-header offsets.
names = aliases(installed.keys())
if not all(name.startswith("lib") and name.endswith(".so") for name in names.values()):
    raise RuntimeError("Invalid Android APK native library names")

for original, source in installed.items():
    target = jni / names[original]
    if target.exists():
        raise RuntimeError(f"Refusing to overwrite {target}")
    shutil.copyfile(source, target)

for guest in ("x86_64", "aarch64"):
    packaged = jni / f"libqemu-system-{guest}.so"
    if not packaged.is_file():
        shutil.copyfile(qbin / f"qemu-system-{guest}", packaged)

rewrite_records = {}
for path in sorted(jni.glob("*.so")):
    if path.name == "libqemu_bridge.so":
        continue
    size_before = path.stat().st_size
    edits = rewrite(path, names)
    if path.stat().st_size != size_before:
        raise RuntimeError("ELF in-place rewrite unexpectedly changed size")
    rewrite_records[path.name] = edits
    if not is_android_arm64(path):
        raise RuntimeError(f"Broken architecture: {path}")
    remaining = set(needed(path)) - SYSTEM - set(names.values())
    if remaining:
        raise RuntimeError(f"Unresolved NEEDED in {path.name}: {sorted(remaining)}")
    dynamic = subprocess.check_output(["readelf", "-d", str(path)], text=True)
    if "/data/data/com.termux/files/usr/lib" in dynamic:
        raise RuntimeError(f"Stale Termux linker RUNPATH in {path.name}")
print("PASS: in-place dynstr rewrite preserved ELF layout for all targets.")

report = {
    "status": "Dependency ELF closure complete in CI; Android device loading NOT TESTED",
    "source": "qemu/QEMU v11.1.2, Termux aarch64 package mirror",
    "source_package_count": len(downloaded),
    "packages": downloaded,
    "source_library_count": len(installed),
    "renamed_libraries": names,
    "layout_preserving_elf_changes": rewrite_records,
    "staged_files": {p.name: p.stat().st_size for p in sorted(jni.glob("*.so"))},
    "warn": [
        "QEMU firmware paths/ROMs must be bundled and installed",
        "No actual Android device tested; ld.so linker namespace and symbol versions may fail",
        "Potential hardcoded /data/data/com.termux/files/usr strings other than DT_RUNPATH remain",
        "Review all transitive licenses and redistribute source as required",
    ],
}
(audit / "CLOSURE_AUDIT.json").write_text(json.dumps(report, indent=2, sort_keys=True))
print(f"PASS: {len(installed)} non-system libraries closed with {len(downloaded)} verified packages")
print("NOT A BOOT TEST: linker namespaces / firmware / device execution still unverified.")
