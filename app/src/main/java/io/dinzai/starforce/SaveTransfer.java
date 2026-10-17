package io.dinzai.starforce;

import java.io.*;

/** Copies the battery save in and out through the system file picker. */
final class SaveTransfer {
    static final int MAX_SAVE = 2 * 1024 * 1024;
    private SaveTransfer() {}

    static String exportSave(OutputStream out, File storage) throws IOException {
        if (out == null) throw new IOException("Cannot open destination");
        File f = RomImporter.saveFile(storage);
        if (!f.isFile()) throw new IOException("No save yet");
        try (InputStream in = new FileInputStream(f)) {
            byte[] b = new byte[65536];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
        }
        return "Save exported (" + f.length() / 1024 + " KiB).";
    }

    static String importSave(InputStream in, File storage) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        byte[] b = new byte[65536];
        int n;
        while ((n = in.read(b)) > 0) {
            if (buf.size() + n > MAX_SAVE) throw new IOException("Save files are at most 2 MiB");
            buf.write(b, 0, n);
        }
        byte[] data = buf.toByteArray();
        StringBuilder note = new StringBuilder();
        data = unwrap(data, note);
        if (!validSize(data.length)) throw new IOException("Not a DS save (size " + data.length + " bytes)");
        convertToLeo(data, note);
        File f = RomImporter.saveFile(storage);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Import the ROM first");
        File backup = new File(f.getPath() + ".bak"), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); out.getFD().sync(); }
        if (f.isFile()) { backup.delete(); f.renameTo(backup); }
        if (!tmp.renameTo(f)) throw new IOException("Could not install save");
        return "Save imported" + note + ". The previous save was kept as battery.sav.bak.";
    }

    static final int ARDS_HEADER = 500;

    /** Strips an Action Replay DS (.duc/.dss) header if present. */
    static byte[] unwrap(byte[] d, StringBuilder note) {
        if (d.length > ARDS_HEADER && new String(d, 0, 4, java.nio.charset.StandardCharsets.US_ASCII).equals("ARDS")) {
            note.append(" (Action Replay file unwrapped)");
            return java.util.Arrays.copyOfRange(d, ARDS_HEADER, d.length);
        }
        return d;
    }

    /**
     * Star Force 1 saves start with "rr1_X061026", where X is the version letter
     * (p = Pegasus, l = Leo, d = Dragon), followed by a header whose 16-bit checksum
     * at 0x0E is ~sum(bytes 0..0x63) with the field zeroed. The save slots carry
     * their own version-independent checksums, so relabelling the header is enough
     * for Leo to load a Pegasus or Dragon save.
     */
    static void convertToLeo(byte[] d, StringBuilder note) throws IOException {
        if (d.length < 0x64) return;
        String magic = new String(d, 0, 11, java.nio.charset.StandardCharsets.US_ASCII);
        if (!magic.matches("rr1_[pld]061026")) {
            if (allFF(d)) return;   // blank save
            throw new IOException("This isn't a Mega Man Star Force 1 save");
        }
        char v = magic.charAt(4);
        if (v == 'l') return;
        d[4] = 'l';
        d[0xE] = 0;
        d[0xF] = 0;
        int sum = 0;
        for (int i = 0; i < 0x64; i++) sum += d[i] & 0xFF;
        int ck = ~sum & 0xFFFF;
        d[0xE] = (byte) ck;
        d[0xF] = (byte) (ck >> 8);
        note.append(v == 'p' ? " (converted from Pegasus)" : " (converted from Dragon)");
    }

    private static boolean allFF(byte[] d) {
        for (byte b : d) if (b != (byte) 0xFF) return false;
        return true;
    }

    /** DS saves are powers of two from 512 bytes; some tools append a 122-byte footer. */
    static boolean validSize(int len) {
        int core = (len > 122 && Integer.bitCount(len - 122) == 1) ? len - 122 : len;
        return core >= 512 && Integer.bitCount(core) == 1;
    }
}
