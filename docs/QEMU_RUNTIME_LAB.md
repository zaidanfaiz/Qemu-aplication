# QEMU Runtime Lab (GitHub Actions)

Goal: experiment with QEMU compiled from the official https://github.com/qemu/QEMU source, BEFORE integrating any native runtime into Qemu Application APK.

Workflow: .github/workflows/qemu-runtime-lab.yml (push to main when lab files change or manual Run workflow).

## Jobs

1. linux-qemu-smoke builds QEMU v11.1.2 with x86_64-softmmu and aarch64-softmmu on the GitHub Linux x86_64 runner. It then starts both executables with no guest OS, runs a QMP capability/query/quit smoke test and uploads a Linux-only artifact.
2. android-arm64-cross checks out the SAME upstream qemu/QEMU tag and the Termux Android/Bionic package recipes. It patches the recipe to use local upstream QEMU source, reduces compile targets to x86_64-softmmu and aarch64-softmmu and attempts Android ARM64 cross compilation in the Termux builder Docker image. If successful, it verifies AArch64 ELF headers and uploads experimental .deb files.

## Critical distinction

- Linux host binaries are not Android binaries.
- Android ARM64 Termux-packaged executables are not automatically standalone JNI libraries. Binary dependences, hard-coded prefixes, firmware and dynamic loading still need work.
- Termux is only a build tool in this CI experiment. The eventual APK should not require the user to install Termux.
- Source QEMU 11.1.2 is pinned so that Termux patch version agrees with QEMU source.
- Passing GitHub Actions does NOT prove VM boot on an actual Android phone.
- Workflow never inserts artifacts into APK and never creates a release.

## How to view

Repo > Actions > QEMU Runtime Lab (upstream source) > choose run > Jobs/Artifacts. Look for qemu-11.1.2-linux-x86_64-SMOKE-ONLY and qemu-11.1.2-android-arm64-EXPERIMENTAL.

## Integration tasks after successful cross compile

Audit ELF NEEDED/interpreter, replace hardcoded Termux prefixes, package library dependencies and BIOS/UEFI, convert to compatible library/loader interface (the existing bridge expects libqemu-system-*.so exposing qemu_init/qemu_main_loop/qemu_cleanup), fulfill QEMU GPL and dependency licenses, and test full guest boot on real Android ARM64.

Reference: https://github.com/termux/termux-packages/wiki/Building-packages