package io.dinzai.starforce;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * In-game voxel mod: draws the live DS screens as one extruded cube per pixel
 * (49,152 instances per screen, GLES 3.0). Height comes from the scene-depth map
 * (which DS layer drew the pixel, plus 3D depth), scaled by the visual mod's "depth". The top screen uses a tilted perspective
 * camera (drag to orbit); the bottom (touch) screen uses an oblique projection
 * whose ground plane stays aligned with the touch panel, so taps still land
 * where they look. The game itself is untouched; this is a renderer-side mod.
 */
final class VoxelRenderer implements GLSurfaceView.Renderer {
    private static final int W = NativeCore.SCREEN_W, H = NativeCore.SCREEN_H;

    private final IntBuffer frame = ByteBuffer.allocateDirect(W * H * 2 * 4).order(ByteOrder.nativeOrder()).asIntBuffer();
    private final ByteBuffer heights = ByteBuffer.allocateDirect(W * H * 2).order(ByteOrder.nativeOrder());
    private volatile boolean dirty = false;
    private volatile ScreenLayout layout;
    private volatile int surfaceH = 1;
    volatile float yaw = 0f, pitch = 28f;
    private final float depth;
    private final boolean grid;
    private final boolean voxelTop, voxelBottom;

    private int cubeProg, quadProg, cubeVao, quadVao, tex, heightTex;
    private final float[] proj = new float[16], view = new float[16], model = new float[16], mvp = new float[16], tmp = new float[16];

    interface FailureListener { void onFailure(String error); }
    private final FailureListener failure;
    private volatile boolean failed = false;

    VoxelRenderer(VisualMod mod, FailureListener failure) { this(mod, failure, true, true); }

    VoxelRenderer(VisualMod mod, FailureListener failure, boolean voxelTop, boolean voxelBottom) {
        this.failure = failure;
        this.voxelTop = voxelTop;
        this.voxelBottom = voxelBottom;
        // height texture is 0-1; a standing sprite (96/255) is ~13 pixels tall at the default depth 2.8
        depth = mod == null ? 34f : Math.max(4f, mod.depth * 12f);
        grid = false;  // per-pixel grid lines read as noise; outlines come from height edges instead
    }

    void setLayout(ScreenLayout l, int surfaceHeight) { layout = l; surfaceH = surfaceHeight; }

    /** Called from the emulation thread with 0xAARRGGBB pixels and 0-255 heights (top then bottom). */
    void submit(int[] pixels, byte[] heightMap) {
        synchronized (frame) {
            frame.position(0);
            frame.put(pixels, 0, W * H * 2);
            frame.position(0);
            if (heightMap != null) {
                heights.position(0);
                heights.put(heightMap, 0, W * H * 2);
                heights.position(0);
            }
            dirty = true;
        }
    }

    // Heights come from the native scene-depth map (DS layer + 3D depth), so the
    // relief is stable: floors stay flat, sprites stand up as solid figures and
    // 3D scenes get their real depth. Edges are outlined only where height changes.
    private static final String CUBE_VS =
            "#version 300 es\n" +
            "layout(location=0) in vec3 aPos;\n" +
            "layout(location=1) in vec3 aNormal;\n" +
            "uniform sampler2D uScreen;\n" +
            "uniform sampler2D uHeight;\n" +
            "uniform mat4 uMVP;\n" +
            "uniform float uDepth;\n" +
            "uniform int uRow;\n" +
            "out vec3 vColor; out vec2 vLocal; out float vTop; out vec4 vDrop;\n" +
            "float hAt(int x, int y){\n" +
            "  x = clamp(x, 0, 255); y = clamp(y, 0, 191);\n" +
            "  return texelFetch(uHeight, ivec2(x, y + uRow), 0).r * uDepth;\n" +
            "}\n" +
            "void main(){\n" +
            "  int x = gl_InstanceID % 256; int y = gl_InstanceID / 256;\n" +
            "  vec3 c = texelFetch(uScreen, ivec2(x, y + uRow), 0).bgr;\n" +   // ARGB ints arrive as BGRA bytes
            "  float h = hAt(x, y);\n" +
            "  // how far each neighbour drops below this column (left, right, up, down)\n" +
            "  vDrop = vec4(h - hAt(x - 1, y), h - hAt(x + 1, y), h - hAt(x, y - 1), h - hAt(x, y + 1));\n" +
            "  vec3 p = vec3(float(x) + aPos.x - 128.0, 96.0 - (float(y) + aPos.y), aPos.z * h);\n" +
            "  // key light from the upper left: top 1.0, left 0.9, up 0.8, right 0.62, down 0.5\n" +
            "  float shade = aNormal.z > 0.5 ? 1.0 : aNormal.x < -0.5 ? 0.9 : aNormal.y > 0.5 ? 0.8 : aNormal.x > 0.5 ? 0.62 : 0.5;\n" +
            "  vColor = c * shade; vLocal = aPos.xy; vTop = aNormal.z;\n" +
            "  gl_Position = uMVP * vec4(p, 1.0);\n" +
            "}\n";
    private static final String CUBE_FS =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "in vec3 vColor; in vec2 vLocal; in float vTop; in vec4 vDrop;\n" +
            "uniform float uGrid;\n" +
            "out vec4 o;\n" +
            "void main(){\n" +
            "  vec3 c = vColor;\n" +
            "  if (vTop > 0.5) {\n" +
            "    // outline the rim of raised regions; flat areas stay clean\n" +
            "    float e = 0.0, w = 0.16;\n" +
            "    if (vDrop.x > 1.0 && vLocal.x < w) e = 1.0;\n" +
            "    if (vDrop.y > 1.0 && vLocal.x > 1.0 - w) e = 1.0;\n" +
            "    if (vDrop.z > 1.0 && vLocal.y < w) e = 1.0;\n" +
            "    if (vDrop.w > 1.0 && vLocal.y > 1.0 - w) e = 1.0;\n" +
            "    c *= 1.0 - 0.45 * e;\n" +
            "    if (uGrid > 0.5) { vec2 g = min(vLocal, 1.0 - vLocal); if (min(g.x, g.y) < 0.04) c *= 0.88; }\n" +
            "  }\n" +
            "  o = vec4(c, 1.0);\n" +
            "}\n";
    private static final String QUAD_VS =
            "#version 300 es\n" +
            "layout(location=0) in vec2 aPos;\n" +
            "out vec2 vUV;\n" +
            "uniform float uV0;\n" +
            "void main(){ vUV = vec2(aPos.x, uV0 + (1.0 - aPos.y) * 0.5); gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0); }\n";
    private static final String QUAD_FS =
            "#version 300 es\n" +
            "precision mediump float;\n" +
            "in vec2 vUV; uniform sampler2D uScreen; out vec4 o;\n" +
            "void main(){ o = vec4(texture(uScreen, vUV).bgr, 1.0); }\n";

    @Override public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        try {
            setup();
        } catch (RuntimeException e) {
            failed = true;
            if (failure != null) failure.onFailure(e.getMessage() == null ? e.toString() : e.getMessage());
        }
    }

    private void setup() {
        cubeProg = program(CUBE_VS, CUBE_FS);
        quadProg = program(QUAD_VS, QUAD_FS);

        // Unit cube without the bottom face: 5 faces * 2 triangles * 3 vertices, position + normal.
        float[][] faces = {
                {0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 0, 0, 1},     // top (z=1)
                {0, 0, 0, 1, 0, 0, 1, 0, 1, 0, 0, 1, 0, -1, 0},    // y=0
                {0, 1, 0, 0, 1, 1, 1, 1, 1, 1, 1, 0, 0, 1, 0},     // y=1
                {0, 0, 0, 0, 0, 1, 0, 1, 1, 0, 1, 0, -1, 0, 0},    // x=0
                {1, 0, 0, 1, 1, 0, 1, 1, 1, 1, 0, 1, 1, 0, 0},     // x=1
        };
        FloatBuffer cube = floats(5 * 6 * 6);
        for (float[] f : faces) {
            int[] order = {0, 1, 2, 0, 2, 3};
            for (int k : order) {
                cube.put(f[k * 3]).put(f[k * 3 + 1]).put(f[k * 3 + 2]);
                cube.put(f[12]).put(f[13]).put(f[14]);
            }
        }
        cube.position(0);
        int[] ids = new int[2];
        GLES30.glGenVertexArrays(2, ids, 0);
        cubeVao = ids[0];
        quadVao = ids[1];
        int[] bufs = new int[2];
        GLES30.glGenBuffers(2, bufs, 0);
        GLES30.glBindVertexArray(cubeVao);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufs[0]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, cube.capacity() * 4, cube, GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 24, 0);
        GLES30.glEnableVertexAttribArray(1);
        GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 24, 12);

        FloatBuffer quad = floats(12);
        quad.put(new float[]{0, 0, 1, 0, 1, 1, 0, 0, 1, 1, 0, 1}).position(0);
        GLES30.glBindVertexArray(quadVao);
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, bufs[1]);
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 48, quad, GLES30.GL_STATIC_DRAW);
        GLES30.glEnableVertexAttribArray(0);
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0);
        GLES30.glBindVertexArray(0);

        int[] t = new int[1];
        GLES30.glGenTextures(1, t, 0);
        tex = t[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_RGBA8, W, H * 2);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        int[] ht = new int[1];
        GLES30.glGenTextures(1, ht, 0);
        heightTex = ht[0];
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, heightTex);
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_R8, W, H * 2);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_NEAREST);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_NEAREST);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        dirty = true;
    }

    @Override public void onSurfaceChanged(GL10 unused, int w, int h) { surfaceH = h; }

    @Override public void onDrawFrame(GL10 unused) {
        if (failed) {
            GLES30.glClearColor(0f, 0f, 0f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }
        if (dirty) {
            synchronized (frame) {
                frame.position(0);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, heightTex);
                GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1);
                heights.position(0);
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, W, H * 2, GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, heights);
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
                GLES30.glTexSubImage2D(GLES30.GL_TEXTURE_2D, 0, 0, 0, W, H * 2, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, frame);
                dirty = false;
            }
        }
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, heightTex);
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex);
        GLES30.glDisable(GLES30.GL_SCISSOR_TEST);
        GLES30.glClearColor(0.027f, 0.05f, 0.094f, 1f);
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT | GLES30.GL_DEPTH_BUFFER_BIT);
        ScreenLayout l = layout;
        if (l == null) return;

        if (ScreenLayout.visible(l.top)) {
            viewport(l.top);
            if (voxelTop) {
                perspective(l.top, pitch, yaw, mvp);
                drawVoxels(0, true);
            } else {
                drawFlat(0f);
            }
        }
        if (ScreenLayout.visible(l.bottom)) {
            viewport(l.bottom);
            if (voxelBottom) {
                perspective(l.bottom, BOTTOM_PITCH, 0f, mvp);
                synchronized (bottomMvp) { System.arraycopy(mvp, 0, bottomMvp, 0, 16); bottomRect = l.bottom.clone(); }
                drawVoxels(H, true);
            } else {
                drawFlat(0.5f);
            }
        }
        GLES30.glBindVertexArray(0);
    }

    static final float BOTTOM_PITCH = 34f;
    private final float[] bottomMvp = new float[16];
    private int[] bottomRect;

    /** Tilted perspective camera that keeps the whole DS screen in view. */
    private void perspective(int[] rect, float pitchDeg, float yawDeg, float[] out) {
        float aspect = (rect[2] - rect[0]) / (float) Math.max(1, rect[3] - rect[1]);
        Matrix.perspectiveM(proj, 0, 32f, aspect, 10f, 2000f);
        // pull back a little more as the tilt grows so the near edge stays on screen
        float dist = 330f + pitchDeg * 2.2f;
        Matrix.setLookAtM(view, 0, 0, -pitchDeg * 0.9f, dist, 0, -pitchDeg * 0.9f, 0, 0, 1, 0);
        Matrix.setIdentityM(model, 0);
        Matrix.rotateM(model, 0, -pitchDeg, 1, 0, 0);
        Matrix.rotateM(model, 0, yawDeg, 0, 1, 0);
        Matrix.multiplyMM(tmp, 0, view, 0, model, 0);
        Matrix.multiplyMM(out, 0, proj, 0, tmp, 0);
    }

    /**
     * Maps a touch on the surface to DS bottom-screen coordinates by casting a ray
     * through the tilted camera onto the floor plane (z = 0). Returns null when the
     * point misses the screen, or when the bottom screen isn't drawn in 3D.
     */
    boolean bottomIn3D() { return voxelBottom; }

    int[] unprojectBottom(float sx, float sy) {
        float[] m = new float[16], inv = new float[16];
        int[] r;
        synchronized (bottomMvp) { System.arraycopy(bottomMvp, 0, m, 0, 16); r = bottomRect; }
        if (!voxelBottom || r == null || !Matrix.invertM(inv, 0, m, 0)) return null;
        float nx = (sx - r[0]) / (r[2] - r[0]) * 2f - 1f;
        float ny = 1f - (sy - r[1]) / (r[3] - r[1]) * 2f;
        float[] a = new float[4], b = new float[4];
        Matrix.multiplyMV(a, 0, inv, 0, new float[]{nx, ny, -1f, 1f}, 0);
        Matrix.multiplyMV(b, 0, inv, 0, new float[]{nx, ny, 1f, 1f}, 0);
        for (int i = 0; i < 3; i++) { a[i] /= a[3]; b[i] /= b[3]; }
        float dz = b[2] - a[2];
        if (Math.abs(dz) < 1e-6f) return null;
        float t = -a[2] / dz;
        float wx = a[0] + (b[0] - a[0]) * t, wy = a[1] + (b[1] - a[1]) * t;
        int tx = (int) Math.floor(wx + 128f), ty = (int) Math.floor(96f - wy);
        if (tx < 0 || tx >= W || ty < 0 || ty >= H) return null;
        return new int[]{tx, ty};
    }

    /**
     * Oblique ("cabinet") projection: the ground plane maps 1:1 onto the viewport
     * and height shears tops up and to the right, exposing left and bottom faces.
     */
    private void obliqueMatrix(float[] m) {
        java.util.Arrays.fill(m, 0f);
        float zr = depth * 1.5f + 1f, shear = 0.45f;
        m[0] = 1f / 128f;           // x
        m[5] = 1f / 96f;            // y
        m[8] = shear / 128f;        // z contributes to x
        m[9] = shear / 96f;         // ... and to y
        m[10] = -1f / zr;           // taller = nearer
        m[15] = 1f;
    }

    private void drawVoxels(int row, boolean cull) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST);
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT);
        if (cull) {
            GLES30.glEnable(GLES30.GL_CULL_FACE);
            GLES30.glCullFace(GLES30.GL_BACK);
            GLES30.glFrontFace(GLES30.GL_CW);  // DS rows run downward, which mirrors y and flips winding
        }
        GLES30.glUseProgram(cubeProg);
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(cubeProg, "uMVP"), 1, false, mvp, 0);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(cubeProg, "uDepth"), depth);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(cubeProg, "uGrid"), grid ? 1f : 0f);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cubeProg, "uRow"), row);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cubeProg, "uScreen"), 0);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(cubeProg, "uHeight"), 1);
        GLES30.glBindVertexArray(cubeVao);
        GLES30.glDrawArraysInstanced(GLES30.GL_TRIANGLES, 0, 30, W * H);
        GLES30.glDisable(GLES30.GL_CULL_FACE);
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
    }

    private void drawFlat(float v0) {
        GLES30.glUseProgram(quadProg);
        GLES30.glUniform1i(GLES30.glGetUniformLocation(quadProg, "uScreen"), 0);
        GLES30.glUniform1f(GLES30.glGetUniformLocation(quadProg, "uV0"), v0);
        GLES30.glBindVertexArray(quadVao);
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 6);
    }

    /** Picks an ES3-capable RGB(A) config with a depth buffer, trying progressively simpler ones. */
    static final class ConfigChooser implements GLSurfaceView.EGLConfigChooser {
        private static final int EGL_OPENGL_ES3_BIT = 0x40;
        @Override public javax.microedition.khronos.egl.EGLConfig chooseConfig(javax.microedition.khronos.egl.EGL10 egl,
                                                                               javax.microedition.khronos.egl.EGLDisplay display) {
            int[][] wanted = {{8, 8, 8, 8, 24}, {8, 8, 8, 0, 24}, {8, 8, 8, 8, 16}, {8, 8, 8, 0, 16}, {5, 6, 5, 0, 16}};
            for (int[] w : wanted) {
                int[] attrs = {
                        javax.microedition.khronos.egl.EGL10.EGL_RED_SIZE, w[0],
                        javax.microedition.khronos.egl.EGL10.EGL_GREEN_SIZE, w[1],
                        javax.microedition.khronos.egl.EGL10.EGL_BLUE_SIZE, w[2],
                        javax.microedition.khronos.egl.EGL10.EGL_ALPHA_SIZE, w[3],
                        javax.microedition.khronos.egl.EGL10.EGL_DEPTH_SIZE, w[4],
                        javax.microedition.khronos.egl.EGL10.EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT,
                        javax.microedition.khronos.egl.EGL10.EGL_NONE};
                javax.microedition.khronos.egl.EGLConfig[] out = new javax.microedition.khronos.egl.EGLConfig[1];
                int[] n = new int[1];
                if (egl.eglChooseConfig(display, attrs, out, 1, n) && n[0] > 0) return out[0];
            }
            throw new IllegalArgumentException("No OpenGL ES 3 configuration");
        }
    }

    private void viewport(int[] r) {
        // GL's origin is bottom-left; layout rects are top-left based.
        GLES30.glViewport(r[0], surfaceH - r[3], r[2] - r[0], r[3] - r[1]);
    }

    private static FloatBuffer floats(int n) {
        return ByteBuffer.allocateDirect(n * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
    }

    private static int shader(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("Voxel shader: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }

    private static int program(String vs, String fs) {
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs));
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs));
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("Voxel program: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }
}
