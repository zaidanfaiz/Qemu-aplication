package dev.zaidan.qemu;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Extracts QEMU BIOS/VGA/UEFI assets from APK into app-private data.
 * A small asset manifest prevents re-copying the 64 MB EDK2 image on every
 * VM start. The marker is committed only after every asset was extracted.
 */
final class NativeFirmware {
    private static final String ASSET_ROOT = "qemu-roms";
    private static final String MANIFEST = "FIRMWARE-SHA256SUMS";
    private static final String STAMP = ".extracted-firmware-manifest";

    static File prepare(Context context, String guestArchitecture) throws IOException {
        AssetManager assets = context.getAssets();
        String[] entries = assets.list(ASSET_ROOT);
        if (entries == null || entries.length == 0) return null;

        File folder = new File(context.getFilesDir(), "firmware");
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Cannot create app-private QEMU firmware directory");

        // Main's lightweight prototype APK has no ROM files; the native
        // runtime CI candidate has a SHA256 manifest bundled with all ROMs.
        String currentManifest = readManifest(assets);
        File stamp = new File(folder, STAMP);
        if (currentManifest != null && stamp.isFile()
                && currentManifest.equals(new String(Files.readAllBytes(stamp.toPath()),
                        StandardCharsets.UTF_8))
                && allFilesExist(assets, ASSET_ROOT, folder)) {
            checkRequiredPcBios(folder, guestArchitecture);
            return folder;
        }

        // QEMU's -L directory contains nested assets like keymaps/en-us.
        // Copy recursively; the former flat copier could not package VNC keymaps.
        for (String name : entries) {
            if (!safeName(name)) throw new IOException("Invalid firmware name: " + name);
            if (name.equals(MANIFEST)) continue;
            copyAssetTree(assets, ASSET_ROOT + "/" + name, new File(folder, name));
        }

        checkRequiredPcBios(folder, guestArchitecture);

        if (currentManifest != null) {
            File temp = new File(folder, STAMP + ".partial");
            try (FileOutputStream output = new FileOutputStream(temp)) {
                output.write(currentManifest.getBytes(StandardCharsets.UTF_8));
                output.getFD().sync();
            }
            if (stamp.exists() && !stamp.delete())
                throw new IOException("Cannot update firmware manifest");
            if (!temp.renameTo(stamp))
                throw new IOException("Cannot finish firmware manifest");
        }
        return folder;
    }

    private static void copyAssetTree(AssetManager assets, String assetPath, File target)
            throws IOException {
        String[] children = assets.list(assetPath);
        if (children != null && children.length > 0) {
            if (!target.isDirectory() && !target.mkdirs())
                throw new IOException("Cannot create firmware subdirectory: " + assetPath);
            for (String child : children) {
                if (!safeName(child)) throw new IOException("Unsafe firmware asset name");
                copyAssetTree(assets, assetPath + "/" + child, new File(target, child));
            }
            return;
        }

        File temp = new File(target.getParentFile(), target.getName() + ".partial");
        if (temp.exists() && !temp.delete())
            throw new IOException("Cannot clean old firmware partial file");
        try (InputStream input = assets.open(assetPath);
             FileOutputStream output = new FileOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) != -1)
                if (count > 0) output.write(buffer, 0, count);
            output.getFD().sync();
        } catch (IOException ex) {
            temp.delete();
            throw ex;
        }
        if (target.exists() && !target.delete())
            throw new IOException("Cannot replace firmware: " + assetPath);
        if (!temp.renameTo(target))
            throw new IOException("Cannot complete firmware import: " + assetPath);
    }

    private static boolean allFilesExist(AssetManager assets, String path, File target)
            throws IOException {
        String[] entries = assets.list(path);
        if (entries == null || entries.length == 0)
            return target.isFile() && target.length() > 0;
        if (!target.isDirectory()) return false;
        for (String child : entries) {
            if (!safeName(child)) return false;
            if (path.equals(ASSET_ROOT) && child.equals(MANIFEST)) continue;
            if (!allFilesExist(assets, path + "/" + child, new File(target, child)))
                return false;
        }
        return true;
    }

    private static String readManifest(AssetManager assets) {
        try (InputStream input = assets.open(ASSET_ROOT + "/" + MANIFEST)) {
            byte[] buffer = new byte[32768];
            int n = input.read(buffer);
            if (n <= 0 || n == buffer.length) return null;
            return new String(buffer, 0, n, StandardCharsets.UTF_8);
        } catch (IOException noManifest) {
            return null;
        }
    }

    private static boolean safeName(String name) {
        return name.matches("[A-Za-z0-9._-]{1,120}") &&
               !name.equals(".") && !name.equals("..");
    }

    private static void checkRequiredPcBios(File folder, String guest) throws IOException {
        if (!"x86_64".equals(guest)) return;
        File bios = new File(folder, "bios-256k.bin");
        if (!bios.isFile() || bios.length() != 262144L)
            throw new IOException("Required x86 PC BIOS missing or invalid: bios-256k.bin. " +
                    "Install the latest Native Dependency Lab APK (old builds omitted SeaBIOS).");
        File keymap = new File(folder, "keymaps/en-us");
        if (!keymap.isFile() || keymap.length() == 0L)
            throw new IOException("VNC keyboard layout missing: keymaps/en-us. " +
                    "Install a Native Dependency Lab APK built after the keymap fix.");
        File vga = new File(folder, "vgabios-stdvga.bin");
        if (!vga.isFile() || vga.length() == 0L)
            throw new IOException("Required x86 VGA ROM missing: vgabios-stdvga.bin.");
    }

    private NativeFirmware() {}
}
