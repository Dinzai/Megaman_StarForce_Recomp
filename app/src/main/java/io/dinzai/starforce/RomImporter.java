package io.dinzai.starforce;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Verified, streaming import. The temporary ROM is removed on success and failure. */
public final class RomImporter {
    public static final String LEO_SHA256 = "defbc6f639fb0bcd11a229d1a0c7c9f595447034ae25d3ba4a97e1268156c5d6";
    private static final long ROM_SIZE = 33554432;
    private RomImporter() {}
    public static String importLeo(InputStream input, File storage) throws Exception {
        File temporary = new File(storage, "leo-import.nds");
        File staging = new File(storage, "leo-staging");
        File current = new File(storage, "leo");
        if (current.exists()) throw new IOException("Leo is already imported");
        delete(staging);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long total = 0;
            try (OutputStream out = new FileOutputStream(temporary)) {
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
                throw new IOException("Unsupported ROM. This build accepts only the profiled US Leo dump.");
            if (!staging.mkdirs()) throw new IOException("Cannot create import directory");
            int count;
            try (RandomAccessFile rom = new RandomAccessFile(temporary, "r")) {
                byte[] header = new byte[512]; rom.readFully(header);
                if (!new String(header, 12, 4, StandardCharsets.US_ASCII).equals("A6BE"))
                    throw new IOException("Wrong game code");
                long fatOffset = u32(header, 0x48), fatSize = u32(header, 0x4c);
                check(fatOffset, fatSize, total);
                if (fatSize % 8 != 0 || fatSize > 1048576) throw new IOException("Invalid file allocation table");
                byte[] fat = new byte[(int)fatSize]; rom.seek(fatOffset); rom.readFully(fat);
                count = fat.length/8;
                File files = new File(staging, "files");
                if (!files.mkdir()) throw new IOException("Cannot create files directory");
                for (int i = 0; i < count; i++) {
                    long start = u32(fat, i*8), end = u32(fat, i*8+4);
                    copy(rom, start, end-start, new File(files, String.format(Locale.ROOT,"%04d.bin",i)));
                }
                // Preserve import metadata; code images stay in developer tooling only.
                copy(rom, u32(header,0x40),u32(header,0x44),new File(staging,"fnt.bin"));
                String report = "{\"game\":\"leo\",\"schema\":1,\"sha256\":\""+hash+"\",\"files\":"+count+"}";
                try (OutputStream out = new FileOutputStream(new File(staging,"import.json"))) {
                    out.write(report.getBytes(StandardCharsets.UTF_8));
                }
            }
            if (!staging.renameTo(current)) throw new IOException("Could not commit imported data");
            return "Leo verified • "+count+" files imported. Gameplay reconstruction is not available yet.";
        } finally {
            if (temporary.exists() && !temporary.delete()) temporary.deleteOnExit();
            delete(staging);
        }
    }
    private static long u32(byte[] d, int p) {
        return (d[p]&255L) | ((d[p+1]&255L)<<8) | ((d[p+2]&255L)<<16) | ((d[p+3]&255L)<<24);
    }
    private static void check(long offset, long size, long length) throws IOException {
        if (offset < 0 || size < 0 || offset > length-size) throw new IOException("Invalid ROM range");
    }
    private static void copy(RandomAccessFile rom, long start, long size, File output) throws IOException {
        check(start,size,rom.length()); rom.seek(start);
        try (OutputStream out = new FileOutputStream(output)) {
            byte[] buffer = new byte[65536];
            while (size > 0) {
                int n = (int)Math.min(size,buffer.length); rom.readFully(buffer,0,n); out.write(buffer,0,n); size-=n;
            }
        }
    }
    private static void delete(File path) throws IOException {
        if (!path.exists()) return;
        if (path.isDirectory()) {
            File[] children=path.listFiles();
            if (children==null) throw new IOException("Cannot inspect staging directory");
            for (File child:children) delete(child);
        }
        if (!path.delete()) throw new IOException("Cannot clean staging data");
    }
}
