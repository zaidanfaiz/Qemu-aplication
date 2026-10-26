package dev.zaidan.qemu;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import java.io.File;
import java.net.Socket;
import java.io.OutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Keeps QEMU's blocking native event loop off the Android UI thread. */
public final class NativeVmService extends Service {
    private static volatile boolean running = false;
    private Thread machineThread;
    static boolean isRunning() { return running; }

    static void start(Context context) {
        Intent intent = new Intent(context, NativeVmService.class);
        intent.setAction("start");
        if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent);
        else context.startService(intent);
    }

    static void stop(Context context) {
        Intent intent = new Intent(context, NativeVmService.class);
        intent.setAction("stop");
        context.startService(intent);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && "stop".equals(intent.getAction())) {
            requestStop();
            return START_NOT_STICKY;
        }
        if (running) return START_NOT_STICKY;

        String channel = "qemu_vm";
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (Build.VERSION.SDK_INT >= 26)
            nm.createNotificationChannel(new NotificationChannel(channel, "Running virtual machine",
                    NotificationManager.IMPORTANCE_LOW));
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pending = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(this, channel)
                .setSmallIcon(android.R.drawable.stat_sys_data_usb)
                .setContentTitle("Qemu Application")
                .setContentText("Native QEMU machine session")
                .setContentIntent(pending).setOngoing(true).build();
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(5, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            else startForeground(5, notification);
        } catch (Exception e) {
            stopSelf();
            return START_NOT_STICKY;
        }
        running = true;
        machineThread = new Thread(() -> {
            try {
                SharedPreferences pref = getSharedPreferences("native_vm", MODE_PRIVATE);
                String arch = pref.getString("arch", "x86_64");
                File disk = new File(getFilesDir(), "machine.img");
                String iso = pref.getString("isoPath", "");
                String kernel = pref.getString("kernelPath", "");
                String dtb = pref.getString("dtbPath", "");
                java.util.ArrayList<String> args = new java.util.ArrayList<>();
                args.add(arch.equals("aarch64") ? "qemu-system-aarch64" : "qemu-system-x86_64");
                args.add("-m"); args.add(String.valueOf(pref.getInt("ram", 1024)));
                args.add("-smp"); args.add(String.valueOf(pref.getInt("cores", 2)));
                if (arch.equals("aarch64")) {
                    args.add("-machine"); args.add("virt,accel=tcg");
                    args.add("-cpu"); args.add("cortex-a72");
                    if (!kernel.isEmpty()) { args.add("-kernel"); args.add(kernel); }
                    if (!dtb.isEmpty()) { args.add("-dtb"); args.add(dtb); }
                    args.add("-drive"); args.add("file=" + disk.getAbsolutePath() + ",format=raw,if=virtio");
                    args.add("-device"); args.add("ramfb");
                } else {
                    args.add("-machine"); args.add("pc,accel=tcg");
                    args.add("-drive"); args.add("file=" + disk.getAbsolutePath() + ",format=raw,if=ide");
                    if (!iso.isEmpty()) {
                        args.add("-drive"); args.add("file=" + iso + ",media=cdrom,readonly=on");
                        args.add("-boot"); args.add("order=d");
                    }
                    args.add("-vga"); args.add("std");
                }
                args.add("-display"); args.add("none");
                args.add("-vnc"); args.add("127.0.0.1:1");
                args.add("-qmp"); args.add("tcp:127.0.0.1:4444,server=on,wait=off");
                String error = NativeEngine.run(this, arch, args.toArray(new String[0]));
                android.util.Log.i("QemuNativeService", "QEMU ended: " + error);
            } catch (Throwable t) {
                android.util.Log.e("QemuNativeService", "Native VM failed", t);
            } finally {
                running = false;
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        }, "android-qemu-native");
        machineThread.start();
        return START_NOT_STICKY;
    }

    private void requestStop() {
        new Thread(() -> {
            try (Socket qmp = new Socket("127.0.0.1", 4444)) {
                qmp.setSoTimeout(2000);
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(qmp.getInputStream(), StandardCharsets.UTF_8));
                OutputStream out = qmp.getOutputStream();
                if (reader.readLine() == null) throw new IllegalStateException("No QMP greeting");
                out.write("{\"execute\":\"qmp_capabilities\"}\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
                String line;
                do { line = reader.readLine(); } while (line != null && !line.contains("\"return\""));
                if (line == null) throw new IllegalStateException("QMP capabilities unavailable");
                out.write("{\"execute\":\"quit\"}\r\n".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (Exception e) {
                android.util.Log.e("QemuNativeService", "QMP stop unavailable", e);
            }
        }, "qmp-quit").start();
    }

    @Override public IBinder onBind(Intent intent) { return null; }
}
