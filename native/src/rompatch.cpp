// SPDX-License-Identifier: GPL-3.0-or-later
#include "rompatch.h"

#include <algorithm>
#include <cstring>

namespace sfr
{

static uint32_t rd32(const std::vector<uint8_t>& b, size_t o)
{
    return (uint32_t)b[o] | ((uint32_t)b[o + 1] << 8) | ((uint32_t)b[o + 2] << 16) | ((uint32_t)b[o + 3] << 24);
}
static uint16_t rd16(const std::vector<uint8_t>& b, size_t o) { return (uint16_t)(b[o] | (b[o + 1] << 8)); }
static void wr32(std::vector<uint8_t>& b, size_t o, uint32_t v)
{
    for (int i = 0; i < 4; i++) b[o + i] = (uint8_t)(v >> (8 * i));
}
static void wr16(std::vector<uint8_t>& b, size_t o, uint16_t v)
{
    b[o] = (uint8_t)v;
    b[o + 1] = (uint8_t)(v >> 8);
}

uint16_t Crc16(const uint8_t* data, size_t len)
{
    uint16_t crc = 0xFFFF;
    for (size_t i = 0; i < len; i++)
    {
        crc ^= data[i];
        for (int j = 0; j < 8; j++) crc = (crc & 1) ? (uint16_t)((crc >> 1) ^ 0xA001) : (uint16_t)(crc >> 1);
    }
    return crc;
}

NitroFS::NitroFS(const std::vector<uint8_t>& rom)
{
    if (rom.size() < 0x200) return;
    uint32_t fnt = rd32(rom, 0x40), fntSize = rd32(rom, 0x44);
    uint32_t fat = rd32(rom, 0x48), fatSize = rd32(rom, 0x4C);
    if ((uint64_t)fnt + fntSize > rom.size() || (uint64_t)fat + fatSize > rom.size() || fntSize < 8) return;
    fatCount_ = fatSize / 8;
    walk(rom, fnt, fntSize, 0xF000, "", 0);
    valid_ = true;
}

void NitroFS::walk(const std::vector<uint8_t>& rom, uint32_t fnt, uint32_t fntSize, uint16_t dirId, const std::string& prefix, int depth)
{
    if (depth > 32) return;
    uint32_t idx = dirId & 0x0FFF;
    if ((idx + 1) * 8 > fntSize) return;
    uint32_t sub = fnt + rd32(rom, fnt + idx * 8);
    uint16_t fileId = rd16(rom, fnt + idx * 8 + 4);
    uint32_t end = fnt + fntSize;
    while (sub < end)
    {
        uint8_t len = rom[sub++];
        if (len == 0) break;
        uint32_t nameLen = len & 0x7F;
        if (sub + nameLen > end) return;
        std::string name(reinterpret_cast<const char*>(&rom[sub]), nameLen);
        sub += nameLen;
        if (len & 0x80)
        {
            if (sub + 2 > end) return;
            uint16_t child = rd16(rom, sub);
            sub += 2;
            walk(rom, fnt, fntSize, child, prefix + name + "/", depth + 1);
        }
        else
        {
            paths_[prefix + name] = fileId++;
        }
    }
}

int NitroFS::lookup(const std::string& path) const
{
    std::string p = path;
    while (!p.empty() && p[0] == '/') p.erase(0, 1);
    auto it = paths_.find(p);
    return it == paths_.end() ? -1 : it->second;
}

// Leo's offset/length archive: N+1 records of (offset, decodedSize | compressedFlag<<31);
// the last record is (fileLength, 0xFFFF). Replacements are stored uncompressed.
bool RebuildArchive(const std::vector<uint8_t>& in, const std::map<int, std::vector<uint8_t>>& entries,
                    std::vector<uint8_t>& out, std::string& error)
{
    if (in.size() < 16) { error = "archive too short"; return false; }
    uint32_t first = rd32(in, 0);
    if (first < 16 || first % 8 || first > in.size()) { error = "not a Leo archive"; return false; }
    uint32_t count = first / 8 - 1;
    if (rd32(in, first - 8) != in.size() || (rd32(in, first - 4) != 0xFFFF && rd32(in, first - 4) != 0)) { error = "archive EOF marker missing"; return false; }
    for (auto& [idx, data] : entries)
        if (idx < 0 || (uint32_t)idx >= count) { error = "archive has no entry " + std::to_string(idx); return false; }
    out.assign(first, 0);
    for (uint32_t i = 0; i < count; i++)
    {
        uint32_t off = rd32(in, i * 8), flags = rd32(in, i * 8 + 4), end = rd32(in, (i + 1) * 8);
        if (off < first || end < off || end > in.size()) { error = "corrupt archive record"; return false; }
        while (out.size() % 4) out.push_back(0);
        uint32_t at = (uint32_t)out.size();
        auto it = entries.find((int)i);
        if (it != entries.end())
        {
            out.insert(out.end(), it->second.begin(), it->second.end());
            wr32(out, i * 8, at);
            wr32(out, i * 8 + 4, (uint32_t)it->second.size() & 0x7FFFFFFF);
        }
        else
        {
            bool compressed = flags >> 31;
            uint32_t stored = compressed ? end - off : std::min(flags & 0x7FFFFFFF, end - off);
            out.insert(out.end(), in.begin() + off, in.begin() + off + stored);
            wr32(out, i * 8, at);
            wr32(out, i * 8 + 4, flags);
        }
    }
    while (out.size() % 4) out.push_back(0);
    wr32(out, count * 8, (uint32_t)out.size());
    wr32(out, count * 8 + 4, rd32(in, first - 4));
    return true;
}

RomPatchResult ApplyFileReplacements(std::vector<uint8_t>& rom, const std::map<std::string, std::vector<uint8_t>>& filesIn)
{
    RomPatchResult res;
    if (filesIn.empty()) return res;

    // "path@N" keys replace entry N inside a Leo archive; fold them into whole-file replacements.
    std::map<std::string, std::vector<uint8_t>> files;
    std::map<std::string, std::map<int, std::vector<uint8_t>>> archiveEdits;
    for (const auto& [key, data] : filesIn)
    {
        auto at = key.rfind('@');
        if (at == std::string::npos) { files[key] = data; continue; }
        char* endp = nullptr;
        long idx = strtol(key.c_str() + at + 1, &endp, 10);
        if (at == 0 || !endp || *endp || idx < 0) { res.ok = false; res.error = "bad archive entry key: " + key; return res; }
        archiveEdits[key.substr(0, at)][(int)idx] = data;
    }
    if (!archiveEdits.empty())
    {
        NitroFS fs0(rom);
        const uint32_t fat0 = rd32(rom, 0x48);
        for (auto& [path, edits] : archiveEdits)
        {
            std::vector<uint8_t> base;
            auto whole = files.find(path);
            if (whole != files.end()) base = whole->second;  // edits apply on top of a whole-file mod
            else
            {
                int id = path[0] == '#' ? atoi(path.c_str() + 1) : fs0.lookup(path);
                if (id < 0 || (uint32_t)id >= fs0.fatCount()) { res.ok = false; res.error = "no such ROM file: " + path; return res; }
                uint32_t st = rd32(rom, fat0 + id * 8), en = rd32(rom, fat0 + id * 8 + 4);
                base.assign(rom.begin() + st, rom.begin() + en);
            }
            std::vector<uint8_t> rebuilt;
            std::string err;
            if (!RebuildArchive(base, edits, rebuilt, err)) { res.ok = false; res.error = path + ": " + err; return res; }
            files[path] = std::move(rebuilt);
            for (auto& [idx, d] : edits) res.applied.push_back(path + "@" + std::to_string(idx));
        }
    }
    NitroFS fs(rom);
    if (!fs.valid())
    {
        res.ok = false;
        res.error = "ROM has no readable file system";
        return res;
    }

    const uint32_t fat = rd32(rom, 0x48);
    uint32_t used = rd32(rom, 0x80);
    if (used == 0 || used > rom.size()) used = (uint32_t)rom.size();
    // Free space starts after the highest allocated byte of any file.
    uint32_t tail = used;
    for (uint32_t i = 0; i < fs.fatCount(); i++) tail = std::max(tail, rd32(rom, fat + i * 8 + 4));
    auto align = [](uint32_t v) { return (v + 0x1FF) & ~0x1FFu; };
    tail = align(tail);

    for (const auto& [key, data] : files)
    {
        if (key == "arm9" || key == "arm7")
        {
            size_t ho = key == "arm9" ? 0x20 : 0x30;
            uint32_t off = rd32(rom, ho), size = rd32(rom, ho + 0x0C);
            if (data.size() > size)
            {
                res.ok = false;
                res.error = key + " replacement is larger than the original (" + std::to_string(data.size()) + " > " + std::to_string(size) + ")";
                return res;
            }
            std::memcpy(&rom[off], data.data(), data.size());
            wr32(rom, ho + 0x0C, (uint32_t)data.size());
            res.applied.push_back(key);
            continue;
        }

        int id = -1;
        if (!key.empty() && key[0] == '#')
        {
            char* endp = nullptr;
            long v = strtol(key.c_str() + 1, &endp, 10);
            if (endp && *endp == 0 && v >= 0 && (uint32_t)v < fs.fatCount()) id = (int)v;
        }
        else
        {
            id = fs.lookup(key);
        }
        if (id < 0)
        {
            res.ok = false;
            res.error = "no such ROM file: " + key;
            return res;
        }

        uint32_t start = rd32(rom, fat + id * 8), end = rd32(rom, fat + id * 8 + 4);
        uint32_t len = (uint32_t)data.size();
        if (len <= end - start)
        {
            std::memcpy(&rom[start], data.data(), len);
            std::memset(&rom[start + len], 0xFF, (end - start) - len);
            wr32(rom, fat + id * 8 + 4, start + len);
        }
        else
        {
            uint32_t dst = tail;
            uint64_t need = (uint64_t)dst + len;
            if (need > 0x20000000ull)
            {
                res.ok = false;
                res.error = "ROM would exceed 512 MiB";
                return res;
            }
            if (need > rom.size())
            {
                size_t cap = rom.size();
                while (cap < need) cap *= 2;  // keep a power-of-two cart size
                rom.resize(cap, 0xFF);
            }
            std::memcpy(&rom[dst], data.data(), len);
            wr32(rom, fat + id * 8, dst);
            wr32(rom, fat + id * 8 + 4, dst + len);
            tail = align(dst + len);
        }
        res.applied.push_back(key);
    }

    // Header: used ROM size, device capacity, header CRC.
    uint32_t newUsed = std::max(used, tail);
    wr32(rom, 0x80, newUsed);
    uint8_t cap = 0;
    while ((0x20000ull << cap) < rom.size()) cap++;
    rom[0x14] = cap;
    wr16(rom, 0x15E, Crc16(rom.data(), 0x15E));
    return res;
}

} // namespace sfr

