package io.dinzai.starforce;

import android.content.res.AssetManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Custom battle cards for Leo (see docs/CARDS.md).
 *
 * A card pack is JSON ("format": "starforce-cards") that re-skins standard cards:
 * each entry names a base card whose attack it keeps, plus a new name, damage,
 * element and picture (copied from another card). Packs are stored in
 * files/cards; all enabled packs are combined into one generated mod
 * ("battle-cards") holding Action Replay codes for the card table in ARM9
 * static data and a rebuilt card-name table (mess.bin entry 1249) made from the
 * user's own ROM.
 */
final class CardPacks {
    static final String MOD_ID = "battle-cards";
    static final int TABLE_RAM = 0x020B8E7C, RECORD = 0x2C, LAST = 149, NAME_MAX = 10;
    static final String NAMES_FILE = "datbin/eng/mess.bin";
    static final int NAMES_ENTRY = 1249;
    static final String[] ELEMENTS = {"none", "fire", "aqua", "elec", "wood"};
    private static final Pattern ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");

    static final class Card {
        final String base, name, element, picture;
        final int damage;  // -1 = keep
        Card(String base, String name, int damage, String element, String picture) {
            this.base = base; this.name = name; this.damage = damage; this.element = element; this.picture = picture;
        }
    }

    static final class Pack {
        final String id, name, author, description;
        final List<Card> cards;
        Pack(String id, String name, String author, String description, List<Card> cards) {
            this.id = id; this.name = name; this.author = author; this.description = description; this.cards = cards;
        }
    }

    private final File storage, dir;

    CardPacks(File storage) { this.storage = storage; this.dir = new File(storage, "cards"); }

    // --- parsing --------------------------------------------------------------

    static Pack parse(String json) throws Exception {
        JSONObject o = new JSONObject(json);
        if (!"starforce-cards".equals(o.optString("format")))
            throw new IOException("Not a card pack (format must be \"starforce-cards\")");
        String game = o.optString("game", "leo");
        if (!game.equals("leo")) throw new IOException("Card packs currently support Leo only");
        String id = o.optString("id", o.optString("name", "").toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-"));
        id = id.replaceAll("^-+|-+$", "");
        if (!ID.matcher(id).matches()) throw new IOException("Card pack needs an id (a-z, 0-9, . _ -)");
        JSONArray a = o.optJSONArray("cards");
        if (a == null || a.length() == 0) throw new IOException("Card pack has no cards");
        if (a.length() > LAST) throw new IOException("Too many cards");
        List<Card> cards = new ArrayList<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject c = a.getJSONObject(i);
            String base = c.optString("base", "");
            if (base.isEmpty()) throw new IOException("Card " + (i + 1) + " needs a \"base\" card");
            String name = c.has("name") ? c.getString("name") : null;
            if (name != null) encodeName(name);  // validates
            int damage = c.has("damage") ? c.getInt("damage") : -1;
            if (c.has("damage") && (damage < 0 || damage > 999)) throw new IOException("Damage must be 0-999");
            String element = c.has("element") ? c.getString("element").toLowerCase(Locale.ROOT) : null;
            if (element != null && index(ELEMENTS, element) < 0) throw new IOException("Unknown element " + element);
            String picture = c.has("picture") ? c.get("picture").toString() : null;
            cards.add(new Card(base, name, damage, element, picture));
        }
        return new Pack(id, o.optString("name", id), o.optString("author", ""), o.optString("description", ""), cards);
    }

    // --- card names (mess.bin entry 1249) --------------------------------------

    static byte[] encodeName(String s) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (char ch : s.toCharArray()) {
            if (ch >= 'A' && ch <= 'Z') out.write(0x21 + ch - 'A');
            else if (ch >= 'a' && ch <= 'z') out.write(0x41 + ch - 'a');
            else if (ch >= '0' && ch <= '9') out.write(0x10 + ch - '0');
            else if (ch == ' ') out.write(0);
            else if (ch == '-') out.write(0x0D);
            else throw new IOException("Card names may use letters, digits, space and - only: " + s);
        }
        if (out.size() < 1 || out.size() > NAME_MAX) throw new IOException("Card names must be 1-" + NAME_MAX + " characters: " + s);
        return out.toByteArray();
    }

    static String decodeName(byte[] d, int start, int end) {
        StringBuilder s = new StringBuilder();
        for (int i = start; i < end; i++) {
            int b = d[i] & 0xFF;
            if (b == 0xE6) break;
            if (b >= 0x21 && b <= 0x3A) s.append((char) ('A' + b - 0x21));
            else if (b >= 0x41 && b <= 0x5A) s.append((char) ('a' + b - 0x41));
            else if (b >= 0x10 && b <= 0x19) s.append((char) ('0' + b - 0x10));
            else if (b == 0) s.append(' ');
            else if (b == 0x0D) s.append('-');
            else s.append('?');
        }
        return s.toString();
    }

    static List<byte[]> splitNames(byte[] entry) throws IOException {
        if (entry.length < 2) throw new IOException("Bad name table");
        int n = FormBuilder.u16(entry, 0) / 2;
        if (n < 1 || n * 2 > entry.length) throw new IOException("Bad name table");
        List<byte[]> out = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int a = FormBuilder.u16(entry, i * 2), b = i + 1 < n ? FormBuilder.u16(entry, i * 2 + 2) : entry.length;
            if (a < n * 2 || b < a || b > entry.length) throw new IOException("Bad name table");
            byte[] s = new byte[b - a];
            System.arraycopy(entry, a, s, 0, s.length);
            out.add(s);
        }
        return out;
    }

    static byte[] joinNames(List<byte[]> strings) throws IOException {
        int at = strings.size() * 2;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] s : strings) { out.write(at & 0xFF); out.write(at >> 8); at += s.length; }
        if (at > 0xFFFF) throw new IOException("Name table too large");
        for (byte[] s : strings) out.write(s);
        return out.toByteArray();
    }

    static List<String> names(byte[] entry) throws IOException {
        List<String> out = new ArrayList<>();
        for (byte[] s : splitNames(entry)) out.add(decodeName(s, 0, s.length));
        return out;
    }

    static byte[] namesEntry(File rom) throws IOException {
        List<byte[]> e = FormBuilder.archiveEntries(FormBuilder.readRomFile(rom, NAMES_FILE));
        if (e.size() <= NAMES_ENTRY) throw new IOException("Card names not found; is this Leo?");
        return e.get(NAMES_ENTRY);
    }

    static int lookup(List<String> names, String ref) throws IOException {
        int n = -1;
        if (ref.matches("\\d+")) n = Integer.parseInt(ref);
        else for (int i = 1; i <= LAST && i < names.size(); i++) if (names.get(i).equalsIgnoreCase(ref)) { n = i; break; }
        if (n < 1 || n > LAST) throw new IOException("Unknown standard card: " + ref);
        return n;
    }

    // --- code generation --------------------------------------------------------

    static String cardCode(int card, int damage, String element, int picture) {
        int addr = TABLE_RAM + card * RECORD;
        StringBuilder c = new StringBuilder("# card ").append(card).append('\n');
        if (picture > 0) {
            c.append(String.format(Locale.ROOT, "D3000000 %08X\n", TABLE_RAM + picture * RECORD + 0x24));
            c.append(String.format(Locale.ROOT, "%08X 00000008\n", 0xF0000000 | (addr + 0x24)));
            c.append("D2000000 00000000\n");
        }
        if (element != null) c.append(String.format(Locale.ROOT, "%08X %08X\n", 0x20000000 | addr, 1 + 4 * index(ELEMENTS, element)));
        if (damage >= 0) c.append(String.format(Locale.ROOT, "%08X %08X\n", 0x10000000 | (addr + 6), damage));
        return c.toString();
    }

    /** Cheat text and rebuilt name table for a list of packs; later packs win when two change one card. */
    static Object[] build(byte[] namesEntry, List<Pack> packs) throws IOException {
        List<String> names = names(namesEntry);
        List<byte[]> strings = splitNames(namesEntry);
        Map<Integer, String> code = new LinkedHashMap<>();
        boolean renamed = false;
        for (Pack p : packs) for (Card c : p.cards) {
            int base = lookup(names, c.base);
            int pic = c.picture == null ? -1 : lookup(names, c.picture);
            code.remove(base);
            code.put(base, cardCode(base, c.damage, c.element, pic));
            if (c.name != null) {
                byte[] enc = encodeName(c.name), s = new byte[enc.length + 2];
                System.arraycopy(enc, 0, s, 0, enc.length);
                s[enc.length] = (byte) 0xE6;
                strings.set(base, s);
                renamed = true;
            }
        }
        StringBuilder all = new StringBuilder();
        for (String s : code.values()) all.append(s);
        return new Object[]{all.toString(), renamed ? joinNames(strings) : null};
    }

    // --- storage ------------------------------------------------------------------

    synchronized List<Pack> installed() {
        List<Pack> out = new ArrayList<>();
        File[] files = dir.listFiles((d, n) -> n.endsWith(".json"));
        if (files == null) return out;
        java.util.Arrays.sort(files);
        for (File f : files) {
            try { out.add(parse(read(new FileInputStream(f)))); } catch (Exception ignored) { }
        }
        return out;
    }

    synchronized Set<String> enabled() {
        Set<String> s = new LinkedHashSet<>();
        try {
            for (String line : read(new FileInputStream(new File(dir, "enabled.txt"))).split("\n"))
                if (!line.trim().isEmpty()) s.add(line.trim());
        } catch (IOException ignored) { }
        return s;
    }

    private void saveEnabled(Set<String> s) throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create cards folder");
        try (OutputStream out = new FileOutputStream(new File(dir, "enabled.txt"))) {
            out.write(String.join("\n", s).getBytes(StandardCharsets.UTF_8));
        }
    }

    /** Validates and stores a pack (replacing one with the same id), enables it and rebuilds. */
    synchronized Pack install(String json) throws Exception {
        Pack p = parse(json);
        byte[] entry = namesEntry(RomImporter.romFile(storage));
        build(entry, Collections.singletonList(p));  // resolve every card name before saving
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create cards folder");
        try (OutputStream out = new FileOutputStream(new File(dir, p.id + ".json"))) {
            out.write(json.getBytes(StandardCharsets.UTF_8));
        }
        Set<String> en = enabled();
        en.add(p.id);
        saveEnabled(en);
        rebuild();
        return p;
    }

    /** Copies the packs bundled with the app into storage (disabled) if they are missing. */
    synchronized void installBundled(AssetManager assets) {
        try {
            String[] list = assets.list("cards");
            if (list == null) return;
            if (!dir.isDirectory() && !dir.mkdirs()) return;
            for (String n : list) {
                File f = new File(dir, n);
                if (f.exists() || !n.endsWith(".json")) continue;
                try (InputStream in = assets.open("cards/" + n); OutputStream out = new FileOutputStream(f)) {
                    byte[] b = new byte[4096]; int k;
                    while ((k = in.read(b)) > 0) out.write(b, 0, k);
                }
            }
        } catch (IOException ignored) { }
    }

    synchronized void setEnabled(String id, boolean on) throws Exception {
        Set<String> en = enabled();
        if (on) en.add(id); else en.remove(id);
        saveEnabled(en);
        rebuild();
    }

    synchronized void remove(String id) throws Exception {
        if (!ID.matcher(id).matches()) return;
        Set<String> en = enabled();
        en.remove(id);
        saveEnabled(en);
        if (!new File(dir, id + ".json").delete()) throw new IOException("Could not remove " + id);
        rebuild();
    }

    /** Regenerates the combined "battle-cards" mod from the enabled packs. */
    synchronized void rebuild() throws Exception {
        ModManager mm = new ModManager(storage);
        Set<String> en = enabled();
        List<Pack> packs = new ArrayList<>();
        for (Pack p : installed()) if (en.contains(p.id)) packs.add(p);
        if (packs.isEmpty()) { mm.remove(MOD_ID); return; }
        Object[] r = build(namesEntry(RomImporter.romFile(storage)), packs);
        Map<String, byte[]> assets = new LinkedHashMap<>();
        if (r[1] != null) assets.put(NAMES_FILE + "@" + NAMES_ENTRY, (byte[]) r[1]);
        StringBuilder label = new StringBuilder();
        for (Pack p : packs) label.append(label.length() == 0 ? "" : ", ").append(p.name);
        mm.installGenerated(MOD_ID, "Battle cards", label.toString(), assets,
                Collections.singletonList(new LaunchConfig.Cheat("Battle cards", (String) r[0])));
    }

    // --- helpers --------------------------------------------------------------------

    private static int index(String[] a, String v) {
        for (int i = 0; i < a.length; i++) if (a[i].equals(v)) return i;
        return -1;
    }

    static String read(InputStream in) throws IOException {
        try (InputStream i = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] b = new byte[4096]; int n;
            while ((n = i.read(b)) > 0) {
                if (out.size() + n > 256 * 1024) throw new IOException("Card pack too large");
                out.write(b, 0, n);
            }
            return out.toString(StandardCharsets.UTF_8.name());
        }
    }
}
