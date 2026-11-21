// RuneLite Mobile GPU mode: offscreen replacement for RuneLite's rlawt.
// Creates an EGL (GLES3) pbuffer context through MobileGlues (desktop GL on GLES), renders the
// GPU plugin into an FBO and reads frames back asynchronously (3 PBOs, two frames latency)
// into a Java int[] (0x00RRGGBB, top-down) that the Java side draws onto the AWT canvas.
#include <jni.h>
#include <dlfcn.h>
#include <string.h>
#include <stdio.h>
#include <stdint.h>
#include <stdlib.h>
#include <time.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <android/log.h>
#include <vulkan/vulkan.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "rlawt", __VA_ARGS__)

static void *mg;
// Zink mode: mg is Mesa's libOSMesa (desktop GL on Vulkan) instead of MobileGlues; no EGL.
static int osm; static void *octx; static uint8_t obuf[16 * 16 * 4];
static void *(*p_OSMesaGetProcAddress)(const char *);
static void *(*p_OSMesaCreateContextAttribs)(const int *, void *);
static GLboolean (*p_OSMesaMakeCurrent)(void *, void *, GLenum, GLsizei, GLsizei);
static void (*p_OSMesaDestroyContext)(void *);
static void *sym(const char *n) {
    void *f = osm && p_OSMesaGetProcAddress ? p_OSMesaGetProcAddress(n) : NULL;
    return f ? f : dlsym(mg, n);
}
static void *osm_gpa(const GLubyte *n) { return p_OSMesaGetProcAddress((const char *) n); }
static EGLDisplay dpy = EGL_NO_DISPLAY; static EGLContext ctx = EGL_NO_CONTEXT; static EGLSurface surf = EGL_NO_SURFACE;
#define NPBO 3
#define GPU_WAIT_NS 25000000ull
static GLuint fbo, rbColor, rbDepth, pbo[NPBO]; static int fw, fh, pboW[NPBO], pboH[NPBO], cur, frames;
static GLsync fence[NPBO];
static GLsync rfence[NPBO];
static GLuint rFbo[NPBO], rRb[NPBO]; static int rW[NPBO], rH[NPBO]; static uint8_t *stage; static size_t stageCap;

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
F(GLsync, glFenceSync, (GLenum, GLbitfield)) F(GLenum, glClientWaitSync, (GLsync, GLbitfield, GLuint64))
F(void, glDeleteSync, (GLsync))
F(void, glBlitFramebuffer, (GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum))
F(GLboolean, glIsEnabled, (GLenum)) F(void, glEnable, (GLenum)) F(void, glDisable, (GLenum))
F(void, glTexImage2D, (GLenum, GLint, GLint, GLsizei, GLsizei, GLint, GLenum, GLenum, const void*))
F(void, glTexSubImage2D, (GLenum, GLint, GLint, GLint, GLsizei, GLsizei, GLenum, GLenum, const void*))
F(void, glTexParameteri, (GLenum, GLenum, GLint))
#undef F
static int ctxMake(int on) {
    if (osm) return on ? p_OSMesaMakeCurrent(octx, obuf, GL_UNSIGNED_BYTE, 16, 16) : p_OSMesaMakeCurrent(NULL, NULL, 0, 0, 0);
    return on ? p_eglMakeCurrent(dpy, surf, surf, ctx) : p_eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}
#define L(name) if (!(p_##name = sym(#name))) { LOG("missing %s", #name); return JNI_FALSE; }

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
// The GPU plugin uploads its full-screen interface texture every frame as GL_BGRA /
// GL_UNSIGNED_INT_8_8_8_8_REV, which GLES lacks, so MobileGlues converted it on the CPU (12-20% of
// the Client thread). Those bytes are B,G,R,A in memory: upload them as plain RGBA and swap red and
// blue back with the texture's swizzle when sampling, so the driver uploads them as they are.
#define BGRA_FMT 0x80E1
#define REV8888 0x8367
static int bgraUpload(GLenum t, GLenum f, GLenum ty) {
    return t == GL_TEXTURE_2D && f == BGRA_FMT && (ty == GL_UNSIGNED_BYTE || ty == REV8888) && p_glTexParameteri;
}
static void swizzleBgra(GLenum t) {
    p_glTexParameteri(t, GL_TEXTURE_SWIZZLE_R, GL_BLUE); p_glTexParameteri(t, GL_TEXTURE_SWIZZLE_B, GL_RED);
}
static void w_glTexImage2D(GLenum t, GLint l, GLint ifmt, GLsizei w, GLsizei h, GLint b, GLenum f, GLenum ty, const void *px) {
    if (!bgraUpload(t, f, ty)) { p_glTexImage2D(t, l, ifmt, w, h, b, f, ty, px); return; }
    p_glTexImage2D(t, l, GL_RGBA8, w, h, b, GL_RGBA, GL_UNSIGNED_BYTE, px);
    swizzleBgra(t);
}
static void w_glTexSubImage2D(GLenum t, GLint l, GLint x, GLint y, GLsizei w, GLsizei h, GLenum f, GLenum ty, const void *px) {
    if (!bgraUpload(t, f, ty)) { p_glTexSubImage2D(t, l, x, y, w, h, f, ty, px); return; }
    p_glTexSubImage2D(t, l, x, y, w, h, GL_RGBA, GL_UNSIGNED_BYTE, px);
    swizzleBgra(t);
}
// On some devices (OnePlus Nord, Android 12) LWJGL's dlopen of this library lands in a different
// linker namespace than AWTContext's System.load(), so it gets a second copy with no nInit() state.
// Opening MobileGlues from that copy loads a second, uninitialised MobileGlues as well (crashed in
// strlen right after). Instead, the initialised copy publishes its resolver through the
// process-wide environment and any other copy forwards every lookup to it, so all GL calls go to
// the one MobileGlues that owns the context.
typedef void *(*resolver_t)(const GLubyte *);
static void *resolve(const GLubyte *name);
static int misses;
static resolver_t shared(void) {
    static resolver_t r;
    if (!r) { const char *e = getenv("RLAWT_RESOLVER"); if (e && *e) r = (resolver_t) (uintptr_t) strtoull(e, NULL, 16); }
    return r;
}
__attribute__((visibility("default"))) void *glXGetProcAddress(const GLubyte *name) {
    if (mg) return resolve(name);
    resolver_t r = shared();
    void *f = r ? r(name) : NULL;
    if (!f && misses++ < 8) {
        LOG("glXGetProcAddress(%s) = NULL (no initialised rlawt, resolver=%p)", name ? (const char *) name : "(null)", (void *) r);
        fprintf(stderr, "rlawt: glXGetProcAddress(%s) = NULL (no initialised rlawt, resolver=%p)\n", name ? (const char *) name : "(null)", (void *) r);
    }
    return f;
}
// MobileGlues translates desktop GLSL with glslang at the shader's own #version. 117 HD's
// shaders are "#version 330" but use backslash line continuations in macros (GLSL 4.20+, accepted
// by desktop drivers), so glslang rejected them, MobileGlues passed the desktop source through
// untouched and the GLES compiler failed with "Invalid #version". Join continuations first.
typedef void (*shsrc_t)(GLuint, GLsizei, const GLchar *const *, const GLint *);
static shsrc_t p_glShaderSource;
static void w_glShaderSource(GLuint sh, GLsizei count, const GLchar *const *str, const GLint *len) {
    size_t total = 0;
    for (GLsizei i = 0; i < count; i++) total += (len && len[i] >= 0) ? (size_t) len[i] : strlen(str[i]);
    char *buf = malloc(total + 1);
    if (!buf) { p_glShaderSource(sh, count, str, len); return; }
    size_t o = 0;
    for (GLsizei i = 0; i < count; i++) {
        size_t l = (len && len[i] >= 0) ? (size_t) len[i] : strlen(str[i]);
        memcpy(buf + o, str[i], l); o += l;
    }
    buf[o] = 0;
    size_t w = 0;
    for (size_t r = 0; r < o; r++) {
        if (buf[r] == '\\') {
            size_t k = r + 1;
            if (k < o && buf[k] == '\r') k++;
            if (k < o && buf[k] == '\n') { r = k; continue; }
        }
        buf[w++] = buf[r];
    }
    buf[w] = 0;
    const GLchar *one[1] = { buf };
    p_glShaderSource(sh, 1, one, NULL);
    free(buf);
}

// MobileGlues implements GL 3.0/3.1 buffer copies and range mapping (core in GLES 3) but doesn't
// list GL_ARB_copy_buffer / GL_ARB_map_buffer_range, so LWJGL reports them missing and 117 HD
// copies buffers by glMapBuffer(GL_READ_ONLY), which MobileGlues can't map ("Failed to map copy
// source buffer"). Advertise them (only if absent) through the GL3 extension queries.
static const char *extraExt[] = { "GL_ARB_copy_buffer", "GL_ARB_map_buffer_range" };
#define NEXTRA (sizeof extraExt / sizeof extraExt[0])
typedef const GLubyte *(*getstringi_t)(GLenum, GLuint);
typedef void (*getintegerv_t)(GLenum, GLint *);
typedef const GLubyte *(*getstring_t)(GLenum);
static getstringi_t p_glGetStringi; static getintegerv_t r_glGetIntegerv; static getstring_t r_glGetString;
static int extBase = -1, extMissing; static const char *missing[NEXTRA]; static char *extStr;
static void extInit(void) {
    if (extBase >= 0 || !r_glGetIntegerv || !p_glGetStringi) return;
    GLint n = 0; r_glGetIntegerv(0x821D /*GL_NUM_EXTENSIONS*/, &n);
    if (n <= 0) return; // no context yet
    extBase = n; extMissing = 0;
    for (size_t e = 0; e < NEXTRA; e++) {
        int found = 0;
        for (GLint i = 0; i < n && !found; i++) { const GLubyte *x = p_glGetStringi(GL_EXTENSIONS, (GLuint) i); found = x && !strcmp((const char *) x, extraExt[e]); }
        if (!found) missing[extMissing++] = extraExt[e];
    }
    LOG("advertising %d extra GL extensions", extMissing);
}
static void w_glGetIntegerv(GLenum pname, GLint *v) {
    r_glGetIntegerv(pname, v);
    if (pname == 0x821D && v) { extInit(); if (extBase >= 0) *v = extBase + extMissing; }
}
static const GLubyte *w_glGetStringi(GLenum name, GLuint i) {
    if (name == GL_EXTENSIONS) { extInit(); if (extBase >= 0 && (int) i >= extBase && (int) i < extBase + extMissing) return (const GLubyte *) missing[i - extBase]; }
    return p_glGetStringi(name, i);
}
static const GLubyte *w_glGetString(GLenum name) {
    const GLubyte *r = r_glGetString(name);
    if (name != GL_EXTENSIONS || !r) return r;
    extInit();
    if (extBase < 0 || !extMissing) return r;
    if (!extStr) {
        size_t l = strlen((const char *) r) + 1;
        for (int i = 0; i < extMissing; i++) l += strlen(missing[i]) + 1;
        extStr = malloc(l);
        if (!extStr) return r;
        strcpy(extStr, (const char *) r);
        for (int i = 0; i < extMissing; i++) { strcat(extStr, " "); strcat(extStr, missing[i]); }
    }
    return (const GLubyte *) extStr;
}
static void *mgproc(const char *n) {
    void *f = p_glXGetProcAddress((const GLubyte *) n);
    return f ? f : dlsym(mg, n);
}

static void *resolve(const GLubyte *name) {
    if (!name) return NULL;
    const char *n = (const char *) name;
    if (!strcmp(n, "glGetIntegerv") || !strcmp(n, "glGetStringi") || !strcmp(n, "glGetString")) {
        if (!r_glGetIntegerv) r_glGetIntegerv = (getintegerv_t) mgproc("glGetIntegerv");
        if (!p_glGetStringi) p_glGetStringi = (getstringi_t) mgproc("glGetStringi");
        if (!r_glGetString) r_glGetString = (getstring_t) mgproc("glGetString");
        if (r_glGetIntegerv && p_glGetStringi && r_glGetString)
            return !strcmp(n, "glGetIntegerv") ? (void *) w_glGetIntegerv : !strcmp(n, "glGetStringi") ? (void *) w_glGetStringi : (void *) w_glGetString;
    }
    if (!strcmp(n, "glShaderSource") || !strcmp(n, "glShaderSourceARB")) {
        if (!p_glShaderSource) {
            p_glShaderSource = (shsrc_t) p_glXGetProcAddress((const GLubyte *) "glShaderSource");
            if (!p_glShaderSource) p_glShaderSource = (shsrc_t) dlsym(mg, "glShaderSource");
        }
        if (p_glShaderSource) return (void *) w_glShaderSource;
    }
    if (!strcmp(n, "glTexImage2D") || !strcmp(n, "glTexSubImage2D")) {
        if (!p_glTexImage2D) p_glTexImage2D = mgproc("glTexImage2D");
        if (!p_glTexSubImage2D) p_glTexSubImage2D = mgproc("glTexSubImage2D");
        if (!p_glTexParameteri) p_glTexParameteri = mgproc("glTexParameteri");
        if (!osm && p_glTexImage2D && p_glTexSubImage2D && p_glTexParameteri && !getenv("RLAWT_NO_BGRA_SWIZZLE"))
            return !strcmp(n, "glTexImage2D") ? (void *) w_glTexImage2D : (void *) w_glTexSubImage2D;
    }
    if (!strcmp(n, "glRenderbufferStorage")) return (void *) w_glRenderbufferStorage;
    if (!strcmp(n, "glRenderbufferStorageMultisample")) return (void *) w_glRenderbufferStorageMultisample;
    void *f = p_glXGetProcAddress(name);
    return f ? f : dlsym(mg, n);
}
__attribute__((visibility("default"))) void *glXGetProcAddressARB(const GLubyte *name) { return glXGetProcAddress(name); }

#define JF(name) Java_net_runelite_rlawt_AWTContext_##name

// Vulkan loader for Zink: Turnip (bundled Mesa Vulkan driver for Adreno) through libpojavexec's
// linker-namespace loader, else the system driver. Mesa reads the handle from VULKAN_PTR.
static void *vkLoader(void) {
    const char *e = getenv("VULKAN_PTR");
    if (e && *e) return (void *) (uintptr_t) strtoull(e, NULL, 16);
    if (!getenv("RLAWT_NO_TURNIP")) setenv("POJAV_LOAD_TURNIP", "1", 1);
    void *px = dlopen("libpojavexec.so", RTLD_NOW | RTLD_NOLOAD);
    void *(*mlv)(void) = px ? (void *(*)(void)) dlsym(px, "maybe_load_vulkan") : NULL;
    if (mlv) return mlv();
    void *vk = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
    char vb[32]; snprintf(vb, sizeof vb, "%llx", (unsigned long long) (uintptr_t) vk);
    setenv("VULKAN_PTR", vb, 1);
    return vk;
}

// Mesa 23's Zink dereferences a NULL screen (SIGSEGV in OSMesaCreateContextAttribs) when it can't
// use the Vulkan driver, which is the case with Qualcomm's own Adreno driver.
static int vkProbe(void *loader, char *out, size_t n) {
    snprintf(out, n, "no Vulkan loader");
    PFN_vkGetInstanceProcAddr gipa = loader ? (PFN_vkGetInstanceProcAddr) dlsym(loader, "vkGetInstanceProcAddr") : NULL;
    if (!gipa) return 0;
    PFN_vkCreateInstance ci = (PFN_vkCreateInstance) gipa(NULL, "vkCreateInstance");
    VkApplicationInfo ai = { .sType = VK_STRUCTURE_TYPE_APPLICATION_INFO, .apiVersion = VK_API_VERSION_1_1 };
    VkInstanceCreateInfo ici = { .sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO, .pApplicationInfo = &ai };
    VkInstance inst;
    if (!ci || ci(&ici, NULL, &inst) != VK_SUCCESS) { snprintf(out, n, "vkCreateInstance failed"); return 0; }
    PFN_vkEnumeratePhysicalDevices epd = (PFN_vkEnumeratePhysicalDevices) gipa(inst, "vkEnumeratePhysicalDevices");
    PFN_vkGetPhysicalDeviceProperties gpp = (PFN_vkGetPhysicalDeviceProperties) gipa(inst, "vkGetPhysicalDeviceProperties");
    PFN_vkDestroyInstance di = (PFN_vkDestroyInstance) gipa(inst, "vkDestroyInstance");
    uint32_t count = 1; VkPhysicalDevice dev; int ok = 0;
    snprintf(out, n, "no Vulkan device");
    if (epd && gpp && epd(inst, &count, &dev) >= 0 && count > 0) {
        VkPhysicalDeviceProperties p; gpp(dev, &p);
        snprintf(out, n, "%s, Vulkan %u.%u.%u", p.deviceName, VK_VERSION_MAJOR(p.apiVersion), VK_VERSION_MINOR(p.apiVersion), VK_VERSION_PATCH(p.apiVersion));
        // Qualcomm's own driver crashes Zink; Turnip, Mali and others with Vulkan 1.1+ are tried.
        ok = strstr(p.deviceName, "Turnip") != NULL || getenv("RLAWT_ZINK_ANY_DRIVER") != NULL
            || (strstr(p.deviceName, "Adreno") == NULL && VK_API_VERSION_MINOR(p.apiVersion) >= 1);
    }
    if (di) di(inst, NULL);
    return ok;
}

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
    char pathBuf[512]; snprintf(pathBuf, sizeof pathBuf, "%s", path);
    (*env)->ReleaseStringUTFChars(env, lib, path);
    osm = strstr(pathBuf, "OSMesa") != NULL;
    if (osm) {
        char info[200];
        int ok = vkProbe(vkLoader(), info, sizeof info);
        fprintf(stderr, "rlawt: Zink Vulkan driver: %s%s\n", info, ok ? "" : " -> unsupported, using MobileGlues");
        fflush(stderr);
        if (ok) {
            setenv("GALLIUM_DRIVER", "zink", 1);
            setenv("MESA_ANDROID_NO_KMS_SWRAST", "1", 1);
        } else {
            osm = 0;
            char *slash = strrchr(pathBuf, '/');
            snprintf(slash ? slash + 1 : pathBuf, sizeof pathBuf - (slash ? (size_t) (slash + 1 - pathBuf) : 0), "libmobileglues.so");
        }
    }
    mg = dlopen(pathBuf, RTLD_NOW | RTLD_GLOBAL);
    if (!mg) LOG("dlopen %s: %s", pathBuf, dlerror());
    if (!mg) return JNI_FALSE;
    if (osm) {
        p_OSMesaGetProcAddress = dlsym(mg, "OSMesaGetProcAddress");
        p_OSMesaCreateContextAttribs = dlsym(mg, "OSMesaCreateContextAttribs");
        p_OSMesaMakeCurrent = dlsym(mg, "OSMesaMakeCurrent");
        p_OSMesaDestroyContext = dlsym(mg, "OSMesaDestroyContext");
        if (!p_OSMesaGetProcAddress || !p_OSMesaCreateContextAttribs || !p_OSMesaMakeCurrent || !p_OSMesaDestroyContext) {
            LOG("libOSMesa is missing OSMesa entry points"); mg = NULL; return JNI_FALSE;
        }
        p_glXGetProcAddress = osm_gpa;
    } else {
    L(eglGetDisplay) L(eglInitialize) L(eglBindAPI) L(eglChooseConfig) L(eglCreateContext) L(eglCreatePbufferSurface)
    L(eglMakeCurrent) L(eglGetError) L(eglDestroyContext) L(eglDestroySurface) L(eglSwapInterval)
    L(glXGetProcAddress)
    }
    L(glGetString) L(glGetIntegerv) L(glGetError) L(glGenFramebuffers) L(glBindFramebuffer) L(glGenRenderbuffers)
    L(glBindRenderbuffer) L(glRenderbufferStorage) L(glFramebufferRenderbuffer) L(glCheckFramebufferStatus)
    L(glDeleteFramebuffers) L(glDeleteRenderbuffers) L(glGenBuffers) L(glBindBuffer) L(glBufferData)
    L(glMapBufferRange) L(glUnmapBuffer) L(glReadPixels) L(glFlush)
    L(glRenderbufferStorageMultisample)
    p_glFenceSync = sym("glFenceSync"); p_glClientWaitSync = sym("glClientWaitSync"); p_glDeleteSync = sym("glDeleteSync");
    if (!p_glFenceSync || !p_glClientWaitSync || !p_glDeleteSync) { LOG("no sync objects, readback maps block"); p_glFenceSync = NULL; }
    p_glBlitFramebuffer = sym("glBlitFramebuffer"); p_glIsEnabled = sym("glIsEnabled");
    p_glEnable = sym("glEnable"); p_glDisable = sym("glDisable");
    if (!p_glBlitFramebuffer || !p_glIsEnabled || !p_glEnable || !p_glDisable) p_glBlitFramebuffer = NULL;
    char buf[32]; snprintf(buf, sizeof buf, "%llx", (unsigned long long) (uintptr_t) resolve);
    setenv("RLAWT_RESOLVER", buf, 1);
    return JNI_TRUE;
}

static jstring createOsm(JNIEnv *env) {
    char err[200];
    // GL_RGBA, no depth (we render into our own FBO); core 4.6 -> core 4.3 -> compat.
    const int tries[3][11] = {
        { 0x22, GL_RGBA, 0x30, 0, 0x33, 0x34, 0x36, 4, 0x37, 6, 0 },
        { 0x22, GL_RGBA, 0x30, 0, 0x33, 0x34, 0x36, 4, 0x37, 3, 0 },
        { 0x22, GL_RGBA, 0x30, 0, 0x33, 0x35, 0, 0, 0, 0, 0 },
    };
    for (int i = 0; i < 3 && !octx; i++) octx = p_OSMesaCreateContextAttribs(tries[i], NULL);
    if (!octx) return (*env)->NewStringUTF(env, "OSMesaCreateContextAttribs failed (Zink/Vulkan unavailable?)");
    if (!ctxMake(1)) return (*env)->NewStringUTF(env, "OSMesaMakeCurrent failed");
    GLint compute = -1; p_glGetIntegerv(0x90EB, &compute);
    GLint e = p_glGetError();
    LOG("Zink GL_VERSION=%s GL_RENDERER=%s GLSL=%s", p_glGetString(GL_VERSION), p_glGetString(GL_RENDERER), p_glGetString(GL_SHADING_LANGUAGE_VERSION));
    snprintf(err, sizeof err, "OK|%s|%s|%s|compute_invocations=%d%s", p_glGetString(GL_VERSION), p_glGetString(GL_RENDERER),
        p_glGetString(GL_SHADING_LANGUAGE_VERSION), compute, e ? " (glGetIntegerv error)" : "");
    return (*env)->NewStringUTF(env, err);
}

JNIEXPORT jstring JNICALL JF(nCreate)(JNIEnv *env, jclass c) {
    if (osm) return createOsm(env);
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

JNIEXPORT void JNICALL JF(nMakeCurrent)(JNIEnv *env, jclass c, jboolean on) { ctxMake(on); }

JNIEXPORT jint JNICALL JF(nSwapInterval)(JNIEnv *env, jclass c, jint i) { return osm ? 0 : p_eglSwapInterval(dpy, i) ? 0 : -1; }

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

// Queue a read of the current FBO into PBO[cur]; copy the oldest queued frame (two swaps ago) into
// dst. Two frames of slack let the GPU (tile-deferred on Adreno/Mali) finish a frame before the
// CPU maps it, instead of the map stalling the Client thread until the GPU catches up.
// Returns 1 if dst was filled (with a frame of size outWH[0] x outWH[1]). outWH[2] / outWH[3] get
// the microseconds spent waiting for the GPU (fence + map) / converting the frame into dst.
static int64_t now_us(void) { struct timespec t; clock_gettime(CLOCK_MONOTONIC, &t); return (int64_t) t.tv_sec * 1000000 + t.tv_nsec / 1000; }

// Preferred path: copy the finished frame on the GPU (glBlitFramebuffer, flipped) into a ring of
// renderbuffers, have the GPU copy each into a PBO one swap later, and map it the swap after that.
// glReadPixels straight from the live FBO made the driver finish the whole frame first, so the
// Client thread waited out the GPU's render time inside every swap (30-40% of its samples).
static jint swapRing(JNIEnv *env, jintArray dst, jintArray outWH) {
    GLint prevRead = 0, prevDraw = 0, prevPack = 0;
    p_glGetIntegerv(0x8CAA, &prevRead); p_glGetIntegerv(0x8CA6, &prevDraw); p_glGetIntegerv(0x88ED, &prevPack);
    GLboolean scissor = p_glIsEnabled(GL_SCISSOR_TEST);
    if (scissor) p_glDisable(GL_SCISSOR_TEST);
    if (!rFbo[cur]) { p_glGenFramebuffers(1, &rFbo[cur]); p_glGenRenderbuffers(1, &rRb[cur]); }
    p_glBindFramebuffer(GL_DRAW_FRAMEBUFFER, rFbo[cur]);
    if (rW[cur] != fw || rH[cur] != fh) {
        p_glBindRenderbuffer(GL_RENDERBUFFER, rRb[cur]); p_glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, fw, fh);
        p_glBindRenderbuffer(GL_RENDERBUFFER, 0);
        p_glFramebufferRenderbuffer(GL_DRAW_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, rRb[cur]);
        rW[cur] = fw; rH[cur] = fh;
    }
    p_glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    p_glBlitFramebuffer(0, 0, fw, fh, 0, fh, fw, 0, GL_COLOR_BUFFER_BIT, GL_NEAREST);
    if (fence[cur]) p_glDeleteSync(fence[cur]);
    fence[cur] = p_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    p_glFlush();
    // Stage 2: the frame blitted last swap is finished on the GPU (normally): let the GPU copy it
    // into its PBO. Reading a finished buffer doesn't stall; glReadPixels into client memory did
    // (~15 ms, a CPU copy from tiled memory), so the copy stays on the GPU.
    int prev = (cur + NPBO - 1) % NPBO, old = (cur + 1) % NPBO, filled = 0;
    int64_t t0 = now_us(), t1 = t0, t2 = t0;
    // Waits are bounded (GPU_WAIT_NS): when the GPU stalls (shader compiles, app in background) the
    // frame is dropped instead of the Client thread blocking for 100 ms per fence.
    if (rW[prev] > 0 && fence[prev]) {
        GLenum st = p_glClientWaitSync(fence[prev], GL_SYNC_FLUSH_COMMANDS_BIT, GPU_WAIT_NS);
        p_glDeleteSync(fence[prev]); fence[prev] = 0;
        if (st == GL_TIMEOUT_EXPIRED || st == GL_WAIT_FAILED) goto map;
        if (!pbo[0]) p_glGenBuffers(NPBO, pbo);
        p_glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[prev]);
        if (pboW[prev] != rW[prev] || pboH[prev] != rH[prev]) {
            p_glBufferData(GL_PIXEL_PACK_BUFFER, (GLsizeiptr) rW[prev] * rH[prev] * 4, NULL, GL_STREAM_READ);
            pboW[prev] = rW[prev]; pboH[prev] = rH[prev];
        }
        p_glBindFramebuffer(GL_READ_FRAMEBUFFER, rFbo[prev]);
        p_glReadPixels(0, 0, rW[prev], rH[prev], GL_RGBA, GL_UNSIGNED_BYTE, 0);
        if (rfence[prev]) p_glDeleteSync(rfence[prev]);
        rfence[prev] = p_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
        p_glFlush();
    }
map:
    // Stage 3: map the PBO filled last swap (already flipped by the blit) and copy it out with wide
    // loads (the mapping is uncached), swizzling in cache.
    if (rfence[old] && pboW[old] > 0) {
        int w = pboW[old], h = pboH[old];
        GLenum st = p_glClientWaitSync(rfence[old], GL_SYNC_FLUSH_COMMANDS_BIT, GPU_WAIT_NS);
        p_glDeleteSync(rfence[old]); rfence[old] = 0;
        t1 = now_us();
        if (st != GL_TIMEOUT_EXPIRED && st != GL_WAIT_FAILED && (*env)->GetArrayLength(env, dst) >= w * h) {
            p_glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[old]);
            const uint8_t *src = p_glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (GLsizeiptr) w * h * 4, GL_MAP_READ_BIT);
            if (src) {
                jint *out = (*env)->GetPrimitiveArrayCritical(env, dst, 0);
                for (int y = 0; y < h; y++) {
                    uint32_t *d = (uint32_t *) (out + (size_t) y * w);
                    memcpy(d, src + (size_t) y * w * 4, (size_t) w * 4);
                    for (int x = 0; x < w; x++) { uint32_t p = d[x]; d[x] = ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF); }
                }
                (*env)->ReleasePrimitiveArrayCritical(env, dst, out, 0);
                p_glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
                t2 = now_us();
                jint wh[2] = { w, h }; (*env)->SetIntArrayRegion(env, outWH, 0, 2, wh);
                filled = 1;
            }
        }
    }
    if (scissor) p_glEnable(GL_SCISSOR_TEST);
    p_glBindBuffer(GL_PIXEL_PACK_BUFFER, prevPack);
    p_glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead); p_glBindFramebuffer(GL_DRAW_FRAMEBUFFER, prevDraw);
    cur = (cur + 1) % NPBO; frames++;
    jint us[2] = { (jint) (t1 - t0), (jint) (t2 - t1) }; (*env)->SetIntArrayRegion(env, outWH, 2, 2, us);
    return filled;
}

JNIEXPORT jint JNICALL JF(nSwap)(JNIEnv *env, jclass c, jintArray dst, jintArray outWH) {
    if (!fbo) return 0;
    if (p_glBlitFramebuffer && p_glFenceSync && !getenv("RLAWT_PBO_READBACK")) return swapRing(env, dst, outWH);
    GLint prevRead = 0, prevPack = 0;
    p_glGetIntegerv(0x8CAA, &prevRead); p_glGetIntegerv(0x88ED, &prevPack);
    if (!pbo[0]) p_glGenBuffers(NPBO, pbo);
    p_glBindFramebuffer(GL_READ_FRAMEBUFFER, fbo);
    p_glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[cur]);
    if (pboW[cur] != fw || pboH[cur] != fh) { p_glBufferData(GL_PIXEL_PACK_BUFFER, (GLsizeiptr) fw * fh * 4, NULL, GL_STREAM_READ); pboW[cur] = fw; pboH[cur] = fh; }
    p_glReadPixels(0, 0, fw, fh, GL_RGBA, GL_UNSIGNED_BYTE, 0);
    if (p_glFenceSync) { if (fence[cur]) p_glDeleteSync(fence[cur]); fence[cur] = p_glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0); }
    p_glFlush();
    int old = (cur + 1) % NPBO, filled = 0;
    int64_t t0 = now_us(), t1 = t0, t2 = t0;
    if (pboW[old] > 0) {
        int w = pboW[old], h = pboH[old];
        if (p_glFenceSync && fence[old]) {
            p_glClientWaitSync(fence[old], GL_SYNC_FLUSH_COMMANDS_BIT, 100000000ull);
            p_glDeleteSync(fence[old]); fence[old] = 0;
        }
        if ((*env)->GetArrayLength(env, dst) >= w * h) {
            p_glBindBuffer(GL_PIXEL_PACK_BUFFER, pbo[old]);
            const uint8_t *src = p_glMapBufferRange(GL_PIXEL_PACK_BUFFER, 0, (GLsizeiptr) w * h * 4, GL_MAP_READ_BIT);
            t1 = now_us();
            if (src) {
                jint *out = (*env)->GetPrimitiveArrayCritical(env, dst, 0);
                // The mapped PBO is uncached on mobile GPUs: per-pixel loads from it cost ~15 ms a
                // frame. Copy each row out with memcpy's wide loads first, then swizzle in cache.
                for (int y = 0; y < h; y++) {
                    uint32_t *d = (uint32_t *) (out + (size_t) y * w);
                    memcpy(d, src + (size_t) (h - 1 - y) * w * 4, (size_t) w * 4);
                    for (int x = 0; x < w; x++) { uint32_t p = d[x]; d[x] = ((p & 0xFF) << 16) | (p & 0xFF00) | ((p >> 16) & 0xFF); }
                }
                (*env)->ReleasePrimitiveArrayCritical(env, dst, out, 0);
                p_glUnmapBuffer(GL_PIXEL_PACK_BUFFER);
                t2 = now_us();
                jint wh[2] = { w, h }; (*env)->SetIntArrayRegion(env, outWH, 0, 2, wh);
                filled = 1;
            } else if (frames < 5) LOG("glMapBufferRange failed 0x%x", p_glGetError());
        }
    }
    p_glBindBuffer(GL_PIXEL_PACK_BUFFER, prevPack);
    p_glBindFramebuffer(GL_READ_FRAMEBUFFER, prevRead);
    cur = (cur + 1) % NPBO; frames++;
    jint us[2] = { (jint) (t1 - t0), (jint) (t2 - t1) }; (*env)->SetIntArrayRegion(env, outWH, 2, 2, us);
    return filled;
}

JNIEXPORT void JNICALL JF(nDestroy)(JNIEnv *env, jclass c) {
    if (ctx != EGL_NO_CONTEXT || octx) {
        ctxMake(1);
        for (int i = 0; i < NPBO; i++) { if (fence[i]) p_glDeleteSync(fence[i]); if (rfence[i]) p_glDeleteSync(rfence[i]); }
        for (int i = 0; i < NPBO; i++) if (rFbo[i]) { p_glDeleteFramebuffers(1, &rFbo[i]); p_glDeleteRenderbuffers(1, &rRb[i]); }
        if (fbo) { p_glDeleteFramebuffers(1, &fbo); GLuint rb[2] = { rbColor, rbDepth }; p_glDeleteRenderbuffers(2, rb); }
        ctxMake(0);
        if (osm) p_OSMesaDestroyContext(octx); else { p_eglDestroySurface(dpy, surf); p_eglDestroyContext(dpy, ctx); }
    }
    octx = NULL; ctx = EGL_NO_CONTEXT; surf = EGL_NO_SURFACE; fbo = rbColor = rbDepth = 0; fw = fh = 0; cur = frames = 0;
    memset(pbo, 0, sizeof pbo); memset(pboW, 0, sizeof pboW); memset(pboH, 0, sizeof pboH); memset(fence, 0, sizeof fence); memset(rfence, 0, sizeof rfence);
    memset(rFbo, 0, sizeof rFbo); memset(rRb, 0, sizeof rRb); memset(rW, 0, sizeof rW); memset(rH, 0, sizeof rH);
    free(stage); stage = NULL; stageCap = 0;
}
