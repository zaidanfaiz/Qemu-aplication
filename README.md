<p align="center">
  <img src="app/src/main/res/drawable-nodpi/qemu_brand.webp" alt="Qemu Application" width="135">
</p>

# Qemu Application — Android native migration (main)

![Status](https://img.shields.io/badge/native%20engine-integration%20pending-orange)
[![Android CI](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml/badge.svg?branch=main)](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml)

> **ENGINE INTEGRATION IN PROGRESS — NOT YET A WORKING STANDALONE QEMU APK**
>
> The main branch now removes the Termux dependency from the app architecture. It compiles a **native Android NDK/JNI host adapter**, not the QEMU emulator itself. Until Android ARM64-compatible QEMU system libraries and guest firmware are built, bundled, and verified, **it cannot boot a VM**. A green GitHub Actions build only means the application and JNI adapter compile.

**Main now tracks this native prototype**, at the maintainer's request. The earlier Termux-based v0.4 remains in Git history and prior downloadable releases, but does not represent the current source. The native prototype is not capable of booting guests yet.

## What changed here

- Native minimal VM-list UI. No Termux setup, environment buttons, shell commands, or giant Start button.
- Tap a machine card to **attempt to boot** when the native QEMU runtime is actually present.
- Menu **⋮** for **ISO**, custom **ARM64 kernel**, DTB, CPU, RAM, disk capacity, disk creation, display, and stop.
- Android document picker imports selected guest files directly into the app's **private files directory**.
- Creates sparse RAW virtual disks without Termux/qemu-img.
- Android foreground service owns the QEMU session lifecycle, not the Activity UI thread.
- Android NDK bridge can dynamically load native QEMU system shared libraries using the symbols \`qemu_init\`, \`qemu_main_loop\`, and \`qemu_cleanup\`.
- Reuses the localhost VNC viewer and sends QMP stop over localhost.

## Native architecture

\`\`\`text
Android APK (arm64-v8a)
  ├─ MainActivity                   Android VM list, SAF pickers, config
  ├─ NativeVmService               Foreground VM session
  │   └─ NativeEngine / JNI        libqemu_bridge.so (included, NDK build)
  │       └─ dlopen (NOT INCLUDED YET)
  │           ├─ libqemu-system-x86_64.so   QEMU compiled for Android Bionic
  │           ├─ libqemu-system-aarch64.so  QEMU compiled for Android Bionic
  │           └─ libglib / pixman / other LGPL dependencies
  ├─ Android app-private media/     Selected ISO, kernel, DTB
  ├─ Android app-private machine.img Sparse RAW disk
  └─ ViewerActivity                localhost VNC :5901 / QMP :4444
\`\`\`

**Important:** The **ABI in \`arm64-v8a\` denotes the physical Android phone's CPU**, not the architecture of guest Windows/Linux. To emulate an x86 guest on Android ARM64, the library must still be an Android ARM64 ELF but support QEMU's **x86_64-softmmu** target.

## Work still required before it can boot guests

1. Select and pin a QEMU version and compile its **system emulator targets** using Android NDK/Clang and **Bionic libc**. Native Linux \`qemu-system-*\` binaries from a standard Ubuntu package won't work on Android.
2. Build and package the correct compatible native dependencies (e.g. GLib, pixman, libffi, and other selected features). Check correct C++ ABI, Android 16 KB page size compatibility, and dynamic library linking.
3. Produce \`libqemu-system-x86_64.so\` and \`libqemu-system-aarch64.so\` that export the **three required entry points**. Stock QEMU executables do not necessarily expose these as callable shared-library entry points. A source patch/build adapter may be needed.
4. Put the verified libraries and dependencies in \`app/src/main/jniLibs/arm64-v8a/\`. Gradle's \`useLegacyPackaging true\` makes them available in \`ApplicationInfo.nativeLibraryDir\`.
5. Bundle legally redistributable QEMU firmware (BIOS, VGA BIOS, UEFI where needed) and adjust launch arguments/firmware search path. A library alone is not enough for PC guests.
6. Run hardware-device tests for launch, QMP, VNC, disk persistence, app backgrounding, shutdown, thermal/load, and Android 16 behavior.
7. Test multi-profile isolation and image-import failure cleanup before shipping a production release.
8. Review and preserve **GPL/LGPL license obligations** for the selected QEMU and dependencies. App-level MIT licensing does not override upstream licenses.

### Why no downloaded third-party QEMU .so yet?

Unverified, precompiled binaries from other apps are not necessarily ABI-compatible, license-compliant, or safe to redistribute. Copying a \`.so\` without ensuring it exports the expected symbols, has the required dependencies, and includes appropriate firmware could lead to native crashes or a misleading "it works" APK.

References:
- [QEMU upstream](https://gitlab.com/qemu-project/qemu)
- [Limbo developers' Android-native QEMU build](https://github.com/limboemu/limbo/blob/master/README.developers)
- [Vectras native runtime architecture reference](https://github.com/FarizGD/Vectras-VM-Android-NativeQemu)
- [Android NDK](https://developer.android.com/ndk)

## Compile the JNI prototype

In Android Studio, install **JDK 17, Android SDK 35, NDK 27.2.12479018 and CMake 3.22.1**.

\`\`\`sh
gradle assembleDebug
\`\`\`

GitHub Actions builds \`native-qemu\` as a separately labeled artifact **NativeBridge-Prototype-NOT-QEMU**, and **never publishes it to the working main release**. It also warns when QEMU core libraries are absent from the APK.

This code is **not a ready-to-use native emulator** until the runtime integration criteria above pass. The existing [legacy dev-latest Release](https://github.com/zaidanfaiz/Qemu-aplication/releases/tag/dev-latest) may still contain the earlier Termux-based build; it is **not** the current native main APK.

## Experimental user flow once the engine is bundled

Open the APK → choose the guest ISO / kernel using the **⋮** menu → configure guest resources → create a disk → tap the VM card → open the VNC display. No Termux app, shell permissions, or manual path entry should be necessary once QEMU is fully integrated.

The custom ARM64 kernel path is **generic QEMU \`virt\`**, not automatically a bootable iPhone XNU port. The [iOS emulator research project](https://github.com/zaidanfaiz/ios-emulator) needs its own compatible hardware model, kernel loader, firmware, and guest boot chain.

## License

Original glue and UI in this repository: MIT. QEMU and its libraries have separate GNU GPL/LGPL licensing requirements that must be fulfilled in any binary distribution.
