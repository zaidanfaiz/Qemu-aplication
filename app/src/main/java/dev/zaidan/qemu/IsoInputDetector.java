package dev.zaidan.qemu;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Conservative ISO9660 boot configuration scanner (no extra dependencies).
 *
 * This predicts keyboard use at the bootloader/installer, NOT actual driver
 * availability once an OS has started. Supports PVD ISO9660 names with ;1
 * version suffix; unreadable or different ISO layouts become UNKNOWN.
 */
final class IsoInputDetector {
    private static final int SECTOR = 2048, MAX_DIRS = 128;
    private static final int MAX_FILES = 3500, MAX_DIR_BYTES = 1024 * 1024;
    private static final int MAX_CFG_BYTES = 64 * 1024;
    private static final int MAX_SCAN_BYTES = 512 * 1024;
    private static final int MAX_DEPTH = 7;

    static final class Report {
        final String family, boot, install, touch, evidence;
        Report(String family, String boot, String install, String touch, String evidence) {
            this.family = family; this.boot = boot; this.install = install;
            this.touch = touch; this.evidence = evidence;
        }
        String summary() {
            return family + " | Boot: " + boot + " | Install: " + install;
        }
        String detail() {
            return "Jenis ISO: " + family + "\n"
                    + "Keyboard saat boot: " + boot + "\n"
                    + "Keyboard saat instalasi: " + install + "\n"
                    + "Touchscreen setelah boot: " + touch + "\n\n"
                    + "Indikator: " + evidence + "\n\n"
                    + "Hasil ini prediksi dari struktur ISO. Tanpa menjalankan guest, "
                    + "aplikasi tidak bisa memastikan driver touchscreen bekerja.";
        }
    }

    private static final class Dir {
        final long offset, length;
        final String path;
        final int depth;
        Dir(long offset, long length, String path, int depth) {
            this.offset = offset; this.length = length; this.path = path; this.depth = depth;
        }
    }

    static Report unknown(String reason) {
        return new Report("Tidak dikenali", "Tidak diketahui",
                "Tidak diketahui", "Belum dapat dipastikan", reason);
    }

    static Report detect(File iso) throws IOException {
        if (iso == null || !iso.isFile()) throw new IOException("ISO tidak ditemukan");
        try (RandomAccessFile in = new RandomAccessFile(iso, "r")) {
            if (in.length() < 18L * SECTOR)
                return unknown("File terlalu kecil untuk ISO9660.");
            byte[] pvd = new byte[SECTOR];
            in.seek(16L * SECTOR);
            in.readFully(pvd);
            if (pvd[0] != 1 || !equalsAt(pvd, 1, "CD001"))
                return unknown("Tidak ada ISO9660 primary volume descriptor yang didukung.");
            String volume = new String(pvd, 40, 32, StandardCharsets.US_ASCII).trim();
            int rootSize = u32le(pvd, 156 + 10);
            int rootSector = u32le(pvd, 156 + 2);
            if (rootSize < 1 || rootSector < 16)
                return unknown("Direktori root ISO tidak bisa dibaca.");

            StringBuilder names = new StringBuilder();
            StringBuilder configs = new StringBuilder();
            ArrayDeque<Dir> pending = new ArrayDeque<>();
            Set<String> visited = new HashSet<>();
            pending.add(new Dir((long)rootSector * SECTOR, rootSize, "", 0));
            int files = 0, folders = 0;
            while (!pending.isEmpty() && folders++ < MAX_DIRS && files < MAX_FILES) {
                Dir dir = pending.removeFirst();
                String key = dir.offset + ":" + dir.length;
                if (!visited.add(key) || dir.depth > MAX_DEPTH ||
                        dir.length > MAX_DIR_BYTES || dir.offset < 0 ||
                        dir.offset + dir.length > in.length()) continue;
                long cursor = dir.offset;
                long end = dir.offset + dir.length;
                while (cursor + 34 <= end && files < MAX_FILES) {
                    in.seek(cursor);
                    int reclen = in.readUnsignedByte();
                    if (reclen == 0) {
                        cursor = ((cursor / SECTOR) + 1) * SECTOR;
                        continue;
                    }
                    if (reclen < 34 || cursor + reclen > end) break;
                    byte[] record = new byte[reclen];
                    in.seek(cursor);
                    in.readFully(record);
                    cursor += reclen;
                    int nlen = record[32] & 255;
                    if (33 + nlen > record.length || nlen == 0) continue;
                    if (nlen == 1 && (record[33] == 0 || record[33] == 1)) continue;
                    String name = new String(record, 33, nlen, StandardCharsets.US_ASCII)
                            .replaceAll(";[0-9]+$", "").trim().toLowerCase(Locale.ROOT);
                    if (name.isEmpty()) continue;
                    String path = dir.path + "/" + name;
                    boolean directory = (record[25] & 2) != 0;
                    long extent = Integer.toUnsignedLong(u32le(record, 2)) * SECTOR;
                    long length = Integer.toUnsignedLong(u32le(record, 10));
                    if (directory) {
                        if (dir.depth < MAX_DEPTH)
                            pending.add(new Dir(extent, length, path, dir.depth + 1));
                    } else {
                        files++;
                        if (names.length() < MAX_SCAN_BYTES) names.append(path).append('\n');
                        if (isBootConfig(path) && length > 0 && length <= MAX_CFG_BYTES
                                && extent >= 0 && extent + length <= in.length()) {
                            byte[] bytes = new byte[(int)length];
                            in.seek(extent);
                            in.readFully(bytes);
                            if (configs.length() < MAX_SCAN_BYTES)
                                configs.append('\n').append(path).append('\n')
                                    .append(new String(bytes, StandardCharsets.ISO_8859_1)
                                        .toLowerCase(Locale.ROOT)).append('\n');
                        }
                    }
                }
            }
            return classify(volume, names.toString(), configs.toString());
        }
    }

    static Report classify(String volume, String names, String configs) {
        String vol = volume.toLowerCase(Locale.ROOT);
        String all = names.toLowerCase(Locale.ROOT);
        String cfg = configs.toLowerCase(Locale.ROOT);
        boolean android = vol.contains("android") || cfg.contains("androidboot") ||
                cfg.contains("install=1") || all.contains("/system.sfs") ||
                all.contains("/ramdisk.img");
        boolean bliss = vol.contains("bliss") || cfg.contains("bliss") ||
                vol.contains("phoenix") || cfg.contains("phoenix os");
        boolean win = vol.contains("windows") || all.contains("/sources/boot.wim") ||
                all.contains("/bootmgr");
        boolean bootMenu = all.contains("grub.cfg") || all.contains("isolinux.cfg") ||
                all.contains("syslinux.cfg") || cfg.contains("menuentry ") ||
                cfg.contains("menu label ") || cfg.contains("ui menu.c32");
        boolean installMenu = cfg.contains("install=1") || cfg.contains("installation") ||
                cfg.contains("install android") || cfg.contains("install bliss");
        boolean live = cfg.contains("live cd") || cfg.contains("run android") ||
                cfg.contains("live-android");
        boolean auto = cfg.contains("set timeout=") || cfg.contains("timeout ") ||
                cfg.contains("timeout_style=hidden");
        String family = bliss ? "Android PC (Bliss/Phoenix)" :
                android ? "Android-x86 / Android PC" :
                win ? "Windows/WinPE" : bootMenu ? "Linux/ISO dengan GRUB atau ISOLINUX" :
                "ISO lain (belum dikenali)";
        String boot = bootMenu ? (auto ? "Kemungkinan perlu untuk memilih menu; ada timer otomatis" :
                                  "Kemungkinan perlu tombol navigasi GRUB/ISOLINUX") :
                "Tidak diketahui (menu boot tak terdeteksi)";
        String installer = (android && installMenu) ?
                "YA — installer Android-x86 berbasis pilihan teks" :
                win ? "Kemungkinan perlu navigasi installer" :
                installMenu ? "Kemungkinan perlu navigasi installer" :
                "Belum dapat dipastikan dari ISO";
        String touch = "Belum dapat dipastikan; bergantung pada driver guest dan perangkat input VM";
        String evidence = "Volume: " + (volume.isEmpty() ? "(tanpa label)" : volume)
                + "; menu boot=" + bootMenu + "; entri instalasi=" + installMenu
                + "; live mode=" + live + "; timeout=" + auto
                + ". Live boot tidak membuktikan proses instalasi tanpa keyboard.";
        return new Report(family, boot, installer, touch, evidence);
    }

    private static boolean isBootConfig(String path) {
        return path.endsWith("/grub.cfg") || path.endsWith("/isolinux.cfg") ||
                path.endsWith("/syslinux.cfg") || path.endsWith("/menu.lst") ||
                path.endsWith("/txt.cfg") || path.endsWith("/loopback.cfg") ||
                path.endsWith("/grub.conf");
    }

    private static boolean equalsAt(byte[] bytes, int start, String expected) {
        for (int i = 0; i < expected.length(); i++)
            if (bytes[start + i] != (byte)expected.charAt(i)) return false;
        return true;
    }
    private static int u32le(byte[] bytes, int at) {
        return (bytes[at] & 255) | ((bytes[at + 1] & 255) << 8) |
                ((bytes[at + 2] & 255) << 16) | ((bytes[at + 3] & 255) << 24);
    }

    private IsoInputDetector() {}
}
