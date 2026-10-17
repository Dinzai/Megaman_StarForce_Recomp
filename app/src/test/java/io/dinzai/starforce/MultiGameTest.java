package io.dinzai.starforce;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import java.io.*;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class MultiGameTest {
    @Rule public TemporaryFolder tmp=new TemporaryFolder();
    @Test public void profilesAndSelection(){
        assertSame(GameProfile.ZERKER_NINJA,GameProfile.forSelection(3));
        assertSame(GameProfile.ZERKER_NINJA,GameProfile.forSelection(4));
        assertSame(GameProfile.BLACK_ACE,GameProfile.forSelection(6));
        assertNull(GameProfile.forSelection(7));
        for(GameProfile g:GameProfile.ALL){assertTrue(g.matches(g.size,g.code,g.sha256));assertFalse(g.matches(g.size,"AAAA",g.sha256));assertFalse(g.matches(g.size,g.code,"bad"));}
        assertThrows(IllegalArgumentException.class,()->GameProfile.fromId("../leo"));
    }
    // Leo imports must be real Star Force 1 saves (or blank), so its marker is the blank-save fill.
    private static byte mark(GameProfile g){return g==GameProfile.LEO?(byte)0xFF:(byte)g.code.charAt(0);}
    @Test public void savesStatesAndRomsAreIsolated()throws Exception {
        File root=tmp.newFolder();Set<String> paths=new HashSet<>();
        for(GameProfile g:GameProfile.ALL){
            assertTrue(paths.add(RomImporter.romFile(root,g).toString()));
            assertTrue(paths.add(RomImporter.saveFile(root,g).toString()));
            assertTrue(paths.add(RomImporter.stateFile(root,g,1).toString()));
            byte[] data=new byte[512];Arrays.fill(data,mark(g));
            SaveTransfer.importSave(new ByteArrayInputStream(data),root,g);
        }
        for(GameProfile g:GameProfile.ALL)assertEquals(mark(g),Files.readAllBytes(RomImporter.saveFile(root,g).toPath())[0]);
        assertEquals(RomImporter.extraRom(root,"sf2"),RomImporter.romFile(root,GameProfile.ZERKER_NINJA));
    }
    @Test public void invalidImportPreservesExistingSave()throws Exception {
        File root=tmp.newFolder();GameProfile g=GameProfile.BLACK_ACE;
        File save=RomImporter.saveFile(root,g);assertTrue(save.getParentFile().mkdirs());Files.write(save.toPath(),new byte[]{42});
        assertThrows(IOException.class,()->RomImporter.importGame(new ByteArrayInputStream(new byte[16]),root,g));
        assertArrayEquals(new byte[]{42},Files.readAllBytes(save.toPath()));assertFalse(RomImporter.isPlayable(root,g));
    }
    @Test public void modsUseSeparateNamespacesAndRejectWrongTargets()throws Exception {
        File root=tmp.newFolder();
        for(GameProfile g:GameProfile.ALL){
            ModManager mm=new ModManager(root,g);
            mm.installGenerated("same-id",g.label,"test",Collections.emptyMap(),Collections.singletonList(new LaunchConfig.Cheat("test","023FF000 00000001")));
            assertEquals(g.id,mm.installed().get(0).game);
            assertEquals(1,mm.launchConfig().cheatCount());
        }
        new ModManager(root,GameProfile.LEO).setEnabled("same-id",false);
        assertEquals(1,new ModManager(root,GameProfile.BLACK_ACE).launchConfig().cheatCount());
        String json="{\"schema\":2,\"game\":\"leo\",\"id\":\"wrong\",\"name\":\"Wrong\",\"cheats\":[{\"code\":\"023FF000 00000001\"}]}";
        ByteArrayOutputStream out=new ByteArrayOutputStream();try(ZipOutputStream z=new ZipOutputStream(out)){z.putNextEntry(new ZipEntry("mod.json"));z.write(json.getBytes(StandardCharsets.UTF_8));z.closeEntry();}
        assertThrows(IOException.class,()->new ModManager(root,GameProfile.BLACK_ACE).install(new ByteArrayInputStream(out.toByteArray())));
        assertEquals(1,new ModManager(root,GameProfile.BLACK_ACE).installed().size());
    }
    @Test public void lz11AllReferenceLengths()throws Exception {
        for(int count:new int[]{3,17,273}){
            ByteArrayOutputStream b=new ByteArrayOutputStream();int size=count+1;
            b.write(0x11);b.write(size&255);b.write(size>>8);b.write(0);b.write(0x40);b.write('A');
            b.write(count==3?new byte[]{0x20,0}:count==17?new byte[]{0,0,0}:new byte[]{0x10,0,0,0});
            byte[] expected=new byte[size];Arrays.fill(expected,(byte)'A');
            assertArrayEquals(expected,FormBuilder.lz11(b.toByteArray(),0,b.size(),size));
        }
        assertThrows(IOException.class,()->FormBuilder.lz11(new byte[]{0x11,4,0,0,(byte)0x80,0x20,0},0,7,4));
    }
}
