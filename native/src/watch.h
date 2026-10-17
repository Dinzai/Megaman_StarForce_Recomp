// SPDX-License-Identifier: GPL-3.0-or-later
// Observation hooks: an NDS subclass that records ARM9 data accesses inside
// watched address ranges (with the PC that made them). Research/trace tool;
// requires the interpreter (the JIT's fast memory path bypasses these calls).
#pragma once

#include "NDS.h"

#include <cstdint>
#include <vector>

namespace sfr
{

struct WatchHit
{
    uint32_t pc, lr, addr, value;
    uint8_t size;   // 1, 2, 4
    bool write;
    uint64_t frame;
    uint32_t regs[16];     // ARM9 r0-r15 at the access
    uint32_t stack[128];   // words from SP upward (return addresses live here)
};

class WatchNDS : public melonDS::NDS
{
public:
    WatchNDS(melonDS::NDSArgs&& args, void* userdata) : melonDS::NDS(std::move(args), userdata) {}

    struct Range { uint32_t lo, hi; bool reads, writes; bool byValue = false; uint32_t value = 0; };
    std::vector<Range> ranges;
    std::vector<WatchHit> hits;
    size_t maxHits = 20000;
    uint64_t frame = 0;

    melonDS::u8 ARM9Read8(melonDS::u32 a) override { auto v = NDS::ARM9Read8(a); note(a, v, 1, false); return v; }
    melonDS::u16 ARM9Read16(melonDS::u32 a) override { auto v = NDS::ARM9Read16(a); note(a, v, 2, false); return v; }
    melonDS::u32 ARM9Read32(melonDS::u32 a) override { auto v = NDS::ARM9Read32(a); note(a, v, 4, false); return v; }
    void ARM9Write8(melonDS::u32 a, melonDS::u8 v) override { note(a, v, 1, true); NDS::ARM9Write8(a, v); }
    void ARM9Write16(melonDS::u32 a, melonDS::u16 v) override { note(a, v, 2, true); NDS::ARM9Write16(a, v); }
    void ARM9Write32(melonDS::u32 a, melonDS::u32 v) override { note(a, v, 4, true); NDS::ARM9Write32(a, v); }

    bool capture = true;

private:
    uint32_t peek32(uint32_t addr)
    {
        // DTCM (stacks usually live there) is not on the bus; read it directly.
        if ((addr & ARM9.DTCMMask) == ARM9.DTCMBase) return *(uint32_t*)&ARM9.DTCM[addr & (melonDS::DTCMPhysicalSize - 1)];
        return NDS::ARM9Read32(addr);
    }

    void note(uint32_t a, uint32_t v, uint8_t size, bool write)
    {
        if (ranges.empty() || hits.size() >= maxHits || !capture) return;
        for (const auto& r : ranges)
        {
            if (a >= r.lo && a < r.hi && (write ? r.writes : r.reads) && (!r.byValue || r.value == v))
            {
                WatchHit h{GetPC(0), ARM9.R[14], a, v, size, write, frame, {}, {}};
                for (int i = 0; i < 16; i++) h.regs[i] = ARM9.R[i];
                if (capture)
                {
                    capture = false;  // reading memory below must not recurse into note()
                    uint32_t sp = ARM9.R[13];
                    for (int i = 0; i < 128; i++) h.stack[i] = peek32(sp + i * 4);
                    capture = true;
                }
                hits.push_back(h);
                return;
            }
        }
    }
};

} // namespace sfr
