# Leo US: observed container layout

Profiled from the user-supplied dump. These values identify this input, not every region or revision.

- Internal title: `MEGAMAN_SF_L`
- Game code: `A6BE`
- Size: 33,554,432 bytes
- SHA-256: `defbc6f639fb0bcd11a229d1a0c7c9f595447034ae25d3ba4a97e1268156c5d6`
- FAT entries: 693; named files: 47
- ARM9 overlay records: 646; ARM7 overlay records: 0

| Processor | ROM offset | Load address | Entry point | Stored bytes |
|---|---:|---:|---:|---:|
| ARM9 | `0x00004000` | `0x02000000` | `0x02000800` | 599,692 |
| ARM7 | `0x001ac000` | `0x02380000` | `0x02380000` | 158,024 |

The FNT exposes asset containers under `datbin/com` and `datbin/eng`. Names such as `models.bin`, `motions.bin`, `stages.bin`, `field.bin`, `mess.bin` and `sound_data.sdat` are useful investigation starting points; their internal formats have not yet been decoded here. Overlay count is a record count, not a recovered function count.

Full metadata is in [the profile](../profiles/leo-us.json). Run `tools/nds.py inspect` to reproduce offsets, hashes and all overlay records locally. No extracted bytes are committed.

Format reference: [GBATEK DS documentation](https://problemkaputt.de/gbatek.htm). Reference application architecture: [gen1recomp README](https://github.com/bryanthaboi/gen1recomp). Android toolchain versions follow [AGP 8.7 compatibility](https://developer.android.com/build/releases/agp-8-7-0-release-notes).
