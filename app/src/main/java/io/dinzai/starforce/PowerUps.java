package io.dinzai.starforce;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Zerker × Ninja power-ups: abilities (HP+1000, Super Armor for free) and Blank Cards
 * that turn into the strongest Mega/Giga cards. Found by tracing the menus with sfhost
 * (see docs/POWERUPS.md).
 *
 * <ul>
 * <li>Ability records: 8 bytes at 0x020DFC90 + id × 8: +0 name index (mess.bin 1070),
 *     +1 sub-index, +3 effect type (0x25 HP+), +4 u16 amount, +6 u16 Link Power cost.
 *     Owned abilities: one byte per id at 0x0211D652; equipped: 48 bytes at 0x0211D622.</li>
 * <li>CardBox: 4 bytes per card number at 0x02119C50 + n × 4: +0 s16 sort stamp,
 *     +2 u8 copies owned (folder copies included). Mega/Giga cards are 256 + their
 *     index in mess.bin entry 1057; Blank Cards are 370-379.</li>
 * </ul>
 */
final class PowerUps {
    static final String ABILITIES = "powerups.abilities", BLANK = "powerups.blank-cards", GIVE = "powerups.give-cards";

    static final int ABILITY_TABLE = 0x020DFC90, OWNED = 0x0211D652, CARDBOX = 0x02119C50;
    static final int HP_ABILITY = 93;          // HP+500 (version with cost 300), becomes HP+1000
    static final int SUPER_ARMOR = 55;
    static final int NAMES = 1070, DESCRIPTIONS = 1069;

    /** Blank Card 370 + i becomes TARGETS[i]. */
    static final int[] TARGETS = {298, 299, 300, 294, 288, 297, 279, 291, 285, 282};
    static final String[] TARGET_NAMES = {
            "PegasusMagic (Giga, 450 aqua)", "LeoKingdom (Giga, Leo Blazer 3×3)", "DragonSky (Giga, Dragon Cyclone 3×3)",
            "LeoKingdom SP (Mega, 400 fire)", "CrownThunder SP (Mega, 330 elec)", "DragonSky SP (Mega, 330 wood)",
            "CygnusWing SP (Mega, 300)", "PegasusMagic SP (Mega, 260 aqua)", "WolfWoods SP (Mega, 170 wood)",
            "LibraScales SP (Mega, 150)"};

    private PowerUps() {}

    // --- abilities -------------------------------------------------------------------

    static String abilityCode() {
        int hp = ABILITY_TABLE + HP_ABILITY * 8, armor = ABILITY_TABLE + SUPER_ARMOR * 8;
        return "# HP+1000: HP+500 (v4) gives 1000 HP, costs 0 Link Power, uses its own name\n"
                + String.format(Locale.ROOT, "2%07X %08X\n", hp, HP_ABILITY)
                + String.format(Locale.ROOT, "0%07X %08X\n", hp + 4, 1000)
                + "# Super Armor costs 0 Link Power\n"
                + String.format(Locale.ROOT, "0%07X %08X\n", armor + 4, 0)
                + "# own both (equip them under Menu > Mega > Equip Abil.)\n"
                + String.format(Locale.ROOT, "2%07X 00000001\n", OWNED + HP_ABILITY)
                + String.format(Locale.ROOT, "2%07X 00000001\n", OWNED + SUPER_ARMOR);
    }

    /** "HP+1000" in the game's text encoding, with the end mark. */
    static byte[] hp1000Name() throws IOException {
        return new byte[]{0x28, 0x30, 0x0B, 0x11, 0x10, 0x10, 0x10, (byte) 0xE6};
    }

    /** The description with its "500" (digits 15 10 10) turned into "1000". */
    static byte[] hp1000Description(byte[] d) throws IOException {
        for (int i = 0; i + 2 < d.length; i++)
            if (d[i] == 0x15 && d[i + 1] == 0x10 && d[i + 2] == 0x10) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                out.write(d, 0, i);
                out.write(new byte[]{0x11, 0x10, 0x10, 0x10}, 0, 4);
                out.write(d, i + 3, d.length - i - 3);
                return out.toByteArray();
            }
        throw new IOException("Ability description not found; is this Zerker × Ninja?");
    }

    static Map<String, byte[]> abilityAssets(File rom) throws IOException {
        List<byte[]> mess = FormBuilder.archiveEntries(FormBuilder.readRomFile(rom, CardPacks.ZERKER_NINJA.namesFile));
        if (mess.size() <= NAMES) throw new IOException("Ability text not found; is this Zerker × Ninja?");
        List<byte[]> names = new ArrayList<>(CardPacks.splitNames(mess.get(NAMES)));
        List<byte[]> desc = new ArrayList<>(CardPacks.splitNames(mess.get(DESCRIPTIONS)));
        names.set(HP_ABILITY, hp1000Name());
        desc.set(HP_ABILITY, hp1000Description(desc.get(HP_ABILITY)));
        Map<String, byte[]> out = new LinkedHashMap<>();
        out.put(CardPacks.ZERKER_NINJA.namesFile + "@" + NAMES, CardPacks.joinNames(names));
        out.put(CardPacks.ZERKER_NINJA.namesFile + "@" + DESCRIPTIONS, CardPacks.joinNames(desc));
        return out;
    }

    // --- Blank Cards -------------------------------------------------------------------

    static int entry(int card) { return CARDBOX + card * 4; }

    /** Each Blank Card you own is swapped for its target card (one copy each). */
    static String blankCode() {
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < TARGETS.length; i++) {
            int blank = entry(370 + i), target = entry(TARGETS[i]);
            c.append("# Blank Card ").append(i + 1).append(" -> ").append(TARGET_NAMES[i]).append('\n');
            c.append(String.format(Locale.ROOT, "A%07X FF000000\n", blank + 2));   // if this Blank Card is owned
            c.append(String.format(Locale.ROOT, "2%07X 00000000\n", blank + 2));   //   remove it
            c.append(String.format(Locale.ROOT, "D9000000 %08X\n", target));       //   target count + 1
            c.append("D4000000 00010000\n");
            c.append(String.format(Locale.ROOT, "D6000000 %08X\n", target));
            c.append("D2000000 00000000\n");
        }
        return c.toString();
    }

    /** All ten target cards right away (only when you have none of that card). */
    static String giveCode() {
        StringBuilder c = new StringBuilder();
        for (int i = 0; i < TARGETS.length; i++) {
            int target = entry(TARGETS[i]);
            c.append("# ").append(TARGET_NAMES[i]).append('\n');
            c.append(String.format(Locale.ROOT, "9%07X FF000000\n", target + 2));  // if you have none
            c.append(String.format(Locale.ROOT, "2%07X 00000001\n", target + 2));
            c.append("D2000000 00000000\n");
        }
        return c.toString();
    }

    // --- install -------------------------------------------------------------------------

    static String install(File storage, String id) throws Exception {
        GameProfile g = GameProfile.ZERKER_NINJA;
        ModManager mm = new ModManager(storage, g);
        switch (id) {
            case ABILITIES:
                mm.installGenerated(ABILITIES, "Abilities: HP+1000 & Super Armor",
                        "Owns HP+1000 and Super Armor, both free to equip (Menu > Mega > Equip Abil.).",
                        abilityAssets(RomImporter.romFile(storage, g)),
                        Collections.singletonList(new LaunchConfig.Cheat("HP+1000 & Super Armor", abilityCode())));
                return "HP+1000 and Super Armor are yours. Equip them under Menu > Mega > Equip Abil.";
            case BLANK:
                mm.installGenerated(BLANK, "Blank Cards: Mega & Giga",
                        "Every Blank Card you find turns into one of the strongest Mega/Giga cards.",
                        new LinkedHashMap<>(), Collections.singletonList(new LaunchConfig.Cheat("Blank Cards", blankCode())));
                return "Blank Cards now turn into Mega and Giga cards as soon as you have them.";
            case GIVE:
                mm.installGenerated(GIVE, "Blank Cards: all ten now",
                        "Puts all ten Blank Card results in your CardBox right away.",
                        new LinkedHashMap<>(), Collections.singletonList(new LaunchConfig.Cheat("All ten cards", giveCode())));
                return "All ten cards are in your CardBox. Giga cards still count 1 per folder.";
            default:
                throw new IllegalArgumentException("Unknown power-up " + id);
        }
    }

    static void off(File storage, String id) throws Exception {
        new ModManager(storage, GameProfile.ZERKER_NINJA).setEnabled(id, false);
    }

    static boolean active(File storage, String id) {
        return new ModManager(storage, GameProfile.ZERKER_NINJA).isEnabled(id);
    }
}
