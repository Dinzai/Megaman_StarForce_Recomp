package io.dinzai.starforce;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Resolved set of ROM file replacements and memory codes for one boot. */
final class LaunchConfig {
    static final class Cheat {
        final String name, code;
        Cheat(String name, String code) { this.name = name; this.code = code; }
    }

    private final Map<String, String> files = new LinkedHashMap<>();
    private final Map<String, String> owners = new LinkedHashMap<>();
    private final List<Cheat> cheats = new ArrayList<>();
    final List<String> conflicts = new ArrayList<>();
    final List<String> activeMods = new ArrayList<>();

    /** Later mods override earlier ones; overrides are reported, not silently hidden. */
    void addFile(String romPath, String localPath, String modId) {
        String previous = owners.put(romPath, modId);
        if (previous != null && !previous.equals(modId))
            conflicts.add(romPath + ": " + modId + " overrides " + previous);
        files.put(romPath, localPath);
    }

    void addCheat(String name, String code) { cheats.add(new Cheat(name, code)); }

    int fileCount() { return files.size(); }
    int cheatCount() { return cheats.size(); }
    String[] fileKeys() { return files.keySet().toArray(new String[0]); }
    String[] filePaths() { return files.values().toArray(new String[0]); }
    String[] cheatNames() {
        String[] out = new String[cheats.size()];
        for (int i = 0; i < out.length; i++) out[i] = cheats.get(i).name;
        return out;
    }
    String[] cheatCodes() {
        String[] out = new String[cheats.size()];
        for (int i = 0; i < out.length; i++) out[i] = cheats.get(i).code;
        return out;
    }
}
