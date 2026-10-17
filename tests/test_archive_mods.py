import json
import struct
import sys
import tempfile
import unittest
import zipfile
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from sfarchive import Archive, build, replace_entries  # noqa: E402
import make_mod  # noqa: E402


def lz10_literal(raw):
    """Minimal valid LZ10 stream made only of literal blocks."""
    out = bytearray([0x10]) + len(raw).to_bytes(3, 'little')
    for i in range(0, len(raw), 8):
        out.append(0)
        out += raw[i:i + 8]
    return bytes(out)


class ArchiveBuildTest(unittest.TestCase):
    def sample(self):
        return build([(b'ABCD', 4, False), (lz10_literal(b'hello world!'), 12, True), (b'xy', 2, False)])

    def test_roundtrip_and_layout(self):
        data = self.sample()
        a = Archive(data)
        self.assertEqual([a.decode(i) for i in range(3)], [b'ABCD', b'hello world!', b'xy'])
        self.assertEqual(struct.unpack_from('<II', data, 24), (len(data), 0xffff))
        for e in a.entries:
            self.assertEqual(e.offset % 4, 0)
        self.assertEqual(build([(e.payload, e.decoded_size, e.compressed) for e in a.entries]), data)

    def test_replace_keeps_untouched_entries(self):
        data = self.sample()
        new = Archive(replace_entries(data, {1: b'changed text, now longer'}))
        self.assertEqual(new.decode(0), b'ABCD')
        self.assertEqual(new.decode(1), b'changed text, now longer')
        self.assertFalse(new.entries[1].compressed)
        self.assertEqual(new.decode(2), b'xy')
        with self.assertRaises(ValueError):
            replace_entries(data, {9: b''})


class ModPackageTest(unittest.TestCase):
    def write(self, root, manifest, files=()):
        (root / 'mod.json').write_text(json.dumps(manifest))
        for name in files:
            p = root / name
            p.parent.mkdir(parents=True, exist_ok=True)
            p.write_bytes(b'data')

    def test_package_valid_mod(self):
        with tempfile.TemporaryDirectory() as t:
            root = Path(t) / 'm'; root.mkdir()
            self.write(root, {'schema': 2, 'id': 'me.test', 'name': 'Test', 'game': 'leo',
                              'files': {'datbin/eng/mess.bin': 'files/mess.bin'},
                              'cheats': [{'name': 'x', 'code': '023FF000 00000001 # note'}]},
                       ['files/mess.bin', 'files/unused.bin'])
            out = Path(t) / 'm.zip'
            make_mod.package(root, out)
            self.assertEqual(sorted(zipfile.ZipFile(out).namelist()), ['files/mess.bin', 'mod.json'])

    def test_rejects_bad_mods(self):
        bad = [
            {'schema': 2, 'id': '../x', 'name': 'T', 'game': 'leo', 'files': {'a.bin': 'files/a.bin'}},
            {'schema': 2, 'id': 'x', 'name': 'T', 'game': 'leo', 'files': {'../a.bin': 'files/a.bin'}},
            {'schema': 2, 'id': 'x', 'name': 'T', 'game': 'leo', 'files': {'a.bin': 'files/missing.bin'}},
            {'schema': 2, 'id': 'x', 'name': 'T', 'game': 'leo', 'cheats': [{'code': '1234 5678'}]},
            {'schema': 2, 'id': 'x', 'name': 'T', 'game': 'leo'},
        ]
        for manifest in bad:
            with tempfile.TemporaryDirectory() as t:
                root = Path(t)
                self.write(root, manifest, ['files/a.bin'])
                with self.assertRaises(SystemExit, msg=manifest):
                    make_mod.validate_manifest(root)


if __name__ == '__main__':
    unittest.main()


class MultiGameArchives(unittest.TestCase):
    def test_sf3_marker_and_lz11(self):
        from sfarchive import lz11_decode
        # Literal A followed by 3/17/273-byte overlapping copies, each encoding form.
        for count,ref in [(3,b'\x20\0'),(17,b'\0\0\0'),(273,b'\x10\0\0\0')]:
            raw=b'\x11'+(count+1).to_bytes(3,'little')+b'\x40A'+ref
            self.assertEqual(lz11_decode(raw),b'A'*(count+1))
            arc=build([(raw,count+1,True),(b'x',1,False)],eof_marker=0)
            modified=Archive(replace_entries(arc,{1:b'longer'}))
            self.assertEqual(modified.eof_marker,0)
            self.assertEqual(modified.decode(0),b'A'*(count+1))
            self.assertEqual(modified.decode(1),b'longer')
        for bad in [b'\x11',b'\x11\x04\0\0\x80\x20\0',b'\x11\x04\0\0\x40A\x10']:
            with self.assertRaises(ValueError):lz11_decode(bad)

    def test_package_game_targets(self):
        for game in ['zerker-ninja','black-ace']:
            with tempfile.TemporaryDirectory() as t:
                root=Path(t)
                (root/'mod.json').write_text(json.dumps(dict(schema=2,id='test',name='Test',game=game,cheats=[dict(code='023FF000 00000001')])))
                make_mod.validate_manifest(root)
