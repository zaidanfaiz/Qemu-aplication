package dev.zaidan.qemu;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.net.Uri;
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
    private boolean startPending;
    private static final String FIRST_ACCESS_COMMAND =
      "mkdir -p ~/.termux; printf '\\nallow-external-apps = true\\n' >> ~/.termux/termux.properties; termux-reload-settings";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        preferences = getSharedPreferences("vm", MODE_PRIVATE);
        buildUi();
    }

    private TextView isoFile, vmSummary;
    private View advancedPanel, isoPickerRow;
    private Button browseButton, startButton;
    private boolean importInProgress;
    private android.net.Uri pendingLegacyIso;
    private static final int REQUEST_ISO = 201, REQUEST_LEGACY_STORAGE = 202;

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(0xFF101217);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(22), dp(20), dp(35));
        scroll.addView(root);
        setContentView(scroll);

        iso = new EditText(this);
        iso.setText(preferences.getString("iso", ""));

        LinearLayout header = horizontal();
        android.widget.ImageView icon = new android.widget.ImageView(this);
        icon.setImageResource(R.drawable.qemu_brand);
        icon.setScaleType(android.widget.ImageView.ScaleType.CENTER_CROP);
        header.addView(icon, new LinearLayout.LayoutParams(dp(42), dp(42)));
        LinearLayout brand = vertical();
        brand.setPadding(dp(11), dp(2), 0, 0);
        brand.addView(label("Qemu Application", 18, 0xFFE9ECF2, true));
        brand.addView(label("LOCAL VIRTUAL MACHINE", 10, 0xFF8E949E, false));
        header.addView(brand, new LinearLayout.LayoutParams(0, -2, 1));
        TextView version = label("0.3 DEV", 11, 0xFF8995A6, false);
        version.setGravity(Gravity.CENTER_VERTICAL);
        header.addView(version);
        root.addView(header);
        root.addView(spacer(23));

        vmSummary = label("●  Checking VM status…", 13, 0xFFA6ADB9, false);
        root.addView(vmSummary);
        root.addView(spacer(15));
        divider(root);
        root.addView(spacer(21));

        root.addView(label("Virtual machine", 24, 0xFFF1F3F6, true));
        TextView subtitle = label("Choose a boot image and configure the guest.", 13, 0xFF9299A5, false);
        subtitle.setPadding(0, dp(5), 0, dp(23));
        root.addView(subtitle);

        section(root, "BOOT MEDIA");
        LinearLayout mediaRow = horizontal();
        mediaRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout mediaCopy = vertical();
        mediaCopy.addView(label("Installer image", 15, 0xFFE8EDF2, true));
        isoFile = label("No ISO selected • boots from virtual disk", 12, 0xFF949CA8, false);
        isoFile.setMaxLines(2);
        isoFile.setEllipsize(android.text.TextUtils.TruncateAt.END);
        mediaCopy.addView(isoFile);
        mediaRow.addView(mediaCopy, new LinearLayout.LayoutParams(0, -2, 1));
        browseButton = flatButton("Browse", false, this::pickIso);
        LinearLayout.LayoutParams browseParams = new LinearLayout.LayoutParams(dp(82), dp(43));
        browseParams.leftMargin = dp(10);
        mediaRow.addView(browseButton, browseParams);
        Button eject = flatButton("Clear", false, () -> {
            if (importInProgress) return;
            iso.setText("");
            preferences.edit().remove("iso").remove("isoName").apply();
            updateIsoLabel();
            info("Boot ISO cleared; VM will boot from its disk.");
        });
        LinearLayout.LayoutParams ejectParams = new LinearLayout.LayoutParams(dp(63), dp(43));
        ejectParams.leftMargin = dp(6);
        mediaRow.addView(eject, ejectParams);
        isoPickerRow = mediaRow;
        root.addView(mediaRow);
        root.addView(spacer(12));

        section(root, "ARCHITECTURE");
        cpu = new Spinner(this);
        String[] arch = {"x86_64", "i386", "aarch64 (kernel)"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
            android.R.layout.simple_spinner_item, arch);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        cpu.setAdapter(adapter);
        cpu.setSelection(Math.min(2, Math.max(0, preferences.getInt("arch", 0))));
        cpu.setBackground(surface(0xFF1B1F27, 0xFF363B45, 10));
        cpu.setPadding(dp(11), 0, dp(11), 0);
        root.addView(cpu, new LinearLayout.LayoutParams(-1, dp(48)));
        root.addView(spacer(22));

        section(root, "RESOURCES");
        LinearLayout resources = horizontal();
        resources.setGravity(Gravity.TOP);
        LinearLayout ramCell = vertical(), cpuCell = vertical(), diskCell = vertical();
        resources.addView(ramCell, weightCell());
        resources.addView(cpuCell, weightCell());
        resources.addView(diskCell, weightCell());
        ram = field(ramCell, "RAM · MB", "1024", preferences.getString("ram", "1024"), true);
        cores = field(cpuCell, "CPU", "2", preferences.getString("cores", "2"), true);
        diskSize = field(diskCell, "Disk · GB", "8", preferences.getString("disk", "8"), true);
        root.addView(resources);
        root.addView(spacer(8));
        root.addView(label("The disk is persistent. Creating it will not overwrite an existing image.", 11, 0xFF8D939D, false));
        root.addView(spacer(9));

        Button createDisk = flatButton("Create virtual disk", false, this::createDisk);
        root.addView(createDisk, new LinearLayout.LayoutParams(-1, dp(45)));
        root.addView(spacer(22));

        startButton = flatButton("Start virtual machine", true, this::startVm);
        root.addView(startButton, new LinearLayout.LayoutParams(-1, dp(52)));
        root.addView(spacer(8));

        LinearLayout actions = horizontal();
        Button display = flatButton("Open display", false,
            () -> startActivity(new Intent(this, ViewerActivity.class)));
        Button stop = flatButton("Stop VM", false, this::stopVm);
        LinearLayout.LayoutParams p1 = weightCell();
        p1.rightMargin = dp(8);
        actions.addView(display, p1);
        actions.addView(stop, weightCell());
        root.addView(actions);
        root.addView(spacer(26));
        divider(root);
        root.addView(spacer(19));
        section(root, "ENVIRONMENT");
        LinearLayout tools = horizontal();
        Button setup = flatButton("Setup QEMU", false, this::setupEnvironment);
        Button check = flatButton("Check status", false, this::checkStatus);
        LinearLayout.LayoutParams toolsP = weightCell();
        toolsP.rightMargin = dp(8);
        tools.addView(setup, toolsP);
        tools.addView(check, weightCell());
        root.addView(tools);

        root.addView(spacer(16));
        Button advanced = flatButton("Advanced ARM64 options   ▾", false, () -> {
            boolean show = advancedPanel.getVisibility() != View.VISIBLE;
            advancedPanel.setVisibility(show ? View.VISIBLE : View.GONE);
        });
        root.addView(advanced, new LinearLayout.LayoutParams(-1, dp(43)));

        LinearLayout extras = vertical();
        extras.setPadding(0, dp(10), 0, dp(5));
        kernel = field(extras, "Kernel image", "/sdcard/Download/Image", preferences.getString("kernel", ""), false);
        initrd = field(extras, "Initrd · optional", "/sdcard/Download/initramfs.cpio.gz", preferences.getString("initrd", ""), false);
        dtb = field(extras, "Device tree · optional", "/sdcard/Download/virt.dtb", preferences.getString("dtb", ""), false);
        kernelArgs = field(extras, "Kernel arguments", "console=ttyAMA0", preferences.getString("kargs", "console=ttyAMA0"), false);
        advancedPanel = extras;
        extras.setVisibility(cpu.getSelectedItemPosition() == 2 ? View.VISIBLE : View.GONE);
        root.addView(extras);

        root.addView(spacer(16));
        status = label("Ready", 12, 0xFFAAB1BB, false);
        root.addView(status);

        cpu.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(android.widget.AdapterView<?> view) {}
            @Override public void onItemSelected(android.widget.AdapterView<?> view, View target, int position, long id) {
                boolean arm = position == 2;
                isoPickerRow.setAlpha(arm ? 0.48f : 1f);
                browseButton.setEnabled(!arm && !importInProgress);
                if (arm) advancedPanel.setVisibility(View.VISIBLE);
            }
        });
        updateIsoLabel();
        checkStatus();
    }

    private LinearLayout vertical() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout horizontal() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        return l;
    }

    private LinearLayout.LayoutParams weightCell() {
        return new LinearLayout.LayoutParams(0, -2, 1);
    }

    private void section(LinearLayout parent, String name) {
        TextView t = label(name, 11, 0xFFA8B0BC, true);
        t.setLetterSpacing(.09f);
        t.setPadding(0, 0, 0, dp(9));
        parent.addView(t);
    }

    private void divider(LinearLayout parent) {
        View line = new View(this);
        line.setBackgroundColor(0xFF2C3038);
        parent.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
    }

    private android.graphics.drawable.GradientDrawable surface(int fill, int stroke, int corner) {
        android.graphics.drawable.GradientDrawable shape = new android.graphics.drawable.GradientDrawable();
        shape.setColor(fill);
        shape.setStroke(dp(1), stroke);
        shape.setCornerRadius(dp(corner));
        return shape;
    }

    private Button flatButton(String title, boolean primary, Runnable tap) {
        Button b = new Button(this);
        b.setText(title);
        b.setAllCaps(false);
        b.setTextSize(13);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? 0xFF11171D : 0xFFE0E4EA);
        b.setPadding(dp(10), 0, dp(10), 0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setBackground(surface(primary ? 0xFFBCC9E6 : 0xFF242831,
                               primary ? 0xFFBCC9E6 : 0xFF383E48, 10));
        b.setOnClickListener(v -> tap.run());
        return b;
    }

    private void updateIsoLabel() {
        if (isoFile == null) return;
        String chosen = preferences.getString("isoName", "");
        String path = iso == null ? "" : iso.getText().toString().trim();
        isoFile.setText(path.isEmpty() ? "No ISO selected • boot from disk"
            : (chosen.isEmpty() ? "Boot ISO selected" : chosen));
    }

    private void pickIso() {
        if (importInProgress) return;
        if (cpu != null && cpu.getSelectedItemPosition() == 2) {
            info("ISO boot is available on x86/i386 profiles; ARM64 uses direct-kernel mode.");
            return;
        }
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQUEST_ISO);
        } catch (android.content.ActivityNotFoundException e) {
            info("No Android file manager available for choosing an ISO.");
        }
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_ISO || resultCode != RESULT_OK || data == null ||
                data.getData() == null) return;
        android.net.Uri chosen = data.getData();
        if (android.os.Build.VERSION.SDK_INT < 29 &&
                checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    != PackageManager.PERMISSION_GRANTED) {
            pendingLegacyIso = chosen;
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE},
                    REQUEST_LEGACY_STORAGE);
            return;
        }
        importIso(chosen);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(requestCode, permissions, grants);
        if (requestCode == REQUEST_LEGACY_STORAGE) {
            if (grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED &&
                    pendingLegacyIso != null) {
                android.net.Uri next = pendingLegacyIso;
                pendingLegacyIso = null;
                importIso(next);
            } else {
                pendingLegacyIso = null;
                info("Storage write permission is needed on Android 8–9 to import an ISO.");
            }
        }
    }

    private void importIso(android.net.Uri uri) {
        if (importInProgress) return;
        importInProgress = true;
        browseButton.setEnabled(false);
        startButton.setEnabled(false);
        isoFile.setText("Importing ISO into Downloads…");
        info("Copying ISO. Large images may take some time.");
        IsoStorage.importDocument(this, uri, new IsoStorage.Listener() {
            @Override public void onProgress(long bytes, long total) {
                int percent = total > 0 ? (int) Math.min(100, bytes * 100 / total) : -1;
                isoFile.setText(percent >= 0 ? "Importing ISO · " + percent + "%" :
                        "Importing ISO · " + (bytes / 1048576L) + " MB");
            }
            @Override public void onReady(String path, String name) {
                importInProgress = false;
                browseButton.setEnabled(cpu.getSelectedItemPosition() != 2);
                startButton.setEnabled(true);
                iso.setText(path);
                preferences.edit().putString("iso", path).putString("isoName", name).apply();
                updateIsoLabel();
                info("ISO imported. Storage path: " + path +
                        ". Termux must have storage permission to boot it.");
            }
            @Override public void onError(String error) {
                importInProgress = false;
                browseButton.setEnabled(cpu.getSelectedItemPosition() != 2);
                startButton.setEnabled(true);
                updateIsoLabel();
                info("ISO import failed: " + error);
            }
        });
    }

    private boolean termuxInstalled() {
        try { getPackageManager().getPackageInfo("com.termux", 0); return true; }
        catch (PackageManager.NameNotFoundException e) { return false; }
    }

    private void setupEnvironment() {
        if (!termuxInstalled()) {
            new AlertDialog.Builder(this).setTitle("Termux required")
              .setMessage("Install official Termux from GitHub or F-Droid and open it once.")
              .setPositiveButton("Get Termux", (dialog, which) -> startActivity(
                  new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/termux/termux-app/releases"))))
              .setNegativeButton("Cancel", null).show();
            return;
        }
        if (!preferences.getBoolean("first_auth_ack", false)) {
            new AlertDialog.Builder(this).setTitle("One-time Termux permission")
              .setMessage("Android blocks other apps from modifying Termux settings. Paste the command copied to clipboard in Termux, then return and tap Setup again. Grant 'Run commands in Termux environment' under Android App info > Permissions > Additional permissions. This initial authorization cannot be bypassed.")
              .setPositiveButton("Copy command & open", (dialog, which) -> {
                  ClipboardManager clip = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                  clip.setPrimaryClip(ClipData.newPlainText("Termux setup permission", FIRST_ACCESS_COMMAND));
                  preferences.edit().putBoolean("first_auth_ack", true).apply();
                  info("Command copied. Run it in Termux and return to Setup.");
                  openTermux();
              })
              .setNegativeButton("Cancel", null).show();
            return;
        }
        String script = "set -euo pipefail; DIR=\"$HOME/qemu-aplication\"; mkdir -p \"$DIR\"; rm -f \"$DIR/setup.status\"; { echo \"[1/3] Updating Termux package index\"; pkg update -y; echo \"[2/3] Installing QEMU packages\"; pkg install -y qemu-system-x86-64-headless qemu-system-i386-headless qemu-system-aarch64-headless qemu-utils; echo \"[3/3] Configuring storage\"; termux-setup-storage || true; command -v qemu-img; command -v qemu-system-x86_64; command -v qemu-system-i386; command -v qemu-system-aarch64; printf \"READY\\\\n\" > \"$DIR/setup.status\"; echo \"Setup complete\"; } 2>&1 | tee \"$DIR/setup.log\"";
        if (checkSelfPermission(TERMUX_PERMISSION) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{TERMUX_PERMISSION}, 100);
            info("Grant Run commands in Termux environment, then tap Setup again.");
            return;
        }
        runTermux(script, "Setup requested in Termux. Watch the terminal; setup.status appears after package verification.", false);
        openTermux();
    }

    private void checkStatus() {
        info("Checking QMP and VNC...");
        new Thread(() -> {
            boolean qmp = isListening(4444), vnc = isListening(5901);
            runOnUiThread(() -> {
                if (vmSummary != null) {
                    vmSummary.setText(qmp && vnc ? "●  VM running · display ready" :
                            (qmp || vnc) ? "●  VM starting / partially available" : "●  VM offline");
                    vmSummary.setTextColor(qmp && vnc ? 0xFFA8D7C1 : 0xFFA6ADB9);
                }
                info(qmp && vnc ? "VM reachable: QMP + VNC active."
                   : qmp ? "QMP online, VNC not ready."
                   : vnc ? "VNC online, QMP not ready."
                   : "VM offline. Check run.log in Termux if a start was requested.");
            });
        }, "vm-status").start();
    }

    private boolean isListening(int port) {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 1200);
            return true;
        } catch (Exception e) { return false; }
    }

    private String launchGuard() {
        return "set -eu; DIR=\"$HOME/qemu-aplication\"; mkdir -p \"$DIR\"; if [ ! -f \"$DIR/setup.status\" ]; then echo 'Run Setup first' > \"$DIR/run.log\"; exit 1; fi; PID=\"$DIR/qemu.pid\"; if [ -r \"$PID\" ]; then old=$(cat \"$PID\" 2>/dev/null || true); if [ -n \"$old\" ] && kill -0 \"$old\" 2>/dev/null; then echo 'VM already running' > \"$DIR/run.log\"; exit 1; fi; rm -f \"$PID\"; fi; ";
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
        if (importInProgress) { info("Finish importing the ISO before starting QEMU."); return; }
        if (startPending) { info("VM start already requested; check its status."); return; }
        startPending = true;
        new android.os.Handler(getMainLooper()).postDelayed(() -> startPending = false, 4000);
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
        String command = launchGuard() +
          "if ! command -v " + binary + " >/dev/null; then echo 'QEMU package missing' > \"$HOME/qemu-aplication/run.log\"; exit 127; fi; " +
          "if [ ! -f \"$HOME/qemu-aplication/disk.qcow2\" ]; then echo 'Create disk first' > \"$HOME/qemu-aplication/run.log\"; exit 1; fi; " +
          (bootIso.isEmpty() ? "" : "if [ ! -r " + quote(bootIso) + " ]; then echo 'ISO cannot be read' > \"$DIR/run.log\"; exit 1; fi; ") +
          "echo $ > \"$PID\"; exec " + binary + " -machine pc -accel tcg,thread=multi -cpu max -smp " + count +
          " -m " + memory + " -boot order=" + boot +
          " -drive file=\"$HOME/qemu-aplication/disk.qcow2\",if=ide,format=qcow2" + optIso +
          " -usb -device usb-tablet -vga std" +
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
        String script = launchGuard() +
          "if ! command -v qemu-system-aarch64 >/dev/null; then echo 'Install qemu-system-aarch64-headless' > \"$HOME/qemu-aplication/run.log\"; exit 127; fi; " +
          "if [ ! -f \"$HOME/qemu-aplication/disk.qcow2\" ]; then echo 'Create disk first' > \"$HOME/qemu-aplication/run.log\"; exit 1; fi; " +
          "if [ ! -r " + quote(k) + " ]; then echo 'ARM64 kernel cannot be read' > \"$DIR/run.log\"; exit 1; fi; " +
          (r.isEmpty() ? "" : "if [ ! -r " + quote(r) + " ]; then echo 'initrd cannot be read' > \"$DIR/run.log\"; exit 1; fi; ") +
          (d.isEmpty() ? "" : "if [ ! -r " + quote(d) + " ]; then echo 'DTB cannot be read' > \"$DIR/run.log\"; exit 1; fi; ") +
          "echo $ > \"$PID\"; exec qemu-system-aarch64 -machine virt,accel=tcg -cpu cortex-a72" +
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
            } catch (Exception e) {
                runOnUiThread(() -> {
                    info("QMP unavailable; asking Termux to stop the recorded QEMU process.");
                    runTermux("PID=\"$HOME/qemu-aplication/qemu.pid\"; if [ -r \"$PID\" ]; then p=\"$(cat \"$PID\" 2>/dev/null || true)\"; case \"$p\" in ''|*[!0-9]*) exit 0;; esac; if [ -r \"/proc/$p/cmdline\" ] && tr '\\\\0' ' ' < \"/proc/$p/cmdline\" | grep -q 'qemu-system-'; then kill -TERM \"$p\" || true; fi; fi", "Termux PID stop fallback requested.");
                });
            }
        }, "qmp-stop").start();
    }

    private void runTermux(String script, String message) {
        runTermux(script, message, true);
    }

    private void runTermux(String script, String message, boolean background) {
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
        intent.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
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
        TextView caption = label(title, 12, 0xFFAAB2BD, false);
        caption.setPadding(0, dp(2), 0, dp(7));
        group.addView(caption);
        EditText edit = new EditText(this);
        edit.setSingleLine(true);
        edit.setTextColor(0xFFF1F3F6);
        edit.setHintTextColor(0xFF838B98);
        edit.setTextSize(14);
        edit.setHint(hint);
        edit.setText(initial);
        edit.setSelectAllOnFocus(false);
        edit.setPadding(dp(11), 0, dp(10), 0);
        edit.setBackground(surface(0xFF1B1F27, 0xFF363B45, 9));
        edit.setInputType(numeric ? InputType.TYPE_CLASS_NUMBER : InputType.TYPE_CLASS_TEXT);
        group.addView(edit, new LinearLayout.LayoutParams(-1, dp(46)));
        group.addView(spacer(8));
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
