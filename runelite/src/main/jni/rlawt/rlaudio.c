// RuneLite Mobile: javax.sound.sampled output through Android AAudio (the bundled JRE has no
// libjsound and there is no ALSA/PulseAudio on Android). Used by com.codyedwin.runelitemobile.shim.sound.
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <stdatomic.h>
#include <android/log.h>
#include <aaudio/AAudio.h>
#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "rlaudio", __VA_ARGS__)

static void *lib;
#define F(ret, name, args) static ret (*p_##name) args;
F(aaudio_result_t, AAudio_createStreamBuilder, (AAudioStreamBuilder**))
F(void, AAudioStreamBuilder_setSampleRate, (AAudioStreamBuilder*, int32_t))
F(void, AAudioStreamBuilder_setChannelCount, (AAudioStreamBuilder*, int32_t))
F(void, AAudioStreamBuilder_setFormat, (AAudioStreamBuilder*, aaudio_format_t))
F(void, AAudioStreamBuilder_setBufferCapacityInFrames, (AAudioStreamBuilder*, int32_t))
F(void, AAudioStreamBuilder_setPerformanceMode, (AAudioStreamBuilder*, aaudio_performance_mode_t))
F(void, AAudioStreamBuilder_setUsage, (AAudioStreamBuilder*, aaudio_usage_t))
F(void, AAudioStreamBuilder_setContentType, (AAudioStreamBuilder*, aaudio_content_type_t))
F(aaudio_result_t, AAudioStreamBuilder_openStream, (AAudioStreamBuilder*, AAudioStream**))
F(aaudio_result_t, AAudioStreamBuilder_delete, (AAudioStreamBuilder*))
F(aaudio_result_t, AAudioStream_requestStart, (AAudioStream*))
F(aaudio_result_t, AAudioStream_requestPause, (AAudioStream*))
F(aaudio_result_t, AAudioStream_requestFlush, (AAudioStream*))
F(aaudio_result_t, AAudioStream_requestStop, (AAudioStream*))
F(aaudio_result_t, AAudioStream_close, (AAudioStream*))
F(aaudio_result_t, AAudioStream_write, (AAudioStream*, const void*, int32_t, int64_t))
F(int64_t, AAudioStream_getFramesWritten, (AAudioStream*))
F(int64_t, AAudioStream_getFramesRead, (AAudioStream*))
F(int32_t, AAudioStream_getBufferCapacityInFrames, (AAudioStream*))
F(aaudio_result_t, AAudioStream_setBufferSizeInFrames, (AAudioStream*, int32_t))
F(int32_t, AAudioStream_getSampleRate, (AAudioStream*))
F(void, AAudioStreamBuilder_setDataCallback, (AAudioStreamBuilder*, AAudioStream_dataCallback, void*))
#undef F
#define L(name) if (!(p_##name = dlsym(lib, #name))) { LOG("missing %s", #name); return JNI_FALSE; }
#define JF(name) Java_com_codyedwin_runelitemobile_shim_sound_AAudioLine_##name

JNIEXPORT jboolean JNICALL JF(nInit)(JNIEnv *e, jclass c) {
    if (lib) return JNI_TRUE;
    lib = dlopen("libaaudio.so", RTLD_NOW);
    if (!lib) { LOG("no libaaudio.so"); return JNI_FALSE; }
    L(AAudio_createStreamBuilder) L(AAudioStreamBuilder_setSampleRate) L(AAudioStreamBuilder_setChannelCount)
    L(AAudioStreamBuilder_setFormat) L(AAudioStreamBuilder_setBufferCapacityInFrames) L(AAudioStreamBuilder_setPerformanceMode)
    L(AAudioStreamBuilder_setUsage) L(AAudioStreamBuilder_setContentType) L(AAudioStreamBuilder_openStream)
    L(AAudioStreamBuilder_delete) L(AAudioStream_requestStart) L(AAudioStream_requestPause) L(AAudioStream_requestFlush)
    L(AAudioStream_requestStop) L(AAudioStream_close) L(AAudioStream_write) L(AAudioStream_getFramesWritten)
    L(AAudioStream_getFramesRead) L(AAudioStream_getBufferCapacityInFrames) L(AAudioStream_setBufferSizeInFrames)
    L(AAudioStream_getSampleRate) L(AAudioStreamBuilder_setDataCallback)
    return JNI_TRUE;
}

// Callback stream with our own ring buffer: AAudio pulls frames as the device needs them (silence
// when the game has not written enough), so playback always runs and the read position the game
// polls through available() advances. A blocking-write legacy stream only started once its whole
// buffer was full, which the game never does (it keeps ~2048 frames queued), so nothing played and
// the game reopened the line every 4 s.
typedef struct {
    AAudioStream *s;
    int16_t *ring;
    int32_t cap, ch;
    _Atomic int64_t wr, rd;
} Line;

static aaudio_data_callback_result_t cb(AAudioStream *st, void *user, void *audio, int32_t n) {
    Line *l = user;
    int16_t *out = audio;
    int64_t rd = atomic_load(&l->rd), wr = atomic_load(&l->wr);
    int32_t take = (int32_t) (wr - rd < n ? wr - rd : n);
    for (int32_t i = 0; i < take; i++) {
        int32_t idx = (int32_t) ((rd + i) % l->cap);
        memcpy(out + (size_t) i * l->ch, l->ring + (size_t) idx * l->ch, l->ch * 2);
    }
    if (take < n) memset(out + (size_t) take * l->ch, 0, (size_t) (n - take) * l->ch * 2);
    atomic_store(&l->rd, rd + take);
    return AAUDIO_CALLBACK_RESULT_CONTINUE;
}

JNIEXPORT jlong JNICALL JF(nOpen)(JNIEnv *e, jclass c, jint rate, jint channels, jint bufferFrames) {
    Line *l = calloc(1, sizeof(Line));
    l->cap = bufferFrames; l->ch = channels;
    l->ring = calloc((size_t) bufferFrames * channels, 2);
    AAudioStreamBuilder *b;
    if (p_AAudio_createStreamBuilder(&b) != AAUDIO_OK) { free(l->ring); free(l); return 0; }
    p_AAudioStreamBuilder_setSampleRate(b, rate);
    p_AAudioStreamBuilder_setChannelCount(b, channels);
    p_AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    p_AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    p_AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    p_AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SONIFICATION);
    p_AAudioStreamBuilder_setDataCallback(b, cb, l);
    aaudio_result_t r = p_AAudioStreamBuilder_openStream(b, &l->s);
    p_AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOG("openStream %d Hz %d ch: %d", rate, channels, r); free(l->ring); free(l); return 0; }
    LOG("opened %d Hz (device %d) %d ch, ring %d frames, callback", rate, p_AAudioStream_getSampleRate(l->s), channels, bufferFrames);
    return (jlong) (intptr_t) l;
}

// Copies as many frames as fit into the ring (never blocks); returns frames copied.
JNIEXPORT jint JNICALL JF(nWrite)(JNIEnv *e, jclass c, jlong h, jbyteArray data, jint off, jint frames, jint frameSize, jlong timeoutNanos) {
    Line *l = (Line *) (intptr_t) h;
    int64_t wr = atomic_load(&l->wr), rd = atomic_load(&l->rd);
    int32_t space = (int32_t) (l->cap - (wr - rd));
    int32_t n = frames < space ? frames : space;
    if (n <= 0) return 0;
    jbyte *buf = (*e)->GetByteArrayElements(e, data, NULL);
    const int16_t *src = (const int16_t *) (buf + off);
    for (int32_t i = 0; i < n; i++) {
        int32_t idx = (int32_t) ((wr + i) % l->cap);
        memcpy(l->ring + (size_t) idx * l->ch, src + (size_t) i * l->ch, l->ch * 2);
    }
    (*e)->ReleaseByteArrayElements(e, data, buf, JNI_ABORT);
    atomic_store(&l->wr, wr + n);
    return n;
}

JNIEXPORT jlong JNICALL JF(nQueued)(JNIEnv *e, jclass c, jlong h) {
    Line *l = (Line *) (intptr_t) h;
    return atomic_load(&l->wr) - atomic_load(&l->rd);
}

JNIEXPORT jlong JNICALL JF(nFramesRead)(JNIEnv *e, jclass c, jlong h) { return atomic_load(&((Line *) (intptr_t) h)->rd); }
JNIEXPORT void JNICALL JF(nStart)(JNIEnv *e, jclass c, jlong h) { p_AAudioStream_requestStart(((Line *) (intptr_t) h)->s); }
JNIEXPORT void JNICALL JF(nPause)(JNIEnv *e, jclass c, jlong h) { p_AAudioStream_requestPause(((Line *) (intptr_t) h)->s); }
JNIEXPORT void JNICALL JF(nFlush)(JNIEnv *e, jclass c, jlong h) {
    Line *l = (Line *) (intptr_t) h;
    atomic_store(&l->rd, atomic_load(&l->wr)); // drop queued frames
}
JNIEXPORT void JNICALL JF(nClose)(JNIEnv *e, jclass c, jlong h) {
    Line *l = (Line *) (intptr_t) h;
    p_AAudioStream_requestStop(l->s);
    p_AAudioStream_close(l->s);
    free(l->ring);
    free(l);
}
