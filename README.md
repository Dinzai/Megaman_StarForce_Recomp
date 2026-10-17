# Mega Man Star Force Recomp

An Android app that turns your own **Mega Man Star Force – Leo (USA)** cartridge dump into a standalone, moddable game. Import the `.nds` file once and the app plays it, with on-screen or controller input, battery saves, save states, dual-screen support (AYN Thor and similar devices), and a mod loader.

**Status: 0.4 runtime milestone. Leo boots and plays.** On a desktop test runner, the runtime boots to the title screen, starts a New Game and plays the opening story with sound. It also loads save states and applies asset mods and memory-code mods. The APK is built by CI; it has not been tested on a phone or the Thor yet. The other Star Force editions are not supported yet.

## How it works (the shortcut)

Hand-decompiling a DS game takes years. To get a playable, moddable APK now, the game runs on an embedded copy of the [melonDS](https://melonds.kuribo64.net) core (pinned to 1.1). Its ARM64 JIT recompiles Leo's ARM code at runtime. Around that core sits a Star Force layer, `native/`, which owns booting, input, audio, saves and the mod hooks. Over time we replace emulated subsystems and game functions with native code without breaking playability. The plan is in [docs/RUNTIME.md](docs/RUNTIME.md).

The research tools from 0.2/0.3 (asset decoding, overlay decompression, the 3D model explorer) are still here. They feed the asset-mod pipeline.

## Use it

1. Download the latest `StarForceRecomp-vX.Y.Z.apk` from [Releases](https://github.com/Dinzai/Megaman_StarForce_Recomp/releases) and install it. Development builds from every push are on the **Android APK** workflow runs (artifact `StarForce-Recomp-debug-apk`). Both are signed with the same key, so they install over each other (see `signing/README.md`).
2. Open it, select **Leo**, tap **Import Leo ROM** and pick your dump. Only the profiled US Leo dump is accepted (SHA-256 `defbc6f6…c5d6`).
3. Tap **▶ Play Leo**. Back (or the ☰ button) opens the menu: save/load state, fast-forward, layout, button mapping, quit.

Controls: face buttons map by position (right = DS A). D-pad and left stick work as the D-pad, L1/R1 as L/R. Touch the bottom screen to use the DS touch panel. On a device with a second display, the bottom DS screen moves there automatically; you can turn this off in Settings. Battery saves are written to app storage within a second of the game saving.

## Mods

Mods are `.zip` packages. A mod can replace any file in the ROM, at any size, and add Action Replay-style memory codes. Install with **Install game mod (.zip)**, then reorder or toggle under **Manage mods**. Format and tools: [docs/MODS.md](docs/MODS.md).

```sh
# Build the demo asset mod from your own ROM, then install the zip on the device
python tools/make_mod.py example-recolor /path/to/leo.nds --out recolor-logo.zip
```

## Releasing

Push a tag such as `v0.4.2`. The **Release APK** workflow builds a signed APK and publishes it as a GitHub release. Optional release notes go in `docs/releases/<tag>.md`.

## Build

Android: JDK 17, Gradle 8.9, SDK platform 35, build-tools 34.0.0, NDK 27.2.12479018, CMake 3.22.1. The native build fetches melonDS from GitHub at a pinned commit (pass `-PmelonDSDir=/path/to/melonDS` to build offline).

```sh
gradle :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Desktop test runner (Linux/macOS, any C++17 compiler):

```sh
cmake -S native -B build-host -G Ninja -DSFR_BUILD_HOST=ON -DCMAKE_BUILD_TYPE=Release
cmake --build build-host
./build-host/sfr_native_tests
./build-host/sfhost /path/to/leo.nds --frames 1200 --shot 600,1200 --out /tmp/shots   # PPM screenshots
./build-host/sfhost /path/to/leo.nds --replace datbin/com/capcomlogo.bin=mod.bin --cheats codes.txt --state-test 900
```

`sfhost --input FILE` replays scripted button presses (`FRAME KEYS [HOLD]`, e.g. `1000 START`). CI runs the native tests, Python tests, Java unit tests and the APK build. Then it runs lint.

## Research tools

Python 3.10+; `pip install -r requirements-research.txt`.

```sh
python -m unittest discover -s tests -v
python tools/nds.py inspect /path/to/leo.nds --out /tmp/leo-manifest.json
python tools/export_models.py /path/to/leo.nds --out research-private/leo-models.zip   # 3D model explorer pack
python tools/disassemble.py /path/to/leo.nds --cpu arm9 --offset 0x800 --size 0x80 --mode arm
```

See [docs/LEO.md](docs/LEO.md), [docs/RESEARCH-02.md](docs/RESEARCH-02.md) and [docs/RESEARCH-03.md](docs/RESEARCH-03.md).

## License and content boundaries

This project is licensed under the **GNU GPL v3 or later** (see `LICENSE`) because it links the GPL-licensed melonDS core. The repository and APK contain only original project code and melonDS. They contain **no ROM, no extracted game data, no Nintendo BIOS or firmware**: melonDS's built-in FreeBIOS and generated firmware are used. You must supply your own legally obtained dump. Mods you distribute should contain only your own changes. This project is unofficial and is not affiliated with Capcom or Nintendo.
