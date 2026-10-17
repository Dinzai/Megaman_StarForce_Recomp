# Custom battle cards (Leo and Zerker × Ninja)

A **card pack** re-skins standard battle cards. Each card in a pack names a *base* card. The custom card keeps the base card's attack (how it fires and moves) and gets a new **name**, **damage**, **element** and **picture**. It then shows up wherever the base card does: in your folder, the custom screen, the library and shops.

In the app, go to **Mods for Leo → Battle cards** (or **Mods for Zerker × Ninja → Battle cards**). Turn packs on or off, or **Import a card pack (.json)**. Changes apply the next time the game starts. Packs that come with the app:

| Pack | Cards |
|---|---|
| Elemental Cannons | Cannon → FireCannon 70 fire · PlusCannon → AquaCannon 120 aqua · HevyCannon → VoltCannon 190 elec |
| Elemental Swords | Sword → FlameSword 110 fire · WideSword → FrostSword 110 aqua · LongSword → LeafSword 110 wood |
| Test Cards | Gatling3 → TestBlast 150 fire (Cannon picture) · Gatling1/2 → Test99A/B, 99 damage |
| Elemental Cannons (Zerker × Ninja) | Cannon → FireCannon 70 fire · PlusCanon → AquaCannon 120 aqua · HevyCanon → VoltCannon 190 elec |
| Test Cards (Zerker × Ninja) | PlsmaGun1 → TestBlast 150 fire (Cannon picture) · AirSpred1 → Test99, 99 wood |

## Format

```json
{
  "format": "starforce-cards",
  "version": 1,
  "game": "leo",
  "id": "yourname.fire-cannons",
  "name": "Fire Cannons",
  "author": "yourname",
  "description": "Shown in the app.",
  "cards": [
    {"base": "Cannon", "name": "FireCannon", "damage": 70, "element": "fire", "picture": "HeatBall1"}
  ]
}
```

- **game** is `leo` or `zerker-ninja`. A pack only loads in its own game.
- **base** (required) is the card to replace: its number (1–149) or its name as the game spells it (`HevyCannon`, `Fokx-Fu1`; Zerker × Ninja shortens some, like `PlusCanon`). List them with `python tools/make_mod.py list-cards rom.nds`.
- **name** has 1–10 characters: letters, digits, space and `-`.
- **damage** is 0–999.
- **element** is `none`, `fire`, `aqua`, `elec` or `wood`.
- **picture** is the card whose artwork to show (number or name).
- Every field except `base` is optional; anything you leave out stays as it was.
- Several packs can be on at once. If two change the same card, the pack later in the list wins.

On a PC, `python tools/make_mod.py cards rom.nds pack.json --out pack.zip` turns a pack into an ordinary mod zip. It contains the rebuilt name table from your ROM, so keep it to yourself.

## How it works

Found by tracing the custom screen with `sfhost`:

- Leo's 169 standard-card records live in ARM9 static data. Card *N*'s record starts at `0x020B8E7C + N × 0x2C`:

  | Offset | Size | Field |
  |---|---|---|
  | `+0x00` | u8 | element code: 1 + 4 × element (0 none, 1 fire, 2 aqua, 3 elec, 4 wood) |
  | `+0x02` | u8 | card family |
  | `+0x03` | u8 | version within the family (high nibble) |
  | `+0x06` | u16 | damage |
  | `+0x24` | u32 | picture offset in `battlecard.bin` |
  | `+0x28` | u32 | picture palette |

  Cards 1–149 are the named standard cards; 150–168 are unnamed leftovers. Mega and Giga cards (Taurus Fire and the others) are in a different table and aren't supported yet.
- The game picks a card's **attack routine and name by card number**, not from the record. Copying a whole record into another card changes only its picture, damage and element; the animation stays the same.
- **Names** are in `datbin/eng/mess.bin`, entry 1249: a table of 256 u16 offsets followed by strings that end in `E6 00`. Text uses A–Z = `0x21`–`0x3A`, a–z = `0x41`–`0x5A`, 0–9 = `0x10`–`0x19`, space = `0x00` and `-` = `0x0D`.
- A pack becomes one mod. Its Action Replay codes write damage and element, and copy the picture words from the source card at run time (`D3` sets the source address, `F` copies 8 bytes). Its `entries` replacement rebuilds the name table from your ROM. The app combines every enabled pack into a single generated mod called `battle-cards`.

**Verified:** in a scripted battle, the custom screen and the in-battle card banner show the new name, picture, element icon and damage (TestBlast 150, fire, Cannon art). The custom card's hit damage hasn't been checked yet: the scripted run kept missing moving enemies.

### Zerker × Ninja

- 24-byte records in ARM9 static data; card *N* starts at `0x020BBA98 + N × 24`: `+0x00` element code (as in Leo), `+0x08` u16 picture, `+0x0A` u16 picture palette, `+0x0E` u16 damage. Cards 1–149 are standard cards.
- Names are `mess.bin` entry 1056. Most strings end in `FA 18 E6` instead of `E6 00`; the renamer keeps whatever control bytes the original name ended with.
- The game reads the name table when it boots, so loading a save state doesn't pick up new names. Restart the game after changing packs.
- **Verified** on the folder edit screen: the test pack's TestBlast shows the new name, the fire icon, 150 damage and the Cannon picture.

**Not possible yet:** new attack behaviours, brand-new card numbers, Mega/Giga cards, card descriptions, and Black Ace cards. All of these need more reverse engineering.
