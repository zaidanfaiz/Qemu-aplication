package dev.zaidan.qemu;

import android.content.Context;
import android.content.res.AssetManager;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Extracts QEMU ROM assets into app-private files, never executable storage. */
final class NativeFirmware {
    static File prepare(Context context) throws IOException {
        AssetManager assets = context.getAssets();
        String[] entries = assets.list("qemu-roms");
        if (entries == null || entries.length == 0) return null;
        File folder = new File(context.getFilesDir(), "firmware");
        if (!folder.isDirectory() && !folder.mkdirs())
            throw new IOException("Unable to create QEMU firmware directory");
        for (String name : entries) {
            if (!name.matches("[A-Za-z0-9._-]{1,120}") || name.equals(".") || name.equals(".."))
                throw new IOException("Invalid packaged firmware name");
            File target = new File(folder, name);
            String assetName = "qemu-roms/" + name;
            try (InputStream input = assets.open(assetName)) {
                File staging = new File(folder, name + ".partial");
                try (FileOutputStream output = new FileOutputStream(staging)) {
                    byte[] buffer = new byte[65536];
                    int bytes;
                    while ((bytes = input.read(buffer)) >= 0) {
                        if (bytes > 0) output.write(buffer, 0, bytes);
                    }
                    output.getFD().sync();
                }
                if (target.exists() && !target.delete())
                    throw new IOException("Unable to replace firmware " + name);
                if (!staging.renameTo(target))
                    throw new IOException("Unable to complete firmware " + name);
            }
        }
        return folder;
    }

    private NativeFirmware() {}
}
