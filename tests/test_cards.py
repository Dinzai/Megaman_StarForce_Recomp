import json
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1] / 'tools'))
import cards  # noqa: E402
import forms  # noqa: E402

ASSETS = Path(__file__).resolve().parents[1] / 'app/src/main/assets/cards'


def table(*names):
    return cards.join_names([(cards.encode_name(n) if n else b'') + b'\xe6\x00' for n in names])


class CardTests(unittest.TestCase):
    def test_names_round_trip(self):
        t = table('MegaBuster', 'Cannon', 'Fokx-Fu1')
        self.assertEqual(cards.card_names(t), ['MegaBuster', 'Cannon', 'Fokx-Fu1'])
        self.assertEqual(cards.join_names(cards.split_names(t)), t)
        for bad in ('', 'ElevenChars', 'Bad!'):
            with self.assertRaises(ValueError):
                cards.encode_name(bad)

    def test_pack_matches_java_layout(self):
        t = table('MegaBuster', 'Cannon', 'PlusCannon', 'HevyCannon')
        code, names = cards.build_pack(t, {'format': 'starforce-cards', 'cards': [
            {'base': 'cannon', 'name': 'FireCannon', 'damage': 70, 'element': 'fire', 'picture': 'HevyCannon'},
            {'base': 2, 'damage': 99}]})
        self.assertIn('D3000000 020B8F24\nF20B8ECC 00000008\nD2000000 00000000\n', code)
        self.assertIn('220B8EA8 00000005\n120B8EAE 00000046\n', code)
        self.assertIn('120B8EDA 00000063\n', code)
        self.assertEqual(cards.card_names(names)[1], 'FireCannon')

    def test_rejects_bad_packs(self):
        t = table('MegaBuster', 'Cannon')
        for pack in ({'format': 'x', 'cards': []},
                     {'format': 'starforce-cards', 'cards': [{'base': 'Nope'}]},
                     {'format': 'starforce-cards', 'cards': [{'base': 'Cannon', 'element': 'ice'}]},
                     {'format': 'starforce-cards', 'cards': [{'base': 'Cannon'}, {'base': 1}]}):
            with self.assertRaises(ValueError):
                cards.build_pack(t, pack)

    def test_bundled_packs_are_valid(self):
        files = sorted(ASSETS.glob('*.json'))
        self.assertGreaterEqual(len(files), 3)
        for f in files:
            pack = json.loads(f.read_text())
            self.assertEqual(pack['format'], 'starforce-cards')
            for c in pack['cards']:
                cards.encode_name(c['name'])


class FormSystemTests(unittest.TestCase):
    def test_codes_match_app(self):
        self.assertIn('221AF1BF 00000003\nD2000000', forms.system_code('dragon'))
        z = forms.system_code('zerker')
        self.assertIn('A21AF022 00FF0000\n221AF022 00000004\n221ADC95 00000003\n', z)
        self.assertEqual(set(forms.SYSTEM), {k for k in forms.FORMS if k != 'mega-man'})


if __name__ == '__main__':
    unittest.main()
