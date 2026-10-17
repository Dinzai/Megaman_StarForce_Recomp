package io.dinzai.starforce;

/**
 * Where the two DS screens go on a surface. Pure arithmetic so it can be unit tested.
 * Rects are {left, top, right, bottom}; an all-zero rect means "not shown here".
 */
final class ScreenLayout {
    enum Mode { STACKED, SIDE_BY_SIDE, TOP_ONLY, BOTTOM_ONLY }

    final int[] top = new int[4], bottom = new int[4];
    /** Free area below/beside the screens where touch controls fit without overlap (may be empty). */
    final int[] free = new int[4];
    final Mode mode;

    private ScreenLayout(Mode mode) { this.mode = mode; }

    static Mode autoMode(int w, int h) { return w > h ? Mode.SIDE_BY_SIDE : Mode.STACKED; }

    static ScreenLayout compute(Mode mode, int w, int h, boolean integerScale) {
        ScreenLayout l = new ScreenLayout(mode);
        final int sw = NativeCore.SCREEN_W, sh = NativeCore.SCREEN_H;
        switch (mode) {
            case STACKED: {
                float s = scale(Math.min(w / (float) sw, h / (float) (sh * 2)), integerScale);
                int dw = Math.round(sw * s), dh = Math.round(sh * s);
                int x = (w - dw) / 2;
                set(l.top, x, 0, x + dw, dh);
                set(l.bottom, x, dh, x + dw, dh * 2);
                set(l.free, 0, dh * 2, w, h);
                break;
            }
            case SIDE_BY_SIDE: {
                float s = scale(Math.min(w / (float) (sw * 2), h / (float) sh), integerScale);
                int dw = Math.round(sw * s), dh = Math.round(sh * s);
                int x = (w - dw * 2) / 2, y = (h - dh) / 2;
                set(l.top, x, y, x + dw, y + dh);
                set(l.bottom, x + dw, y, x + dw * 2, y + dh);
                set(l.free, 0, 0, 0, 0);
                break;
            }
            default: {
                float s = scale(Math.min(w / (float) sw, h / (float) sh), integerScale);
                int dw = Math.round(sw * s), dh = Math.round(sh * s);
                int x = (w - dw) / 2, y = (h - dh) / 2;
                set(mode == Mode.TOP_ONLY ? l.top : l.bottom, x, y, x + dw, y + dh);
                break;
            }
        }
        return l;
    }

    private static float scale(float s, boolean integer) {
        return integer && s >= 1f ? (float) Math.floor(s) : s;
    }

    private static void set(int[] r, int l, int t, int rr, int b) { r[0] = l; r[1] = t; r[2] = rr; r[3] = b; }

    static boolean visible(int[] r) { return r[2] > r[0] && r[3] > r[1]; }

    /** Maps a surface point to DS bottom-screen coordinates, or null if outside. */
    int[] toTouch(float x, float y) {
        if (!visible(bottom) || x < bottom[0] || x >= bottom[2] || y < bottom[1] || y >= bottom[3]) return null;
        int tx = (int) ((x - bottom[0]) * NativeCore.SCREEN_W / (bottom[2] - bottom[0]));
        int ty = (int) ((y - bottom[1]) * NativeCore.SCREEN_H / (bottom[3] - bottom[1]));
        return new int[]{Math.min(tx, NativeCore.SCREEN_W - 1), Math.min(ty, NativeCore.SCREEN_H - 1)};
    }
}
