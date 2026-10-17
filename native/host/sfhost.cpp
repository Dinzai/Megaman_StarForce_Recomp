// SPDX-License-Identifier: GPL-3.0-or-later
// Headless test runner: boots a ROM through the same Session used on Android,
// replays a scripted input sequence, and writes screenshots (PPM) / audio stats.
//
//   sfhost ROM [--frames N] [--shot F1,F2,...] [--out DIR] [--input FILE]
//              [--replace PATH=FILE]... [--cheats FILE] [--save FILE] [--nojit]
//              [--state-test F]
//
// Input file: one event per line, "FRAME KEYS [HOLD]" where KEYS is a '+'
// separated list of A B X Y L R START SELECT UP DOWN LEFT RIGHT, or
// "FRAME TOUCH X Y [HOLD]". HOLD defaults to 6 frames.
// --watch LO:HI[:rw[=VALUE]] logs (optionally only accesses of VALUE); --watch-stack adds regs/stack;
// it logs ARM9 accesses in a range (forces the interpreter);
// --load-state FILE starts from a state; --save-state FILE writes one at the end (input
// frames then count from 1 again); --peek HEX prints an ARM9 word after the run; --dump ADDR:LEN (hex) saves memory; --state-test F saves a
// state at frame F, restores it at the end and writes after_loadstate.ppm.

#include "session.h"
#include "watch.h"

#include <chrono>
#include <cstdio>
#include <cstring>
#include <fstream>
#include <map>
#include <set>
#include <sstream>
#include <string>
#include <vector>

using namespace sfr;

static std::vector<uint8_t> readFile(const std::string& p)
{
    std::ifstream f(p, std::ios::binary);
    return std::vector<uint8_t>((std::istreambuf_iterator<char>(f)), std::istreambuf_iterator<char>());
}

struct Event { uint64_t frame; uint32_t keys; bool touch; int x, y; int hold; };

static uint32_t keyByName(const std::string& n)
{
    static const std::map<std::string, uint32_t> m = {
        {"A", KeyA}, {"B", KeyB}, {"X", KeyX}, {"Y", KeyY}, {"L", KeyL}, {"R", KeyR},
        {"START", KeyStart}, {"SELECT", KeySelect}, {"UP", KeyUp}, {"DOWN", KeyDown},
        {"LEFT", KeyLeft}, {"RIGHT", KeyRight}};
    auto it = m.find(n);
    return it == m.end() ? 0 : it->second;
}

static void writePPM(const std::string& path, const uint32_t* px)
{
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return;
    fprintf(f, "P6\n256 384\n255\n");
    for (int i = 0; i < 256 * 384; i++)
    {
        uint8_t rgb[3] = {(uint8_t)(px[i] >> 16), (uint8_t)(px[i] >> 8), (uint8_t)px[i]};
        fwrite(rgb, 1, 3, f);
    }
    fclose(f);
}

int main(int argc, char** argv)
{
    if (argc < 2)
    {
        fprintf(stderr, "usage: sfhost ROM [options]\n");
        return 2;
    }
    std::string romPath = argv[1], outDir = ".", inputPath;
    uint64_t frames = 600, stateTest = 0;
    std::vector<uint32_t> peeks;
    std::vector<std::pair<uint32_t,uint32_t>> dumps;
    std::string loadStatePath, saveStatePath;
    struct W { uint32_t lo, hi; bool r, w; bool bv; uint32_t v; };
    bool watchStack = false;
    std::vector<W> watches;
    size_t watchPrint = 400;
    std::set<uint64_t> shots;
    LaunchOptions opts;
    opts.threaded3D = false;
    for (int i = 2; i < argc; i++)
    {
        std::string a = argv[i];
        auto next = [&]() -> std::string { return i + 1 < argc ? argv[++i] : ""; };
        if (a == "--frames") frames = std::stoull(next());
        else if (a == "--out") outDir = next();
        else if (a == "--input") inputPath = next();
        else if (a == "--nojit") opts.jit = false;
        else if (a == "--threaded") opts.threaded3D = true;
        else if (a == "--state-test") stateTest = std::stoull(next());
        else if (a == "--save") opts.save = readFile(next());
        else if (a == "--verbose") Session::SetVerboseLog(true);
        else if (a == "--watch")
        {
            // LO:HI[:rw] hex, half-open; implies the interpreter
            std::string v = next();
            auto c1 = v.find(':'), c2 = v.find(':', c1 + 1);
            std::string mode = c2 == std::string::npos ? "rw" : v.substr(c2 + 1);
            // optional "=VALUE" suffix on the mode filters by the accessed value
            bool bv = false; uint32_t val = 0;
            auto eq = mode.find('=');
            if (eq != std::string::npos) { bv = true; val = (uint32_t)std::stoul(mode.substr(eq + 1), nullptr, 16); mode = mode.substr(0, eq); }
            watches.push_back({(uint32_t)std::stoul(v.substr(0, c1), nullptr, 16),
                               (uint32_t)std::stoul(v.substr(c1 + 1, c2 - c1 - 1), nullptr, 16),
                               mode.find('r') != std::string::npos, mode.find('w') != std::string::npos, bv, val});
            opts.instrument = true;
            opts.jit = false;
        }
        else if (a == "--watch-print") watchPrint = std::stoul(next());
        else if (a == "--watch-stack") watchStack = true;
        else if (a == "--load-state") loadStatePath = next();
        else if (a == "--save-state") saveStatePath = next();
        else if (a == "--peek") peeks.push_back((uint32_t)std::stoul(next(), nullptr, 16));
        else if (a == "--dump")
        {
            std::string v = next();  // ADDR:LEN in hex
            auto c = v.find(':');
            dumps.push_back({(uint32_t)std::stoul(v.substr(0, c), nullptr, 16), (uint32_t)std::stoul(v.substr(c + 1), nullptr, 16)});
        }
        else if (a == "--shot")
        {
            std::stringstream ss(next());
            std::string t;
            while (std::getline(ss, t, ',')) shots.insert(std::stoull(t));
        }
        else if (a == "--replace")
        {
            std::string kv = next();
            auto eq = kv.find('=');
            opts.files[kv.substr(0, eq)] = readFile(kv.substr(eq + 1));
        }
        else if (a == "--cheats")
        {
            auto raw = readFile(next());
            std::string err;
            CheatCode c{"cli", {}};
            if (!ParseCheatText(std::string(raw.begin(), raw.end()), c.words, err))
            {
                fprintf(stderr, "cheat parse error: %s\n", err.c_str());
                return 2;
            }
            opts.cheats.push_back(c);
        }
    }

    std::vector<Event> events;
    if (!inputPath.empty())
    {
        std::ifstream in(inputPath);
        std::string line;
        while (std::getline(in, line))
        {
            if (line.empty() || line[0] == '#') continue;
            std::stringstream ss(line);
            Event e{};
            std::string keys;
            ss >> e.frame >> keys;
            e.hold = 6;
            if (keys == "TOUCH")
            {
                e.touch = true;
                ss >> e.x >> e.y;
            }
            else
            {
                std::stringstream ks(keys);
                std::string k;
                while (std::getline(ks, k, '+')) e.keys |= keyByName(k);
            }
            int h;
            if (ss >> h) e.hold = h;
            events.push_back(e);
        }
    }

    Session::SetDataDir(outDir);
    Session s;
    std::string err;
    auto rom = readFile(romPath);
    if (rom.empty())
    {
        fprintf(stderr, "cannot read %s\n", romPath.c_str());
        return 1;
    }
    if (!s.Load(std::move(rom), std::move(opts), err))
    {
        fprintf(stderr, "load failed: %s\n", err.c_str());
        return 1;
    }
    for (auto& f : s.AppliedFiles()) printf("replaced %s\n", f.c_str());
    for (auto& w : watches) s.AddWatch(w.lo, w.hi, w.r, w.w, w.bv, w.v);
    if (!loadStatePath.empty())
    {
        auto st = readFile(loadStatePath);
        if (!s.LoadState(st)) { fprintf(stderr, "cannot load state %s\n", loadStatePath.c_str()); return 1; }
    }

    std::vector<uint32_t> px(256 * 384);
    std::vector<int16_t> audio(4096 * 2);
    uint64_t audioFrames = 0, nonSilent = 0;
    auto t0 = std::chrono::steady_clock::now();
    std::vector<uint8_t> state;
    for (uint64_t f = 1; f <= frames; f++)
    {
        uint32_t keys = 0;
        bool touch = false;
        int tx = 0, ty = 0;
        for (auto& e : events)
        {
            if (f >= e.frame && f < e.frame + (uint64_t)e.hold)
            {
                keys |= e.keys;
                if (e.touch) { touch = true; tx = e.x; ty = e.y; }
            }
        }
        s.SetInput(keys, touch, tx, ty);
        s.RunFrame();
        int n;
        while ((n = s.ReadAudio(audio.data(), 4096)) > 0)
        {
            audioFrames += n;
            for (int i = 0; i < n * 2; i++) if (audio[i] > 64 || audio[i] < -64) { nonSilent++; break; }
        }
        if (stateTest && f == stateTest)
        {
            state = s.SaveState();
            printf("savestate at %llu: %zu bytes\n", (unsigned long long)f, state.size());
        }
        if (shots.count(f))
        {
            s.CopyScreens(px.data());
            char name[64];
            snprintf(name, sizeof(name), "/frame_%06llu.ppm", (unsigned long long)f);
            writePPM(outDir + name, px.data());
        }
        if (s.Stopped())
        {
            printf("console stopped at frame %llu\n", (unsigned long long)f);
            break;
        }
    }
    double secs = std::chrono::duration<double>(std::chrono::steady_clock::now() - t0).count();
    printf("frames=%llu time=%.2fs fps=%.1f pc=%08X audioFrames=%llu audibleChunks=%llu saveBytes=%zu saveDirty=%d\n",
           (unsigned long long)s.FrameCount(), secs, s.FrameCount() / secs, s.Arm9PC(),
           (unsigned long long)audioFrames, (unsigned long long)nonSilent, s.SaveData().size(), (int)s.TakeSaveDirty());

    if (!watches.empty())
    {
        auto hits = s.TakeWatchHits();
        printf("watch: %zu hits\n", hits.size());
        for (size_t i = 0; i < hits.size() && i < watchPrint; i++)
        {
            auto& h = hits[i];
            printf("  f%-6llu %c%u pc=%08X lr=%08X addr=%08X val=%08X\n", (unsigned long long)h.frame,
                   h.write ? 'W' : 'R', h.size * 8, h.pc, h.lr, h.addr, h.value);
            if (watchStack)
            {
                printf("    regs:");
                for (int r = 0; r < 16; r++) printf(" %08X", h.regs[r]);
                printf("\n    stack:");
                for (int k = 0; k < 128; k++) printf(" %08X", h.stack[k]);
                printf("\n");
            }
        }
    }
    if (!saveStatePath.empty())
    {
        auto st = s.SaveState();
        FILE* f = fopen(saveStatePath.c_str(), "wb");
        if (f) { fwrite(st.data(), 1, st.size(), f); fclose(f); }
    }
    for (uint32_t a : peeks) printf("peek %08X = %08X\n", a, s.Read32(a));
    for (auto& d : dumps)
    {
        char name[64];
        snprintf(name, sizeof(name), "/dump_%08X.bin", d.first);
        FILE* f = fopen((outDir + name).c_str(), "wb");
        for (uint32_t i = 0; f && i < d.second; i += 2) { uint16_t v = s.Read16(d.first + i); fwrite(&v, 2, 1, f); }
        if (f) fclose(f);
    }
    if (!state.empty())
    {
        s.CopyScreens(px.data());
        std::vector<uint32_t> before = px;
        bool ok = s.LoadState(state);
        s.RunFrame();
        s.CopyScreens(px.data());
        writePPM(outDir + "/after_loadstate.ppm", px.data());
        printf("loadstate=%d\n", ok);
    }
    if (s.TakeSaveDirty() || true)
    {
        auto sv = s.SaveData();
        FILE* f = fopen((outDir + "/battery.sav").c_str(), "wb");
        if (f) { fwrite(sv.data(), 1, sv.size(), f); fclose(f); }
    }
    return 0;
}
