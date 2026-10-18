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

    def test_zerker_ninja_layout_keeps_name_suffix(self):
        t = cards.join_names([b'\x2d\xe6', b'\x23\x41\xfa\x18\xe6', b'\x30\xfa\x18\xe6'])
        code, names = cards.build_pack(t, {'format': 'starforce-cards', 'game': 'zerker-ninja', 'cards': [
            {'base': 'Ca', 'name': 'Zap', 'damage': 123, 'element': 'fire', 'picture': 2}]})
        self.assertIn('D3000000 020BBAD0\nF20BBAB8 00000004\n', code)
        self.assertIn('220BBAB0 00000005\n120BBABE 0000007B\n', code)
        self.assertEqual(cards.split_names(names)[1], b'\x3a\x41\x50\xfa\x18\xe6')
        with self.assertRaises(ValueError):
            cards.build_pack(t, {'format': 'starforce-cards', 'game': 'red-joker', 'cards': [{'base': 1}]})

    def test_bundled_packs_are_valid(self):
        files = sorted(ASSETS.glob('*.json')) + sorted((ASSETS.parent / 'cards-zerker-ninja').glob('*.json')) \
            + sorted((ASSETS.parent / 'cards-black-ace').glob('*.json'))
        self.assertGreaterEqual(len(files), 9)
        for f in files:
            pack = json.loads(f.read_text())
            self.assertEqual(pack['format'], 'starforce-cards')
            for c in pack['cards']:
                cards.encode_name(c['name'])


class BlackAceCardTests(unittest.TestCase):
    def test_gsm_round_trip_and_codes(self):
        head = bytearray(b' GSM\x00\x01\x03\x00\x0a\x00\xff\xff') + bytes(12)
        t = cards.gsm_join(bytes(head), [cards.gsm_encode('None'), cards.gsm_encode('Cannon'), cards.gsm_encode('Plus Gun')])
        self.assertEqual(cards.card_names(t, 'black-ace'), ['None', 'Cannon', 'Plus Gun'])
        code, names = cards.build_pack(t, {'format': 'starforce-cards', 'game': 'black-ace', 'cards': [
            {'base': 'cannon', 'name': 'Zap Gun', 'damage': 123, 'element': 'fire', 'picture': 2}]})
        self.assertIn('D3000000 020D18E0\nF20D18D0 00000004\n', code)
        self.assertIn('DB000000 020D18CC\nD4000002 000000E3\nD4000001 00000004\nD8000000 020D18CC\n', code)
        self.assertIn('120D18D4 0000007B\n', code)
        self.assertEqual(cards.card_names(names, 'black-ace')[1], 'Zap Gun')


class FormSystemTests(unittest.TestCase):
    def test_codes_match_app(self):
        self.assertIn('221AF1BF 00000003\nD2000000', forms.system_code('dragon'))
        z = forms.system_code('zerker')
        self.assertIn('A21AF022 00FF0000\n221AF022 00000004\n221ADC95 00000003\n', z)
        self.assertEqual(set(forms.SYSTEM), {k for k in forms.FORMS if k != 'mega-man'})

    def test_zerker_ninja_codes_match_app(self):
        self.assertIn('D4000002 FFFC1FFF\nD4000001 00008000\nD6000000 0219D2E4\n', forms.zn_code('saurian'))
        self.assertIn('D4000001 00020000', forms.zn_code('black-ace'))
        self.assertEqual(len(forms.ZN_SYSTEM), 9)


if __name__ == '__main__':
    unittest.main()
