#!/usr/bin/env python3
"""Decompilation groundwork: split a Star Force ROM's code into modules and functions.

  decomp.py split ROM --out DIR          ARM9 + every overlay -> DIR/<module>/{module.s,functions.csv}
  decomp.py report DIR                   coverage summary of a split
  decomp.py find DIR ADDRESS             which module/function holds an address

Function discovery (no execution):
  * seeds: the ARM9 entry point, overlay static initialisers, every name in
    decomp/symbols/<game>.txt, and every direct BL/BLX target found by scanning;
  * Thumb push {..., lr} and ARM stmfd sp!, {..., lr} prologues that follow a
    return are added as likely starts;
  * a function runs to the next start in the same module.
Overlays that share an address range are analysed separately; calls from an
overlay into ARM9 resolve against the ARM9 function list.

The output is disassembly of the game's code: it is generated on your machine
from your ROM and must not be committed or shared (decomp/build is git-ignored).
"""
import argparse
import bisect
import csv
import struct
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

GAMES = {'A6BE': 'leo', 'YRVE': 'zerker-ninja', 'CRBE': 'black-ace'}
SYMBOLS = Path(__file__).resolve().parents[1] / 'decomp' / 'symbols'


def load_symbols(game):
    """{address: (name, mode, module or None, comment)} from decomp/symbols/<game>.txt.

    Line format: ``name = 0xADDRESS [thumb|arm|data] [@module]  # comment``.
    """
    out = {}
    f = SYMBOLS / f'{game}.txt'
    if not f.is_file():
        return out
    for line in f.read_text(encoding='utf-8').splitlines():
        body, _, comment = line.partition('#')
        if '=' not in body:
            continue
        name, rest = (x.strip() for x in body.split('=', 1))
        parts = rest.split()
        addr = int(parts[0], 16)
        mode = next((p for p in parts[1:] if p in ('thumb', 'arm', 'data')), None)
        module = next((p[1:] for p in parts[1:] if p.startswith('@')), None)
        out[addr] = (name, mode, module, comment.strip())
    return out


class Module:
    def __init__(self, name, address, data, bss=0, init=()):
        self.name, self.address, self.data, self.bss, self.init = name, address, data, bss, list(init)

    def contains(self, a):
        return self.address <= a < self.address + len(self.data)

    def u16(self, a):
        return struct.unpack_from('<H', self.data, a - self.address)[0]

    def u32(self, a):
        return struct.unpack_from('<I', self.data, a - self.address)[0]


def load_modules(rom_path):
    import ndspy.rom
    import ndspy.codeCompression
    rom = ndspy.rom.NintendoDSRom.fromFile(str(rom_path))
    game = GAMES.get(bytes(rom.idCode).decode('ascii'))
    if game is None:
        raise SystemExit('unsupported ROM (needs US Leo, Zerker x Ninja or Black Ace)')
    arm9 = ndspy.codeCompression.decompress(rom.arm9)
    mods = [Module('arm9', rom.arm9RamAddress, bytes(arm9))]
    mods[0].entry = rom.arm9EntryAddress
    for oid, ov in sorted(rom.loadArm9Overlays().items()):
        data = ov.data
        init = []
        if ov.staticInitStart and ov.staticInitEnd > ov.staticInitStart:
            s, e = ov.staticInitStart - ov.ramAddress, ov.staticInitEnd - ov.ramAddress
            if 0 <= s < e <= len(data):
                init = [v for (v,) in struct.iter_unpack('<I', data[s:e]) if v]
        mods.append(Module(f'ov{oid:03d}', ov.ramAddress, bytes(data), ov.bssSize, init))
    return game, mods


def bl_targets(m):
    """Direct BL/BLX targets in a module, as {target: thumb?}."""
    out = {}
    d, base = m.data, m.address
    # Thumb BL / BLX pairs (halfword aligned)
    for i in range(0, len(d) - 3, 2):
        h1, h2 = struct.unpack_from('<HH', d, i)
        if (h1 & 0xF800) != 0xF000 or (h2 & 0xE800) != 0xE800:
            continue
        off = ((h1 & 0x7FF) << 12) | ((h2 & 0x7FF) << 1)
        if off & 0x400000:
            off -= 0x800000
        t = base + i + 4 + off
        if (h2 & 0xF800) == 0xF800:
            out.setdefault(t, True)            # BL: stays Thumb
        else:
            out.setdefault(t & ~3, False)      # BLX: switches to ARM
    # ARM BL / BLX (word aligned)
    for i in range(0, len(d) - 3, 4):
        w = struct.unpack_from('<I', d, i)[0]
        if (w & 0x0F000000) == 0x0B000000 and (w >> 28) != 0xF:   # BL (any condition)
            off = (w & 0xFFFFFF) << 2
            if off & 0x2000000:
                off -= 0x4000000
            out.setdefault(base + i + 8 + off, False)
        elif (w & 0xFE000000) == 0xFA000000:                       # BLX imm
            off = ((w & 0xFFFFFF) << 2) | (((w >> 24) & 1) << 1)
            if off & 0x2000000:
                off -= 0x4000000
            out.setdefault(base + i + 8 + off, True)
    return out


def prologues(m):
    """Likely function starts: push {..lr} / stmfd sp!,{..lr} right after a return or padding."""
    out = {}
    d, base = m.data, m.address
    for i in range(2, len(d) - 1, 2):
        h = struct.unpack_from('<H', d, i)[0]
        if (h & 0xFF00) == 0xB500:                  # push {..., lr}
            prev = struct.unpack_from('<H', d, i - 2)[0]
            if prev in (0x4770, 0x46C0, 0x0000) or (prev & 0xFF00) == 0xBD00:   # bx lr, nop, pad, pop {..pc}
                out.setdefault(base + i, True)
    for i in range(4, len(d) - 3, 4):
        w = struct.unpack_from('<I', d, i)[0]
        if (w & 0xFFFF4000) == 0xE92D4000:          # stmfd sp!, {..., lr}
            prev = struct.unpack_from('<I', d, i - 4)[0]
            if prev in (0xE12FFF1E, 0x00000000) or (prev & 0xFFFF8000) == 0xE8BD8000:
                out.setdefault(base + i, False)
    return out


def split(rom_path, out_dir):
    from capstone import Cs, CS_ARCH_ARM, CS_MODE_ARM, CS_MODE_THUMB
    game, mods = load_modules(rom_path)
    syms = load_symbols(game)
    arm9 = mods[0]
    arm9_funcs = {}
    dis = {True: Cs(CS_ARCH_ARM, CS_MODE_THUMB), False: Cs(CS_ARCH_ARM, CS_MODE_ARM)}
    out_dir = Path(out_dir)
    summary = []
    calls_into = {}                                   # target -> set(caller module)
    for m in mods:
        starts = {}
        if m.name == 'arm9':
            starts[m.entry & ~1] = False
        for t in m.init:
            starts[t & ~1] = bool(t & 1)
        for a, th in prologues(m).items():
            starts.setdefault(a, th)
        bls = bl_targets(m)
        for a, th in bls.items():
            if m.contains(a):
                starts[a] = th                        # call evidence beats prologue guesses
            else:
                calls_into.setdefault(a, set()).add(m.name)
        for a, (name, mode, module, _) in syms.items():
            if m.contains(a) and mode in ('thumb', 'arm') and module in (None, m.name):
                starts[a] = mode == 'thumb'
        order = sorted(starts)
        funcs = []
        for k, a in enumerate(order):
            end = order[k + 1] if k + 1 < len(order) else m.address + len(m.data)
            funcs.append((a, end - a, starts[a]))
        if m.name == 'arm9':
            arm9_funcs = {a: th for a, _, th in funcs}
        mdir = out_dir / m.name
        mdir.mkdir(parents=True, exist_ok=True)

        def label(a):
            s = syms.get(a)
            if s and (s[2] in (None, m.name) or arm9.contains(a)):
                return s[0]
            return f'func_{a:08x}' if (a in starts or a in arm9_funcs) else f'loc_{a:08x}'

        with open(mdir / 'functions.csv', 'w', newline='') as fh:
            w = csv.writer(fh)
            w.writerow(['address', 'size', 'mode', 'name'])
            for a, size, th in funcs:
                w.writerow([f'0x{a:08x}', size, 'thumb' if th else 'arm', label(a)])
        with open(mdir / 'module.s', 'w') as fh:
            fh.write(f'@ {m.name}: 0x{m.address:08x}-0x{m.address + len(m.data):08x} (+bss 0x{m.bss:x})\n')
            fh.write('@ generated from your ROM by tools/decomp.py; do not commit or share\n\n')
            for a, size, th in funcs:
                s = syms.get(a)
                fh.write(f'\n\t.{"thumb" if th else "arm"}\n{label(a)}:' + (f'  @ {s[3]}' if s and s[3] else '') + '\n')
                off = a - m.address
                body = m.data[off:off + size]
                pos = 0
                for ins in dis[th].disasm(body, a):
                    pos = ins.address + ins.size - a
                    txt = f'{ins.mnemonic} {ins.op_str}'.strip()
                    if ins.mnemonic in ('bl', 'blx', 'b') and ins.op_str.startswith('#'):
                        try:
                            t = int(ins.op_str[1:], 16)
                            txt = f'{ins.mnemonic} {label(t)}'
                        except ValueError:
                            pass
                    fh.write(f'\t{txt:<40} @ {ins.address:08x}\n')
                if pos < size:                         # data or undecodable bytes
                    for k in range(pos, size, 4):
                        chunk = body[k:k + 4]
                        fh.write(f'\t.byte {", ".join(f"0x{b:02x}" for b in chunk):<38} @ {a + k:08x}\n')
        summary.append((m.name, m.address, len(m.data), len(funcs)))
    with open(out_dir / 'modules.csv', 'w', newline='') as fh:
        w = csv.writer(fh)
        w.writerow(['module', 'address', 'size', 'functions'])
        for row in summary:
            w.writerow([row[0], f'0x{row[1]:08x}', row[2], row[3]])
    total = sum(r[3] for r in summary)
    print(f'{game}: {len(summary)} modules, {total} functions -> {out_dir}')


def report(out_dir):
    rows = list(csv.DictReader(open(Path(out_dir) / 'modules.csv')))
    named = total = 0
    for r in rows:
        for f in csv.DictReader(open(Path(out_dir) / r['module'] / 'functions.csv')):
            total += 1
            named += not f['name'].startswith(('func_', 'loc_'))
    size = sum(int(r['size']) for r in rows)
    print(f'{len(rows)} modules, {size} bytes of code, {total} functions, {named} named ({100 * named / max(total, 1):.2f}%)')


def find(out_dir, address):
    a = int(address, 16)
    for r in csv.DictReader(open(Path(out_dir) / 'modules.csv')):
        lo = int(r['address'], 16)
        if not lo <= a < lo + int(r['size']):
            continue
        fs = list(csv.DictReader(open(Path(out_dir) / r['module'] / 'functions.csv')))
        addrs = [int(f['address'], 16) for f in fs]
        k = bisect.bisect_right(addrs, a) - 1
        if k >= 0:
            f = fs[k]
            print(f"{r['module']}: {f['name']} ({f['address']}, {f['size']} bytes, {f['mode']}) +0x{a - addrs[k]:x}")


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('split'); s.add_argument('rom'); s.add_argument('--out', required=True)
    s = sub.add_parser('report'); s.add_argument('dir')
    s = sub.add_parser('find'); s.add_argument('dir'); s.add_argument('address')
    a = p.parse_args()
    if a.cmd == 'split':
        split(a.rom, a.out)
    elif a.cmd == 'report':
        report(a.dir)
    else:
        find(a.dir, a.address)


if __name__ == '__main__':
    main()
