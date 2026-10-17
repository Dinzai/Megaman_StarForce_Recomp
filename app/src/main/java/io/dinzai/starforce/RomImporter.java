package io.dinzai.starforce;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Verified, streaming import of the user's own Leo dump into app-private
 * storage (files/leo/game.nds). The runtime boots from this private copy.
 * Imports from 0.3.x (which kept only extracted files) are upgraded by
 * importing the ROM again.
 */
public final class RomImporter {
    public static final String LEO_SHA256 = "defbc6f639fb0bcd11a229d1a0c7c9f595447034ae25d3ba4a97e1268156c5d6";
    private static final long ROM_SIZE = 33554432;
    private RomImporter() {}

    static File romFile(File storage) { return new File(storage, "leo/game.nds"); }
    static File saveFile(File storage) { return new File(storage, "leo/battery.sav"); }
    static File stateFile(File storage, int slot) { return new File(storage, "leo/state" + slot + ".sst.gz"); }
    static boolean isPlayable(File storage) { return romFile(storage).isFile() && new File(storage, "leo/import.json").isFile(); }

    public static synchronized String importLeo(InputStream input, File storage) throws Exception {
        File staging = new File(storage, "leo-staging");
        File current = new File(storage, "leo");
        if (isPlayable(storage)) throw new IOException("Leo is already imported");
        delete(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create import directory");
        try {
            File rom = new File(staging, "game.nds");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out = new FileOutputStream(rom)) {
                byte[] buffer = new byte[65536];
                int n;
                while ((n = input.read(buffer)) != -1) {
                    total += n;
                    if (total > ROM_SIZE) throw new IOException("Expected the 32 MiB US Leo ROM");
                    digest.update(buffer, 0, n);
                    out.write(buffer, 0, n);
                }
            }
            StringBuilder hash = new StringBuilder();
            for (byte b : digest.digest()) hash.append(String.format(Locale.ROOT, "%02x", b & 255));
            if (total != ROM_SIZE || !LEO_SHA256.equals(hash.toString()))
                throw new IOException("Unsupported ROM. This build accepts only the profiled US Leo dump (Mega Man Star Force - Leo, USA).");
            try (RandomAccessFile f = new RandomAccessFile(rom, "r")) {
                byte[] header = new byte[16];
                f.readFully(header);
                if (!new String(header, 12, 4, StandardCharsets.US_ASCII).equals("A6BE")) throw new IOException("Wrong game code");
            }
            String report = "{\"game\":\"leo\",\"schema\":2,\"sha256\":\"" + hash + "\"}";
            try (OutputStream out = new FileOutputStream(new File(staging, "import.json"))) {
                out.write(report.getBytes(StandardCharsets.UTF_8));
            }
            // Keep saves and states from an older import directory.
            if (current.isDirectory()) {
                File[] keep = current.listFiles((d, name) -> name.endsWith(".sav") || name.endsWith(".sst.gz"));
                if (keep != null) for (File k : keep) k.renameTo(new File(staging, k.getName()));
                delete(current);
            }
            if (!staging.renameTo(current)) throw new IOException("Could not commit imported data");
            return "Leo verified and ready to play.";
        } finally {
            delete(staging);
        }
    }

    private static void delete(File path) throws IOException {
        if (!path.exists()) return;
        if (path.isDirectory()) {
            File[] children = path.listFiles();
            if (children == null) throw new IOException("Cannot inspect staging directory");
            for (File child : children) delete(child);
        }
        if (!path.delete()) throw new IOException("Cannot clean staging data");
    }
}
