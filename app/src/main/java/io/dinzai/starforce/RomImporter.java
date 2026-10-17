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
    private RomImporter() {}
    static File romFile(File s){return romFile(s,GameProfile.LEO);}
    static File saveFile(File s){return saveFile(s,GameProfile.LEO);}
    static File stateFile(File s,int slot){return stateFile(s,GameProfile.LEO,slot);}
    static boolean isPlayable(File s){return isPlayable(s,GameProfile.LEO);}
    static File romFile(File s,GameProfile g){return new File(s,g.directory+"/game.nds");}
    static File saveFile(File s,GameProfile g){return new File(s,g.directory+"/battery.sav");}
    static File stateFile(File s,GameProfile g,int slot){return new File(s,g.directory+"/state"+slot+".sst.gz");}
    static boolean isPlayable(File s,GameProfile g){return romFile(s,g).length()==g.size&&new File(s,g.directory+"/import.json").isFile();}
    public static String importLeo(InputStream in,File s)throws Exception{return importGame(in,s,GameProfile.LEO);}
    static File extraRom(File s,String game){return romFile(s,game.equals("sf2")?GameProfile.ZERKER_NINJA:GameProfile.BLACK_ACE);}
    static String importExtra(InputStream in,File s,String game)throws Exception{return importGame(in,s,game.equals("sf2")?GameProfile.ZERKER_NINJA:GameProfile.BLACK_ACE);}

    static synchronized String importGame(InputStream input,File storage,GameProfile game)throws Exception {
        File staging=new File(storage,game.directory+"-staging"),current=new File(storage,game.directory),backup=new File(storage,game.directory+"-backup");
        if(!current.exists()&&backup.exists()&&!backup.renameTo(current))throw new IOException("Cannot recover previous import");
        if(isPlayable(storage,game))return game.label+" is already imported.";
        delete(staging);delete(backup);
        if(!staging.mkdirs())throw new IOException("Cannot create import directory");
        try {
            File rom=new File(staging,"game.nds");MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;
            try(OutputStream out=new FileOutputStream(rom)) {
                byte[] buffer=new byte[65536];int n;
                while((n=input.read(buffer))!=-1){total+=n;if(total>game.size)throw new IOException("Expected the 32 MiB US "+game.label+" ROM");digest.update(buffer,0,n);out.write(buffer,0,n);}
            }
            StringBuilder hash=new StringBuilder();for(byte b:digest.digest())hash.append(String.format(Locale.ROOT,"%02x",b&255));
            String code="";
            if(total>=16)try(RandomAccessFile f=new RandomAccessFile(rom,"r")){f.seek(12);byte[] h=new byte[4];f.readFully(h);code=new String(h,StandardCharsets.US_ASCII);}
            if(!game.matches(total,code,hash.toString()))throw new IOException("Wrong ROM for "+game.label+". Select the profiled US dump ("+game.code+").");
            String report="{\"schema\":2,\"game\":\""+game.id+"\",\"sha256\":\""+hash+"\"}";
            try(OutputStream out=new FileOutputStream(new File(staging,"import.json"))){out.write(report.getBytes(StandardCharsets.UTF_8));}
            // Copy (never move) saves before the atomic directory swap, so failure preserves them.
            File[] keep=current.listFiles((d,n)->n.endsWith(".sav")||n.endsWith(".sav.bak")||n.endsWith(".sst.gz"));
            if(keep!=null)for(File f:keep)java.nio.file.Files.copy(f.toPath(),new File(staging,f.getName()).toPath());
            if(current.exists()&&!current.renameTo(backup))throw new IOException("Cannot retain previous import");
            if(!staging.renameTo(current)){
                if(backup.exists()&&!backup.renameTo(current))throw new IOException("Import needs recovery");
                throw new IOException("Cannot commit imported ROM");
            }
            delete(backup);return game.label+" verified and ready to play.";
        }finally{delete(staging);}
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

