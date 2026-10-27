package dev.zaidan.qemu;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.SharedPreferences;
import android.content.Context;
import android.view.inputmethod.InputMethodManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.text.InputType;
import android.view.KeyEvent;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.util.SparseIntArray;
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
    private boolean computerMode;
    private boolean ctrlModifier;
    private boolean altModifier;
    private LinearLayout computerKeyboard;
    private EditText guestInput;
    private TextView ctrlButton, altButton;
    private boolean nativeTouchMode;
    private QmpMultiTouch qmpTouch;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        SharedPreferences vmSettings = getSharedPreferences("native_vm", MODE_PRIVATE);
        computerMode = "computer".equals(vmSettings.getString("interactionMode", "phone"));
        deviceFrame = !computerMode && getPreferences(MODE_PRIVATE).getBoolean("deviceFrame", true);
        setRequestedOrientation(computerMode ?
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE :
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        nativeTouchMode = getPreferences(MODE_PRIVATE).getBoolean("nativeGuestTouch", false);
        qmpTouch = new QmpMultiTouch();
        if (nativeTouchMode) qmpTouch.connect();
        getWindow().setStatusBarColor(BACKGROUND);
        getWindow().setNavigationBarColor(BACKGROUND);

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
        headings.addView(text(computerMode ? "Computer display" : "Phone display", 17, FOREGROUND, true));
        status = text("Connecting to local VM…", 11, SECONDARY, false);
        status.setMaxLines(1);
        status.setEllipsize(TextUtils.TruncateAt.END);
        headings.addView(status);

        TextView more = text("⋯", 28, FOREGROUND, true);
        more.setContentDescription("Simulator options: touch mode, reconnect, screen mode");
        more.setGravity(Gravity.CENTER);
        toolbar.addView(more, new LinearLayout.LayoutParams(dp(45), dp(45)));
        more.setOnClickListener(v -> openOptions(more));

        stage = new FrameLayout(this);
        stage.setBackgroundColor(BACKGROUND);
        page.addView(stage, new LinearLayout.LayoutParams(-1, 0, 1));

        phone = new FrameLayout(this);
        stage.addView(phone);
        // Re-measure available display space without permanently visible controls.
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

        computerKeyboard = buildComputerKeyboard();
        page.addView(computerKeyboard, new LinearLayout.LayoutParams(-1, -2));
        computerKeyboard.setVisibility(computerMode ? View.VISIBLE : View.GONE);

        footer = text("LOCAL DISPLAY  ·  TOUCH INPUT", 10, SECONDARY, false);
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
        footer.setText(nativeTouchMode ? "DIRECT TOUCH  ·  MULTITOUCH EXPERIMENTAL" :
                (deviceFrame ? "TOUCH  ·  SWIPE  ·  TWO-FINGER SCROLL" :
                               "TOUCH INPUT  ·  LOCAL VNC"));
        sizeDevice();
    }

    private void openOptions(View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Reconnect display");
        if (!computerMode) menu.getMenu().add(0, 3, 1, deviceFrame ? "Screen only" : "Show device frame");
        menu.getMenu().add(0, 5, 2, nativeTouchMode ?
            "Standard touch" : "Multitouch (experimental)");
        menu.getMenu().add(0, 6, 3, computerMode ? "Switch to Phone / Touch" :
                "Switch to Computer / Keyboard");
        if (computerMode) menu.getMenu().add(0, 7, 4, "Show Android keyboard");
        menu.getMenu().add(0, 4, 5, "Back to machines");
        menu.setOnMenuItemClickListener(item -> {
            switch(item.getItemId()) {
                case 1: connect(); return true;
                case 3:
                    deviceFrame = !deviceFrame;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("deviceFrame", deviceFrame).apply();
                    applyDeviceStyle();
                    return true;
                case 5:
                    nativeTouchMode = !nativeTouchMode;
                    getPreferences(MODE_PRIVATE).edit().putBoolean("nativeGuestTouch", nativeTouchMode).apply();
                    if (nativeTouchMode) {
                        qmpTouch.connect();
                        Toast.makeText(this,
                            "QMP multitouch needs a guest virtio input driver; reconnect may be needed",
                            Toast.LENGTH_LONG).show();
                    } else {
                        Toast.makeText(this, "Standard touch gestures enabled", Toast.LENGTH_SHORT).show();
                    }
                    applyDeviceStyle();
                    return true;
                case 6:
                    getSharedPreferences("native_vm", MODE_PRIVATE).edit()
                            .putString("interactionMode", computerMode ? "phone" : "computer").apply();
                    recreate();
                    return true;
                case 7: showAndroidKeyboard(); return true;
                case 4: finish(); return true;
                default: return false;
            }
        });
        menu.show();
    }

    /**
     * Desktop mode: compact physical-PC keys plus the user's real Android IME.
     * All input sends RFB key events; the row never appears in Phone mode.
     */
    private LinearLayout buildComputerKeyboard() {
        LinearLayout bar = new LinearLayout(this);
        bar.setBackgroundColor(SURFACE);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        HorizontalScrollView scroller = new HorizontalScrollView(this);
        scroller.setHorizontalScrollBarEnabled(false);
        LinearLayout keys = new LinearLayout(this);
        keys.setGravity(Gravity.CENTER_VERTICAL);

        String[] labels = {"Esc", "Tab", "Ctrl", "Alt", "←", "↑", "↓", "→",
                "Enter", "Backspace", "Del", "Home", "End"};
        int[] values = {0xff1b,0xff09,0,0,0xff51,0xff52,0xff54,0xff53,
                0xff0d,0xff08,0xffff,0xff50,0xff57};
        for (int i = 0; i < labels.length; i++) {
            final int sym = values[i];
            final String keyName = labels[i];
            TextView key = smallPcKey(keyName);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    keyName.equals("Backspace") ? dp(90) : dp(60), dp(38));
            lp.rightMargin = dp(5);
            keys.addView(key, lp);
            if (keyName.equals("Ctrl")) ctrlButton = key;
            if (keyName.equals("Alt")) altButton = key;
            key.setOnClickListener(view -> {
                if ("Ctrl".equals(keyName)) {
                    ctrlModifier = !ctrlModifier;
                    setModifierStyle(ctrlButton, ctrlModifier);
                } else if ("Alt".equals(keyName)) {
                    altModifier = !altModifier;
                    setModifierStyle(altButton, altModifier);
                } else {
                    sendGuestKey(sym);
                }
            });
        }
        scroller.addView(keys);
        bar.addView(scroller, new LinearLayout.LayoutParams(-1, dp(43)));

        LinearLayout inputRow = new LinearLayout(this);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(-1, dp(48));
        ip.topMargin = dp(5);
        bar.addView(inputRow, ip);

        guestInput = new GuestEditText();
        guestInput.setTextColor(FOREGROUND);
        guestInput.setHintTextColor(SECONDARY);
        guestInput.setTextSize(14);
        guestInput.setSingleLine(true);
        guestInput.setHint("Type on your Android keyboard…");
        guestInput.setInputType(InputType.TYPE_CLASS_TEXT |
                InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        guestInput.setImeOptions(EditorInfo.IME_ACTION_DONE |
                EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        guestInput.setBackground(round(0xFF252932, 11, 0, 0));
        guestInput.setPadding(dp(12), 0, dp(12), 0);
        guestInput.setOnEditorActionListener((view, actionId, event) -> {
            if (event != null && event.getAction() != KeyEvent.ACTION_DOWN) return true;
            sendGuestKey(0xff0d);
            return true;
        });
        guestInput.setOnKeyListener((view, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            int sym = keysymForEvent(event);
            if (sym == 0) return false;
            sendGuestKey(sym);
            return true;
        });
        inputRow.addView(guestInput, new LinearLayout.LayoutParams(0, dp(44), 1));

        TextView openIme = smallPcKey("⌨ Open");
        LinearLayout.LayoutParams openLp = new LinearLayout.LayoutParams(dp(82), dp(44));
        openLp.leftMargin = dp(8);
        inputRow.addView(openIme, openLp);
        openIme.setOnClickListener(view -> showAndroidKeyboard());
        return bar;
    }

    private TextView smallPcKey(String label) {
        TextView view = text(label, 12, FOREGROUND, true);
        view.setGravity(Gravity.CENTER);
        view.setBackground(round(0xFF30343E, 9, 0, 0));
        view.setFocusable(true);
        view.setClickable(true);
        return view;
    }

    private void setModifierStyle(TextView view, boolean active) {
        view.setTextColor(active ? 0xFF0E1116 : FOREGROUND);
        view.setBackground(round(active ? 0xFFBACDED : 0xFF30343E, 9, 0, 0));
    }

    private void showAndroidKeyboard() {
        if (!computerMode || guestInput == null) return;
        guestInput.requestFocus();
        InputMethodManager imm = (InputMethodManager)
                getSystemService(Context.INPUT_METHOD_SERVICE);
        if (imm != null) imm.showSoftInput(guestInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private int keysymForEvent(KeyEvent event) {
        switch (event.getKeyCode()) {
            case KeyEvent.KEYCODE_ENTER: return 0xff0d;
            case KeyEvent.KEYCODE_ESCAPE: return 0xff1b;
            case KeyEvent.KEYCODE_TAB: return 0xff09;
            case KeyEvent.KEYCODE_DEL: return 0xff08;
            case KeyEvent.KEYCODE_FORWARD_DEL: return 0xffff;
            case KeyEvent.KEYCODE_DPAD_LEFT: return 0xff51;
            case KeyEvent.KEYCODE_DPAD_UP: return 0xff52;
            case KeyEvent.KEYCODE_DPAD_RIGHT: return 0xff53;
            case KeyEvent.KEYCODE_DPAD_DOWN: return 0xff54;
            case KeyEvent.KEYCODE_MOVE_HOME: return 0xff50;
            case KeyEvent.KEYCODE_MOVE_END: return 0xff57;
            case KeyEvent.KEYCODE_PAGE_UP: return 0xff55;
            case KeyEvent.KEYCODE_PAGE_DOWN: return 0xff56;
            default:
                int unicode = event.getUnicodeChar();
                return unicode > 0 ? unicodeKeysym(unicode) : 0;
        }
    }

    private int unicodeKeysym(int unicode) {
        if (unicode == '\n') return 0xff0d;
        if (unicode == '\t') return 0xff09;
        if (unicode == '\b') return 0xff08;
        if (unicode < 0x100) return unicode;
        return unicode <= 0x10ffff ? 0x01000000 | unicode : 0;
    }

    private void sendGuestText(CharSequence text) {
        RfbClient active = client;
        if (active == null || text == null) return;
        String value = text.toString();
        for (int i = 0; i < value.length();) {
            int unicode = value.codePointAt(i);
            i += Character.charCount(unicode);
            int sym = unicodeKeysym(unicode);
            if (sym != 0) active.sendChord(sym, ctrlModifier, altModifier);
        }
    }

    private void sendGuestKey(int sym) {
        RfbClient active = client;
        if (active != null) active.sendChord(sym, ctrlModifier, altModifier);
    }

    /**
     * InputConnection receives Android IME commits, even on Gboard keyboards
     * that never emit KeyEvents. Never depend on EditText's visible text as
     * a source of truth for guest keyboard events.
     */
    private class GuestEditText extends EditText {
        GuestEditText() { super(ViewerActivity.this); }

        @Override public InputConnection onCreateInputConnection(EditorInfo attrs) {
            InputConnection original = super.onCreateInputConnection(attrs);
            if (original == null) return null;
            return new InputConnectionWrapper(original, false) {
                @Override public boolean commitText(CharSequence text, int cursor) {
                    if (text != null && text.length() != 0) sendGuestText(text);
                    return super.commitText(text, cursor);
                }
                @Override public boolean deleteSurroundingText(int before, int after) {
                    // Some soft keyboards send deletion even when EditText is empty.
                    for (int i = 0; i < Math.min(before, 20); i++) sendGuestKey(0xff08);
                    for (int i = 0; i < Math.min(after, 20); i++) sendGuestKey(0xffff);
                    return super.deleteSurroundingText(before, after);
                }
                @Override public boolean sendKeyEvent(KeyEvent event) {
                    if (event.getAction() == KeyEvent.ACTION_DOWN) {
                        int sym = keysymForEvent(event);
                        if (sym != 0) {
                            sendGuestKey(sym);
                            return true;
                        }
                    }
                    return super.sendKeyEvent(event);
                }
            };
        }
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
        if(qmpTouch != null) qmpTouch.close();
        super.onDestroy();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private class ScreenView extends View {
        private final Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        private boolean pressed;
        private boolean twoFingerGesture;
        private boolean ignoreUntilAllReleased;
        private int lastGuestX, lastGuestY;
        private float lastCenterX, lastCenterY;
        private float scrollRemainderX, scrollRemainderY;
        private boolean qmpGesture;
        private long lastQmpMoveAt;
        private final SparseIntArray slots = new SparseIntArray();
        private final SparseIntArray trackingIds = new SparseIntArray();

        ScreenView() {
            super(ViewerActivity.this);
            setBackgroundColor(Color.BLACK);
            setClickable(true);
        }

        private RectF fitted(int w, int h) {
            float scale = Math.min(getWidth() / (float) w, getHeight() / (float) h);
            float left = (getWidth() - w * scale) / 2f;
            float top = (getHeight() - h * scale) / 2f;
            return new RectF(left, top, left + w * scale, top + h * scale);
        }

        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            synchronized (framebufferLock) {
                if (framebuffer != null && !framebuffer.isRecycled())
                    canvas.drawBitmap(framebuffer, null,
                            fitted(framebuffer.getWidth(), framebuffer.getHeight()), bitmapPaint);
            }
        }

        private int coordX(float x, RectF dst, int width) {
            return Math.max(0, Math.min(width - 1, Math.round(
                    (x - dst.left) / dst.width() * (width - 1))));
        }

        private int coordY(float y, RectF dst, int height) {
            return Math.max(0, Math.min(height - 1, Math.round(
                    (y - dst.top) / dst.height() * (height - 1))));
        }

        private void release(RfbClient c) {
            if (pressed) {
                c.pointer(lastGuestX, lastGuestY, 0);
                pressed = false;
            }
        }

        private void wheelFromTwoFingers(RfbClient c, MotionEvent event) {
            float centerX = (event.getX(0) + event.getX(1)) / 2f;
            float centerY = (event.getY(0) + event.getY(1)) / 2f;
            float deltaX = centerX - lastCenterX;
            float deltaY = centerY - lastCenterY;
            lastCenterX = centerX;
            lastCenterY = centerY;
            scrollRemainderX += deltaX;
            scrollRemainderY += deltaY;
            // RFB: wheel-up bit 3, wheel-down bit 4; left bit 5, right bit 6.
            // Dragging two fingers upward scrolls content down, like a phone.
            float threshold = dp(24);
            int count = 0;
            while (Math.abs(scrollRemainderY) >= threshold && count++ < 5) {
                int mask = scrollRemainderY > 0 ? 8 : 16;
                c.wheel(lastGuestX, lastGuestY, mask);
                scrollRemainderY += scrollRemainderY > 0 ? -threshold : threshold;
            }
            count = 0;
            while (Math.abs(scrollRemainderX) >= threshold && count++ < 5) {
                int mask = scrollRemainderX > 0 ? 32 : 64;
                c.wheel(lastGuestX, lastGuestY, mask);
                scrollRemainderX += scrollRemainderX > 0 ? -threshold : threshold;
            }
        }

        // QMP supports explicit multi-contact events when a guest has
        // a virtio-multitouch driver. VNC remains the default because generic
        // PC/ISO guests do not all have that driver.
        private int takeSlot() {
            for (int candidate = 0; candidate <= 10; candidate++) {
                boolean used = false;
                for (int i = 0; i < slots.size(); i++) {
                    if (slots.valueAt(i) == candidate) { used = true; break; }
                }
                if (!used) return candidate;
            }
            return -1;
        }

        private void beginGuestContact(MotionEvent event, int pointerIndex, RectF dst,
                                       int width, int height) {
            int pointerId = event.getPointerId(pointerIndex);
            if (slots.indexOfKey(pointerId) >= 0) return;
            int slot = takeSlot();
            if (slot < 0) return;
            int tracking = qmpTouch.trackingId();
            int x = QmpMultiTouch.abs(coordX(event.getX(pointerIndex), dst, width), width);
            int y = QmpMultiTouch.abs(coordY(event.getY(pointerIndex), dst, height), height);
            boolean first = slots.size() == 0;
            slots.put(pointerId, slot);
            trackingIds.put(pointerId, tracking);
            qmpTouch.begin(slot, tracking, x, y, first);
        }

        private void endGuestContact(int pointerId) {
            int index = slots.indexOfKey(pointerId);
            if (index < 0) return;
            boolean last = slots.size() == 1;
            qmpTouch.end(slots.valueAt(index), trackingIds.get(pointerId), last);
            slots.delete(pointerId);
            trackingIds.delete(pointerId);
        }

        private boolean guestContacts(MotionEvent event, RectF dst, int width, int height) {
            int action = event.getActionMasked();
            switch (action) {
                case MotionEvent.ACTION_DOWN:
                    slots.clear();
                    trackingIds.clear();
                    beginGuestContact(event, 0, dst, width, height);
                    return true;
                case MotionEvent.ACTION_POINTER_DOWN:
                    beginGuestContact(event, event.getActionIndex(), dst, width, height);
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (event.getEventTime() - lastQmpMoveAt < 30) return true;
                    lastQmpMoveAt = event.getEventTime();
                    for (int index = 0; index < event.getPointerCount(); index++) {
                        int id = event.getPointerId(index);
                        int slot = slots.get(id, -1);
                        if (slot < 0) continue;
                        int x = QmpMultiTouch.abs(coordX(event.getX(index), dst, width), width);
                        int y = QmpMultiTouch.abs(coordY(event.getY(index), dst, height), height);
                        qmpTouch.move(slot, trackingIds.get(id), x, y);
                    }
                    return true;
                case MotionEvent.ACTION_POINTER_UP:
                    endGuestContact(event.getPointerId(event.getActionIndex()));
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    // Cancel every slot, not only actionIndex, to avoid stuck
                    // fingers if Android cancels a multitouch gesture.
                    for (int i = slots.size() - 1; i >= 0; i--)
                        endGuestContact(slots.keyAt(i));
                    qmpGesture = false;
                    return true;
                default:
                    return true;
            }
        }

        @Override public boolean onTouchEvent(MotionEvent event) {
            final int action = event.getActionMasked();
            if (action != MotionEvent.ACTION_DOWN && action != MotionEvent.ACTION_UP
                    && action != MotionEvent.ACTION_MOVE
                    && action != MotionEvent.ACTION_POINTER_DOWN
                    && action != MotionEvent.ACTION_POINTER_UP
                    && action != MotionEvent.ACTION_CANCEL) return false;

            RfbClient c = client;
            if (c == null) return true;
            synchronized (framebufferLock) {
                if (framebuffer == null || framebuffer.isRecycled()) {
                    pressed = false;
                    return true;
                }
                RectF dst = fitted(framebuffer.getWidth(), framebuffer.getHeight());
                if (action == MotionEvent.ACTION_DOWN) {
                    // Pick one transport for the duration of the gesture.
                    qmpGesture = nativeTouchMode && qmpTouch != null && qmpTouch.isReady()
                            && dst.contains(event.getX(), event.getY());
                    if (qmpGesture) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (qmpGesture)
                    return guestContacts(event, dst, framebuffer.getWidth(), framebuffer.getHeight());
                switch (action) {
                    case MotionEvent.ACTION_DOWN:
                        twoFingerGesture = false;
                        ignoreUntilAllReleased = false;
                        scrollRemainderX = scrollRemainderY = 0;
                        if (!dst.contains(event.getX(), event.getY())) return true;
                        getParent().requestDisallowInterceptTouchEvent(true);
                        lastGuestX = coordX(event.getX(), dst, framebuffer.getWidth());
                        lastGuestY = coordY(event.getY(), dst, framebuffer.getHeight());
                        // Native Android gestures, without a visible mouse or virtual keyboard.
                        // Holding the primary RFB button maps tap, long-press and
                        // drag/swipe to guests that support mouse/tablet input.
                        pressed = true;
                        c.pointer(lastGuestX, lastGuestY, 1);
                        return true;
                    case MotionEvent.ACTION_POINTER_DOWN:
                        if (event.getPointerCount() >= 2) {
                            release(c); // never leave button #1 held during scrolling
                            twoFingerGesture = true;
                            lastCenterX = (event.getX(0) + event.getX(1)) / 2f;
                            lastCenterY = (event.getY(0) + event.getY(1)) / 2f;
                            scrollRemainderX = scrollRemainderY = 0;
                        }
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        if (twoFingerGesture && event.getPointerCount() >= 2) {
                            wheelFromTwoFingers(c, event);
                        } else if (pressed && !twoFingerGesture && !ignoreUntilAllReleased) {
                            lastGuestX = coordX(event.getX(), dst, framebuffer.getWidth());
                            lastGuestY = coordY(event.getY(), dst, framebuffer.getHeight());
                            c.pointer(lastGuestX, lastGuestY, 1);
                        }
                        return true;
                    case MotionEvent.ACTION_POINTER_UP:
                        // Do not synthesize an accidental tap from the finger that
                        // remains on screen after a two-finger gesture.
                        if (twoFingerGesture && event.getPointerCount() <= 2) {
                            twoFingerGesture = false;
                            ignoreUntilAllReleased = true;
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        release(c);
                        twoFingerGesture = false;
                        ignoreUntilAllReleased = false;
                        scrollRemainderX = scrollRemainderY = 0;
                        return true;
                    default:
                        return true;
                }
            }
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

        void sendChord(int keysym, boolean ctrl, boolean alt) {
            synchronized (outputLock) {
                if (out == null || !active || keysym == 0) return;
                try {
                    if (ctrl) writeKeyEvent(0xffe3, true);
                    if (alt) writeKeyEvent(0xffe9, true);
                    writeKeyEvent(keysym, true);
                    writeKeyEvent(keysym, false);
                    if (alt) writeKeyEvent(0xffe9, false);
                    if (ctrl) writeKeyEvent(0xffe3, false);
                    out.flush();
                } catch (IOException e) { close(); }
            }
        }

        private void writeKeyEvent(int sym, boolean down) throws IOException {
            out.writeByte(4);
            out.writeByte(down ? 1 : 0);
            out.writeShort(0);
            out.writeInt(sym);
        }

        void wheel(int x, int y, int mask) {
            // RFB wheel events are pulses: pressed then released, no held state.
            synchronized (outputLock) {
                if (out == null || !active) return;
                try {
                    out.writeByte(5); out.writeByte(mask);
                    out.writeShort(x); out.writeShort(y);
                    out.writeByte(5); out.writeByte(0);
                    out.writeShort(x); out.writeShort(y);
                    out.flush();
                } catch (IOException e) { close(); }
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

        void close() {
            active = false;
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        }
    }
}
