# Mega Man Star Force Recomp

Android-first research project targeting **Leo** first, with a selector for Leo, Pegasus, Dragon, Zerker, Ninja, Saurian, Black Ace and Red Joker.

**Status: research foundation, not a playable port.** No Leo gameplay has been decompiled or recompiled yet. The APK imports a verified Leo ROM and demonstrates a data-driven voxel rendering extension using original test artwork. The texture and 3D model explorers display locally decoded Leo assets in static bind poses. It does not execute DS code. Other editions are selectable placeholders.

## Research milestone 0.3

See [findings and Thor test instructions](docs/RESEARCH-03.md). The APK now renders 195 decoded Leo models with 576 material-bound textures, including Mega Man `pl000`. Drag, pinch or use the D-pad to inspect them. Animation and gameplay remain unfinished. The [0.2 research pipeline](docs/RESEARCH-02.md) still exports the broader texture gallery and decompresses all 646 ARM9 overlays.

```sh
python -m pip install -r requirements-research.txt
python tools/export_models.py /path/to/leo.nds --out research-private/leo-models.zip
```

Create the output directory first. Transfer `leo-models.zip` to your AYN Thor, use **Import Leo asset pack (.zip)**, then **Browse 3D models**. The model pack replaces the installed gallery pack. ROM-derived files are never bundled into the APK or committed.

## What works

- Android document-picker import of the profiled US Leo ROM, SHA-256 verification, bounded streaming, private FAT-file cache, transactional installation, and temporary-ROM cleanup.
- Eight-edition launcher, persistent local import, external JSON visual-mod installation.
- A bundled **Dramatic Voxel rendering study**: extruded procedural pixels, shaded faces, a grid, touch rotation, configurable depth and color. This is an original prototype inspired by the requested visual direction, not a port of another mod or a modification of Leo's scenes yet.
- Python NDS header/FAT/FNT/overlay inventory, safe extraction, explicit-range ARM/Thumb disassembly using optional Capstone, and same-size file repacking.
- ROM-independent parser tests and Android APK/lint CI.

## Android build and install

Use JDK 17, Gradle 8.9, Android SDK platform 35 and build-tools 34.0.0.

```sh
gradle :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Gradle is required on PATH; a wrapper is not bundled. Alternatively use the **Android research APK** GitHub Actions workflow and download the `StarForce-Recomp-research-debug` artifact from a successful run. The APK is debug-signed, not a store release. No ROM is needed to build it.

Open the app, select Leo, and choose **Import Leo ROM**. Unsupported hashes are rejected without replacing an existing import. Import consumes roughly 60 MiB of temporary/private storage. To remove the import, clear this app's storage in Android settings. Save files are not supported yet.

Choose **Open voxel mod preview** to inspect the independent renderer. Choose **Install visual mod (.json)** to load `mods/dramatic-voxel.json` or an edited copy. See [mod format](docs/MODS.md).

## Leo research tools

Python 3.10+; the base NDS parser uses only the standard library. Install `requirements-research.txt` for the expanded research tools and full test suite.

```sh
python -m unittest discover -s tests -v
python tools/nds.py inspect /path/to/leo.nds --out /tmp/leo-manifest.json
python tools/nds.py extract /path/to/leo.nds --out research-private/leo
python tools/nds.py repack /path/to/leo.nds --out research-private/roundtrip.nds
# Replace a FAT file only when the replacement is exactly the same byte length:
python tools/nds.py repack /path/to/leo.nds --out research-private/patched.nds --replace 646=/path/to/replacement.bin
# Optional research dependency:
python -m pip install capstone==5.0.3
python tools/disassemble.py /path/to/leo.nds --cpu arm9 --offset 0x800 --size 0x80 --mode arm
```

Extraction and repacking require the exact profiled ROM. Inspect accepts structurally valid DS containers. Output directories must be new. Repacking preserves file layout; it is **not** recompilation. Disassembly does not distinguish embedded data from instructions and requires the researcher to choose a known range and execution mode. Main code and overlays are now decompressed by the research tools; use `--overlay ID` to inspect a specific overlay.

## Architecture and remaining work

The supplied reference, [gen1recomp](https://github.com/bryanthaboi/gen1recomp), describes a handwritten native recreation with ROM-derived data. Its existing game-specific code is not a DS-to-Android translator. No code or assets from that repository have been copied here.

See [Leo research](docs/LEO.md) and [implementation milestones](docs/ROADMAP.md). A native recreation requires recovering Leo's asset formats, scripts, state transitions, combat, field logic, audio and save semantics. A true static recompilation route additionally needs complete ARM/Thumb translation, indirect-call/overlay resolution and implementations of DS hardware services. Neither route is completed by this commit.

## Content boundaries

The repository and APK contain original project code, metadata and procedural preview artwork only. No ROM, extracted game binaries, proprietary artwork or BIOS is included. Imports and developer extraction outputs stay local. This project is unofficial and is not affiliated with Capcom or Nintendo. No claim is made that this is the first Star Force recompilation project.
