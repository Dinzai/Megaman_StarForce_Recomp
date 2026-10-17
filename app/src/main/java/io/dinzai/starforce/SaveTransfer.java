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
        if (!validSize(data.length)) throw new IOException("Not a DS save (size " + data.length + " bytes)");
        File f = RomImporter.saveFile(storage);
        File dir = f.getParentFile();
        if (dir != null && !dir.isDirectory() && !dir.mkdirs()) throw new IOException("Import the ROM first");
        File backup = new File(f.getPath() + ".bak"), tmp = new File(f.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) { out.write(data); out.getFD().sync(); }
        if (f.isFile()) { backup.delete(); f.renameTo(backup); }
        if (!tmp.renameTo(f)) throw new IOException("Could not install save");
        return "Save imported. The previous save was kept as battery.sav.bak.";
    }

    /** DS saves are powers of two from 512 bytes; some tools append a 122-byte footer. */
    static boolean validSize(int len) {
        int core = (len > 122 && Integer.bitCount(len - 122) == 1) ? len - 122 : len;
        return core >= 512 && Integer.bitCount(core) == 1;
    }
}
