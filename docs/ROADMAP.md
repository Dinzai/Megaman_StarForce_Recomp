# Milestones and acceptance criteria

1. **Research import (this revision):** exact-ROM profile; Android library and private import; FAT/FNT/overlay inventory; malformed-container tests; independent voxel preview. Device testing still required.
2. **Decode a real Leo scene:** identify container indexes/compression for field.bin, fieldbg.bin, models.bin and motions.bin; export one local scene with texture/palette/geometry metadata; compare against captured DS output. Preserve provenance for every inferred format.
3. **Recover execution structure:** disassemble known ARM9/ARM7 entry points, decompress overlays, establish ARM/Thumb boundaries, relocation/load rules and function ownership. Identify field-loop, script dispatch, collision, input and render callsites. Do not treat every aligned word as code.
4. **Choose and validate runtime route:** either port understood game logic natively (reference-project approach), or implement static translation with tested arithmetic/flags, memory semantics and DS service replacements. Prove a single scene advances correctly before advertising playability.
5. **Playable Leo vertical slice:** title → new game → one field → one battle → save/load; accurate input, dialogue, deterministic battle state, sound and Android pause/resume. Golden traces from original execution gate correctness.
6. **Actual voxel gameplay mod:** feed recovered scene assets through replacement geometry/material hooks; toggling the mod must preserve gameplay state and collisions. Compare modded/unmodded captures and frame times on an Android device.
7. **Leo completion:** story progression, scripted events, cards, equipment, encounters, audio and save compatibility, plus regression coverage. Later editions receive their own verified profiles and compatibility work.

The APK build pipeline verifies compilation and lint only. It cannot verify gameplay parity, touch usability or rendering correctness without device/emulator execution and the missing game runtime.
