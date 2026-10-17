package io.dinzai.starforce;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

public class FormBuilderTest {
    private static byte[] lz10Literal(byte[] raw) {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        o.write(0x10); o.write(raw.length); o.write(raw.length >> 8); o.write(raw.length >> 16);
        for (int i = 0; i < raw.length; i += 8) { o.write(0); o.write(raw, i, Math.min(8, raw.length - i)); }
        return o.toByteArray();
    }

    @Test public void lz10DecodesLiteralsAndBackReferences() throws Exception {
        byte[] raw = "hello world!".getBytes(StandardCharsets.US_ASCII);
        byte[] c = lz10Literal(raw);
        assertArrayEquals(raw, FormBuilder.lz10(c, 0, c.length, raw.length));
        // "abcabcabc": 3 literals then a reference (len 6, dist 3) -> flags 0b00010000
        byte[] r = {0x10, 9, 0, 0, 0x10, 'a', 'b', 'c', 0x30, 0x02};
        assertEquals("abcabcabc", new String(FormBuilder.lz10(r, 0, r.length, 9), StandardCharsets.US_ASCII));
        try { FormBuilder.lz10(r, 0, r.length, 8); fail(); } catch (java.io.IOException expected) { }
    }

    @Test public void archiveEntriesHandleStoredAndCompressed() throws Exception {
        byte[] comp = lz10Literal("xyz".getBytes(StandardCharsets.US_ASCII));
        int first = 24, e0 = first, e1 = e0 + 4, end = e1 + ((comp.length + 3) & ~3);
        byte[] a = new byte[end];
        FormBuilder.put32(a, 0, e0); FormBuilder.put32(a, 4, 4);
        FormBuilder.put32(a, 8, e1); FormBuilder.put32(a, 12, 3 | 0x80000000L);
        FormBuilder.put32(a, 16, end); FormBuilder.put32(a, 20, 0xFFFF);
        System.arraycopy("ABCD".getBytes(StandardCharsets.US_ASCII), 0, a, e0, 4);
        System.arraycopy(comp, 0, a, e1, comp.length);
        List<byte[]> es = FormBuilder.archiveEntries(a);
        assertEquals("ABCD", new String(es.get(0), StandardCharsets.US_ASCII));
        assertEquals("xyz", new String(es.get(1), StandardCharsets.US_ASCII));
    }

    @Test public void containerRoundTripsAndRenamesModel() throws Exception {
        // Minimal MDL0 with a one-entry name list holding "pl001".
        byte[] mdl = new byte[8 + 12 + 4 + 4 + 4 + 16];
        System.arraycopy("MDL0".getBytes(StandardCharsets.US_ASCII), 0, mdl, 0, 4);
        FormBuilder.put32(mdl, 4, mdl.length);
        mdl[9] = 1;                                   // count
        FormBuilder.put16(mdl, 8 + 12 + 4, 4);        // entry width
        System.arraycopy("pl001".getBytes(StandardCharsets.US_ASCII), 0, mdl, 8 + 12 + 4 + 4 + 4, 5);
        assertEquals("pl001", FormBuilder.modelName(mdl));
        byte[] renamed = FormBuilder.renamed(mdl, "pl003");
        assertEquals("pl003", FormBuilder.modelName(renamed));
        byte[] tex = new byte[12];
        System.arraycopy("TEX0".getBytes(StandardCharsets.US_ASCII), 0, tex, 0, 4);
        FormBuilder.put32(tex, 4, tex.length);
        byte[] c = FormBuilder.container("BMD0", 2, renamed, tex);
        Map<String, byte[]> b = FormBuilder.blocks(c);
        assertArrayEquals(renamed, b.get("MDL0"));
        assertArrayEquals(tex, b.get("TEX0"));
        assertEquals(c.length, FormBuilder.u32(c, 8));
    }
}
