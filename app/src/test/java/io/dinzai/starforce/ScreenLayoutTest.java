package io.dinzai.starforce;

import org.junit.Test;
import static org.junit.Assert.*;

public class ScreenLayoutTest {
    @Test public void stackedPortraitFillsWidth() {
        ScreenLayout l = ScreenLayout.compute(ScreenLayout.Mode.STACKED, 1080, 2400, false);
        assertArrayEquals(new int[]{0, 0, 1080, 810}, l.top);
        assertArrayEquals(new int[]{0, 810, 1080, 1620}, l.bottom);
        assertEquals(1620, l.free[1]);
    }

    @Test public void touchMapsToDsCoordinates() {
        ScreenLayout l = ScreenLayout.compute(ScreenLayout.Mode.STACKED, 1024, 1536, false);
        assertArrayEquals(new int[]{0, 0}, l.toTouch(0, 768));
        assertArrayEquals(new int[]{255, 191}, l.toTouch(1023.9f, 1535.9f));
        assertNull(l.toTouch(10, 10));  // top screen is not touchable
    }

    @Test public void sideBySideCentersVertically() {
        ScreenLayout l = ScreenLayout.compute(ScreenLayout.Mode.SIDE_BY_SIDE, 2048, 1000, false);
        assertEquals(1024, l.top[2] - l.top[0]);
        assertEquals(l.top[2], l.bottom[0]);
        assertEquals((1000 - 768) / 2, l.top[1]);
    }

    @Test public void dualScreenShowsOneScreenPerDisplay() {
        ScreenLayout top = ScreenLayout.compute(ScreenLayout.Mode.TOP_ONLY, 1920, 1080, false);
        assertTrue(ScreenLayout.visible(top.top));
        assertFalse(ScreenLayout.visible(top.bottom));
        assertNull(top.toTouch(960, 540));
        ScreenLayout bottom = ScreenLayout.compute(ScreenLayout.Mode.BOTTOM_ONLY, 1240, 1080, true);
        assertEquals(1024, bottom.bottom[2] - bottom.bottom[0]);  // integer scale 4x
        assertNotNull(bottom.toTouch(620, 540));
    }
}
