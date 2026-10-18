"""Overworld (field) Mega Man recolours that follow the active transformation.

The field draws wave-changed Mega Man as 2D sprites from datbin/com/fieldobj.bin.
Every sprite set of his carries the same 16-colour palette, so the mod finds that
palette in the entries listed below (exact offsets in the verified US ROMs) and
rewrites its colours: the blue armour ramp and the dark hair/crest accent take the
form's colours, everything else (skin, visor, outline) stays. Nothing here
contains game data; the palettes are read from the user's ROM and checked first.
"""
import colorsys
import struct

# fieldobj.bin entry -> offset of Mega Man's palette in it (decoded entry data)
ENTRIES = {
    'leo': {73: 0xc92c, 136: 0x16e0, 154: 0x1a88, 157: 0x2e20, 212: 0xa40, 263: 0x930,
            264: 0x2a4, 292: 0x76c, 297: 0x738, 345: 0x1e4},
    'zerker-ninja': {58: 0x13410, 137: 0x2c28, 138: 0x57a8, 237: 0x1440, 238: 0x2840, 301: 0xe314,
                     302: 0x1c454, 335: 0x1aa0, 336: 0x34e0, 371: 0x1450, 372: 0x2850, 407: 0x16c,
                     408: 0x2ac, 486: 0xb34, 491: 0x198c, 492: 0x32cc, 514: 0xafc, 529: 0xd78,
                     539: 0x3a8, 554: 0xbb4, 559: 0x233c, 580: 0x3ac},
    'black-ace': {88: 0xa640, 306: 0x10c0, 324: 0x10c0, 352: 0x1950, 358: 0x2dfc, 359: 0x4a04, 360: 0x6410,
                  361: 0xfd0, 380: 0x8b4, 381: 0x428, 394: 0x2bf8, 430: 0x1508, 436: 0xb74, 437: 0x1c54},
}

# form -> (body dark, mid, light), (accent dark, light)
COLOURS = {
    'pegasus': ((30, 70, 120), (110, 190, 240), (235, 250, 255), (60, 100, 160), (170, 220, 255)),
    'leo': ((90, 20, 0), (230, 90, 20), (255, 215, 130), (70, 20, 80), (170, 70, 190)),
    'dragon': ((10, 60, 40), (40, 170, 110), (190, 250, 200), (100, 80, 20), (220, 190, 60)),
    'zerker': ((95, 75, 10), (225, 185, 35), (255, 250, 215), (45, 45, 45), (140, 140, 140)),
    'ninja': ((10, 60, 30), (40, 160, 80), (175, 240, 185), (120, 20, 30), (230, 60, 60)),
    'saurian': ((75, 10, 10), (205, 50, 30), (255, 175, 120), (90, 60, 20), (205, 150, 60)),
    'tribe-king': ((80, 10, 20), (210, 40, 50), (255, 195, 195), (90, 90, 100), (215, 215, 225)),
    'black-ace': ((12, 12, 18), (58, 58, 68), (155, 155, 170), (110, 0, 10), (230, 30, 40)),
    'red-joker': ((60, 0, 10), (185, 20, 30), (255, 125, 125), (20, 20, 20), (85, 85, 85)),
    'libra': ((10, 70, 60), (40, 170, 140), (190, 245, 225), (110, 80, 10), (235, 195, 60)),
    'corvus': ((20, 10, 30), (70, 45, 95), (165, 140, 190), (110, 0, 10), (225, 40, 45)),
    'cancer': ((90, 15, 10), (215, 70, 45), (255, 190, 160), (20, 60, 120), (90, 170, 240)),
    'gemini': ((100, 85, 10), (230, 200, 40), (255, 250, 200), (35, 35, 40), (200, 200, 210)),
    'ophiuca': ((50, 15, 70), (135, 60, 175), (225, 190, 245), (15, 80, 30), (70, 200, 90)),
    'cygnus': ((70, 80, 110), (180, 195, 225), (250, 252, 255), (20, 30, 90), (70, 100, 210)),
    'taurus': ((85, 10, 10), (210, 40, 35), (255, 180, 160), (110, 100, 90), (240, 235, 225)),
    'virgo': ((100, 30, 70), (225, 110, 170), (255, 215, 235), (20, 90, 110), (90, 210, 230)),
    'crown': ((25, 20, 15), (85, 70, 50), (190, 175, 150), (110, 80, 0), (240, 200, 40)),
    'wolf': ((15, 50, 25), (50, 125, 70), (180, 225, 190), (70, 70, 80), (195, 195, 205)),
    'rogue': ((20, 20, 60), (60, 60, 150), (175, 175, 235), (80, 80, 85), (225, 225, 230)),
}


def _rgb(c):
    return ((c & 31) << 3 | (c & 31) >> 2, (c >> 5 & 31) << 3 | (c >> 5 & 31) >> 2, (c >> 10 & 31) << 3 | (c >> 10 & 31) >> 2)


def _bgr555(r, g, b):
    return (round(r) >> 3) | (round(g) >> 3) << 5 | (round(b) >> 3) << 10


def _lum(rgb):
    r, g, b = rgb
    return 0.299 * r + 0.587 * g + 0.114 * b


def _lerp(a, b, t):
    return tuple(a[i] + (b[i] - a[i]) * t for i in range(3))


def looks_like_palette(raw):
    return len(raw) == 32 and raw[:2] == b'\0\0' and all(c < 0x8000 for c in struct.unpack('<16H', raw))


def classify(cols):
    """Indices of the armour ramp and the dark accent in Mega Man's palette."""
    body, accent = [], []
    for i, c in enumerate(cols[1:], 1):
        h, s, v = colorsys.rgb_to_hsv(*[x / 255 for x in _rgb(c)])
        h *= 360
        if 190 <= h <= 235 and s >= 0.3:
            body.append(i)
        elif 290 <= h <= 345 and s >= 0.4 and v < 0.5:
            accent.append(i)
    return body, accent


def recolour(raw, form):
    cols = list(struct.unpack('<16H', raw))
    body, accent = classify(cols)
    dark, mid, light, adark, alight = COLOURS[form]
    for group, ramp in ((body, (dark, mid, light)), (accent, (adark, alight))):
        if not group:
            continue
        ls = [_lum(_rgb(cols[i])) for i in group]
        lo, hi = min(ls), max(ls)
        for i, l in zip(group, ls):
            t = 0.5 if hi == lo else (l - lo) / (hi - lo)
            if len(ramp) == 3:
                rgb = _lerp(ramp[0], ramp[1], t * 2) if t < 0.5 else _lerp(ramp[1], ramp[2], (t - 0.5) * 2)
            else:
                rgb = _lerp(ramp[0], ramp[1], t)
            cols[i] = _bgr555(*rgb)
    return struct.pack('<16H', *cols)


def patched_entries(archive, game, form):
    """{entry index: new decoded bytes} for fieldobj.bin."""
    out = {}
    for index, off in ENTRIES[game].items():
        data = bytearray(archive.decode(index))
        raw = bytes(data[off:off + 32])
        if not looks_like_palette(raw):
            raise ValueError(f'fieldobj.bin entry {index} has no palette at {off:#x}; unsupported ROM?')
        data[off:off + 32] = recolour(raw, form)
        out[index] = bytes(data)
    return out
