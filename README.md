# Qemu Application

**Android-native UI for local QEMU virtual machines** | Java • QEMU • RFB/VNC • QMP

[![Build APK](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml/badge.svg)](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml)

> **Status: v0.1.0 developer preview.** This APK is a QEMU controller and VNC viewer. The QEMU runtime **is not embedded**. It requires a separately installed **Termux** plus its QEMU packages. No root is needed for software-emulated x86 guests. This is not yet a standalone emulator, and it does not currently boot iOS.

## Features

- Android APK built automatically by GitHub Actions
- Basic x86_64/i386 VM configuration, vCPU and RAM controls
- Create an 8 GB (configurable 1–128 GB) QCOW2 virtual disk inside Termux
- Boot an ISO stored on Android/Termux-accessible storage
- Start a local QEMU software-emulation process through Termux `RUN_COMMAND`
- Embedded VNC/RFB **RAW** framebuffer viewer at `127.0.0.1:5901`
- Touch pointer input, basic guest keyboard controls and text sender
- Graceful shutdown via QMP at `127.0.0.1:4444`
- Networking using QEMU user-mode NAT

## Download / build APK

Open [GitHub Actions](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml), select the latest successful `Android APK CI` run, then download the artifact `QemuApplication-debug-apk`. It contains `app-debug.apk` signed with the standard Android **debug** key.

To build locally, use Android SDK API 35, JDK 17 and Gradle 8.9, then run `gradle assembleDebug`.

## Termux setup (required)

Install the official [Termux](https://github.com/termux/termux-app) from GitHub Releases or F-Droid (do not mix Termux builds/signatures).

In Termux:

```bash
pkg update && pkg upgrade
pkg install qemu-system-x86-64-headless qemu-utils
termux-setup-storage
mkdir -p ~/.termux
echo 'allow-external-apps = true' >> ~/.termux/termux.properties
```

Then fully restart Termux. In Android **Settings → Apps → Qemu Application → Permissions → Additional permissions**, enable **Run commands in Termux environment** (wording varies by device). If requested, grant Termux file/media access after `termux-setup-storage`. The app calls Termux's documented `RUN_COMMAND` Intent; its availability depends on Termux permissions and manufacturer restrictions.

## Run a VM

1. Copy a small supported PC-bootable ISO (for example, an x86_64 Linux installer) into the phone's **Download** folder.
2. Enter its absolute path, for example `/sdcard/Download/linux.iso` (or `/storage/emulated/0/Download/linux.iso`).
3. Choose `x86_64`, RAM (recommend 1024 MB), and vCPU count (recommend 2).
4. Tap **Create virtual disk** once. To verify, check `~/qemu-aplication/create-disk.log` in Termux.
5. Tap **Start QEMU**. For errors, inspect `~/qemu-aplication/run.log` in Termux.
6. Tap **Open VNC display**, or **Reconnect** if the VM has not finished starting.
7. After installing the guest OS, clear the ISO path to boot from the QCOW2 disk.

The virtual disk is stored at `$HOME/qemu-aplication/disk.qcow2` inside Termux. An existing disk is **not** overwritten by Create virtual disk. Only one VM at a time is supported due to fixed local ports. You can stop the VM using **Graceful shutdown** in the main app, which issues QMP `quit` (power off, not ACPI OS shutdown).

## Security and limitations

- QEMU software TCG acceleration runs **on the Android phone** under Termux, not on a remote server. Performance can be slow. KVM acceleration is not assumed to exist.
- VNC has no password, but is bound to **loopback only**. QMP is also loopback only. Other apps running on the same phone may still connect to loopback ports; do not run untrusted local apps alongside sensitive guests.
- The VNC client handles RAW updates, basic mouse/keyboard, and no-auth RFB; clipboard, audio, multi-touch, resizing and accelerated graphics aren't implemented.
- The app is an actual installable APK, **but not a fully self-contained QEMU package**. Bundling a QEMU ARM64 runtime and its native dependencies is a separate milestone.
- `x86_64`/`i386` VM profiles are the only implemented presets so far. ARM64, UEFI and iOS-specific hardware/device boot are not supported in this developer preview.
- The research project [ios-emulator](https://github.com/zaidanfaiz/ios-emulator) is separate. Its experimental XNU boot environment, kernel patches, guest images, custom device tree and boot chain are **not included** here. This project does not claim iOS 16 boots on Android.

## Architecture

```
Android APK (Java)
  ├── MainActivity: configure, create disk, start, stop
  ├── Termux RUN_COMMAND intent (granted by the user)
  │    └── Termux qemu-system-x86_64 / qemu-img (ARM64 host)
  │         ├── VNC 127.0.0.1:5901  ──► Android RFB viewer
  │         └── QMP 127.0.0.1:4444  ◄── Android stop button
  └── ViewerActivity: bitmap rendering and mouse/keyboard events
```

## References and attribution

The implementation uses Android/Java components and does not copy source from these projects:

- [Android QEMU mirror](https://github.com/mirror/qemu-android): Google's Android emulator downstream of QEMU, not a ready-made VM embedding SDK.
- [Limbo Emulator](https://github.com/limboemu/limbo): QEMU Android manager reference.
- [Vectras VM](https://github.com/xoureldeen/Vectras-VM-Android): Android VM management and runtime packaging reference.
- [DroidVM](https://github.com/Droid-VM/DroidVM): optional hardware-hypervisor architecture requiring supported devices/root.
- [Termux RUN_COMMAND specification](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

This application's original code is MIT-licensed. **QEMU and Termux are separate upstream projects with their own licenses.** Bundling or distributing their binaries later will require preserving upstream license obligations.

## Roadmap

- [x] GitHub Actions APK CI
- [x] Functional Termux bridge, disk creation, QEMU launch, basic VNC, QMP
- [ ] Native ARM64 QEMU package bundled in APK (remove Termux dependency)
- [ ] USB/HID polish, guest audio, complete keyboard, clipboard
- [ ] UEFI, ARM64 machines, multi-VM profiles, image import
- [ ] Separate compatibility research for the iOS/XNU project

Contributions and experimental testing are welcome. Report crash logs and the phone's Android version in Issues.
