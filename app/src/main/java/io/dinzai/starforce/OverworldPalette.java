package io.dinzai.starforce;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Field (overworld) Mega Man recolours that follow the active transformation.
 * Java port of tools/overworld.py; both produce identical bytes.
 *
 * The field draws wave-changed Mega Man as 2D sprites from
 * datbin/com/fieldobj.bin. Each of his sprite sets carries the same 16-colour
 * palette; the mod rewrites it so the blue armour ramp and the dark hair/crest
 * accent take the form's colours. Offsets are for the verified US ROMs and are
 * checked before use. No game data is embedded.
 */
final class OverworldPalette {
    static final String FILE = "datbin/com/fieldobj.bin";

    private static final int[][] LEO = {{73, 0xc92c}, {136, 0x16e0}, {154, 0x1a88}, {157, 0x2e20}, {212, 0xa40},
            {263, 0x930}, {264, 0x2a4}, {292, 0x76c}, {297, 0x738}, {345, 0x1e4}};
    private static final int[][] ZN = {{58, 0x13410}, {137, 0x2c28}, {138, 0x57a8}, {237, 0x1440}, {238, 0x2840},
            {301, 0xe314}, {302, 0x1c454}, {335, 0x1aa0}, {336, 0x34e0}, {371, 0x1450}, {372, 0x2850}, {407, 0x16c},
            {408, 0x2ac}, {486, 0xb34}, {491, 0x198c}, {492, 0x32cc}, {514, 0xafc}, {529, 0xd78}, {539, 0x3a8},
            {554, 0xbb4}, {559, 0x233c}, {580, 0x3ac}};

    // body dark, mid, light; accent dark, light
    private static final Map<String, int[][]> COLOURS = new LinkedHashMap<>();
    static {
        COLOURS.put("pegasus", new int[][]{{30, 70, 120}, {110, 190, 240}, {235, 250, 255}, {60, 100, 160}, {170, 220, 255}});
        COLOURS.put("leo", new int[][]{{90, 20, 0}, {230, 90, 20}, {255, 215, 130}, {70, 20, 80}, {170, 70, 190}});
        COLOURS.put("dragon", new int[][]{{10, 60, 40}, {40, 170, 110}, {190, 250, 200}, {100, 80, 20}, {220, 190, 60}});
        COLOURS.put("zerker", new int[][]{{95, 75, 10}, {225, 185, 35}, {255, 250, 215}, {45, 45, 45}, {140, 140, 140}});
        COLOURS.put("ninja", new int[][]{{10, 60, 30}, {40, 160, 80}, {175, 240, 185}, {120, 20, 30}, {230, 60, 60}});
        COLOURS.put("saurian", new int[][]{{75, 10, 10}, {205, 50, 30}, {255, 175, 120}, {90, 60, 20}, {205, 150, 60}});
        COLOURS.put("tribe-king", new int[][]{{80, 10, 20}, {210, 40, 50}, {255, 195, 195}, {90, 90, 100}, {215, 215, 225}});
        COLOURS.put("black-ace", new int[][]{{12, 12, 18}, {58, 58, 68}, {155, 155, 170}, {110, 0, 10}, {230, 30, 40}});
        COLOURS.put("red-joker", new int[][]{{60, 0, 10}, {185, 20, 30}, {255, 125, 125}, {20, 20, 20}, {85, 85, 85}});
    }

    private OverworldPalette() {}

    static boolean supports(GameProfile g, String form) {
        return entries(g) != null && COLOURS.containsKey(form);
    }

    private static int[][] entries(GameProfile g) {
        if (g == GameProfile.LEO) return LEO;
        if (g == GameProfile.ZERKER_NINJA) return ZN;
        return null;
    }

    private static double[] rgb(int c) {
        int r = c & 31, g = c >> 5 & 31, b = c >> 10 & 31;
        return new double[]{r << 3 | r >> 2, g << 3 | g >> 2, b << 3 | b >> 2};
    }

    /** Python's round() (half to even), so both ports produce the same bytes. */
    private static int pyRound(double v) { return (int) Math.rint(v); }

    private static int bgr555(double[] c) {
        return (pyRound(c[0]) >> 3) | (pyRound(c[1]) >> 3) << 5 | (pyRound(c[2]) >> 3) << 10;
    }

    private static double lum(double[] c) { return 0.299 * c[0] + 0.587 * c[1] + 0.114 * c[2]; }

    private static double[] lerp(int[] a, int[] b, double t) {
        return new double[]{a[0] + (b[0] - a[0]) * t, a[1] + (b[1] - a[1]) * t, a[2] + (b[2] - a[2]) * t};
    }

    /** colorsys.rgb_to_hsv on 0-1 values: {h (0-1), s, v}. */
    private static double[] hsv(double[] c) {
        double r = c[0] / 255, g = c[1] / 255, b = c[2] / 255;
        double max = Math.max(r, Math.max(g, b)), min = Math.min(r, Math.min(g, b));
        double v = max;
        if (min == max) return new double[]{0, 0, v};
        double s = (max - min) / max;
        double rc = (max - r) / (max - min), gc = (max - g) / (max - min), bc = (max - b) / (max - min);
        double h;
        if (r == max) h = bc - gc;
        else if (g == max) h = 2.0 + rc - bc;
        else h = 4.0 + gc - rc;
        h = (h / 6.0) % 1.0;
        if (h < 0) h += 1.0;
        return new double[]{h, s, v};
    }

    static boolean looksLikePalette(byte[] d, int off) {
        if (off < 0 || off + 32 > d.length || d[off] != 0 || d[off + 1] != 0) return false;
        for (int i = 0; i < 16; i++) if ((FormBuilder.u16(d, off + i * 2) & 0x8000) != 0) return false;
        return true;
    }

    /** Recolours the 16-colour palette at `off` in place. */
    static void recolour(byte[] d, int off, String form) {
        int[] cols = new int[16];
        for (int i = 0; i < 16; i++) cols[i] = FormBuilder.u16(d, off + i * 2);
        java.util.List<Integer> body = new java.util.ArrayList<>(), accent = new java.util.ArrayList<>();
        for (int i = 1; i < 16; i++) {
            double[] h = hsv(rgb(cols[i]));
            double deg = h[0] * 360;
            if (deg >= 190 && deg <= 235 && h[1] >= 0.3) body.add(i);
            else if (deg >= 290 && deg <= 345 && h[1] >= 0.4 && h[2] < 0.5) accent.add(i);
        }
        int[][] c = COLOURS.get(form);
        apply(cols, body, new int[][]{c[0], c[1], c[2]});
        apply(cols, accent, new int[][]{c[3], c[4]});
        for (int i = 0; i < 16; i++) FormBuilder.put16(d, off + i * 2, cols[i]);
    }

    private static void apply(int[] cols, List<Integer> group, int[][] ramp) {
        if (group.isEmpty()) return;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        double[] ls = new double[group.size()];
        for (int k = 0; k < ls.length; k++) {
            ls[k] = lum(rgb(cols[group.get(k)]));
            lo = Math.min(lo, ls[k]);
            hi = Math.max(hi, ls[k]);
        }
        for (int k = 0; k < ls.length; k++) {
            double t = hi == lo ? 0.5 : (ls[k] - lo) / (hi - lo);
            double[] out;
            if (ramp.length == 3) out = t < 0.5 ? lerp(ramp[0], ramp[1], t * 2) : lerp(ramp[1], ramp[2], (t - 0.5) * 2);
            else out = lerp(ramp[0], ramp[1], t);
            cols[group.get(k)] = bgr555(out);
        }
    }

    /** "fieldobj.bin@N" -> recoloured entry, built from the user's ROM. */
    static Map<String, byte[]> assets(File rom, GameProfile g, String form) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        int[][] list = entries(g);
        if (list == null || !COLOURS.containsKey(form)) return out;
        List<byte[]> archive = FormBuilder.archiveEntries(FormBuilder.readRomFile(rom, FILE));
        for (int[] e : list) {
            if (e[0] >= archive.size()) throw new IOException("fieldobj.bin is too short; unsupported ROM?");
            byte[] d = archive.get(e[0]).clone();
            if (!looksLikePalette(d, e[1])) throw new IOException("No Mega Man palette in fieldobj.bin entry " + e[0]);
            recolour(d, e[1], form);
            out.put(FILE + "@" + e[0], d);
        }
        return out;
    }
}
