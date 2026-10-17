"""Bounds-checked Nintendo DS container tools. No CPU emulation or game logic."""
import argparse
import hashlib
import json
import struct
from pathlib import Path

LEO_SHA256 = 'defbc6f639fb0bcd11a229d1a0c7c9f595447034ae25d3ba4a97e1268156c5d6'

class Rom:
    def __init__(self, data):
        self.data = data
        if len(data) < 512:
            raise ValueError('Truncated DS header')
        self.title = data[:12].rstrip(b'\0').decode('ascii')
        self.code = data[12:16].decode('ascii')
        self.sha256 = hashlib.sha256(data).hexdigest()
        self.sections = {}
        for name, off in [('arm9', 0x20), ('arm7', 0x30)]:
            start, entry, ram, size = self.words(off, 4)
            self.slice(start, size)
            self.sections[name] = dict(offset=start, entry=entry, ram=ram, size=size)
        for name, off in [('fnt', 0x40), ('fat', 0x48), ('arm9_overlays', 0x50), ('arm7_overlays', 0x58)]:
            start, size = self.words(off, 2)
            self.slice(start, size)
            self.sections[name] = dict(offset=start, size=size)
        fat = self.section('fat')
        if len(fat) % 8:
            raise ValueError('Invalid FAT length')
        self.files = []
        for start, end in struct.iter_unpack('<II', fat):
            self.slice(start, end-start)
            self.files.append(dict(offset=start, size=end-start))
        self.names = self.read_names()
        self.overlays = {}
        for cpu in ('arm9', 'arm7'):
            table = self.section(cpu+'_overlays')
            if len(table) % 32:
                raise ValueError('Invalid overlay table size')
            rows = []
            for row in struct.iter_unpack('<8I', table):
                oid, ram, size, bss, init_start, init_end, fid, flags = row
                if fid >= len(self.files):
                    raise ValueError('Overlay references invalid file')
                rows.append(dict(id=oid, ram=ram, size=size, bss=bss,
                                 init_start=init_start, init_end=init_end, file_id=fid,
                                 compressed_size=flags & 0xffffff, flags=flags >> 24))
            self.overlays[cpu] = rows

    def slice(self, start, size):
        if start < 0 or size < 0 or start > len(self.data)-size:
            raise ValueError('ROM section outside file')
        return self.data[start:start+size]

    def words(self, off, count):
        return struct.unpack('<'+'I'*count, self.slice(off, count*4))

    def section(self, name):
        s = self.sections[name]
        return self.slice(s['offset'], s['size'])

    def file(self, fid):
        s = self.files[fid]
        return self.slice(s['offset'], s['size'])

    def read_names(self):
        fnt = self.section('fnt')
        if len(fnt) < 8:
            raise ValueError('Missing root FNT directory')
        count = struct.unpack_from('<H', fnt, 6)[0]
        if not 1 <= count <= 4096 or count*8 > len(fnt):
            raise ValueError('Invalid directory count')
        names, visited = {}, set()
        def walk(index, parent, depth=0):
            if index >= count or index in visited or depth > 64:
                raise ValueError('Invalid/cyclic FNT directory')
            visited.add(index)
            pos, fid, _ = struct.unpack_from('<IHH', fnt, index*8)
            while True:
                if pos >= len(fnt):
                    raise ValueError('Truncated name table')
                tag = fnt[pos]; pos += 1
                if tag == 0: break
                length = tag & 127
                if not length or pos+length > len(fnt):
                    raise ValueError('Invalid filename')
                name = fnt[pos:pos+length].decode('ascii'); pos += length
                if name in ('.', '..') or any(c in name for c in '/\\\0'):
                    raise ValueError('Unsafe filename')
                path = parent+name
                if tag & 128:
                    if pos+2 > len(fnt): raise ValueError('Truncated directory ID')
                    did = struct.unpack_from('<H', fnt, pos)[0]; pos += 2
                    if did < 0xf000: raise ValueError('Invalid directory ID')
                    walk(did-0xf000, path+'/', depth+1)
                else:
                    if fid >= len(self.files) or fid in names or path in names.values():
                        raise ValueError('Duplicate/invalid file ID or path')
                    names[fid] = path; fid += 1
        walk(0, '')
        return names

    def report(self):
        return dict(title=self.title, code=self.code, sha256=self.sha256,
                    size=len(self.data), supported_leo=self.sha256==LEO_SHA256,
                    sections=self.sections, overlays=self.overlays,
                    files=[dict(id=i, path=self.names.get(i), sha256=hashlib.sha256(self.file(i)).hexdigest(),
                                **f) for i, f in enumerate(self.files)])

    def extract(self, destination):
        root = Path(destination)
        # A fresh output directory prevents overwriting an earlier research export.
        root.mkdir(parents=True, exist_ok=False)
        for name in self.sections:
            (root/(name+'.bin')).write_bytes(self.section(name))
        for i in range(len(self.files)):
            path = root/'files'/self.names.get(i, f'__unnamed/{i:04d}.bin')
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(self.file(i))
        (root/'manifest.json').write_text(json.dumps(self.report(), indent=2)+'\n')


def patch_files(rom, replacements):
    """Same-size FAT replacements preserve layout. This is ROM repacking, not recompilation."""
    output = bytearray(rom.data)
    spans = []
    for fid, payload in replacements.items():
        if not 0 <= fid < len(rom.files): raise ValueError('Unknown file ID')
        item = rom.files[fid]
        start, size = item['offset'], item['size']
        if len(payload) != size: raise ValueError('Replacement must have identical size')
        if any(start < b and a < start+size for a,b in spans):
            raise ValueError('Overlapping replacements')
        spans.append((start,start+size))
        output[start:start+size] = payload
    return bytes(output)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('command', choices=['inspect','extract','repack'])
    p.add_argument('rom', type=Path)
    p.add_argument('--out', type=Path)
    p.add_argument('--replace', action='append', default=[], metavar='FILE_ID=PATH')
    a = p.parse_args()
    rom = Rom(a.rom.read_bytes())
    if a.command == 'inspect':
        report = json.dumps(rom.report(), indent=2)+'\n'
        if a.out: a.out.write_text(report)
        else: print(report, end='')
    else:
        if rom.sha256 != LEO_SHA256: p.error('Only the profiled Leo ROM is supported')
        if not a.out: p.error('--out is required')
        if a.out.exists(): p.error('Output must not already exist')
        if a.command == 'extract': rom.extract(a.out)
        else:
            replacements = {}
            for spec in a.replace:
                fid, path = spec.split('=',1)
                if int(fid) in replacements: p.error('Duplicate replacement ID')
                replacements[int(fid)] = Path(path).read_bytes()
            a.out.write_bytes(patch_files(rom, replacements))

if __name__ == '__main__':
    main()
