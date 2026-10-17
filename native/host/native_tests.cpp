// SPDX-License-Identifier: GPL-3.0-or-later
// ROM-independent tests for the mod layer (file replacement + cheat parsing).
#include "rompatch.h"
#include "session.h"

#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

using namespace sfr;

static int failures = 0;
#define CHECK(c) do { if (!(c)) { fprintf(stderr, "FAIL %s:%d: %s\n", __FILE__, __LINE__, #c); failures++; } } while (0)

static void put32(std::vector<uint8_t>& b, size_t o, uint32_t v) { for (int i = 0; i < 4; i++) b[o + i] = (uint8_t)(v >> (8 * i)); }
static uint32_t get32(const std::vector<uint8_t>& b, size_t o) { return b[o] | (b[o + 1] << 8) | (b[o + 2] << 16) | ((uint32_t)b[o + 3] << 24); }

// Minimal image: root dir with "a.bin" (id 0) and dir "sub" containing "b.bin" (id 1).
static std::vector<uint8_t> makeRom()
{
    std::vector<uint8_t> rom(0x10000, 0xFF);
    std::memset(rom.data(), 0, 0x200);
    const uint32_t fnt = 0x400, fat = 0x600;
    std::vector<uint8_t> t;
    auto p16 = [&](uint16_t v) { t.push_back(v & 0xFF); t.push_back(v >> 8); };
    auto p32 = [&](uint32_t v) { for (int i = 0; i < 4; i++) t.push_back((v >> (8 * i)) & 0xFF); };
    // main table: 2 dirs
    p32(16); p16(0); p16(2);
    p32(0); p16(1); p16(0xF000);  // patched below
    size_t sub0 = t.size();
    t.push_back(5); for (char c : std::string("a.bin")) t.push_back(c);
    t.push_back(0x83); for (char c : std::string("sub")) t.push_back(c); p16(0xF001);
    t.push_back(0);
    size_t sub1 = t.size();
    t.push_back(5); for (char c : std::string("b.bin")) t.push_back(c);
    t.push_back(0);
    t[0] = (uint8_t)sub0;
    t[8] = (uint8_t)sub1;
    std::memcpy(&rom[fnt], t.data(), t.size());
    put32(rom, 0x40, fnt); put32(rom, 0x44, (uint32_t)t.size());
    put32(rom, 0x48, fat); put32(rom, 0x4C, 16);
    put32(rom, fat + 0, 0x1000); put32(rom, fat + 4, 0x1010);
    put32(rom, fat + 8, 0x1200); put32(rom, fat + 12, 0x1210);
    std::memset(&rom[0x1000], 0xAA, 16);
    std::memset(&rom[0x1200], 0xBB, 16);
    put32(rom, 0x80, 0x1210);
    return rom;
}

int main()
{
    {
        auto rom = makeRom();
        NitroFS fs(rom);
        CHECK(fs.valid());
        CHECK(fs.lookup("a.bin") == 0);
        CHECK(fs.lookup("sub/b.bin") == 1);
        CHECK(fs.lookup("/sub/b.bin") == 1);
        CHECK(fs.lookup("missing") == -1);
    }
    {
        auto rom = makeRom();
        std::map<std::string, std::vector<uint8_t>> files;
        files["a.bin"] = std::vector<uint8_t>(8, 0x11);           // shrinks in place
        files["sub/b.bin"] = std::vector<uint8_t>(0x300, 0x22);   // relocates
        auto r = ApplyFileReplacements(rom, files);
        CHECK(r.ok);
        CHECK(get32(rom, 0x600) == 0x1000 && get32(rom, 0x604) == 0x1008);
        CHECK(rom[0x1000] == 0x11 && rom[0x1008] == 0xFF);
        uint32_t s = get32(rom, 0x608), e = get32(rom, 0x60C);
        CHECK(e - s == 0x300);
        CHECK(s >= 0x1210 && (s & 0x1FF) == 0);
        CHECK(rom[s] == 0x22 && rom[e - 1] == 0x22);
        CHECK(get32(rom, 0x80) >= e);
        uint16_t crc = rom[0x15E] | (rom[0x15F] << 8);
        CHECK(crc == Crc16(rom.data(), 0x15E));
    }
    {
        auto rom = makeRom();
        std::map<std::string, std::vector<uint8_t>> files{{"#1", std::vector<uint8_t>(0x20000, 0x33)}};
        auto r = ApplyFileReplacements(rom, files);
        CHECK(r.ok);
        CHECK(rom.size() == 0x40000);  // grew to next power of two
    }
    {
        auto rom = makeRom();
        auto r = ApplyFileReplacements(rom, {{"nope.bin", {1}}});
        CHECK(!r.ok);
    }
    {
        std::vector<uint32_t> w;
        std::string err;
        CHECK(ParseCheatText("# infinite hp\n12345678 0000FFFF\n; x\nD2000000 00000000\n", w, err));
        CHECK(w.size() == 4 && w[0] == 0x12345678 && w[3] == 0);
        CHECK(!ParseCheatText("1234 5678", w, err));
        CHECK(!ParseCheatText("12345678", w, err));
    }
    {
        // Archive with 2 entries: [0] "ABCD" stored, [1] 3 bytes stored; then edit entry 1 via "a.bin@1".
        std::vector<uint8_t> arc(24, 0);
        auto p32v = [&](size_t o, uint32_t v) { for (int i = 0; i < 4; i++) arc[o + i] = (uint8_t)(v >> (8 * i)); };
        arc.insert(arc.end(), {'A', 'B', 'C', 'D', 'x', 'y', 'z', 0});
        p32v(0, 24); p32v(4, 4); p32v(8, 28); p32v(12, 3); p32v(16, 32); p32v(20, 0xFFFF);
        std::vector<uint8_t> out;
        std::string err;
        CHECK(RebuildArchive(arc, {{1, {'h', 'e', 'l', 'l', 'o', '!'}}}, out, err));
        CHECK(get32(out, 0) == 24 && get32(out, 4) == 4 && out[24] == 'A');
        CHECK(get32(out, 8) == 28 && get32(out, 12) == 6 && out[28] == 'h' && out[33] == '!');
        CHECK(get32(out, 16) == out.size() && get32(out, 20) == 0xFFFF && out.size() % 4 == 0);
        CHECK(!RebuildArchive(arc, {{2, {1}}}, out, err));

        // Through ApplyFileReplacements on a ROM whose a.bin is that archive.
        auto rom = makeRom();
        std::memcpy(&rom[0x1000], arc.data(), arc.size());
        put32(rom, 0x600, 0x1000); put32(rom, 0x604, 0x1000 + (uint32_t)arc.size());
        auto r = ApplyFileReplacements(rom, {{"a.bin@1", {'Q', 'Q', 'Q', 'Q', 'Q', 'Q', 'Q', 'Q', 'Q', 'Q'}}});
        CHECK(r.ok);
        uint32_t s0 = get32(rom, 0x600), e0 = get32(rom, 0x604);
        std::vector<uint8_t> patched(rom.begin() + s0, rom.begin() + e0);
        CHECK(get32(patched, 12) == 10 && patched[get32(patched, 8)] == 'Q');
        CHECK(!ApplyFileReplacements(rom, {{"a.bin@9", {1}}}).ok);
    }
    {
        // SF3 uses a zero EOF tag. Preserve untouched compressed payloads byte-for-byte.
        std::vector<uint8_t> arc(32,0),out;
        put32(arc,0,24);put32(arc,4,0x80000004);put32(arc,8,28);put32(arc,12,1);put32(arc,16,32);put32(arc,20,0);
        arc[24]=0x11;arc[25]=4;arc[28]='x';std::string err;
        CHECK(RebuildArchive(arc,{{1,{'y','z'}}},out,err));
        CHECK(get32(out,20)==0 && get32(out,4)==0x80000004 && out[24]==0x11 && out[25]==4);
        CHECK(get32(out,12)==2 && out[get32(out,8)]=='y');
        put32(arc,20,1);CHECK(!RebuildArchive(arc,{{1,{'y'}}},out,err));
    }
    if (failures) { fprintf(stderr, "%d failure(s)\n", failures); return 1; }
    printf("native tests passed\n");
    return 0;
}

