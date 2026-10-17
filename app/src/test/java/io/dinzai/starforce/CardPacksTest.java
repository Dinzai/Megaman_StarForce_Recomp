package io.dinzai.starforce;

import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

public class CardPacksTest {
    /** A small name table in the game's format: u16 offsets, then names ending in E6 00. */
    private static byte[] table(String... names) throws Exception {
        java.util.List<byte[]> s = new java.util.ArrayList<>();
        for (String n : names) {
            byte[] e = n.isEmpty() ? new byte[0] : CardPacks.encodeName(n), b = new byte[e.length + 2];
            System.arraycopy(e, 0, b, 0, e.length);
            b[e.length] = (byte) 0xE6;
            s.add(b);
        }
        return CardPacks.joinNames(s);
    }

    @Test public void namesRoundTrip() throws Exception {
        byte[] t = table("MegaBuster", "Cannon", "PlusCannon", "Fokx-Fu1");
        assertEquals(Arrays.asList("MegaBuster", "Cannon", "PlusCannon", "Fokx-Fu1"), CardPacks.names(t));
        assertArrayEquals(t, CardPacks.joinNames(CardPacks.splitNames(t)));
        assertArrayEquals(new byte[]{0x27, 0x41, 0x10, 0x00, 0x0D}, CardPacks.encodeName("Ga0 -"));
    }

    @Test public void rejectsBadNames() {
        for (String bad : new String[]{"", "ElevenChars", "Bad!"}) {
            try { CardPacks.encodeName(bad); fail(bad); } catch (java.io.IOException expected) { }
        }
    }

    @Test public void buildsCodesAndRenames() throws Exception {
        byte[] t = table("MegaBuster", "Cannon", "PlusCannon", "HevyCannon");
        CardPacks.Pack p = CardPacks.parse("{\"format\":\"starforce-cards\",\"id\":\"t\",\"cards\":["
                + "{\"base\":\"cannon\",\"name\":\"FireCannon\",\"damage\":70,\"element\":\"fire\",\"picture\":\"HevyCannon\"},"
                + "{\"base\":\"2\",\"damage\":99}]}");
        Object[] r = CardPacks.build(CardPacks.LEO, t, Collections.singletonList(p));
        String code = (String) r[0];
        // card 1 record at 0x020B8E7C + 0x2C
        assertTrue(code.contains("D3000000 020B8F24\nF20B8ECC 00000008\nD2000000 00000000\n"));
        assertTrue(code.contains("220B8EA8 00000005\n"));
        assertTrue(code.contains("120B8EAE 00000046\n"));
        assertTrue(code.contains("120B8EDA 00000063\n"));
        List<String> names = CardPacks.names((byte[]) r[1]);
        assertEquals(Arrays.asList("MegaBuster", "FireCannon", "PlusCannon", "HevyCannon"), names);
    }

    @Test public void laterPackWinsAndNoRenameKeepsTable() throws Exception {
        byte[] t = table("MegaBuster", "Cannon");
        CardPacks.Pack a = CardPacks.parse("{\"format\":\"starforce-cards\",\"id\":\"a\",\"cards\":[{\"base\":\"Cannon\",\"damage\":10}]}");
        CardPacks.Pack b = CardPacks.parse("{\"format\":\"starforce-cards\",\"id\":\"b\",\"cards\":[{\"base\":\"Cannon\",\"damage\":20}]}");
        Object[] r = CardPacks.build(CardPacks.LEO, t, Arrays.asList(a, b));
        assertFalse(((String) r[0]).contains("0000000A"));
        assertTrue(((String) r[0]).contains("00000014"));
        assertNull(r[1]);
    }

    @Test public void rejectsInvalidPacks() {
        String[] bad = {
                "{\"format\":\"other\",\"id\":\"x\",\"cards\":[{\"base\":\"Cannon\"}]}",
                "{\"format\":\"starforce-cards\",\"id\":\"x\",\"cards\":[]}",
                "{\"format\":\"starforce-cards\",\"id\":\"x\",\"cards\":[{\"base\":\"Cannon\",\"damage\":1000}]}",
                "{\"format\":\"starforce-cards\",\"id\":\"x\",\"cards\":[{\"base\":\"Cannon\",\"element\":\"ice\"}]}",
                "{\"format\":\"starforce-cards\",\"id\":\"x\",\"game\":\"black-ace\",\"cards\":[{\"base\":\"Cannon\"}]}",
                "{\"format\":\"starforce-cards\",\"id\":\"x\",\"game\":\"../leo\",\"cards\":[{\"base\":\"Cannon\"}]}",
        };
        for (String j : bad) {
            try { CardPacks.parse(j); fail(j); } catch (Exception expected) { }
        }
    }

    @Test public void bundledPacksParse() throws Exception {
        for (GameProfile g : new GameProfile[]{GameProfile.LEO, GameProfile.ZERKER_NINJA}) {
            java.io.File dir = new java.io.File("src/main/assets/" + CardPacks.assetDir(g));
            if (!dir.isDirectory()) dir = new java.io.File("app/src/main/assets/" + CardPacks.assetDir(g));
            java.io.File[] files = dir.listFiles();
            assertNotNull(files);
            assertTrue(files.length >= 2);
            for (java.io.File f : files) assertEquals(g.id, CardPacks.parse(CardPacks.read(new java.io.FileInputStream(f))).game);
        }
    }

    /** Zerker × Ninja: 24-byte records at 0x020BBA98, damage at +0x0E, picture words at +0x08; names keep FA 18 E6. */
    @Test public void zerkerNinjaLayout() throws Exception {
        java.util.List<byte[]> s = new java.util.ArrayList<>();
        s.add(new byte[]{0x2D, (byte) 0xE6});
        s.add(new byte[]{0x23, 0x41, (byte) 0xFA, 0x18, (byte) 0xE6});   // "Ca" + FA 18 E6
        s.add(new byte[]{0x30, (byte) 0xFA, 0x18, (byte) 0xE6});
        byte[] t = CardPacks.joinNames(s);
        CardPacks.Pack p = CardPacks.parse("{\"format\":\"starforce-cards\",\"game\":\"zerker-ninja\",\"id\":\"z\",\"cards\":["
                + "{\"base\":\"Ca\",\"name\":\"Zap\",\"damage\":123,\"element\":\"fire\",\"picture\":\"2\"}]}");
        Object[] r = CardPacks.build(CardPacks.ZERKER_NINJA, t, Collections.singletonList(p));
        String code = (String) r[0];
        assertTrue(code, code.contains("D3000000 020BBAD0\nF20BBAB8 00000004\nD2000000 00000000\n"));
        assertTrue(code.contains("220BBAB0 00000005\n120BBABE 0000007B\n"));
        java.util.List<byte[]> out = CardPacks.splitNames((byte[]) r[1]);
        assertArrayEquals(new byte[]{0x3A, 0x41, 0x50, (byte) 0xFA, 0x18, (byte) 0xE6}, out.get(1));
        assertEquals("Zap", CardPacks.names((byte[]) r[1]).get(1));
    }
}
