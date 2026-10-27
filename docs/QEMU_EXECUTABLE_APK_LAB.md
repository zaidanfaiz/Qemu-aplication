# Native Android QEMU executable staging experiment

Actions: https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/qemu-executable-apk-lab.yml

Cross-compile source: upstream qemu/QEMU v11.1.2; minimal Android/Bionic ARM64 artifact from successful workflow run 40402854329.

Previous QEMU 11.1.2 minimal build reduced **31 to 15 direct shared-library dependencies** per AArch64-host executable. That is 16 fewer dependencies, not a claim about the transitive dependency closure.

## What is changed in APK launch code

- QEMU from the source build is an **Android AArch64 PIE executable** (PT_INTERP = /system/bin/linker64), not a JNI-callable QEMU library.
- The previous NativeEngine JNI entrypoint expectations were incorrect for this artifact. NativeEngine now prepares ProcessBuilder with a packaged native ELF payload from ApplicationInfo.nativeLibraryDir, avoiding Android 10+ app-home executable restrictions.
- The VM foreground service records native process logs to app-private qemu.log. There is now a View QEMU log entry in the overflow menu.
- On an actual device, executable loading and dependency resolution must still be tested. Binary packaging is not proof of runtime viability.

## Staging-only CI

GitHub Actions downloads the existing minimal ARM64 QEMU artifact, checks SHA-256, validates AArch64 machine and Android interpreter headers, and stages qemu-system-x86_64 and qemu-system-aarch64 under JNI lib-shaped names (libqemu-system-*.so). Android Gradle builds the debug APK and verifies both native executable ELF payloads are present in the ZIP.

Expected staging artifact name: INCOMPLETE-native-QEMU-executable-APK-NOT-BOOTABLE.

**THIS APK IS NOT FUNCTIONAL AS A VM EMULATOR.** It is an installation and packaging test. It does not include the 15 direct non-system library dependencies, their transitive dependencies, PC/ARM firmware, nor relocation of Termux-origin RUNPATHs. The engine may fail to launch. Do not distribute or present it as a working QEMU APK.

## Remaining gates

1. Resolve all ELF DT_NEEDED names against real Android ARM64 native libraries with consistent SONAME and versioned names.
2. Test Bionic linker's library namespace/RUNPATH handling with nativeLibraryDir on a physical Android phone.
3. Package BIOS and other firmware, set data directory explicitly with -L.
4. Test an actual guest, basic VNC display and QMP stop, disk persistence and background behavior.
5. Review GPL/LGPL source release and binary licensing before public distribution.

The default main APK CI intentionally does not include the QEMU binary. Runtime experiments are separate.