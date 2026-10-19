import struct
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
from forms import nitro_container, rename_model, FORMS, SLOTS  # noqa: E402
from leo_assets import blocks  # noqa: E402


def mdl0(name):
    body = bytearray(8 + 12 + 4 + 4 + 4 + 16)
    body[0:4] = b'MDL0'
    struct.pack_into('<I', body, 4, len(body))
    body[9] = 1
    struct.pack_into('<H', body, 8 + 12 + 4, 4)
    body[8 + 12 + 4 + 4 + 4:8 + 12 + 4 + 4 + 4 + len(name)] = name
    return bytes(body)


class FormsTest(unittest.TestCase):
    def test_container_and_rename(self):
        tex = b'TEX0' + struct.pack('<I', 12) + b'\0' * 4
        renamed = rename_model(mdl0(b'pl001'), 'pl003')
        self.assertIn(b'pl003', renamed)
        self.assertNotIn(b'pl001', renamed)
        c = nitro_container(b'BMD0', [('MDL0', renamed), ('TEX0', tex)])
        self.assertEqual(struct.unpack_from('<I', c, 8)[0], len(c))
        self.assertEqual(dict(blocks(c)), {'MDL0': renamed, 'TEX0': tex})

    def test_tables(self):
        self.assertEqual(SLOTS['starforce'], (157, 'pl003'))
        self.assertEqual(FORMS['zerker'], ('sf2', 'pl001'))
        self.assertEqual(FORMS['black-ace'], ('sf3', 'pl010'))
        self.assertEqual(FORMS['red-joker'], ('sf3', 'pl00f00'))


if __name__ == '__main__':
    unittest.main()


class BlackAceFormsTest(unittest.TestCase):
    def test_noise_and_finalize_codes(self):
        from forms import ba_code, BA_SYSTEM
        self.assertEqual(len(BA_SYSTEM), 14)
        self.assertIn('22189876 00000007\n', ba_code('leo'))
        self.assertIn('22189876 00000007\n', ba_code('taurus'))
        self.assertIn('12163DA0 0000220C\n', ba_code('black-ace'))
        self.assertIn('221897C2 00000001\n', ba_code('red-joker'))
