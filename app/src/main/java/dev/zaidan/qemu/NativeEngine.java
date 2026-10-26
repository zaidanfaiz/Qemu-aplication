package dev.zaidan.qemu;

import android.content.Context;
import java.io.File;

/** Android NDK bridge. Does not depend on Termux. Requires separately built Android-QEMU .so binaries. */
final class NativeEngine {
    private static final boolean BRIDGE_LOADED;
    static {
        boolean loaded;
        try {
            System.loadLibrary("qemu_bridge");
            loaded = true;
        } catch (UnsatisfiedLinkError e) { loaded = false; }
        BRIDGE_LOADED = loaded;
    }

    static boolean isAvailable(Context context, String guest) {
        if (!BRIDGE_LOADED) return false;
        return qemuLibrary(context, guest).isFile();
    }

    static File qemuLibrary(Context context, String guest) {
        String fileName = "aarch64".equals(guest) ?
                "libqemu-system-aarch64.so" : "libqemu-system-x86_64.so";
        return new File(context.getApplicationInfo().nativeLibraryDir, fileName);
    }

    static String run(Context context, String guest, String[] args) {
        if (!BRIDGE_LOADED) return "Android JNI bridge is missing";
        File lib = qemuLibrary(context, guest);
        if (!lib.isFile()) return "Native QEMU library is not bundled: " + lib.getName();
        return nativeRun(lib.getAbsolutePath(), args);
    }

    private static native String nativeRun(String libraryPath, String[] args);
    private NativeEngine() {}
}
