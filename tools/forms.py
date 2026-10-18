"""Star Force player-form models: discovery and Nitro container surgery.

Every Star Force player model found so far (SF1 pl000-pl003, SF2 pl000-pl00a,
SF3 pl001-pl010)
shares one 19-bone rig, so Leo's battle animations can drive a model taken from
another form or game. SF1 stores each model as a BMD0 with its own TEX0; SF2
keeps textures in the following BTX0 entries. Nothing here embeds game data;
everything is read from ROMs the user supplies.
"""
import struct

from leo_assets import blocks
from leo_models import names, read
from nds import Rom
from sfarchive import Archive

# Known forms: (game, model name). Game "sf1" means the Leo ROM itself.
FORMS = {
    'mega-man': ('sf1', 'pl000'),
    'pegasus': ('sf1', 'pl001'),   # Pegasus Magic (ice)
    'dragon': ('sf1', 'pl002'),    # Dragon Sky (wood)
    'leo': ('sf1', 'pl003'),       # Leo Kingdom (fire)
    'zerker': ('sf2', 'pl001'),    # Tribe On: Zerker
    'ninja': ('sf2', 'pl002'),     # Tribe On: Ninja
    'saurian': ('sf2', 'pl003'),   # Tribe On: Saurian
    'black-ace': ('sf3', 'pl010'),   # Black Ace (Star Force 3)
    'red-joker': ('sf3', 'pl00f00'), # Red Joker (Star Force 3)
}

# The unified transformation system (mirrors app FormSystem.java): every form
# rides on SF1's real transform routine. (host form, charge type, element);
# None keeps the host's own. Host 1 Pegasus, 2 Leo, 3 Dragon. Charge types:
# 1 Mega Buster, 3 Ice Slash, 4 ground-wave slash, 5 missiles, 6 rings,
# 7 orb blast, 8 Woody Shot, 9 Flame Burner. Elements: 0 none, 1 fire, 2 aqua,
# 3 elec, 4 wood.
SYSTEM = {
    'pegasus': (1, None, None),
    'leo': (2, None, None),
    'dragon': (3, None, None),
    'zerker': (2, 4, 3),
    'ninja': (2, 8, 4),
    'saurian': (2, 5, 1),
    'black-ace': (2, 6, 0),
    'red-joker': (2, 7, 0),
}


# Zerker x Ninja: start flag bit for each Tribe On host, and the host model whose
# entries a borrowed look replaces. (look source game, look model, bit, host model)
ZN_SYSTEM = {
    'zerker': (None, None, 13, 'pl001'),
    'ninja': (None, None, 14, 'pl002'),
    'saurian': (None, None, 15, 'pl003'),
    'tribe-king': (None, None, 17, 'pl00a'),
    'pegasus': ('sf1', 'pl001', 13, 'pl001'),
    'leo': ('sf1', 'pl003', 15, 'pl003'),
    'dragon': ('sf1', 'pl002', 14, 'pl002'),
    'black-ace': ('sf3', 'pl010', 17, 'pl00a'),
    'red-joker': ('sf3', 'pl00f00', 17, 'pl00a'),
}


def zn_code(form):
    """Zerker x Ninja: set one Tribe On start bit in the battle setup block (overlay 875)."""
    bit = ZN_SYSTEM[form][2]
    return ('# start battles in Tribe On (guarded: battle overlay loaded)\n'
            '52143B3C D1052804\n'
            'D9000000 0219D2E4\n'
            'D4000002 FFFC1FFF\n'
            f'D4000001 {1 << bit:08X}\n'
            'D6000000 0219D2E4\n'
            'D2000000 00000000\n')


# Black Ace: starting Noise (1-11) for each Noise Change, or 12/13 for Finalize
# Black Ace / Red Joker (the battle-start form call is patched to mode 12).
BA_SYSTEM = {
    'black-ace': 12, 'red-joker': 13,
    'libra': 1, 'corvus': 2, 'cancer': 3, 'gemini': 4, 'ophiuca': 5, 'cygnus': 6,
    'taurus': 7, 'virgo': 8, 'crown': 9, 'wolf': 10, 'rogue': 11,
}


def ba_code(form):
    """Black Ace: start battles in a Noise Change or in Finalize (overlay 8)."""
    n = BA_SYSTEM[form]
    if n >= 12:
        return ('# start battles in Finalize (guarded: battle overlay loaded)\n'
                '52163D94 1C0CB538\n'
                '12163DA0 0000220C\n'
                '12163DA8 0000210C\n'
                f'221897C2 {n - 12:08X}\n'
                'D2000000 00000000\n')
    return ('# start battles in Noise Change (guarded: battle overlay loaded)\n'
            '52141F20 B089B5F0\n'
            f'22189876 {n:08X}\n'
            'D2000000 00000000\n')


def texture_entries(archive, index):
    """External texture entries (BTX0) that follow a model entry."""
    return external_textures(archive, index)


def system_code(form):
    host, charge, element = SYSTEM[form]
    lines = ['# start battles transformed (guarded: battle overlay loaded)',
             '5217600C 1C05B570', f'221AF1BF {host:08X}']
    if charge is not None or element is not None:
        lines += ['# while transformed (current form != 0)', 'A21AF022 00FF0000']
        if charge is not None:
            lines.append(f'221AF022 {charge:08X}')
        if element is not None:
            lines.append(f'221ADC95 {element:08X}')
    lines.append('D2000000 00000000')
    return '\n'.join(lines) + '\n'

# Target slots in Leo's models.bin (archive entry index, model name to present).
SLOTS = {
    'starforce': (157, 'pl003'),   # shown after Star Force Big Bang (Leo Kingdom)
    'base': (151, 'pl000'),        # regular Mega Man in every battle
}

RIG_BONES = 19


def models_archive(rom):
    by_path = {v: k for k, v in rom.names.items()}
    fid = by_path.get('datbin/com/models.bin')
    if fid is None:
        raise ValueError('ROM has no datbin/com/models.bin; not a Star Force game?')
    return fid, Archive(rom.file(fid))


def model_info(container):
    """(model name, [node names], has TEX0) for the first model in a BMD0."""
    kinds = dict(blocks(container))
    mdl = kinds.get('MDL0')
    if mdl is None:
        raise ValueError('no MDL0 block')
    name, off = names(mdl, 8, 4)[0]
    start = int.from_bytes(off, 'little')
    data = mdl[start:start + read(mdl, '<I', start)[0]]
    return name, [n for n, _ in names(data, 64, 4)], 'TEX0' in kinds


def player_models(archive):
    """Yield (entry index, name, node count, texture source) for pl* models."""
    for e in archive.entries:
        data = archive.decode(e.index)
        if data[:4] != b'BMD0':
            continue
        try:
            name, nodes, has_tex = model_info(data)
        except (ValueError, IndexError, KeyError):
            continue
        if not name.startswith('pl') or 'ef' in name or '_' in name:
            continue
        texture = 'embedded' if has_tex else external_textures(archive, e.index)
        yield e.index, name, len(nodes), texture


def external_textures(archive, index):
    """Indices of the BTX0 entries that follow a texture-less model."""
    found = []
    for later in archive.entries[index + 1:]:
        head = archive.decode(later.index)[:4]
        if head == b'BTX0':
            found.append(later.index)
        elif head == b'BMD0':
            break
    return found


def find_model(archive, model_name):
    for idx, name, nodes, tex in player_models(archive):
        if name == model_name:
            return idx, nodes, tex
    raise ValueError(f'model {model_name} not found')


def nitro_container(magic, blocks_, version=2):
    """Serialize a Nitro container (BMD0/BTX0) from (kind, bytes) blocks."""
    header = 16 + 4 * len(blocks_)
    header += (-header) % 4
    offsets, body, at = [], b'', header
    for _, data in blocks_:
        offsets.append(at)
        pad = (-len(data)) % 4
        body += data + b'\0' * pad
        at += len(data) + pad
    total = header + len(body)
    out = struct.pack('<4sHHIHH', magic, 0xFEFF, version, total, 16, len(blocks_))
    out += b''.join(struct.pack('<I', o) for o in offsets)
    out += b'\0' * (header - len(out))
    return out + body


def rename_model(mdl, new_name):
    """Rewrite the first model's 16-byte name in an MDL0 block's name list."""
    _, count, size = read(mdl, '<BBH', 8)
    at = 8 + 12 + 4 * count
    width, _ = read(mdl, '<HH', at)
    name_at = at + 4 + count * width
    encoded = new_name.encode('ascii')[:16].ljust(16, b'\0')
    return mdl[:name_at] + encoded + mdl[name_at + 16:]


def build_form_model(source_rom_bytes, model_name, target_name, texture_variant=0):
    """Return a self-contained BMD0 (MDL0 + TEX0) for `model_name`, renamed to `target_name`."""
    rom = Rom(source_rom_bytes)
    _, archive = models_archive(rom)
    idx, nodes, tex = find_model(archive, model_name)
    if nodes != RIG_BONES:
        raise ValueError(f'{model_name} has {nodes} bones; Leo animations need {RIG_BONES}')
    data = archive.decode(idx)
    kinds = dict(blocks(data))
    mdl = rename_model(kinds['MDL0'], target_name)
    if tex == 'embedded':
        tex0 = kinds['TEX0']
    else:
        if not tex:
            raise ValueError(f'{model_name} has no texture archive')
        if not 0 <= texture_variant < len(tex):
            raise ValueError(f'{model_name} has {len(tex)} texture sets; variant {texture_variant} does not exist')
        tex0 = dict(blocks(archive.decode(tex[texture_variant])))['TEX0']
    version = struct.unpack_from('<H', data, 6)[0]
    return nitro_container(b'BMD0', [('MDL0', mdl), ('TEX0', tex0)], version)
