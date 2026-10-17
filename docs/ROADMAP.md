# Milestones

Leo first. Other editions get their own verified profiles after Leo is complete.

1. ✅ **Research import (0.1).** Exact-ROM profile, Android import, FAT/FNT/overlay inventory, voxel preview.
2. ✅ **Decode real assets (0.2–0.3).** Archive format, LZ10, overlays decompressed, 195 bind-pose models and 576 textures in the Android explorer.
3. ✅ **Playable runtime (0.4, this revision).** Embedded melonDS 1.1 core with FreeBIOS direct boot, JNI session layer, Android play screen (touch controls, gamepad, dual display), audio, battery saves, save states. Verified on the desktop runner: boot → title → New Game → intro.
4. ✅ **Mod loader v2 (0.4).** Zip mods replacing any ROM file at any size, plus AR memory codes. Load order, conflicts, atomic install. `tools/make_mod.py` asset pipeline with a working example.
5. ⏳ **Device validation.** Install the CI APK on the AYN Thor and a phone. Check frame rate with JIT, audio latency, dual-screen touch, controller mapping, save persistence across app kills, and pause/resume. Fix what breaks.
6. ⏳ **Observation hooks and golden traces.** Started: `WatchNDS` records ARM9 accesses in watched ranges with the PC (`sfhost --watch`). It found the save-header check and checksum ([SAVES.md](SAVES.md)). Scripted runs from power-on into a battle are reproducible (`sfhost --input`, `--load-state`/`--save-state`). PC breakpoints and memory watch in `Session`. Identify the field loop, script dispatcher, battle loop and save routine, then record traces for regression tests.
7. **Native overrides.** `Session::Override(addr, fn)`. Port leaf functions first, each checked by differential tests against the emulated original.
8. **Overlay recompilation.** Static ARM/Thumb → C translation of whole overlays, registered as overrides. Emulation remains the fallback.
9. **Renderer/asset hooks.** Runtime model and texture replacement from decoded NSBMD/NSBTX. HD and voxel rendering mods.
   - ✅ 0.7: scene-depth voxel renderer, unified transformations, custom battle cards.
10. **Script mods and Leo completion.** Event/dialogue data exposed to mods; full regression playthrough with save compatibility.

CI builds and unit-tests everything. Gameplay checks currently run on the desktop runner (`sfhost`) with scripted input; device testing is manual.
