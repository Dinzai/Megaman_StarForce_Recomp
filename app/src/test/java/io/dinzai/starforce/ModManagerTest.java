package io.dinzai.starforce;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.Assert.*;

public class ModManagerTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static byte[] zip(String[][] entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            for (String[] e : entries) {
                z.putNextEntry(new ZipEntry(e[0]));
                z.write(e[1].getBytes(StandardCharsets.UTF_8));
                z.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static String manifest(String id, String files, String cheats) {
        return "{\"schema\":2,\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"game\":\"leo\"" +
                (files == null ? "" : ",\"files\":" + files) + (cheats == null ? "" : ",\"cheats\":" + cheats) + "}";
    }

    @Test public void parsesFilesAndCheats() throws Exception {
        ModManager.Mod m = ModManager.parse(manifest("a.b", "{\"datbin/com/capcomlogo.bin\":\"files/logo.bin\",\"#661\":\"files/m.bin\"}",
                "[{\"name\":\"hp\",\"code\":\"12345678 9ABCDEF0\\n# note\\n\"}]"));
        assertEquals(2, m.files.size());
        assertEquals(1, m.cheats.size());
        assertEquals("2 files • 1 code", m.summary());
    }

    @Test public void parsesArchiveEntries() throws Exception {
        ModManager.Mod m = ModManager.parse("{\"schema\":2,\"id\":\"t\",\"name\":\"T\",\"game\":\"leo\"," +
                "\"entries\":{\"datbin/com/models.bin\":{\"157\":\"files/pl.bmd\",\"158\":\"files/ef.bmd\"}}}");
        assertEquals("files/pl.bmd", m.files.get("datbin/com/models.bin@157"));
        assertEquals("2 assets", m.summary());
        for (String bad : new String[]{"{\"../x\":{\"1\":\"files/a\"}}", "{\"a.bin\":{\"-1\":\"files/a\"}}", "{\"a.bin\":{\"1\":\"../a\"}}"}) {
            try { ModManager.parse("{\"schema\":2,\"id\":\"t\",\"name\":\"T\",\"game\":\"leo\",\"entries\":" + bad + "}"); fail(bad); }
            catch (Exception expected) { }
        }
    }

    @Test public void rejectsUnsafeOrInvalidManifests() {
        String[] bad = {
                manifest("../x", "{\"a.bin\":\"files/a\"}", null),
                manifest("ok", "{\"../a.bin\":\"files/a\"}", null),
                manifest("ok", "{\"a.bin\":\"../escape\"}", null),
                manifest("ok", "{\"a.bin\":\"/abs\"}", null),
                manifest("ok", null, "[{\"code\":\"1234 5678\"}]"),
                manifest("ok", null, "[{\"code\":\"12345678\"}]"),
                manifest("ok", null, null),
                "{\"schema\":1,\"id\":\"ok\",\"name\":\"x\",\"game\":\"leo\",\"files\":{\"a\":\"b\"}}",
                "{\"schema\":2,\"id\":\"ok\",\"name\":\"x\",\"game\":\"pegasus\",\"files\":{\"a\":\"b\"}}",
        };
        for (String json : bad) {
            try { ModManager.parse(json); fail("accepted " + json); } catch (Exception expected) { }
        }
    }

    @Test public void installsTogglesOrdersAndResolvesConflicts() throws Exception {
        File storage = tmp.newFolder();
        ModManager mm = new ModManager(storage);
        mm.install(new ByteArrayInputStream(zip(new String[][]{
                {"mod.json", manifest("first", "{\"datbin/eng/mess.bin\":\"files/mess.bin\"}", null)},
                {"files/mess.bin", "one"}, {"README.txt", "ignored"}})));
        mm.install(new ByteArrayInputStream(zip(new String[][]{
                {"mod.json", manifest("second", "{\"datbin/eng/mess.bin\":\"files/mess.bin\"}", "[{\"code\":\"023FF000 00000001\"}]")},
                {"files/mess.bin", "two"}})));
        assertEquals(2, mm.installed().size());

        LaunchConfig cfg = mm.launchConfig();
        assertEquals(1, cfg.fileCount());
        assertTrue(cfg.filePaths()[0].endsWith("second/files/mess.bin"));
        assertEquals(1, cfg.conflicts.size());
        assertEquals(1, cfg.cheatCount());

        mm.move("second", -1);
        assertTrue(mm.launchConfig().filePaths()[0].endsWith("first/files/mess.bin"));

        mm.setEnabled("first", false);
        cfg = mm.launchConfig();
        assertTrue(cfg.filePaths()[0].endsWith("second/files/mess.bin"));
        assertTrue(cfg.conflicts.isEmpty());

        mm.remove("second");
        assertEquals(1, mm.installed().size());
        assertEquals(0, mm.launchConfig().fileCount());
        assertFalse(new File(storage, "mods/second").exists());
    }

    @Test public void rejectsPackagesWithMissingFilesOrTraversal() throws Exception {
        ModManager mm = new ModManager(tmp.newFolder());
        try {
            mm.install(new ByteArrayInputStream(zip(new String[][]{{"mod.json", manifest("x", "{\"a.bin\":\"files/a.bin\"}", null)}})));
            fail("missing file accepted");
        } catch (IOException expected) { }
        try {
            mm.install(new ByteArrayInputStream(zip(new String[][]{{"../evil", "x"}, {"mod.json", manifest("x", null, "[{\"code\":\"00000000 00000000\"}]")}})));
            fail("traversal accepted");
        } catch (IOException expected) { }
        assertTrue(mm.installed().isEmpty());
    }

    @Test public void generatedModsInstallAndOneTransformPerSlot() throws Exception {
        File storage = tmp.newFolder();
        ModManager mm = new ModManager(storage);
        java.util.Map<String, byte[]> a = new java.util.HashMap<>();
        a.put("datbin/com/models.bin@157", new byte[]{1, 2, 3});
        mm.installGenerated("transform.starforce.pegasus", "Pegasus", "d", a);
        mm.installGenerated("transform.starforce.zerker", "Zerker", "d", a);
        mm.disablePrefix("transform.starforce.", "transform.starforce.zerker");
        assertFalse(mm.isEnabled("transform.starforce.pegasus"));
        assertTrue(mm.isEnabled("transform.starforce.zerker"));
        LaunchConfig cfg = mm.launchConfig();
        assertEquals(1, cfg.fileCount());
        assertEquals("datbin/com/models.bin@157", cfg.fileKeys()[0]);
        assertTrue(cfg.filePaths()[0].contains("transform.starforce.zerker"));
    }

    @Test public void realFormModsAreCheatOnlyAndExclusive() throws Exception {
        ModManager mm = new ModManager(tmp.newFolder());
        for (int f = 1; f <= 3; f++) {
            mm.installGenerated("transform.real." + f, "Real " + f, "d", new java.util.HashMap<>(),
                    java.util.Collections.singletonList(new LaunchConfig.Cheat("f", FormBuilder.realFormCode(f))));
            mm.disablePrefix("transform.real.", "transform.real." + f);
        }
        LaunchConfig cfg = mm.launchConfig();
        assertEquals(0, cfg.fileCount());
        assertEquals(1, cfg.cheatCount());
        assertTrue(cfg.cheatCodes()[0].contains("221AF1BF 00000003"));
        assertTrue(cfg.cheatCodes()[0].startsWith("#") && cfg.cheatCodes()[0].contains("5217600C 1C05B570"));
    }

    @Test public void upgradeReplacesPreviousVersion() throws Exception {
        File storage = tmp.newFolder();
        ModManager mm = new ModManager(storage);
        for (String v : new String[]{"old", "new"}) {
            mm.install(new ByteArrayInputStream(zip(new String[][]{
                    {"mod.json", manifest("same", "{\"a.bin\":\"files/a.bin\"}", null)}, {"files/a.bin", v}})));
        }
        assertEquals(1, mm.installed().size());
        assertEquals("new", ModManager.readText(new File(storage, "mods/same/files/a.bin")));
    }
}
