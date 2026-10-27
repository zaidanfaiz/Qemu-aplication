package dev.zaidan.qemu;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Files;
import java.nio.file.Path;

/** Self-contained Java 17 checks: no Android instrumentation is required. */
public final class RomImageInspectorTest {
    private static void expect(String expected, File file, String name) throws Exception {
        String actual = RomImageInspector.inspect(file, name);
        if (!expected.equals(actual))
            throw new AssertionError(name + ": expected " + expected + " got " + actual);
    }
    private static void reject(File file, String name, String reason) throws Exception {
        try {
            RomImageInspector.inspect(file, name);
            throw new AssertionError(name + ": unexpectedly accepted; " + reason);
        } catch (IOException correct) {
            System.out.println("REJECTED " + name + ": " + correct.getMessage());
        }
    }
    private static void writeDisk(File file) throws Exception {
        try (RandomAccessFile out = new RandomAccessFile(file, "rw")) {
            out.setLength(2L * 1024 * 1024);
        }
    }
    public static void main(String[] args) throws Exception {
        Path dir = Files.createTempDirectory("rom-image-sniff-");
        try {
            File iso = dir.resolve("android-x86.iso").toFile();
            writeDisk(iso);
            try (RandomAccessFile raf = new RandomAccessFile(iso, "rw")) {
                raf.seek(32769);
                raf.write("CD001".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
            expect("iso", iso, "android-x86.iso");

            File raw = dir.resolve("rom.img").toFile();
            writeDisk(raw);
            try (RandomAccessFile raf = new RandomAccessFile(raw, "rw")) {
                raf.seek(510); raf.write(0x55); raf.write(0xaa);
            }
            expect("raw", raw, "rom.img");
            reject(raw, "rom.zip", "recovery package cannot boot as full disk");

            File qcow = dir.resolve("vm.qcow2").toFile();
            writeDisk(qcow);
            try (RandomAccessFile raf = new RandomAccessFile(qcow, "rw")) {
                raf.seek(0);
                raf.write(new byte[]{'Q','F','I',(byte)0xfb});
                raf.writeInt(3);
                raf.seek(8); raf.writeLong(0);
                raf.seek(72); raf.writeLong(0);
            }
            expect("qcow2", qcow, "vm.qcow2");
            try (RandomAccessFile raf = new RandomAccessFile(qcow, "rw")) {
                raf.seek(8); raf.writeLong(0x400L);
            }
            reject(qcow, "vm.qcow2", "external backing file disallowed");
            try (RandomAccessFile raf = new RandomAccessFile(qcow, "rw")) {
                raf.seek(8); raf.writeLong(0);
                raf.seek(72); raf.writeLong(4);
            }
            reject(qcow, "vm.qcow2", "external data file disallowed");

            File system = dir.resolve("system.img").toFile();
            writeDisk(system);
            try (RandomAccessFile raf = new RandomAccessFile(system, "rw")) {
                raf.seek(1080); raf.writeByte(0x53); raf.writeByte(0xef);
            }
            reject(system, "system.img", "partition without MBR");
            File sparse = dir.resolve("super.img").toFile();
            writeDisk(sparse);
            try (RandomAccessFile raf = new RandomAccessFile(sparse, "rw")) {
                raf.seek(0); raf.write(new byte[]{0x3a,(byte)0xff,0x26,(byte)0xed});
            }
            reject(sparse, "super.img", "Android sparse partition");
            System.out.println("PASS: ROM importer accepts PC ISO/raw/independent QCOW2 and rejects unsafe ROM files");
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.list(dir)) {
                paths.forEach(path -> path.toFile().delete());
            }
            Files.deleteIfExists(dir);
        }
    }
}
