package dev.zaidan.qemu;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Locale;

/**
 * Conservative sniffing of Android-x86 PC installers and complete VM disks.
 *
 * Phone recovery ZIPs, Android sparse partitions, boot/system/vendor images
 * and device-specific ROM images do not contain a bootable QEMU PC disk.
 * The code checks the actual file bytes, not just a picked filename.
 */
final class RomImageInspector {
    static final String ISO = "iso";
    static final String RAW = "raw";
    static final String QCOW2 = "qcow2";

    static boolean isIsoName(String name) {
        return name != null && name.toLowerCase(Locale.ROOT).endsWith(".iso");
    }

    static String inspect(File file, String displayName) throws IOException {
        if (displayName == null) throw new IOException("No ROM filename");
        String lower = displayName.toLowerCase(Locale.ROOT);
        if (lower.endsWith(".zip") || lower.endsWith(".7z")
                || lower.endsWith(".tar") || lower.endsWith(".gz")
                || lower.endsWith(".payload.bin")) {
            throw new IOException("Recovery/OTA ZIP bukan image bootable. Gunakan ISO Android-x86 atau disk VM lengkap.");
        }
        if (!isIsoName(lower) && !lower.endsWith(".img")
                && !lower.endsWith(".raw") && !lower.endsWith(".qcow2")) {
            throw new IOException("Pilih file .iso, .img, .raw, atau .qcow2 untuk VM PC.");
        }
        if (file == null || !file.isFile() || file.length() < 1024 * 1024)
            throw new IOException("Image tidak lengkap atau terlalu kecil (< 1 MB).");

        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            byte[] header = new byte[512];
            in.readFully(header);
            if (header[0] == 'P' && header[1] == 'K')
                throw new IOException("ZIP recovery tidak bisa di-boot langsung. Gunakan installer ISO PC.");
            if (starts(header, "ANDROID!") || starts(header, "VNDRBOOT") ||
                    starts(header, "AVB0"))
                throw new IOException("Ini partisi boot/vendor Android, bukan image satu disk VM.");
            if ((header[0] & 255) == 0x3a && (header[1] & 255) == 0xff
                    && (header[2] & 255) == 0x26 && (header[3] & 255) == 0xed)
                throw new IOException("Android sparse system.img bukan disk bootable QEMU.");

            if (isIsoName(lower)) {
                in.seek(32769);
                byte[] pvd = new byte[5];
                in.readFully(pvd);
                if (!starts(pvd, "CD001"))
                    throw new IOException("Bukan ISO9660 yang valid atau file ISO rusak.");
                return ISO;
            }

            boolean isQcow = (header[0] & 255) == 0x51 && (header[1] & 255) == 0x46
                    && (header[2] & 255) == 0x49 && (header[3] & 255) == 0xfb;
            if (isQcow) {
                if (!lower.endsWith(".qcow2"))
                    throw new IOException("Terdeteksi QCOW2, ubah ekstensi file menjadi .qcow2.");
                int version = readBeInt(header, 4);
                if (version != 2 && version != 3)
                    throw new IOException("Versi QCOW2 tidak dikenal.");
                if (readBeLong(header, 8) != 0)
                    throw new IOException("QCOW2 mempunyai backing file eksternal, tidak didukung demi keamanan.");
                if (version == 3 && (readBeLong(header, 72) & 0x4L) != 0)
                    throw new IOException("QCOW2 memakai external data file, tidak didukung.");
                return QCOW2;
            }
            if (lower.endsWith(".qcow2"))
                throw new IOException("Header QCOW2 tidak valid.");

            // A complete bootable PC disk has an MBR (including protective
            // MBR for GPT), unlike GSI system.img and raw ext4 partitions.
            boolean mbr = (header[510] & 255) == 0x55 &&
                    (header[511] & 255) == 0xaa;
            if (!mbr) {
                throw new IOException("IMG ini bukan disk PC lengkap (MBR/GPT tidak ditemukan). " +
                        "system.img/boot.img/GSI dari HP tidak dapat boot langsung.");
            }
            return RAW;
        }
    }

    private static boolean starts(byte[] data, String signature) {
        byte[] bytes = signature.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        if (data.length < bytes.length) return false;
        for (int i = 0; i < bytes.length; i++)
            if (data[i] != bytes[i]) return false;
        return true;
    }

    private static int readBeInt(byte[] data, int offset) {
        return ((data[offset] & 255) << 24) | ((data[offset + 1] & 255) << 16)
                | ((data[offset + 2] & 255) << 8) | (data[offset + 3] & 255);
    }

    private static long readBeLong(byte[] data, int offset) {
        long high = readBeInt(data, offset) & 0xffffffffL;
        long low = readBeInt(data, offset + 4) & 0xffffffffL;
        return (high << 32) | low;
    }

    private RomImageInspector() {}
}
