# Minimal Android ARM64 QEMU dependency experiment

Job: [QEMU ARM64 Minimal Runtime Experiment](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/qemu-arm64-minimal.yml).

The [first successful upstream Android cross-compile](QEMU_RUNTIME_AUDIT.md) had **31 direct DT_NEEDED shared libraries** per emulator, with a hard-coded Termux RUNPATH.

This follow-up builds the same official QEMU v11.1.2 source again with optional features disabled at configure-time, using Termux's Android-Bionic patches **only on the GitHub build server**.

Features deliberately disabled for this baseline experiment: GnuTLS, Nettle, ncurses, JPEG VNC compression, PNG, 9p virtfs, curl, LZO, bzip2, libssh, bochs/cloop/dmg/parallels/qed block formats, slirp networking, SPICE, host USB, USB redirection and KVM. **Standard VNC, x86_64-softmmu, aarch64-softmmu, raw drives and FDT remain enabled.**

Workflow assertions:

- Both compiled binaries are **Android host AArch64 ELF**.
- Each links fewer than the previous 31 direct dependencies.
- Selected `.deb` packages and dependency/readelf reports are placed inside a tar.gz artifact, avoiding invalid colons in uploaded paths.
- The archive is never automatically installed in the APK or published as a release.

**Limitations:** Guest networking, audio, USB passthrough and optional disk formats may be unavailable in this build. This is intentional for dependency isolation. It is not proof of guest boot. Runtime library paths, firmware, dependency redistribution and conversion to a JNI-callable QEMU library still require separate engineering.

Inspect `audit/dependency-count.tsv` and `audit/qemu-system-*.deps.txt` in the artifact to compare against the [31-dependency baseline](QEMU_RUNTIME_AUDIT.md).