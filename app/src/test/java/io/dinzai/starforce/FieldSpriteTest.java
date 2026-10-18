package io.dinzai.starforce;

import org.junit.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.Assert.*;

public class FieldSpriteTest {
    @Test public void framesMapToSheetCells() {
        assertArrayEquals(new int[]{4, 0}, FieldSprite.sourceOf(6));    // idle facing S
        assertArrayEquals(new int[]{4, 0}, FieldSprite.sourceOf(7));    // its blink frame
        assertArrayEquals(new int[]{1, 1}, FieldSprite.sourceOf(13));   // NE walk, first step
        assertArrayEquals(new int[]{7, 6}, FieldSprite.sourceOf(36));   // NW walk, last step
        assertArrayEquals(new int[]{0, 1}, FieldSprite.sourceOf(37));   // N run
        assertArrayEquals(new int[]{7, 6}, FieldSprite.sourceOf(84));   // NW run, last
    }

    @Test public void encodePoseCoversEveryOpaquePixelOnce() {
        int w = 20, h = 30;
        byte[] px = new byte[w * h];
        for (int y = 2; y < h; y++) for (int x = 3; x < 17; x++) px[y * w + x] = (byte) (2 + (x + y) % 13);
        List<byte[]> tiles = new ArrayList<>();
        byte[][] pieces = FieldSprite.encodePose(new FieldSprite.Pose(w, h, px), tiles);
        assertTrue(pieces.length > 0);
        assertEquals(0, tiles.size() % 2);
        int opaque = 0;
        for (byte[] t : tiles) for (byte b : t) { if ((b & 15) != 0) opaque++; if (((b >> 4) & 15) != 0) opaque++; }
        assertEquals(14 * 28, opaque);
        for (byte[] p : pieces) assertTrue(p[2] <= 3);   // nothing below the feet line
    }

    @Test public void quantiseKeepsFourteenColoursDarkFirst() {
        int[][][] imgs = new int[1][1][];
        int[] img = new int[64];
        for (int i = 0; i < 64; i++) img[i] = 0xFF000000 | (i * 4) << 16 | (255 - i * 4);
        imgs[0][0] = img;
        int[] pal = FieldSprite.quantise(imgs, 14);
        assertEquals(0, pal[0]);
        assertEquals(0, pal[1]);
        for (int i = 2; i < 16; i++) assertTrue(pal[i] >= 0 && pal[i] < 0x8000);
    }
}
