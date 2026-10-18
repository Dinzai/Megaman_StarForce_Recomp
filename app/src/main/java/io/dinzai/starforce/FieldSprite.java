package io.dinzai.starforce;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Mega Man's field (overworld) sprite set, as stored in a fieldobj.bin entry, and
 * a custom sprite-sheet importer for it. See docs/SPRITES.md.
 *
 * Container: four u32 section offsets (A, B, C, D).
 *   A: u16 tile budget per frame, u16 tile count, u32 offset of the 4bpp tiles
 *      (relative to A), then one (u16 tile count, u16 first tile) run per frame.
 *   B: u16, u16, then 16-colour palettes (BGR555). Palette 0 is Mega Man's.
 *   C: u32 animation count + offsets; each animation is (u8 frame, u8 frames to
 *      hold, u16 flags) steps. Left untouched.
 *   D: u32 frame count + offsets; each frame is a list of 8-byte pieces:
 *      u8 tile unit (2 tiles) in the frame's run, s8 x, s8 y, u8 OAM size,
 *      u8 OAM shape, u8 flip (1 = h, 2 = v), u8 last-piece flag, u8 0.
 *
 * Frames 0-84 have the same meaning in Leo and Zerker × Ninja: idle poses, the
 * walk cycles for the four diagonals and the run cycles for eight directions.
 * A sprite sheet supplies one row per direction (N, NE, E, SE, S, SW, W, NW)
 * with an idle frame followed by six movement frames.
 */
final class FieldSprite {
    // NDS OAM sizes by [shape][size] -> {w, h}
    private static final int[][][] OAM = {
            {{8, 8}, {16, 16}, {32, 32}, {64, 64}},
            {{16, 8}, {32, 8}, {32, 16}, {64, 32}},
            {{8, 16}, {8, 32}, {16, 32}, {32, 64}}};

    // {shape, size}, biggest first
    static final int[][] ORDER = {{0, 2}, {1, 2}, {2, 2}, {0, 1}, {1, 1}, {2, 1}, {1, 0}, {2, 0}, {0, 0}};

    /** Idle frame per direction N, NE, E, SE, S, SW, W, NW, and the blink frames that follow some of them. */
    static final int[] IDLE = {0, 1, 2, 4, 6, 8, 10, 12};
    static final int[] BLINK_OF = {-1, -1, 3, 5, 7, 9, 11, -1};
    /** First frame of the 6-frame walk cycles: they exist for the diagonals NE, SE, SW, NW. */
    static final int[] WALK_DIR = {1, 3, 5, 7}, WALK_FIRST = {13, 19, 25, 31};
    /** First frame of the 6-frame run cycles, one per direction. */
    static final int RUN_FIRST = 37;

    /** fieldobj.bin entries holding Mega Man's main field sprite set, per game. */
    static int[] mainEntries(GameProfile g) {
        if (g == GameProfile.LEO) return new int[]{73};
        if (g == GameProfile.ZERKER_NINJA) return new int[]{58, 301, 302};
        return new int[0];
    }

    static final int TARGET_HEIGHT = 46;

    // --- stored custom sheets: files/sprites/<game>/<form>.sheet (gzip: int w, int h, w*h ARGB) ---

    static File sheetFile(File storage, GameProfile g, String form) {
        return new File(storage, "sprites/" + g.id + "/" + form + ".sheet");
    }

    static void saveSheet(File storage, GameProfile g, String form, int[] argb, int w, int h) throws IOException {
        fromSheet(argb, w, h, TARGET_HEIGHT);  // validate before storing
        File f = sheetFile(storage, g, form), tmp = new File(f.getPath() + ".tmp");
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create sprite folder");
        try (java.io.DataOutputStream out = new java.io.DataOutputStream(new java.util.zip.GZIPOutputStream(new java.io.FileOutputStream(tmp)))) {
            out.writeInt(w); out.writeInt(h);
            for (int v : argb) out.writeInt(v);
        }
        if (!tmp.renameTo(f)) throw new IOException("Cannot save sprite sheet");
    }

    /** "fieldobj.bin@N" -> custom sprite set built from the stored sheet; empty if none is stored. */
    static java.util.Map<String, byte[]> assets(File storage, GameProfile g, String form) throws IOException {
        java.util.Map<String, byte[]> out = new java.util.LinkedHashMap<>();
        File f = sheetFile(storage, g, form);
        if (!f.isFile() || mainEntries(g).length == 0) return out;
        int w, h; int[] argb;
        try (java.io.DataInputStream in = new java.io.DataInputStream(new java.util.zip.GZIPInputStream(new java.io.FileInputStream(f)))) {
            w = in.readInt(); h = in.readInt();
            if (w <= 0 || h <= 0 || (long) w * h > 16_000_000L) throw new IOException("Bad sprite sheet");
            argb = new int[w * h];
            for (int i = 0; i < argb.length; i++) argb[i] = in.readInt();
        }
        Object[] sheet = fromSheet(argb, w, h, TARGET_HEIGHT);
        List<byte[]> archive = FormBuilder.archiveEntries(FormBuilder.readRomFile(RomImporter.romFile(storage, g), OverworldPalette.FILE));
        for (int e : mainEntries(g))
            out.put(OverworldPalette.FILE + "@" + e, new FieldSprite(archive.get(e)).withPoses((Pose[][]) sheet[0], (int[]) sheet[1]));
        return out;
    }

    final byte[] data;
    final int a, b, c, d, budget, frames, tileOffset;
    final int[][] runs;          // per frame {count, start}
    final List<byte[]> pieces;   // per frame: raw 8-byte pieces concatenated

    FieldSprite(byte[] data) throws IOException {
        this.data = data;
        if (data.length < 0x20) throw new IOException("Not a field sprite");
        a = (int) FormBuilder.u32(data, 0); b = (int) FormBuilder.u32(data, 4);
        c = (int) FormBuilder.u32(data, 8); d = (int) FormBuilder.u32(data, 12);
        if (!(a < b && b < c && c < d && d < data.length)) throw new IOException("Not a field sprite");
        budget = FormBuilder.u16(data, a);
        tileOffset = (int) FormBuilder.u32(data, a + 4);
        frames = (int) FormBuilder.u32(data, d);
        if (frames < 85 || frames > 1024 || tileOffset != 8 + 4 * frames) throw new IOException("Unexpected field sprite layout");
        runs = new int[frames][];
        for (int i = 0; i < frames; i++) runs[i] = new int[]{FormBuilder.u16(data, a + 8 + 4 * i), FormBuilder.u16(data, a + 10 + 4 * i)};
        pieces = new ArrayList<>();
        for (int i = 0; i < frames; i++) {
            int s = d + (int) FormBuilder.u32(data, d + 4 + 4 * i);
            int e = i + 1 < frames ? d + (int) FormBuilder.u32(data, d + 8 + 4 * i) : data.length;
            if (s > e || e > data.length || (e - s) % 8 != 0) throw new IOException("Bad frame table");
            pieces.add(Arrays.copyOfRange(data, s, e));
        }
    }

    int tileCount() { return FormBuilder.u16(data, a + 2); }

    byte[] tile(int index) { int o = a + tileOffset + index * 32; return Arrays.copyOfRange(data, o, o + 32); }

    int[] palette(int p) {
        int[] out = new int[16];
        for (int i = 0; i < 16; i++) out[i] = FormBuilder.u16(data, b + 4 + p * 32 + i * 2);
        return out;
    }

    int paletteCount() { return (c - b - 4) / 32; }

    /** The shadow piece of frame 0 (the one drawn only with colour 1) and its two tiles. */
    private Object[] shadow() throws IOException {
        byte[] p = pieces.get(0);
        for (int k = 0; k < p.length; k += 8) {
            int unit = p[k] & 0xFF, size = p[k + 3], shape = p[k + 4];
            if (shape != 1 || size != 0) continue;  // 16x8
            int t = runs[0][1] + unit * 2;
            byte[] t0 = tile(t), t1 = tile(t + 1);
            boolean only1 = true;
            for (byte[] tt : new byte[][]{t0, t1}) for (byte x : tt) {
                int lo = x & 15, hi = (x >> 4) & 15;
                if ((lo != 0 && lo != 1) || (hi != 0 && hi != 1)) only1 = false;
            }
            if (only1) return new Object[]{Arrays.copyOfRange(p, k, k + 8), t0, t1};
        }
        throw new IOException("No shadow piece in frame 0");
    }

    // --- building a replacement -------------------------------------------------------

    /** An indexed image (0 = transparent) placed with its bottom centre at the sprite's feet. */
    static final class Pose {
        final int w, h;
        final byte[] px;
        Pose(int w, int h, byte[] px) { this.w = w; this.h = h; this.px = px; }
    }

    /**
     * Returns a new container where frames 0-84 are drawn from `poses[dir][col]`
     * (col 0 idle, 1-6 movement) with palette 0 (and the other palettes) set to
     * `colours` (index 1 keeps the original shadow colour).
     */
    byte[] withPoses(Pose[][] poses, int[] colours) throws IOException {
        Object[] sh = shadow();
        byte[] shadowPiece = (byte[]) sh[0];
        List<byte[]> tiles = new ArrayList<>();
        for (int i = 0; i < tileCount(); i++) tiles.add(tile(i));
        int[][] newRuns = new int[frames][];
        List<byte[]> newPieces = new ArrayList<>(pieces);
        for (int i = 0; i < frames; i++) newRuns[i] = runs[i].clone();

        java.util.Map<String, int[]> built = new java.util.HashMap<>();  // pose -> {count, start, pieceIndex}
        List<byte[]> builtPieces = new ArrayList<>();
        for (int f = 0; f <= 84; f++) {
            int[] src = sourceOf(f);
            String key = src[0] + "," + src[1];
            int[] done = built.get(key);
            if (done == null) {
                Pose pose = poses[src[0]][src[1]];
                List<byte[]> frameTiles = new ArrayList<>();
                ByteArrayOutputStream list = new ByteArrayOutputStream();
                // shadow first (it is listed first in the original frames), then the body
                byte[] sp = shadowPiece.clone();
                byte[][] body = encodePose(pose, frameTiles);
                sp[0] = (byte) (frameTiles.size() / 2);
                frameTiles.add((byte[]) sh[1]);
                frameTiles.add((byte[]) sh[2]);
                sp[6] = 0;
                list.write(sp, 0, 8);
                for (byte[] p : body) list.write(p, 0, 8);
                byte[] rec = list.toByteArray();
                rec[rec.length - 2] = 1;   // flag 1 marks the last piece of a frame
                list.reset();
                list.write(rec, 0, rec.length);
                if (frameTiles.size() > budget)
                    throw new IOException("Sprite frame needs " + frameTiles.size() + " tiles; this game allows " + budget);
                int start = tiles.size();
                tiles.addAll(frameTiles);
                done = new int[]{frameTiles.size(), start, builtPieces.size()};
                builtPieces.add(list.toByteArray());
                built.put(key, done);
            }
            newRuns[f] = new int[]{done[0], done[1]};
            newPieces.set(f, builtPieces.get(done[2]));
        }
        if (tiles.size() > 0xFFFF) throw new IOException("Too many tiles");
        return assemble(tiles, newRuns, newPieces, colours);
    }

    /** {direction, column} that supplies frame f (0-84). */
    static int[] sourceOf(int f) {
        for (int dir = 0; dir < 8; dir++) if (IDLE[dir] == f || BLINK_OF[dir] == f) return new int[]{dir, 0};
        for (int k = 0; k < 4; k++) if (f >= WALK_FIRST[k] && f < WALK_FIRST[k] + 6) return new int[]{WALK_DIR[k], 1 + f - WALK_FIRST[k]};
        if (f >= RUN_FIRST && f < RUN_FIRST + 48) return new int[]{(f - RUN_FIRST) / 6, 1 + (f - RUN_FIRST) % 6};
        throw new IllegalArgumentException("frame " + f);
    }

    /**
     * Cuts a pose into OAM pieces. Appends the pieces' tiles (each piece starting
     * on an even tile) to `out` and returns the 8-byte piece records.
     */
    static byte[][] encodePose(Pose p, List<byte[]> out) {
        int gw = (p.w + 7) / 8, gh = (p.h + 7) / 8;
        int ox = -(gw * 8) / 2, oy = 3 - gh * 8;   // feet (bottom) at y = +3, centred on x = 0
        int padX = (gw * 8 - p.w) / 2, padY = gh * 8 - p.h;
        boolean[][] used = new boolean[gh][gw];
        boolean[][] filled = new boolean[gh][gw];
        for (int ty = 0; ty < gh; ty++)
            for (int tx = 0; tx < gw; tx++) filled[ty][tx] = !tileEmpty(p, tx, ty, padX, padY);
        List<byte[]> pieces = new ArrayList<>();
        int[][] order = ORDER;
        for (int[] ss : order) {
            int w = OAM[ss[0]][ss[1]][0] / 8, h = OAM[ss[0]][ss[1]][1] / 8;
            for (int ty = 0; ty + h <= gh; ty++)
                for (int tx = 0; tx + w <= gw; tx++) {
                    int full = 0, free = 0;
                    for (int y = ty; y < ty + h; y++)
                        for (int x = tx; x < tx + w; x++) { if (!used[y][x]) free++; if (filled[y][x] && !used[y][x]) full++; }
                    if (free != w * h || full == 0) continue;
                    // a big piece must be mostly filled; small ones just need one filled tile
                    if (w * h >= 4 && full * 4 < w * h * 3) continue;
                    for (int y = ty; y < ty + h; y++) for (int x = tx; x < tx + w; x++) used[y][x] = true;
                    if (out.size() % 2 == 1) out.add(new byte[32]);
                    int unit = out.size() / 2;
                    for (int y = ty; y < ty + h; y++) for (int x = tx; x < tx + w; x++) out.add(tileBytes(p, x, y, padX, padY));
                    byte[] rec = new byte[8];
                    rec[0] = (byte) unit;
                    rec[1] = (byte) (ox + tx * 8);
                    rec[2] = (byte) (oy + ty * 8);
                    rec[3] = (byte) ss[1];
                    rec[4] = (byte) ss[0];
                    pieces.add(rec);
                }
        }
        if (out.size() % 2 == 1) out.add(new byte[32]);
        return pieces.toArray(new byte[0][]);
    }

    private static int at(Pose p, int x, int y) {
        return x < 0 || y < 0 || x >= p.w || y >= p.h ? 0 : p.px[y * p.w + x] & 15;
    }

    private static boolean tileEmpty(Pose p, int tx, int ty, int padX, int padY) {
        for (int y = 0; y < 8; y++) for (int x = 0; x < 8; x++) if (at(p, tx * 8 + x - padX, ty * 8 + y - padY) != 0) return false;
        return true;
    }

    private static byte[] tileBytes(Pose p, int tx, int ty, int padX, int padY) {
        byte[] t = new byte[32];
        for (int y = 0; y < 8; y++)
            for (int x = 0; x < 8; x += 2) {
                int lo = at(p, tx * 8 + x - padX, ty * 8 + y - padY), hi = at(p, tx * 8 + x + 1 - padX, ty * 8 + y - padY);
                t[y * 4 + x / 2] = (byte) (lo | hi << 4);
            }
        return t;
    }

    private byte[] assemble(List<byte[]> tiles, int[][] newRuns, List<byte[]> newPieces, int[] colours) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = new byte[16];
        out.write(head, 0, 16);
        // A
        int na = 16;
        byte[] ah = new byte[8 + 4 * frames];
        FormBuilder.put16(ah, 0, budget);
        FormBuilder.put16(ah, 2, tiles.size());
        FormBuilder.put32(ah, 4, 8 + 4 * frames);
        for (int i = 0; i < frames; i++) { FormBuilder.put16(ah, 8 + 4 * i, newRuns[i][0]); FormBuilder.put16(ah, 10 + 4 * i, newRuns[i][1]); }
        // keep whatever sits between the 16-byte header and A (normally nothing beyond the u32 at 0x10)
        byte[] pre = Arrays.copyOfRange(data, 16, a);
        out.write(pre, 0, pre.length);
        na = 16 + pre.length;
        out.write(ah, 0, ah.length);
        for (byte[] t : tiles) out.write(t, 0, 32);
        // B: palettes
        int nb = out.size();
        byte[] pal = Arrays.copyOfRange(data, b, c);
        int[] orig = palette(0);
        for (int p = 0; p < paletteCount(); p++)
            for (int i = 0; i < 16; i++)
                FormBuilder.put16(pal, 4 + p * 32 + i * 2, i == 1 ? orig[1] : i == 0 ? orig[0] : colours[i]);
        out.write(pal, 0, pal.length);
        // C: animations unchanged
        int nc = out.size();
        out.write(data, c, d - c);
        // D: frames
        int nd = out.size();
        byte[] dh = new byte[4 + 4 * frames];
        FormBuilder.put32(dh, 0, frames);
        int at = dh.length;
        for (int i = 0; i < frames; i++) { FormBuilder.put32(dh, 4 + 4 * i, at); at += newPieces.get(i).length; }
        out.write(dh, 0, dh.length);
        for (byte[] p : newPieces) out.write(p, 0, p.length);
        byte[] res = out.toByteArray();
        FormBuilder.put32(res, 0, na); FormBuilder.put32(res, 4, nb); FormBuilder.put32(res, 8, nc); FormBuilder.put32(res, 12, nd);
        return res;
    }

    // --- sprite sheets --------------------------------------------------------------------

    /**
     * Slices an ARGB sprite sheet into 8 rows x 7 columns, scales every pose so the
     * tallest is `height` pixels, and quantises all of them to 14 colours.
     * Returns {Pose[8][7], int[16] palette (BGR555)}.
     */
    static Object[] fromSheet(int[] argb, int width, int heightPx, int targetHeight) throws IOException {
        int[][] cols = spans(argb, width, heightPx, true), rows = spans(argb, width, heightPx, false);
        if (cols.length != 7 || rows.length != 8)
            throw new IOException("Expected 8 rows x 7 columns of poses with clear gaps; found " + rows.length + " x " + cols.length);
        int tallest = 1;
        int[][][] boxes = new int[8][7][];
        for (int r = 0; r < 8; r++)
            for (int k = 0; k < 7; k++) {
                int[] bb = bbox(argb, width, cols[k][0], rows[r][0], cols[k][1], rows[r][1]);
                if (bb == null) throw new IOException("Empty pose at row " + (r + 1) + ", column " + (k + 1));
                boxes[r][k] = bb;
                tallest = Math.max(tallest, bb[3] - bb[1]);
            }
        double scale = targetHeight / (double) tallest;
        int[][][] small = new int[8][7][];
        int[][] dims = new int[56][];
        for (int r = 0; r < 8; r++)
            for (int k = 0; k < 7; k++) {
                int[] bb = boxes[r][k];
                int w = Math.max(1, (int) Math.round((bb[2] - bb[0]) * scale)), h = Math.max(1, (int) Math.round((bb[3] - bb[1]) * scale));
                small[r][k] = downscale(argb, width, bb, w, h);
                dims[r * 7 + k] = new int[]{w, h};
            }
        int[] palette = quantise(small, 14);
        Pose[][] poses = new Pose[8][7];
        for (int r = 0; r < 8; r++)
            for (int k = 0; k < 7; k++) {
                int[] img = small[r][k];
                int w = dims[r * 7 + k][0], h = dims[r * 7 + k][1];
                byte[] px = new byte[w * h];
                for (int i = 0; i < img.length; i++) px[i] = (byte) ((img[i] >>> 24) < 128 ? 0 : nearest(palette, img[i]));
                poses[r][k] = new Pose(w, h, px);
            }
        return new Object[]{poses, palette};
    }

    /** Runs of non-empty columns (or rows) separated by fully transparent gaps. */
    private static int[][] spans(int[] argb, int w, int h, boolean columns) {
        int n = columns ? w : h;
        List<int[]> out = new ArrayList<>();
        int start = -1;
        for (int i = 0; i <= n; i++) {
            boolean any = false;
            if (i < n) {
                int m = columns ? h : w;
                for (int j = 0; j < m && !any; j++) {
                    int px = columns ? argb[j * w + i] : argb[i * w + j];
                    if ((px >>> 24) >= 128) any = true;
                }
            }
            if (any && start < 0) start = i;
            if (!any && start >= 0) { if (i - start >= 4) out.add(new int[]{start, i}); start = -1; }
        }
        return out.toArray(new int[0][]);
    }

    private static int[] bbox(int[] argb, int w, int x0, int y0, int x1, int y1) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, maxX = -1, maxY = -1;
        for (int y = y0; y < y1; y++)
            for (int x = x0; x < x1; x++)
                if ((argb[y * w + x] >>> 24) >= 128) { minX = Math.min(minX, x); maxX = Math.max(maxX, x); minY = Math.min(minY, y); maxY = Math.max(maxY, y); }
        return maxX < 0 ? null : new int[]{minX, minY, maxX + 1, maxY + 1};
    }

    /** Area-average downscale of a box; alpha-weighted colour. */
    private static int[] downscale(int[] argb, int w, int[] bb, int nw, int nh) {
        int[] out = new int[nw * nh];
        double sx = (bb[2] - bb[0]) / (double) nw, sy = (bb[3] - bb[1]) / (double) nh;
        for (int y = 0; y < nh; y++)
            for (int x = 0; x < nw; x++) {
                int xa = bb[0] + (int) Math.floor(x * sx), xb = Math.max(xa + 1, bb[0] + (int) Math.floor((x + 1) * sx));
                int ya = bb[1] + (int) Math.floor(y * sy), yb = Math.max(ya + 1, bb[1] + (int) Math.floor((y + 1) * sy));
                long a = 0, r = 0, g = 0, b = 0, n = 0;
                for (int yy = ya; yy < yb; yy++)
                    for (int xx = xa; xx < xb; xx++) {
                        int px = argb[yy * w + xx], al = px >>> 24;
                        a += al; r += ((px >> 16) & 255) * al; g += ((px >> 8) & 255) * al; b += (px & 255) * al; n++;
                    }
                int al = (int) (a / n);
                out[y * nw + x] = a == 0 ? 0 : al << 24 | (int) (r / a) << 16 | (int) (g / a) << 8 | (int) (b / a);
            }
        return out;
    }

    /** Median-cut quantisation of the opaque pixels to `n` colours; returns a 16-entry BGR555 palette (indices 2..n+1 used). */
    static int[] quantise(int[][][] images, int n) {
        List<int[]> px = new ArrayList<>();
        for (int[][] row : images) for (int[] img : row) for (int p : img) if ((p >>> 24) >= 128) px.add(new int[]{(p >> 16) & 255, (p >> 8) & 255, p & 255});
        List<List<int[]>> boxes = new ArrayList<>();
        boxes.add(px);
        while (boxes.size() < n) {
            int best = -1, bestRange = -1, bestCh = 0;
            for (int i = 0; i < boxes.size(); i++) {
                List<int[]> bx = boxes.get(i);
                if (bx.size() < 2) continue;
                for (int ch = 0; ch < 3; ch++) {
                    int lo = 255, hi = 0;
                    for (int[] p : bx) { lo = Math.min(lo, p[ch]); hi = Math.max(hi, p[ch]); }
                    if (hi - lo > bestRange) { bestRange = hi - lo; best = i; bestCh = ch; }
                }
            }
            if (best < 0 || bestRange <= 0) break;
            List<int[]> bx = boxes.remove(best);
            final int ch = bestCh;
            bx.sort((p, q) -> p[ch] != q[ch] ? Integer.compare(p[ch], q[ch])
                    : p[0] != q[0] ? Integer.compare(p[0], q[0]) : p[1] != q[1] ? Integer.compare(p[1], q[1]) : Integer.compare(p[2], q[2]));
            int mid = bx.size() / 2;
            boxes.add(new ArrayList<>(bx.subList(0, mid)));
            boxes.add(new ArrayList<>(bx.subList(mid, bx.size())));
        }
        // darkest first, so the palette reads like the game's ramps
        List<int[]> means = new ArrayList<>();
        for (List<int[]> bx : boxes) {
            if (bx.isEmpty()) continue;
            long r = 0, g = 0, b = 0;
            for (int[] p : bx) { r += p[0]; g += p[1]; b += p[2]; }
            means.add(new int[]{(int) (r / bx.size()), (int) (g / bx.size()), (int) (b / bx.size())});
        }
        means.sort((p, q) -> Integer.compare(p[0] * 299 + p[1] * 587 + p[2] * 114, q[0] * 299 + q[1] * 587 + q[2] * 114));
        int[] pal = new int[16];
        for (int i = 0; i < means.size() && i < 14; i++) {
            int[] m = means.get(i);
            pal[2 + i] = (m[0] >> 3) | (m[1] >> 3) << 5 | (m[2] >> 3) << 10;
        }
        for (int i = 2 + means.size(); i < 16; i++) pal[i] = pal[Math.max(2, 1 + means.size())];
        return pal;
    }

    /** Palette index (2-15) closest to an ARGB colour. */
    static int nearest(int[] pal, int argb) {
        int r = (argb >> 16) & 255, g = (argb >> 8) & 255, b = argb & 255, best = 2;
        long bestD = Long.MAX_VALUE;
        for (int i = 2; i < 16; i++) {
            int c = pal[i], pr = (c & 31) << 3, pg = (c >> 5 & 31) << 3, pb = (c >> 10 & 31) << 3;
            long dd = (long) (r - pr) * (r - pr) * 3 + (long) (g - pg) * (g - pg) * 4 + (long) (b - pb) * (b - pb) * 2;
            if (dd < bestD) { bestD = dd; best = i; }
        }
        return best;
    }
}
