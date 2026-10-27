package dev.zaidan.qemu;

import java.io.File;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Deterministic ISO9660 fixture + boot-input confidence tests for CI. */
public final class IsoInputDetectorTest {
    private static final int BLOCK = 2048;
    private static void le32(byte[] data, int offset, int value) {
        data[offset] = (byte)value;
        data[offset+1] = (byte)(value >>> 8);
        data[offset+2] = (byte)(value >>> 16);
        data[offset+3] = (byte)(value >>> 24);
    }
    private static byte[] record(int sector, int bytes, boolean dir, byte[] name) {
        int size = 33 + name.length + (name.length % 2 == 0 ? 1 : 0);
        byte[] entry = new byte[size];
        entry[0] = (byte)size;
        le32(entry, 2, sector);
        le32(entry, 10, bytes);
        entry[25] = (byte)(dir ? 2 : 0);
        entry[28] = 1; entry[31] = 1;
        entry[32] = (byte)name.length;
        System.arraycopy(name, 0, entry, 33, name.length);
        return entry;
    }
    private static File iso(Path folder, String label, String config) throws Exception {
        File path = Files.createTempFile(folder, "iso-boot-", ".iso").toFile();
        byte[] pvd = new byte[BLOCK];
        pvd[0] = 1;
        System.arraycopy("CD001".getBytes(StandardCharsets.US_ASCII),0,pvd,1,5);
        pvd[6] = 1;
        byte[] vol = label.getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(vol,0,pvd,40,Math.min(vol.length,32));
        byte[] rootRec = record(20, BLOCK, true, new byte[]{0});
        System.arraycopy(rootRec,0,pvd,156,rootRec.length);

        byte[] root = new byte[BLOCK];
        byte[] dot = record(20, BLOCK, true, new byte[]{0});
        byte[] isolinux = record(21,BLOCK,true,
                "ISOLINUX".getBytes(StandardCharsets.US_ASCII));
        System.arraycopy(dot,0,root,0,dot.length);
        System.arraycopy(isolinux,0,root,dot.length,isolinux.length);
        byte[] configs = new byte[BLOCK];
        byte[] configBytes = config.getBytes(StandardCharsets.US_ASCII);
        byte[] configRec = record(22,configBytes.length,false,
                "ISOLINUX.CFG;1".getBytes(StandardCharsets.US_ASCII));
        System.arraycopy(configRec,0,configs,0,configRec.length);
        try(RandomAccessFile out = new RandomAccessFile(path,"rw")) {
            out.setLength(24L * BLOCK);
            out.seek(16L * BLOCK); out.write(pvd);
            out.seek(20L * BLOCK); out.write(root);
            out.seek(21L * BLOCK); out.write(configs);
            out.seek(22L * BLOCK); out.write(configBytes);
        }
        return path;
    }
    private static void require(boolean pass,String message) {
        if (!pass) throw new AssertionError(message);
    }
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("iso-input-test");
        try {
            File android = iso(folder,"ANDROID-X86",
                    "UI menu.c32\nTIMEOUT 30\n" +
                    "LABEL live\nMENU LABEL Live CD - Run Android-x86 without installation\n" +
                    "LABEL install\nMENU LABEL Installation - Install Android-x86 to harddisk\n" +
                    "APPEND initrd=/initrd.img INSTALL=1\n");
            IsoInputDetector.Report a = IsoInputDetector.detect(android);
            require(a.family.contains("Android"),"Must recognize Android from ISO PVD/config");
            require(a.boot.contains("Kemungkinan"),"Boot menu needs navigation");
            require(a.install.startsWith("YA"),"Android text installer needs keyboard");
            require(a.touch.contains("Belum"),"No false touch-ready claim");
            require(a.evidence.contains("menu boot=true"),"Read actual isolinux.cfg");

            File linux = iso(folder,"UBUNTU-LIVE",
                    "UI menu.c32\nLABEL ubuntu\nMENU LABEL Try Ubuntu\n");
            IsoInputDetector.Report b = IsoInputDetector.detect(linux);
            require(b.family.contains("Linux"),"Generic ISOLINUX should be Linux/boot menu");
            require(b.install.contains("Belum"),"Unknown installer, no unjustified guarantee");

            File hidden = iso(folder,"UNKNOWN",
                    "No known boot entries at all\n");
            IsoInputDetector.Report c = IsoInputDetector.detect(hidden);
            require(c.boot.contains("Tidak diketahui"),"No menu cannot be declared touch-only");
            require(c.touch.contains("Belum"),"No touch support guarantee");

            System.out.println("PASS: ISO9660 boot config parsing, Android-x86 text installer, "
                    + "Linux menu and unknown/driver fallback");
        } finally {
            try(java.util.stream.Stream<Path> list = Files.list(folder)) {
                list.forEach(item -> item.toFile().delete());
            }
            Files.deleteIfExists(folder);
        }
    }
}
