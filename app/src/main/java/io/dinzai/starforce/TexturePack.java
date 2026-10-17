package io.dinzai.starforce;

import android.graphics.BitmapFactory;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Imports only bounded PNG images and JSON; never executes code from a pack. */
public final class TexturePack {
    private TexturePack() {}
    public static JSONArray manifest(File root) throws Exception {
        try(InputStream in=new FileInputStream(new File(root,"manifest.json"))) {
            JSONObject manifest=new JSONObject(new String(read(in,2*1024*1024),StandardCharsets.UTF_8));
            if(manifest.getInt("schema")!=1 || !"leo".equals(manifest.getString("game")) ||
               !RomImporter.LEO_SHA256.equals(manifest.getString("rom_sha256")))throw new IOException("Incompatible texture pack");
            return manifest.getJSONArray("textures");
        }
    }
    public static synchronized int install(InputStream input,File storage)throws Exception {
        File staging=new File(storage,"textures-staging"),current=new File(storage,"textures"),backup=new File(storage,"textures-backup");
        // Recover a previous interrupted directory swap before starting a new one.
        if(!current.exists()&&backup.exists()&&!backup.renameTo(current))throw new IOException("Cannot restore previous pack");
        erase(staging);erase(backup);
        if(!new File(staging,"textures").mkdirs())throw new IOException("Cannot create pack directory");
        try {
            Set<String> names=new HashSet<>();long total=0;
            try(ZipInputStream zip=new ZipInputStream(input)) {
                ZipEntry entry;
                while((entry=zip.getNextEntry())!=null) {
                    String name=entry.getName();
                    if(entry.isDirectory() || !(name.equals("manifest.json")||name.matches("textures/[0-9]+_[0-9]+_[0-9]+\\.png")) ||
                       !names.add(name)||names.size()>4096)throw new IOException("Invalid or duplicate pack path");
                    byte[] bytes=read(zip,name.equals("manifest.json")?2*1024*1024:4*1024*1024);
                    total+=bytes.length;if(total>128*1024*1024)throw new IOException("Texture pack exceeds 128 MiB");
                    if(name.endsWith(".png")) {
                        byte[] signature={(byte)137,80,78,71,13,10,26,10};
                        if(bytes.length<8||!Arrays.equals(Arrays.copyOf(bytes,8),signature))throw new IOException("Expected PNG");
                        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
                        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
                        if(options.outWidth<1||options.outHeight<1||options.outWidth>1024||options.outHeight>1024)
                            throw new IOException("Texture dimensions outside 1–1024");
                    }
                    try(OutputStream out=new FileOutputStream(new File(staging,name))){out.write(bytes);}
                    zip.closeEntry();
                }
            }
            JSONArray textures=manifest(staging);
            if(textures.length()==0||textures.length()>4095)throw new IOException("Invalid texture count");
            Set<String> declared=new HashSet<>();declared.add("manifest.json");
            for(int i=0;i<textures.length();i++) {
                JSONObject texture=textures.getJSONObject(i);String path=texture.getString("path");
                if(!path.matches("textures/[0-9]+_[0-9]+_[0-9]+\\.png")||!declared.add(path)||!names.contains(path))
                    throw new IOException("Missing or duplicate texture");
                texture.getString("name");
                java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
                try(InputStream in=new FileInputStream(new File(staging,path))){digest.update(read(in,4*1024*1024));}
                StringBuilder hex=new StringBuilder();for(byte b:digest.digest())hex.append(String.format(Locale.ROOT,"%02x",b&255));
                if(!hex.toString().equals(texture.getString("sha256")))throw new IOException("Texture checksum mismatch");
            }
            if(!declared.equals(names))throw new IOException("Undeclared files in pack");
            if(current.exists()&&!current.renameTo(backup))throw new IOException("Cannot retain previous pack");
            if(!staging.renameTo(current)) {
                if(backup.exists()&&!backup.renameTo(current))throw new IOException("Pack commit failed; recovery needed");
                throw new IOException("Cannot commit texture pack");
            }
            erase(backup);return textures.length();
        } finally {erase(staging);}
    }
    private static byte[] read(InputStream in,int max)throws IOException {
        ByteArrayOutputStream out=new ByteArrayOutputStream();byte[] bytes=new byte[16384];int n;
        while((n=in.read(bytes))!=-1){if(out.size()+n>max)throw new IOException("Pack entry exceeds limit");out.write(bytes,0,n);}
        return out.toByteArray();
    }
    private static void erase(File file)throws IOException {
        if(!file.exists())return;
        if(file.isDirectory()) {File[] entries=file.listFiles();if(entries==null)throw new IOException("Cannot list pack directory");for(File f:entries)erase(f);}
        if(!file.delete())throw new IOException("Cannot remove stale pack data");
    }
}
