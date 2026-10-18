import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import decomp  # noqa: E402


def thumb_bl(src, dst):
    off = dst - (src + 4)
    off &= 0x7FFFFF
    return struct.pack('<HH', 0xF000 | (off >> 12), 0xF800 | ((off >> 1) & 0x7FF))


class DecompTests(unittest.TestCase):
    def test_bl_targets_and_prologues(self):
        base = 0x02000000
        code = bytearray(0x40)
        code[0:4] = thumb_bl(base, base + 0x20)                     # bl 0x20 (thumb)
        struct.pack_into('<I', code, 0x10, 0xEB000002)               # ARM bl: 0x10+8+8 = 0x20
        struct.pack_into('<H', code, 0x1E, 0x4770)                   # bx lr
        struct.pack_into('<H', code, 0x20, 0xB570)                   # push {r4-r6, lr}
        m = decomp.Module('t', base, bytes(code))
        t = decomp.bl_targets(m)
        self.assertTrue(t[base + 0x20])
        self.assertIn(base + 0x20, decomp.prologues(m))

    def test_symbol_files_parse(self):
        for game in ('leo', 'zerker-ninja', 'black-ace'):
            syms = decomp.load_symbols(game)
            self.assertTrue(syms)
            for addr, (name, mode, module, _) in syms.items():
                self.assertTrue(0x02000000 <= addr < 0x02400000, name)
                self.assertIn(mode, ('thumb', 'arm', 'data'))
        self.assertEqual(decomp.load_symbols('leo')[0x0217600C][:3], ('Battle_Transform', 'thumb', 'ov007'))


if __name__ == '__main__':
    unittest.main()
