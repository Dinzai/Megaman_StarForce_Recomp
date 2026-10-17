// SPDX-License-Identifier: GPL-3.0-or-later
#include "session.h"
#include "rompatch.h"
#include "watch.h"

#include "NDS.h"
#include "NDSCart.h"
#include "Args.h"
#include "GPU3D_Soft.h"
#include "Savestate.h"
#include "SPI_Firmware.h"

#include <cctype>
#include <cstring>

namespace sfr
{

static std::string g_dataDir = ".";
static bool g_verbose = false;

void Session::SetDataDir(const std::string& dir) { g_dataDir = dir; }
const std::string& Session::DataDir() { return g_dataDir; }
void Session::SetVerboseLog(bool v) { g_verbose = v; }
bool Session::VerboseLog() { return g_verbose; }

bool ParseCheatText(const std::string& text, std::vector<uint32_t>& out, std::string& error)
{
    out.clear();
    size_t i = 0;
    int line = 1;
    while (i < text.size())
    {
        char c = text[i];
        if (c == '\n') { line++; i++; continue; }
        if (c == '#' || c == ';')
        {
            while (i < text.size() && text[i] != '\n') i++;
            continue;
        }
        if (isspace((unsigned char)c)) { i++; continue; }
        size_t start = i;
        while (i < text.size() && isxdigit((unsigned char)text[i])) i++;
        if (i - start != 8 || (i < text.size() && !isspace((unsigned char)text[i]) && text[i] != '#' && text[i] != ';'))
        {
            error = "line " + std::to_string(line) + ": expected 8 hex digits";
            return false;
        }
        out.push_back((uint32_t)strtoul(text.substr(start, 8).c_str(), nullptr, 16));
    }
    if (out.size() % 2)
    {
        error = "odd number of code words";
        return false;
    }
    return true;
}

Session::Session() = default;
Session::~Session()
{
    if (nds_) nds_->Stop(melonDS::Platform::StopReason::External);
}

bool Session::Load(std::vector<uint8_t> rom, LaunchOptions opts, std::string& error)
{
    using namespace melonDS;

    auto patch = ApplyFileReplacements(rom, opts.files);
    if (!patch.ok)
    {
        error = "mod file replacement failed: " + patch.error;
        return false;
    }
    applied_ = patch.applied;

    NDSArgs args;
    args.Firmware = Firmware(0);  // generated firmware; FreeBIOS + direct boot
    if (!opts.jit) args.JIT = std::nullopt;
    args.OutputSampleRate = opts.audioRate;
    args.Interpolation = AudioInterpolation::Cubic;

    if (opts.instrument) nds_ = std::make_unique<WatchNDS>(std::move(args), this);
    else nds_ = std::make_unique<NDS>(std::move(args), this);
    nds_->Reset();

    NDSCart::NDSCartArgs cartArgs;
    if (!opts.save.empty())
    {
        cartArgs.SRAM = std::make_unique<u8[]>(opts.save.size());
        std::memcpy(cartArgs.SRAM.get(), opts.save.data(), opts.save.size());
        cartArgs.SRAMLength = (u32)opts.save.size();
    }

    u32 len = (u32)rom.size();
    auto romData = std::make_unique<u8[]>(len);
    std::memcpy(romData.get(), rom.data(), len);
    rom.clear();
    rom.shrink_to_fit();

    auto cart = NDSCart::ParseROM(std::move(romData), len, this, std::move(cartArgs));
    if (!cart)
    {
        error = "not a valid DS cartridge image";
        nds_.reset();
        return false;
    }

    nds_->SetNDSCart(std::move(cart));
    nds_->Reset();
    nds_->SetupDirectBoot("game.nds");

    if (opts.threaded3D)
    {
        if (auto* soft = dynamic_cast<SoftRenderer*>(&nds_->GPU.GetRenderer3D()))
            soft->SetThreaded(true, nds_->GPU);
    }

    {
        std::lock_guard<std::mutex> lk(saveMutex_);
        const u8* s = nds_->GetNDSSave();
        u32 sl = nds_->GetNDSSaveLength();
        save_.assign(s, s + (s ? sl : 0));
    }

    SetCheats(opts.cheats);
    nds_->Start();
    return true;
}

void Session::SetCheats(const std::vector<CheatCode>& cheats)
{
    if (!nds_) return;
    nds_->AREngine.Cheats.clear();
    for (const auto& c : cheats)
    {
        melonDS::ARCode code{};
        code.Parent = nullptr;
        code.Name = c.name;
        code.Enabled = true;
        code.Code = c.words;
        nds_->AREngine.Cheats.push_back(std::move(code));
    }
}

void Session::SetInput(uint32_t pressedKeys, bool touching, int tx, int ty)
{
    if (!nds_) return;
    nds_->SetKeyMask(~pressedKeys & 0xFFF);  // hardware keys are active-low
    if (touching)
    {
        tx = tx < 0 ? 0 : tx > 255 ? 255 : tx;
        ty = ty < 0 ? 0 : ty > 191 ? 191 : ty;
        nds_->TouchScreen((uint16_t)tx, (uint16_t)ty);
    }
    else
    {
        nds_->ReleaseScreen();
    }
}

bool Session::AddWatch(uint32_t lo, uint32_t hi, bool reads, bool writes, bool byValue, uint32_t value)
{
    auto* w = dynamic_cast<WatchNDS*>(nds_.get());
    if (!w || hi <= lo) return false;
    WatchNDS::Range r{lo, hi, reads, writes};
    r.byValue = byValue;
    r.value = value;
    w->ranges.push_back(r);
    return true;
}

std::vector<WatchHit> Session::TakeWatchHits()
{
    auto* w = dynamic_cast<WatchNDS*>(nds_.get());
    if (!w) return {};
    std::vector<WatchHit> out;
    out.swap(w->hits);
    return out;
}

void Session::RunFrame()
{
    if (!nds_ || stopped_) return;
    if (auto* w = dynamic_cast<WatchNDS*>(nds_.get())) w->frame = frames_ + 1;
    nds_->RunFrame();
    frames_++;
}

void Session::CopyScreens(uint32_t* out) const
{
    if (!nds_) return;
    int fb = nds_->GPU.FrontBuffer;
    const uint32_t* top = nds_->GPU.Framebuffer[fb][0].get();
    const uint32_t* bot = nds_->GPU.Framebuffer[fb][1].get();
    const int n = ScreenW * ScreenH;
    if (!top || !bot) return;
    // melonDS's software renderer writes 0x00RRGGBB (the layout of Qt's
    // QImage::Format_RGB32). Android bitmaps want 0xAARRGGBB, so only the
    // alpha byte needs setting. (0.4.0 wrongly swapped red and blue here.)
    for (int i = 0; i < n; i++)
    {
        out[i] = 0xFF000000u | (top[i] & 0x00FFFFFFu);
        out[n + i] = 0xFF000000u | (bot[i] & 0x00FFFFFFu);
    }
}

int Session::ReadAudio(int16_t* out, int maxFrames)
{
    if (!nds_) return 0;
    return nds_->SPU.ReadOutput(out, maxFrames);
}

void Session::OnStop(int) { stopped_ = true; }

void Session::OnSaveWritten(const uint8_t* data, uint32_t len, uint32_t off, uint32_t wlen)
{
    std::lock_guard<std::mutex> lk(saveMutex_);
    if (save_.size() != len) save_.assign(data, data + len);
    else if (off + wlen <= len) std::memcpy(save_.data() + off, data + off, wlen);
    else save_.assign(data, data + len);
    saveDirty_ = true;
}

bool Session::TakeSaveDirty() { return saveDirty_.exchange(false); }

std::vector<uint8_t> Session::SaveData() const
{
    std::lock_guard<std::mutex> lk(saveMutex_);
    return save_;
}

std::vector<uint8_t> Session::SaveState()
{
    if (!nds_) return {};
    melonDS::Savestate st;
    if (st.Error) return {};
    nds_->DoSavestate(&st);
    st.Finish();
    if (st.Error) return {};
    const uint8_t* b = static_cast<const uint8_t*>(st.Buffer());
    return std::vector<uint8_t>(b, b + st.Length());
}

bool Session::LoadState(const std::vector<uint8_t>& state)
{
    if (!nds_ || state.empty()) return false;
    std::vector<uint8_t> copy = state;  // Savestate may write into its buffer
    melonDS::Savestate st(copy.data(), (uint32_t)copy.size(), false);
    if (st.Error) return false;
    // Keep a backup so a bad state cannot leave the console half-loaded.
    melonDS::Savestate backup;
    nds_->DoSavestate(&backup);
    backup.Finish();
    if (!nds_->DoSavestate(&st) || st.Error)
    {
        backup.Rewind(false);
        nds_->DoSavestate(&backup);
        return false;
    }
    return true;
}

uint32_t Session::Read32(uint32_t a) { return nds_ ? nds_->ARM9Read32(a) : 0; }
void Session::Write32(uint32_t a, uint32_t v) { if (nds_) nds_->ARM9Write32(a, v); }
uint16_t Session::Read16(uint32_t a) { return nds_ ? nds_->ARM9Read16(a) : 0; }
void Session::Write16(uint32_t a, uint16_t v) { if (nds_) nds_->ARM9Write16(a, v); }
uint8_t Session::Read8(uint32_t a) { return nds_ ? nds_->ARM9Read8(a) : 0; }
void Session::Write8(uint32_t a, uint8_t v) { if (nds_) nds_->ARM9Write8(a, v); }
uint32_t Session::Arm9PC() const { return nds_ ? nds_->GetPC(0) : 0; }

} // namespace sfr
