import struct
import sys
import unittest
from pathlib import Path
sys.path.insert(0,str(Path(__file__).resolve().parents[1]/'tools'))
from sfarchive import Archive,lz10_decode
from leo_code import Image,LoadMap,explore
from leo_assets import blocks,textures
import ndspy.lz10
import ndspy.texture

class ArchiveTests(unittest.TestCase):
    def test_lz_literal_and_overlap(self):
        self.assertEqual(lz10_decode(b'\x10\x06\0\0\x10ABC\0\x02'),b'ABCABC')
        self.assertEqual(lz10_decode(b'\x10\x06\0\0\x40A\x20\0'),b'AAAAAA')
    def test_lz_reference_decoder_agreement(self):
        for raw in (b'x'*4096,bytes(range(256))*3,b'ARM9 THUMB '+b'ab'*511):
            encoded=ndspy.lz10.compress(raw)
            self.assertEqual(lz10_decode(encoded),raw)
    def test_lz_rejects_invalid_streams(self):
        for data in (b'',b'\x10\x06\0\0',b'\x10\x03\0\0\x80\0\0'):
            with self.assertRaises(ValueError):lz10_decode(data)
        with self.assertRaises(ValueError):lz10_decode(b'\x10\0\0\0',expected=10)
    def test_raw_archive(self):
        data=struct.pack('<4I',16,4,20,65535)+b'test'
        a=Archive(data);self.assertEqual(a.decode(0),b'test')
        self.assertEqual(len(a.entries),1)
    def test_compressed_archive(self):
        data=struct.pack('<4I',16,0x80000006,28,65535)+b'\x10\x06\0\0\x10ABC\0\x02\0\0'
        self.assertEqual(Archive(data).decode(0),b'ABCABC')
    def test_archive_rejects_bad_ranges(self):
        for data in (struct.pack('<4I',16,9,20,65535)+b'test',struct.pack('<4I',16,4,20,0)+b'test'):
            with self.assertRaises(ValueError):Archive(data)

class LoadMapTests(unittest.TestCase):
    def test_bss_and_overlay_lifetime(self):
        m=LoadMap();m.load(Image('a',0x2000000,b'ABCD',4))
        self.assertEqual(m.read(0x2000002,6),b'CD\0\0\0\0')
        with self.assertRaises(ValueError):m.load(Image('b',0x2000004,b'E'))
        m.unload('a');m.load(Image('b',0x2000004,b'E'))
        self.assertEqual(m.read(0x2000004,1),b'E')
        with self.assertRaises(ValueError):m.read(0x4000000,4)
    def test_arm_return_barrier(self):
        image=Image('code',0x2000000,struct.pack('<3I',0xe3a00001,0xe12fff1e,0xe3a00002))
        report=explore([image],[(image.address,False)])
        self.assertEqual(report['instruction_count'],2)
        self.assertEqual(report['stops'][0]['reason'],'indirect branch')
    def test_thumb_call_and_return(self):
        image=Image('code',0x2000000,bytes.fromhex('012070470220'))
        report=explore([image],[(image.address,True)])
        self.assertEqual(report['instruction_count'],2)
    def test_budget(self):
        image=Image('code',0x2000000,struct.pack('<4I',*[0xe1a00000]*4))
        self.assertTrue(explore([image],[(image.address,False)],2)['limit_reached'])
    def test_literal_mmio_evidence(self):
        image=Image('code',0x2000000,struct.pack('<3I',0xe59f0000,0xe12fff1e,0x04000000))
        report=explore([image],[(image.address,False)])
        self.assertTrue(report['literals'][0]['io_range'])

class NitroTests(unittest.TestCase):
    def test_bounds(self):
        with self.assertRaises(ValueError):blocks(b'BMD0')
    def test_direct_color_texture(self):
        container=ndspy.texture.NSBTX()
        tex=ndspy.texture.Texture(0,0,7<<10,0,struct.pack('<H',0x801f)*64,b'')
        container.textures=[('red',tex)]
        decoded=list(textures(container.save()))
        self.assertEqual(decoded[0][1].getpixel((0,0)),(255,0,0,255))

    def test_indexed_palette_adapter(self):
        container=ndspy.texture.NSBTX()
        container.textures=[('test',ndspy.texture.Texture(0,0,3<<10,0,b'\x11'*32,b''))]
        colors=[0,0x03e0]+[0]*14
        container.palettes=[('test',ndspy.texture.Palette(0,0,0,struct.pack('<16H',*colors)))]
        decoded=list(textures(container.save()))
        self.assertEqual(decoded[0][1].getpixel((0,0)),(0,255,0,255))
        self.assertEqual(decoded[0][0]['palette_selection'],'name-match')

if __name__=='__main__':unittest.main()
