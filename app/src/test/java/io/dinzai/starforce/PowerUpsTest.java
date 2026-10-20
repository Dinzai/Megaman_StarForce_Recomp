package io.dinzai.starforce;

import org.junit.Test;
import static org.junit.Assert.*;

public class PowerUpsTest {
    private static String strip(String c) { return c.replaceAll("(?m)^#.*\n", ""); }

    @Test public void abilityCodeMakesHp1000AndFreeSuperArmor() {
        String c = strip(PowerUps.abilityCode());
        assertEquals("220DFF78 0000005D\n020DFF7C 000003E8\n020DFE4C 00000000\n2211D6AF 00000001\n2211D689 00000001\n", c);
    }

    @Test public void blankCardSwapsForItsTarget() {
        String c = strip(PowerUps.blankCode());
        assertTrue(c, c.startsWith("A211A21A FF000000\n2211A21A 00000000\nD9000000 0211A0F8\nD4000000 00010000\nD6000000 0211A0F8\nD2000000 00000000\n"));
        assertEquals(60, c.split("\n").length);
        assertEquals(10, PowerUps.TARGETS.length);
        assertEquals(10, PowerUps.TARGET_NAMES.length);
    }

    @Test public void giveCodeOnlyFillsEmptySlots() {
        String c = strip(PowerUps.giveCode());
        assertTrue(c, c.startsWith("9211A0FA FF000000\n2211A0FA 00000001\nD2000000 00000000\n"));
    }

    @Test public void descriptionDigitsBecome1000() throws Exception {
        byte[] d = {0x37, 0x00, 0x15, 0x10, 0x10, 0x00, (byte) 0xE6};
        assertArrayEquals(new byte[]{0x37, 0x00, 0x11, 0x10, 0x10, 0x10, 0x00, (byte) 0xE6}, PowerUps.hp1000Description(d));
    }
}
