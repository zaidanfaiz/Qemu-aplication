<p align="center">
  <img src="app/src/main/res/drawable-nodpi/qemu_brand.webp" alt="Qemu Application" width="135">
</p>

# Qemu Application — Native Android QEMU Research

[![Android CI](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml/badge.svg?branch=main)](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml)

> **Developer research preview. Not yet a bootable standalone QEMU APK.**
> Both upstream QEMU 11.1.2 and the Android ARM64 minimal QEMU build compile in GitHub Actions. The experimental APK packages both real native executables, but it still lacks required shared libraries, firmware and path relocation. No Termux installation is intended for the eventual app.

## Current milestones

| Component | Status |
| --- | --- |
| Android native VM-list UI and ⋮ file menus | Implemented |
| Android file picker: ISO, custom kernel, DTB | Implemented |
| Private guest image storage + sparse RAW disk | Implemented |
| Upstream qemu/QEMU 11.1.2 Linux QMP smoke test | **Passed** |
| QEMU 11.1.2 Android ARM64/Bionic cross-compile | **Passed** |
| Reduced direct native dependencies from 31 to 15 | **Passed** |
| QEMU ARM64 + x86_64 executables packaged inside experimental APK | **Passed packaging test** |
| Full dependency closure, relocation and firmware | **Not complete** |
| Real-device Android guest boot, VNC and QMP lifecycle | **Not yet verified** |

## What has changed architecturally?

```text
Android APK (arm64-v8a)
├── MainActivity / Android system file picker
├── NativeVmService (foreground service, ProcessBuilder)
│   └── ApplicationInfo.nativeLibraryDir
│       ├── libqemu-system-aarch64.so    ← actual Bionic PIE executable
│       ├── libqemu-system-x86_64.so     ← actual Bionic PIE executable
│       └── required dependencies        ← NOT bundled yet
├── app-private media images / sparse RAW disk
├── app-private qemu.log (View QEMU log in ⋮ menu)
└── ViewerActivity / local VNC
```

The ARM64-host executables are **not QEMU JNI libraries** and do **not** expose qemu_init/qemu_main_loop/qemu_cleanup. The APK now has an Android ProcessBuilder execution path instead of attempting to dlopen the executables through those unavailable entrypoints. This path still needs dependency and device testing.

Executables are staged under `jniLibs/arm64-v8a` with `lib*.so` filenames and `useLegacyPackaging true`, allowing Android to extract them into nativeLibraryDir instead of trying to exec files inside writable app storage. **The name .so does not convert an executable into a JNI library.**

## GitHub Actions experiments and results

1. [QEMU Runtime Lab](https://github.com/zaidanfaiz/Qemu-aplication/actions/runs/40400628542) builds native Linux/QMP smoke-test binaries and Android ARM64 QEMU binaries from upstream QEMU 11.1.2.
2. [QEMU Minimal Runtime Experiment](https://github.com/zaidanfaiz/Qemu-aplication/actions/runs/40402854329) reduced direct Android ELF dependencies **31 → 15** for both guest targets. [Audit](docs/QEMU_RUNTIME_AUDIT.md).
3. [Executable APK Staging Lab](https://github.com/zaidanfaiz/Qemu-aplication/actions/runs/40452007211) packages real Android ARM64 QEMU executables into a debug APK. The artifact is intentionally named **INCOMPLETE-native-QEMU-executable-APK-NOT-BOOTABLE**.
4. [Additional minimal-build experiments](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/qemu-arm64-minimal.yml) work toward removing ALSA/PulseAudio/JACK and other optional dependencies.

Build logs and dependency numbers: [QEMU Runtime Audit](docs/QEMU_RUNTIME_AUDIT.md). Experimental packaging guide: [QEMU Executable APK Lab](docs/QEMU_EXECUTABLE_APK_LAB.md).

## Tutorial: using the Android UI

**This UI is experimental and does not boot a guest until the runtime is completed.**

1. Install the debug APK built by Android CI or the special **INCOMPLETE** staging APK for packaging-only tests.
2. Open Qemu Application. Tap **⋮** → **Choose ISO** and select the installer from Android Files. The app imports it into private app storage (this may require sufficient free storage).
3. For ARM64: select **Architecture → aarch64**, then **Choose custom kernel** and optionally **Choose DTB**, using the same Android file picker.
4. Choose RAM, core count and disk capacity in the **⋮** menu and tap **Create virtual disk**. The sparse RAW disk persists between sessions.
5. Tapping the VM card will attempt native execution only if a packaged QEMU binary is detected. With the current staging APK, missing libraries/firmware are expected to prevent boot.
6. For native launch diagnostics, tap **⋮ → View QEMU log**; process output and load errors are stored in `qemu.log`. QMP stop and VNC display are not yet tested on a physical device.

**Important:** The normal debug APK built from main contains only the Android native application and native bridge; it does **not** include QEMU executables. The experimental staging workflow produces a separate and explicitly incomplete APK. Older `dev-latest` releases may still refer to the discontinued Termux controller build.

## What remains before a usable APK?

- Resolve **12 non-system direct shared library dependencies** observed in the 15-dependency minimal build, plus their transitive dependencies (e.g. libglib and libpixman). No library may be assumed present on all devices.
- Ensure proper ELF SONAMEs, Android app linker namespace compatibility, architecture alignment, hardcoded Termux runpaths and firmware search paths. The compiled binary currently uses a Termux RUNPATH.
- Bundle the required PC BIOS/ROMs or ARM guest boot resources under a correct Android firmware/data location.
- Verify ProcessBuilder native execution, VNC, QMP, guest boot, storage and thermal stability on a real Android ARM64 device.
- Fulfill QEMU GPL and dependency LGPL/source redistribution obligations before release.

Do **not** rename Linux binaries to .so, assume binary packaging is proof of boot, or copy Android binaries into writable app storage and expect exec to work on Android 10+.

## Build

Build prerequisites: JDK 17, Android SDK 35, NDK 27.2.12479018, CMake 3.22.1 and Gradle 8.9.

```sh
gradle assembleDebug
```

Normal Android CI uploads `NativeBridge-Prototype-NOT-QEMU`; staging CI uploads `INCOMPLETE-native-QEMU-executable-APK-NOT-BOOTABLE`. These are not official working emulator releases.

## Attribution and licensing

- [QEMU source](https://github.com/qemu/QEMU) and [QEMU licenses](https://www.qemu.org/docs/master/about/license.html)
- [Termux Android build recipes](https://github.com/termux/termux-packages) used only in GitHub Actions as a cross-compilation toolchain, not as an app runtime requirement.
- Android application glue code: MIT; upstream QEMU and shared libraries retain their individual licenses.