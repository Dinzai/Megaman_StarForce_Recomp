package io.dinzai.starforce;

import android.graphics.Color;
import org.json.JSONObject;

/** Data-only rendering extension, versioned independently of future gameplay hooks. */
public final class VisualMod {
    public final String name;
    public final int accent;
    public final float depth;
    public final boolean grid;
    private VisualMod(String name, int accent, float depth, boolean grid) {
        this.name=name; this.accent=accent; this.depth=depth; this.grid=grid;
    }
    public static VisualMod parse(String json) throws Exception {
        JSONObject root=new JSONObject(json);
        if (root.getInt("schema")!=1 || !root.getString("game").equals("leo")
                || !root.getString("type").equals("voxel-preview"))
            throw new IllegalArgumentException("Mod requires schema 1, game leo, type voxel-preview");
        String name=root.getString("name");
        if (name.trim().isEmpty() || name.length()>64) throw new IllegalArgumentException("Invalid mod name");
        JSONObject render=root.getJSONObject("render");
        double depth=render.getDouble("depth");
        if (!Double.isFinite(depth) || depth<0 || depth>8) throw new IllegalArgumentException("Depth must be 0–8");
        String color=render.getString("accent");
        if (!color.matches("#[0-9a-fA-F]{6}")) throw new IllegalArgumentException("Accent must be #RRGGBB");
        return new VisualMod(name,Color.parseColor(color),(float)depth,render.getBoolean("grid"));
    }
}
