package io.dinzai.starforce;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds transformation (form-swap) models from ROMs the user imported. Java
 * port of tools/forms.py: find a player model in a Star Force ROM's models.bin,
 * pair it with its textures, rename it to the target slot and return a
 * self-contained BMD0 that a schema-2 "entries" mod drops into Leo's models.bin.
 */
final class FormBuilder {
    static final int RIG_BONES = 19;

    static final class Form {
        final String id, label, game, model;
        Form(String id, String label, String game, String model) { this.id = id; this.label = label; this.game = game; this.model = model; }
    }

    static final Form[] FORMS = {
            new Form("pegasus", "Pegasus Magic (ice)", "sf1", "pl001"),
            new Form("dragon", "Dragon Sky (wood)", "sf1", "pl002"),
            new Form("leo", "Leo Kingdom (fire)", "sf1", "pl003"),
            new Form("zerker", "Zerker (Star Force 2)", "sf2", "pl001"),
            new Form("ninja", "Ninja (Star Force 2)", "sf2", "pl002"),
            new Form("saurian", "Saurian (Star Force 2)", "sf2", "pl003"),
            new Form("black-ace", "Black Ace (Star Force 3)", "sf3", null),
            new Form("red-joker", "Red Joker (Star Force 3)", "sf3", null),
    };

    /** Target slots in Leo's models.bin: archive entry and model name. */
    static final int SLOT_STARFORCE = 157, SLOT_BASE = 151;
    static String slotName(int entry) { return entry == SLOT_BASE ? "pl000" : "pl003"; }

    private FormBuilder() {}

    // --- Real Star Force forms (game mechanics) -----------------------------
    // Found by tracing the battle overlay (overlay 7): at battle setup the game
    // reads Mega Man's starting form from 0x021AF1BF (player 0; stride 0x1D4)
    // and calls its own transform routine (0x0217600C), which stores the current
    // form at 0x021AF023, loads the model, spawns the form's effect objects and
    // switches in the form's behaviour. Values: 1 Pegasus, 2 Leo, 3 Dragon.
    // The write is guarded by an instruction word inside overlay 7 so it only
    // happens while the battle code is loaded (that RAM is reused elsewhere).
    static final int REAL_PEGASUS = 1, REAL_LEO = 2, REAL_DRAGON = 3;

    static String realFormCode(int form) {
        if (form < 1 || form > 3) throw new IllegalArgumentException("form must be 1-3");
        return "# Start every battle in this Star Force form (guarded: battle overlay loaded)\n"
                + "5217600C 1C05B570\n"
                + "221AF1BF 0000000" + form + "\n"
                + "D2000000 00000000\n";
    }

    // --- ROM access ---------------------------------------------------------

    /** Reads one NitroFS file by path from an NDS image on disk. */
    static byte[] readRomFile(File rom, String path) throws IOException {
        try (RandomAccessFile f = new RandomAccessFile(rom, "r")) {
            byte[] h = new byte[0x60];
            f.readFully(h);
            long fnt = u32(h, 0x40), fntSize = u32(h, 0x44), fat = u32(h, 0x48), fatSize = u32(h, 0x4C);
            if (fntSize > 1 << 20 || fatSize > 1 << 20 || fnt + fntSize > f.length() || fat + fatSize > f.length())
                throw new IOException("Not a DS ROM");
            byte[] t = new byte[(int) fntSize];
            f.seek(fnt);
            f.readFully(t);
            int id = lookup(t, 0xF000, "", path, 0);
            if (id < 0 || id * 8L + 8 > fatSize) throw new IOException("ROM has no " + path);
            byte[] e = new byte[8];
            f.seek(fat + id * 8L);
            f.readFully(e);
            long start = u32(e, 0), end = u32(e, 4);
            if (end < start || end > f.length() || end - start > 64 << 20) throw new IOException("Corrupt FAT entry");
            byte[] out = new byte[(int) (end - start)];
            f.seek(start);
            f.readFully(out);
            return out;
        }
    }

    private static int lookup(byte[] t, int dir, String prefix, String want, int depth) {
        if (depth > 16) return -1;
        int i = (dir & 0xFFF) * 8;
        if (i + 8 > t.length) return -1;
        int p = (int) u32(t, i), file = u16(t, i + 4);
        while (p < t.length) {
            int len = t[p++] & 0xFF;
            if (len == 0) break;
            int n = len & 0x7F;
            if (p + n > t.length) return -1;
            String name = new String(t, p, n, StandardCharsets.US_ASCII);
            p += n;
            if ((len & 0x80) != 0) {
                if (p + 2 > t.length) return -1;
                int child = u16(t, p);
                p += 2;
                String path = prefix + name + "/";
                if (want.startsWith(path)) {
                    int r = lookup(t, child, path, want, depth + 1);
                    if (r >= 0) return r;
                }
            } else {
                if ((prefix + name).equals(want)) return file;
                file++;
            }
        }
        return -1;
    }

    // --- Leo archive + LZ10 -------------------------------------------------

    static List<byte[]> archiveEntries(byte[] a) throws IOException {
        if (a.length < 16) throw new IOException("Archive too short");
        int first = (int) u32(a, 0);
        if (first < 16 || first % 8 != 0 || first > a.length) throw new IOException("Not a Leo archive");
        int count = first / 8 - 1;
        List<byte[]> out = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            long off = u32(a, i * 8), flags = u32(a, i * 8 + 4), end = u32(a, (i + 1) * 8);
            if (off < first || end < off || end > a.length) throw new IOException("Corrupt archive");
            int size = (int) (flags & 0x7FFFFFFF);
            if ((flags >>> 31) != 0) out.add((a[(int)off]&255)==0x11?lz11(a,(int)off,(int)end,size):lz10(a, (int) off, (int) end, size));
            else {
                if (off + size > end) throw new IOException("Corrupt archive entry");
                byte[] b = new byte[size];
                System.arraycopy(a, (int) off, b, 0, size);
                out.add(b);
            }
        }
        return out;
    }

    static byte[] lz10(byte[] d, int p, int end, int expected) throws IOException {
        if (end - p < 4 || (d[p] & 0xFF) != 0x10) throw new IOException("Expected LZ10");
        int size = (d[p + 1] & 0xFF) | (d[p + 2] & 0xFF) << 8 | (d[p + 3] & 0xFF) << 16;
        if (size != expected || size > 32 << 20) throw new IOException("LZ10 size mismatch");
        byte[] out = new byte[size];
        int o = 0;
        p += 4;
        while (o < size) {
            if (p >= end) throw new IOException("Truncated LZ10");
            int flags = d[p++] & 0xFF;
            for (int bit = 7; bit >= 0 && o < size; bit--) {
                if ((flags & (1 << bit)) != 0) {
                    if (p + 1 >= end) throw new IOException("Truncated LZ10");
                    int a = d[p++] & 0xFF, b = d[p++] & 0xFF;
                    int len = (a >> 4) + 3, dist = ((a & 15) << 8 | b) + 1;
                    if (dist > o || o + len > size) throw new IOException("Bad LZ10 reference");
                    for (int k = 0; k < len; k++, o++) out[o] = out[o - dist];
                } else {
                    if (p >= end) throw new IOException("Truncated LZ10");
                    out[o++] = d[p++];
                }
            }
        }
        return out;
    }

    static byte[] lz11(byte[] d,int start,int end,int expected)throws IOException {
        if(start<0||end>d.length||end-start<4||(d[start]&255)!=0x11)throw new IOException("Expected LZ11");
        int size=(d[start+1]&255)|((d[start+2]&255)<<8)|((d[start+3]&255)<<16),p=start+4;
        if(size==0){if(end-p<4)throw new IOException("Truncated LZ11 header");long extended=u32(d,p);if(extended>32<<20)throw new IOException("LZ11 too large");size=(int)extended;p+=4;}
        if(size!=expected||size>32<<20)throw new IOException("LZ11 size mismatch");
        byte[] out=new byte[size];int o=0;
        while(o<size){
            if(p>=end)throw new IOException("Truncated LZ11");int flags=d[p++]&255;
            for(int bit=7;bit>=0&&o<size;bit--){
                if((flags&(1<<bit))==0){if(p>=end)throw new IOException("Truncated LZ11 literal");out[o++]=d[p++];continue;}
                if(p+2>end)throw new IOException("Truncated LZ11 reference");int a=d[p++]&255,b=d[p++]&255,kind=a>>4,len,dist;
                if(kind==0){if(p>=end)throw new IOException("Truncated LZ11 reference");int c=d[p++]&255;len=((a&15)<<4 | b>>4)+0x11;dist=((b&15)<<8 | c)+1;}
                else if(kind==1){if(p+2>end)throw new IOException("Truncated LZ11 reference");int c=d[p++]&255,e=d[p++]&255;len=((a&15)<<12 | b<<4 | c>>4)+0x111;dist=((c&15)<<8 | e)+1;}
                else{len=kind+1;dist=((a&15)<<8 | b)+1;}
                if(dist>o||o+len>size)throw new IOException("Invalid LZ11 reference");
                for(int k=0;k<len;k++,o++)out[o]=out[o-dist];
            }
        }return out;
    }

    static int findModelEntry(File rom,String name)throws IOException {
        List<byte[]> entries=archiveEntries(readRomFile(rom,"datbin/com/models.bin"));
        for(int i=0;i<entries.size();i++)if(isMagic(entries.get(i),"BMD0")){
            byte[] mdl=blocks(entries.get(i)).get("MDL0");
            if(mdl!=null&&name.equals(safeName(mdl)))return i;
        }
        throw new IOException("Target ROM has no "+name);
    }

    // --- Nitro containers ---------------------------------------------------

    static Map<String, byte[]> blocks(byte[] c) throws IOException {
        if (c.length < 16 || u16(c, 4) != 0xFEFF || u16(c, 12) != 16) throw new IOException("Not a Nitro container");
        int n = u16(c, 14);
        Map<String, byte[]> out = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            int off = (int) u32(c, 16 + i * 4);
            if (off < 16 || off + 8 > c.length) throw new IOException("Bad Nitro block");
            int len = (int) u32(c, off + 4);
            if (len < 8 || off + len > c.length) throw new IOException("Bad Nitro block size");
            byte[] b = new byte[len];
            System.arraycopy(c, off, b, 0, len);
            out.put(new String(c, off, 4, StandardCharsets.US_ASCII), b);
        }
        return out;
    }

    /** Offset of the 16-byte name for entry `index` in a Nitro name list at `base`. */
    private static int nameOffset(byte[] d, int base, int index) throws IOException {
        int count = d[base + 1] & 0xFF;
        int at = base + 12 + 4 * count;
        if (at + 4 > d.length) throw new IOException("Bad name list");
        int width = u16(d, at);
        int off = at + 4 + count * width + 16 * index;
        if (index >= count || off + 16 > d.length) throw new IOException("Bad name list");
        return off;
    }

    private static int listCount(byte[] d, int base) { return d[base + 1] & 0xFF; }

    private static int firstModelOffset(byte[] mdl) throws IOException {
        int count = listCount(mdl, 8);
        int at = 8 + 12 + 4 * count;
        if (u16(mdl, at) != 4) throw new IOException("Unexpected MDL0 layout");
        return (int) u32(mdl, at + 4);
    }

    static String modelName(byte[] mdl) throws IOException {
        int o = nameOffset(mdl, 8, 0);
        int n = 0;
        while (n < 16 && mdl[o + n] != 0) n++;
        return new String(mdl, o, n, StandardCharsets.US_ASCII);
    }

    static int boneCount(byte[] mdl) throws IOException {
        int start = firstModelOffset(mdl);
        if (start + 64 + 2 > mdl.length) throw new IOException("Bad model");
        return listCount(mdl, start + 64);
    }

    static byte[] renamed(byte[] mdl, String name) throws IOException {
        byte[] out = mdl.clone();
        int o = nameOffset(out, 8, 0);
        byte[] n = name.getBytes(StandardCharsets.US_ASCII);
        for (int i = 0; i < 16; i++) out[o + i] = i < n.length ? n[i] : 0;
        return out;
    }

    static byte[] container(String magic, int version, byte[]... blocks) {
        int header = 16 + 4 * blocks.length;
        header += (4 - header % 4) % 4;
        int total = header;
        for (byte[] b : blocks) total += b.length + (4 - b.length % 4) % 4;
        byte[] out = new byte[total];
        System.arraycopy(magic.getBytes(StandardCharsets.US_ASCII), 0, out, 0, 4);
        put16(out, 4, 0xFEFF);
        put16(out, 6, version);
        put32(out, 8, total);
        put16(out, 12, 16);
        put16(out, 14, blocks.length);
        int at = header;
        for (int i = 0; i < blocks.length; i++) {
            put32(out, 16 + i * 4, at);
            System.arraycopy(blocks[i], 0, out, at, blocks[i].length);
            at += blocks[i].length + (4 - blocks[i].length % 4) % 4;
        }
        return out;
    }

    // --- Form models --------------------------------------------------------

    /** Builds the BMD0 for `model` from `rom`, renamed for Leo's `targetEntry` slot. */
    static byte[] build(File rom, String model, int targetEntry, int textureVariant) throws IOException {
        return build(rom,model,slotName(targetEntry),RIG_BONES,textureVariant);
    }
    static byte[] build(File rom,String model,String targetName,int targetBones,int textureVariant)throws IOException {
        List<byte[]> entries = archiveEntries(readRomFile(rom, "datbin/com/models.bin"));
        for (int i = 0; i < entries.size(); i++) {
            byte[] e = entries.get(i);
            if (!isMagic(e, "BMD0")) continue;
            Map<String, byte[]> b;
            try { b = blocks(e); } catch (IOException ex) { continue; }
            byte[] mdl = b.get("MDL0");
            if (mdl == null || !model.equals(safeName(mdl))) continue;
            int bones = boneCount(mdl);
            if (bones != targetBones) throw new IOException(model + " has " + bones + " bones; target needs " + targetBones);
            byte[] tex = b.get("TEX0");
            if (tex == null) {
                int seen = 0;
                for (int j = i + 1; j < entries.size() && tex == null; j++) {
                    byte[] t = entries.get(j);
                    if (isMagic(t, "BMD0")) break;
                    if (isMagic(t, "BTX0") && seen++ == textureVariant) tex = blocks(t).get("TEX0");
                }
                if (tex == null) throw new IOException(model + " has no texture set " + textureVariant);
            }
            return container("BMD0", u16(e, 6), renamed(mdl, targetName), tex);
        }
        throw new IOException("This ROM has no " + model + " model");
    }

    private static String safeName(byte[] mdl) {
        try { return modelName(mdl); } catch (IOException e) { return ""; }
    }

    private static boolean isMagic(byte[] d, String m) {
        return d.length >= 4 && new String(d, 0, 4, StandardCharsets.US_ASCII).equals(m);
    }

    static long u32(byte[] d, int o) { return (d[o] & 0xFFL) | (d[o + 1] & 0xFFL) << 8 | (d[o + 2] & 0xFFL) << 16 | (d[o + 3] & 0xFFL) << 24; }
    static int u16(byte[] d, int o) { return (d[o] & 0xFF) | (d[o + 1] & 0xFF) << 8; }
    static void put32(byte[] d, int o, long v) { for (int i = 0; i < 4; i++) d[o + i] = (byte) (v >> (8 * i)); }
    static void put16(byte[] d, int o, int v) { d[o] = (byte) v; d[o + 1] = (byte) (v >> 8); }
}

