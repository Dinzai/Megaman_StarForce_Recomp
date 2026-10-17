package io.dinzai.starforce;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class SaveTransferTest {
    private static byte[] save(char version) {
        byte[] d = new byte[0x40000];
        java.util.Arrays.fill(d, (byte) 0xFF);
        byte[] magic = ("rr1_" + version + "061026").getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(magic, 0, d, 0, magic.length);
        for (int i = 11; i < 0x64; i++) d[i] = (byte) (i * 7);
        d[0xE] = 0; d[0xF] = 0;
        int sum = 0;
        for (int i = 0; i < 0x64; i++) sum += d[i] & 0xFF;
        d[0xE] = (byte) ~sum; d[0xF] = (byte) (~sum >> 8);
        return d;
    }

    private static int checksum(byte[] d) {
        int e = d[0xE] & 0xFF | (d[0xF] & 0xFF) << 8, sum = 0;
        for (int i = 0; i < 0x64; i++) if (i != 0xE && i != 0xF) sum += d[i] & 0xFF;
        return (~sum & 0xFFFF) == e ? 1 : 0;
    }

    @Test public void convertsDragonAndPegasusToLeo() throws Exception {
        for (char v : new char[]{'d', 'p'}) {
            byte[] d = save(v);
            StringBuilder note = new StringBuilder();
            SaveTransfer.convertToLeo(d, note);
            assertEquals('l', (char) d[4]);
            assertEquals(1, checksum(d));
            assertTrue(note.toString().contains("converted"));
        }
        byte[] leo = save('l'), copy = leo.clone();
        SaveTransfer.convertToLeo(leo, new StringBuilder());
        assertArrayEquals(copy, leo);
    }

    @Test public void unwrapsActionReplayAndRejectsOtherGames() throws Exception {
        byte[] raw = save('d');
        byte[] duc = new byte[raw.length + 500];
        System.arraycopy("ARDS000000000001".getBytes(StandardCharsets.US_ASCII), 0, duc, 0, 16);
        System.arraycopy(raw, 0, duc, 500, raw.length);
        assertArrayEquals(raw, SaveTransfer.unwrap(duc, new StringBuilder()));
        byte[] other = new byte[0x40000];
        try { SaveTransfer.convertToLeo(other, new StringBuilder()); fail(); } catch (java.io.IOException expected) { }
        byte[] blank = new byte[0x40000];
        java.util.Arrays.fill(blank, (byte) 0xFF);
        SaveTransfer.convertToLeo(blank, new StringBuilder());
    }
}
