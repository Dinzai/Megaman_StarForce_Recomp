# Mods

The launcher manages three kinds of mod for you: **transformations** ([TRANSFORMATIONS.md](TRANSFORMATIONS.md)), **battle cards** ([CARDS.md](CARDS.md)) and **voxel 3D mode** (below). Everything else is a mod package (`.zip`), installed under **More mods**.

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
- **Load order.** Mods load in the order shown under **More mods** (long-press to reorder), and the later mod wins when two replace the same file. The app reports the conflict when the game starts.
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

## Voxel 3D mode

**Mods → Voxel 3D mode** (or the in-game menu, which switches without restarting) draws each DS screen as one extruded cube per pixel, with OpenGL ES 3. Each column's height comes from the **scene**, not the colours: the core reports which layer drew each pixel (backdrop, background by priority, sprite or 3D) and, for 3D, its depth. Floors therefore stay flat, characters and objects stand up as solid figures, and battles keep their real 3D depth. Edges are outlined only where the height changes. Both screens use a tilted perspective camera; drag the top screen to orbit. Taps on the 3D bottom screen are ray-cast back onto the touch panel, so they land where they look. **3D screens** picks bottom (default; Star Force 1's field), both or top. On dual-screen devices each display gets its own 3D view. If the GPU path fails, the app says why and falls back to the normal screen.

The scene depth comes from a small patch to melonDS's software renderer (`native/patches/melonds-scene-depth.patch`), applied automatically when the core is fetched. The heights are built in `Session::CopyHeights`; `sfhost --heights` writes them as PGM images for tuning.

A schema 1 JSON file can still set the relief strength (`depth`, 0–8; default 2.8):

```json
{ "schema": 1, "id": "example.deep-cyan", "name": "Deep Cyan", "game": "leo",
  "type": "voxel-preview", "render": { "depth": 4, "accent": "#20CCFF", "grid": true } }
```

Place it at `files/visual-mod.json` in the app's storage. Maximum 16 KiB. The voxel mode changes rendering only; game state is untouched.


## Other supported games

Use `"game": "zerker-ninja"` or `"game": "black-ace"` for packages targeting those US dumps. The same schema and mod controls apply. Files, archive indices and codes must be authored for that game; Leo packages are rejected in another game. See [multi-game support](MULTIGAME.md).
