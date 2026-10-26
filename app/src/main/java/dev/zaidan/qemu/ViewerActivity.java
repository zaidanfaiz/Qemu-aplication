package dev.zaidan.qemu;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
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

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(0xFF0B1220);
        getWindow().setNavigationBarColor(0xFF0B1220);

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setBackgroundColor(0xFF0B1220);
        setContentView(body);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        status = new TextView(this);
        status.setText("VNC • Connecting to 127.0.0.1:5901");
        status.setTextColor(Color.WHITE);
        status.setPadding(dp(12), dp(10), dp(8), dp(8));
        bar.addView(status, new LinearLayout.LayoutParams(0, -2, 1));
        Button reconnect = action("Reconnect", () -> connect());
        bar.addView(reconnect);
        body.addView(bar);

        screen = new ScreenView();
        body.addView(screen, new LinearLayout.LayoutParams(-1, 0, 1));

        LinearLayout keyboard = new LinearLayout(this);
        keyboard.setOrientation(LinearLayout.HORIZONTAL);
        keyboard.addView(action("ESC", () -> press(0xFF1B)));
        keyboard.addView(action("Tab", () -> press(0xFF09)));
        keyboard.addView(action("Enter", () -> press(0xFF0D)));
        keyboard.addView(action("⌫", () -> press(0xFF08)));
        keyboard.addView(action("←", () -> press(0xFF51)));
        keyboard.addView(action("↑", () -> press(0xFF52)));
        keyboard.addView(action("↓", () -> press(0xFF54)));
        keyboard.addView(action("→", () -> press(0xFF53)));
        body.addView(keyboard);

        LinearLayout sendRow = new LinearLayout(this);
        sendRow.setOrientation(LinearLayout.HORIZONTAL);
        EditText input = new EditText(this);
        input.setTextColor(Color.WHITE);
        input.setHintTextColor(0xFF93A3B8);
        input.setHint("Type text to guest");
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD);
        sendRow.addView(input, new LinearLayout.LayoutParams(0, dp(51), 1));
        sendRow.addView(action("Send", () -> {
            RfbClient c = client;
            if (c != null) c.sendText(input.getText().toString());
            input.setText("");
        }));
        body.addView(sendRow);
        connect();
    }

    private Button action(String text, Runnable click) {
        Button b = new Button(this);
        b.setAllCaps(false);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(12);
        b.setMinimumWidth(0);
        b.setMinWidth(0);
        b.setPadding(dp(6), 0, dp(6), 0);
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
                if (framebuffer == null) {
                    canvas.drawText("Start a VM, then tap Reconnect", dp(18), getHeight() / 2f, help);
                } else {
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
                socket = new Socket();
                socket.connect(new InetSocketAddress("127.0.0.1", 5901), 4500);
                socket.setTcpNoDelay(true);
                in = new DataInputStream(socket.getInputStream());
                out = new DataOutputStream(socket.getOutputStream());
                handshake();
                updateStatus("VNC connected • " + width + "×" + height + " • touch = drag/click", this);
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
                if (active) updateStatus("VNC disconnected: " + e.getMessage(), this);
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
