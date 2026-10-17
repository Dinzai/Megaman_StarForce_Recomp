// SPDX-License-Identifier: GPL-3.0-or-later
// JNI surface for io.dinzai.starforce.NativeCore. One call per frame keeps the
// Java/native crossing cheap: input in, pixels + audio out.
#include <jni.h>

#include "session.h"

#include <cstdio>
#include <string>
#include <vector>

using sfr::Session;

namespace
{

std::string str(JNIEnv* env, jstring s)
{
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c ? c : "");
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

bool readFile(const std::string& path, std::vector<uint8_t>& out)
{
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseeko(f, 0, SEEK_END);
    off_t len = ftello(f);
    fseeko(f, 0, SEEK_SET);
    if (len < 0 || len > (off_t)512 * 1024 * 1024) { fclose(f); return false; }
    out.resize((size_t)len);
    bool ok = len == 0 || fread(out.data(), 1, (size_t)len, f) == (size_t)len;
    fclose(f);
    return ok;
}

jbyteArray toArray(JNIEnv* env, const std::vector<uint8_t>& v)
{
    jbyteArray a = env->NewByteArray((jsize)v.size());
    if (a && !v.empty()) env->SetByteArrayRegion(a, 0, (jsize)v.size(), reinterpret_cast<const jbyte*>(v.data()));
    return a;
}

Session* S(jlong h) { return reinterpret_cast<Session*>(h); }

} // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_io_dinzai_starforce_NativeCore_nCreate(JNIEnv* env, jclass, jstring dataDir)
{
    Session::SetDataDir(str(env, dataDir));
    return reinterpret_cast<jlong>(new Session());
}

JNIEXPORT void JNICALL Java_io_dinzai_starforce_NativeCore_nDestroy(JNIEnv*, jclass, jlong h)
{
    delete S(h);
}

// Returns null on success or an error message.
JNIEXPORT jstring JNICALL Java_io_dinzai_starforce_NativeCore_nLoad(JNIEnv* env, jclass, jlong h,
    jstring romPath, jstring savePath, jobjectArray fileKeys, jobjectArray filePaths,
    jobjectArray cheatNames, jobjectArray cheatCodes, jboolean jit, jboolean threaded3D, jint audioRate)
{
    sfr::LaunchOptions opts;
    opts.jit = jit;
    opts.threaded3D = threaded3D;
    opts.audioRate = audioRate;

    std::vector<uint8_t> rom;
    if (!readFile(str(env, romPath), rom)) return env->NewStringUTF("cannot read the imported ROM");
    std::string sp = str(env, savePath);
    if (!sp.empty()) readFile(sp, opts.save);  // a missing save is normal on first boot

    jsize nf = fileKeys ? env->GetArrayLength(fileKeys) : 0;
    for (jsize i = 0; i < nf; i++)
    {
        auto k = str(env, (jstring)env->GetObjectArrayElement(fileKeys, i));
        auto p = str(env, (jstring)env->GetObjectArrayElement(filePaths, i));
        std::vector<uint8_t> data;
        if (!readFile(p, data)) return env->NewStringUTF(("cannot read mod file for " + k).c_str());
        opts.files[k] = std::move(data);
    }

    jsize nc = cheatNames ? env->GetArrayLength(cheatNames) : 0;
    for (jsize i = 0; i < nc; i++)
    {
        sfr::CheatCode c;
        c.name = str(env, (jstring)env->GetObjectArrayElement(cheatNames, i));
        std::string err;
        if (!sfr::ParseCheatText(str(env, (jstring)env->GetObjectArrayElement(cheatCodes, i)), c.words, err))
            return env->NewStringUTF(("cheat '" + c.name + "': " + err).c_str());
        opts.cheats.push_back(std::move(c));
    }

    std::string error;
    if (!S(h)->Load(std::move(rom), std::move(opts), error)) return env->NewStringUTF(error.c_str());
    return nullptr;
}

// Runs one frame. pixels: int[256*384] ARGB (top then bottom). audio: short[]
// interleaved stereo. Returns audio frames written, or -1 if the console stopped.
JNIEXPORT jint JNICALL Java_io_dinzai_starforce_NativeCore_nRunFrame(JNIEnv* env, jclass, jlong h,
    jint keys, jboolean touching, jint tx, jint ty, jintArray pixels, jshortArray audio)
{
    Session* s = S(h);
    s->SetInput((uint32_t)keys, touching, tx, ty);
    s->RunFrame();
    if (s->Stopped()) return -1;

    if (pixels)
    {
        jint* px = env->GetIntArrayElements(pixels, nullptr);
        s->CopyScreens(reinterpret_cast<uint32_t*>(px));
        env->ReleaseIntArrayElements(pixels, px, 0);
    }
    int written = 0;
    if (audio)
    {
        jsize cap = env->GetArrayLength(audio) / 2;
        jshort* a = env->GetShortArrayElements(audio, nullptr);
        while (written < cap)
        {
            int n = s->ReadAudio(reinterpret_cast<int16_t*>(a) + written * 2, cap - written);
            if (n <= 0) break;
            written += n;
        }
        env->ReleaseShortArrayElements(audio, a, 0);
    }
    return written;
}

// Returns the battery save if it changed since the last call, else null.
JNIEXPORT jbyteArray JNICALL Java_io_dinzai_starforce_NativeCore_nTakeDirtySave(JNIEnv* env, jclass, jlong h)
{
    if (!S(h)->TakeSaveDirty()) return nullptr;
    return toArray(env, S(h)->SaveData());
}

JNIEXPORT jbyteArray JNICALL Java_io_dinzai_starforce_NativeCore_nSaveState(JNIEnv* env, jclass, jlong h)
{
    auto st = S(h)->SaveState();
    return st.empty() ? nullptr : toArray(env, st);
}

JNIEXPORT jboolean JNICALL Java_io_dinzai_starforce_NativeCore_nLoadState(JNIEnv* env, jclass, jlong h, jbyteArray state)
{
    if (!state) return JNI_FALSE;
    jsize n = env->GetArrayLength(state);
    std::vector<uint8_t> v((size_t)n);
    env->GetByteArrayRegion(state, 0, n, reinterpret_cast<jbyte*>(v.data()));
    return S(h)->LoadState(v) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jint JNICALL Java_io_dinzai_starforce_NativeCore_nRead32(JNIEnv*, jclass, jlong h, jint addr)
{
    return (jint)S(h)->Read32((uint32_t)addr);
}

JNIEXPORT void JNICALL Java_io_dinzai_starforce_NativeCore_nWrite32(JNIEnv*, jclass, jlong h, jint addr, jint value)
{
    S(h)->Write32((uint32_t)addr, (uint32_t)value);
}

} // extern "C"
