package dev.zaidan.qemu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.StatFs;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.text.InputType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

public class MainActivity extends Activity {
    private static final int ISO = 501, KERNEL = 502, DTB = 503, ROM = 504;
    private volatile boolean importing;
    private static final int BG = 0xFF0E1014, PANEL = 0xFF1B1E25, LINE = 0xFF363B45;
    private static final int FG = 0xFFEAF0F8, SECONDARY = 0xFFADB4C0;
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView engineText, mediaText, machineText, feedback, modePhone, modeComputer;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        prefs = getSharedPreferences("native_vm", MODE_PRIVATE);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(24), dp(20), dp(32));
        root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(dp(20), insets.getSystemWindowInsetTop() + dp(22),
                    dp(20), insets.getSystemWindowInsetBottom() + dp(22));
            return insets;
        });
        setContentView(root);
        showHome();
    }

    @Override protected void onResume() {
        super.onResume();
        refresh();
    }

    private void showHome() {
        root.removeAllViews();
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, -1));
        LinearLayout page = column();
        page.setPadding(0, dp(4), 0, dp(25));
        scroll.addView(page);

        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.qemu_brand);
        header.addView(logo, new LinearLayout.LayoutParams(dp(43), dp(43)));
        LinearLayout brand = column();
        LinearLayout.LayoutParams titleBox = new LinearLayout.LayoutParams(0, -2, 1);
        titleBox.leftMargin = dp(12);
        header.addView(brand, titleBox);
        brand.addView(text("Qemu", 20, FG, true));
        brand.addView(text("LOCAL VIRTUAL MACHINE", 10, SECONDARY, false));
        TextView more = chip("⋯", false);
        more.setTextSize(22);
        header.addView(more, new LinearLayout.LayoutParams(dp(48), dp(43)));
        more.setOnClickListener(v -> showMenu());
        page.addView(header);
        page.addView(gap(29));

        page.addView(text("Your machine", 25, FG, true));
        page.addView(gap(12));
        LinearLayout card = column();
        card.setPadding(dp(19), dp(18), dp(19), dp(19));
        card.setBackground(shape(PANEL, LINE, 20));
        page.addView(card);
        LinearLayout vmHeading = row();
        vmHeading.setGravity(Gravity.CENTER_VERTICAL);
        TextView vmTitle = text("Local VM", 19, FG, true);
        vmHeading.addView(vmTitle, new LinearLayout.LayoutParams(0, -2, 1));
        TextView config = chip("Edit", false);
        vmHeading.addView(config, new LinearLayout.LayoutParams(dp(72), dp(38)));
        config.setOnClickListener(v -> showHardwareMenu());
        card.addView(vmHeading);
        card.addView(gap(8));
        machineText = text("", 13, SECONDARY, false);
        card.addView(machineText);
        card.addView(gap(8));
        mediaText = text("", 13, FG, false);
        mediaText.setMaxLines(2);
        mediaText.setEllipsize(android.text.TextUtils.TruncateAt.END);
        card.addView(mediaText);
        card.addView(gap(23));
        card.addView(text("CONTROL MODE", 11, SECONDARY, true));
        card.addView(gap(10));

        LinearLayout modes = row();
        modePhone = chip("◉  Phone · Touch", false);
        modeComputer = chip("⌨  Computer", false);
        LinearLayout.LayoutParams p1 = new LinearLayout.LayoutParams(0, dp(48), 1);
        p1.rightMargin = dp(9);
        modes.addView(modePhone, p1);
        modes.addView(modeComputer, new LinearLayout.LayoutParams(0, dp(48), 1));
        card.addView(modes);
        modePhone.setOnClickListener(v -> saveControlMode("phone"));
        modeComputer.setOnClickListener(v -> saveControlMode("computer"));
        card.addView(gap(17));
        TextView start = chip("Start VM   →", true);
        start.setTextSize(15);
        card.addView(start, new LinearLayout.LayoutParams(-1, dp(50)));
        start.setOnClickListener(v -> launchFromCard());

        page.addView(gap(17));
        LinearLayout actions = row();
        TextView media = chip("＋  Media", false);
        TextView settings = chip("⚙  Settings", false);
        TextView logs = chip("≡  Logs", false);
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(0, dp(46), 1);
        ap.rightMargin = dp(7);
        actions.addView(media, ap);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(46), 1);
        sp.rightMargin = dp(7);
        actions.addView(settings, sp);
        actions.addView(logs, new LinearLayout.LayoutParams(0, dp(46), 1));
        media.setOnClickListener(v -> showMediaMenu());
        settings.setOnClickListener(v -> showHardwareMenu());
        logs.setOnClickListener(v -> showLog());
        page.addView(actions);

        page.addView(gap(22));
        engineText = text("", 12, SECONDARY, false);
        page.addView(engineText);
        page.addView(gap(6));
        feedback = text("", 12, SECONDARY, false);
        feedback.setMaxLines(3);
        page.addView(feedback);
        refresh();
    }

    private TextView chip(String label, boolean primary) {
        TextView v = text(label, 12, primary ? 0xFF101319 : FG, true);
        v.setGravity(Gravity.CENTER);
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        v.setBackground(shape(primary ? 0xFFB9CFF1 : 0xFF282E39,
                primary ? 0xFFB9CFF1 : LINE, 13));
        v.setClickable(true);
        v.setFocusable(true);
        return v;
    }

    private void saveControlMode(String mode) {
        prefs.edit().putString("interactionMode", mode).apply();
        refresh();
    }

    private void refresh() {
        if (engineText == null) return;
        boolean bundled = NativeEngine.isAvailable(this, "x86_64") ||
                NativeEngine.isAvailable(this, "aarch64");
        boolean active = NativeVmService.isRunning();
        engineText.setText(active ? "● Virtual machine running" :
                bundled ? "● QEMU runtime ready for testing" :
                "○ QEMU runtime not bundled");
        engineText.setTextColor(active ? 0xFF9EDBBB : SECONDARY);
        String arch = prefs.getString("arch", "x86_64");
        machineText.setText(arch + "   ·   " + prefs.getInt("ram", 1024) +
                " MB RAM   ·   " + prefs.getInt("cores", 2) + " CPU");
        String boot = prefs.getString("bootMode", "iso");
        String media = "rom".equals(boot) ? prefs.getString("romName", "No ROM selected") :
                "installed".equals(boot) ? "Installed disk · ISO ejected" :
                prefs.getString("isoName", "No ISO selected");
        mediaText.setText(("rom".equals(boot) ? "Disk: " : "Boot: ") + media);
        boolean computer = "computer".equals(prefs.getString("interactionMode", "phone"));
        styleMode(modePhone, !computer);
        styleMode(modeComputer, computer);
    }

    private void styleMode(TextView view, boolean selected) {
        view.setTextColor(selected ? 0xFF111419 : FG);
        view.setBackground(shape(selected ? 0xFFB9CFF1 : 0xFF282E39,
                selected ? 0xFFB9CFF1 : LINE, 13));
    }

    private void showMenu() {
        String[] options = {"Media & boot", "Virtual machine settings",
                "Display & input", "Diagnostics", "Stop VM"};
        new AlertDialog.Builder(this).setTitle("Machine options")
                .setItems(options, (dialog, selected) -> {
                    switch (selected) {
                        case 0: showMediaMenu(); break;
                        case 1: showHardwareMenu(); break;
                        case 2: showDisplayMenu(); break;
                        case 3: showDiagnosticMenu(); break;
                        case 4: NativeVmService.stop(this); break;
                    }
                }).show();
    }

    private void showMediaMenu() {
        String[] options = {"Choose installer ISO", "Import complete ROM disk",
                "Choose boot source", "Create virtual disk"};
        new AlertDialog.Builder(this).setTitle("Media & boot")
                .setItems(options, (dialog, selected) -> {
                    switch(selected) {
                        case 0: chooseFile(ISO); break;
                        case 1: chooseFile(ROM); break;
                        case 2: chooseBootSource(); break;
                        case 3: createDisk(); break;
                    }
                }).show();
    }

    private void showHardwareMenu() {
        String[] options = {"Architecture", "RAM", "CPU cores", "Disk capacity",
                "Advanced: custom kernel", "Advanced: device tree (DTB)"};
        new AlertDialog.Builder(this).setTitle("Virtual machine settings")
                .setItems(options, (dialog, selected) -> {
                    switch(selected) {
                        case 0: chooseArchitecture(); break;
                        case 1: numberDialog("RAM (MB)", "ram", 1024, 256, 8192); break;
                        case 2: numberDialog("CPU cores", "cores", 2, 1, 8); break;
                        case 3: numberDialog("Disk capacity (GB)", "diskGb", 8, 1, 128); break;
                        case 4: chooseFile(KERNEL); break;
                        case 5: chooseFile(DTB); break;
                    }
                }).show();
    }

    private void showDisplayMenu() {
        String[] modes = {"Phone · Touchscreen only", "Computer · Keyboard + mouse"};
        boolean computer = "computer".equals(prefs.getString("interactionMode", "phone"));
        new AlertDialog.Builder(this).setTitle("Display & input")
                .setSingleChoiceItems(modes, computer ? 1 : 0, (dialog, index) -> {
                    saveControlMode(index == 1 ? "computer" : "phone");
                    dialog.dismiss();
                }).setNeutralButton("Open display", (dialog, index) ->
                    startActivity(new Intent(this, ViewerActivity.class)))
                .setNegativeButton("Cancel", null).show();
    }

    private void showDiagnosticMenu() {
        String[] options = {"QEMU runtime log", "Native engine status",
                "Open display", "Stop VM"};
        new AlertDialog.Builder(this).setTitle("Diagnostics")
                .setItems(options, (dialog, selected) -> {
                    switch (selected) {
                        case 0: showLog(); break;
                        case 1: engineDialog(); break;
                        case 2: startActivity(new Intent(this, ViewerActivity.class)); break;
                        case 3: NativeVmService.stop(this); break;
                    }
                }).show();
    }

    private void chooseArchitecture() {
        String[] choices = {"x86_64 (PC)", "aarch64 (virt, experimental)"};
        new AlertDialog.Builder(this).setTitle("Guest architecture")
                .setSingleChoiceItems(choices,
                        prefs.getString("arch", "x86_64").equals("aarch64") ? 1 : 0,
                        (dialog, which) -> {
                            prefs.edit().putString("arch", which == 0 ? "x86_64" : "aarch64").apply();
                            dialog.dismiss();
                            refresh();
                        }).setNegativeButton("Cancel", null).show();
    }

    private void chooseBootSource() {
        if (NativeVmService.isRunning() || importing) {
            message("Stop VM and finish importing before changing boot source.");
            return;
        }
        String[] choices = {"Install from ISO (uses machine.img)",
                "Boot installed VM disk (eject ISO)",
                "Boot imported ROM disk (separate image)"};
        String current = prefs.getString("bootMode", "iso");
        int selectedMode = "rom".equals(current) ? 2 : "installed".equals(current) ? 1 : 0;
        new AlertDialog.Builder(this).setTitle("Boot source")
                .setSingleChoiceItems(choices, selectedMode, (dialog, selected) -> {
                    if (selected == 2) {
                        File rom = new File(prefs.getString("romPath", ""));
                        if (!rom.isFile()) {
                            message("Import a full Android-x86 PC ROM disk first.");
                            return;
                        }
                        prefs.edit().putString("bootMode", "rom")
                                .putString("arch", "x86_64").apply();
                    } else {
                        if (selected == 1 && !disk().isFile()) {
                            message("Create a virtual disk and install the OS first.");
                            return;
                        }
                        prefs.edit().putString("bootMode", selected == 1 ? "installed" : "iso")
                                .putString("arch", "x86_64").apply();
                    }
                    dialog.dismiss();
                    refresh();
                }).setNegativeButton("Cancel", null).show();
    }

    private void numberDialog(String title, String key, int defaultValue, int min, int max) {
        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setText(String.valueOf(prefs.getInt(key, defaultValue)));
        new AlertDialog.Builder(this).setTitle(title)
                .setView(field).setNegativeButton("Cancel", null)
                .setPositiveButton("Save", (d, w) -> {
                    try {
                        int value = Integer.parseInt(field.getText().toString());
                        if (value < min || value > max) throw new IllegalArgumentException();
                        prefs.edit().putInt(key, value).apply();
                        refresh();
                    } catch (Exception ex) {
                        message("Allowed range: " + min + "–" + max);
                    }
                }).show();
    }

    private void chooseFile(int request) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(intent, request);
    }

    @Override @SuppressWarnings("deprecation")
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        if (requestCode != ISO && requestCode != KERNEL &&
                requestCode != DTB && requestCode != ROM) return;
        if (NativeVmService.isRunning()) {
            message("Stop the VM before changing its boot media.");
            return;
        }
        if (importing) {
            message("Another file is still importing.");
            return;
        }

        final Uri source = data.getData();
        String sourceName = "selected file";
        try (Cursor cursor = getContentResolver().query(source,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst())
                sourceName = cursor.getString(0);
        } catch (Exception ignored) {}
        if (sourceName == null || sourceName.trim().isEmpty()) sourceName = "selected file";
        final String displayName = sourceName.length() > 120 ?
                sourceName.substring(0, 120) : sourceName;
        final String lowerName = displayName.toLowerCase(java.util.Locale.ROOT);
        if (requestCode == ISO && !RomImageInspector.isIsoName(displayName)) {
            message("Select an ISO installer. For a full disk image, choose Import Android ROM.");
            return;
        }
        if (requestCode == ROM && (lowerName.endsWith(".zip")
                || lowerName.endsWith(".7z") || lowerName.endsWith(".tar")
                || lowerName.endsWith(".gz") || lowerName.endsWith(".bin"))) {
            new AlertDialog.Builder(this).setTitle("ROM package not bootable")
                    .setMessage("ZIP/OTA Android yang dibuat untuk HP tidak bisa langsung di-flash " +
                            "ke mesin PC QEMU. Pilih installer Android-x86 .iso atau full VM " +
                            "disk .img/.raw/.qcow2. File system.img/boot.img/GSI juga bukan satu disk bootable.")
                    .setPositiveButton("OK", null).show();
            return;
        }

        final boolean romMode = requestCode == ROM && !RomImageInspector.isIsoName(displayName);
        Runnable doImport = () -> importMedia(source, displayName, requestCode, romMode);
        if (romMode && !prefs.getString("romPath", "").isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Replace imported ROM?")
                    .setMessage("Replacing the imported VM ROM image may erase changes made " +
                            "inside that ROM. Your existing machine.img disk and original " +
                            "file in Downloads are preserved.")
                    .setNegativeButton("Cancel", null)
                    .setPositiveButton("Replace", (dialog, which) -> doImport.run()).show();
        } else {
            doImport.run();
        }
    }

    private void importMedia(Uri uri, String name, int requestCode, boolean romMode) {
        if (importing || NativeVmService.isRunning()) {
            message("Finish current VM/import first.");
            return;
        }
        importing = true;
        if (feedback != null) feedback.setText("Importing " + name + "…");
        new Thread(() -> {
            File pending = null;
            try {
                File dir = new File(getFilesDir(), "media");
                if (!dir.isDirectory() && !dir.mkdirs())
                    throw new IOException("Cannot create private media directory");
                String kind = romMode ? "rom" :
                        requestCode == KERNEL ? "kernel" :
                        requestCode == DTB ? "dtb" : "iso";
                pending = new File(dir, kind + ".partial");
                if (pending.exists() && !pending.delete())
                    throw new IOException("Cannot clear interrupted import");
                long totalBytes = 0;
                try (InputStream input = getContentResolver().openInputStream(uri);
                     FileOutputStream output = new FileOutputStream(pending)) {
                    if (input == null) throw new IOException("Selected file cannot be read");
                    byte[] chunk = new byte[256 * 1024];
                    int size;
                    while ((size = input.read(chunk)) != -1) {
                        if (size == 0) continue;
                        output.write(chunk, 0, size);
                        totalBytes += size;
                    }
                    output.getFD().sync();
                }

                String format = null;
                if (romMode || requestCode == ISO || requestCode == ROM)
                    format = RomImageInspector.inspect(pending, name);

                String filename = romMode ?
                        ("qcow2".equals(format) ? "rom.qcow2" : "rom.raw") : kind + ".img";
                File target = new File(dir, filename);
                if (target.exists() && !target.delete())
                    throw new IOException("Could not replace old media file");
                if (!pending.renameTo(target))
                    throw new IOException("Could not finalize imported file");

                SharedPreferences.Editor update = prefs.edit();
                if (romMode) {
                    update.putString("romPath", target.getAbsolutePath())
                            .putString("romName", name)
                            .putString("romFormat", format)
                            .putString("bootMode", "rom")
                            .putString("arch", "x86_64");
                } else {
                    update.putString(kind + "Path", target.getAbsolutePath())
                            .putString(kind + "Name", name);
                    if ("iso".equals(kind)) {
                        update.putString("bootMode", "iso").putString("arch", "x86_64");
                    }
                }
                update.apply();
                final String mode = romMode ? "ROM disk (bootable PC image)" :
                        (requestCode == ROM || requestCode == ISO) ? "ISO installer" : kind;
                final long imported = totalBytes;
                runOnUiThread(() -> {
                    if (feedback != null)
                        feedback.setText(mode + " imported: " + name + " (" +
                                (imported / (1024 * 1024)) + " MB)");
                    refresh();
                });
            } catch (Exception ex) {
                final String error = ex.getMessage() == null ? ex.toString() : ex.getMessage();
                runOnUiThread(() -> {
                    if (feedback != null) feedback.setText("Import failed: " + error);
                    message("Import failed: " + error);
                });
            } finally {
                if (pending != null && pending.exists()) pending.delete();
                importing = false;
            }
        }, "rom-media-import").start();
    }

    private File disk() {
        return new File(getFilesDir(), "machine.img");
    }

    private void createDisk() {
        if (disk().exists()) {
            new AlertDialog.Builder(this).setTitle("Disk already exists")
                    .setMessage("Existing guest data is preserved. Delete it manually only after backing it up.")
                    .setPositiveButton("OK", null).show();
            return;
        }
        int gb = prefs.getInt("diskGb", 8);
        new Thread(() -> {
            try {
                File target = disk();
                try (RandomAccessFile out = new RandomAccessFile(target, "rw")) {
                    out.setLength(gb * 1024L * 1024 * 1024);
                }
                runOnUiThread(() -> message("Sparse " + gb + " GB RAW disk created"));
            } catch (Exception e) {
                String error = e.getMessage();
                runOnUiThread(() -> message("Disk creation failed: " + error));
            }
        }, "disk-create").start();
    }

    private void launchFromCard() {
        String arch = prefs.getString("arch", "x86_64");
        if (!NativeEngine.isAvailable(this, arch)) {
            engineDialog();
            return;
        }
        if (importing) {
            message("Media import still running; finish importing before boot.");
            return;
        }
        boolean fromRom = "rom".equals(prefs.getString("bootMode", "iso"));
        if (fromRom) {
            File rom = new File(prefs.getString("romPath", ""));
            if (!"x86_64".equals(arch) || !rom.isFile()) {
                message("Select an imported Android-x86 PC ROM disk first.");
                return;
            }
        }
        if (!fromRom && !disk().exists()) {
            new AlertDialog.Builder(this).setTitle("No virtual disk")
                    .setMessage("Create the virtual disk before attempting to boot.")
                    .setPositiveButton("Create disk", (d, w) -> createDisk())
                    .setNegativeButton("Cancel", null).show();
            return;
        }
        // Open the device-style viewer immediately; it will retry local VNC
        // while native QEMU initializes and extracts bundled firmware.
        NativeVmService.start(this);
        startActivity(new Intent(this, ViewerActivity.class));
    }

    private void showLog() {
        File log = new File(getFilesDir(), "qemu.log");
        if (!log.isFile()) {
            new AlertDialog.Builder(this)
                .setTitle("QEMU runtime log")
                .setMessage("Belum ada log. Jalankan VM lebih dulu, kemudian buka kembali.")
                .setPositiveButton("Close", null).show();
            return;
        }
        try {
            String contents;
            // Show the most recent 128 KB; keep complete content for Copy/Share
            // if the log file is reasonably sized. A runaway linker warning
            // must not freeze the UI or exhaust Android memory.
            try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(log, "r")) {
                long from = Math.max(0, in.length() - 128 * 1024);
                in.seek(from);
                byte[] tail = new byte[(int)(in.length() - from)];
                in.readFully(tail);
                contents = (from == 0 ? "" : "[Log awal dipotong. Menampilkan 128 KB terakhir.]\n\n")
                        + new String(tail, java.nio.charset.StandardCharsets.UTF_8);
            }
            ScrollView logScroll = new ScrollView(this);
            logScroll.setFillViewport(false);
            TextView selectableLog = new TextView(this);
            selectableLog.setText(contents);
            selectableLog.setTextSize(12);
            selectableLog.setTextColor(0xFFE6EAF0);
            selectableLog.setTypeface(Typeface.MONOSPACE);
            selectableLog.setTextIsSelectable(true);
            selectableLog.setPadding(dp(18), dp(12), dp(18), dp(12));
            logScroll.addView(selectableLog);
            LinearLayout root = new LinearLayout(this);
            root.setOrientation(LinearLayout.VERTICAL);
            root.addView(logScroll, new LinearLayout.LayoutParams(-1, 0, 1));
            AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("QEMU runtime log")
                .setView(root)
                .setNeutralButton("Copy log", (d, w) -> {
                    android.content.ClipboardManager cb = (android.content.ClipboardManager)
                        getSystemService(CLIPBOARD_SERVICE);
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("QEMU log", contents));
                    Toast.makeText(this, "Log copied to clipboard", Toast.LENGTH_SHORT).show();
                })
                .setPositiveButton("Share", (d, w) -> {
                    Intent send = new Intent(Intent.ACTION_SEND);
                    send.setType("text/plain");
                    send.putExtra(Intent.EXTRA_TEXT, contents);
                    startActivity(Intent.createChooser(send, "Share QEMU log"));
                })
                .setNegativeButton("Close", null).create();
            dialog.setOnShowListener(v -> {
                android.view.WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
                lp.width = getResources().getDisplayMetrics().widthPixels - dp(32);
                lp.height = (int)(getResources().getDisplayMetrics().heightPixels * .76f);
                dialog.getWindow().setAttributes(lp);
            });
            dialog.show();
        } catch (Exception e) {
            message("Cannot open QEMU log: " + e.getMessage());
        }
    }

    private void engineDialog() {
        new AlertDialog.Builder(this).setTitle("Native QEMU engine")
          .setMessage("Native execution now uses Android ProcessBuilder for packaged AArch64 QEMU PIE executables (instead of calling nonexistent QEMU JNI exports). The stable/main APK still does not include the executable or its shared-library dependencies. An experimental staging workflow packages raw binaries, but it is NOT a working bootable emulator. You can read process errors via View QEMU log. Firmware/dependency relocation and device tests are still required.")
          .setPositiveButton("OK", null).show();
    }

    private void message(String message) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show();
        if (feedback != null) feedback.setText(message);
        refresh();
    }

    private LinearLayout row() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.HORIZONTAL);
        return result;
    }
    private LinearLayout column() {
        LinearLayout result = new LinearLayout(this);
        result.setOrientation(LinearLayout.VERTICAL);
        return result;
    }
    private View gap(int value) {
        View gap = new View(this);
        gap.setLayoutParams(new LinearLayout.LayoutParams(1, dp(value)));
        return gap;
    }
    private TextView text(String value, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }
    private GradientDrawable shape(int color, int stroke, int radius) {
        GradientDrawable result = new GradientDrawable();
        result.setColor(color);
        result.setCornerRadius(dp(radius));
        result.setStroke(dp(1), stroke);
        return result;
    }
    private int dp(int v) {
        return (int)(v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
