<p align="center">
  <img src="app/src/main/res/drawable-nodpi/qemu_brand.webp" alt="Qemu Application official app icon" width="170">
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

**Status: 0.4.0 developer preview.** The QEMU binaries run inside a separately installed **Termux** environment and are **not bundled in the APK**. Root is not required for TCG software emulation. Android hardware virtualization, iOS/XNU boot, and a fully standalone runtime are **not implemented**.

## Features

- Minimal native-style screen: boot media, architecture, resources, and primary VM actions
- **Browse / Choose ISO** opens Android's system file picker (SAF), with progress and **Clear** to boot from the virtual disk
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

## Panduan cepat (Bahasa Indonesia)

1. Install [Termux resmi](https://github.com/termux/termux-app/releases) dan buka sekali.
2. Install APK Qemu Application dari **Releases**. Tekan **Setup QEMU**.
3. Pada penggunaan pertama, app menyalin perintah otorisasi. Paste di Termux, jalankan, lalu berikan izin **Run commands in Termux environment** untuk Qemu Application melalui pengaturan Android.
4. Kembali ke APK, tekan **Setup QEMU** lagi. Perintah instalasi QEMU dikirim ke Termux. Tunggu sampai selesai dan izinkan akses storage jika diminta.
5. Pilih `x86_64`, RAM 1024 MB, 2 core. Tekan **Create virtual disk** sekali.
6. Nama ISO akan muncul otomatis. Tekan **Start virtual machine**, lalu **Check status**. Untuk boot dari disk setelah instalasi OS, tekan **Clear** pada ISO.
7. Setelah VNC aktif, tekan **Open display**. Untuk berhenti, matikan OS dari dalam guest atau gunakan tombol **Stop VM** (penghentian paksa).

**Catatan:** Tanpa izin awal Termux, Android tidak mengizinkan instalasi otomatis melalui APK lain. Jika setup gagal, buka `~/qemu-aplication/setup.log` di Termux. Bila sesi setup tidak tampil pada Android 10+, coba buka notifikasi Termux atau izinkan **Display over other apps** untuk Termux sesuai pengaturan perangkat.

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

### 3. Pick a Linux ISO (no typing file paths)

Download an x86_64 Linux installer ISO to your phone, then:

1. Open **Qemu Application → Boot media → Browse**.
2. Android's system file picker opens. Select a file ending in `.iso`, from Downloads or another compatible document provider.
3. The app **copies the ISO** to `Download/QemuApplication/` using a streaming import. Watch the displayed progress; **do not start the VM until import finishes**.
4. After import, the selected filename appears under *Installer image*. Press **Clear** whenever you want to eject the ISO and boot from the installed system on QCOW2.

The import creates its own copy, so large ISOs need enough spare storage. To free space, delete old files inside `Download/QemuApplication` using Android's file manager. Android 8–9 may ask for legacy write permission. On Android 10+, the app uses MediaStore. Termux still needs its own storage permission.

### 3A. Where imported files are stored (reference only)

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

With a successfully imported ISO selected under **Boot media**, tap **Start virtual machine** then **Check status**. QEMU runs locally under Termux (software TCG, not a remote PC).

When VNC and QMP become available, tap **Open display**. The viewer retries while QEMU starts. Inside VNC: touch to click/drag, use the keys below the display, and send basic text input.

After installing the guest OS to QCOW2, tap **Clear** next to **Browse**, then start the VM again to boot the virtual disk.

### 6. Stop the virtual machine

Prefer shutdown from inside the guest OS if it is responding. The APK's **Stop VM** button sends QMP `quit` (an **immediate VM termination**, not an ACPI shutdown). If QMP cannot be contacted, the app requests Termux to send TERM to the PID recorded by its own launcher after verifying it is a QEMU process.

## Pick a custom ARM64 kernel, initrd and DTB

1. Select **aarch64 (kernel)** in Architecture.
2. Expand **Advanced · ARM64 kernel** if it isn't already open.
3. Tap the **⋮** beside **Kernel image**, choose the kernel in Android's system file picker.
4. Optionally select an **Initrd** and **Device tree** using their respective **⋮** buttons.
5. Wait for import to finish. The app places files under `Download/QemuApplication` and fills their absolute paths automatically.
6. Set kernel arguments if required by the guest, then create a virtual disk and start QEMU.
7. If the screen stays black, read `~/qemu-aplication/run.log` for serial output.

This is a generic QEMU `virt` machine: a custom iPhone XNU kernel might not boot without a compatible loader and hardware model. File selection alone does not add that support.

## Experimental ARM64 boot

Select `aarch64 (kernel)`, provide an ARM64 bootable kernel accepted by QEMU's `-kernel` option and optionally an initrd/DTB/boot arguments. Create a disk before starting. Inspect the serial output in `run.log` if VNC is black.

This is a **generic QEMU `virt`** machine. It does not emulate an iPhone SoC or load the complete custom boot chain from [ios-emulator](https://github.com/zaidanfaiz/ios-emulator). An iOS boot is **not** confirmed.

## Troubleshooting

| Problem | What to check |
| --- | --- |
| Setup does not open a Termux session | Open Termux once, run the one-time authorization command, grant `RUN_COMMAND`, and tap Setup again. On Android 10+, also check Termux notification or optional Display over other apps permission |
| Automatic install fails | Check internet, Termux repository mirror, and `~/qemu-aplication/setup.log`; rerun Setup |
| ISO cannot be read | Select via **Browse** and finish import. Confirm storage permission inside Termux and that the imported file exists at `/sdcard/Download/QemuApplication/` |
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

## UI and ISO file handling

The default screen intentionally shows only the core tasks. Select architecture, pick an ISO with **Browse**, set resources, create a disk, start, and open the display. **Advanced ARM64 options** remain collapsed unless AArch64 is selected. **Environment** contains setup and status diagnostics.

The Android document picker provides a `content://` URI, which **cannot simply be passed to Termux's QEMU as a filesystem path**. The app streams the selected ISO into MediaStore Downloads with a unique filename, marks the item complete only when copying succeeds, cleans up incomplete imports on error, and then supplies QEMU a local public-storage path. A valid storage grant to Termux is still required.

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
