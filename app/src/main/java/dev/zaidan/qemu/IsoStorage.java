package dev.zaidan.qemu;

import android.app.Activity;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** Copies an SAF boot image into public Downloads, which Termux can access with storage permission.
 *  Never passes an opaque content:// URI to QEMU; QEMU requires a real filesystem path.
 */
final class IsoStorage {
    interface Listener {
        void onProgress(long bytes, long total);
        void onReady(String accessiblePath, String displayName);
        void onError(String error);
    }

    static void importDocument(Activity activity, Uri source, Listener callback) {
        Handler ui = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            Uri created = null;
            File legacyFile = null;
            ContentResolver resolver = activity.getContentResolver();
            try {
                String displayName = "unknown.iso";
                long size = -1;
                try (Cursor c = resolver.query(source,
                        new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                        null, null, null)) {
                    if (c != null && c.moveToFirst()) {
                        int title = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        int length = c.getColumnIndex(OpenableColumns.SIZE);
                        if (title >= 0 && !c.isNull(title)) displayName = c.getString(title);
                        if (length >= 0 && !c.isNull(length)) size = c.getLong(length);
                    }
                }
                if (!displayName.toLowerCase(java.util.Locale.ROOT).endsWith(".iso")) {
                    throw new IOException("Choose a .iso image. Selected: " + displayName);
                }
                StatFs stats = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
                if (size > 0 && size + 64L * 1024 * 1024 > stats.getAvailableBytes())
                    throw new IOException("Not enough free storage to import this ISO.");
                final String publicName = "qemu-" + System.currentTimeMillis() + ".iso";
                String publicPath = "/sdcard/Download/QemuApplication/" + publicName;
                OutputStream output;
                if (Build.VERSION.SDK_INT >= 29) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, publicName);
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/QemuApplication/");
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    created = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
                    if (created == null) throw new IOException("Unable to create destination in Downloads.");
                    output = resolver.openOutputStream(created, "w");
                } else {
                    File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
                            "QemuApplication");
                    if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create Download/QemuApplication.");
                    legacyFile = new File(dir, publicName);
                    output = new FileOutputStream(legacyFile);
                }
                if (output == null) throw new IOException("Cannot open destination image.");
                long written = 0;
                long lastNotice = 0;
                try (InputStream input = resolver.openInputStream(source); OutputStream dest = output) {
                    if (input == null) throw new IOException("Selected file cannot be opened.");
                    byte[] buffer = new byte[256 * 1024];
                    int n;
                    while ((n = input.read(buffer)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw new IOException("Import interrupted.");
                        dest.write(buffer, 0, n);
                        written += n;
                        long currentTime = android.os.SystemClock.uptimeMillis();
                        if (currentTime - lastNotice > 280) {
                            long progress = written, total = size;
                            ui.post(() -> callback.onProgress(progress, total));
                            lastNotice = currentTime;
                        }
                    }
                    dest.flush();
                }
                if (size >= 0 && written != size)
                    throw new IOException("Incomplete ISO copy (" + written + " of " + size + " bytes).");
                if (created != null) {
                    ContentValues done = new ContentValues();
                    done.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (resolver.update(created, done, null, null) <= 0)
                        throw new IOException("Could not finalize downloaded ISO.");
                }
                String name = displayName;
                ui.post(() -> callback.onReady(publicPath, name));
            } catch (Exception ex) {
                if (created != null) {
                    try { resolver.delete(created, null, null); } catch (Exception ignored) {}
                }
                if (legacyFile != null && legacyFile.exists()) legacyFile.delete();
                String message = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                ui.post(() -> callback.onError(message));
            }
        }, "iso-import").start();
    }

    private IsoStorage() {}
}
