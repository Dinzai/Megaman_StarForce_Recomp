# Runtime architecture (0.4)

```
Android app (Java)                       native/ (C++, libsfrcore.so)
┌───────────────────────────┐            ┌─────────────────────────────────────────┐
│ MainActivity  launcher,   │            │ jni_bridge.cpp   one call per frame     │
│               import, mods│  JNI       │ session.cpp      boot, input, audio,    │
│ GameActivity  surfaces,   │──────────▶ │                  saves, states, cheats  │
│   input, audio, menu      │            │ rompatch.cpp     NitroFS file replace   │
│ ModManager    zip mods    │            │ platform.cpp     melonDS Platform API   │
└───────────────────────────┘            │ melonDS 1.1 core (FetchContent, pinned) │
                                         │   ARM9/ARM7 interpreter + ARM64 JIT,    │
                                         │   2D/3D software renderer, SPU, cart    │
                                         └─────────────────────────────────────────┘
```

## Boot sequence

1. `ModManager.launchConfig()` resolves enabled mods in load order into ROM file replacements and memory codes. Conflicts are reported.
2. `Session::Load` copies the imported ROM. `ApplyFileReplacements` then writes each replacement in place when it fits. Otherwise it relocates the file into the ROM's unused tail and grows the image to the next power of two if needed. It updates the FAT, the header's used-size and capacity fields, and the header CRC.
3. melonDS builds the cartridge from the patched image, loads the battery save and direct-boots with FreeBIOS and generated firmware. No user BIOS is required.
4. Every frame: `SetInput` → `RunFrame` → screens (2×256×192 ARGB) and 48 kHz stereo audio are returned to Java. The AR engine runs memory codes once per frame. Save writes are mirrored and flushed by Java within about 60 frames.

## Measured (desktop runner, x86-64 JIT, single thread)

| Check | Result |
|---|---|
| Boot → Capcom logo → title | frames 60–600, ~380 fps |
| Title → New Game → intro dialogue | scripted input, frames 1000–3200 |
| Audio | 802 frames/video frame at 48 kHz, audible |
| Save state round trip | 19.5 MB raw state, restores correctly |
| Asset mod (`capcomlogo.bin` grown and relocated) | recolored logo, game continues |
| Memory code (`023FF000 C0FFEE11`) | value present after run |

Android performance has not been measured. The ARM64 JIT plus the threaded software renderer is expected to run full speed on Snapdragon 8-class handhelds. Turn off **Fast CPU (JIT)** in Settings if a device crashes on boot.

## From emulation to recompilation

The emulator gives us a correct, playable baseline and a ground truth to diff against. The recompilation work replaces pieces of it, one verifiable step at a time:

1. **Observation hooks (next).** Add a breakpoint/trace hook to the session (melonDS's GDB-stub plumbing, or a small patch to `ARM::Execute`). It will log overlay loads, the field-loop entry and the script dispatcher, and give golden traces of real execution. This replaces the guessing that limited the 0.2/0.3 static analysis.
2. **Native function overrides.** Register `Session::Override(addr, fn)`. When the ARM9 PC reaches `addr`, the native C++ `fn` runs against emulated registers and memory and returns. Start with leaf functions (math, decompression, text layout), and validate each against the traced original with differential tests in `sfhost`.
3. **Static recompilation of overlays.** Use the existing overlay decompressor and Capstone tooling to translate whole overlays to C. The translated code is registered through the same override table. Untranslated code keeps running on the JIT, so coverage can grow overlay by overlay.
4. **Renderer and asset hooks.** Intercept the game's model and texture loads, using the decoded NSBMD formats from 0.3. Mods can then swap geometry and materials at runtime, and an HD/voxel renderer becomes possible without patching the ROM.
5. **Script-level mods.** Once the event script interpreter is identified, expose events and dialogue as data that mods can edit.

Each step keeps the game playable, and the emulator stays available as a fallback and as a correctness oracle.

## Files written on device

`files/leo/game.nds` (verified import), `battery.sav`, `state1.sst.gz`, `files/mods/<id>/…` and `mods/state.json`. Clearing app storage removes everything.
