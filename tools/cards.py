"""Star Force standard battle cards (Leo and Zerker x Ninja): build card mods.

Found by tracing the custom screen with sfhost (see docs/CARDS.md):

* ARM9 static data holds 169 standard-card records of 0x2C bytes. Card N's
  record starts at RAM 0x020B8E7C + N * 0x2C (card 0 is the Mega Buster).
    +0x00 u8  element code: 1 + 4 * element (0 none, 1 fire, 2 aqua, 3 elec, 4 wood)
    +0x01 u8  (6 for every card)
    +0x02 u8  card family
    +0x03 u8  version within the family (high nibble)
    +0x06 u16 damage
    +0x24 u32 picture offset in battlecard.bin
    +0x28 u32 picture palette
    Cards 1-149 are the standard cards; 150-168 are unnamed leftovers.
  The card's attack routine and name are chosen by its number, not its record:
  copying another card's record changes picture, damage and element only.
* Names: datbin/eng/mess.bin entry 1249, a u16 offset table (256 entries)
  followed by strings that end in E6 00. Letters A-Z are 0x21-0x3A, a-z are
  0x41-0x5A, digits 0x10-0x19, space 0x00.

* Zerker x Ninja (see GAMES['zerker-ninja']): 24-byte records at
  0x020BBA98 + N * 24; +0x00 element code, +0x08 u16 picture, +0x0A u16
  picture palette, +0x0E u16 damage. Names are mess.bin entry 1056, strings
  ending in FA 18 E6 or E6. Cards 1-149 are standard cards.

Nothing here embeds game data; everything is read from the user's own ROM.
"""
import struct

TABLE_ROM_OFFSET = 0xB8E7C  # offset into the decompressed ARM9 binary
TABLE_RAM = 0x02000000 + TABLE_ROM_OFFSET
RECORD = 0x2C
COUNT = 169
LAST = 149  # last named standard card (ParlyzPlus)
NAMES_FILE = 'datbin/eng/mess.bin'
NAMES_ENTRY = 1249
NAME_MAX = 10

# Per-game layout: table RAM address, record size, last standard card, name
# entry in mess.bin, and offsets of element (u8), damage (u16) and the
# picture words (offset, length).
GAMES = {
    'leo': dict(table=0x020B8E7C, record=0x2C, last=149, names=1249, element=0x00, damage=0x06, picture=(0x24, 8)),
    'zerker-ninja': dict(table=0x020BBA98, record=24, last=149, names=1056, element=0x00, damage=0x0E, picture=(0x08, 4)),
    # Star Force 3: one 16-byte table for every card (standard, Mega, Giga);
    # element bits 2-4 of +0x00 (the low/high bits mark the card class), names
    # in mess_static.bin entry 15 as a GSM table (UTF-16, XOR 0x55).
    'black-ace': dict(table=0x020D18BC, record=16, last=397, names=15, names_file='datbin/eng/mess_static.bin',
                      text='gsm', element=0x00, damage=0x08, picture=(0x04, 4)),
}


def names_file(game):
    return GAMES[game].get('names_file', NAMES_FILE)


# --- Star Force 3 "GSM" text tables -------------------------------------------
# ' GSM', u16 version, u16 count, u16 longest, u16 0xFFFF, then count x (u16
# offset, u16 length in characters); strings are UTF-16LE ASCII XOR 0x55 with
# '_' for space, each followed by a 0x0000 (0x5555 after XOR) terminator.

def gsm_split(entry):
    n = struct.unpack_from('<H', entry, 6)[0]
    out = []
    for i in range(n):
        off, ln = struct.unpack_from('<HH', entry, 12 + i * 4)
        out.append(entry[off:off + ln * 2])
    return out


def gsm_text(raw):
    s = ''
    for i in range(0, len(raw), 2):
        c = struct.unpack_from('<H', raw, i)[0] ^ 0x5555
        s += ' ' if c == 0x5F else chr(c)
    return s


def gsm_encode(text):
    encode_name(text)  # same allowed characters and length as the other games
    return bytes(b ^ 0x55 for b in text.replace(' ', '_').encode('utf-16-le'))


def gsm_join(entry, strings):
    n = len(strings)
    head = bytearray(entry[:12 + 4 * n])
    body = bytearray()
    for i, raw in enumerate(strings):
        struct.pack_into('<HH', head, 12 + i * 4, len(head) + len(body), len(raw) // 2)
        body += raw + b'\x55\x55'
    longest = max(len(r) // 2 for r in strings)
    struct.pack_into('<H', head, 8, max(longest, struct.unpack_from('<H', entry, 8)[0]))
    return bytes(head + body)

ELEMENTS = ['none', 'fire', 'aqua', 'elec', 'wood']


def encode_name(text):
    out = bytearray()
    for ch in text:
        if 'A' <= ch <= 'Z':
            out.append(0x21 + ord(ch) - 65)
        elif 'a' <= ch <= 'z':
            out.append(0x41 + ord(ch) - 97)
        elif '0' <= ch <= '9':
            out.append(0x10 + ord(ch) - 48)
        elif ch == ' ':
            out.append(0)
        elif ch == '-':
            out.append(0x0D)
        else:
            raise ValueError(f'card names may use letters, digits, space and "-" only: {text!r}')
    if not 1 <= len(out) <= NAME_MAX:
        raise ValueError(f'card names must be 1-{NAME_MAX} characters: {text!r}')
    return bytes(out)


def decode_name(raw):
    s = ''
    for b in raw:
        if 0x21 <= b <= 0x3A:
            s += chr(65 + b - 0x21)
        elif 0x41 <= b <= 0x5A:
            s += chr(97 + b - 0x41)
        elif 0x10 <= b <= 0x19:
            s += chr(48 + b - 0x10)
        elif b == 0:
            s += ' '
        elif b == 0x0D:
            s += '-'
        else:
            s += '?'
    return s


def split_names(entry):
    n = struct.unpack_from('<H', entry, 0)[0] // 2
    offs = list(struct.unpack_from(f'<{n}H', entry, 0)) + [len(entry)]
    return [entry[offs[i]:offs[i + 1]] for i in range(n)]


def join_names(strings):
    head = 2 * len(strings)
    out, at = bytearray(), head
    offs = []
    for s in strings:
        offs.append(at)
        at += len(s)
    if at > 0xFFFF:
        raise ValueError('name table too large')
    return struct.pack(f'<{len(offs)}H', *offs) + b''.join(strings)


def _text_end(raw):
    """Index of the first control byte (>= 0xE0): the name ends there."""
    for i, b in enumerate(raw):
        if b >= 0xE0:
            return i
    return len(raw)


def rename(entry, renames, game='leo'):
    """Return the names entry with {card number: new name} applied.

    The original string's trailing control bytes (E6 00 in Leo, FA 18 E6 in
    Zerker x Ninja) are kept.
    """
    if GAMES[game].get('text') == 'gsm':
        strings = gsm_split(entry)
        for card, name in renames.items():
            strings[card] = gsm_encode(name)
        return gsm_join(entry, strings)
    strings = split_names(entry)
    for card, name in renames.items():
        old = strings[card]
        suffix = old[_text_end(old):] or b'\xe6'
        strings[card] = encode_name(name) + suffix
    return join_names(strings)


def card_names(entry, game='leo'):
    if GAMES[game].get('text') == 'gsm':
        return [gsm_text(r) for r in gsm_split(entry)]
    return [decode_name(s[:_text_end(s)]) for s in split_names(entry)]


def lookup(names, ref, last=LAST):
    """Card number from an int, a numeric string or a (case-insensitive) name."""
    if isinstance(ref, int):
        n = ref
    elif str(ref).isdigit():
        n = int(ref)
    else:
        low = [x.lower() for x in names]
        if str(ref).lower() not in low:
            raise ValueError(f'unknown card {ref!r}')
        n = low.index(str(ref).lower())
    if not 1 <= n <= last:
        raise ValueError(f'card {ref!r} is not a standard card (1-{last})')
    return n


def card_code(card, damage=None, element=None, picture=None, game='leo'):
    """Action Replay lines for one card override (ARM9 static data, always loaded).

    The picture is copied at run time from another card's record (D3 sets the
    source, F copies the picture words), so nothing is read from the ARM9 binary.
    """
    g = GAMES[game]
    addr = g['table'] + card * g['record']
    poff, plen = g['picture']
    lines = [f'# card {card}']
    if picture is not None:
        lines.append(f'D3000000 {g["table"] + picture * g["record"] + poff:08X}')
        lines.append(f'{0xF0000000 | (addr + poff):08X} {plen:08X}')
        lines.append('D2000000 00000000')
    if element is not None and g.get('text') == 'gsm':
        # keep the card-class bits: byte = (byte & 0xE3) | element << 2
        lines.append(f'DB000000 {addr + g["element"]:08X}')
        lines.append('D4000002 000000E3')
        lines.append(f'D4000001 {4 * ELEMENTS.index(element):08X}')
        lines.append(f'D8000000 {addr + g["element"]:08X}')
        lines.append('D2000000 00000000')
    elif element is not None:
        lines.append(f'{0x20000000 | (addr + g["element"]):08X} {1 + 4 * ELEMENTS.index(element):08X}')
    if damage is not None:
        if not 0 <= damage <= 999:
            raise ValueError('damage must be 0-999')
        lines.append(f'{0x10000000 | (addr + g["damage"]):08X} {damage:08X}')
    return '\n'.join(lines) + '\n'


def build_pack(names_entry, pack):
    """(cheat text, new names entry) for a card pack dict (see docs/CARDS.md)."""
    if pack.get('format') != 'starforce-cards':
        raise ValueError('not a card pack (format must be "starforce-cards")')
    game = pack.get('game', 'leo')
    if game not in GAMES:
        raise ValueError(f'card packs support {", ".join(GAMES)}; not {game!r}')
    names = card_names(names_entry, game)
    last = GAMES[game]['last']
    code, renames, seen = [], {}, set()
    for c in pack.get('cards', []):
        base = lookup(names, c['base'], last)
        if base in seen:
            raise ValueError(f'card {names[base]} is replaced twice')
        seen.add(base)
        element = c.get('element')
        if element is not None and element not in ELEMENTS:
            raise ValueError(f'unknown element {element!r}')
        picture = lookup(names, c['picture'], last) if c.get('picture') is not None else None
        code.append(card_code(base, c.get('damage'), element, picture, game))
        if c.get('name'):
            renames[base] = c['name']
    return ''.join(code), rename(names_entry, renames, game)
