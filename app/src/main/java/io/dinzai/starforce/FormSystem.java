package io.dinzai.starforce;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One transformation system for every Star Force form, in Leo and in Zerker × Ninja.
 *
 * <p><b>Leo.</b> Pegasus, Leo and Dragon are Star Force 1's own forms: the battle
 * code reads Mega Man's starting form (0x021AF1BF) and runs its real transform
 * routine. Forms from Star Force 2/3 ride on that routine with Fire Leo as the
 * host and override, only while transformed, the charged-attack type
 * (0x021AF022, low byte; high byte = current form) and Mega Man's element
 * (0x021ADC95). Their model replaces the host's model entry (models.bin #157).
 *
 * <p><b>Zerker × Ninja.</b> At battle setup the battle overlay (overlay 875)
 * picks Mega Man's starting state from flag bits in its per-player setup block
 * (0x0219D1CC, +0x118): bit 13 Zerker, 14 Ninja, 15 Saurian, 17 Tribe King, then
 * runs the game's own Tribe On routine. Setting one bit starts every battle in
 * that Tribe On form with all of its real moves. Forms from the other games
 * ride on a tribe host and replace the host's model and texture entries.
 *
 * <p>Charged-attack types in SF1's table: 1 Mega Buster, 3 Ice Slash, 4 ground-wave
 * slash, 5 missiles, 6 spinning rings, 7 orb blast, 8 Woody Shot (multi-hit),
 * 9 Flame Burner. Elements: 0 none, 1 fire, 2 aqua, 3 elec, 4 wood.
 */
final class FormSystem {
    static final String PREFIX = "transform.";

    static final class Spec {
        final GameProfile target;           // the game this form plays in
        final String id, name, game, model, summary;   // game/model: where the look comes from (null model = host's own)
        final int host, charge, element;    // Leo: host form 1-3; charge/element < 0 = keep the host's own
        final int tribeBit;                 // Zerker × Ninja: start flag bit
        final String hostModel;             // Zerker × Ninja: host model whose entries get replaced
        Spec(GameProfile target, String id, String name, String game, String model, int host, int charge, int element,
             int tribeBit, String hostModel, String summary) {
            this.target = target; this.id = id; this.name = name; this.game = game; this.model = model;
            this.host = host; this.charge = charge; this.element = element;
            this.tribeBit = tribeBit; this.hostModel = hostModel; this.summary = summary;
        }
        boolean needsModel() { return model != null; }
    }

    private static Spec leo(String id, String name, String game, String model, int host, int charge, int element, String summary) {
        return new Spec(GameProfile.LEO, id, name, game, model, host, charge, element, 0, null, summary);
    }

    private static Spec zn(String id, String name, String game, String model, int bit, String hostModel, String summary) {
        return new Spec(GameProfile.ZERKER_NINJA, id, name, game, model, 0, -1, -1, bit, hostModel, summary);
    }

    static final int ZN_ZERKER = 13, ZN_NINJA = 14, ZN_SAURIAN = 15, ZN_TRIBE_KING = 17;

    static final Spec[] FORMS = {
            leo("pegasus", "Ice Pegasus", "sf1", null, 1, -1, -1, "Aqua • Ice Slash • weak to Elec"),
            leo("leo", "Fire Leo", "sf1", null, 2, -1, -1, "Fire • Flame Burner • weak to Aqua"),
            leo("dragon", "Green Dragon", "sf1", null, 3, -1, -1, "Wood • Woody Shot • weak to Fire"),
            leo("zerker", "Zerker", "sf2", "pl001", 2, 4, 3, "Elec • slashing wave • from Star Force 2"),
            leo("ninja", "Ninja", "sf2", "pl002", 2, 8, 4, "Wood • multi-hit shot • from Star Force 2"),
            leo("saurian", "Saurian", "sf2", "pl003", 2, 5, 1, "Fire • missile cannon • from Star Force 2"),
            leo("black-ace", "Black Ace", "sf3", "pl010", 2, 6, 0, "No element • spinning rings • from Star Force 3"),
            leo("red-joker", "Red Joker", "sf3", "pl00f00", 2, 7, 0, "No element • orb blast • from Star Force 3"),
    };

    static final Spec[] ZN_FORMS = {
            zn("zerker", "Zerker", "sf2", null, ZN_ZERKER, "pl001", "Elec • Tribe On • Zerker's own moves"),
            zn("ninja", "Ninja", "sf2", null, ZN_NINJA, "pl002", "Wood • Tribe On • Ninja's own moves"),
            zn("saurian", "Saurian", "sf2", null, ZN_SAURIAN, "pl003", "Fire • Tribe On • Saurian's own moves"),
            zn("tribe-king", "Tribe King", "sf2", null, ZN_TRIBE_KING, "pl00a", "Tribe King • all three tribes' power"),
            zn("pegasus", "Ice Pegasus", "sf1", "pl001", ZN_ZERKER, "pl001", "Pegasus's look on Zerker's moves • from Leo"),
            zn("leo", "Fire Leo", "sf1", "pl003", ZN_SAURIAN, "pl003", "Leo's look on Saurian's fire moves • from Leo"),
            zn("dragon", "Green Dragon", "sf1", "pl002", ZN_NINJA, "pl002", "Dragon's look on Ninja's wood moves • from Leo"),
            zn("black-ace", "Black Ace", "sf3", "pl010", ZN_TRIBE_KING, "pl00a", "Black Ace's look on Tribe King's moves • from Star Force 3"),
            zn("red-joker", "Red Joker", "sf3", "pl00f00", ZN_TRIBE_KING, "pl00a", "Red Joker's look on Tribe King's moves • from Star Force 3"),
    };

    private FormSystem() {}

    static Spec[] forms(GameProfile g) {
        if (g == GameProfile.LEO) return FORMS;
        if (g == GameProfile.ZERKER_NINJA) return ZN_FORMS;
        return new Spec[0];
    }

    static boolean supports(GameProfile g) { return forms(g).length > 0; }

    static Spec find(String id) { return find(GameProfile.LEO, id); }

    static Spec find(GameProfile g, String id) {
        for (Spec s : forms(g)) if (s.id.equals(id)) return s;
        return null;
    }

    /** ROM the form's look comes from (the target game itself when it uses its own model). */
    static File source(File storage, Spec s) {
        if (s.model == null) return RomImporter.romFile(storage, s.target);
        if (s.game.equals("sf1")) return RomImporter.romFile(storage, GameProfile.LEO);
        return RomImporter.extraRom(storage, s.game);
    }

    static boolean available(File storage, Spec s) { return source(storage, s).isFile(); }

    static String code(Spec s) {
        StringBuilder c = new StringBuilder();
        if (s.target == GameProfile.ZERKER_NINJA) {
            c.append("# ").append(s.name).append(": start battles in Tribe On (guarded: battle overlay loaded)\n");
            c.append("52143B3C D1052804\n");
            c.append("D9000000 0219D2E4\n");               // datareg = start flags
            c.append("D4000002 FFFC1FFF\n");               // clear the tribe bits 13-17
            c.append(String.format(Locale.ROOT, "D4000001 %08X\n", 1 << s.tribeBit));
            c.append("D6000000 0219D2E4\n");               // store
            c.append("D2000000 00000000\n");
            return c.toString();
        }
        c.append("# ").append(s.name).append(": start battles transformed (guarded: battle overlay loaded)\n");
        c.append("5217600C 1C05B570\n");
        c.append(String.format(Locale.ROOT, "221AF1BF %08X\n", s.host));
        if (s.charge >= 0 || s.element >= 0) {
            c.append("# while transformed (current form != 0)\n");
            c.append("A21AF022 00FF0000\n");
            if (s.charge >= 0) c.append(String.format(Locale.ROOT, "221AF022 %08X\n", s.charge));
            if (s.element >= 0) c.append(String.format(Locale.ROOT, "221ADC95 %08X\n", s.element));
        }
        c.append("D2000000 00000000\n");
        return c.toString();
    }

    static String apply(File storage, String id) throws Exception { return apply(storage, GameProfile.LEO, id); }

    /** Builds and enables the form's mod for a game (disabling any other transformation). */
    static String apply(File storage, GameProfile g, String id) throws Exception {
        Spec s = find(g, id);
        if (s == null) throw new IllegalArgumentException("Unknown form " + id);
        if (!available(storage, s)) {
            String need = s.game.equals("sf1") ? "Leo" : s.game.equals("sf2") ? "Zerker × Ninja" : "Black Ace";
            throw new IllegalStateException("Import your " + need + " ROM first");
        }
        Map<String, byte[]> assets = new LinkedHashMap<>();
        if (s.needsModel()) {
            if (g == GameProfile.LEO) {
                assets.put("datbin/com/models.bin@" + FormBuilder.SLOT_STARFORCE,
                        FormBuilder.build(source(storage, s), s.model, FormBuilder.SLOT_STARFORCE, 0));
            } else {
                File targetRom = RomImporter.romFile(storage, g);
                int entry = FormBuilder.findModelEntry(targetRom, s.hostModel);
                byte[] bmd = FormBuilder.build(source(storage, s), s.model, s.hostModel, FormBuilder.RIG_BONES, 0);
                assets.put("datbin/com/models.bin@" + entry, bmd);
                // the host binds textures from the entries after its model; give them the new look's textures
                byte[] btx = FormBuilder.textureContainer(bmd);
                for (int t : FormBuilder.textureEntries(targetRom, entry)) assets.put("datbin/com/models.bin@" + t, btx);
            }
        }
        // overworld: recolour Mega Man's field sprites to match the form
        assets.putAll(OverworldPalette.assets(RomImporter.romFile(storage, g), g, s.id));
        // a custom overworld sprite sheet for this form replaces Mega Man's main field sprites
        assets.putAll(FieldSprite.assets(storage, g, s.id));
        List<LaunchConfig.Cheat> cheats = Collections.singletonList(new LaunchConfig.Cheat(s.name, code(s)));
        ModManager mm = new ModManager(storage, g);
        String modId = PREFIX + s.id;
        mm.installGenerated(modId, "Transformation: " + s.name, s.summary, assets, cheats);
        mm.disablePrefix(PREFIX, modId);
        return s.name + " is active. Mega Man starts each battle transformed.";
    }

    static void off(File storage) throws Exception { off(storage, GameProfile.LEO); }

    static void off(File storage, GameProfile g) throws Exception { new ModManager(storage, g).disablePrefix(PREFIX, null); }

    static String active(File storage) { return active(storage, GameProfile.LEO); }

    /** The enabled transformation's id for a game, or null. */
    static String active(File storage, GameProfile g) {
        ModManager mm = new ModManager(storage, g);
        for (Spec s : forms(g)) if (mm.isEnabled(PREFIX + s.id)) return s.id;
        return null;
    }

    static List<String> ids(GameProfile g) {
        List<String> out = new ArrayList<>();
        for (Spec s : forms(g)) out.add(s.id);
        return out;
    }
}
