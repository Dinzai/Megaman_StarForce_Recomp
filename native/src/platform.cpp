// SPDX-License-Identifier: GPL-3.0-or-later
// melonDS Platform layer for the Star Force runtime (Android + host builds).
// Only the services Leo needs are real; multiplayer, camera, addons and DSi
// AAC are stubbed because a retail DS game never reaches them.

#include "Platform.h"
#include "SPI_Firmware.h"
#include "session.h"

#include <chrono>
#include <condition_variable>
#include <cstdarg>
#include <cstdio>
#include <mutex>
#include <thread>
#include <sys/stat.h>
#include <dlfcn.h>

#ifdef __ANDROID__
#include <android/log.h>
#endif

namespace melonDS::Platform
{

void SignalStop(StopReason reason, void* userdata)
{
    if (auto* s = static_cast<sfr::Session*>(userdata)) s->OnStop(static_cast<int>(reason));
}

static const char* ModeString(FileMode mode)
{
    // Text/binary makes no difference on Linux/Android.
    if (mode & FileMode::Append) return (mode & FileMode::Read) ? "a+b" : "ab";
    bool rd = mode & FileMode::Read, wr = mode & FileMode::Write;
    bool preserve = mode & FileMode::Preserve;
    if (rd && wr) return preserve ? "r+b" : "w+b";
    if (wr) return preserve ? "r+b" : "wb";
    return "rb";
}

std::string GetLocalFilePath(const std::string& filename)
{
    return sfr::Session::DataDir() + "/" + filename;
}

FileHandle* OpenFile(const std::string& path, FileMode mode)
{
    if ((mode & FileMode::ReadWrite) == FileMode::None) return nullptr;
    bool exists = FileExists(path);
    if ((mode & FileMode::NoCreate) && !exists) return nullptr;
    if ((mode & FileMode::Write) && (mode & FileMode::Preserve) && !exists)
    {
        // "r+b" cannot create; create it first.
        if (FILE* f = fopen(path.c_str(), "wb")) fclose(f);
    }
    return reinterpret_cast<FileHandle*>(fopen(path.c_str(), ModeString(mode)));
}

FileHandle* OpenLocalFile(const std::string& path, FileMode mode)
{
    if (!path.empty() && path[0] == '/') return OpenFile(path, mode);
    return OpenFile(GetLocalFilePath(path), mode);
}

bool FileExists(const std::string& name)
{
    struct stat st;
    return stat(name.c_str(), &st) == 0 && S_ISREG(st.st_mode);
}

bool LocalFileExists(const std::string& name) { return FileExists(GetLocalFilePath(name)); }

bool CheckFileWritable(const std::string& filepath)
{
    FILE* f = fopen(filepath.c_str(), "ab");
    if (!f) return false;
    fclose(f);
    return true;
}

bool CheckLocalFileWritable(const std::string& filepath) { return CheckFileWritable(GetLocalFilePath(filepath)); }

static FILE* F(FileHandle* h) { return reinterpret_cast<FILE*>(h); }

bool CloseFile(FileHandle* file) { return fclose(F(file)) == 0; }
bool IsEndOfFile(FileHandle* file) { return feof(F(file)) != 0; }
bool FileReadLine(char* str, int count, FileHandle* file) { return fgets(str, count, F(file)) != nullptr; }
u64 FilePosition(FileHandle* file) { return (u64)ftello(F(file)); }

bool FileSeek(FileHandle* file, s64 offset, FileSeekOrigin origin)
{
    int whence = origin == FileSeekOrigin::Start ? SEEK_SET : origin == FileSeekOrigin::Current ? SEEK_CUR : SEEK_END;
    return fseeko(F(file), offset, whence) == 0;
}

void FileRewind(FileHandle* file) { rewind(F(file)); }
u64 FileRead(void* data, u64 size, u64 count, FileHandle* file) { return fread(data, size, count, F(file)); }
bool FileFlush(FileHandle* file) { return fflush(F(file)) == 0; }
u64 FileWrite(const void* data, u64 size, u64 count, FileHandle* file) { return fwrite(data, size, count, F(file)); }

u64 FileWriteFormatted(FileHandle* file, const char* fmt, ...)
{
    va_list args;
    va_start(args, fmt);
    int r = vfprintf(F(file), fmt, args);
    va_end(args);
    return r < 0 ? 0 : (u64)r;
}

u64 FileLength(FileHandle* file)
{
    FILE* f = F(file);
    off_t pos = ftello(f);
    fseeko(f, 0, SEEK_END);
    off_t len = ftello(f);
    fseeko(f, pos, SEEK_SET);
    return (u64)len;
}

void Log(LogLevel level, const char* fmt, ...)
{
    if (level == LogLevel::Debug && !sfr::Session::VerboseLog()) return;
    va_list args;
    va_start(args, fmt);
#ifdef __ANDROID__
    int prio = level == LogLevel::Error ? ANDROID_LOG_ERROR : level == LogLevel::Warn ? ANDROID_LOG_WARN
             : level == LogLevel::Info ? ANDROID_LOG_INFO : ANDROID_LOG_DEBUG;
    __android_log_vprint(prio, "sfr-core", fmt, args);
#else
    vfprintf(stderr, fmt, args);
#endif
    va_end(args);
}

struct Thread { std::thread t; };

Thread* Thread_Create(std::function<void()> func) { return new Thread{std::thread(std::move(func))}; }
void Thread_Free(Thread* thread)
{
    if (thread->t.joinable()) thread->t.detach();
    delete thread;
}
void Thread_Wait(Thread* thread) { if (thread->t.joinable()) thread->t.join(); }

struct Semaphore
{
    std::mutex m;
    std::condition_variable cv;
    int count = 0;
};

Semaphore* Semaphore_Create() { return new Semaphore(); }
void Semaphore_Free(Semaphore* sema) { delete sema; }
void Semaphore_Reset(Semaphore* sema)
{
    std::lock_guard<std::mutex> lk(sema->m);
    sema->count = 0;
}
void Semaphore_Wait(Semaphore* sema)
{
    std::unique_lock<std::mutex> lk(sema->m);
    sema->cv.wait(lk, [sema] { return sema->count > 0; });
    sema->count--;
}
bool Semaphore_TryWait(Semaphore* sema, int timeout_ms)
{
    std::unique_lock<std::mutex> lk(sema->m);
    if (timeout_ms == 0)
    {
        if (sema->count <= 0) return false;
    }
    else if (!sema->cv.wait_for(lk, std::chrono::milliseconds(timeout_ms), [sema] { return sema->count > 0; }))
    {
        return false;
    }
    sema->count--;
    return true;
}
void Semaphore_Post(Semaphore* sema, int count)
{
    {
        std::lock_guard<std::mutex> lk(sema->m);
        sema->count += count;
    }
    if (count == 1) sema->cv.notify_one(); else sema->cv.notify_all();
}

struct Mutex { std::mutex m; };
Mutex* Mutex_Create() { return new Mutex(); }
void Mutex_Free(Mutex* mutex) { delete mutex; }
void Mutex_Lock(Mutex* mutex) { mutex->m.lock(); }
void Mutex_Unlock(Mutex* mutex) { mutex->m.unlock(); }
bool Mutex_TryLock(Mutex* mutex) { return mutex->m.try_lock(); }

void Sleep(u64 usecs) { std::this_thread::sleep_for(std::chrono::microseconds(usecs)); }

u64 GetMSCount()
{
    using namespace std::chrono;
    return duration_cast<milliseconds>(steady_clock::now().time_since_epoch()).count();
}

u64 GetUSCount()
{
    using namespace std::chrono;
    return duration_cast<microseconds>(steady_clock::now().time_since_epoch()).count();
}

void WriteNDSSave(const u8* savedata, u32 savelen, u32 writeoffset, u32 writelen, void* userdata)
{
    if (auto* s = static_cast<sfr::Session*>(userdata)) s->OnSaveWritten(savedata, savelen, writeoffset, writelen);
}

void WriteGBASave(const u8*, u32, u32, u32, void*) {}
void WriteFirmware(const Firmware&, u32, u32, void*) {}
void WriteDateTime(int, int, int, int, int, int, void*) {}

// Local multiplayer and Wi-Fi are out of scope for the single-player runtime.
void MP_Begin(void*) {}
void MP_End(void*) {}
int MP_SendPacket(u8*, int, u64, void*) { return 0; }
int MP_RecvPacket(u8*, u64*, void*) { return 0; }
int MP_SendCmd(u8*, int, u64, void*) { return 0; }
int MP_SendReply(u8*, int, u64, u16, void*) { return 0; }
int MP_SendAck(u8*, int, u64, void*) { return 0; }
int MP_RecvHostPacket(u8*, u64*, void*) { return 0; }
u16 MP_RecvReplies(u8*, u64, u16, void*) { return 0; }
int Net_SendPacket(u8*, int, void*) { return 0; }
int Net_RecvPacket(u8*, void*) { return 0; }

void Camera_Start(int, void*) {}
void Camera_Stop(int, void*) {}
void Camera_CaptureFrame(int, u32*, int, int, bool, void*) {}

void Mic_Start(void*) {}
void Mic_Stop(void*) {}
int Mic_ReadInput(s16*, int, void*) { return 0; }

AACDecoder* AAC_Init() { return nullptr; }
void AAC_DeInit(AACDecoder*) {}
bool AAC_Configure(AACDecoder*, int, int) { return false; }
bool AAC_DecodeFrame(AACDecoder*, const void*, int, void*, int) { return false; }

bool Addon_KeyDown(KeyType, void*) { return false; }
void Addon_RumbleStart(u32, void*) {}
void Addon_RumbleStop(void*) {}
float Addon_MotionQuery(MotionQueryType, void*) { return 0.f; }

DynamicLibrary* DynamicLibrary_Load(const char* lib) { return reinterpret_cast<DynamicLibrary*>(dlopen(lib, RTLD_NOW | RTLD_LOCAL)); }
void DynamicLibrary_Unload(DynamicLibrary* lib) { if (lib) dlclose(lib); }
void* DynamicLibrary_LoadFunction(DynamicLibrary* lib, const char* name) { return lib ? dlsym(lib, name) : nullptr; }

} // namespace melonDS::Platform
