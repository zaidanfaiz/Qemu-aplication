#!/usr/bin/env python3
"""Safely rewrite existing string entries in an Android ARM64 ELF without changing layout.

Unlike patchelf --replace-needed/--set-rpath, this only changes bytes inside
existing ELF .dynstr strings and never moves PT_LOAD, .dynstr, dynsym, the
dynamic table or PT_INTERP. New SONAME aliases must fit the existing strings.

This is a narrower experimental workaround for observed Android/Bionic
linker warnings after patchelf relocation. Physical-device validation is
still required.
"""
from pathlib import Path
import re
import struct
import sys

PT_LOAD = 1
PT_DYNAMIC = 2
DT_NULL = 0
DT_NEEDED = 1
DT_STRTAB = 5
DT_STRSZ = 10
DT_SONAME = 14
DT_RPATH = 15
DT_RUNPATH = 29

def aliases(names):
    result = {}
    for original in names:
        if original == "libz.so.1":
            result[original] = "libqz.so"  # avoid Android system libz.so
        elif ".so." in original:
            result[original] = original.split(".so.", 1)[0] + ".so"
        else:
            result[original] = original
        if len(result[original]) > len(original):
            raise RuntimeError("In-place ELF alias longer than original: " + original)
    if len(set(result.values())) != len(result):
        raise RuntimeError("Alias collision")
    return result

def rewrite(path, names):
    path = Path(path)
    data = bytearray(path.read_bytes())
    if data[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", data, 18)[0] != 183:
        raise RuntimeError("Expected ELF64 Little-endian Android AArch64: " + str(path))
    phoff = struct.unpack_from("<Q", data, 32)[0]
    phentsize, phnum = struct.unpack_from("<HH", data, 54)
    if phentsize != 56 or phnum > 200:
        raise RuntimeError("Unexpected ELF program headers")
    loads = []
    dynamic = None
    for i in range(phnum):
        off = phoff + i * phentsize
        typ, flags, poff, vaddr, _, filesz, memsz, align = struct.unpack_from("<IIQQQQQQ", data, off)
        if typ == PT_LOAD:
            loads.append((vaddr, vaddr + filesz, poff))
        elif typ == PT_DYNAMIC:
            dynamic = (poff, filesz)
    if dynamic is None:
        raise RuntimeError("Missing PT_DYNAMIC")
    tags = []
    for off in range(dynamic[0], dynamic[0] + dynamic[1], 16):
        tag, val = struct.unpack_from("<qQ", data, off)
        tags.append((tag, val))
        if tag == DT_NULL:
            break
    strtab = next((v for t,v in tags if t == DT_STRTAB), None)
    strsz = next((v for t,v in tags if t == DT_STRSZ), None)
    if strtab is None or not strsz:
        raise RuntimeError("Missing dynamic string table")
    base = next((poff + strtab - v0 for v0,v1,poff in loads if v0 <= strtab < v1), None)
    if base is None or base + strsz > len(data):
        raise RuntimeError("Dynamic string table outside file-backed PT_LOAD")
    modified = []
    changes = {}
    for tag, offset in tags:
        if tag not in (DT_NEEDED, DT_SONAME, DT_RPATH, DT_RUNPATH):
            continue
        if offset >= strsz:
            raise RuntimeError("Bad dynamic string offset")
        begin = base + offset
        end = data.index(b"\0", begin, base + strsz)
        old = bytes(data[begin:end]).decode("utf-8")
        new = "$ORIGIN" if tag in (DT_RPATH,DT_RUNPATH) else names.get(old,old)
        if new == old:
            continue
        encoded = new.encode("utf-8")
        if len(encoded) > end - begin:
            raise RuntimeError(f"Cannot rewrite {old} to {new} without relocating dynstr")
        if begin in changes and changes[begin] != new:
            raise RuntimeError("Shared dynamic string received conflicting aliases")
        changes[begin] = new
        data[begin:end] = encoded + b"\0" * (end-begin-len(encoded))
        modified.append((old,new))
    if len(data) != path.stat().st_size:
        raise RuntimeError("Unexpected ELF file size change")
    path.write_bytes(data)
    return modified

if __name__ == "__main__":
    if len(sys.argv) < 2:
        sys.exit("Usage: rewrite_elf_inplace.py PATH [original SONAME...]")
    print(rewrite(sys.argv[1], aliases(sys.argv[2:])))
