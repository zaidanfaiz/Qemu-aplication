package dev.zaidan.qemu;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;
import android.text.InputType;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    private static final String TERMUX_PERMISSION = "com.termux.permission.RUN_COMMAND";
    private static final String TERMUX_BASH = "/data/data/com.termux/files/usr/bin/bash";
    private static final String TERMUX_HOME = "/data/data/com.termux/files/home";
    private static final int TEXT = 0xFFDFE7F5, MUTED = 0xFF9CAFC8, BG = 0xFF0B1220, CARD = 0xFF172238;
    private EditText iso, ram, cores, diskSize, kernel, initrd, dtb, kernelArgs;
    private Spinner cpu;
    private TextView status;
    private SharedPreferences preferences;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        preferences = getSharedPreferences("vm", MODE_PRIVATE);
        buildUi();
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(BG);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(26), dp(20), dp(32));
        scroll.addView(root);
        setContentView(scroll);

        TextView eyebrow = label("ANDROID • QEMU CONTROL CENTER", 12, 0xFF60A5FA, true);
        root.addView(eyebrow);
        TextView headline = label("Virtual machines\non your phone.", 31, TEXT, true);
        headline.setPadding(0, dp(10), 0, dp(8));
        root.addView(headline);
        TextView intro = label("Local QEMU backend via Termux • VNC display • QMP shutdown. No root required for software emulation.", 14, MUTED, false);
        root.addView(intro);
        root.addView(spacer(22));

        LinearLayout form = panel(root);
        form.addView(label("MACHINE CONFIGURATION", 12, 0xFF60A5FA, true));
        form.addView(spacer(14));
        form.addView(label("Guest CPU architecture", 14, TEXT, true));
        cpu = new Spinner(this);
        String[] arch = {"x86_64", "i386", "aarch64 (kernel)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, arch);
        cpu.setAdapter(adapter);
        cpu.setSelection(preferences.getInt("arch", 0));
        form.addView(cpu, new LinearLayout.LayoutParams(-1, dp(49)));
        iso = field(form, "Boot ISO path (x86 only)", "/sdcard/Download/linux.iso", preferences.getString("iso", ""), false);
        form.addView(label("EXPERIMENTAL ARM64 DIRECT-KERNEL BOOT", 12, 0xFF60A5FA, true));
        kernel = field(form, "ARM64 kernel image path", "/sdcard/Download/Image", preferences.getString("kernel", ""), false);
        initrd = field(form, "ARM64 initrd (optional)", "/sdcard/Download/initramfs.cpio.gz", preferences.getString("initrd", ""), false);
        dtb = field(form, "ARM64 DTB (optional)", "/sdcard/Download/virt.dtb", preferences.getString("dtb", ""), false);
        kernelArgs = field(form, "ARM64 kernel arguments", "console=ttyAMA0", preferences.getString("kargs", "console=ttyAMA0"), false);
        ram = field(form, "RAM (MB)", "1024", preferences.getString("ram", "1024"), true);
        cores = field(form, "vCPU cores", "2", preferences.getString("cores", "2"), true);
        diskSize = field(form, "New disk size (GB)", "8", preferences.getString("disk", "8"), true);
        form.addView(spacer(6));
        action(form, "Create virtual disk", false, () -> createDisk());
        action(form, "▶  Start QEMU", true, () -> startVm());
        action(form, "Open VNC display", false, () -> startActivity(new Intent(this, ViewerActivity.class)));
        action(form, "■  Stop VM (QMP quit)", false, () -> stopVm());

        root.addView(spacer(17));
        LinearLayout setup = panel(root);
        setup.addView(label("FIRST-TIME SETUP", 12, 0xFF60A5FA, true));
        setup.addView(spacer(8));
        setup.addView(label("1. Install Termux from F-Droid or official GitHub.\n2. In Termux: pkg update && pkg install qemu-system-x86-64-headless qemu-system-i386-headless qemu-system-aarch64-headless qemu-utils\n3. Run: termux-setup-storage\n4. Run: mkdir -p ~/.termux && echo 'allow-external-apps = true' >> ~/.termux/termux.properties\n5. In Android app settings, grant Qemu Application permission to run commands in Termux. Restart Termux.\n6. Put a compatible boot ISO in Download, enter its path, create a disk, start VM, then open VNC.", 13, MUTED, false));
        action(setup, "Open Termux", false, () -> openTermux());

        root.addView(spacer(14));
        status = label("Idle • Ready to configure", 13, 0xFF93C5FD, false);
        root.addView(status);
        root.addView(spacer(12));
        root.addView(label("Developer preview: Termux-based QEMU. Experimental ARM64 kernel mode is not an iOS boot implementation.", 12, MUTED, false));
    }

    private void persist() {
        preferences.edit().putInt("arch", cpu.getSelectedItemPosition())
          .putString("iso", iso.getText().toString().trim())
          .putString("ram", ram.getText().toString().trim())
          .putString("cores", cores.getText().toString().trim())
          .putString("disk", diskSize.getText().toString().trim())
          .putString("kernel", kernel.getText().toString().trim())
          .putString("initrd", initrd.getText().toString().trim())
          .putString("dtb", dtb.getText().toString().trim())
          .putString("kargs", kernelArgs.getText().toString().trim()).apply();
    }

    private void createDisk() {
        persist();
        int gb = numeric(diskSize, 1, 128);
        if (gb == -1) { info("Disk size must be 1–128 GB."); return; }
        String command = "set -eu; mkdir -p \"$HOME/qemu-aplication\"; " +
          "command -v qemu-img >/dev/null || { echo 'Missing qemu-utils'; exit 127; }; " +
          "if [ -e \"$HOME/qemu-aplication/disk.qcow2\" ]; then echo 'Disk already exists'; exit 1; fi; " +
          "qemu-img create -f qcow2 \"$HOME/qemu-aplication/disk.qcow2\" " + gb + "G " +
          "> \"$HOME/qemu-aplication/create-disk.log\" 2>&1";
        runTermux(command, "Virtual disk creation requested. Check ~/qemu-aplication/create-disk.log in Termux.");
    }

    private void startVm() {
        persist();
        int memory = numeric(ram, 256, 8192), count = numeric(cores, 1, 8);
        if (memory == -1 || count == -1) { info("RAM: 256–8192 MB. Cores: 1–8."); return; }
        if (cpu.getSelectedItemPosition() == 2) { startArm64(memory, count); return; }
        String bootIso = iso.getText().toString().trim();
        if (!bootIso.isEmpty() && !(bootIso.startsWith("/sdcard/") ||
             bootIso.startsWith("/storage/emulated/0/") || bootIso.startsWith("/data/data/com.termux/files/home/"))) {
            info("ISO path must be inside shared storage or Termux home."); return;
        }
        if (bootIso.contains("\n") || bootIso.contains("\r")) { info("Invalid ISO path."); return; }
        String binary = cpu.getSelectedItemPosition() == 1 ? "qemu-system-i386" : "qemu-system-x86_64";
        String boot = bootIso.isEmpty() ? "c" : "d";
        String optIso = bootIso.isEmpty() ? "" :
             " -drive file=" + quote(bootIso) + ",media=cdrom,readonly=on";
        String command = "mkdir -p \"$HOME/qemu-aplication\"; " +
          "if ! command -v " + binary + " >/dev/null; then echo 'QEMU package missing' > \"$HOME/qemu-aplication/run.log\"; exit 127; fi; " +
          "if [ ! -f \"$HOME/qemu-aplication/disk.qcow2\" ]; then echo 'Create disk first' > \"$HOME/qemu-aplication/run.log\"; exit 1; fi; " +
          "exec " + binary + " -machine pc -accel tcg,thread=multi -cpu max -smp " + count +
          " -m " + memory + " -boot order=" + boot +
          " -drive file=\"$HOME/qemu-aplication/disk.qcow2\",if=ide,format=qcow2" + optIso +
          " -device usb-tablet -vga std" +
          " -netdev user,id=net0 -device e1000,netdev=net0" +
          " -vnc 127.0.0.1:1 -display none" +
          " -qmp tcp:127.0.0.1:4444,server=on,wait=off" +
          " > \"$HOME/qemu-aplication/run.log\" 2>&1";
        runTermux(command, "Start request sent. Once QEMU is running, open VNC display (127.0.0.1:5901).");
    }

    private boolean acceptablePath(String path) {
        return !path.isEmpty() && !path.contains("\n") && !path.contains("\r")
          && (path.startsWith("/sdcard/") || path.startsWith("/storage/emulated/0/")
          || path.startsWith("/data/data/com.termux/files/home/"));
    }

    private void startArm64(int memory, int count) {
        String k = kernel.getText().toString().trim();
        String r = initrd.getText().toString().trim();
        String d = dtb.getText().toString().trim();
        String args = kernelArgs.getText().toString().trim();
        if (!acceptablePath(k) || (!r.isEmpty() && !acceptablePath(r)) ||
            (!d.isEmpty() && !acceptablePath(d)) ||
            args.contains("\n") || args.contains("\r") || args.length() > 1024) {
            info("ARM64 needs valid paths and boot arguments.");
            return;
        }
        String other = (r.isEmpty() ? "" : " -initrd " + quote(r))
                     + (d.isEmpty() ? "" : " -dtb " + quote(d));
        String script = "mkdir -p \"$HOME/qemu-aplication\"; " +
          "if ! command -v qemu-system-aarch64 >/dev/null; then echo 'Install qemu-system-aarch64-headless' > \"$HOME/qemu-aplication/run.log\"; exit 127; fi; " +
          "if [ ! -f \"$HOME/qemu-aplication/disk.qcow2\" ]; then echo 'Create disk first' > \"$HOME/qemu-aplication/run.log\"; exit 1; fi; " +
          "exec qemu-system-aarch64 -machine virt,accel=tcg -cpu cortex-a72" +
          " -smp " + count + " -m " + memory + " -kernel " + quote(k) + other +
          " -append " + quote(args) +
          " -drive file=\"$HOME/qemu-aplication/disk.qcow2\",format=qcow2,if=virtio" +
          " -device ramfb -device qemu-xhci -device usb-kbd -device usb-tablet" +
          " -netdev user,id=net0 -device virtio-net-device,netdev=net0" +
          " -display none -vnc 127.0.0.1:1 -serial stdio -monitor none" +
          " -qmp tcp:127.0.0.1:4444,server=on,wait=off" +
          " > \"$HOME/qemu-aplication/run.log\" 2>&1";
        runTermux(script, "ARM64 launch requested. Inspect serial logs in Termux at ~/qemu-aplication/run.log.");
    }

    private void stopVm() {
        info("Sending QMP shutdown command…");
        new Thread(() -> {
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1", 4444), 2500);
                socket.setSoTimeout(3000);
                BufferedReader input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                OutputStream output = socket.getOutputStream();
                String greeting = input.readLine();
                if (greeting == null || !greeting.contains("QMP")) throw new IllegalStateException("QMP handshake failed");
                output.write("{\"execute\":\"qmp_capabilities\"}\r\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                String answer;
                do { answer = input.readLine(); } while (answer != null && !answer.contains("return") && !answer.contains("error"));
                if (answer == null || answer.contains("error")) throw new IllegalStateException("QMP capabilities failed");
                output.write("{\"execute\":\"quit\"}\r\n".getBytes(StandardCharsets.UTF_8));
                output.flush();
                runOnUiThread(() -> info("QMP quit submitted."));
            } catch (Exception e) { runOnUiThread(() -> info("Cannot reach VM QMP: " + e.getMessage())); }
        }, "qmp-stop").start();
    }

    private void runTermux(String script, String message) {
        PackageManager pm = getPackageManager();
        try { pm.getPackageInfo("com.termux", 0); }
        catch (PackageManager.NameNotFoundException e) { info("Install the official Termux app first."); return; }
        if (checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{TERMUX_PERMISSION}, 100);
            info("Grant 'Run commands in Termux' permission, then tap the button again.");
            return;
        }
        Intent intent = new Intent("com.termux.RUN_COMMAND");
        intent.setClassName("com.termux", "com.termux.app.RunCommandService");
        intent.putExtra("com.termux.RUN_COMMAND_PATH", TERMUX_BASH);
        intent.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", new String[]{"-lc", script});
        intent.putExtra("com.termux.RUN_COMMAND_WORKDIR", TERMUX_HOME);
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", true);
        try { startService(intent); info(message); }
        catch (Exception e) { info("Termux rejected command: " + e.getMessage()); }
    }

    private void openTermux() {
        Intent open = getPackageManager().getLaunchIntentForPackage("com.termux");
        if (open != null) startActivity(open); else info("Termux not installed.");
    }

    private String quote(String value) { return "'" + value.replace("'", "'\\''") + "'"; }

    private int numeric(EditText input, int min, int max) {
        try { int v = Integer.parseInt(input.getText().toString().trim()); return v >= min && v <= max ? v : -1; }
        catch (Exception e) { return -1; }
    }

    private void info(String text) { if (status != null) status.setText(text); }

    private LinearLayout panel(LinearLayout parent) {
        LinearLayout p = new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(dp(17), dp(18), dp(17), dp(18));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(CARD); bg.setCornerRadius(dp(17));
        bg.setStroke(dp(1), 0xFF263957);
        p.setBackground(bg);
        parent.addView(p, new LinearLayout.LayoutParams(-1, -2));
        return p;
    }

    private EditText field(LinearLayout group, String title, String hint, String initial, boolean numeric) {
        group.addView(spacer(9));
        group.addView(label(title, 13, MUTED, false));
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextColor(TEXT);
        edit.setHintTextColor(0xFF6884A7);
        edit.setTextSize(15);
        edit.setHint(hint);
        edit.setText(initial);
        edit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        group.addView(edit, new LinearLayout.LayoutParams(-1, dp(48)));
        return edit;
    }

    private TextView label(String text, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(sp);
        t.setLineSpacing(dp(3), 1f);
        if (bold) t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return t;
    }

    private void action(LinearLayout group, String title, boolean primary, Runnable task) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(title);
        b.setTextSize(14);
        b.setTextColor(primary ? 0xFF081322 : TEXT);
        b.setBackgroundTintList(android.content.res.ColorStateList.valueOf(primary ? 0xFF60A5FA : 0xFF30445F));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(47));
        lp.topMargin = dp(9);
        group.addView(b, lp);
        b.setOnClickListener(v -> task.run());
    }

    private View spacer(int h) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(h)));
        return v;
    }

    private int dp(int dp) { return (int)(dp * getResources().getDisplayMetrics().density + .5f); }
}
