# Leo, Zerker × Ninja and Black Ace

The launcher supports the exact US dumps listed below. Zerker and Ninja are two launcher entries for the **same Zerker × Ninja cartridge**, with the same save and mod list; they are not separate ROM versions.

| Game / mod target | Code | SHA-256 |
|---|---|---|
| `leo` | A6BE | `defbc6f639fb0bcd11a229d1a0c7c9f595447034ae25d3ba4a97e1268156c5d6` |
| `zerker-ninja` | YRVE | `87e960af99c8b9f0978751a3b22f17009aa370dddf43d76337dd244288b97cda` |
| `black-ace` | CRBE | `543e65608eb8ce68d5ddecd4cd9f6ab0a479262823e5a0be25083982c4acfa43` |

All three supplied images are 32 MiB. Imports require size, code and full SHA-256 agreement. Select the game, import its ROM and press Play. Existing SF2/SF3 ROMs imported as transformation sources can use **Verify existing ROM**, preserving saves. Original Leo storage and mods are retained.

Each game uses the same native core, controller/touch input, audio, dual-screen layout, save-state support and voxel renderer. Settings and the visual JSON mod are shared. Battery saves and states are isolated in `leo/`, `sf2/` and `sf3/`. Save import/export follows the selected game; only Leo performs the existing SF1 version conversion.

## Mod compatibility

All three games support schema-2 file replacements, archive-entry replacements, memory codes, enable/disable and load ordering. Set `mod.json`'s `game` to the exact target above. The app rejects a package targeting a different game. Mod directories are `mods/` for existing Leo installations, `mods-zerker-ninja/` and `mods-black-ace/`. Identical mod IDs in different games do not collide.

The same **mod features** are available; Leo's binary replacements, entry indices and RAM addresses are not universally portable. Voxel rendering uses the shared rendered screens and works independently of ROM offsets. Forced real-transformation codes remain Leo-only because their addresses were derived from Leo's battle overlay.

For SF2/SF3, **Transformations** builds visual replacements for the destination's base `pl000` model. Destination entries are found by name, not by reusing Leo's slot 151. Bone counts must match. Source models/textures are assembled locally from imported ROMs. These swaps preserve the target game's moves; a matching bone count does not establish full animation compatibility. SF3 form names remain raw model names where their identity is unconfirmed.

SF3 archives use a zero EOF tag and LZ11 compression. The Java model builder and Python tools now decode LZ11; the native patcher preserves the archive's EOF tag and untouched compressed entries. Replacements remain uncompressed, as in the existing Leo mod path.

## Verification

ROM-independent tests cover game selection, path isolation, wrong-ROM rejection, save preservation, mod targeting and namespace isolation, LZ11 reference forms and bounds, and native archive rewriting. Both supplied games boot in the same host Session used by Android, produce audio, and restore a save state successfully. Android device performance and cross-game model swaps during battle require Thor testing; this is not a full-game completion test.
