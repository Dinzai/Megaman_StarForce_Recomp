package io.dinzai.starforce;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Launcher: pick a game, pick its mods, play. Everything else lives in Settings.
 */
public final class MainActivity extends Activity {
    private static final int REQ_ROM = 1, REQ_MOD_ZIP = 4, REQ_EXPORT_SAVE = 5, REQ_IMPORT_SAVE = 6, REQ_CARDS = 9, REQ_SPRITE = 10;
    private String pendingForm;

    private static final int BG = Color.rgb(7, 13, 26), CARD = Color.rgb(17, 31, 56), CARD_HI = Color.rgb(24, 46, 82),
            ACCENT = Color.rgb(51, 214, 255), ORANGE = Color.rgb(255, 138, 42), TEXT = Color.WHITE,
            DIM = Color.rgb(150, 170, 196), LINE = Color.rgb(36, 58, 96);

    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private GameProfile game = GameProfile.LEO;
    private GameProfile pendingGame = GameProfile.LEO;
    private boolean busy = false;
    private String message = null;
    private LinearLayout body;
    private Runnable screen = this::home;
    private boolean atHome = true;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        try { game = GameProfile.fromId(prefs.getString("game", "leo")); } catch (IllegalArgumentException e) { game = GameProfile.LEO; }
        getWindow().setStatusBarColor(BG);
        getWindow().setNavigationBarColor(BG);
        for (GameProfile g : GameProfile.ALL) if (CardPacks.layout(g) != null) new CardPacks(getFilesDir(), g).installBundled(getAssets());
        migrateOldTransformations();
        home();
    }

    @Override protected void onResume() {
        super.onResume();
        if (body != null && !busy) screen.run();
    }

    /** Pre-0.7 builds used several transformation slots; keep only the unified one. */
    private void migrateOldTransformations() {
        try {
            ModManager mm = new ModManager(getFilesDir());
            for (ModManager.Mod m : mm.installed())
                if (m.id.startsWith("transform.") && FormSystem.find(GameProfile.LEO, m.id.substring(FormSystem.PREFIX.length())) == null
                        && mm.isEnabled(m.id)) mm.setEnabled(m.id, false);
        } catch (Exception ignored) { }
    }

    // --- UI building blocks ------------------------------------------------------

    private int dp(float n) { return (int) (n * getResources().getDisplayMetrics().density + .5f); }

    private LinearLayout page(String title, boolean back) {
        ScrollView scroll = new ScrollView(this);
        scroll.setBackgroundColor(BG);
        scroll.setFillViewport(true);
        body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(16), dp(20), dp(28));
        scroll.addView(body);
        setContentView(scroll);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            v.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                    insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
            return insets;
        });
        scroll.requestApplyInsets();
        if (back) {
            LinearLayout bar = new LinearLayout(this);
            bar.setGravity(Gravity.CENTER_VERTICAL);
            TextView arrow = text("‹", 30, ACCENT, true);
            arrow.setPadding(0, 0, dp(12), dp(4));
            bar.addView(arrow);
            bar.addView(text(title, 22, TEXT, true));
            bar.setOnClickListener(v -> home());
            bar.setPadding(0, dp(4), 0, dp(12));
            body.addView(bar);
        }
        if (message != null) {
            TextView m = text(message, 14, TEXT, false);
            m.setBackground(round(Color.rgb(20, 60, 90), 12));
            m.setPadding(dp(14), dp(10), dp(14), dp(10));
            body.addView(m, margins(-1, 0, 0, 0, 12));
        }
        return body;
    }

    private TextView text(String s, float size, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(size);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        return t;
    }

    private GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(radius));
        return g;
    }

    private LinearLayout.LayoutParams margins(int w, int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private LinearLayout card(String heading) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(round(CARD, 18));
        c.setPadding(dp(4), dp(6), dp(4), dp(6));
        if (heading != null) {
            TextView h = text(heading.toUpperCase(java.util.Locale.ROOT), 12, DIM, true);
            h.setLetterSpacing(0.08f);
            h.setPadding(dp(14), dp(8), dp(14), dp(4));
            c.addView(h);
        }
        body.addView(c, margins(-1, 0, 0, 0, 14));
        return c;
    }

    /** A tappable settings-style row: title, subtitle and a value on the right. */
    private View row(LinearLayout parent, String title, String subtitle, String value, boolean enabled, Runnable action) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(12), dp(14), dp(12));
        r.setMinimumHeight(dp(56));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, enabled ? TEXT : DIM, true));
        if (subtitle != null && !subtitle.isEmpty()) {
            TextView s = text(subtitle, 13, DIM, false);
            s.setPadding(0, dp(2), 0, 0);
            labels.addView(s);
        }
        r.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (value != null) {
            TextView v = text(value, 14, enabled ? ACCENT : DIM, true);
            v.setMaxWidth(dp(150));
            v.setEllipsize(TextUtils.TruncateAt.END);
            v.setSingleLine(true);
            v.setPadding(dp(10), 0, 0, 0);
            r.addView(v);
        }
        if (action != null) {
            TextView chev = text("›", 22, DIM, false);
            chev.setPadding(dp(10), 0, 0, dp(2));
            r.addView(chev);
            r.setEnabled(enabled && !busy);
            r.setClickable(true);
            r.setFocusable(true);
            r.setBackground(pressable());
            r.setOnClickListener(v -> action.run());
        }
        parent.addView(r);
        return r;
    }

    private Drawable pressable() {
        android.content.res.TypedArray a = obtainStyledAttributes(new int[]{android.R.attr.selectableItemBackground});
        Drawable d = a.getDrawable(0);
        a.recycle();
        return d;
    }

    private Switch toggleRow(LinearLayout parent, String title, String subtitle, boolean on, java.util.function.Consumer<Boolean> change) {
        LinearLayout r = new LinearLayout(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPadding(dp(14), dp(10), dp(10), dp(10));
        r.setMinimumHeight(dp(56));
        LinearLayout labels = new LinearLayout(this);
        labels.setOrientation(LinearLayout.VERTICAL);
        labels.addView(text(title, 16, TEXT, true));
        if (subtitle != null) { TextView s = text(subtitle, 13, DIM, false); s.setPadding(0, dp(2), dp(8), 0); labels.addView(s); }
        r.addView(labels, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch sw = new Switch(this);
        sw.setChecked(on);
        sw.setEnabled(!busy);
        sw.setOnCheckedChangeListener((b, v) -> change.accept(v));
        r.addView(sw);
        r.setOnClickListener(v -> sw.toggle());
        parent.addView(r);
        return sw;
    }

    private void divider(LinearLayout parent) {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, dp(1) / 2));
        p.setMargins(dp(14), 0, dp(14), 0);
        parent.addView(v, p);
    }

    private Button bigButton(String label, int color, boolean enabled, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(18);
        b.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        b.setTextColor(enabled ? Color.rgb(4, 16, 30) : DIM);
        b.setBackground(round(enabled ? color : CARD, 28));
        b.setEnabled(enabled && !busy);
        b.setMinHeight(dp(56));
        b.setStateListAnimator(null);
        b.setOnClickListener(v -> action.run());
        body.addView(b, margins(-1, 0, 4, 0, 16));
        return b;
    }

    private Button smallButton(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(ACCENT);
        b.setBackground(round(CARD_HI, 18));
        b.setStateListAnimator(null);
        b.setEnabled(!busy);
        b.setOnClickListener(v -> action.run());
        parent.addView(b, margins(-1, 0, 4, 0, 6));
        return b;
    }

    // --- home ------------------------------------------------------------------------

    private boolean imported(GameProfile g) { return RomImporter.isPlayable(getFilesDir(), g); }

    private void home() {
        screen = this::home;
        atHome = true;
        page(null, false);
        // header
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        ImageView icon = new ImageView(this);
        icon.setImageDrawable(getApplicationInfo().loadIcon(getPackageManager()));
        head.addView(icon, new LinearLayout.LayoutParams(dp(52), dp(52)));
        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPadding(dp(12), 0, 0, 0);
        titles.addView(text("Star Force Recomp", 24, TEXT, true));
        titles.addView(text("v" + BuildConfig.VERSION_NAME, 12, DIM, false));
        head.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView gear = text("⚙", 24, DIM, false);
        gear.setPadding(dp(12), dp(8), dp(4), dp(8));
        gear.setBackground(pressable());
        gear.setOnClickListener(v -> settings());
        head.addView(gear);
        body.addView(head, margins(-1, 0, 4, 0, 18));
        if (message != null) {   // page() put the message first; move it under the header
            View m = body.getChildAt(0);
            body.removeViewAt(0);
            body.addView(m);
        }

        // game picker
        TextView pick = text("GAME", 12, DIM, true);
        pick.setLetterSpacing(0.08f);
        pick.setPadding(dp(4), 0, 0, dp(8));
        body.addView(pick);
        LinearLayout games = new LinearLayout(this);
        for (GameProfile g : GameProfile.ALL) {
            LinearLayout tile = new LinearLayout(this);
            tile.setOrientation(LinearLayout.VERTICAL);
            tile.setPadding(dp(12), dp(12), dp(12), dp(12));
            boolean sel = g == game;
            GradientDrawable bg = round(sel ? CARD_HI : CARD, 16);
            if (sel) bg.setStroke(dp(2), ACCENT);
            tile.setBackground(bg);
            TextView name = text(g.label, 15, TEXT, true);
            name.setMaxLines(2);
            tile.addView(name);
            boolean ok = imported(g);
            TextView st = text(ok ? "● Ready" : "Import ROM", 12, ok ? ACCENT : DIM, false);
            st.setPadding(0, dp(6), 0, 0);
            tile.addView(st);
            tile.setClickable(true);
            tile.setOnClickListener(v -> { game = g; prefs.edit().putString("game", g.id).apply(); message = null; home(); });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(84), 1);
            p.setMargins(g == GameProfile.ALL[0] ? 0 : dp(5), 0, g == GameProfile.ALL[GameProfile.ALL.length - 1] ? 0 : dp(5), 0);
            games.addView(tile, p);
        }
        body.addView(games, margins(-1, 0, 0, 0, 16));

        boolean ok = imported(game);
        if (busy) bigButton("Working…", ACCENT, false, () -> { });
        else if (ok) bigButton("▶  Play " + game.label, ACCENT, true, this::play);
        else {
            boolean onDisk = RomImporter.romFile(getFilesDir(), game).isFile();
            bigButton("Import your " + game.label + " ROM", ORANGE, true, () -> pick(REQ_ROM, game));
            if (onDisk) {
                LinearLayout c = new LinearLayout(this);
                c.setOrientation(LinearLayout.VERTICAL);
                body.addView(c);
                smallButton(c, "Check the ROM already imported", () -> verifyExisting(game));
            }
        }

        // mods for this game
        LinearLayout mods = card("Mods for " + game.label);
        if (FormSystem.supports(game)) {
            String form = FormSystem.active(getFilesDir(), game);
            FormSystem.Spec fs = form == null ? null : FormSystem.find(game, form);
            row(mods, "Transformation", fs == null ? "Mega Man starts battles in his normal form" : fs.summary,
                    fs == null ? "None" : fs.name, ok, this::chooseTransformation);
            if (fs != null && FieldSprite.mainEntries(game).length > 0) {
                divider(mods);
                boolean custom = FieldSprite.sheetFile(getFilesDir(), game, fs.id).isFile();
                row(mods, "Overworld sprite", custom ? "Your sprite sheet for " + fs.name : "Mega Man recoloured as " + fs.name,
                        custom ? "Custom" : "Recolour", ok, () -> overworldSprite(fs));
            }
        } else {
            String cur = activeModelSwap(game);
            row(mods, "Battle model", "Mega Man's look in battle, from any imported game",
                    cur == null ? "Original" : cur, ok, this::chooseModelSwap);
        }
        if (CardPacks.layout(game) != null) {
            divider(mods);
            CardPacks cp = new CardPacks(getFilesDir(), game);
            int on = 0;
            for (CardPacks.Pack p : cp.installed()) if (cp.enabled().contains(p.id)) on++;
            row(mods, "Battle cards", "Custom cards built on " + game.label + "'s own attacks",
                    on == 0 ? "Off" : on + (on == 1 ? " pack" : " packs"), ok, this::cardsScreen);
        }
        if (game == GameProfile.ZERKER_NINJA) {
            divider(mods);
            powerUpRow(mods, PowerUps.ABILITIES, "HP+1000 & Super Armor", "Abilities you own, free to equip (Menu › Mega › Equip Abil.)", ok);
            divider(mods);
            powerUpRow(mods, PowerUps.BLANK, "Blank Cards → Mega/Giga", "Each Blank Card turns into a top card, like PegasusMagic Giga", ok);
            divider(mods);
            powerUpRow(mods, PowerUps.GIVE, "All ten cards now", "Puts all ten Blank Card results in your CardBox", ok);
        }
        divider(mods);
        boolean gl3 = gles3();
        String screens = prefs.getString("voxel_screens", "bottom");
        toggleRow(mods, "Voxel 3D mode", gl3 ? "Draws the game as 3D voxels (" + screensLabel(screens) + ")" : "Needs OpenGL ES 3",
                gl3 && prefs.getBoolean("voxel_ingame", false), v -> { prefs.edit().putBoolean("voxel_ingame", v && gl3).apply(); home(); });
        if (gl3 && prefs.getBoolean("voxel_ingame", false)) {
            row(mods, "3D screens", "Which DS screens are drawn in 3D", screensLabel(screens), true, () -> {
                String[] keys = {"bottom", "both", "top"};
                String[] labels = {"Bottom screen (field)", "Both screens", "Top screen"};
                new AlertDialog.Builder(this).setTitle("3D screens")
                        .setSingleChoiceItems(labels, java.util.Arrays.asList(keys).indexOf(screens), (d, i) -> {
                            prefs.edit().putString("voxel_screens", keys[i]).apply();
                            d.dismiss();
                            home();
                        }).show();
            });
        }
        divider(mods);
        int others = otherMods(game).size();
        row(mods, "More mods", "Mod packages (.zip) for " + game.label, others == 0 ? "None" : String.valueOf(others), true, this::modsScreen);

        TextView note = text("You supply your own ROM; nothing from the games ships with this app.", 12, DIM, false);
        note.setPadding(dp(4), dp(8), dp(4), 0);
        body.addView(note);
    }

    private void powerUpRow(LinearLayout parent, String id, String title, String subtitle, boolean enabled) {
        Switch sw = toggleRow(parent, title, subtitle, PowerUps.active(getFilesDir(), id), v -> {
            if (v) runBusy("Building " + title + "…", () -> PowerUps.install(getFilesDir(), id));
            else runBusy("Turning off " + title + "…", () -> { PowerUps.off(getFilesDir(), id); return title + " is off."; });
        });
        sw.setEnabled(enabled);
    }

    private static String screensLabel(String s) {
        return s.equals("both") ? "both screens" : s.equals("top") ? "top screen" : "bottom screen";
    }

    private boolean gles3() {
        android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
        return am != null && am.getDeviceConfigurationInfo().reqGlEsVersion >= 0x30000;
    }

    private void play() {
        startActivity(new Intent(this, GameActivity.class).putExtra(GameProfile.EXTRA_GAME, game.id));
    }

    // --- transformations (Leo) ------------------------------------------------------

    private void chooseTransformation() {
        File files = getFilesDir();
        List<String> labels = new ArrayList<>(), ids = new ArrayList<>();
        labels.add("None");
        ids.add(null);
        GameProfile target = game;
        for (FormSystem.Spec f : FormSystem.forms(target)) {
            boolean have = FormSystem.available(files, f);
            String from = f.game.equals("sf1") ? "  · needs Leo" : f.game.equals("sf2") ? "  · needs Zerker × Ninja" : "  · needs Black Ace";
            labels.add(f.name + (have ? "" : from));
            ids.add(f.id);
        }
        String cur = FormSystem.active(files, target);
        new AlertDialog.Builder(this)
                .setTitle("Transformation")
                .setSingleChoiceItems(labels.toArray(new String[0]), Math.max(0, ids.indexOf(cur)), (d, i) -> {
                    d.dismiss();
                    String id = ids.get(i);
                    if (id == null) {
                        try { FormSystem.off(files, target); message = "Transformation off."; } catch (Exception e) { message = e.getMessage(); }
                        home();
                        return;
                    }
                    FormSystem.Spec f = FormSystem.find(target, id);
                    if (!FormSystem.available(files, f)) {
                        GameProfile need = f.game.equals("sf1") ? GameProfile.LEO : f.game.equals("sf2") ? GameProfile.ZERKER_NINJA : GameProfile.BLACK_ACE;
                        new AlertDialog.Builder(this).setTitle(f.name)
                                .setMessage(f.name + "'s model comes from your " + need.label + " ROM. Import it now? (It also makes "
                                        + need.label + " playable.)")
                                .setPositiveButton("Import", (x, y) -> pick(REQ_ROM, need))
                                .setNegativeButton("Cancel", null).show();
                        return;
                    }
                    runBusy("Building " + f.name + "…", () -> FormSystem.apply(files, target, id));
                })
                .show();
    }

    private void overworldSprite(FormSystem.Spec fs) {
        GameProfile target = game;
        File sheet = FieldSprite.sheetFile(getFilesDir(), target, fs.id);
        // AlertDialog hides setItems() when a message is set, so the choices are buttons
        AlertDialog.Builder dlg = new AlertDialog.Builder(this).setTitle("Overworld sprite: " + fs.name)
                .setMessage("A sheet has 8 rows (facing N, NE, E, SE, S, SW, W, NW) of 7 poses: standing, then 6 running frames, on a transparent background. See docs/SPRITES.md.")
                .setPositiveButton("Import sheet (.png)", (d, i) -> { pendingForm = fs.id; pick(REQ_SPRITE, target); })
                .setNegativeButton("Cancel", null);
        if (sheet.isFile())
            dlg.setNeutralButton("Use recolour", (d, i) -> {
                if (!sheet.delete()) { toast("Could not remove the sheet"); return; }
                runBusy("Rebuilding " + fs.name + "…", () -> FormSystem.apply(getFilesDir(), target, fs.id));
            });
        dlg.show();
    }

    // --- model swaps (SF2/SF3) ---------------------------------------------------------

    private String activeModelSwap(GameProfile target) {
        ModManager mm = new ModManager(getFilesDir(), target);
        for (ModManager.Mod m : mm.installed())
            if (m.id.startsWith("transform.base.") && mm.isEnabled(m.id)) return m.name.replace(" model", "");
        return null;
    }

    private void chooseModelSwap() {
        GameProfile target = game;
        File files = getFilesDir();
        List<String> labels = new ArrayList<>();
        List<FormBuilder.Form> forms = new ArrayList<>();
        labels.add("Original");
        forms.add(null);
        for (FormBuilder.Form f : FormBuilder.FORMS) {
            File src = f.game.equals("sf1") ? RomImporter.romFile(files) : RomImporter.extraRom(files, f.game);
            if (!src.isFile()) continue;
            labels.add(f.label);
            forms.add(f);
        }
        new AlertDialog.Builder(this)
                .setTitle("Battle model for " + target.label)
                .setItems(labels.toArray(new String[0]), (d, i) -> {
                    FormBuilder.Form f = forms.get(i);
                    if (f == null) {
                        try { new ModManager(files, target).disablePrefix("transform.base.", null); message = "Original model restored."; }
                        catch (IOException e) { message = e.getMessage(); }
                        home();
                        return;
                    }
                    File src = f.game.equals("sf1") ? RomImporter.romFile(files) : RomImporter.extraRom(files, f.game);
                    runBusy("Building " + f.label + "…", () -> applyCrossGameModel(target, src, f.model, f.label));
                })
                .show();
    }

    private String applyCrossGameModel(GameProfile target, File source, String sourceModel, String label) throws Exception {
        File targetRom = RomImporter.romFile(getFilesDir(), target);
        int entry = FormBuilder.findModelEntry(targetRom, "pl000");
        byte[] targetData = FormBuilder.archiveEntries(FormBuilder.readRomFile(targetRom, "datbin/com/models.bin")).get(entry);
        int bones = FormBuilder.boneCount(FormBuilder.blocks(targetData).get("MDL0"));
        byte[] bmd = FormBuilder.build(source, sourceModel, "pl000", bones, 0);
        String id = "transform.base." + source.getParentFile().getName() + "." + sourceModel;
        Map<String, byte[]> assets = new LinkedHashMap<>();
        assets.put("datbin/com/models.bin@" + entry, bmd);
        ModManager mm = new ModManager(getFilesDir(), target);
        mm.installGenerated(id, label + " model", "Battle model swap for " + target.label + "; moves stay the same.", assets);
        mm.disablePrefix("transform.base.", id);
        return label + " model on for " + target.label + ".";
    }

    // --- battle cards (Leo) ---------------------------------------------------------------

    private void cardsScreen() {
        screen = this::cardsScreen;
        atHome = false;
        page("Battle cards", true);
        TextView intro = text("Each card keeps the attack of the card it replaces and gets a new name, damage, element and picture. "
                + "Changes apply the next time you start the game.", 14, DIM, false);
        intro.setPadding(dp(4), 0, dp(4), dp(12));
        body.addView(intro);
        GameProfile target = CardPacks.layout(game) != null ? game : GameProfile.LEO;
        CardPacks cp = new CardPacks(getFilesDir(), target);
        Set<String> en = cp.enabled();
        List<CardPacks.Pack> packs = cp.installed();
        LinearLayout list = card("Card packs");
        if (packs.isEmpty()) row(list, "No card packs yet", "Import one below", null, true, null);
        for (int i = 0; i < packs.size(); i++) {
            CardPacks.Pack p = packs.get(i);
            if (i > 0) divider(list);
            StringBuilder sub = new StringBuilder(p.description);
            sub.append(sub.length() > 0 ? "\n" : "").append(cardSummary(p));
            Switch sw = toggleRow(list, p.name, sub.toString(), en.contains(p.id), v -> runBusy(null, () -> {
                cp.setEnabled(p.id, v);
                return null;
            }));
            ((View) sw.getParent()).setOnLongClickListener(v -> {
                new AlertDialog.Builder(this).setTitle("Remove " + p.name + "?")
                        .setPositiveButton("Remove", (d, w) -> runBusy(null, () -> { cp.remove(p.id); return p.name + " removed."; }))
                        .setNegativeButton("Cancel", null).show();
                return true;
            });
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        body.addView(actions);
        smallButton(actions, "Import a card pack (.json)", () -> pick(REQ_CARDS, target));
        TextView help = text("Long-press a pack to remove it. Card packs are small JSON files; see docs/CARDS.md for the format.", 12, DIM, false);
        help.setPadding(dp(4), dp(8), dp(4), 0);
        body.addView(help);
    }

    private static String cardSummary(CardPacks.Pack p) {
        StringBuilder s = new StringBuilder();
        for (CardPacks.Card c : p.cards) {
            if (s.length() > 0) s.append(" · ");
            s.append(c.name != null ? c.name : c.base);
            if (c.damage >= 0) s.append(' ').append(c.damage);
        }
        return s.toString();
    }

    // --- other mods -------------------------------------------------------------------------

    /** Installed mods that aren't managed by the transformation/card screens. */
    private List<ModManager.Mod> otherMods(GameProfile g) {
        List<ModManager.Mod> out = new ArrayList<>();
        for (ModManager.Mod m : new ModManager(getFilesDir(), g).installed())
            if (!m.id.startsWith("transform.") && !m.id.startsWith("powerups.") && !m.id.equals(CardPacks.MOD_ID)) out.add(m);
        return out;
    }

    private void modsScreen() {
        screen = this::modsScreen;
        atHome = false;
        page(game.label + " mods", true);
        ModManager mm = new ModManager(getFilesDir(), game);
        List<ModManager.Mod> list = otherMods(game);
        LinearLayout c = card("Installed");
        if (list.isEmpty()) row(c, "No mods installed", "Mods are .zip packages (docs/MODS.md)", null, true, null);
        for (int i = 0; i < list.size(); i++) {
            ModManager.Mod m = list.get(i);
            if (i > 0) divider(c);
            String sub = m.summary() + (m.author.isEmpty() ? "" : " · " + m.author) + (m.description.isEmpty() ? "" : "\n" + m.description);
            Switch sw = toggleRow(c, m.name + (m.version.isEmpty() ? "" : " " + m.version), sub, mm.isEnabled(m.id), v -> {
                try { mm.setEnabled(m.id, v); } catch (IOException e) { toast(e.getMessage()); }
            });
            ((View) sw.getParent()).setOnLongClickListener(v -> {
                String[] opts = {"Load earlier", "Load later", "Remove"};
                new AlertDialog.Builder(this).setTitle(m.name).setItems(opts, (d, w) -> {
                    try { if (w == 2) mm.remove(m.id); else mm.move(m.id, w == 0 ? -1 : 1); } catch (IOException e) { toast(e.getMessage()); }
                    modsScreen();
                }).show();
                return true;
            });
        }
        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        body.addView(actions);
        smallButton(actions, "Install a mod (.zip)", () -> pick(REQ_MOD_ZIP, game));
        TextView help = text("Enabled mods apply when the game starts. Long-press a mod to reorder or remove it; later mods win when two change the same file.", 12, DIM, false);
        help.setPadding(dp(4), dp(8), dp(4), 0);
        body.addView(help);
    }

    // --- settings ---------------------------------------------------------------------------

    private void settings() {
        screen = this::settings;
        atHome = false;
        page("Settings", true);
        LinearLayout play = card("Playing");
        String[][] toggles = {
                {"jit", "true", "Fast CPU (JIT)", "Turn off if a game crashes on boot"},
                {"dual_screen", "true", "Second display", "Bottom screen on the second display (AYN Thor and similar)"},
                {"touch_controls", "true", "On-screen buttons", null},
                {"autohide_controls", "true", "Hide buttons with a controller", null},
                {"swap_face", "false", "Face buttons by label", "Instead of by position"},
                {"integer_scale", "false", "Integer scaling", "Sharper, smaller picture"},
                {"smooth", "false", "Smooth filtering", null}};
        for (int i = 0; i < toggles.length; i++) {
            String[] t = toggles[i];
            if (i > 0) divider(play);
            toggleRow(play, t[2], t[3], prefs.getBoolean(t[0], Boolean.parseBoolean(t[1])), v -> prefs.edit().putBoolean(t[0], v).apply());
        }

        LinearLayout saves = card("Save for " + game.label);
        boolean hasSave = RomImporter.saveFile(getFilesDir(), game).isFile();
        row(saves, "Export save", "Write the battery save to a .sav file", null, hasSave, () -> {
            Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("application/octet-stream");
            i.putExtra(Intent.EXTRA_TITLE, "MegaManStarForce-" + game.id + ".sav");
            pendingGame = game;
            startActivityForResult(i, REQ_EXPORT_SAVE);
        });
        divider(saves);
        row(saves, "Import save", "Replaces the current save (the old one is kept as a backup)", null, imported(game),
                () -> pick(REQ_IMPORT_SAVE, game));

        LinearLayout roms = card("Games");
        for (int i = 0; i < GameProfile.ALL.length; i++) {
            GameProfile g = GameProfile.ALL[i];
            if (i > 0) divider(roms);
            boolean ok = imported(g);
            row(roms, g.label, ok ? "Imported" : "Not imported", ok ? "Re-import" : "Import", true, () -> pick(REQ_ROM, g));
        }
    }

    // --- background work + file pickers ---------------------------------------------------

    private interface Job { String run() throws Exception; }

    private void runBusy(String working, Job job) {
        busy = true;
        if (working != null) message = working;
        Runnable back = screen;
        back.run();
        worker.execute(() -> {
            String m;
            try { m = job.run(); } catch (Exception e) { m = "Failed: " + e.getMessage(); }
            String fm = m;
            runOnUiThread(() -> {
                busy = false;
                message = fm;
                if (!isDestroyed()) back.run();
            });
        });
    }

    private void verifyExisting(GameProfile target) {
        runBusy("Checking " + target.label + "…", () -> {
            try (InputStream in = new FileInputStream(RomImporter.romFile(getFilesDir(), target))) {
                return RomImporter.importGame(in, getFilesDir(), target);
            }
        });
    }

    private void pick(int request, GameProfile target) {
        pendingGame = target;
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        startActivityForResult(i, request);
    }

    @Override public void onSaveInstanceState(Bundle state) {
        state.putString("pendingGame", pendingGame.id);
        if (pendingForm != null) state.putString("pendingForm", pendingForm);
        super.onSaveInstanceState(state);
    }

    @Override protected void onRestoreInstanceState(Bundle state) {
        super.onRestoreInstanceState(state);
        try { pendingGame = GameProfile.fromId(state.getString("pendingGame", "leo")); } catch (IllegalArgumentException ignored) { }
        pendingForm = state.getString("pendingForm", null);
    }

    @Override public void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (result != RESULT_OK || data == null || data.getData() == null || busy) return;
        GameProfile target = pendingGame;
        android.net.Uri uri = data.getData();
        if (request == REQ_EXPORT_SAVE) {
            runBusy("Exporting…", () -> {
                try (OutputStream out = getContentResolver().openOutputStream(uri)) { return SaveTransfer.exportSave(out, getFilesDir(), target); }
            });
            return;
        }
        String working = request == REQ_ROM ? "Importing " + target.label + "… this checks the whole ROM" : "Installing…";
        runBusy(working, () -> {
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                if (in == null) throw new IOException("Cannot open the selected file");
                switch (request) {
                    case REQ_ROM: {
                        String m = RomImporter.importGame(in, getFilesDir(), target);
                        if (CardPacks.layout(target) != null) try { new CardPacks(getFilesDir(), target).rebuild(); } catch (Exception ignored) { }
                        runOnUiThread(() -> { game = target; prefs.edit().putString("game", target.id).apply(); });
                        return m;
                    }
                    case REQ_IMPORT_SAVE: return SaveTransfer.importSave(in, getFilesDir(), target);
                    case REQ_MOD_ZIP: {
                        ModManager.Mod m = new ModManager(getFilesDir(), target).install(in);
                        return "Installed " + m.name + ". It's on for the next start.";
                    }
                    case REQ_SPRITE: {
                        android.graphics.Bitmap bm = android.graphics.BitmapFactory.decodeStream(in);
                        if (bm == null) throw new IOException("Not an image");
                        int w = bm.getWidth(), h = bm.getHeight();
                        if ((long) w * h > 16_000_000L) throw new IOException("Image too large");
                        int[] argb = new int[w * h];
                        bm.getPixels(argb, 0, w, 0, 0, w, h);
                        bm.recycle();
                        String form = pendingForm;
                        if (form == null) throw new IOException("No transformation selected");
                        FieldSprite.saveSheet(getFilesDir(), target, form, argb, w, h);
                        FormSystem.apply(getFilesDir(), target, form);
                        return "Custom overworld sprite on for " + FormSystem.find(target, form).name + ".";
                    }
                    case REQ_CARDS: {
                        CardPacks.Pack p = new CardPacks(getFilesDir(), target).install(CardPacks.read(in));
                        return "Card pack " + p.name + " is on (" + p.cards.size() + " cards).";
                    }
                    default: return null;
                }
            }
        });
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    @Override public void onBackPressed() {
        if (atHome || body == null) super.onBackPressed();
        else { message = null; home(); }
    }

    @Override protected void onDestroy() { worker.shutdown(); super.onDestroy(); }
}
