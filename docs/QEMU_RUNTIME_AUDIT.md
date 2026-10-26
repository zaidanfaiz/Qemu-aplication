# Real QEMU 11.1.2 Android ARM64 binary audit

Source: official [qemu/QEMU](https://github.com/qemu/QEMU) v11.1.2, cross-built in [GitHub Actions run 40400628542](https://github.com/zaidanfaiz/Qemu-aplication/actions/runs/40400628542).

**Both jobs passed:** Linux/QMP smoke test and Android ARM64 build + ELF audit + artifact upload. The Android artifact is **qemu-11.1.2-android-arm64-EXPERIMENTAL** (compressed upload approx. 64.8 MB).

The findings below are extracted from the real workflow artifact, not inferred from the QEMU source.

## Output validation

| Property | `qemu-system-aarch64` | `qemu-system-x86_64` |
| --- | --- | --- |
| Android host architecture | AArch64 / ELF64 | AArch64 / ELF64 |
| Guest architecture | AArch64 | x86_64 |
| Linker interpreter | `/system/bin/linker64` | `/system/bin/linker64` |
| ELF type | `ET_DYN` executable (PIE; includes `PT_INTERP`) | `ET_DYN` executable (PIE; includes `PT_INTERP`) |
| Direct `DT_NEEDED` shared libraries | 31 | 31 |
| Embedded `RUNPATH` | `/data/data/com.termux/files/usr/lib` | `/data/data/com.termux/files/usr/lib` |

Actual extracted binary sizes: **~30 MB for AArch64 guest emulation** and **~25 MB for x86_64 guest emulation** (uncompressed files).

A `objdump -T` inspection of both extracted executables found **no dynamic exports matching `qemu_init`, `qemu_main_loop`, or `qemu_cleanup`**. This confirms that the existing APK JNI host bridge cannot load these executables as if they were QEMU plugin libraries without additional build/entrypoint work.

**Important:** An `ET_DYN` executable with an interpreter is *not* automatically a callable JNI `.so`. Our current Android JNI wrapper expects `qemu_init`, `qemu_main_loop`, and `qemu_cleanup` in `libqemu-system-*.so`. These current Linux-style executables must not be renamed and passed to `dlopen` as a shortcut.

## Representative direct library dependencies

Observed `DT_NEEDED` includes:

`libglib-2.0.so.0`, `libgio-2.0.so.0`, `libgobject-2.0.so.0`, `libpixman-1.so`, `libfdt.so`, `libslirp.so`, `libz.so.1`, `libzstd.so.1`, `libspice-server.so`, `libgnutls.so`, `libnettle.so.8`, `libgmp.so`, `libcurl.so`, `libssh.so`, `libasound.so`, `libpulse.so`, `libjack.so.0`, `libusb-1.0.so`.

Several dependencies support optional functions (SPICE, audio, remote storage, USB redirection). For a first native headless VM prototype, these should be disabled at **QEMU configure time** and ELF dependencies audited again, before trying to bundle into an APK.

## Blockers to standalone APK integration

1. Remove optional QEMU dependencies by compiling a feature-minimal runtime; preserve AArch64/x86_64 softmmu and VNC.
2. Resolve and bundle the **transitive** dynamic library closure (not only QEMU's direct 31 dependencies).
3. Replace static Termux library/firmware prefixes with paths under the Android app's native library directory and private files directory.
4. Choose an Android-safe execution model: genuine `libqemu-system-*.so` with exported entrypoints, or properly packaged NDK executable payloads compatible with modern Android execution restrictions. The current APK expects the former.
5. Bundle BIOS/UEFI/ROM files legally, meet QEMU GPL and dependency licenses, and test actual boot/VNC/QMP on physical Android ARM64.

Artifact inspection:

```bash
# After downloading qemu-11.1.2-android-arm64-EXPERIMENTAL and extracting the GitHub zip:
sha256sum -c qemu-11.1.2-android-arm64-experiment.tar.gz.sha256
tar -xzf qemu-11.1.2-android-arm64-experiment.tar.gz
cat android-arm64/audit/qemu-system-aarch64.dynamic-deps.txt
cat android-arm64/audit/qemu-system-x86_64.dynamic-deps.txt
```

**No Android guest boot claim is made for this artifact.**