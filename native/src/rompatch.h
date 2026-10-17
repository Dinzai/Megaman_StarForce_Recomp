// SPDX-License-Identifier: GPL-3.0-or-later
#pragma once

#include <cstdint>
#include <map>
#include <string>
#include <vector>

namespace sfr
{

// Replaces files inside an in-memory NDS image before the cartridge is built.
// Keys are NitroFS paths ("datbin/eng/mess.bin"), FAT ids ("#661"), or the
// processor binaries "arm9" / "arm7". Larger files are relocated into the
// unused tail of the ROM image; the header size and CRC are updated.
struct RomPatchResult
{
    bool ok = true;
    std::string error;
    std::vector<std::string> applied;
};

class NitroFS
{
public:
    explicit NitroFS(const std::vector<uint8_t>& rom);
    bool valid() const { return valid_; }
    // Returns -1 if not found.
    int lookup(const std::string& path) const;
    const std::map<std::string, int>& paths() const { return paths_; }
    uint32_t fatCount() const { return fatCount_; }

private:
    void walk(const std::vector<uint8_t>& rom, uint32_t fnt, uint32_t fntSize, uint16_t dirId, const std::string& prefix, int depth);
    bool valid_ = false;
    uint32_t fatCount_ = 0;
    std::map<std::string, int> paths_;
};

RomPatchResult ApplyFileReplacements(std::vector<uint8_t>& rom, const std::map<std::string, std::vector<uint8_t>>& files);

uint16_t Crc16(const uint8_t* data, size_t len);

} // namespace sfr
