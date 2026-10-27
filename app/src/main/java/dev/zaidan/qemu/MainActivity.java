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
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.EditText;
import android.widget.ScrollView;
import android.text.InputType;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;

public class MainActivity extends Activity {
    private static final int ISO = 501, KERNEL = 502, DTB = 503;
    private static final int BG = 0xFF0E1014, PANEL = 0xFF1B1E25, LINE = 0xFF363B45;
    private static final int FG = 0xFFEAF0F8, SECONDARY = 0xFFADB4C0;
    private SharedPreferences prefs;
    private LinearLayout root;
    private TextView engineText, mediaText, machineText, feedback;

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
        LinearLayout header = row();
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView logo = new ImageView(this);
        logo.setImageResource(R.drawable.qemu_brand);
        header.addView(logo, new LinearLayout.LayoutParams(dp(42), dp(42)));
        TextView title = text("Qemu Application", 19, FG, true);
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, -2, 1);
        tp.leftMargin = dp(12);
        header.addView(title, tp);
        TextView dots = text("⋮", 27, FG, false);
        dots.setGravity(Gravity.CENTER);
        header.addView(dots, new LinearLayout.LayoutParams(dp(48), dp(48)));
        dots.setOnClickListener(v -> showMenu(dots));
        root.addView(header);
        root.addView(gap(26));

        TextView section = text("Machines", 25, FG, true);
        root.addView(section);
        root.addView(gap(15));

        LinearLayout card = column();
        card.setPadding(dp(17), dp(19), dp(17), dp(17));
        card.setBackground(shape(PANEL, LINE, 14));
        TextView name = text("Local VM", 18, FG, true);
        card.addView(name);
        machineText = text("x86_64 · 1024 MB · 2 cores", 13, SECONDARY, false);
        card.addView(machineText);
        card.addView(gap(9));
        mediaText = text("No boot image selected", 13, SECONDARY, false);
        card.addView(mediaText);
        card.addView(gap(12));
        TextView hint = text("Tap to boot in device preview  ·  ⋮ Configure", 12, 0xFF83AACF, false);
        card.addView(hint);
        root.addView(card);
        card.setOnClickListener(v -> launchFromCard());

        root.addView(gap(25));
        root.addView(text("ENGINE", 11, 0xFF8993A2, true));
        root.addView(gap(9));
        engineText = text("", 13, SECONDARY, false);
        root.addView(engineText);
        root.addView(gap(17));
        feedback = text("No Termux, shell, or external setup required by the app UI.", 12, SECONDARY, false);
        root.addView(feedback);
        root.addView(gap(17));
        TextView open = text("Open device preview   ›", 14, FG, true);
        open.setPadding(dp(2), dp(13), dp(2), dp(13));
        root.addView(open);
        open.setOnClickListener(v -> {
            startActivity(new Intent(this, ViewerActivity.class));
        });
        refresh();
    }

    private void refresh() {
        if (engineText == null) return;
        boolean engine = NativeEngine.isAvailable(this, "x86_64") ||
                         NativeEngine.isAvailable(this, "aarch64");
        boolean running = NativeVmService.isRunning();
        engineText.setText(running ? "● VM process active" : engine ?
                "Native QEMU executable bundled (dependencies not verified)" :
                "Native QEMU runtime not bundled yet");
        engineText.setTextColor(running ? 0xFF9CD8AE : engine ? FG : 0xFFFFBA88);
        if (machineText != null) {
            String arch = prefs.getString("arch", "x86_64");
            machineText.setText(arch + " · " + prefs.getInt("ram", 1024) + " MB · " +
                    prefs.getInt("cores", 2) + " cores");
        }
        if (mediaText != null) {
            mediaText.setText(prefs.getString("isoName", "No boot image selected"));
        }
    }

    private void showMenu(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        String[] labels = {"Choose ISO", "Choose custom kernel", "Choose DTB",
                "Architecture", "RAM", "CPU cores", "Disk capacity",
                "Create virtual disk", "Open device preview", "Stop VM", "Native engine status", "View QEMU log"};
        for (int i = 0; i < labels.length; i++) menu.getMenu().add(0, i, i, labels[i]);
        menu.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 0: chooseFile(ISO); break;
                case 1: chooseFile(KERNEL); break;
                case 2: chooseFile(DTB); break;
                case 3: chooseArchitecture(); break;
                case 4: numberDialog("RAM (MB)", "ram", 1024, 256, 8192); break;
                case 5: numberDialog("CPU cores", "cores", 2, 1, 8); break;
                case 6: numberDialog("Disk capacity (GB)", "diskGb", 8, 1, 128); break;
                case 7: createDisk(); break;
                case 8: startActivity(new Intent(this, ViewerActivity.class)); break;
                case 9: NativeVmService.stop(this); break;
                case 10: engineDialog(); break;
                case 11: showLog(); break;
                default: break;
            }
            return true;
        });
        menu.show();
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
        if (requestCode != ISO && requestCode != KERNEL && requestCode != DTB) return;
        Uri source = data.getData();
        String kind = requestCode == ISO ? "iso" : requestCode == KERNEL ? "kernel" : "dtb";
        String sourceName = "selected file";
        try (Cursor cursor = getContentResolver().query(source,
                new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst())
                sourceName = cursor.getString(0);
        } catch (Exception ignored) {}
        if (requestCode == ISO && !sourceName.toLowerCase(java.util.Locale.ROOT).endsWith(".iso")) {
            message("Select a .iso installer image.");
            return;
        }
        final String chosenName = sourceName;
        feedback.setText("Importing " + chosenName + "…");
        new Thread(() -> {
            try {
                File dir = new File(getFilesDir(), "media");
                if (!dir.exists() && !dir.mkdirs()) throw new IOException("Cannot create media directory");
                File pending = new File(dir, kind + ".partial");
                File target = new File(dir, kind + ".img");
                try (InputStream input = getContentResolver().openInputStream(source);
                     FileOutputStream output = new FileOutputStream(pending)) {
                    if (input == null) throw new IOException("Selected file cannot be read");
                    byte[] chunk = new byte[256 * 1024];
                    int size;
                    while ((size = input.read(chunk)) != -1) output.write(chunk, 0, size);
                    output.getFD().sync();
                }
                if (target.exists() && !target.delete()) throw new IOException("Could not replace old image");
                if (!pending.renameTo(target)) throw new IOException("Could not finalize imported image");
                prefs.edit().putString(kind + "Path", target.getAbsolutePath())
                        .putString(kind + "Name", chosenName).apply();
                runOnUiThread(() -> {
                    feedback.setText(chosenName + " imported to private app storage");
                    refresh();
                });
            } catch (Exception e) {
                String error = e.getMessage();
                runOnUiThread(() -> feedback.setText("Import failed: " + error));
            }
        }, "native-import").start();
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
        if (!disk().exists()) {
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
