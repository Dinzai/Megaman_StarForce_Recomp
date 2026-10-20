# Power-ups (Zerker × Ninja)

Three switches under **Mods for Zerker × Ninja**. They apply the next time the game starts.

| Switch | What it does |
|---|---|
| **HP+1000 & Super Armor** | You own two abilities, both costing 0 Link Power: **HP+1000** (+1000 max HP) and **SprArmr** (Super Armor: you don't flinch when hit). Equip them under **Menu › Mega › Equip Abil.** |
| **Blank Cards → Mega/Giga** | Every Blank Card you own turns into one of the strongest Mega/Giga cards, one per Blank Card. |
| **All ten cards now** | Puts all ten of those cards in your CardBox right away, without finding the Blank Cards. |

The ten Blank Cards (370–379 in the game's numbering) and what each becomes:

| Blank Card | Becomes | Class | Damage |
|---|---|---|---|
| 1 | PegasusMagic | Giga | 450 aqua, Pegasus Freeze on the far 3×3 |
| 2 | LeoKingdom | Giga | 40 per hit, Leo Blazer on the far 3×3 |
| 3 | DragonSky | Giga | 50 per hit, Dragon Cyclone on the far 3×3 |
| 4 | LeoKingdom SP | Mega | 400 fire |
| 5 | CrownThunder SP | Mega | 330 elec |
| 6 | DragonSky SP | Mega | 330 wood |
| 7 | CygnusWing SP | Mega | 300 |
| 8 | PegasusMagic SP | Mega | 260 aqua |
| 9 | WolfWoods SP | Mega | 170 wood |
| 10 | LibraScales SP | Mega | 150 |

The first three are Star Force 1's Satellite Admin Giga cards. The game's folder rules still apply: a folder holds one Giga card (more with the G-class folder abilities).

## How it works

Found by tracing the menus with `sfhost` on a save just after the first boss.

- **CardBox:** one 4-byte entry per card number at `0x02119C50 + n × 4`: `+0` a sort stamp, `+2` the number of copies you own (folder copies included). Standard cards are 1–150; Mega/Giga cards are 256 + their index in `mess.bin` entry 1057, so Blank Cards are 370–379 and PegasusMagic (Giga) is 298.
- **Blank Cards:** for each Blank Card, if you own it (`A` condition on its count), its count is set to 0 and the target card's count goes up by one (`D9`/`D4`/`D6`).
- **Abilities:** 8-byte records at `0x020DFC90 + id × 8`: `+0` name index (`mess.bin` 1070), `+3` effect type (`0x25` = HP+), `+4` u16 amount, `+6` u16 Link Power cost. Owned abilities are one byte per id at `0x0211D652`; equipped ones are 48 bytes at `0x0211D622`. HP+500 (version 4, id 93) is turned into HP+1000 (amount 1000, cost 0, its own name and description); Super Armor (id 55) gets cost 0.

**Verified** with your save: the Blank Card in your CardBox turned into PegasusMagic (Giga, 450); HP+1000 and SprArmr show in the ability list at cost 0, and with both equipped Geo has 1170/1170 HP (170 + 1000). The new cards haven't been used in a battle yet.

Turning a switch off stops it from now on. Cards already converted or given stay in your CardBox. Abilities stay owned with their original cost, so the game may refuse to keep them equipped if your Link Power is too low.
