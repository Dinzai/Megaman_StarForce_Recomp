package io.dinzai.starforce;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.Presentation;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Bundle;
import android.view.Display;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Toast;

import java.io.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/** Plays Leo on the native runtime. Supports one or two physical displays (e.g. AYN Thor). */
public final class GameActivity extends Activity {
    private static final int AUDIO_RATE = 48000;
    private static final double DS_FPS = 59.8261;

    private SharedPreferences prefs;
    private final TouchControls controls = new TouchControls();
    private final GamepadMapper pad = new GamepadMapper();
    private final ConcurrentLinkedQueue<Runnable> commands = new ConcurrentLinkedQueue<>();

    private Target mainTarget, secondTarget;
    private Presentation presentation;
    private EmuThread emu;
    private volatile boolean menuOpen = false;
    private volatile boolean fastForward = false;

    // Touch state shared with the emulation thread.
    private volatile int touchKeys = 0;
    private volatile boolean dsTouching = false;
    private volatile int dsX = 0, dsY = 0;

    /** One drawing surface and its screen layout. */
    private final class Target implements SurfaceHolder.Callback, View.OnTouchListener {
        final SurfaceView view;
        final boolean primary;
        volatile SurfaceHolder holder;
        volatile ScreenLayout layout;
        volatile int width, height;

        Target(Context ctx, boolean primary) {
            this.primary = primary;
            view = new SurfaceView(ctx);
            view.getHolder().addCallback(this);
            view.setOnTouchListener(this);
            view.setFocusable(true);
        }

        @Override public void surfaceCreated(SurfaceHolder h) { holder = h; }
        @Override public void surfaceChanged(SurfaceHolder h, int f, int w, int hh) { holder = h; relayout(w, hh); }
        @Override public void surfaceDestroyed(SurfaceHolder h) { holder = null; }

        void relayout(int w, int h) {
            width = w; height = h;
            layout = ScreenLayout.compute(modeFor(this, w, h), w, h, prefs.getBoolean("integer_scale", false));
            if (primary) controls.layout(w, h, layout, getResources().getDisplayMetrics().density);
        }

        @Override public boolean onTouch(View v, MotionEvent e) {
            ScreenLayout l = layout;
            if (l == null) return true;
            int keys = 0;
            boolean touching = false;
            int tx = 0, ty = 0;
            int action = e.getActionMasked();
            for (int i = 0; i < e.getPointerCount(); i++) {
                boolean lifted = (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL)
                        || (action == MotionEvent.ACTION_POINTER_UP && i == e.getActionIndex());
                if (lifted) continue;
                float x = e.getX(i), y = e.getY(i);
                int hit = primary ? controls.hit(x, y) : 0;
                if (hit == TouchControls.MENU) {
                    if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) runOnUiThread(GameActivity.this::openMenu);
                    continue;
                }
                if (hit != 0) { keys |= hit; continue; }
                int[] p = l.toTouch(x, y);
                if (p != null && !touching) { touching = true; tx = p[0]; ty = p[1]; }
            }
            if (primary) touchKeys = keys;
            if (primary || secondTarget == this) {
                // Only the surface showing the bottom screen drives the DS touch panel.
                if (ScreenLayout.visible(l.bottom)) { dsTouching = touching; dsX = tx; dsY = ty; }
            }
            return true;
        }
    }

    private ScreenLayout.Mode modeFor(Target t, int w, int h) {
        if (secondTarget != null) return t.primary ? ScreenLayout.Mode.TOP_ONLY : ScreenLayout.Mode.BOTTOM_ONLY;
        String m = prefs.getString("layout", "auto");
        if (m.equals("stacked")) return ScreenLayout.Mode.STACKED;
        if (m.equals("side")) return ScreenLayout.Mode.SIDE_BY_SIDE;
        return ScreenLayout.autoMode(w, h);
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        pad.swapFaceButtons = prefs.getBoolean("swap_face", false);
        controls.visible = prefs.getBoolean("touch_controls", true);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (!RomImporter.isPlayable(getFilesDir())) {
            Toast.makeText(this, "Import the Leo ROM first.", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        mainTarget = new Target(this, true);
        setContentView(mainTarget.view);
        immersive();
        if (prefs.getBoolean("dual_screen", true)) attachSecondDisplay();
        emu = new EmuThread();
        emu.start();
    }

    private void immersive() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
    }

    /** Dual-screen devices: top screen on this display, bottom (touch) screen on the presentation display. */
    private void attachSecondDisplay() {
        DisplayManager dm = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] displays = dm.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) return;
        presentation = new Presentation(this, displays[0]) {
            @Override protected void onCreate(Bundle s) {
                super.onCreate(s);
                secondTarget = new Target(getContext(), false);
                setContentView(secondTarget.view);
            }
        };
        try {
            presentation.show();
            presentation.setOnDismissListener(d -> { secondTarget = null; presentation = null; relayoutAll(); });
            relayoutAll();
        } catch (WindowManager.InvalidDisplayException e) {
            presentation = null;
            secondTarget = null;
        }
    }

    private void relayoutAll() {
        Target m = mainTarget;
        if (m != null && m.width > 0) m.relayout(m.width, m.height);
        Target s = secondTarget;
        if (s != null && s.width > 0) s.relayout(s.width, s.height);
    }

    @Override public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) immersive();
    }

    @Override protected void onPause() {
        super.onPause();
        if (emu != null) emu.setPaused(true);
    }

    @Override protected void onResume() {
        super.onResume();
        if (emu != null && !menuOpen) emu.setPaused(false);
    }

    @Override protected void onDestroy() {
        if (emu != null) emu.shutdown();
        if (presentation != null) presentation.dismiss();
        super.onDestroy();
    }

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        int code = e.getKeyCode();
        if (code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_BUTTON_MODE) {
            if (e.getAction() == KeyEvent.ACTION_UP) openMenu();
            return true;
        }
        if (pad.onKey(code, e.getAction() == KeyEvent.ACTION_DOWN)) {
            if ((e.getSource() & InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD && controls.visible
                    && prefs.getBoolean("autohide_controls", true)) {
                controls.visible = false;
            }
            return true;
        }
        return super.dispatchKeyEvent(e);
    }

    @Override public boolean dispatchGenericMotionEvent(MotionEvent e) {
        if (pad.onMotion(e)) return true;
        return super.dispatchGenericMotionEvent(e);
    }

    // --- menu ---------------------------------------------------------------

    private void openMenu() {
        if (menuOpen || isFinishing()) return;
        menuOpen = true;
        emu.setPaused(true);
        String[] items = {
                "Resume",
                "Save state",
                "Load state",
                fastForward ? "Fast-forward: on" : "Fast-forward: off",
                controls.visible ? "Hide touch controls" : "Show touch controls",
                "Screen layout: " + prefs.getString("layout", "auto"),
                pad.swapFaceButtons ? "Face buttons: by label" : "Face buttons: by position",
                "Quit to library",
        };
        new AlertDialog.Builder(this)
                .setTitle("Star Force Recomp")
                .setItems(items, (d, which) -> {
                    switch (which) {
                        case 1: commands.add(() -> saveState(1)); break;
                        case 2: commands.add(() -> loadState(1)); break;
                        case 3: fastForward = !fastForward; break;
                        case 4:
                            controls.visible = !controls.visible;
                            prefs.edit().putBoolean("touch_controls", controls.visible).apply();
                            break;
                        case 5: {
                            String cur = prefs.getString("layout", "auto");
                            String next = cur.equals("auto") ? "stacked" : cur.equals("stacked") ? "side" : "auto";
                            prefs.edit().putString("layout", next).apply();
                            relayoutAll();
                            break;
                        }
                        case 6:
                            pad.swapFaceButtons = !pad.swapFaceButtons;
                            prefs.edit().putBoolean("swap_face", pad.swapFaceButtons).apply();
                            break;
                        case 7: finish(); break;
                        default: break;
                    }
                })
                .setOnDismissListener(d -> {
                    menuOpen = false;
                    pad.reset();
                    if (!isFinishing()) { emu.setPaused(false); immersive(); }
                })
                .show();
    }

    private void toast(String s) { runOnUiThread(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show()); }

    // Runs on the emulation thread.
    private void saveState(int slot) {
        byte[] st = emu.core.saveState();
        if (st == null) { toast("Save state failed"); return; }
        File f = RomImporter.stateFile(getFilesDir(), slot), tmp = new File(f.getPath() + ".tmp");
        try (OutputStream out = new GZIPOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)), 65536)) {
            out.write(st);
        } catch (IOException e) { toast("Save state failed: " + e.getMessage()); return; }
        if (tmp.renameTo(f)) toast("State saved"); else toast("Save state failed");
    }

    private void loadState(int slot) {
        File f = RomImporter.stateFile(getFilesDir(), slot);
        if (!f.isFile()) { toast("No saved state yet"); return; }
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(f)), 65536)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(24 << 20);
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            toast(emu.core.loadState(out.toByteArray()) ? "State loaded" : "State is incompatible");
        } catch (IOException e) { toast("Load state failed: " + e.getMessage()); }
    }

    // --- emulation thread ---------------------------------------------------

    private final class EmuThread extends Thread {
        NativeCore core;
        private final Object lock = new Object();
        private boolean paused = false, running = true;
        private final int[] pixels = new int[NativeCore.SCREEN_W * NativeCore.SCREEN_H * 2];
        private final short[] audio = new short[4096 * 2];
        private final Bitmap frame = Bitmap.createBitmap(NativeCore.SCREEN_W, NativeCore.SCREEN_H * 2, Bitmap.Config.ARGB_8888);
        private final Paint paint = new Paint();
        private final Rect srcTop = new Rect(0, 0, NativeCore.SCREEN_W, NativeCore.SCREEN_H);
        private final Rect srcBottom = new Rect(0, NativeCore.SCREEN_H, NativeCore.SCREEN_W, NativeCore.SCREEN_H * 2);
        private final Rect dst = new Rect();
        private AudioTrack track;

        EmuThread() { super("sfr-emu"); }

        void setPaused(boolean p) {
            synchronized (lock) { paused = p; lock.notifyAll(); }
        }

        void shutdown() {
            synchronized (lock) { running = false; lock.notifyAll(); }
            try { join(3000); } catch (InterruptedException ignored) { }
        }

        @Override public void run() {
            paint.setFilterBitmap(prefs.getBoolean("smooth", false));
            File storage = getFilesDir();
            ModManager mods = new ModManager(storage);
            LaunchConfig cfg = mods.launchConfig();
            core = NativeCore.create(storage.getPath());
            String err = core.load(RomImporter.romFile(storage).getPath(), RomImporter.saveFile(storage).getPath(),
                    cfg, prefs.getBoolean("jit", true), true, AUDIO_RATE);
            if (err != null) {
                core.close();
                runOnUiThread(() -> new AlertDialog.Builder(GameActivity.this).setTitle("Cannot start Leo")
                        .setMessage(err).setOnDismissListener(d -> finish()).setPositiveButton("OK", null).show());
                return;
            }
            if (!cfg.activeMods.isEmpty()) toast("Mods: " + String.join(", ", cfg.activeMods));
            for (String c : cfg.conflicts) toast("Mod conflict — " + c);
            startAudio();

            final long period = (long) (1_000_000_000L / DS_FPS);
            long next = System.nanoTime();
            long frames = 0;
            try {
                while (true) {
                    synchronized (lock) {
                        if (paused && running) {
                            flushSave();
                            if (track != null) track.pause();
                            while (paused && running) lock.wait();
                            if (track != null) track.play();
                            next = System.nanoTime();
                        }
                        if (!running) break;
                    }
                    Runnable cmd;
                    while ((cmd = commands.poll()) != null) cmd.run();

                    int keys = pad.keys() | touchKeys;
                    int n = core.runFrame(keys, dsTouching, dsX, dsY, pixels, audio);
                    if (n < 0) { runOnUiThread(GameActivity.this::finish); break; }
                    boolean ff = fastForward;
                    if (n > 0 && track != null && !ff) track.write(audio, 0, n * 2, AudioTrack.WRITE_NON_BLOCKING);
                    if (!ff || frames % 4 == 0) draw(keys);
                    if (++frames % 60 == 0) flushSave();

                    if (!ff) {
                        next += period;
                        long wait = next - System.nanoTime();
                        if (wait > 0) Thread.sleep(wait / 1_000_000L, (int) (wait % 1_000_000L));
                        else if (wait < -period * 4) next = System.nanoTime();  // fell behind: resync, don't spiral
                    } else {
                        next = System.nanoTime();
                    }
                }
            } catch (InterruptedException ignored) {
            } finally {
                flushSave();
                if (track != null) { track.stop(); track.release(); }
                core.close();
            }
        }

        private void startAudio() {
            int min = AudioTrack.getMinBufferSize(AUDIO_RATE, AudioFormat.CHANNEL_OUT_STEREO, AudioFormat.ENCODING_PCM_16BIT);
            try {
                track = new AudioTrack.Builder()
                        .setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME)
                                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                        .setAudioFormat(new AudioFormat.Builder().setSampleRate(AUDIO_RATE)
                                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO).setEncoding(AudioFormat.ENCODING_PCM_16BIT).build())
                        .setBufferSizeInBytes(Math.max(min, AUDIO_RATE / 60 * 4 * 6))
                        .setTransferMode(AudioTrack.MODE_STREAM)
                        .build();
                track.play();
            } catch (Exception e) {
                track = null;
                toast("Audio unavailable: " + e.getMessage());
            }
        }

        private void flushSave() {
            if (core == null) return;
            byte[] save = core.takeDirtySave();
            if (save == null) return;
            File f = RomImporter.saveFile(getFilesDir()), tmp = new File(f.getPath() + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(save);
                out.getFD().sync();
            } catch (IOException e) { toast("Could not write save: " + e.getMessage()); return; }
            if (!tmp.renameTo(f)) toast("Could not write save");
        }

        private void draw(int keys) {
            frame.setPixels(pixels, 0, NativeCore.SCREEN_W, 0, 0, NativeCore.SCREEN_W, NativeCore.SCREEN_H * 2);
            drawTarget(mainTarget, keys);
            Target s = secondTarget;
            if (s != null) drawTarget(s, keys);
        }

        private void drawTarget(Target t, int keys) {
            SurfaceHolder h = t.holder;
            ScreenLayout l = t.layout;
            if (h == null || l == null) return;
            Canvas c;
            try { c = h.getSurface().isValid() ? h.lockHardwareCanvas() : null; }
            catch (Exception e) { c = null; }
            if (c == null) return;
            try {
                c.drawColor(Color.BLACK);
                if (ScreenLayout.visible(l.top)) { dst.set(l.top[0], l.top[1], l.top[2], l.top[3]); c.drawBitmap(frame, srcTop, dst, paint); }
                if (ScreenLayout.visible(l.bottom)) { dst.set(l.bottom[0], l.bottom[1], l.bottom[2], l.bottom[3]); c.drawBitmap(frame, srcBottom, dst, paint); }
                if (t.primary) controls.draw(c, keys);
            } finally {
                h.unlockCanvasAndPost(c);
            }
        }
    }
}
