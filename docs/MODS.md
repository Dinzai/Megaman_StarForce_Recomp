# Mods

## Game mods (schema 2) — change the actual game

A game mod is a `.zip` with a `mod.json` at its root:

```json
{
  "schema": 2,
  "id": "yourname.blue-logo",
  "name": "Blue boot logo",
  "game": "leo",
  "version": "1.0.0",
  "author": "yourname",
  "description": "Optional text shown in Manage mods.",
  "files": {
    "datbin/com/capcomlogo.bin": "files/capcomlogo.bin",
    "#661": "files/models.bin"
  },
  "cheats": [
    { "name": "Example code", "code": "XXXXXXXX YYYYYYYY\n# comments allowed" }
  ]
}
```

- **files** maps a ROM path to a file inside the zip. A ROM path is a NitroFS path (`datbin/eng/mess.bin`) or a FAT id (`#123`; ids 0–645 are ARM9 overlays). `arm9`/`arm7` are also accepted when the replacement is no larger than the original. Any size works: larger files are relocated automatically. List every path with `python tools/make_mod.py list-files leo.nds`.
- **entries** replaces single entries of one of Leo's offset/length archives without shipping the whole file: `"entries": {"datbin/com/models.bin": {"157": "files/form.bmd"}}`. Entry data is stored uncompressed, and two mods can change different entries of the same file. Transformation mods use this (see [TRANSFORMATIONS.md](TRANSFORMATIONS.md)).
- **cheats** are Action Replay DS codes, run every frame by the melonDS AR engine. Use them for value and memory tweaks. Codes are pairs of 8-digit hex words; `#` and `;` start comments.
- **Load order.** Mods load in the order shown in **Manage mods**, and the later mod wins when two replace the same file. The app reports the conflict when the game starts.
- **Limits.** Package ≤ 256 MiB, `mod.json` ≤ 64 KiB, ≤ 4096 entries, ≤ 256 cheat entries. The id uses `a-z 0-9 . _ -`. Paths may not contain `..` or start with `/`.
- **Safety.** Mods are data. The app never executes anything from a package, extracts only validated paths, and installs atomically. Upgrading a mod with the same id replaces it.

### Making an asset mod

Most of Leo's `.bin` files are offset/length archives of optionally LZ10-compressed entries (see `tools/sfarchive.py`):

```sh
python tools/make_mod.py unpack leo.nds datbin/com/capcomlogo.bin --out work/logo     # work/logo/000.bin …
# edit entries (palettes are BGR555, textures are Nitro NSBTX/NSBMD — see tools/leo_assets.py)
python tools/make_mod.py repack leo.nds datbin/com/capcomlogo.bin --entries work/logo --out mymod/files/capcomlogo.bin
# write mymod/mod.json, then:
python tools/make_mod.py package mymod --out mymod.zip
```

`repack` stores only the entries you changed, uncompressed; the game accepts both forms. `python tools/make_mod.py example-recolor leo.nds --out demo.zip` builds a complete working example that recolors the boot logo.

Test on desktop before copying to the device:

```sh
./build-host/sfhost leo.nds --replace datbin/com/capcomlogo.bin=mymod/files/capcomlogo.bin --frames 400 --shot 300 --out /tmp
```

Do not distribute packages that contain large unmodified game files. Ship only what you changed.

## Visual mods (schema 1): voxel renderer

**Install visual mod (.json)** sets the voxel style. Turn the mod on with **Settings → Voxel mod in game** or **in-game menu → Voxel mod**; the in-game toggle switches without restarting. Each DS screen is drawn live as one extruded cube per pixel, with height from brightness × `depth` and optional grid lines, rendered with OpenGL ES 3. The top screen uses a tilted camera (drag it to orbit). The bottom screen uses an oblique projection whose floor stays aligned with the touch panel, so taps land where they look. **Settings → Voxel screens** picks bottom (default; that's where Star Force 1's field is), both or top. On dual-screen devices both displays are voxelized. If the GPU path fails, the app says why and falls back to the normal screen. Without an installed file the bundled *Dramatic Voxel* settings are used. The same file also drives the standalone preview:

```json
{ "schema": 1, "id": "example.deep-cyan", "name": "Deep Cyan", "game": "leo",
  "type": "voxel-preview", "render": { "depth": 4, "accent": "#20CCFF", "grid": true } }
```

Maximum 16 KiB; depth 0–8; accent `#RRGGBB` (preview only). The voxel mod changes rendering only; game state is untouched.


## Other supported games

Use `"game": "zerker-ninja"` or `"game": "black-ace"` for packages targeting those US dumps. The same schema and mod controls apply. Files, archive indices and codes must be authored for that game; Leo packages are rejected in another game. See [multi-game support](MULTIGAME.md).
