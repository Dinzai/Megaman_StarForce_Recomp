// SPDX-License-Identifier: GPL-3.0-or-later
// One running game: owns the melonDS console, applies mods, and exposes a
// small frame-stepping API used by both the Android JNI bridge and the host
// test runner.
#pragma once

#include <atomic>
#include <cstdint>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <vector>

namespace melonDS { class NDS; }

namespace sfr
{

enum Key : uint32_t
{
    KeyA = 1 << 0, KeyB = 1 << 1, KeySelect = 1 << 2, KeyStart = 1 << 3,
    KeyRight = 1 << 4, KeyLeft = 1 << 5, KeyUp = 1 << 6, KeyDown = 1 << 7,
    KeyR = 1 << 8, KeyL = 1 << 9, KeyX = 1 << 10, KeyY = 1 << 11,
};

struct CheatCode
{
    std::string name;
    std::vector<uint32_t> words;
};

// Parses Action Replay DS text: pairs of 8-digit hex words, '#' / ';' comments.
bool ParseCheatText(const std::string& text, std::vector<uint32_t>& out, std::string& error);

struct LaunchOptions
{
    bool jit = true;
    bool threaded3D = true;
    int audioRate = 48000;
    std::vector<uint8_t> save;                              // battery save, may be empty
    std::map<std::string, std::vector<uint8_t>> files;      // ROM file replacements
    std::vector<CheatCode> cheats;                          // memory codes run every frame
    bool instrument = false;                                // enable watch hooks (use with jit=false)
};

struct WatchHit;

class Session
{
public:
    static constexpr int ScreenW = 256, ScreenH = 192;

    Session();
    ~Session();

    bool Load(std::vector<uint8_t> rom, LaunchOptions opts, std::string& error);
    void SetInput(uint32_t pressedKeys, bool touching, int tx, int ty);
    void RunFrame();
    // Copies both screens (top then bottom) as 0xAARRGGBB, 2*256*192 pixels.
    void CopyScreens(uint32_t* out) const;
    // Interleaved stereo s16; returns frames written.
    int ReadAudio(int16_t* out, int maxFrames);

    // Battery save.
    bool TakeSaveDirty();
    std::vector<uint8_t> SaveData() const;

    // Save states.
    std::vector<uint8_t> SaveState();
    bool LoadState(const std::vector<uint8_t>& state);

    // Mod / research access to ARM9 address space.
    uint32_t Read32(uint32_t addr);
    void Write32(uint32_t addr, uint32_t value);
    uint16_t Read16(uint32_t addr);
    void Write16(uint32_t addr, uint16_t value);
    uint8_t Read8(uint32_t addr);
    void Write8(uint32_t addr, uint8_t value);
    uint32_t Arm9PC() const;
    void SetCheats(const std::vector<CheatCode>& cheats);

    // Observation hooks (LaunchOptions::instrument). Ranges are [lo, hi).
    bool AddWatch(uint32_t lo, uint32_t hi, bool reads, bool writes);
    std::vector<WatchHit> TakeWatchHits();

    uint64_t FrameCount() const { return frames_; }
    bool Stopped() const { return stopped_; }
    const std::vector<std::string>& AppliedFiles() const { return applied_; }

    // Platform callbacks.
    void OnStop(int reason);
    void OnSaveWritten(const uint8_t* data, uint32_t len, uint32_t off, uint32_t wlen);

    static void SetDataDir(const std::string& dir);
    static const std::string& DataDir();
    static void SetVerboseLog(bool v);
    static bool VerboseLog();

private:
    std::unique_ptr<melonDS::NDS> nds_;
    mutable std::mutex saveMutex_;
    std::vector<uint8_t> save_;
    std::atomic<bool> saveDirty_{false};
    std::atomic<bool> stopped_{false};
    uint64_t frames_ = 0;
    std::vector<std::string> applied_;
};

} // namespace sfr
