package io.dinzai.starforce;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;

/** On-screen DS buttons. Positions are recomputed from the surface size and layout. */
final class TouchControls {
    static final int MENU = 1 << 20;

    private static final class Button {
        final String label; final int key; final RectF r = new RectF(); final boolean round;
        Button(String label, int key, boolean round) { this.label = label; this.key = key; this.round = round; }
    }

    private final Button[] buttons = {
            new Button("A", NativeCore.KEY_A, true), new Button("B", NativeCore.KEY_B, true),
            new Button("X", NativeCore.KEY_X, true), new Button("Y", NativeCore.KEY_Y, true),
            new Button("L", NativeCore.KEY_L, false), new Button("R", NativeCore.KEY_R, false),
            new Button("START", NativeCore.KEY_START, false), new Button("SELECT", NativeCore.KEY_SELECT, false),
            new Button("☰", MENU, false),
    };
    private final RectF dpad = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), stroke = new Paint(Paint.ANTI_ALIAS_FLAG), text = new Paint(Paint.ANTI_ALIAS_FLAG);
    volatile boolean visible = true;

    TouchControls() {
        fill.setColor(Color.argb(70, 255, 255, 255));
        stroke.setColor(Color.argb(150, 255, 255, 255));
        stroke.setStyle(Paint.Style.STROKE);
        text.setColor(Color.argb(200, 255, 255, 255));
        text.setTextAlign(Paint.Align.CENTER);
    }

    void layout(int w, int h, ScreenLayout screens, float density) {
        float unit = Math.min(w, h) / 9f;
        if (unit > 72 * density) unit = 72 * density;
        stroke.setStrokeWidth(Math.max(2, density * 1.5f));
        text.setTextSize(unit * 0.38f);
        boolean usesFree = ScreenLayout.visible(screens.free) && (screens.free[3] - screens.free[1]) > unit * 3.2f;
        float bandTop = usesFree ? screens.free[1] : h - unit * 4.2f;
        float bandBottom = h;
        float cy = (bandTop + bandBottom) / 2f;
        float margin = unit * 0.4f;
        // D-pad on the left, face buttons on the right.
        float d = unit * 3f;
        dpad.set(margin, cy - d / 2, margin + d, cy + d / 2);
        float fx = w - margin - d / 2, r = unit * 0.55f;
        place(0, fx + unit, cy, r);        // A right
        place(1, fx, cy + unit, r);        // B bottom
        place(2, fx, cy - unit, r);        // X top
        place(3, fx - unit, cy, r);        // Y left
        float shoulderY = usesFree ? bandTop + unit * 0.2f : unit * 0.3f;
        buttons[4].r.set(margin, shoulderY, margin + unit * 2.2f, shoulderY + unit * 0.8f);
        buttons[5].r.set(w - margin - unit * 2.2f, shoulderY, w - margin, shoulderY + unit * 0.8f);
        float by = bandBottom - unit * 0.9f;
        buttons[6].r.set(w / 2f + unit * 0.2f, by, w / 2f + unit * 2.2f, by + unit * 0.6f);
        buttons[7].r.set(w / 2f - unit * 2.2f, by, w / 2f - unit * 0.2f, by + unit * 0.6f);
        float my = usesFree ? bandTop + unit * 0.2f : unit * 0.3f;
        buttons[8].r.set(w / 2f - unit * 0.5f, my, w / 2f + unit * 0.5f, my + unit * 0.8f);
    }

    private void place(int i, float cx, float cy, float r) { buttons[i].r.set(cx - r, cy - r, cx + r, cy + r); }

    /** Keys (and MENU) under a point; 0 if none. Diagonals are allowed on the D-pad. */
    int hit(float x, float y) {
        if (!visible) return 0;
        if (dpad.contains(x, y)) {
            float cx = dpad.centerX(), cy = dpad.centerY(), dz = dpad.width() * 0.12f;
            int k = 0;
            if (x < cx - dz) k |= NativeCore.KEY_LEFT; else if (x > cx + dz) k |= NativeCore.KEY_RIGHT;
            if (y < cy - dz) k |= NativeCore.KEY_UP; else if (y > cy + dz) k |= NativeCore.KEY_DOWN;
            return k;
        }
        for (Button b : buttons) {
            RectF r = b.r;
            float pad = r.width() * 0.15f;
            if (x >= r.left - pad && x <= r.right + pad && y >= r.top - pad && y <= r.bottom + pad) return b.key;
        }
        return 0;
    }

    void draw(Canvas c, int pressed) {
        if (!visible) return;
        float third = dpad.width() / 3f;
        RectF h = new RectF(dpad.left, dpad.top + third, dpad.right, dpad.bottom - third);
        RectF v = new RectF(dpad.left + third, dpad.top, dpad.right - third, dpad.bottom);
        c.drawRoundRect(h, third * 0.3f, third * 0.3f, fill);
        c.drawRoundRect(v, third * 0.3f, third * 0.3f, fill);
        c.drawRoundRect(h, third * 0.3f, third * 0.3f, stroke);
        c.drawRoundRect(v, third * 0.3f, third * 0.3f, stroke);
        for (Button b : buttons) {
            boolean down = (pressed & b.key) != 0;
            fill.setAlpha(down ? 160 : 70);
            if (b.round) {
                c.drawOval(b.r, fill);
                c.drawOval(b.r, stroke);
            } else {
                float rr = b.r.height() / 2;
                c.drawRoundRect(b.r, rr, rr, fill);
                c.drawRoundRect(b.r, rr, rr, stroke);
            }
            float ty = b.r.centerY() - (text.descent() + text.ascent()) / 2;
            float size = text.getTextSize();
            if (b.label.length() > 2) text.setTextSize(size * 0.6f);
            c.drawText(b.label, b.r.centerX(), ty, text);
            text.setTextSize(size);
        }
        fill.setAlpha(70);
    }
}
