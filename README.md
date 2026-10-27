<p align="center">
  <img src="app/src/main/res/drawable-nodpi/qemu_brand.webp" alt="Qemu Application" width="112">
</p>

<h1 align="center">Qemu Application</h1>
<p align="center">Native Android virtual machine manager · ARM64 host · QEMU 11.1.2</p>
<p align="center">
  <a href="https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml"><img src="https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml/badge.svg?branch=main" alt="Android build"></a>
  <a href="https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/qemu-arm64-minimal.yml"><img src="https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/qemu-arm64-minimal.yml/badge.svg?branch=main" alt="QEMU ARM64 build"></a>
  <img src="https://img.shields.io/badge/status-experimental-orange" alt="Experimental">
  <img src="https://img.shields.io/badge/host-Android%20ARM64-244560" alt="ARM64">
</p>

> [!IMPORTANT]
> **APK kandidat sudah berisi runtime QEMU dan firmware, tetapi boot VM masih belum teruji.** Build GitHub Actions berhasil memaketkan executable, library transitif dan ROM. Stabilitas di HP Android tetap perlu diuji, jadi ini bukan rilis publik yang sudah siap pakai.

## Tentang proyek

Qemu Application adalah VM manager Android yang dirancang menjalankan QEMU **langsung dalam APK**, tanpa instalasi Termux, root, atau konfigurasi perintah terminal untuk pengguna akhir.

Antarmuka menggunakan Android native Java. Emulator QEMU dikompilasi untuk **host Android ARM64** menggunakan toolchain Android/Bionic. Guest yang ditargetkan adalah **x86_64** dan **AArch64** melalui software emulation (TCG), bukan hardware virtualization/KVM.

**Kondisi saat ini:** `main` adalah proyek riset native. CI reguler membangun aplikasi tanpa engine; CI **Native Dependency Integration Lab** membangun APK kandidat dengan engine. Kedua jalur tetap terpisah sampai lulus uji perangkat.

## Perkembangan

| Komponen | Status |
|:--|:--|
| UI Android native, konfigurasi VM, menu ⋮ | Ada |
| Pemilih ISO, kernel ARM64, dan DTB dari file manager | Ada |
| Impor image ke penyimpanan privat, disk RAW | Ada |
| QEMU 11.1.2 dari source resmi, smoke test QMP Linux | Lulus |
| Cross-compile QEMU untuk host Android ARM64 | Lulus |
| Build minimal dengan VNC: **31 → 15 → 10** dependensi ELF langsung | Lulus |
| Dependency closure: **11 library non-sistem dari 8 paket** | **Lulus audit CI** |
| QEMU + firmware dasar pada satu APK kandidat | **Lulus packaging** |
| Linker Android aktual dan path runtime tambahan | Belum diuji di HP |
| Boot guest, VNC, QMP pada HP Android | **Belum teruji** |

<details>
<summary>Mengapa hanya menghitung dependensi langsung?</summary>

`readelf -d` menunjukkan library yang secara langsung diminta oleh executable. Library seperti GLib dapat membutuhkan library lain; seluruh rantai (`DT_NEEDED` transitif) harus ditemukan dan dipaketkan. Hasil 10 bukan berarti APK cukup memuat 10 file, dan bukan bukti emulator berhasil boot.

</details>

## Kontrol touchscreen Android

Viewer mendukung **tap**, **tekan-tahan**, dan **swipe/drag** langsung pada area framebuffer. Input dikirim lewat RFB PointerEvent dan diterima QEMU melalui perangkat pointer absolut **USB tablet** untuk mesin PC x86_64. **Geser dua jari** pada layar untuk mengirim pulsa scroll (wheel RFB), bukan melakukan drag/klik tanpa sengaja.

- Tap = sentuhan yang diterjemahkan menjadi satu klik perangkat virtual.
- Tekan-tahan = tombol utama ditahan sampai jari dilepaskan.
- Swipe = pergerakan pointer dengan tombol utama tetap ditekan.
- Dua jari = scroll vertikal atau horizontal melalui RFB.
- Touch tetap dibatasi pada **satu pointer guest**. Gestur seperti pinch-to-zoom sistem iOS serta multitouch native memerlukan perangkat emulasi dan driver guest khusus. **ISO/guest belum otomatis mendukung touchscreen native iOS.**

## Tampilan simulator perangkat

Viewer tidak lagi memaksa posisi lanskap atau menampilkan bar tombol terminal. Versi UI terbaru menampilkan **frame perangkat bergaya iPhone dalam posisi potret**, dengan display VNC di dalamnya dan rasio asli framebuffer tetap terjaga.

- Bar atas: kembali ke daftar VM, status koneksi, dan menu **⋯**.
- Mode default: **Device frame**, dengan bezel, area kamera, dan indikator gestur sebagai elemen visual.
- **⋯ → Screen only** menyembunyikan bingkai untuk menguji gambar guest dalam area maksimal.
- **⋯ → Keyboard & send text** membuka keyboard tambahan seperlunya; tidak memenuhi ruang layar secara permanen.
- **⋯ → Reconnect display** mengulang koneksi localhost VNC tanpa menutup aplikasi.
- Sentuhan pada framebuffer yang terlihat dikirim sebagai **pointer VNC tunggal**; huruf / keyboard dikirim sebagai tombol VNC. *Bukan* implementasi multitouch iOS.

**Penting:** frame perangkat hanyalah tampilan. Ini tidak menjadikan guest QEMU otomatis menjalankan iOS, dan tidak mengganti perangkat keras/bootloader yang dibutuhkan sistem operasi tersebut.

## Cara kerja

```text
Qemu Application (Android APK)
  ├─ UI / daftar VM / menu konfigurasi
  ├─ Android system file picker → ISO, kernel, DTB
  ├─ Penyimpanan internal aplikasi → image dan disk RAW
  ├─ NativeVmService → ProcessBuilder + QMP
  │    └─ nativeLibraryDir/
  │         ├─ QEMU executable AArch64-host (x86_64 guest)
  │         ├─ QEMU executable AArch64-host (AArch64 guest)
  │         └─ dependency libraries [tersedia pada APK kandidat]
  ├─ firmware/ [ROM dasar tersedia pada APK kandidat]
  ├─ qemu.log
  └─ VNC Viewer → tampilan guest
```

**Binary-nya executable, bukan library JNI.** Binary hasil build memiliki ELF interpreter `/system/bin/linker64` dan tidak mengekspor `qemu_init`, `qemu_main_loop`, atau `qemu_cleanup`. Jalur native saat ini memakai `ProcessBuilder`. File executable hanya diberi nama `libqemu-system-*.so` agar dapat dipaketkan dan diekstrak Android melalui `nativeLibraryDir`; penggantian nama **tidak** mengubah format executable menjadi shared library.

## APK kandidat native

Workflow **[Native Dependency Integration Lab](https://github.com/zaidanfaiz/Qemu-aplication/actions/runs/40476148120)** sudah berhasil menghasilkan satu APK yang memuat emulator QEMU Android ARM64 dan dependensinya. Audit artifact terkonfirmasi:

- **2** executable QEMU untuk guest x86_64 dan AArch64.
- **11** library non-sistem yang ditemukan secara rekursif dari **8** paket ARM64, dengan verifikasi checksum paket.
- **14** file native keseluruhan, termasuk JNI bridge lama, dan **10** ROM/firmware.
- Penyesuaian nama library, `DT_NEEDED`, dan `RUNPATH=$ORIGIN` berhasil melewati audit di runner.
- Ukuran APK sekitar **16,7 MB**. Struktur APK serta CRC sudah diperiksa.

**Unduh melalui GitHub:** [buka build terbaru dengan SeaBIOS + keymap](https://github.com/zaidanfaiz/Qemu-aplication/actions/runs/40476148120), lalu bagian **Artifacts** → `QEMU-ANDROID-NATIVE-DEPENDENCY-CANDIDATE-UNTESTED`. Ekstrak ZIP dan instal `app-debug.apk`.

**Perlu diperhatikan:** dependency closure di CI bukan bukti Android linker akan menerima seluruh library saat runtime. QEMU mungkin masih gagal start akibat linker namespace, path yang tertanam, opsi QEMU, maupun firmware. Gunakan untuk pengujian, bukan untuk data penting.

## Perbaikan BIOS PC pada Android (27 Oktober 2026)

Laporan uji perangkat menemukan bahwa QEMU native **berhasil dieksekusi**, tetapi berhenti dengan pesan:

```text
qemu: could not load PC BIOS 'bios-256k.bin'
[QEMU EXIT STATUS] 1
```

Penyebabnya adalah daftar firmware dalam workflow APK yang hanya memasukkan `bios.bin`, bukan `bios-256k.bin` (SeaBIOS 256 KiB) yang dibutuhkan konfigurasi PC default QEMU 11.1.2.

**Perbaikan sudah masuk ke source:** pipeline sekarang mewajibkan `bios-256k.bin` berukuran tepat **262144 byte**, memaketkan beberapa VGA ROM tambahan, dan memastikan keberadaannya dalam APK. Aplikasi juga memeriksa file BIOS/VGA sebelum menjalankan guest x86_64 dan menyimpan firmware hasil ekstraksi sehingga ROM besar tidak disalin ulang pada setiap boot.

**Penting:** hanya APK hasil **Native Dependency Integration Lab yang lebih baru dari commit perbaikan** yang memiliki BIOS tersebut. APK sebelumnya masih akan gagal dengan pesan yang sama. Perbaikan telah **lulus GitHub Actions run 40474093675**. Isi APK hasil build telah diperiksa dan memuat `bios-256k.bin` (262144 byte), `vgabios-stdvga.bin` (39424 byte), dan manifest firmware. **Boot sistem operasi tetap perlu diuji pada HP**; error BIOS berikutnya atau error konfigurasi lain masih mungkin.

## Perbaikan keymap VNC (27 Oktober 2026)

Pengujian HP setelah SeaBIOS berhasil dimuat menunjukkan error:
```text
libqemu-system-x86_64.so: -vnc 127.0.0.1:1:
could not find keymap file for language 'en-us'
[QEMU EXIT STATUS] 1
```

Ini disebabkan **folder `share/qemu/keymaps/` belum ikut dipaketkan**. Pipeline native sekarang membawa seluruh keymap upstream (termasuk `keymaps/en-us`) ke `assets/qemu-roms/keymaps/`, memvalidasi file di dalam APK, dan Android mengekstraknya ke direktori firmware yang sama dengan BIOS. Firmware cache otomatis diperbarui karena manifest berubah.

**ISO installer saja sudah cukup untuk VM x86_64.** Pilih ISO menggunakan **⋮ → Choose ISO**, pilih arsitektur **x86_64**, buat disk virtual lewat **Create virtual disk**, lalu ketuk kartu VM. Lu **tidak perlu** memilih custom kernel, initrd, ataupun DTB untuk ISO x86_64. Kernel/DTB adalah opsi khusus boot ARM64 langsung.

**Build terverifikasi:** [Actions run 40476148120](https://github.com/zaidanfaiz/Qemu-aplication/actions/runs/40476148120) berhasil dan APK-nya sudah diperiksa. APK memuat `keymaps/en-us` (27.086 byte), 34 layout keyboard, `bios-256k.bin`, dan `vgabios-stdvga.bin`; pemeriksaan CRC APK lulus. Belum ada bukti boot ISO berhasil pada perangkat fisik.

**Panduan uji ISO x86_64:** instal APK kandidat terbaru sebagai pembaruan (jangan uninstall agar data VM tidak terhapus) → **⋮ → Architecture → x86_64** → **⋮ → Choose ISO** → **⋮ → Create virtual disk** (kalau belum pernah dibuat) → ketuk **Local VM** → **Display**. Jika VNC masih hitam, buka **⋮ → View QEMU log → Copy log**.



## Pengujian aplikasi

1. Instal **APK kandidat native** dari Native Dependency Lab. Untuk uji engine, jangan memakai APK CI reguler karena belum menyertakan QEMU.
2. Buka aplikasi; menu **⋮ → Native engine status** menunjukkan apakah executable terdeteksi.
3. Di aplikasi, tekan **⋮ → Choose ISO**. Pilih file `.iso` di Files. Image diimpor ke penyimpanan privat aplikasi; pastikan ruang kosong cukup.
4. Gunakan **Architecture** untuk memilih x86_64 atau aarch64. Untuk ARM64, gunakan **Choose custom kernel** dan **Choose DTB** bila diperlukan.
5. Atur **RAM**, **CPU cores**, dan **Disk capacity**, kemudian **Create virtual disk**. Disk tidak ditimpa jika sudah ada.
6. Ketuk kartu **Local VM** untuk mencoba menjalankan QEMU. Jika gagal, buka **⋮ → View QEMU log** dan catat error pertama. Jika berhasil, lanjutkan pengujian **Open VM display**, QMP stop, dan reboot guest. Hasilnya belum dikonfirmasi pada perangkat asli.

> [!NOTE]
> Rilis lama `dev-latest` berasal dari implementasi sebelumnya yang memakai Termux dan **bukan** representasi source `main` saat ini.

## GitHub Actions

| Workflow | Tujuan | Artifact |
|:--|:--|:--|
| [Native Android Prototype CI](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/build-apk.yml) | Bangun UI dan service native Android | `NativeBridge-Prototype-NOT-QEMU` |
| [QEMU Runtime Lab](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/qemu-runtime-lab.yml) | Compile dari [qemu/QEMU](https://github.com/qemu/QEMU) dan smoke test QMP | Linux + Android binaries |
| [QEMU ARM64 Minimal](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/qemu-arm64-minimal.yml) | Pangkas fitur opsional, audit ELF | Paket ARM64 dan laporan dependensi |
| [APK Staging Lab](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/qemu-executable-apk-lab.yml) | Uji pengemasan executable | `NOT-BOOTABLE` APK |
| [Native Dependency Lab](https://github.com/zaidanfaiz/Qemu-aplication/actions/workflows/qemu-native-runtime-lab.yml) | Dependensi transitif + firmware + APK kandidat | `QEMU-ANDROID-NATIVE-DEPENDENCY-CANDIDATE-UNTESTED` |

Sumber QEMU dipin ke versi **11.1.2** untuk konsistensi resep build. Resep Termux dipakai **hanya sebagai alat cross-compile di GitHub Actions**, bukan kewajiban instal Termux oleh pengguna aplikasi.

Panduan lanjutan: [ELF audit](docs/QEMU_RUNTIME_AUDIT.md) · [Minimal build](docs/QEMU_MINIMAL_EXPERIMENT.md) · [APK staging](docs/QEMU_EXECUTABLE_APK_LAB.md).

## Build dari source

Persyaratan: JDK 17, Android SDK 35, NDK `27.2.12479018`, CMake `3.22.1`, Gradle `8.9`.

```bash
gradle --no-daemon assembleDebug
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

Perintah ini hanya membangun **aplikasi native**. Runtime QEMU dibangun lewat workflow terpisah agar kegagalan eksperimen tidak otomatis merusak APK utama.

## Sebelum rilis standalone

- [x] QEMU Android ARM64 terkompilasi dari source.
- [x] Jumlah direct dependencies dikurangi menjadi 10.
- [x] Executable berhasil dimasukkan ke APK eksperimen.
- [x] Audit dan bundling seluruh dependency closure ELF yang ditemukan, termasuk penyesuaian nama library dan RUNPATH dalam APK kandidat.
- [x] Paketkan BIOS/ROM dasar untuk pengujian awal.
- [ ] Pastikan kompatibilitas dengan **Android linker aktual** di HP.
- [ ] Verifikasi proses native bisa dieksekusi pada perangkat Android ARM64.
- [ ] Boot guest Linux sungguhan, uji VNC, QMP, shutdown, dan disk.
- [ ] Audit lisensi dan kewajiban distribusi source QEMU serta dependensinya.

## Kredit dan lisensi

UI dan kode glue aplikasi menggunakan lisensi yang tercantum di repository. QEMU memiliki ketentuan GPL, dan library pihak ketiga memiliki lisensinya masing-masing; mendistribusikan executable/library mensyaratkan pemenuhan kewajiban lisensi yang berlaku.

Source: [QEMU](https://github.com/qemu/QEMU) · [Termux packages](https://github.com/termux/termux-packages) · [Android NDK](https://developer.android.com/ndk).
