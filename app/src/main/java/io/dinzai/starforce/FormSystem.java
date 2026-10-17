package io.dinzai.starforce;

import java.io.File;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One transformation system for every Star Force form.
 *
 * Pegasus, Leo and Dragon are Star Force 1's own forms: the battle code reads
 * Mega Man's starting form (0x021AF1BF) and runs its real transform routine.
 * Forms from Star Force 2/3 ride on that same routine with Fire Leo as the host
 * form (so transformation, weakness knock-out and effects all keep working) and
 * override, only while transformed, the charged-attack type (0x021AF022, low
 * byte; high byte = current form) and Mega Man's element (0x021ADC95). Their 3D
 * model replaces the host's model entry (models.bin #157), built from the
 * user's own SF2/SF3 ROM.
 *
 * Charged-attack types present in SF1's table: 1 Mega Buster, 3 Ice Slash,
 * 4 ground-wave slash, 5 missiles, 6 spinning rings, 7 orb blast, 8 Woody Shot
 * (multi-hit), 9 Flame Burner. Elements: 0 none, 1 fire, 2 aqua, 3 elec, 4 wood.
 */
final class FormSystem {
    static final String PREFIX = "transform.";

    static final class Spec {
        final String id, name, game, model, summary;
        final int host, charge, element;   // charge/element < 0 = keep the host's own
        Spec(String id, String name, String game, String model, int host, int charge, int element, String summary) {
            this.id = id; this.name = name; this.game = game; this.model = model;
            this.host = host; this.charge = charge; this.element = element; this.summary = summary;
        }
        boolean needsModel() { return !game.equals("sf1"); }
    }

    static final Spec[] FORMS = {
            new Spec("pegasus", "Ice Pegasus", "sf1", null, 1, -1, -1, "Aqua • Ice Slash • weak to Elec"),
            new Spec("leo", "Fire Leo", "sf1", null, 2, -1, -1, "Fire • Flame Burner • weak to Aqua"),
            new Spec("dragon", "Green Dragon", "sf1", null, 3, -1, -1, "Wood • Woody Shot • weak to Fire"),
            new Spec("zerker", "Zerker", "sf2", "pl001", 2, 4, 3, "Elec • slashing wave • from Star Force 2"),
            new Spec("ninja", "Ninja", "sf2", "pl002", 2, 8, 4, "Wood • multi-hit shot • from Star Force 2"),
            new Spec("saurian", "Saurian", "sf2", "pl003", 2, 5, 1, "Fire • missile cannon • from Star Force 2"),
            new Spec("black-ace", "Black Ace", "sf3", "pl010", 2, 6, 0, "No element • spinning rings • from Star Force 3"),
            new Spec("red-joker", "Red Joker", "sf3", "pl00f00", 2, 7, 0, "No element • orb blast • from Star Force 3"),
    };

    private FormSystem() {}

    static Spec find(String id) {
        for (Spec s : FORMS) if (s.id.equals(id)) return s;
        return null;
    }

    static File source(File storage, Spec s) {
        return s.game.equals("sf1") ? RomImporter.romFile(storage) : RomImporter.extraRom(storage, s.game);
    }

    static boolean available(File storage, Spec s) { return source(storage, s).isFile(); }

    static String code(Spec s) {
        StringBuilder c = new StringBuilder();
        c.append("# ").append(s.name).append(": start battles transformed (guarded: battle overlay loaded)\n");
        c.append("5217600C 1C05B570\n");
        c.append(String.format(java.util.Locale.ROOT, "221AF1BF %08X\n", s.host));
        if (s.charge >= 0 || s.element >= 0) {
            c.append("# while transformed (current form != 0)\n");
            c.append("A21AF022 00FF0000\n");
            if (s.charge >= 0) c.append(String.format(java.util.Locale.ROOT, "221AF022 %08X\n", s.charge));
            if (s.element >= 0) c.append(String.format(java.util.Locale.ROOT, "221ADC95 %08X\n", s.element));
        }
        c.append("D2000000 00000000\n");
        return c.toString();
    }

    /** Builds and enables the form's mod (disabling any other transformation). */
    static String apply(File storage, String id) throws Exception {
        Spec s = find(id);
        if (s == null) throw new IllegalArgumentException("Unknown form " + id);
        if (!available(storage, s))
            throw new IllegalStateException("Import your Star Force " + (s.game.equals("sf2") ? "2" : "3") + " ROM first");
        Map<String, byte[]> assets = new LinkedHashMap<>();
        if (s.needsModel())
            assets.put("datbin/com/models.bin@" + FormBuilder.SLOT_STARFORCE,
                    FormBuilder.build(source(storage, s), s.model, FormBuilder.SLOT_STARFORCE, 0));
        List<LaunchConfig.Cheat> cheats = Collections.singletonList(new LaunchConfig.Cheat(s.name, code(s)));
        ModManager mm = new ModManager(storage);
        String modId = PREFIX + s.id;
        mm.installGenerated(modId, "Transformation: " + s.name, s.summary, assets, cheats);
        mm.disablePrefix(PREFIX, modId);
        return s.name + " is active. Mega Man starts each battle transformed.";
    }

    static void off(File storage) throws Exception { new ModManager(storage).disablePrefix(PREFIX, null); }

    /** The enabled transformation's id, or null. */
    static String active(File storage) {
        ModManager mm = new ModManager(storage);
        for (Spec s : FORMS) if (mm.isEnabled(PREFIX + s.id)) return s.id;
        return null;
    }
}
