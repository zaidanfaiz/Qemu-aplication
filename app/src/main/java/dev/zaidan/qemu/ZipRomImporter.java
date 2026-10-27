package dev.zaidan.qemu;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Import a ZIP containing ONE complete QEMU-bootable image.
 *
 * OTA/recovery ZIP files for physical phones require matching bootloader,
 * partitions and recovery. They are not a generic PC disk and must not be
 * silently "flashed" or treated as a QEMU bootable system.
 *
 * Security: no user-controlled archive paths are extracted. Only a vetted
 * candidate is streamed to a fixed private output file, with size/space/CRC
 * limits. Never extract ZIP metadata, executables or updater scripts.
 */
final class ZipRomImporter {
    static final long MAX_IMAGE_BYTES = 24L * 1024 * 1024 * 1024; // 24 GiB
    static final int MAX_ENTRIES = 4096;
    private static final long MIN_FREE_AFTER = 32L * 1024 * 1024;
    private static final String OTA_ERROR =
            "Ini ZIP recovery/OTA ROM untuk HP, bukan installer PC. " +
            "QEMU memerlukan image disk/ISO Android-x86; ZIP perangkat " +
            "membutuhkan recovery, partisi, dan driver perangkat yang cocok.";

    static final class Result {
        final File extracted;
        final String imageName;
        final String format;

        Result(File extracted, String imageName, String format) {
            this.extracted = extracted;
            this.imageName = imageName;
            this.format = format;
        }
    }

    static Result extract(File archive, File privateDir, boolean isoOnly) throws IOException {
        if (!archive.isFile() || archive.length() < 22)
            throw new IOException("File ZIP tidak lengkap.");
        if (!privateDir.isDirectory() && !privateDir.mkdirs())
            throw new IOException("Tidak bisa membuat folder impor.");

        File output = new File(privateDir, "zip-image.partial");
        if (output.exists() && !output.delete())
            throw new IOException("Tidak bisa membersihkan impor ZIP sebelumnya.");

        try (ZipFile zip = new ZipFile(archive)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            ZipEntry candidate = null;
            int count = 0;
            boolean deviceOta = false;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (++count > MAX_ENTRIES)
                    throw new IOException("ZIP memiliki terlalu banyak file.");
                String path = entry.getName().replace('\\', '/');
                if (path.startsWith("/") || path.contains("../") || path.equals("..")
                        || path.contains("\u0000"))
                    throw new IOException("ZIP memiliki path yang tidak aman.");
                if (entry.isDirectory()) continue;
                String lower = path.toLowerCase(Locale.ROOT);
                if (lower.equals("payload.bin") || lower.endsWith("/payload.bin")
                        || lower.equals("payload_properties.txt")
                        || lower.startsWith("meta-inf/com/google/android/")
                        || lower.equals("meta-inf/com/android/metadata")
                        || lower.endsWith("/updater-script")) {
                    deviceOta = true;
                }

                String base = lower.substring(lower.lastIndexOf('/') + 1);
                if (isPartition(base) || !isImageName(base)
                        || (isoOnly && !base.endsWith(".iso"))) continue;
                if (candidate != null)
                    throw new IOException("ZIP memiliki beberapa ISO/disk. " +
                            "Sisakan satu image bootable dalam ZIP, lalu impor ulang.");
                candidate = entry;
            }

            if (deviceOta) throw new IOException(OTA_ERROR);
            if (candidate == null) throw new IOException(
                    "ZIP tidak memiliki ISO atau disk VM lengkap. " +
                    "ZIP custom ROM HP, system.img, boot.img, dan GSI " +
                    "tidak bisa dipasang langsung sebagai PC QEMU.");
            long expected = candidate.getSize();
            if (expected < 1024 * 1024 || expected > MAX_IMAGE_BYTES)
                throw new IOException("Image di ZIP terlalu kecil/besar atau tidak diketahui.");
            if (privateDir.getUsableSpace() < expected + MIN_FREE_AFTER)
                throw new IOException("Penyimpanan tidak cukup untuk ekstrak image ZIP (" +
                        (expected / (1024 * 1024)) + " MB).");

            long written = 0;
            CRC32 crc = new CRC32();
            try (InputStream input = zip.getInputStream(candidate);
                 FileOutputStream out = new FileOutputStream(output)) {
                byte[] data = new byte[256 * 1024];
                int n;
                while ((n = input.read(data)) != -1) {
                    if (n == 0) continue;
                    written += n;
                    if (written > expected || written > MAX_IMAGE_BYTES)
                        throw new IOException("Batas ukuran image ZIP terlampaui.");
                    crc.update(data, 0, n);
                    out.write(data, 0, n);
                }
                out.getFD().sync();
            }
            if (written != expected)
                throw new IOException("File ZIP tidak lengkap: ukuran ekstraksi tidak sesuai.");
            if (candidate.getCrc() >= 0 && candidate.getCrc() != crc.getValue())
                throw new IOException("CRC image ZIP tidak cocok; file mungkin rusak.");
            String displayName = candidate.getName();
            String baseName = displayName.substring(displayName.lastIndexOf('/') + 1);
            String format = RomImageInspector.inspect(output, baseName);
            return new Result(output, baseName, format);
        } catch (IOException | RuntimeException failure) {
            if (output.exists()) output.delete();
            throw failure;
        }
    }

    private static boolean isImageName(String name) {
        return name.endsWith(".iso") || name.endsWith(".img") ||
                name.endsWith(".raw") || name.endsWith(".qcow2");
    }

    private static boolean isPartition(String name) {
        String base = name;
        if (base.endsWith(".img")) base = base.substring(0, base.length() - 4);
        else if (base.endsWith(".raw")) base = base.substring(0, base.length() - 4);
        else return false;
        return base.equals("system") || base.equals("system_a") ||
                base.equals("system_b") || base.equals("vendor") ||
                base.equals("vendor_a") || base.equals("vendor_b") ||
                base.equals("product") || base.equals("odm") ||
                base.equals("boot") || base.equals("init_boot") ||
                base.equals("vendor_boot") || base.equals("recovery") ||
                base.equals("super") || base.equals("userdata") ||
                base.equals("vbmeta") || base.equals("dtbo") ||
                base.equals("persist") || base.equals("cache") ||
                base.equals("metadata");
    }

    private ZipRomImporter() {}
}
