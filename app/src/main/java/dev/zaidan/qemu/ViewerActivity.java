package dev.zaidan.qemu;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.widget.HorizontalScrollView;
import android.widget.FrameLayout;
import android.os.Bundle;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

public class ViewerActivity extends Activity {
    private final Object framebufferLock = new Object();
    private volatile RfbClient client;
    private Bitmap framebuffer;
    private ScreenView screen;
    private TextView status;

    private static final int BG = 0xFF101216, BAR = 0xFF191C22;
    private static final int FG = 0xFFE7EAF0, MUTED = 0xFF9CA5B3;
    private LinearLayout body;
    private TextView emptyHeading, emptySubtitle;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(BG);
        body.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(0, insets.getSystemWindowInsetTop(),
                0, insets.getSystemWindowInsetBottom());
            return insets;
        });
        setContentView(body);

        LinearLayout header = new LinearLayout(this);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(12), 0, dp(13), 0);
        header.setBackgroundColor(BAR);
        body.addView(header, new LinearLayout.LayoutParams(-1, dp(56)));

        Button back = action("‹  Machines", false, () -> finish());
        header.addView(back, new LinearLayout.LayoutParams(dp(122), dp(40)));

        status = new TextView(this);
        status.setText("Connecting to local display…");
        status.setTextColor(MUTED);
        status.setTextSize(13);
        status.setMaxLines(1);
        status.setEllipsize(android.text.TextUtils.TruncateAt.END);
        status.setGravity(Gravity.CENTER_VERTICAL);
        status.setPadding(dp(13), 0, dp(10), 0);
        header.addView(status, new LinearLayout.LayoutParams(0, dp(43), 1));

        Button reconnect = action("Retry", true, this::connect);
        header.addView(reconnect, new LinearLayout.LayoutParams(dp(82), dp(40)));

        FrameLayout display = new FrameLayout(this);
        display.setBackgroundColor(0xFF070809);
        body.addView(display, new LinearLayout.LayoutParams(-1, 0, 1));
        screen = new ScreenView();
        display.addView(screen, new FrameLayout.LayoutParams(-1, -1));

        LinearLayout empty = new LinearLayout(this);
        empty.setOrientation(LinearLayout.VERTICAL);
        empty.setGravity(Gravity.CENTER);
        empty.setPadding(dp(16), dp(8), dp(16), dp(8));
        FrameLayout.LayoutParams emptyPos =
             new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER);
        display.addView(empty, emptyPos);
        emptyHeading = new TextView(this);
        emptyHeading.setText("Display not connected");
        emptyHeading.setTextSize(19);
        emptyHeading.setTextColor(FG);
        emptyHeading.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        emptyHeading.setGravity(Gravity.CENTER);
        empty.addView(emptyHeading);
        emptySubtitle = new TextView(this);
        emptySubtitle.setText("Start a virtual machine from Machines, then retry.");
        emptySubtitle.setTextSize(13);
        emptySubtitle.setTextColor(MUTED);
        emptySubtitle.setGravity(Gravity.CENTER);
        emptySubtitle.setPadding(0, dp(7), 0, 0);
        empty.addView(emptySubtitle);

        HorizontalScrollView keyScroll = new HorizontalScrollView(this);
        keyScroll.setHorizontalScrollBarEnabled(false);
        keyScroll.setFillViewport(true);
        keyScroll.setBackgroundColor(BAR);
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER_VERTICAL);
        keys.setPadding(dp(9), dp(3), dp(9), dp(3));
        for (String label : new String[]{"Esc", "Tab", "Enter", "⌫", "←", "↑", "↓", "→"}) {
            final int code;
            switch (label) {
                case "Esc": code = 0xFF1B; break;
                case "Tab": code = 0xFF09; break;
                case "Enter": code = 0xFF0D; break;
                case "⌫": code = 0xFF08; break;
                case "←": code = 0xFF51; break;
                case "↑": code = 0xFF52; break;
                case "↓": code = 0xFF54; break;
                default: code = 0xFF53; break;
            }
            Button key = action(label, false, () -> press(code));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                label.equals("Enter") ? dp(78) : dp(58), dp(40));
            lp.rightMargin = dp(7);
            keys.addView(key, lp);
        }
        keyScroll.addView(keys);
        body.addView(keyScroll, new LinearLayout.LayoutParams(-1, dp(50)));

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setGravity(Gravity.CENTER_VERTICAL);
        sendRow.setBackgroundColor(BAR);
        sendRow.setPadding(dp(12), dp(2), dp(12), dp(7));
        EditText input = new EditText(this);
        input.setTextColor(FG);
        input.setHintTextColor(MUTED);
        input.setTextSize(14);
        input.setHint("Type a command or text…");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setPadding(dp(12), 0, dp(12), 0);
        input.setBackground(shape(0xFF242830, 10));
        LinearLayout.LayoutParams inputLp = new LinearLayout.LayoutParams(0, dp(42), 1);
        inputLp.rightMargin = dp(10);
        sendRow.addView(input, inputLp);
        sendRow.addView(action("Send", true, () -> {
            RfbClient c = client;
            if (c != null) c.sendText(input.getText().toString());
            input.setText("");
        }), new LinearLayout.LayoutParams(dp(76), dp(42)));
        body.addView(sendRow, new LinearLayout.LayoutParams(-1, dp(52)));
        connect();
    }

    private GradientDrawable shape(int color, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radius));
        return d;
    }

    private Button action(String text, boolean primary, Runnable click) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        b.setTextColor(primary ? 0xFF111317 : FG);
        b.setTextSize(13);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(dp(7), 0, dp(7), 0);
        b.setBackground(shape(primary ? 0xFFC2CCD9 : 0xFF303641, 10));
        b.setOnClickListener(v -> click.run());
        return b;
    }

    private void press(int key) {
        RfbClient c = client;
        if (c != null) c.sendKey(key);
    }

    private void connect() {
        if (client != null) client.close();
        synchronized (framebufferLock) { framebuffer = null; }
        if (emptyHeading != null) emptyHeading.setVisibility(View.VISIBLE);
        if (emptySubtitle != null) emptySubtitle.setVisibility(View.VISIBLE);
        screen.invalidate();
        status.setText("VNC • Connecting to 127.0.0.1:5901");
        RfbClient next = new RfbClient();
        client = next;
        new Thread(next, "qemu-vnc-client").start();
    }

    private void updateStatus(String text, RfbClient origin) {
        runOnUiThread(() -> { if (client == origin && !isFinishing()) status.setText(text); });
    }

    @Override protected void onDestroy() {
        if (client != null) client.close();
        super.onDestroy();
    }

    private int dp(int value) {
        return (int)(value * getResources().getDisplayMetrics().density + .5f);
    }

    private class ScreenView extends View {
        private final Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        private final Paint help = new Paint(Paint.ANTI_ALIAS_FLAG);
        ScreenView() {
            super(ViewerActivity.this);
            help.setColor(0xFF91A5C7);
            help.setTextSize(dp(17));
            setBackgroundColor(Color.BLACK);
        }

        private RectF fitted(int w, int h) {
            float scale = Math.min(getWidth() / (float) w, getHeight() / (float) h);
            float x = (getWidth() - w * scale) / 2f;
            float y = (getHeight() - h * scale) / 2f;
            return new RectF(x, y, x + w * scale, y + h * scale);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            synchronized (framebufferLock) {
                if (framebuffer != null) {
                    canvas.drawBitmap(framebuffer, null, fitted(framebuffer.getWidth(), framebuffer.getHeight()), paint);
                }
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            if (event.getActionMasked() != MotionEvent.ACTION_DOWN &&
                event.getActionMasked() != MotionEvent.ACTION_MOVE &&
                event.getActionMasked() != MotionEvent.ACTION_UP &&
                event.getActionMasked() != MotionEvent.ACTION_CANCEL) return false;
            RfbClient c = client;
            if (c == null) return true;
            synchronized (framebufferLock) {
                if (framebuffer == null) return true;
                RectF dst = fitted(framebuffer.getWidth(), framebuffer.getHeight());
                int x = Math.max(0, Math.min(framebuffer.getWidth() - 1,
                    (int)((event.getX() - dst.left) / dst.width() * framebuffer.getWidth())));
                int y = Math.max(0, Math.min(framebuffer.getHeight() - 1,
                    (int)((event.getY() - dst.top) / dst.height() * framebuffer.getHeight())));
                int action = event.getActionMasked();
                int buttons = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) ? 0 : 1;
                c.pointer(x, y, buttons);
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
                            updateStatus("Waiting for VM display · " + attempt + "/10", this);
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
                updateStatus("Connected · " + width + "×" + height, this);
                runOnUiThread(() -> {
                    if (client == this && !isFinishing()) {
                        emptyHeading.setVisibility(View.GONE);
                        emptySubtitle.setVisibility(View.GONE);
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
                            emptyHeading.setText("No VM display");
                            emptySubtitle.setText("QEMU VNC is not listening on 127.0.0.1:5901.\nOpen Machines → ⋮ → View QEMU log.");
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
