# Leo research milestone 0.2

## Reproduce locally

```sh
python -m pip install -r requirements-research.txt
python -m unittest discover -s tests -v
python tools/research_leo.py /path/to/leo.nds --out research-private/leo-v2 --export-binaries
```

Use a new output directory. The command verifies the ROM hash before analysis. It produces:

- `summary.json`: reproducible counts and code-image hashes.
- `overlays.json`: load addresses, decoded sizes/hashes, static initializer targets and overlapping overlay pairs.
- `startup-flow.json`: direct-control-flow exploration from the ARM9 entry point, including disassembly, branch/call edges, stop reasons and referenced literal values.
- `archives.json`: entry offsets, sizes, compression flags, hashes and Nitro block metadata.
- `texture-issues.json`: unresolved palette cases or decoder failures.
- `leo-textures.zip`: decoded PNGs and a manifest for the Android texture explorer.
- With `--export-binaries`, `code/` and `assets/`: local decompressed images for further analysis. Code images are not ELF files; load them at the reported addresses in a disassembler. BSS is separately reported and is not present in the file.

These outputs contain ROM-derived data and stay local. Only the small metadata summary is committed.

## Verified observations

| Item | Result |
|---|---:|
| ARM9 overlays | 646 |
| Compressed overlays | 607 |
| Overlay decoded-size checks | 646 / 646 pass |
| Datbin archives parsed | 45 |
| Archive entries decoded | 4,617 |
| LZ10-compressed entries | 2,668 |
| BMD0 model containers identified | 349 |
| BTX0 texture containers identified | 151 |
| BCA0 animation containers identified | 671 |
| BTP0 animation containers identified | 180 |
| Texture PNGs exported | 1,162 |
| Texture cases unresolved | 44 |

The ARM9 main image expands into three sections reported in `profiles/leo-research-summary.json`. Earlier raw-ROM disassembly was insufficient; `tools/disassemble.py` now operates on decompressed sections and supports `--overlay ID`.

The archive directory is an array of 8-byte `(offset, size_flags)` records. The first payload offset determines directory size. Bit 31 of `size_flags` indicates LZ10 compression; the other bits give decoded size. A final `(file_length, 0xffff)` sentinel marks EOF. All 45 datbin archives satisfy these rules. `dwc/utility.bin` has a different layout and is deliberately excluded.

The startup explorer follows direct calls/branches with ARM/Thumb interworking. It currently visits 2,393 instructions and emits 344 direct edges, 171 stop records and 46 IO-range literal references. Stop records include ordinary returns, unresolved indirect transfers and out-of-map targets; they are not 171 missing functions. A literal in the IO address range is evidence of a constant reference, not proof of a hardware operation. Some entry-reachable code may still be data or compiler veneers; this is not a complete function map.

The audio SDAT parser inventories 38 sequence slots, 1 sequence-archive slot, 41 bank slots, 41 wave-archive slots and 2 stream slots. Counts include archive table slots; audio playback and sound effect naming are not implemented.

## Runtime progress and limits

`leo_code.LoadMap` is a Python research model that maps load images, zero-fills their BSS, requires explicit unload before overlapping loads, and rejects unmapped reads. The overlap table describes spatial conflicts, not the game's temporal overlay-loading sequence. No CPU instructions execute in this model. DS MMIO, interrupts, DMA, IPC, graphics and audio services still need implementations or native replacements.

Textures are decoded using ndspy. Palette selection uses an exact conventional name match (`name` or `name_pl`) or a sole available palette; that evidence is stored per texture. Ambiguous choices are left unresolved. These choices have not been verified against every model material binding or an original-device capture. A compatibility adapter supplies packed RGB555 palette entries to ndspy 4.2's renderer.

Models and animations have valid Nitro container/block metadata, but geometry, skeletal transforms and animation playback have not been reconstructed. The 3,266 entries without recognized Nitro magic include game-specific data; no dialogue or event script decoder is claimed.

## AYN Thor test

1. Install the 0.2 research APK from GitHub Actions.
2. Run the command above on your computer with the Leo ROM.
3. Transfer `leo-textures.zip` to the Thor.
4. In the app, select Leo → **Import Leo texture pack (.zip)** → **Browse ROM textures**.
5. Select a texture. The top view shows its decoded image; the lower view extrudes sampled texture pixels using the installed visual mod. Drag the lower view to rotate.

The voxel preview retains texture colors, thresholds alpha at 128 and samples to at most 32 pixels per side. It does not reconstruct game geometry or scene placement. `depth` and `grid` apply; the mod's accent color applies only to the procedural test image. This is a single-activity viewer; Thor's second display and gamepad bindings are not implemented or device-tested yet. No assumptions about a particular Thor SKU or Android version are required beyond the APK's Android 8+ minimum.

## Next concrete gate

Trace the unresolved startup transfer into the main loop, identify overlay loader callsites, and decode one model's geometry/material bindings with a corresponding animation. Only after those mappings are validated should a scene/runtime claim be made.

## References and dependencies

- [ndspy](https://github.com/RoadrunnerWMC/ndspy): executable decompression, main-section parsing, Nitro texture decoding and SDAT inventory; used as an installed Python dependency.
- [Capstone](https://www.capstone-engine.org/): ARM/Thumb instruction decoding; installed Python dependency.
- [SFArcTool](https://github.com/Prof9/SFArcTool): corroborates Star Force archive extraction/decompression and EOF records. This project's archive reader was implemented from the supplied ROM layout; no SFArcTool code is vendored.
- [Star Force DX](https://github.com/Prof9/Mega-Man-Star-Force-DX): existing reverse-engineering/modding reference. No patch code or artwork has been copied.
