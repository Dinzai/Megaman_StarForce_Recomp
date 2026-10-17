"""Star Force 1 (Leo) standard battle cards: read the card table, build card mods.

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


def rename(entry, renames):
    """Return the names entry with {card number: new name} applied."""
    strings = split_names(entry)
    for card, name in renames.items():
        strings[card] = encode_name(name) + b'\xe6\x00'
    return join_names(strings)


def card_names(entry):
    return [decode_name(s.split(b'\xe6')[0]) for s in split_names(entry)]


def lookup(names, ref):
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
    if not 1 <= n <= LAST:
        raise ValueError(f'card {ref!r} is not a standard card (1-{LAST})')
    return n


def card_code(card, damage=None, element=None, picture=None):
    """Action Replay lines for one card override (ARM9 static data, always loaded).

    The picture is copied at run time from another card's record (D3 sets the
    source, F copies 8 bytes), so nothing is read from the ARM9 binary here.
    """
    addr = TABLE_RAM + card * RECORD
    lines = [f'# card {card}']
    if picture is not None:
        lines.append(f'D3000000 {TABLE_RAM + picture * RECORD + 0x24:08X}')
        lines.append(f'{0xF0000000 | (addr + 0x24):08X} 00000008')
        lines.append('D2000000 00000000')
    if element is not None:
        lines.append(f'{0x20000000 | addr:08X} {1 + 4 * ELEMENTS.index(element):08X}')
    if damage is not None:
        if not 0 <= damage <= 999:
            raise ValueError('damage must be 0-999')
        lines.append(f'{0x10000000 | (addr + 6):08X} {damage:08X}')
    return '\n'.join(lines) + '\n'


def build_pack(names_entry, pack):
    """(cheat text, new names entry) for a card pack dict (see docs/CARDS.md)."""
    names = card_names(names_entry)
    if pack.get('format') != 'starforce-cards':
        raise ValueError('not a card pack (format must be "starforce-cards")')
    code, renames, seen = [], {}, set()
    for c in pack.get('cards', []):
        base = lookup(names, c['base'])
        if base in seen:
            raise ValueError(f'card {names[base]} is replaced twice')
        seen.add(base)
        element = c.get('element')
        if element is not None and element not in ELEMENTS:
            raise ValueError(f'unknown element {element!r}')
        picture = lookup(names, c['picture']) if c.get('picture') is not None else None
        code.append(card_code(base, c.get('damage'), element, picture))
        if c.get('name'):
            renames[base] = c['name']
    return ''.join(code), rename(names_entry, renames)
