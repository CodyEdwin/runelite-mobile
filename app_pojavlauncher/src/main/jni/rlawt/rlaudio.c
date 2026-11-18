// RuneLite Mobile: javax.sound.sampled output through Android AAudio (the bundled JRE has no
// libjsound and there is no ALSA/PulseAudio on Android). Used by net.kdt.pojavlaunch.runelite.sound.
#include <jni.h>
#include <dlfcn.h>
#include <stdint.h>
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
#undef F
#define L(name) if (!(p_##name = dlsym(lib, #name))) { LOG("missing %s", #name); return JNI_FALSE; }
#define JF(name) Java_net_kdt_pojavlaunch_runelite_sound_AAudioLine_##name

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
    L(AAudioStream_getSampleRate)
    return JNI_TRUE;
}

// format: 0 = PCM16, 1 = float. Returns the stream handle or 0.
JNIEXPORT jlong JNICALL JF(nOpen)(JNIEnv *e, jclass c, jint rate, jint channels, jint bufferFrames) {
    AAudioStreamBuilder *b; AAudioStream *s = NULL;
    if (p_AAudio_createStreamBuilder(&b) != AAUDIO_OK) return 0;
    p_AAudioStreamBuilder_setSampleRate(b, rate);
    p_AAudioStreamBuilder_setChannelCount(b, channels);
    p_AAudioStreamBuilder_setFormat(b, AAUDIO_FORMAT_PCM_I16);
    p_AAudioStreamBuilder_setBufferCapacityInFrames(b, bufferFrames * 2);
    p_AAudioStreamBuilder_setPerformanceMode(b, AAUDIO_PERFORMANCE_MODE_NONE);
    p_AAudioStreamBuilder_setUsage(b, AAUDIO_USAGE_GAME);
    p_AAudioStreamBuilder_setContentType(b, AAUDIO_CONTENT_TYPE_SONIFICATION);
    aaudio_result_t r = p_AAudioStreamBuilder_openStream(b, &s);
    p_AAudioStreamBuilder_delete(b);
    if (r != AAUDIO_OK) { LOG("openStream %d Hz %d ch: %d", rate, channels, r); return 0; }
    p_AAudioStream_setBufferSizeInFrames(s, bufferFrames);
    LOG("opened %d Hz (device %d) %d ch, buffer %d frames (capacity %d)", rate, p_AAudioStream_getSampleRate(s), channels,
        bufferFrames, p_AAudioStream_getBufferCapacityInFrames(s));
    return (jlong) (intptr_t) s;
}

JNIEXPORT jint JNICALL JF(nWrite)(JNIEnv *e, jclass c, jlong h, jbyteArray data, jint off, jint frames, jint frameSize, jlong timeoutNanos) {
    jbyte *buf = (*e)->GetByteArrayElements(e, data, NULL);
    aaudio_result_t r = p_AAudioStream_write((AAudioStream *) (intptr_t) h, buf + off, frames, timeoutNanos);
    (*e)->ReleaseByteArrayElements(e, data, buf, JNI_ABORT);
    return r;
}

JNIEXPORT jlong JNICALL JF(nQueued)(JNIEnv *e, jclass c, jlong h) {
    AAudioStream *s = (AAudioStream *) (intptr_t) h;
    int64_t q = p_AAudioStream_getFramesWritten(s) - p_AAudioStream_getFramesRead(s);
    return q < 0 ? 0 : q;
}

JNIEXPORT jlong JNICALL JF(nFramesRead)(JNIEnv *e, jclass c, jlong h) { return p_AAudioStream_getFramesRead((AAudioStream *) (intptr_t) h); }
JNIEXPORT void JNICALL JF(nStart)(JNIEnv *e, jclass c, jlong h) { p_AAudioStream_requestStart((AAudioStream *) (intptr_t) h); }
JNIEXPORT void JNICALL JF(nPause)(JNIEnv *e, jclass c, jlong h) { p_AAudioStream_requestPause((AAudioStream *) (intptr_t) h); }
JNIEXPORT void JNICALL JF(nFlush)(JNIEnv *e, jclass c, jlong h) { p_AAudioStream_requestFlush((AAudioStream *) (intptr_t) h); }
JNIEXPORT void JNICALL JF(nClose)(JNIEnv *e, jclass c, jlong h) {
    AAudioStream *s = (AAudioStream *) (intptr_t) h;
    p_AAudioStream_requestStop(s);
    p_AAudioStream_close(s);
}
