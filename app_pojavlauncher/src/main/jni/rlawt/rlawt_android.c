// RuneLite Mobile GPU mode: offscreen replacement for RuneLite's rlawt.
// Creates an EGL (GLES3) pbuffer context through MobileGlues (desktop GL on GLES), renders the
// GPU plugin into an FBO and reads frames back asynchronously (2 PBOs, one frame latency)
// into a Java int[] (0x00RRGGBB, top-down) that the Java side draws onto the AWT canvas.
#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "rlawt", __VA_ARGS__)

static void *mg;
static EGLDisplay dpy = EGL_NO_DISPLAY; static EGLContext ctx = EGL_NO_CONTEXT; static EGLSurface surf = EGL_NO_SURFACE;
static GLuint fbo, rbColor, rbDepth, pbo[2]; static int fw, fh, pboW[2], pboH[2], cur, frames;

#define F(ret, name, args) static ret (*p_##name) args;
F(EGLDisplay, eglGetDisplay, (EGLNativeDisplayType)) F(EGLBoolean, eglInitialize, (EGLDisplay, EGLint*, EGLint*))
F(EGLBoolean, eglBindAPI, (EGLenum)) F(EGLBoolean, eglChooseConfig, (EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*))
F(EGLContext, eglCreateContext, (EGLDisplay, EGLConfig, EGLContext, const EGLint*))
F(EGLSurface, eglCreatePbufferSurface, (EGLDisplay, EGLConfig, const EGLint*))
F(EGLBoolean, eglMakeCurrent, (EGLDisplay, EGLSurface, EGLSurface, EGLContext)) F(EGLint, eglGetError, (void))
F(EGLBoolean, eglDestroyContext, (EGLDisplay, EGLContext)) F(EGLBoolean, eglDestroySurface, (EGLDisplay, EGLSurface))
F(EGLBoolean, eglSwapInterval, (EGLDisplay, EGLint))
F(const GLubyte*, glGetString, (GLenum)) F(void, glGetIntegerv, (GLenum, GLint*)) F(GLenum, glGetError, (void))
F(void, glGenFramebuffers, (GLsizei, GLuint*)) F(void, glBindFramebuffer, (GLenum, GLuint))
F(void, glGenRenderbuffers, (GLsizei, GLuint*)) F(void, glBindRenderbuffer, (GLenum, GLuint))
F(void, glRenderbufferStorage, (GLenum, GLenum, GLsizei, GLsizei))
F(void, glFramebufferRenderbuffer, (GLenum, GLenum, GLenum, GLuint)) F(GLenum, glCheckFramebufferStatus, (GLenum))
F(void, glDeleteFramebuffers, (GLsizei, const GLuint*)) F(void, glDeleteRenderbuffers, (GLsizei, const GLuint*))
F(void, glGenBuffers, (GLsizei, GLuint*)) F(void, glBindBuffer, (GLenum, GLuint))
F(void, glBufferData, (GLenum, GLsizeiptr, const void*, GLenum))
F(void*, glMapBufferRange, (GLenum, GLintptr, GLsizeiptr, GLbitfield)) F(GLboolean, glUnmapBuffer, (GLenum))
F(void, glReadPixels, (GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, void*)) F(void, glFlush, (void))
F(void, glRenderbufferStorageMultisample, (GLenum, GLsizei, GLenum, GLsizei, GLsizei))
F(void*, glXGetProcAddress, (const GLubyte*))
#undef F
#define L(name) if (!(p_##name = dlsym(mg, #name))) { LOG("missing %s", #name); return JNI_FALSE; }

// LWJGL loads this library as its "libGL" (org.lwjgl.opengl.libname) and resolves every GL
// function through glXGetProcAddress below, which forwards to MobileGlues except for calls that
// need fixing up for GLES. GLES only accepts sized internal formats for renderbuffers, but the GPU
// plugin allocates its MSAA scene buffer as GL_RGBA, which made the FBO incomplete (0x8CD6) and
// crashed the client when the first scene was drawn.
static GLenum sizedFormat(GLenum f) {
    switch (f) {
        case GL_RGBA: return GL_RGBA8;
        case GL_RGB: return GL_RGB8;
        case GL_DEPTH_COMPONENT: return GL_DEPTH_COMPONENT24;
        case GL_DEPTH_STENCIL: return GL_DEPTH24_STENCIL8;
        default: return f;
    }
}
static void w_glRenderbufferStorage(GLenum t, GLenum f, GLsizei w, GLsizei h) { p_glRenderbufferStorage(t, sizedFormat(f), w, h); }
static void w_glRenderbufferStorageMultisample(GLenum t, GLsizei s, GLenum f, GLsizei w, GLsizei h) {
    p_glRenderbufferStorageMultisample(t, s, sizedFormat(f), w, h);
}
// LWJGL may reach this library through a different load than the System.load()+nInit() one (seen
// on the OnePlus Nord / Android 12: every lookup came back NULL -> "Core OpenGL functions could not
// be found"), so never depend on nInit() having run in this instance: open MobileGlues lazily
// (path from RLAWT_MG_LIB, set by the launcher) and fall back to a global symbol lookup.
static void *mgHandle(void) {
    if (!mg) {
        const char *p = getenv("RLAWT_MG_LIB");
        mg = dlopen(p && *p ? p : "libmobileglues.so", RTLD_NOW | RTLD_GLOBAL);
        LOG("glXGetProcAddress: lazy MobileGlues open %s -> %p", p ? p : "libmobileglues.so", mg);
        fprintf(stderr, "rlawt: lazy MobileGlues open -> %p\n", mg);
    }
    if (mg && !p_glXGetProcAddress) p_glXGetProcAddress = dlsym(mg, "glXGetProcAddress");
    return mg;
}
static int misses;
__attribute__((visibility("default"))) void *glXGetProcAddress(const GLubyte *name) {
    const char *n = (const char *) name;
    if (!strcmp(n, "glRenderbufferStorage") || !strcmp(n, "glRenderbufferStorageMultisample")) {
        // wrappers call through p_*: make sure they're resolved in this instance too
        if (!p_glRenderbufferStorage && mgHandle()) {
            p_glRenderbufferStorage = dlsym(mg, "glRenderbufferStorage");
            p_glRenderbufferStorageMultisample = dlsym(mg, "glRenderbufferStorageMultisample");
        }
        if (p_glRenderbufferStorage) return !strcmp(n, "glRenderbufferStorage") ? (void *) w_glRenderbufferStorage : (void *) w_glRenderbufferStorageMultisample;
    }
    void *h = mgHandle(), *f = NULL;
    if (h && p_glXGetProcAddress) f = p_glXGetProcAddress(name);
    if (!f && h) f = dlsym(h, n);
    if (!f) f = dlsym(RTLD_DEFAULT, n);
    if (!f && misses++ < 8) {
        LOG("glXGetProcAddress(%s) = NULL (mg=%p)", n, mg);
        fprintf(stderr, "rlawt: glXGetProcAddress(%s) = NULL (mg=%p)\n", n, mg);
    }
    return f;
}
__attribute__((visibility("default"))) void *glXGetProcAddressARB(const GLubyte *name) { return glXGetProcAddress(name); }

#define JF(name) Java_net_runelite_rlawt_AWTContext_##name

JNIEXPORT jstring JNICALL JF(nProbe)(JNIEnv *env, jclass c) {
    char b[200];
    snprintf(b, sizeof b, "mg=%p glGetError=%p glGetString=%p glGetIntegerv=%p", mg,
        glXGetProcAddress((const GLubyte *) "glGetError"), glXGetProcAddress((const GLubyte *) "glGetString"),
        glXGetProcAddress((const GLubyte *) "glGetIntegerv"));
    return (*env)->NewStringUTF(env, b);
}

JNIEXPORT jboolean JNICALL JF(nInit)(JNIEnv *env, jclass c, jstring lib) {
    if (mg) return JNI_TRUE;
    const char *path = (*env)->GetStringUTFChars(env, lib, 0);
    mg = dlopen(path, RTLD_NOW | RTLD_GLOBAL);
    if (!mg) LOG("dlopen %s: %s", path, dlerror());
    (*env)->ReleaseStringUTFChars(env, lib, path);
    if (!mg) return JNI_FALSE;
    L(eglGetDisplay) L(eglInitialize) L(eglBindAPI) L(eglChooseConfig) L(eglCreateContext) L(eglCreatePbufferSurface)
    L(eglMakeCurrent) L(eglGetError) L(eglDestroyContext) L(eglDestroySurface) L(eglSwapInterval)
    L(glGetString) L(glGetIntegerv) L(glGetError) L(glGenFramebuffers) L(glBindFramebuffer) L(glGenRenderbuffers)
    L(glBindRenderbuffer) L(glRenderbufferStorage) L(glFramebufferRenderbuffer) L(glCheckFramebufferStatus)
    L(glDeleteFramebuffers) L(glDeleteRenderbuffers) L(glGenBuffers) L(glBindBuffer) L(glBufferData)
    L(glMapBufferRange) L(glUnmapBuffer) L(glReadPixels) L(glFlush)
    L(glRenderbufferStorageMultisample) L(glXGetProcAddress)
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL JF(nCreate)(JNIEnv *env, jclass c) {
    char err[160];
    dpy = p_eglGetDisplay(EGL_DEFAULT_DISPLAY);
    EGLint maj, min;
    if (!p_eglInitialize(dpy, &maj, &min)) { snprintf(err, sizeof err, "eglInitialize 0x%x", p_eglGetError()); return (*env)->NewStringUTF(env, err); }
    p_eglBindAPI(EGL_OPENGL_ES_API);
    const EGLint attrs[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, 0x40 /*ES3*/,
        EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
    EGLConfig cfg; EGLint n = 0;
    if (!p_eglChooseConfig(dpy, attrs, &cfg, 1, &n) || n < 1) { snprintf(err, sizeof err, "eglChooseConfig 0x%x", p_eglGetError()); return (*env)->NewStringUTF(env, err); }
    const EGLint cattrs[] = { EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE };
    ctx = p_eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, cattrs);
    if (ctx == EGL_NO_CONTEXT) { snprintf(err, sizeof err, "eglCreateContext 0x%x", p_eglGetError()); return (*env)->NewStringUTF(env, err); }
    const EGLint sattrs[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
    surf = p_eglCreatePbufferSurface(dpy, cfg, sattrs);
    if (!p_eglMakeCurrent(dpy, surf, surf, ctx)) { snprintf(err, sizeof err, "eglMakeCurrent 0x%x", p_eglGetError()); return (*env)->NewStringUTF(env, err); }
    GLint compute = -1; p_glGetIntegerv(0x90EB /*GL_MAX_COMPUTE_WORK_GROUP_INVOCATIONS*/, &compute);
    GLint e = p_glGetError();
    LOG("EGL %d.%d GL_VERSION=%s GL_RENDERER=%s GLSL=%s", maj, min, p_glGetString(GL_VERSION), p_glGetString(GL_RENDERER), p_glGetString(GL_SHADING_LANGUAGE_VERSION));
    snprintf(err, sizeof err, "OK|%s|%s|%s|compute_invocations=%d%s", p_glGetString(GL_VERSION), p_glGetString(GL_RENDERER),
        p_glGetString(GL_SHADING_LANGUAGE_VERSION), compute, e ? " (glGetIntegerv error)" : "");
    return (*env)->NewStringUTF(env, err);
}

JNIEXPORT void JNICALL JF(nMakeCurrent)(JNIEnv *env, jclass c, jboolean on) {
    if (on) p_eglMakeCurrent(dpy, surf, surf, ctx); else p_eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

JNIEXPORT jint JNICALL JF(nSwapInterval)(JNIEnv *env, jclass c, jint i) { return p_eglSwapInterval(dpy, i) ? 0 : -1; }

JNIEXPORT jint JNICALL JF(nFramebuffer)(JNIEnv *env, jclass c, jint w, jint h) {
    if (w < 1) w = 1; if (h < 1) h = 1;
    if (fbo && w == fw && h == fh) return (jint) fbo;
    GLint prevDraw = 0, prevRead = 0;
    p_glGetIntegerv(0x8CA6, &prevDraw); p_glGetIntegerv(0x8CAA, &prevRead);
    if (!fbo) { p_glGenFramebuffers(1, &fbo); p_glGenRenderbuffers(1, &rbColor); p_glGenRenderbuffers(1, &rbDepth); }
    p_glBindRenderbuffer(GL_RENDERBUFFER, rbColor); p_glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, w, h);
    p_glBindRenderbuffer(GL_RENDERBUFFER, rbDepth); p_glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, w, h);
    p_glBindRenderbuffer(GL_RENDERBUFFER, 0);
    p_glBindFramebuffer(GL_FRAMEBUFFER, fbo);
    p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rbColor);
    p_glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, rbDepth);
    GLenum st = p_glCheckFramebufferStatus(GL_FRAMEBUFFER);
    p_glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDraw); p_glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
    LOG("FBO %u %dx%d status 0x%x", fbo, w, h, st);
    fw = w; fh = h;
    return (jint) fbo;
}

// Queue a read of the current FBO into PBO[cur]; copy the previous frame's PBO into dst.
// Returns 1 if dst was filled (with a frame of size outWH[0] x outWH[1]).
JNIEXPORT jint JNICALL JF(nSwap)(JNIEnv *env, jclass c, jintArray dst, jintArray outWH) {
    if (!fbo) return 0;
    GLint prevRead = 0, prevPack = 0;
    p_glGetIntegerv(0x8CAA, &prevRead); p_glGetIntegerv(0x88ED, &prevPack);
    if (!pbo[0]) p_glGenBuffers(2, pbo);
    p_glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    p_glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[cur]);
    if (pboW[cur] != fw || pboH[cur] != fh) { p_glBufferData(GL_PIXEL_PACK_BUFFER, (GLsizeiptr) fw * fh * 4, NULL, GL_STREAM_READ); pboW[cur] = fw; pboH[cur] = fh; }
    p_glReadPixels(0, 0, fw, fh, GL_RGBA, GL_UNSIGNED_BYTE, 0);
    p_glFlush();
    int prev = cur ^ 1, filled = 0;
    if (pboW[prev] > 0) {
        int w = pboW[prev], h = pboH[prev];
        if ((*env)->GetArrayLength(env, dst) >= w * h) {
            p_glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[prev]);
            const uint8_t *src = p_glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (GLsizeiptr) w * h * 4, GL_MAP_READ_BIT);
            if (src) {
                jint *out = (*env)->GetPrimitiveArrayCritical(env, dst, 0);
                for (int y = 0; y < h; y++) {
                    const uint32_t *s = (const uint32_t *) (src + (size_t) (h - 1 - y) * w * 4);
                    jint *d = out + (size_t) y * w;
                    for (int x = 0; x < w; x++) { uint32_t p = s[x]; d[x] = (jint) (((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF)); }
                }
                (*env)->ReleasePrimitiveArrayCritical(env, dst, out, 0);
                p_glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
                jint wh[2] = { w, h }; (*env)->SetIntArrayRegion(env, outWH, 0, 2, wh);
                filled = 1;
            } else if (frames < 5) LOG("glMapBufferRange failed 0x%x", p_glGetError());
        }
    }
    p_glBindBuffer(GL_PIXEL_PACK_BUFFER, prevPack);
    p_glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
    cur = prev; frames++;
    return filled;
}

JNIEXPORT void JNICALL JF(nDestroy)(JNIEnv *env, jclass c) {
    if (ctx != EGL_NO_CONTEXT) {
        p_eglMakeCurrent(dpy, surf, surf, ctx);
        if (fbo) { p_glDeleteFramebuffers(1, &fbo); GLuint rb[2] = { rbColor, rbDepth }; p_glDeleteRenderbuffers(2, rb); }
        p_eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        p_eglDestroySurface(dpy, surf); p_eglDestroyContext(dpy, ctx);
    }
    ctx = EGL_NO_CONTEXT; surf = EGL_NO_SURFACE; fbo = rbColor = rbDepth = 0; pbo[0] = pbo[1] = 0;
    pboW[0] = pboW[1] = pboH[0] = pboH[1] = 0; fw = fh = 0; cur = frames = 0;
}
