# Transformations

Leo's Mega Man can take all eight Star Force forms: **Ice Pegasus, Fire Leo, Green Dragon** (Star Force 1), **Zerker, Ninja, Saurian** (Star Force 2) and **Black Ace, Red Joker** (Star Force 3). They all run through one system, and each form is a real transformation with its own charged attack, element and model:

| Form | Host form | Charged attack (B, after charging) | Element | Model |
|---|---|---|---|---|
| Ice Pegasus | Pegasus (1) | Ice Slash | aqua | Leo ROM `pl001` |
| Fire Leo | Leo (2) | Flame Burner | fire | Leo ROM `pl003` |
| Green Dragon | Dragon (3) | Woody Shot | wood | Leo ROM `pl002` |
| Zerker | Leo | ground-wave slash | elec | Zerker × Ninja ROM `pl001` |
| Ninja | Leo | multi-hit seed shot | wood | Zerker × Ninja ROM `pl002` |
| Saurian | Leo | missile cannon | fire | Zerker × Ninja ROM `pl003` |
| Black Ace | Leo | spinning rings | none | Black Ace ROM `pl010` |
| Red Joker | Leo | orb blast | none | Black Ace ROM `pl00f00` |

**In the app:** go to **Mods for Leo → Transformation** and pick a form, or *None*. A Star Force 2/3 form needs that game imported (the app offers to import it). You can also switch forms **while playing**: in-game menu → **Transformation**. The game is snapshotted, restarted with the new form and restored, so you carry on where you were, and the new form appears from the next battle.

**On a PC:** `python tools/make_mod.py form leo.nds --form black-ace --sf3 black-ace.nds --out ace.zip`.

**How the SF2/SF3 forms work:** they use Star Force 1's real transform routine with Fire Leo as the host. That means transforming, knock-out by weakness and the effects all work as in the original game. While transformed (current form ≠ 0), the mod overrides the charged-attack type (`0x021AF022`, low byte) and Mega Man's element (`0x021ADC95`), and the form's model replaces the host's model entry (`models.bin` #157). Every code is guarded so it only writes while the battle overlay is loaded:

```
5217600C 1C05B570   if the battle overlay is loaded
221AF1BF 00000002   starting form = Fire Leo (host)
A21AF022 00FF0000   while transformed:
221AF022 00000006     charged attack = spinning rings
221ADC95 00000000     element = none
D2000000 00000000   end
```

The charged attacks come from Star Force 1's own table: 1 Mega Buster, 3 Ice Slash, 4 ground-wave slash, 5 missiles, 6 spinning rings, 7 orb blast, 8 Woody Shot (multi-hit), 9 Flame Burner. They're the closest Star Force 1 attacks, not ports of the Star Force 2/3 originals. Cards, HP and weakness timing stay Star Force 1's.

**Verified** in a scripted battle (complete save, power-on → EM Wave Change → virus battle): all five Star Force 2/3 forms load their model, start transformed, and fire their charged attack for damage. Switching form after loading a snapshot taken without mods works too (Black Ace, tested with `sfhost --load-state`).

## Overworld

The transformation also recolours Mega Man on the field (Wave Road and EM areas) so he matches the form he battles in. The field draws him as 2D sprites from `datbin/com/fieldobj.bin`. All of his sprite sets share one 16-colour palette (10 entries in Leo, 22 in Zerker × Ninja). The mod rewrites that palette from your ROM: the blue armour ramp and the dark hair/crest accent take the form's colours, while skin, visor and outlines stay. It's a recolour, not new sprite art; the pose and shape are Mega Man's. Code: `OverworldPalette.java` and `tools/overworld.py`, which produce identical bytes.

## Zerker × Ninja

Zerker × Ninja has its own system, and here the Tribe On forms are completely real: the game's own Tribe On, with every move, effect and weakness of that tribe.

| Form | How | Look |
|---|---|---|
| Zerker | Tribe On (start bit 13) | own |
| Ninja | Tribe On (bit 14) | own |
| Saurian | Tribe On (bit 15) | own |
| Tribe King | Tribe King (bit 17) | own |
| Ice Pegasus | Zerker's moves | Leo ROM `pl001` |
| Fire Leo | Saurian's fire moves | Leo ROM `pl003` |
| Green Dragon | Ninja's wood moves | Leo ROM `pl002` |
| Black Ace | Tribe King's moves | Black Ace ROM `pl010` |
| Red Joker | Tribe King's moves | Black Ace ROM `pl00f00` |

**In the app:** select Zerker × Ninja, then **Mods for Zerker × Ninja → Transformation**. The in-game menu can switch forms too, starting from the next battle. **On a PC:** `python tools/make_mod.py form zn.nds --form tribe-king --out tk.zip` (add `--leo` or `--sf3` for borrowed looks).

**How it works** (found with the `sfhost` watch hook and a save from your Thor):

- The battle code is overlay 875 (RAM `0x0213FAA0`). Mega Man's form-to-model table is at `0x02191098`: `pl000`, Zerker, Ninja, Saurian, (version tribe), six more and Tribe King.
- At battle setup (`0x0215E8C0`) the game picks Mega Man's starting state from flag bits in the per-player setup block (`0x0219D1CC + player × 0x158`, offset `+0x118`). Bit 13 → Zerker, 14 → Ninja, 15 → Saurian, 17 → Tribe King, 16 → the cartridge's own tribe. It then calls the game's own form routine (`0x0215F090`), which stores the state at `0x0219A844 + player × 0x124 + 0x0A` and loads the model.
- The mod sets exactly one of those bits while the battle overlay is loaded (guard word `0x02143B3C == 0xD1052804`), clearing the other tribe bits first:

  ```
  52143B3C D1052804   if the battle overlay is loaded
  D9000000 0219D2E4   datareg = start flags
  D4000002 FFFC1FFF   clear bits 13-17
  D4000001 00008000   set bit 15 (Saurian)
  D6000000 0219D2E4   store
  D2000000 00000000   end
  ```
- A borrowed look replaces the host's model entry and the texture entries after it (Zerker 179 + 180/181, Ninja 182 + 183/184, Saurian 185 + 186/187, Tribe King 194 + 195), because Zerker × Ninja binds tribe textures from separate entries.

**Verified** in battles started from your save: all nine forms load, and the real tribes fire their own charged attacks. Field frames are identical with the code on.

**Black Ace (the game)** still offers **Battle model** swaps only (visual). Its battle code hasn't been traced yet.

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
| Black Ace | Star Force 3 ROM | `pl010` | model ready |
| Red Joker | Star Force 3 ROM | `pl00f00` | model ready |

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

Tested: field frames are identical with the code active, and battles start with the current-form byte set and the real form's model and effects. Being hit by the form's weakness still knocks Mega Man out of it, as in the original. In the app: **Mods for Leo → Transformation**. On a PC: `python tools/make_mod.py real-form --form leo --out leo.zip`.

**Mixing:** a real form combined with a model swap in the *When transforming* slot gives, for example, Zerker's look with Fire Leo's mechanics (tested). True Zerker/Ninja/Saurian/Black Ace/Red Joker mechanics would need new behaviour code; see Next steps.

## Model swaps (visual)

A transformation mod replaces one entry of Leo's `models.bin` with the chosen form's model. The model is renamed to the slot's name and its textures are embedded. Two slots are offered:

- **Star Force slot** (#157, `pl003`): Mega Man appears as the new form after Star Force Big Bang, in place of Leo Kingdom.
- **Base slot** (#151, `pl000`): Mega Man appears as the new form in every battle. Use this to test from the first battle.

Moves, element, charge shot and stats stay Leo's. Only the 3D model changes.

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
3. ✅ **Behaviour for SF2/SF3 forms**: charged attack and element overrides (see the top of this page). Still to do: form-specific card bonuses and palette effects.
4. ✅ **SF3 mapping**: Black Ace is `pl010`, Red Joker `pl00f00`.
5. **Real forms inside Zerker × Ninja and Black Ace**: trace their battle overlays the same way.
