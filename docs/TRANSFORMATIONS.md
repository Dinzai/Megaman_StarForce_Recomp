# Transformation mods

Goal: let Leo's Mega Man take any Star Force form: Pegasus, Dragon, Zerker, Ninja, Saurian, Black Ace and Red Joker.

## What we found

| Form | Source | Model | Status |
|---|---|---|---|
| Mega Man (base) | Leo ROM | `models.bin` #151 `pl000` | original |
| Pegasus Magic | **Leo ROM** | #153 `pl001` | model ready |
| Dragon Sky | **Leo ROM** | #155 `pl002` | model ready |
| Leo Kingdom | Leo ROM | #157 `pl003` | original form slot |
| Zerker | Star Force 2 ROM | #179 `pl001` + textures #180/#181 | model ready |
| Ninja | Star Force 2 ROM | #182 `pl002` + #183/#184 | model ready |
| Saurian | Star Force 2 ROM | #185 `pl003` + #186/#187 | model ready |
| Black Ace / Red Joker | Star Force 3 ROM | not yet identified | needs an SF3 ROM |

- **All three Star Force Big Bang forms ship inside every SF1 cartridge.** Leo contains Pegasus and Dragon too. That suggests the game picks the form from a version value, which we can later flip to get the real forms, abilities included.
- **Every player model in SF1 and SF2 uses the same 19-bone rig** (`plNNN00`–`plNNN18`), so Leo's battle animations drive an SF2 model without retargeting.
- SF2 also has the double-tribe models (`pl004`–`pl009`) and Tribe King (`pl00a`), all on the same rig.

## Real transformations (game mechanics)

Traced in the battle overlay (overlay 7) with the `sfhost --watch` hook and your complete save:

| Address | Meaning |
|---|---|
| `0x021AF1BF` (+ player × `0x1D4`) | Mega Man's **starting form**, read during battle setup (`0x0217562A`) |
| `0x021AF023` (+ player × `0x1D4`) | **current form**, written by the transform routine |
| `0x0217600C` | the game's transform routine: tears down the old form, stores the form, loads its model (`0x021745FC`), re-initializes Mega Man and spawns the form's effect objects (`0x02175F58`; Pegasus's wings, for example) |
| `0x021AA694` | form → model table: `0x10097` (pl000), `0x10099` (pl001), `0x1009D` (pl003), `0x1009B` (pl002) |

Form values: **0 Mega Man, 1 Ice Pegasus, 2 Fire Leo, 3 Green Dragon.** Per-frame battle code branches on the current form, with extra handling for Dragon. Setting the starting form therefore gives the real form through the same routine the Star Force card uses. The guide-documented charged shots (Ice Slash, Flame Burner, Woody Shot), element and weakness belong to these forms.

The mod is an Action Replay code, guarded so it only writes while the battle overlay is loaded; that RAM belongs to other code in the field:

```
5217600C 1C05B570   if the battle overlay is loaded
221AF1BF 0000000N   starting form = N (1 Pegasus, 2 Leo, 3 Dragon)
D2000000 00000000   end
```

Tested: field frames are identical with the code active, and battles start with the current-form byte set and the real form's model and effects. Being hit by the form's weakness still knocks Mega Man out of it, as in the original. In the app: **Transformations → Real Ice Pegasus / Fire Leo / Green Dragon**. On a PC: `python tools/make_mod.py real-form --form leo --out leo.zip`.

**Mixing:** a real form combined with a model swap in the *When transforming* slot gives, for example, Zerker's look with Fire Leo's mechanics (tested). True Zerker/Ninja/Saurian/Black Ace/Red Joker mechanics would need new behaviour code; see Next steps.

## Model swaps (visual)

A transformation mod replaces one entry of Leo's `models.bin` with the chosen form's model. The model is renamed to the slot's name and its textures are embedded. Two slots are offered:

- **Star Force slot** (#157, `pl003`): Mega Man appears as the new form after Star Force Big Bang, in place of Leo Kingdom.
- **Base slot** (#151, `pl000`): Mega Man appears as the new form in every battle. Use this to test from the first battle.

Moves, element, charge shot and stats stay Leo's. Only the 3D model changes.

### In the app

Library → **Transformations** → pick the slot → tap a form. For SF2 forms, first tap **Import Star Force 2 ROM** and pick your Zerker x Ninja (or other SF2) dump; Saurian loads from the same file. The mod is built on the device from your ROMs and enabled. Starting the game applies it. **Turn off transformations** restores Leo.

### On a PC

```sh
python tools/make_mod.py list-models leo.nds                     # forms in any Star Force ROM
python tools/make_mod.py transform leo.nds --form pegasus --out pegasus.zip
python tools/make_mod.py transform leo.nds --form zerker --sf2 sf2.nds --slot base --out zerker.zip
python tools/make_mod.py transform leo.nds --form black-ace --sf3 sf3.nds --source-model plNNN --out ace.zip
```

These zips contain data from your ROMs. Keep them for yourself; don't redistribute them.

## Verified so far

- The Java (app) and Python builders produce byte-identical models for all five forms, built from the real ROMs.
- Each built model decodes and renders correctly with the project's NSBMD decoder.
- Leo boots and plays identically through 12,000 frames with a transformation mod applied (the rebuilt `models.bin` loads).
- **Verified in battle (base slot):** with a complete save (a Dragon save converted to Leo, see [SAVES.md](SAVES.md)), a scripted run from power-on through EM Wave Change into a virus battle shows each form in place of Mega Man. Pegasus, Dragon, Leo, Zerker, Ninja and Saurian all load, animate with Leo's rig and render with their own textures, including the larger SF2 texture sets. In SF1, battle Mega Man is drawn translucent, and so are the forms.
- Star Force slot (#157): not yet triggered in a scripted battle; it uses the same mechanism as the base slot.

## Next steps

1. **Star Force slot in battle**: script the Star Force Big Bang with the complete save.
2. ✅ **Real Pegasus/Leo/Dragon forms**: done (see above). Still to do: map the charged-shot dispatch per form, so new forms can reuse it.
3. **Behavior for SF2/SF3 forms.** Map Leo Kingdom's battle-form parameters (element, charge shot, palette effects), then let a transformation mod override them through native hooks (see [RUNTIME.md](RUNTIME.md), steps 6–7).
4. **SF3 mapping**: identify Black Ace and Red Joker model numbers with `list-models` on an SF3 ROM and add them to `FORMS`.
