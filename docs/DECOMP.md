# Decompilation

The goal: understand the games' code well enough to replace what they draw and do (custom models, sprites and moves) without being held to the original data formats and memory limits. This is the plan, and where it stands.

## Stage 1: split and name (started)

`tools/decomp.py` splits a ROM's code into its modules (the always-loaded ARM9 program and every overlay) and finds the functions in each:

```sh
python tools/decomp.py split leo.nds --out decomp/build/leo
python tools/decomp.py report decomp/build/leo
python tools/decomp.py find decomp/build/leo 0217600C      # which function holds an address
```

Each module gets `functions.csv` (address, size, ARM/Thumb, name) and `module.s` (labelled disassembly, calls resolved to names). Functions are found from the entry point, overlay initialisers, every direct call target and standard prologues.

| Game | Modules | Functions found | Named |
|---|---|---|---|
| Leo | 647 (ARM9 + 646 overlays) | 9,300 | first handful |
| Zerker × Ninja | 956 | 10,502 | first handful |
| Black Ace | 159 | 11,521 | — |

Names live in `decomp/symbols/<game>.txt` (`name = 0xADDRESS thumb|arm|data @module  # notes`), shared by every tool. They start with everything traced so far: Leo's transform routine and form/charge/element state, the custom-screen hand and card tables, Zerker × Ninja's Tribe On setup and form routines.

**The disassembly is the game's code.** It is generated on your machine from your own ROM into `decomp/build/` (git-ignored) and must never be committed or shared. Only the tools, symbol names and notes go into the repository.

## Stage 2: map the subsystems that mods need

In order of usefulness for custom content:

1. **Asset loading**: the archive/file loader that every `.bin` read goes through. Hooking it in the native runtime lets mods supply data in memory instead of patching the ROM, and makes it visible which assets an area loads.
2. **Field sprites**: the routine that loads Mega Man's `fieldobj.bin` set and the per-frame tile upload. With it, a custom sprite can be streamed per frame from the app instead of living in the game's heap, which removes the size limit described in [SPRITES.md](SPRITES.md).
3. **Battle models**: the model/animation loader behind `Battle_LoadPlayerModel`, so new models (not just swaps of existing ones) can be used.
4. **Battle cards**: the per-card attack dispatch, for chips with new behaviour (freeze *and* poison).

## Stage 3: runtime replacement

The native layer runs the original code in melonDS. Once a function is understood, it can be replaced at run time with a native implementation (a high-level hook), the way the recomp projects for other consoles do. Each replaced function is one less piece of the original game in the loop, and a place where mods can plug in.

## Stage 4: matching decompilation (long term)

Turning functions into C that compiles back to identical bytes needs the original toolchain (Metrowerks CodeWarrior for DS) and is slow, careful work per function. It isn't needed for modding, so it comes after stages 2 and 3 have the subsystems that matter.
