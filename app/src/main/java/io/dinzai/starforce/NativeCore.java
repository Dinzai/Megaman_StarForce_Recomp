package io.dinzai.starforce;

/**
 * Thin JNI wrapper over the native runtime (melonDS core + Star Force session layer).
 * Every method except {@link #create} must be called from the emulation thread.
 */
final class NativeCore implements AutoCloseable {
    static { System.loadLibrary("sfrcore"); }

    static final int SCREEN_W = 256, SCREEN_H = 192;
    static final int KEY_A = 1, KEY_B = 1 << 1, KEY_SELECT = 1 << 2, KEY_START = 1 << 3,
            KEY_RIGHT = 1 << 4, KEY_LEFT = 1 << 5, KEY_UP = 1 << 6, KEY_DOWN = 1 << 7,
            KEY_R = 1 << 8, KEY_L = 1 << 9, KEY_X = 1 << 10, KEY_Y = 1 << 11;

    private long handle;

    private NativeCore(long handle) { this.handle = handle; }

    static NativeCore create(String dataDir) { return new NativeCore(nCreate(dataDir)); }

    /** @return null on success, otherwise a readable error. */
    String load(String romPath, String savePath, LaunchConfig config, boolean jit, boolean threaded3D, int audioRate) {
        return nLoad(handle, romPath, savePath, config.fileKeys(), config.filePaths(),
                config.cheatNames(), config.cheatCodes(), jit, threaded3D, audioRate);
    }

    /** @return audio frames written, or -1 if the emulated console powered off. */
    int runFrame(int keys, boolean touching, int x, int y, int[] pixels, short[] audio) {
        return nRunFrame(handle, keys, touching, x, y, pixels, audio);
    }

    byte[] takeDirtySave() { return nTakeDirtySave(handle); }
    byte[] saveState() { return nSaveState(handle); }
    boolean loadState(byte[] state) { return nLoadState(handle, state); }
    int read32(int address) { return nRead32(handle, address); }
    void write32(int address, int value) { nWrite32(handle, address, value); }

    @Override public void close() {
        if (handle != 0) { nDestroy(handle); handle = 0; }
    }

    private static native long nCreate(String dataDir);
    private static native void nDestroy(long handle);
    private static native String nLoad(long handle, String romPath, String savePath,
            String[] fileKeys, String[] filePaths, String[] cheatNames, String[] cheatCodes,
            boolean jit, boolean threaded3D, int audioRate);
    private static native int nRunFrame(long handle, int keys, boolean touching, int x, int y, int[] pixels, short[] audio);
    private static native byte[] nTakeDirtySave(long handle);
    private static native byte[] nSaveState(long handle);
    private static native boolean nLoadState(long handle, byte[] state);
    private static native int nRead32(long handle, int address);
    private static native void nWrite32(long handle, int address, int value);
}
