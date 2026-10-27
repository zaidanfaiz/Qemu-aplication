package dev.zaidan.qemu;

import android.util.Log;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Opt-in QMP 8.1+ multitouch bridge for a virtio-multitouch-pci guest device.
 * Entirely asynchronous: never access QMP from Android's touch/UI thread.
 *
 * An accepting QMP command does not imply the guest OS has a virtio touch
 * driver. Standard VNC pointer input remains the default/fallback.
 */
final class QmpMultiTouch {
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "qemu-qmp-touch");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger queueDepth = new AtomicInteger();
    private final AtomicInteger nextTracking = new AtomicInteger(1);
    private volatile boolean ready;
    private volatile boolean closed;
    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;

    boolean isReady() { return ready && !closed; }

    void connect() {
        if (closed) return;
        worker.execute(() -> {
            if (ready || closed) return;
            for (int n = 0; n < 7 && !closed; n++) {
                try {
                    socket = new Socket();
                    socket.connect(new InetSocketAddress("127.0.0.1", 4444), 1300);
                    socket.setSoTimeout(2500);
                    reader = new BufferedReader(new InputStreamReader(
                            socket.getInputStream(), StandardCharsets.UTF_8));
                    writer = new BufferedWriter(new OutputStreamWriter(
                            socket.getOutputStream(), StandardCharsets.UTF_8));
                    String greeting = reader.readLine();
                    if (greeting == null || !new JSONObject(greeting).has("QMP"))
                        throw new IllegalStateException("Missing QMP greeting");
                    JSONObject capabilities = new JSONObject();
                    capabilities.put("execute", "qmp_capabilities");
                    write(capabilities);
                    readReply();
                    ready = true;
                    Log.i("QmpMultiTouch", "QMP input bridge connected");
                    return;
                } catch (Exception e) {
                    ready = false;
                    disconnect();
                    if (n == 6) {
                        Log.w("QmpMultiTouch", "Native guest touch unavailable; using VNC", e);
                    } else try { Thread.sleep(650); } catch (InterruptedException ignored) { return; }
                }
            }
        });
    }

    int trackingId() {
        int id = nextTracking.getAndIncrement();
        if (id > 1000000000) nextTracking.set(1);
        return id;
    }

    private static JSONObject multi(String type, int slot, int tracking, String axis, int value)
            throws Exception {
        JSONObject data = new JSONObject();
        data.put("type", type);
        data.put("slot", slot);
        data.put("tracking-id", tracking);
        data.put("axis", axis);
        data.put("value", value);
        return new JSONObject().put("type", "mtt").put("data", data);
    }

    private static JSONObject touchButton(boolean down) throws Exception {
        return new JSONObject().put("type", "btn")
                .put("data", new JSONObject().put("button", "touch").put("down", down));
    }

    // Absolute positions are in QEMU's 0..0x7fff input coordinate range.
    static int abs(int pixel, int maximum) {
        return Math.max(0, Math.min(32767,
                Math.round(pixel / (float) Math.max(1, maximum - 1) * 32767)));
    }

    void begin(int slot, int tracking, int x, int y, boolean first) {
        enqueue("begin", slot, tracking, x, y, first, false, false);
    }

    void move(int slot, int tracking, int x, int y) {
        enqueue("update", slot, tracking, x, y, false, false, true);
    }

    void end(int slot, int tracking, boolean last) {
        enqueue("end", slot, tracking, 0, 0, false, last, false);
    }

    private void enqueue(String type, int slot, int tracking, int x, int y,
                         boolean first, boolean last, boolean movement) {
        if (!isReady() || slot < 0 || slot > 10) return;
        int queued = queueDepth.incrementAndGet();
        if (movement && queued > 6) {
            queueDepth.decrementAndGet();
            return;
        }
        worker.execute(() -> {
            try {
                if (!ready || closed) return;
                JSONArray events = new JSONArray();
                events.put(multi(type, slot, "end".equals(type) ? -1 : tracking,
                                 "x", x));
                if (!"end".equals(type)) {
                    events.put(multi("data", slot, tracking, "x", x));
                    events.put(multi("data", slot, tracking, "y", y));
                }
                if (first) events.put(touchButton(true));
                if (last) events.put(touchButton(false));
                JSONObject cmd = new JSONObject();
                cmd.put("execute", "input-send-event");
                cmd.put("arguments", new JSONObject().put("events", events));
                write(cmd);
                readReply();
            } catch (Exception e) {
                Log.w("QmpMultiTouch", "Guest touch command failed; VNC available", e);
                ready = false;
                disconnect();
            } finally { queueDepth.decrementAndGet(); }
        });
    }

    private void write(JSONObject message) throws Exception {
        writer.write(message.toString());
        writer.write("\r\n");
        writer.flush();
    }

    private void readReply() throws Exception {
        for (int n = 0; n < 12; n++) {
            String line = reader.readLine();
            if (line == null) throw new IllegalStateException("QMP connection closed");
            JSONObject response = new JSONObject(line);
            if (response.has("error"))
                throw new IllegalStateException(response.getJSONObject("error").toString());
            if (response.has("return")) return;
        }
        throw new IllegalStateException("No QMP command reply");
    }

    private void disconnect() {
        try { if (socket != null) socket.close(); } catch (Exception ignored) {}
        socket = null;
        reader = null;
        writer = null;
    }

    void close() {
        closed = true;
        ready = false;
        disconnect();
        worker.shutdownNow();
    }
}
