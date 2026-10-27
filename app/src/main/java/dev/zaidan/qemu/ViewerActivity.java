package dev.zaidan.qemu;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * Device-style local VNC viewer for the iOS-emulator research UI.
 *
 * The chrome is a device preview; it does not imply the guest is iOS.
 * RFB 3.3/3.8, RAW encoding and single-pointer input are unchanged.
 * The device display preserves the guest framebuffer aspect ratio.
 */
public class ViewerActivity extends Activity {
    private static final int BACKGROUND = 0xFF0C0D10;
    private static final int SURFACE = 0xFF17191E;
    private static final int FOREGROUND = 0xFFF2F2F4;
    private static final int SECONDARY = 0xFF8D919B;
    private static final float PHONE_RATIO = 0.476f;

    private final Object framebufferLock = new Object();
    private volatile RfbClient client;
    private Bitmap framebuffer;
    private ScreenView screen;
    private FrameLayout stage;
    private FrameLayout phone;
    private FrameLayout content;
    private LinearLayout emptyLayer;
    private TextView emptyHeading;
    private TextView emptySubtitle;
    private TextView status;
    private TextView footer;
    private View island;
    private View homeIndicator;
    private boolean deviceFrame = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        deviceFrame = getPreferences(MODE_PRIVATE).getBoolean("deviceFrame", true);
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.VERTICAL);
        page.setBackgroundColor(BACKGROUND);
        page.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(),
                    0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(page);

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(14), 0, dp(14), 0);
        page.addView(toolbar, new LinearLayout.LayoutParams(-1, dp(59)));

        TextView back = text("‹", 31, FOREGROUND, false);
        back.setContentDescription("Return to machines");
        back.setGravity(Gravity.CENTER);
        toolbar.addView(back, new LinearLayout.LayoutParams(dp(38), dp(48)));
        back.setOnClickListener(v -> finish());

        LinearLayout headings = new LinearLayout(this);
        headings.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams headingParams = new LinearLayout.LayoutParams(0, -2, 1);
        headingParams.leftMargin = dp(10);
        toolbar.addView(headings, headingParams);
        headings.addView(text("Device preview", 17, FOREGROUND, true));
        status = text("Connecting to local VM…", 11, SECONDARY, false);
        status.setMaxLines(1);
        status.setEllipsize(TextUtils.TruncateAt.END);
        headings.addView(status);

        TextView more = text("⋯", 28, FOREGROUND, true);
        more.setContentDescription("Simulator options: keyboard, reconnect, screen mode");
        more.setGravity(Gravity.CENTER);
        toolbar.addView(more, new LinearLayout.LayoutParams(dp(45), dp(45)));
        more.setOnClickListener(v -> openOptions(more));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(BACKGROUND);
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        phone = new FrameLayout(this);
        stage.addView(phone);
        // Remeasure on rotation and on keyboard appearance. Prevent a device
        // whose lower frame is offscreen on short phones.
        stage.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, oright, ob) -> {
            if (r - l != oright - ol || b - t != ob - ot) sizeDevice();
        });

        content = new FrameLayout(this);
        content.setBackground(round(0xFF000000, 35, 0, 0));
        content.setClipToOutline(true);
        FrameLayout.LayoutParams glass = new FrameLayout.LayoutParams(-1, -1);
        glass.setMargins(dp(6), dp(6), dp(6), dp(6));
        phone.addView(content, glass);

        screen = new ScreenView();
        content.addView(screen, new FrameLayout.LayoutParams(-1, -1));

        emptyLayer = new LinearLayout(this);
        emptyLayer.setOrientation(LinearLayout.VERTICAL);
        emptyLayer.setGravity(Gravity.CENTER);
        emptyLayer.setPadding(dp(20), dp(16), dp(20), dp(16));
        emptyLayer.setBackgroundColor(0xFF090A0D);
        content.addView(emptyLayer, new FrameLayout.LayoutParams(-1, -1));
        TextView emptyGlyph = text("◯", 28, 0xFF5E6370, false);
        emptyGlyph.setGravity(Gravity.CENTER);
        emptyLayer.addView(emptyGlyph);
        View gap = new View(this);
        emptyLayer.addView(gap, new LinearLayout.LayoutParams(1, dp(12)));
        emptyHeading = text("Waiting for display", 17, FOREGROUND, true);
        emptyHeading.setGravity(Gravity.CENTER);
        emptyLayer.addView(emptyHeading);
        emptySubtitle = text("Start a VM in Machines.\nThe display will appear here.", 12, SECONDARY, false);
        emptySubtitle.setPadding(0, dp(7), 0, 0);
        emptySubtitle.setGravity(Gravity.CENTER);
        emptyLayer.addView(emptySubtitle);

        island = new View(this);
        island.setBackground(round(0xFF000000, 18, 0, 0));
        FrameLayout.LayoutParams islandParams = new FrameLayout.LayoutParams(dp(108), dp(29),
                Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        islandParams.topMargin = dp(15);
        phone.addView(island, islandParams);
        island.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        homeIndicator = new View(this);
        homeIndicator.setBackground(round(0xFFCFD0D2, 6, 0, 0));
        FrameLayout.LayoutParams homeParams = new FrameLayout.LayoutParams(dp(108), dp(4),
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        homeParams.bottomMargin = dp(17);
        phone.addView(homeIndicator, homeParams);
        homeIndicator.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);

        footer = text("LOCAL DISPLAY  ·  SINGLE-POINTER INPUT", 10, SECONDARY, false);
        footer.setGravity(Gravity.CENTER);
        footer.setPadding(dp(12), 0, dp(12), dp(5));
        page.addView(footer, new LinearLayout.LayoutParams(-1, dp(33)));

        applyDeviceStyle();
        connect();
    }

    private void sizeDevice() {
        if (stage == null || stage.getWidth() == 0 || stage.getHeight() == 0) return;
        int availW = Math.max(dp(1), stage.getWidth() - dp(34));
        int availH = Math.max(dp(1), stage.getHeight() - dp(14));
        int width = deviceFrame ? Math.min(availW, Math.round(availH * PHONE_RATIO)) : availW;
        int height = deviceFrame ? Math.min(availH, Math.round(width / PHONE_RATIO)) : availH;
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height, Gravity.CENTER);
        phone.setLayoutParams(params);
    }

    private void applyDeviceStyle() {
        phone.setBackground(round(deviceFrame ? 0xFF272A31 : 0xFF000000,
                deviceFrame ? 44 : 5, deviceFrame ? 0xFF5A5B63 : 0, 1));
        phone.setClipToOutline(deviceFrame);
        content.setBackground(round(0xFF000000, deviceFrame ? 38 : 2, 0, 0));
        content.setClipToOutline(deviceFrame);
        FrameLayout.LayoutParams inner = (FrameLayout.LayoutParams) content.getLayoutParams();
        int edge = deviceFrame ? dp(6) : 0;
        inner.setMargins(edge, edge, edge, edge);
        content.setLayoutParams(inner);
        island.setVisibility(deviceFrame ? View.VISIBLE : View.GONE);
        homeIndicator.setVisibility(deviceFrame ? View.VISIBLE : View.GONE);
        footer.setText(deviceFrame ? "DEVICE FRAME  ·  LOCAL VNC" : "SCREEN ONLY  ·  LOCAL VNC");
        sizeDevice();
    }

    private void openOptions(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Reconnect display");
        menu.getMenu().add(0, 2, 1, "Keyboard & send text");
        menu.getMenu().add(0, 3, 2, deviceFrame ? "Screen only" : "Show device frame");
        menu.getMenu().add(0, 4, 3, "Back to machines");
        menu.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()) {
                case 1: connect(); return true;
                case 2: openKeyboard(); return true;
                case 3:
                    deviceFrame = !deviceFrame;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("deviceFrame", deviceFrame).apply();
                    applyDeviceStyle();
                    return true;
                case 4: finish(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    private void openKeyboard() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(8), dp(18), dp(8));
        TextView note = text("Sends keys to the guest through VNC (single-key input).", 12, SECONDARY, false);
        root.addView(note);
        root.addView(new View(this), new LinearLayout.LayoutParams(1, dp(10)));

        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER);
        root.addView(keys);
        String[] names = {"Esc", "Tab", "Enter", "⌫"};
        int[] codes = {0xFF1B, 0xFF09, 0xFF0D, 0xFF08};
        for(int i = 0; i < names.length; ++i) {
            final int keyCode = codes[i];
            Button key = smallButton(names[i]);
            LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(0, dp(44), 1);
            kp.rightMargin = i < 3 ? dp(6) : 0;
            keys.addView(key, kp);
            key.setOnClickListener(v -> press(keyCode));
        }

        EditText message = new EditText(this);
        message.setSingleLine(true);
        message.setTextColor(FOREGROUND);
        message.setHintTextColor(SECONDARY);
        message.setTextSize(14);
        message.setHint("Type text…");
        message.setInputType(InputType.TYPE_CLASS_TEXT);
        root.addView(message, new LinearLayout.LayoutParams(-1, dp(56)));
        new AlertDialog.Builder(this)
            .setTitle("Guest keyboard")
            .setView(root)
            .setNegativeButton("Close", null)
            .setPositiveButton("Send", (dialog, which) -> {
                RfbClient c = client;
                if (c != null) c.sendText(message.getText().toString());
                else Toast.makeText(this, "No display connected", Toast.LENGTH_SHORT).show();
            })
            .show();
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setAllCaps(false);
        button.setTextSize(12);
        button.setText(label);
        button.setTextColor(FOREGROUND);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(0);
        button.setMinimumHeight(0);
        button.setBackground(round(0xFF282B32, 10, 0, 0));
        return button;
    }

    private GradientDrawable round(int fill, int radius, int strokeColor, int strokeDp) {
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(fill);
        bg.setCornerRadius(dp(radius));
        if (strokeDp > 0) bg.setStroke(dp(strokeDp), strokeColor);
        return bg;
    }

    private TextView text(String content, int sp, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(content);
        view.setTextColor(color);
        view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private void press(int key) {
        RfbClient c = client;
        if (c != null) c.sendKey(key);
    }

    private void connect() {
        if (client != null) client.close();
        synchronized (framebufferLock) {
            if (framebuffer != null) {
                framebuffer.recycle();
                framebuffer = null;
            }
        }
        if (emptyLayer != null) {
            emptyLayer.setVisibility(View.VISIBLE);
            emptyHeading.setText("Waiting for display");
            emptySubtitle.setText("Start a VM in Machines.\nThe display will appear here.");
        }
        screen.invalidate();
        status.setText("Connecting to local VM…");
        RfbClient next = new RfbClient();
        client = next;
        new Thread(next, "qemu-vnc-client").start();
    }

    private void updateStatus(String label, RfbClient origin) {
        runOnUiThread(() -> {
            if(client == origin && !isFinishing()) status.setText(label);
        });
    }

    @Override protected void onDestroy() {
        if(client != null) client.close();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class ScreenView extends View {
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private boolean dragging = false;

        ScreenView() {
            super(ViewerActivity.this);
            setBackgroundColor(Color.BLACK);
        }

        private RectF fitted(int w, int h) {
            float scale = Math.min(getWidth() / (float)w, getHeight() / (float)h);
            float left = (getWidth() - w * scale) / 2f;
            float top = (getHeight() - h * scale) / 2f;
            return new RectF(left, top, left + w * scale, top + h * scale);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            synchronized (framebufferLock) {
                if(framebuffer != null && !framebuffer.isRecycled())
                    canvas.drawBitmap(framebuffer, null,
                            fitted(framebuffer.getWidth(), framebuffer.getHeight()), bitmapPaint);
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            if(action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_MOVE &&
               action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL) return false;
            RfbClient c = client;
            if(c == null) return true;
            synchronized (framebufferLock) {
                if(framebuffer == null || framebuffer.isRecycled()) return true;
                RectF dst = fitted(framebuffer.getWidth(), framebuffer.getHeight());
                if(action == MotionEvent.ACTION_DOWN) {
                    dragging = dst.contains(event.getX(), event.getY());
                    if(!dragging) return true;
                }
                if(!dragging) return true;
                int x = Math.max(0, Math.min(framebuffer.getWidth()-1,
                    (int)((event.getX()-dst.left)/dst.width()*framebuffer.getWidth())));
                int y = Math.max(0, Math.min(framebuffer.getHeight()-1,
                    (int)((event.getY()-dst.top)/dst.height()*framebuffer.getHeight())));
                int buttons = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ? 0 : 1;
                c.pointer(x, y, buttons);
                if(buttons == 0) dragging = false;
            }
            return true;
        }
    }

    private class RfbClient implements Runnable {
        private volatile boolean active = true;
        private Socket socket;
        private DataInputStream in;
        private DataOutputStream out;
        private final Object outputLock = new Object();
        private int width, height;

        @Override public void run() {
            try {
                IOException lastError = null;
                for (int attempt = 1; attempt <= 10 && active; attempt++) {
                    Socket trying = new Socket();
                    try {
                        trying.connect(new InetSocketAddress("127.0.0.1", 5901), 2300);
                        socket = trying;
                        break;
                    } catch (IOException e) {
                        lastError = e;
                        try { trying.close(); } catch (IOException ignored) {}
                        if (attempt < 10) {
                            updateStatus("Waiting for display  ·  " + attempt + "/10", this);
                            Thread.sleep(950);
                        }
                    }
                }
                if (!active) return;
                if (socket == null) throw lastError == null ? new IOException("VM not running") : lastError;
                socket.setTcpNoDelay(true);
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());
                handshake();
                updateStatus("Connected  ·  " + width + " × " + height, this);
                runOnUiThread(() -> {
                    if (client == this && !isFinishing()) {
                        if (emptyLayer != null) emptyLayer.setVisibility(View.GONE);
                    }
                });
                while (active && !socket.isClosed()) {
                    int message = in.readUnsignedByte();
                    if (message == 0) {
                        in.readUnsignedByte();
                        int rectangles = in.readUnsignedShort();
                        for (int r = 0; r < rectangles; r++) {
                            int x = in.readUnsignedShort(), y = in.readUnsignedShort();
                            int rw = in.readUnsignedShort(), rh = in.readUnsignedShort();
                            int encoding = in.readInt();
                            if (encoding != 0) throw new IOException("Unsupported VNC encoding: " + encoding);
                            if (rw < 1 || rh < 1 || x + rw > width || y + rh > height)
                                throw new IOException("Invalid framebuffer rectangle");
                            int[] pixels = new int[rw];
                            byte[] raw = new byte[rw * 4];
                            for (int iy = 0; iy < rh; iy++) {
                                in.readFully(raw);
                                for (int ix = 0; ix < rw; ix++) {
                                    int b = raw[ix * 4] & 0xff;
                                    int g = raw[ix * 4 + 1] & 0xff;
                                    int red = raw[ix * 4 + 2] & 0xff;
                                    pixels[ix] = 0xFF000000 | (red << 16) | (g << 8) | b;
                                }
                                synchronized (framebufferLock) {
                                    if (framebuffer != null) framebuffer.setPixels(pixels, 0, rw, x, y + iy, rw, 1);
                                }
                            }
                        }
                        runOnUiThread(() -> { if (client == this && !isFinishing()) screen.invalidate(); });
                        requestFrame(true);
                    } else if (message == 2) {
                        // Bell
                    } else if (message == 3) {
                        in.skipBytes(3);
                        int len = in.readInt();
                        if (len < 0 || len > 1024 * 1024) throw new IOException("Invalid clipboard length");
                        byte[] chunk = new byte[4096];
                        while (len > 0) { int n = Math.min(len, chunk.length); in.readFully(chunk, 0, n); len -= n; }
                    } else {
                        throw new IOException("Unsupported VNC server message: " + message);
                    }
                }
            } catch (Exception e) {
                if (active) {
                    updateStatus("Offline · " + e.getClass().getSimpleName(), this);
                    runOnUiThread(() -> {
                        if (client == this && !isFinishing()) {
                            if (emptyLayer != null) emptyLayer.setVisibility(View.VISIBLE);
                            emptyHeading.setText("Display offline");
                            emptySubtitle.setText("The VM has not opened its display.\nCheck the QEMU log from Machines.");
                        }
                    });
                }
            } finally { close(); }
        }

        private void handshake() throws IOException {
            byte[] version = new byte[12];
            in.readFully(version);
            String remote = new String(version, StandardCharsets.US_ASCII);
            if (!remote.startsWith("RFB 003.")) throw new IOException("Not an RFB server");
            boolean legacy = remote.equals("RFB 003.003\n");
            out.write("RFB 003.".getBytes(StandardCharsets.US_ASCII));
            out.write((legacy ? "003\n" : "008\n").getBytes(StandardCharsets.US_ASCII));
            out.flush();
            if (legacy) {
                if (in.readInt() != 1) throw new IOException("VNC requires authentication");
            } else {
                int count = in.readUnsignedByte();
                if (count == 0) throw new IOException("VNC refused connection");
                byte[] security = new byte[count]; in.readFully(security);
                boolean none = false;
                for (byte s : security) if (s == 1) none = true;
                if (!none) throw new IOException("VNC no-auth mode not offered");
                out.writeByte(1); out.flush();
                if (in.readInt() != 0) throw new IOException("VNC authentication failed");
            }
            out.writeByte(1); out.flush(); // share the session
            width = in.readUnsignedShort();
            height = in.readUnsignedShort();
            if (width < 1 || height < 1 || width > 4096 || height > 4096)
                throw new IOException("Unsupported screen dimensions");
            byte[] serverFormat = new byte[16]; in.readFully(serverFormat);
            int titleLen = in.readInt();
            if (titleLen < 0 || titleLen > 65536) throw new IOException("Bad RFB title");
            byte[] title = new byte[titleLen]; in.readFully(title);
            synchronized (framebufferLock) { framebuffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); }
            synchronized (outputLock) {
                // SetPixelFormat: 32bpp, depth 24, little-endian, RGB888.
                out.writeByte(0); out.write(new byte[3]);
                out.writeByte(32); out.writeByte(24); out.writeByte(0); out.writeByte(1);
                out.writeShort(255); out.writeShort(255); out.writeShort(255);
                out.writeByte(16); out.writeByte(8); out.writeByte(0);
                out.write(new byte[3]);
                // Encoding 0: RAW. Deliberately small and dependency-free client.
                out.writeByte(2); out.writeByte(0); out.writeShort(1); out.writeInt(0);
                out.flush();
            }
            requestFrame(false);
        }

        private void requestFrame(boolean incremental) throws IOException {
            synchronized (outputLock) {
                out.writeByte(3); out.writeByte(incremental ? 1 : 0);
                out.writeShort(0); out.writeShort(0);
                out.writeShort(width); out.writeShort(height); out.flush();
            }
        }

        void pointer(int x, int y, int buttons) {
            synchronized (outputLock) {
                if (out == null || !active) return;
                try {
                    out.writeByte(5);
                    out.writeByte(buttons);
                    out.writeShort(x);
                    out.writeShort(y);
                    out.flush();
                } catch (IOException e) { close(); }
            }
        }

        void sendKey(int sym) {
            synchronized (outputLock) {
                if (out == null || !active) return;
                try {
                    keyEvent(true, sym);
                    keyEvent(false, sym);
                    out.flush();
                } catch (IOException e) { close(); }
            }
        }

        void sendText(String text) {
            if (text.length() > 2048) text = text.substring(0, 2048);
            for (int i = 0; i < text.length();) {
                int code = text.codePointAt(i); i += Character.charCount(code);
                if (code == '\n') sendKey(0xFF0D);
                else if (code <= 0xFF) sendKey(code);
                else sendKey(0x01000000 | code);
            }
        }

        private void keyEvent(boolean down, int sym) throws IOException {
            out.writeByte(4); out.writeByte(down ? 1 : 0);
            out.writeShort(0); out.writeInt(sym);
        }

        void close() {
            active = false;
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }
    }
}
