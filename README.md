<p align="center">
  <img src="docs/assets/brand.svg" alt="Qemu Application" width="720">
</p>

<p align="center">
  <strong>Android QEMU VM Manager · VNC Viewer · Termux-powered</strong>
</p>

<p align="center">
  <a href="https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml"><img alt="Android APK CI" src="https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml/badge.svg"></a>
  <a href="https://github.com/zaidanfaiz/Qemu-aplication/releases/tag/dev-latest"><img alt="Download" src="https://img.shields.io/badge/Download-APK-blue"></a>
  <img alt="Status" src="https://img.shields.io/badge/Status-Developer%20Preview-orange">
</p>

# Qemu Application

Qemu Application is an **Android APK** for launching software-emulated QEMU virtual machines locally on a phone. The APK provides a machine configuration UI, QCOW2 disk creation, a VNC viewer, and QMP/PID-based stop controls.

**Status: 0.2.0 developer preview.** The QEMU binaries run inside a separately installed **Termux** environment and are **not bundled in the APK**. Root is not required for TCG software emulation. Android hardware virtualization, iOS/XNU boot, and a fully standalone runtime are **not implemented**.

## Features

- One **Setup QEMU** button replaces the long first-run instruction panel
- Installs QEMU packages through Termux after the mandatory one-time authorization
- Configures x86_64, i386, and experimental AArch64 direct-kernel guests
- Creates a persistent QCOW2 disk without silently overwriting an existing disk
- Guards VM startup against an existing QEMU PID; shows a diagnostic log on failure
- Checks localhost VNC (5901) and QMP (4444) availability
- Built-in VNC/RFB viewer with a retry window, touch controls, virtual keys, and text entry
- QMP stop command; TERM-to-verified-PID fallback if QMP cannot be reached
- GitHub Actions automatic Android APK builds and rolling prereleases

## Download

**[Download latest developer APK](https://github.com/zaidanfaiz/Qemu-aplication/releases/download/dev-latest/app-debug.apk)**

Or use [GitHub Actions](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml), choose a successful run, and download **QemuApplication-debug-apk**.

The development APK uses the Android **debug** signing certificate. It is intended for testing, not production distribution.

## Tutorial: first-time setup

### 1. Install official Termux

Install [Termux from its official GitHub Releases](https://github.com/termux/termux-app/releases) or F-Droid. Open Termux once to initialize its home directory. Avoid mixing Termux and add-ons from builds with different signing certificates.

### 2. Install the APK and press Setup QEMU

Open Qemu Application and tap **Setup QEMU**.

- If Termux is missing, the app opens the official download page.
- **The first time only:** the app copies the one-time authorization command and opens Termux. Paste and run the command shown below.
- Grant Qemu Application the **Run commands in Termux environment** permission in Android's **App info → Permissions → Additional permissions**.
- Return to the APK and tap **Setup QEMU** again. The app requests automatic QEMU package installation and opens Termux so you can see the progress.
- Allow Termux's storage permission if Android requests it. Once the commands finish, the setup script writes `~/qemu-aplication/setup.status`.

**Mandatory one-time command (copied by the app):**

```sh
mkdir -p ~/.termux
printf '\nallow-external-apps = true\n' >> ~/.termux/termux.properties
termux-reload-settings
```

**Why is the first command not fully automatic?** Android isolates Termux app data. Termux explicitly requires both `allow-external-apps = true` **inside Termux** and the Android `RUN_COMMAND` permission before another APK may execute its commands. The app cannot bypass this safely. See [Termux's official RUN_COMMAND documentation](https://github.com/termux/termux-app/wiki/RUN_COMMAND-Intent).

If the automatic setup does not start, open Termux and verify the mandatory property and permission above. As a manual fallback you can run:

```sh
pkg update -y
pkg install -y qemu-system-x86-64-headless qemu-system-i386-headless qemu-system-aarch64-headless qemu-utils
termux-setup-storage
```

**Important:** manual package installation alone does not create the app's `setup.status` file. After fixing permissions, press **Setup QEMU** again so the app can verify binaries and create its setup marker.

### 3. Prepare a Linux ISO

Download a legally distributable **x86_64 Linux installer ISO** appropriate for QEMU (start with a lightweight guest, such as Alpine Linux). Place the ISO in the phone's Download folder. Example absolute file path:

```text
/sdcard/Download/alpine-linux.iso
```

The name must match your actual ISO filename. Grant Termux storage permission; `termux-setup-storage` may open Android's permission dialog.

### 4. Create a virtual disk

In the app, select `x86_64`, RAM (try 1024 MB), vCPU cores (try 2), and disk capacity (try 8 GB). Tap **Create virtual disk** once.

The virtual disk is stored at:

```text
~/qemu-aplication/disk.qcow2
```

Reusing the same disk is supported. **Create virtual disk does not overwrite an existing file.** Only one VM profile/disk is supported in this preview.

### 5. Start the virtual machine

Enter your ISO absolute path, tap **Start QEMU**, then **Check VM status**. QEMU runs locally under Termux using the phone's CPU (software TCG, not a remote PC).

When VNC and QMP become available, tap **Open VNC display**. The viewer retries while QEMU starts. Inside VNC: touch to click/drag, use the keys below the display, and send basic text input.

After installing the guest OS to QCOW2, **clear the ISO field** and start the VM again to boot its virtual hard disk.

### 6. Stop the virtual machine

Prefer shutdown from inside the guest OS if it is responding. The APK's **Stop VM** button sends QMP `quit` (an **immediate VM termination**, not an ACPI shutdown). If QMP cannot be contacted, the app requests Termux to send TERM to the PID recorded by its own launcher after verifying it is a QEMU process.

## Experimental ARM64 boot

Select `aarch64 (kernel)`, provide an ARM64 bootable kernel accepted by QEMU's `-kernel` option and optionally an initrd/DTB/boot arguments. Create a disk before starting. Inspect the serial output in `run.log` if VNC is black.

This is a **generic QEMU `virt`** machine. It does not emulate an iPhone SoC or load the complete custom boot chain from [ios-emulator](https://github.com/zaidanfaiz/ios-emulator). An iOS boot is **not** confirmed.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Setup does not open a Termux session | Open Termux once, run the one-time authorization command, grant `RUN_COMMAND`, and tap Setup again |
| Automatic install fails | Check internet, Termux repository mirror, and `~/qemu-aplication/setup.log`; rerun Setup |
| ISO cannot be read | Confirm exact filename/path, storage permission, and that Termux can read the file |
| Start request sent but VM is offline | Review `~/qemu-aplication/run.log` in Termux; check QEMU binary, disk, and port conflicts |
| VM already running | Don't start another instance with the same disk or ports; stop the existing VM first |
| VNC is black | Wait for guest boot; retry viewer; ARM64 serial-only guests may have no graphical console |
| Phone gets hot/VM is slow | Use a lightweight guest, 1-2 vCPU, and lower resolution; TCG is CPU intensive |
| VM is killed when switching apps | Exempt Termux from aggressive battery restrictions if permitted by your device |

**Helpful paths**

| File | Location in Termux |
| --- | --- |
| VM disk | `~/qemu-aplication/disk.qcow2` |
| VM log | `~/qemu-aplication/run.log` |
| Disk creation log | `~/qemu-aplication/create-disk.log` |
| Setup log | `~/qemu-aplication/setup.log` |
| Setup success marker | `~/qemu-aplication/setup.status` |
| QEMU process PID | `~/qemu-aplication/qemu.pid` |

## Build from source

Requirements: **JDK 17**, **Android SDK 35**, **Gradle 8.9**.

```bash
gradle assembleDebug
```

The APK is written to `app/build/outputs/apk/debug/app-debug.apk`. GitHub Actions automatically builds and uploads the APK after commits to `main`.

## Security / compatibility notes

- Only run trusted guest images. QEMU is complex native software and guests may expose emulator vulnerabilities.
- VNC without a password and QMP are bound to **127.0.0.1**, not a public network interface. However, other apps on the same device may still attempt connections; do not put sensitive information into experimental guests.
- Android sandbox restrictions require explicit Termux authorization and storage permissions; bypassing these is intentionally unsupported.
- Some Android devices may kill Termux background processes even after a successful build. No uptime/stability guarantee is made until real-device testing.
- This implementation is **not a fork of official QEMU's branding**, and no Apple firmware/OS images are distributed.

## Credits

Independent Android application inspired by:
[QEMU Android mirror](https://github.com/mirror/qemu-android), [Limbo](https://github.com/limboemu/limbo), [Vectras VM](https://github.com/xoureldeen/Vectras-VM-Android), and [DroidVM](https://github.com/droid-vm/droidvm).

Code: MIT license. QEMU and Termux retain their respective upstream licenses.
