# Custom overworld sprites

With a transformation on, Mega Man on the field is recoloured to match the form (see [TRANSFORMATIONS.md](TRANSFORMATIONS.md)). You can also give a form a **sprite sheet of your own**: in the app, pick the transformation, then **Mods for <game> → Overworld sprite → Import a sprite sheet (.png)**. It works in Leo and Zerker × Ninja, applies from the next start of the game, and switches with the form, including from the in-game menu.

## The sheet

- A PNG with a transparent background.
- **8 rows**, one per facing direction, top to bottom: **N, NE, E, SE, S, SW, W, NW** (N is walking away from the camera, up-right on screen; S is facing the camera).
- **7 poses per row**: a standing pose, then 6 running frames.
- Poses are found by the transparent gaps between them, so leave at least a few clear pixels between cells. Any cell size works.
- The app scales the poses so the tallest is 46 pixels (Mega Man's own height), and reduces the whole sheet to 14 colours. Bold, flat colours survive this best.
- **Size limit:** the game keeps Mega Man's whole sprite set in memory, so the new one must be no bigger than the original. Wide poses take more space; if they don't fit, the app first reuses running frames (3, then 2 distinct per cycle) and then shrinks the poses (42, 38, 34, 30 pixels tall) until they do. Tiles used only by the standard poses are freed for your art; the special animations keep theirs.

The walk cycle uses the first six running frames of the matching diagonal. The blink frames reuse the standing pose. Special animations (EM Wave Change, Wave In/Out, hurt and the like) keep Mega Man's original sprite.

Only use art you have the rights to. A sheet you import stays on your device; it isn't uploaded anywhere or put into the app.

## How it works

Mega Man's main field sprite set is `datbin/com/fieldobj.bin` entry 73 in Leo, and entries 58, 301 and 302 in Zerker × Ninja (Echo Ridge uses 301/302). An entry has four sections:

| Section | Contents |
|---|---|
| A | u16 tiles per frame (budget: 40 in Leo, 80 in Zerker × Ninja), u16 tile count, u32 tile offset, one (count, first tile) run per frame, then 4bpp 8×8 tiles |
| B | 16-colour palettes (Mega Man uses palette 0; colour 1 is the shadow) |
| C | animations: (frame, frames to hold, flags) steps; left untouched |
| D | frames: 8-byte pieces, each u8 tile unit (2 tiles), s8 x, s8 y, u8 OAM size, u8 OAM shape, u8 flip, u8 last-piece flag, u8 0 |

Frames 0–84 mean the same thing in both games: standing poses 0, 1, 2, 4, 6, 8, 10, 12 (N…NW, with blink frames 3, 5, 7, 9, 11), walk cycles 13–36 (NE, SE, SW, NW) and run cycles 37–84 (N…NW). The importer cuts each pose into OAM pieces, keeps the original shadow piece, drops the tiles only frames 0–84 used, compacts the rest, appends the new tiles, rewrites palette 0 and the frame tables, and leaves every other frame and animation alone. The game needs the last piece of each frame marked with flag 1; without it, it keeps drawing pieces past the end.

Code: `FieldSprite.java`. Verified in both games on a fresh boot: standing, walking and running in all directions, with the shadow in place.
