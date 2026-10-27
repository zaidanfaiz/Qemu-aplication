package dev.zaidan.qemu;

import android.content.Context;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Map;

/**
 * Experimental Android native EXECUTABLE runner, not an in-process JNI QEMU loader.
 *
 * The QEMU 11.1.2 Bionic artifact is an ELF PIE executable (PT_INTERP)
 * without qemu_init/qemu_main_loop/qemu_cleanup exports. It must be executed,
 * not handed to dlopen(). On Android 10+, app-private writable files cannot
 * be executed; package verified executables in jniLibs under lib*.so names,
 * with useLegacyPackaging=true, for Android to extract into nativeLibraryDir.
 *
 * A working VM still requires the full relocated DT_NEEDED dependency closure,
 * firmware and real-device tests. Stage-1 CI packages only executables.
 */
final class NativeEngine {
    static File executable(Context context, String guest) {
        String fileName = "aarch64".equals(guest) ?
                "libqemu-system-aarch64.so" : "libqemu-system-x86_64.so";
        return new File(context.getApplicationInfo().nativeLibraryDir, fileName);
    }

    static boolean isAvailable(Context context, String guest) {
        File elf = executable(context, guest);
        return elf.isFile() && elf.canExecute();
    }

    static Process start(Context context, String guest, String[] arguments)
            throws IOException {
        File binary = executable(context, guest);
        if (!binary.isFile() || !binary.canExecute()) {
            throw new IOException("Native QEMU executable not packaged: " + binary.getName());
        }
        ArrayList<String> command = new ArrayList<>();
        command.add(binary.getAbsolutePath());
        // NativeEngine owns argv[0] so caller arguments start from argv[1].
        for (int i = 1; i < arguments.length; i++) command.add(arguments[i]);

        ProcessBuilder builder = new ProcessBuilder(command);
        builder.directory(context.getFilesDir());
        Map<String, String> environment = builder.environment();
        environment.put("HOME", context.getFilesDir().getAbsolutePath());
        environment.put("TMPDIR", context.getCacheDir().getAbsolutePath());
        environment.put("XDG_CACHE_HOME", context.getCacheDir().getAbsolutePath());
        environment.put("XDG_CONFIG_HOME", context.getFilesDir().getAbsolutePath());
        environment.put("LD_LIBRARY_PATH", context.getApplicationInfo().nativeLibraryDir);
        environment.put("PATH", "/system/bin:/system/xbin");
        environment.remove("LD_PRELOAD");
        environment.put("QEMU_AUDIO_DRV", "none");
        builder.redirectErrorStream(true);
        builder.redirectOutput(new File(context.getFilesDir(), "qemu.log"));
        return builder.start();
    }

    private NativeEngine() {}
}
