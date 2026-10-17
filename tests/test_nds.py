import struct
import sys
import tempfile
import unittest
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'tools'))
from nds import Rom, patch_files

def fixture():
    d=bytearray(1024)
    d[:16]=b'TEST_ROM\0\0\0\0TEST'
    struct.pack_into('<4I',d,0x20,512,0x2000000,0x2000000,16)
    struct.pack_into('<4I',d,0x30,528,0x2380000,0x2380000,16)
    struct.pack_into('<2I',d,0x40,544,14)
    struct.pack_into('<2I',d,0x48,568,8)
    struct.pack_into('<IHH',d,544,8,0,1)
    d[552:558]=b'\x04test\0'
    struct.pack_into('<II',d,568,600,604)
    d[600:604]=b'data'
    return d

class RomTests(unittest.TestCase):
    def test_manifest(self):
        r=Rom(fixture())
        self.assertEqual(r.names,{0:'test'})
        self.assertEqual(r.file(0),b'data')
        self.assertFalse(r.report()['supported_leo'])
    def test_truncated_header(self):
        with self.assertRaises(ValueError):Rom(b'')
    def test_bad_range(self):
        d=fixture();struct.pack_into('<I',d,0x20,99999)
        with self.assertRaises(ValueError):Rom(d)
    def test_reversed_fat(self):
        d=fixture();struct.pack_into('<II',d,568,604,600)
        with self.assertRaises(ValueError):Rom(d)
    def test_path_escape(self):
        d=fixture();d[553:557]=b'../x'
        with self.assertRaises(ValueError):Rom(d)
    def test_cycle(self):
        d=fixture();d[552:558]=b'\x81x\x00\xf0\0\0'
        with self.assertRaises(ValueError):Rom(d)
    def test_invalid_file_id(self):
        d=fixture();struct.pack_into('<H',d,548,1)
        with self.assertRaises(ValueError):Rom(d)
    def test_repack(self):
        original=fixture();r=Rom(original)
        self.assertEqual(patch_files(r,{}),original)
        patched=patch_files(r,{0:b'MODS'})
        self.assertEqual(Rom(patched).file(0),b'MODS')
        self.assertEqual(patched[:600],original[:600])
        self.assertEqual(patched[604:],original[604:])
        with self.assertRaises(ValueError):patch_files(r,{0:b'oversize'})
    def test_extract(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)/'out';Rom(fixture()).extract(root)
            self.assertEqual((root/'files/test').read_bytes(),b'data')
            with self.assertRaises(FileExistsError):Rom(fixture()).extract(root)

if __name__=='__main__':unittest.main()
