package io.dinzai.starforce;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Game mods (schema 2): a .zip with a mod.json that replaces ROM files and/or
 * adds Action Replay-style memory codes. Packages are data only: nothing in a
 * mod is executed by the app, and every path is validated before extraction.
 * See docs/MODS.md.
 */
final class ModManager {
    static final int SCHEMA = 2;
    static final long MAX_PACKAGE = 256L * 1024 * 1024;
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
    private static final Pattern ROM_PATH = Pattern.compile("(#[0-9]{1,4})|([A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+){0,7})");
    private static final Pattern ZIP_PATH = Pattern.compile("[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+){0,7}");

    static final class Mod {
        final String id, name, version, author, description, game;
        final Map<String, String> files;      // ROM path -> package-relative path
        final List<LaunchConfig.Cheat> cheats;
        Mod(String game, String id, String name, String version, String author, String description,
            Map<String, String> files, List<LaunchConfig.Cheat> cheats) {
            this.game = game; this.id = id; this.name = name; this.version = version; this.author = author;
            this.description = description; this.files = files; this.cheats = cheats;
        }
        String summary() {
            List<String> parts = new ArrayList<>();
            long entries = files.keySet().stream().filter(k -> k.contains("@")).count();
            long whole = files.size() - entries;
            if (whole > 0) parts.add(whole + (whole == 1 ? " file" : " files"));
            if (entries > 0) parts.add(entries + (entries == 1 ? " asset" : " assets"));
            if (!cheats.isEmpty()) parts.add(cheats.size() + (cheats.size() == 1 ? " code" : " codes"));
            return String.join(" • ", parts);
        }
    }

    private final File root;
    private final GameProfile game;

    ModManager(File storage) { this(storage,GameProfile.LEO); }
    ModManager(File storage,GameProfile game) { this.game=game; root=new File(storage,game==GameProfile.LEO?"mods":"mods-"+game.id); }

    static Mod parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        if (o.getInt("schema") != SCHEMA) throw new IOException("Game mods need \"schema\": 2");
        String target=o.getString("game");
        try {GameProfile.fromId(target);} catch(IllegalArgumentException e){throw new IOException(e.getMessage());}
        String id = o.getString("id");
        if (!ID.matcher(id).matches()) throw new IOException("Invalid mod id (use a-z, 0-9, '.', '_' or '-')");
        String name = o.getString("name").trim();
        if (name.isEmpty() || name.length() > 64) throw new IOException("Mod name must be 1–64 characters");
        Map<String, String> files = new LinkedHashMap<>();
        JSONObject f = o.optJSONObject("files");
        if (f != null) {
            for (Iterator<String> it = f.keys(); it.hasNext(); ) {
                String romPath = it.next(), local = f.getString(romPath);
                if (!validRomPath(romPath)) throw new IOException("Invalid ROM path: " + romPath);
                if (!validZipPath(local)) throw new IOException("Invalid package path: " + local);
                files.put(romPath, local);
            }
        }
        // "entries": {"datbin/com/models.bin": {"157": "files/form.bmd"}} replaces single
        // entries of a Leo archive; encoded as "path@157" keys for the native patcher.
        JSONObject en = o.optJSONObject("entries");
        if (en != null) {
            for (Iterator<String> it = en.keys(); it.hasNext(); ) {
                String romPath = it.next();
                if (!validRomPath(romPath) || romPath.equals("arm9") || romPath.equals("arm7"))
                    throw new IOException("Invalid archive path: " + romPath);
                JSONObject idx = en.getJSONObject(romPath);
                for (Iterator<String> jt = idx.keys(); jt.hasNext(); ) {
                    String n = jt.next(), local = idx.getString(n);
                    if (!n.matches("[0-9]{1,5}")) throw new IOException("Invalid archive entry: " + n);
                    if (!validZipPath(local)) throw new IOException("Invalid package path: " + local);
                    files.put(romPath + "@" + Integer.parseInt(n), local);
                }
            }
        }
        List<LaunchConfig.Cheat> cheats = new ArrayList<>();
        JSONArray c = o.optJSONArray("cheats");
        if (c != null) {
            if (c.length() > 256) throw new IOException("Too many codes");
            for (int i = 0; i < c.length(); i++) {
                JSONObject e = c.getJSONObject(i);
                String code = e.getString("code");
                validateCheat(code);
                cheats.add(new LaunchConfig.Cheat(e.optString("name", "code " + (i + 1)), code));
            }
        }
        if (files.isEmpty() && cheats.isEmpty()) throw new IOException("Mod has no files or codes");
        return new Mod(target, id, name, o.optString("version", ""), o.optString("author", ""),
                o.optString("description", ""), files, cheats);
    }

    static boolean validRomPath(String p) {
        if (p.equals("arm9") || p.equals("arm7")) return true;
        return ROM_PATH.matcher(p).matches() && !p.contains("..");
    }

    static boolean validZipPath(String p) {
        return ZIP_PATH.matcher(p).matches() && !p.contains("..") && !p.equals("mod.json");
    }

    /** Mirrors the native parser so bad codes fail at install time, not at boot. */
    static void validateCheat(String code) throws IOException {
        int words = 0;
        for (String line : code.split("\n")) {
            int hash = line.indexOf('#');
            if (hash >= 0) line = line.substring(0, hash);
            int semi = line.indexOf(';');
            if (semi >= 0) line = line.substring(0, semi);
            for (String w : line.trim().split("\\s+")) {
                if (w.isEmpty()) continue;
                if (!w.matches("[0-9A-Fa-f]{8}")) throw new IOException("Code word must be 8 hex digits: " + w);
                words++;
            }
        }
        if (words == 0 || words % 2 != 0) throw new IOException("Codes must be pairs of 8-digit hex words");
        if (words > 8192) throw new IOException("Code list too long");
    }

    /** Installs or upgrades a mod package; returns the installed mod. */
    synchronized Mod install(InputStream input) throws Exception {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create mod directory");
        File staging = new File(root, ".staging");
        erase(staging);
        if (!staging.mkdirs()) throw new IOException("Cannot create staging directory");
        try {
            Set<String> names = new HashSet<>();
            long total = 0;
            byte[] buffer = new byte[65536];
            try (ZipInputStream zip = new ZipInputStream(input)) {
                ZipEntry e;
                while ((e = zip.getNextEntry()) != null) {
                    if (e.isDirectory()) continue;
                    String name = e.getName();
                    if (!(name.equals("mod.json") || validZipPath(name)) || !names.add(name) || names.size() > 4096)
                        throw new IOException("Invalid or duplicate package path: " + name);
                    File out = new File(staging, name);
                    File parent = out.getParentFile();
                    if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("Cannot create " + name);
                    try (OutputStream os = new FileOutputStream(out)) {
                        int n;
                        while ((n = zip.read(buffer)) > 0) {
                            total += n;
                            if (total > MAX_PACKAGE) throw new IOException("Mod exceeds 256 MiB");
                            if (name.equals("mod.json") && out.length() + n > 65536) throw new IOException("mod.json exceeds 64 KiB");
                            os.write(buffer, 0, n);
                        }
                    }
                }
            }
            File manifest = new File(staging, "mod.json");
            if (!manifest.isFile()) throw new IOException("Package has no mod.json");
            Mod mod = parse(readText(manifest));
            if(!game.id.equals(mod.game))throw new IOException("This mod targets "+mod.game+", not "+game.label);
            for (String local : mod.files.values())
                if (!new File(staging, local).isFile()) throw new IOException("Package is missing " + local);

            File target = new File(root, mod.id), backup = new File(root, ".backup");
            erase(backup);
            if (target.exists() && !target.renameTo(backup)) throw new IOException("Cannot replace previous version");
            if (!staging.renameTo(target)) {
                if (backup.exists()) backup.renameTo(target);
                throw new IOException("Cannot commit mod");
            }
            erase(backup);
            State s = state();
            if (!s.order.contains(mod.id)) { s.order.add(mod.id); s.enabled.add(mod.id); }
            save(s);
            return mod;
        } finally {
            erase(staging);
        }
    }

    /**
     * Installs a mod generated on the device (e.g. a transformation built from the
     * user's own ROMs). `assets` maps "path@entry" keys to file contents.
     */
    synchronized Mod installGenerated(String id, String name, String description, Map<String, byte[]> assets) throws Exception {
        return installGenerated(id, name, description, assets, Collections.emptyList());
    }

    synchronized Mod installGenerated(String id, String name, String description, Map<String, byte[]> assets,
                                      List<LaunchConfig.Cheat> cheats) throws Exception {
        if (!ID.matcher(id).matches()) throw new IOException("Invalid mod id");
        JSONObject entries = new JSONObject();
        File staging = new File(root, ".staging");
        erase(staging);
        if (!new File(staging, "files").mkdirs()) throw new IOException("Cannot create staging directory");
        try {
            int n = 0;
            for (Map.Entry<String, byte[]> a : assets.entrySet()) {
                String key = a.getKey();
                int at = key.lastIndexOf('@');
                String local = "files/asset" + (n++) + ".bin";
                try (OutputStream out = new FileOutputStream(new File(staging, local))) { out.write(a.getValue()); }
                JSONObject table = entries.optJSONObject(key.substring(0, at));
                if (table == null) { table = new JSONObject(); entries.put(key.substring(0, at), table); }
                table.put(key.substring(at + 1), local);
            }
            JSONObject o = new JSONObject();
            o.put("schema", SCHEMA).put("id", id).put("name", name).put("game", game.id).put("version", "generated")
                    .put("author", "Star Force Recomp").put("description", description);
            if (entries.length() > 0) o.put("entries", entries);
            if (!cheats.isEmpty()) {
                JSONArray ca = new JSONArray();
                for (LaunchConfig.Cheat c : cheats) ca.put(new JSONObject().put("name", c.name).put("code", c.code));
                o.put("cheats", ca);
            }
            try (OutputStream out = new FileOutputStream(new File(staging, "mod.json"))) { out.write(o.toString(2).getBytes(StandardCharsets.UTF_8)); }
            Mod mod = parse(readText(new File(staging, "mod.json")));
            File target = new File(root, id);
            erase(target);
            if (!staging.renameTo(target)) throw new IOException("Cannot commit mod");
            State s = state();
            if (!s.order.contains(id)) s.order.add(id);
            s.enabled.add(id);
            save(s);
            return mod;
        } finally {
            erase(staging);
        }
    }

    /** Disables every enabled mod whose id starts with `prefix` (e.g. one transformation per slot). */
    synchronized void disablePrefix(String prefix, String except) throws IOException {
        State s = state();
        s.enabled.removeIf(id -> id.startsWith(prefix) && !id.equals(except));
        save(s);
    }

    synchronized List<Mod> installed() {
        List<Mod> out = new ArrayList<>();
        for (String id : state().order) {
            try { Mod m=parse(readText(new File(new File(root,id),"mod.json"))); if(game.id.equals(m.game))out.add(m); }
            catch (Exception ignored) { /* a broken folder is skipped, not fatal */ }
        }
        return out;
    }

    synchronized boolean isEnabled(String id) { return state().enabled.contains(id); }

    synchronized void setEnabled(String id, boolean enabled) throws IOException {
        State s = state();
        if (enabled) s.enabled.add(id); else s.enabled.remove(id);
        save(s);
    }

    /** Moves a mod later in the load order (later wins on conflicts). */
    synchronized void move(String id, int delta) throws IOException {
        State s = state();
        int i = s.order.indexOf(id), j = i + delta;
        if (i < 0 || j < 0 || j >= s.order.size()) return;
        Collections.swap(s.order, i, j);
        save(s);
    }

    synchronized void remove(String id) throws IOException {
        if (!ID.matcher(id).matches()) return;
        State s = state();
        s.order.remove(id); s.enabled.remove(id);
        save(s);
        erase(new File(root, id));
    }

    synchronized LaunchConfig launchConfig() {
        LaunchConfig cfg = new LaunchConfig();
        State s = state();
        for (Mod m : installed()) {
            if (!s.enabled.contains(m.id)) continue;
            cfg.activeMods.add(m.name);
            File dir = new File(root, m.id);
            for (Map.Entry<String, String> f : m.files.entrySet())
                cfg.addFile(f.getKey(), new File(dir, f.getValue()).getPath(), m.id);
            for (LaunchConfig.Cheat c : m.cheats) cfg.addCheat(m.name + ": " + c.name, c.code);
        }
        return cfg;
    }

    // --- persistence --------------------------------------------------------

    private static final class State {
        final List<String> order = new ArrayList<>();
        final Set<String> enabled = new LinkedHashSet<>();
    }

    private State state() {
        State s = new State();
        File f = new File(root, "state.json");
        if (!f.isFile()) return s;
        try {
            JSONObject o = new JSONObject(readText(f));
            JSONArray order = o.getJSONArray("order"), enabled = o.getJSONArray("enabled");
            for (int i = 0; i < order.length(); i++) {
                String id = order.getString(i);
                if (ID.matcher(id).matches() && !s.order.contains(id)) s.order.add(id);
            }
            for (int i = 0; i < enabled.length(); i++) if (s.order.contains(enabled.getString(i))) s.enabled.add(enabled.getString(i));
        } catch (Exception ignored) { }
        return s;
    }

    private void save(State s) throws IOException {
        if (!root.isDirectory() && !root.mkdirs()) throw new IOException("Cannot create mod directory");
        JSONObject o = new JSONObject();
        try {
            o.put("order", new JSONArray(s.order));
            o.put("enabled", new JSONArray(new ArrayList<>(s.enabled)));
        } catch (org.json.JSONException e) { throw new IOException(e); }
        File tmp = new File(root, "state.json.tmp");
        try (OutputStream out = new FileOutputStream(tmp)) { out.write(o.toString().getBytes(StandardCharsets.UTF_8)); }
        if (!tmp.renameTo(new File(root, "state.json"))) throw new IOException("Cannot save mod state");
    }

    static String readText(File f) throws IOException {
        if (f.length() > 1024 * 1024) throw new IOException("File too large: " + f.getName());
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[8192];
            int n;
            while ((n = in.read(b)) > 0) out.write(b, 0, n);
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }

    static void erase(File f) {
        if (!f.exists()) return;
        File[] children = f.isDirectory() ? f.listFiles() : null;
        if (children != null) for (File c : children) erase(c);
        f.delete();
    }
}

