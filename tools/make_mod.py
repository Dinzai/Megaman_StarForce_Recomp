#!/usr/bin/env python3
"""Build Star Force Recomp game mods (schema 2) from your own Leo ROM.

  list-files    ROM                                  NitroFS paths and FAT ids
  unpack        ROM PATH --out DIR                   decode a Leo archive into DIR/NNN.bin
  repack        ROM PATH --entries DIR --out FILE    rebuild the archive with changed entries
  package       MODDIR --out MOD.zip                 validate mod.json and zip the mod
  example-recolor ROM --out MOD.zip                  demo mod: recolours the CAPCOM boot logo
  list-models   ROM                                  player-form models in any Star Force ROM
  transform     LEO --form NAME [--sf2 ROM] [--sf3 ROM --source-model plNNN]
                [--slot starforce|base] [--texture-variant N] --out MOD.zip
                                                     form-swap mod (see docs/TRANSFORMATIONS.md)

Only entries whose bytes differ from the ROM are replaced; they are stored
uncompressed (the game loader accepts both forms). Packages contain only what
you put in them; never distribute mods that embed large unmodified game files.
"""
import argparse
import hashlib
import json
import os
import re
import sys
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import nds  # noqa: E402
from sfarchive import Archive, replace_entries  # noqa: E402

ID_RE = re.compile(r'^[a-z0-9][a-z0-9._-]{0,63}$')
ROM_PATH_RE = re.compile(r'^(#[0-9]{1,4}|arm9|arm7|[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+){0,7})$')
ZIP_PATH_RE = re.compile(r'^[A-Za-z0-9_.-]+(/[A-Za-z0-9_.-]+){0,7}$')
CODE_RE = re.compile(r'^[0-9A-Fa-f]{8}$')


def read_rom_file(rom_bytes, path):
    rom = nds.Rom(rom_bytes)
    if path.startswith('#'):
        fid = int(path[1:])
        if not 0 <= fid < len(rom.files):
            raise SystemExit(f'no such ROM file: {path}')
        return rom.file(fid)
    by_path = {v: k for k, v in rom.names.items()}
    fid = by_path.get(path.lstrip('/'))
    if fid is None:
        raise SystemExit(f'no such ROM file: {path}')
    return rom.file(fid)


def validate_manifest(mod_dir):
    manifest_path = mod_dir / 'mod.json'
    m = json.loads(manifest_path.read_text(encoding='utf-8'))
    errors = []
    if m.get('schema') != 2: errors.append('schema must be 2')
    if m.get('game') != 'leo': errors.append('game must be "leo"')
    if not ID_RE.match(str(m.get('id', ''))): errors.append('id must match [a-z0-9][a-z0-9._-]{0,63}')
    name = str(m.get('name', '')).strip()
    if not 1 <= len(name) <= 64: errors.append('name must be 1-64 characters')
    files = m.get('files', {}) or {}
    for rom_path, local in files.items():
        if not ROM_PATH_RE.match(rom_path) or '..' in rom_path: errors.append(f'bad ROM path {rom_path}')
        if not ZIP_PATH_RE.match(local) or '..' in local or local == 'mod.json': errors.append(f'bad package path {local}')
        elif not (mod_dir / local).is_file(): errors.append(f'missing {local}')
    entries = m.get('entries', {}) or {}
    for rom_path, table in entries.items():
        if not ROM_PATH_RE.match(rom_path) or '..' in rom_path or rom_path in ('arm9', 'arm7'): errors.append(f'bad archive path {rom_path}')
        for idx, local in table.items():
            if not re.match(r'^[0-9]{1,5}$', idx): errors.append(f'bad entry index {idx}')
            if not ZIP_PATH_RE.match(local) or '..' in local: errors.append(f'bad package path {local}')
            elif not (mod_dir / local).is_file(): errors.append(f'missing {local}')
    cheats = m.get('cheats', []) or []
    for i, c in enumerate(cheats):
        words = []
        for line in str(c.get('code', '')).splitlines():
            line = re.split('[#;]', line, 1)[0]
            words += line.split()
        if not words or len(words) % 2 or not all(CODE_RE.match(w) for w in words):
            errors.append(f'cheat {i}: codes must be pairs of 8-digit hex words')
    if not files and not cheats and not entries: errors.append('mod has no files, entries or cheats')
    if errors:
        raise SystemExit('mod.json invalid:\n  ' + '\n  '.join(errors))
    return m


def package(mod_dir, out):
    mod_dir = Path(mod_dir)
    m = validate_manifest(mod_dir)
    out = Path(out)
    if out.exists():
        raise SystemExit(f'{out} exists; refusing to overwrite')
    with zipfile.ZipFile(out, 'w', zipfile.ZIP_DEFLATED) as z:
        z.write(mod_dir / 'mod.json', 'mod.json')
        locals_ = set((m.get('files') or {}).values())
        for table in (m.get('entries') or {}).values(): locals_ |= set(table.values())
        for local in sorted(locals_):
            z.write(mod_dir / local, local)
        for extra in ('README.md', 'README.txt', 'preview.png'):
            if (mod_dir / extra).is_file():
                z.write(mod_dir / extra, extra)
    print(f'wrote {out} ({out.stat().st_size} bytes)')


def cmd_list(a):
    rom = nds.Rom(Path(a.rom).read_bytes())
    for i, f in enumerate(rom.files):
        print(f"#{i:<4} {f['size']:>9}  {rom.names.get(i, '(overlay/unnamed)')}")


def cmd_unpack(a):
    rom = Path(a.rom).read_bytes()
    data = read_rom_file(rom, a.path)
    archive = Archive(data)
    out = Path(a.out)
    out.mkdir(parents=True, exist_ok=False)
    for e in archive.entries:
        (out / f'{e.index:03d}.bin').write_bytes(archive.decode(e.index))
    print(f'unpacked {len(archive.entries)} entries to {out}')


def cmd_repack(a):
    rom = Path(a.rom).read_bytes()
    data = read_rom_file(rom, a.path)
    archive = Archive(data)
    changed = {}
    for p in sorted(Path(a.entries).glob('[0-9][0-9][0-9].bin')):
        idx = int(p.stem)
        new = p.read_bytes()
        if idx < len(archive.entries) and new != archive.decode(idx):
            changed[idx] = new
    if not changed:
        raise SystemExit('no entries differ from the ROM; nothing to repack')
    out = Path(a.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_bytes(replace_entries(data, changed))
    print(f'replaced entries {sorted(changed)} -> {out}')


def rotate_palette(pal):
    out = bytearray(pal)
    for i in range(0, len(out) - 1, 2):
        c = int.from_bytes(out[i:i + 2], 'little')
        r, g, b = c & 31, (c >> 5) & 31, (c >> 10) & 31
        out[i:i + 2] = (b | (r << 5) | (g << 10) | (c & 0x8000)).to_bytes(2, 'little')
    return bytes(out)


def cmd_example(a):
    rom = Path(a.rom).read_bytes()
    if hashlib.sha256(rom).hexdigest() != nds.LEO_SHA256:
        raise SystemExit('expected the profiled US Leo ROM')
    path = 'datbin/com/capcomlogo.bin'
    data = read_rom_file(rom, path)
    archive = Archive(data)
    # 32- and 512-byte entries in this archive are BGR555 palettes.
    changed = {e.index: rotate_palette(archive.decode(e.index)) for e in archive.entries if e.decoded_size in (32, 512)}
    work = Path(a.out).with_suffix('.d')
    (work / 'files').mkdir(parents=True, exist_ok=False)
    (work / 'files' / 'capcomlogo.bin').write_bytes(replace_entries(data, changed))
    (work / 'mod.json').write_text(json.dumps({
        'schema': 2, 'id': 'example.recolor-capcom-logo', 'name': 'Recolored boot logo',
        'game': 'leo', 'version': '1.0.0', 'author': 'Star Force Recomp',
        'description': 'Demo asset mod: rotates the colour channels of the boot logo palettes.',
        'files': {path: 'files/capcomlogo.bin'}}, indent=2), encoding='utf-8')
    package(work, a.out)


def cmd_list_models(a):
    import forms
    rom = nds.Rom(Path(a.rom).read_bytes())
    fid, archive = forms.models_archive(rom)
    print(f'{rom.title} ({rom.code}) models.bin = #{fid}')
    for idx, name, bones, tex in forms.player_models(archive):
        ok = 'compatible' if bones == forms.RIG_BONES else 'different rig'
        print(f'  entry {idx:4}  {name:8} {bones:3} bones  textures: {tex}  [{ok}]')


def cmd_transform(a):
    import forms
    leo = Path(a.leo).read_bytes()
    if hashlib.sha256(leo).hexdigest() != nds.LEO_SHA256:
        raise SystemExit('the first argument must be the profiled US Leo ROM')
    game, model = forms.FORMS[a.form]
    model = a.source_model or model
    source = {'sf1': a.leo, 'sf2': a.sf2, 'sf3': a.sf3}[game]
    if source is None:
        raise SystemExit(f'--{game} ROM is required for the {a.form} form')
    if model is None:
        raise SystemExit(f'{a.form} needs --source-model (run list-models on your {game.upper()} ROM)')
    slot_entry, slot_name = forms.SLOTS[a.slot]
    try:
        bmd = forms.build_form_model(Path(source).read_bytes(), model, slot_name, a.texture_variant)
    except ValueError as exc:
        raise SystemExit(f'cannot build {a.form}: {exc}')
    work = Path(a.out).with_suffix('.d')
    (work / 'files').mkdir(parents=True, exist_ok=False)
    local = f'files/{a.form}.bmd'
    (work / local).write_bytes(bmd)
    pretty = a.form.replace('-', ' ').title()
    where = 'after Star Force Big Bang' if a.slot == 'starforce' else 'in every battle'
    (work / 'mod.json').write_text(json.dumps({
        'schema': 2, 'id': f'transform.{a.form}.{a.slot}', 'name': f'{pretty} form ({a.slot})',
        'game': 'leo', 'version': '0.1.0', 'author': 'make_mod.py transform',
        'description': f'Mega Man appears as {pretty} {where}. Visual only: moves and element stay Leo\'s.',
        'entries': {'datbin/com/models.bin': {str(slot_entry): local}}}, indent=2), encoding='utf-8')
    package(work, a.out)


def main():
    p = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = p.add_subparsers(dest='cmd', required=True)
    s = sub.add_parser('list-files'); s.add_argument('rom'); s.set_defaults(fn=cmd_list)
    s = sub.add_parser('unpack'); s.add_argument('rom'); s.add_argument('path'); s.add_argument('--out', required=True); s.set_defaults(fn=cmd_unpack)
    s = sub.add_parser('repack'); s.add_argument('rom'); s.add_argument('path'); s.add_argument('--entries', required=True)
    s.add_argument('--out', required=True); s.set_defaults(fn=cmd_repack)
    s = sub.add_parser('package'); s.add_argument('moddir'); s.add_argument('--out', required=True)
    s.set_defaults(fn=lambda a: package(a.moddir, a.out))
    s = sub.add_parser('list-models'); s.add_argument('rom'); s.set_defaults(fn=cmd_list_models)
    s = sub.add_parser('transform'); s.add_argument('leo')
    s.add_argument('--form', required=True, choices=sorted(__import__('forms').FORMS))
    s.add_argument('--sf2'); s.add_argument('--sf3'); s.add_argument('--source-model')
    s.add_argument('--slot', default='starforce', choices=['starforce', 'base'])
    s.add_argument('--texture-variant', type=int, default=0); s.add_argument('--out', required=True)
    s.set_defaults(fn=cmd_transform)
    s = sub.add_parser('example-recolor'); s.add_argument('rom'); s.add_argument('--out', required=True); s.set_defaults(fn=cmd_example)
    a = p.parse_args()
    a.fn(a)


if __name__ == '__main__':
    main()
