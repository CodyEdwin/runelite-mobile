#include <jni.h>
#include <assert.h>
#include <string.h>
#include <stdio.h>
#include <stdlib.h>
#include <unistd.h>
#include <sched.h>
#include <sys/syscall.h>
#include <android/bitmap.h>

static JavaVM* dalvikJavaVMPtr;

static JavaVM* runtimeJavaVMPtr;
static JNIEnv* runtimeJNIEnvPtr_GRAPHICS;
static JNIEnv* runtimeJNIEnvPtr_INPUT;
jclass class_CTCScreen;
jmethodID method_GetRGB;

jclass class_CTCAndroidInput;
jmethodID method_ReceiveInput;

jclass class_AwtHost;
jmethodID method_OpenLink;
jmethodID method_OpenPath;
jmethodID method_QuerySystemClipboard;
jmethodID method_PutClipboardData;

jclass class_Frame;
jclass class_Rectangle;
jclass class_CTCClipboard = NULL;
jmethodID constructor_Rectangle;
jmethodID method_GetFrames;
jmethodID method_GetBounds;
jmethodID method_SetBounds;
jmethodID method_SystemClipboardDataReceived = NULL;

jfieldID field_x;
jfieldID field_y;

jint JNI_OnLoad(JavaVM* vm, void* reserved) {
    if (dalvikJavaVMPtr == NULL) {
        //Save dalvik global JavaVM pointer
        dalvikJavaVMPtr = vm;
        JNIEnv *env = NULL;
        (*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_4);
        class_AwtHost = (*env)->NewGlobalRef(env,(*env)->FindClass(env, "com/codyedwin/runelitemobile/AwtHost"));
        method_OpenLink= (*env)->GetStaticMethodID(env, class_AwtHost, "openLink", "(Ljava/lang/String;)V");
        method_OpenPath= (*env)->GetStaticMethodID(env, class_AwtHost, "openLink", "(Ljava/lang/String;)V");
        method_QuerySystemClipboard = (*env)->GetStaticMethodID(env, class_AwtHost, "querySystemClipboard", "()V");
        method_PutClipboardData = (*env)->GetStaticMethodID(env, class_AwtHost, "putClipboardData", "(Ljava/lang/String;Ljava/lang/String;)V");
    } else if (dalvikJavaVMPtr != vm) {
        runtimeJavaVMPtr = vm;
    }

    return JNI_VERSION_1_4;
}

JNIEXPORT void JNICALL Java_com_codyedwin_runelitemobile_AWTInputBridge_nativeSendData(JNIEnv* env, jclass clazz, jint type, jint i1, jint i2, jint i3, jint i4) {
    if (runtimeJNIEnvPtr_INPUT == NULL) {
        if (runtimeJavaVMPtr == NULL) {
            return;
        } else {
            (*runtimeJavaVMPtr)->AttachCurrentThread(runtimeJavaVMPtr, &runtimeJNIEnvPtr_INPUT, NULL);
        }
    }

    if (method_ReceiveInput == NULL) {
        class_CTCAndroidInput = (*runtimeJNIEnvPtr_INPUT)->FindClass(runtimeJNIEnvPtr_INPUT, "net/java/openjdk/cacio/ctc/CTCAndroidInput");
        if ((*runtimeJNIEnvPtr_INPUT)->ExceptionCheck(runtimeJNIEnvPtr_INPUT) == JNI_TRUE) {
            (*runtimeJNIEnvPtr_INPUT)->ExceptionClear(runtimeJNIEnvPtr_INPUT);
            class_CTCAndroidInput = (*runtimeJNIEnvPtr_INPUT)->FindClass(runtimeJNIEnvPtr_INPUT, "com/github/caciocavallosilano/cacio/ctc/CTCAndroidInput");
        }
        assert(class_CTCAndroidInput != NULL);
        method_ReceiveInput = (*runtimeJNIEnvPtr_INPUT)->GetStaticMethodID(runtimeJNIEnvPtr_INPUT, class_CTCAndroidInput, "receiveData", "(IIIII)V");
        assert(method_ReceiveInput != NULL);
    }
    (*runtimeJNIEnvPtr_INPUT)->CallStaticVoidMethod(
        runtimeJNIEnvPtr_INPUT,
        class_CTCAndroidInput,
        method_ReceiveInput,
        type, i1, i2, i3, i4
    );
}

// TODO: check for memory leaks
// int printed = 0;
int threadAttached = 0;
JNIEXPORT jintArray JNICALL Java_com_codyedwin_runelitemobile_utils_JREUtils_renderAWTScreenFrame(JNIEnv* env, jclass clazz /*, jobject canvas, jint width, jint height */) {
    if (runtimeJNIEnvPtr_GRAPHICS == NULL) {
        if (runtimeJavaVMPtr == NULL) {
            return NULL;
        } else {
            (*runtimeJavaVMPtr)->AttachCurrentThread(runtimeJavaVMPtr, &runtimeJNIEnvPtr_GRAPHICS, NULL);
        }
    }

    int *rgbArray;
    jintArray jreRgbArray, androidRgbArray;
  
    if (method_GetRGB == NULL) {
        class_CTCScreen = (*runtimeJNIEnvPtr_GRAPHICS)->FindClass(runtimeJNIEnvPtr_GRAPHICS, "net/java/openjdk/cacio/ctc/CTCScreen");
        if ((*runtimeJNIEnvPtr_GRAPHICS)->ExceptionCheck(runtimeJNIEnvPtr_GRAPHICS) == JNI_TRUE) {
            (*runtimeJNIEnvPtr_GRAPHICS)->ExceptionClear(runtimeJNIEnvPtr_GRAPHICS);
            class_CTCScreen = (*runtimeJNIEnvPtr_GRAPHICS)->FindClass(runtimeJNIEnvPtr_GRAPHICS, "com/github/caciocavallosilano/cacio/ctc/CTCScreen");
        }
        assert(class_CTCScreen != NULL);
        method_GetRGB = (*runtimeJNIEnvPtr_GRAPHICS)->GetStaticMethodID(runtimeJNIEnvPtr_GRAPHICS, class_CTCScreen, "getCurrentScreenRGB", "()[I");
        assert(method_GetRGB != NULL);
    }
    jreRgbArray = (jintArray) (*runtimeJNIEnvPtr_GRAPHICS)->CallStaticObjectMethod(
        runtimeJNIEnvPtr_GRAPHICS,
        class_CTCScreen,
        method_GetRGB
    );
    if (jreRgbArray == NULL) {
        if ((*runtimeJNIEnvPtr_GRAPHICS)->ExceptionCheck(runtimeJNIEnvPtr_GRAPHICS)) {
            (*runtimeJNIEnvPtr_GRAPHICS)->ExceptionClear(runtimeJNIEnvPtr_GRAPHICS);
        }
        return NULL;
    }

    // Copy JRE RGB array memory to Android. This thread stays attached to the second JVM,
    // so the JRE local reference must be released explicitly after every frame.
    int arrayLength = (*runtimeJNIEnvPtr_GRAPHICS)->GetArrayLength(runtimeJNIEnvPtr_GRAPHICS, jreRgbArray);
    rgbArray = (*runtimeJNIEnvPtr_GRAPHICS)->GetIntArrayElements(runtimeJNIEnvPtr_GRAPHICS, jreRgbArray, 0);
    if (rgbArray == NULL) {
        (*runtimeJNIEnvPtr_GRAPHICS)->DeleteLocalRef(runtimeJNIEnvPtr_GRAPHICS, jreRgbArray);
        return NULL;
    }
    androidRgbArray = (*env)->NewIntArray(env, arrayLength);
    if (androidRgbArray != NULL) {
        (*env)->SetIntArrayRegion(env, androidRgbArray, 0, arrayLength, rgbArray);
    }

    (*runtimeJNIEnvPtr_GRAPHICS)->ReleaseIntArrayElements(runtimeJNIEnvPtr_GRAPHICS, jreRgbArray, rgbArray, JNI_ABORT);
    (*runtimeJNIEnvPtr_GRAPHICS)->DeleteLocalRef(runtimeJNIEnvPtr_GRAPHICS, jreRgbArray);

    return androidRgbArray;
}

static jclass class_CTCScreenGlobal = NULL;
static jmethodID method_GetPixelsIfChanged = NULL;
static jobject screenLockGlobal = NULL;

/*
 * Copy the cacio screen straight into an Android ARGB_8888 bitmap, only when it changed.
 * Returns 1 when the bitmap was updated, 0 when the screen is unchanged (or not ready), and -N
 * (N = pixel count of the screen) when the bitmap does not match the screen size.
 *
 * Replaces renderAWTScreenFrame's per-frame path (screen -> int[] copy in the JRE, JNI copy,
 * a new 3 MB Android int[] every frame, Bitmap#setPixels) which used ~75% of a big core plus ART GC
 * time on the same core as the game's Client thread.
 */
JNIEXPORT jint JNICALL Java_com_codyedwin_runelitemobile_utils_JREUtils_renderAWTScreenFrameToBitmap(JNIEnv* env, jclass clazz, jobject bitmap, jboolean force) {
    if (runtimeJNIEnvPtr_GRAPHICS == NULL) {
        if (runtimeJavaVMPtr == NULL) return 0;
        (*runtimeJavaVMPtr)->AttachCurrentThread(runtimeJavaVMPtr, &runtimeJNIEnvPtr_GRAPHICS, NULL);
    }
    JNIEnv* jre = runtimeJNIEnvPtr_GRAPHICS;
    if (method_GetPixelsIfChanged == NULL) {
        jclass cls = (*jre)->FindClass(jre, "com/github/caciocavallosilano/cacio/ctc/CTCScreen");
        if (cls == NULL || (*jre)->ExceptionCheck(jre)) { (*jre)->ExceptionClear(jre); return 0; }
        jmethodID m = (*jre)->GetStaticMethodID(jre, cls, "getScreenPixelsIfChanged", "(Z)[I");
        jfieldID f = m == NULL ? NULL : (*jre)->GetStaticFieldID(jre, cls, "SCREEN_LOCK", "Ljava/lang/Object;");
        if (m == NULL || f == NULL || (*jre)->ExceptionCheck(jre)) { (*jre)->ExceptionClear(jre); (*jre)->DeleteLocalRef(jre, cls); return 0; }
        jobject lock = (*jre)->GetStaticObjectField(jre, cls, f);
        screenLockGlobal = (*jre)->NewGlobalRef(jre, lock);
        (*jre)->DeleteLocalRef(jre, lock);
        class_CTCScreenGlobal = (*jre)->NewGlobalRef(jre, cls);
        (*jre)->DeleteLocalRef(jre, cls);
        method_GetPixelsIfChanged = m;
    }
    AndroidBitmapInfo info;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS
            || info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) return 0;

    jint result = 0;
    (*jre)->MonitorEnter(jre, screenLockGlobal);
    jintArray pixels = (jintArray) (*jre)->CallStaticObjectMethod(jre, class_CTCScreenGlobal, method_GetPixelsIfChanged, force);
    if ((*jre)->ExceptionCheck(jre)) { (*jre)->ExceptionClear(jre); pixels = NULL; }
    if (pixels != NULL) {
        jsize length = (*jre)->GetArrayLength(jre, pixels);
        if ((jsize) (info.width * info.height) != length) {
            result = -length;
        } else {
            void* dst = NULL;
            if (AndroidBitmap_lockPixels(env, bitmap, &dst) == ANDROID_BITMAP_RESULT_SUCCESS) {
                const uint32_t* src = (*jre)->GetPrimitiveArrayCritical(jre, pixels, NULL);
                if (src != NULL) {
                    const uint32_t w = info.width, h = info.height;
                    for (uint32_t y = 0; y < h; y++) {
                        const uint32_t* s = src + (size_t) y * w;
                        uint32_t* d = (uint32_t*) ((char*) dst + (size_t) y * info.stride);
                        for (uint32_t x = 0; x < w; x++) {
                            uint32_t p = s[x]; /* 0xAARRGGBB -> RGBA bytes (0xAABBGGRR), opaque */
                            d[x] = 0xFF000000u | (p & 0x0000FF00u) | ((p >> 16) & 0xFFu) | ((p & 0xFFu) << 16);
                        }
                    }
                    (*jre)->ReleasePrimitiveArrayCritical(jre, pixels, (void*) src, JNI_ABORT);
                    result = 1;
                }
                AndroidBitmap_unlockPixels(env, bitmap);
            }
        }
        (*jre)->DeleteLocalRef(jre, pixels);
    }
    (*jre)->MonitorExit(jre, screenLockGlobal);
    return result;
}

/* sched_setaffinity for a thread of this process (tid), mask = bit per CPU. */
JNIEXPORT jint JNICALL Java_com_codyedwin_runelitemobile_utils_JREUtils_setThreadAffinity(JNIEnv* env, jclass clazz, jint tid, jlong mask) {
    unsigned long set = (unsigned long) mask; /* kernel cpumask: bit i = cpu i */
    return (jint) syscall(__NR_sched_setaffinity, (pid_t) tid, sizeof(set), &set);
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCClipboard_nQuerySystemClipboard(JNIEnv *env, jclass clazz) {
    JNIEnv *dalvikEnv;char detachable = 0;
    if((*dalvikJavaVMPtr)->GetEnv(dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*dalvikJavaVMPtr)->AttachCurrentThread(dalvikJavaVMPtr, &dalvikEnv, NULL);
        detachable = 1;
    }
    if(method_SystemClipboardDataReceived == NULL) {
        class_CTCClipboard = (*env)->NewGlobalRef(env, clazz);
        method_SystemClipboardDataReceived = (*env)->GetStaticMethodID(env, clazz, "systemClipboardDataReceived", "(Ljava/lang/String;Ljava/lang/String;)V");
    }
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_AwtHost, method_QuerySystemClipboard);
    if(detachable) (*dalvikJavaVMPtr)->DetachCurrentThread(dalvikJavaVMPtr);
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCClipboard_nPutClipboardData(JNIEnv* env, jclass clazz, jstring clipboardData, jstring clipboardDataMime) {
    JNIEnv *dalvikEnv;char detachable = 0;
    if((*dalvikJavaVMPtr)->GetEnv(dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*dalvikJavaVMPtr)->AttachCurrentThread(dalvikJavaVMPtr, &dalvikEnv, NULL);
        detachable = 1;
    }

    const char* dataChars = (*env)->GetStringUTFChars(env, clipboardData, NULL);
    const char* mimeChars = (*env)->GetStringUTFChars(env, clipboardDataMime, NULL);
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_AwtHost, method_PutClipboardData,
                                       (*dalvikEnv)->NewStringUTF(dalvikEnv, dataChars),
                                       (*dalvikEnv)->NewStringUTF(dalvikEnv, mimeChars));
    (*env)->ReleaseStringUTFChars(env, clipboardData, dataChars);
    (*env)->ReleaseStringUTFChars(env, clipboardDataMime, mimeChars);
    if(detachable) (*dalvikJavaVMPtr)->DetachCurrentThread(dalvikJavaVMPtr);
}

JNIEXPORT void JNICALL Java_com_github_caciocavallosilano_cacio_ctc_CTCClipboard_nQuerySystemClipboard(JNIEnv *env, jclass clazz) {
    Java_net_java_openjdk_cacio_ctc_CTCClipboard_nQuerySystemClipboard(env, clazz);
}

JNIEXPORT void JNICALL Java_com_github_caciocavallosilano_cacio_ctc_CTCClipboard_nPutClipboardData(JNIEnv* env, jclass clazz, jstring clipboardData, jstring clipboardDataMime) {
    Java_net_java_openjdk_cacio_ctc_CTCClipboard_nPutClipboardData(env, clazz, clipboardData, clipboardDataMime);
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openFile(JNIEnv *env, jclass clazz, jstring filePath) {
    JNIEnv *dalvikEnv;char detachable = 0;
    if((*dalvikJavaVMPtr)->GetEnv(dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*dalvikJavaVMPtr)->AttachCurrentThread(dalvikJavaVMPtr, &dalvikEnv, NULL);
        detachable = 1;
    }
    const char* stringChars = (*env)->GetStringUTFChars(env, filePath, NULL);
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_AwtHost, method_OpenPath, (*dalvikEnv)->NewStringUTF(dalvikEnv, stringChars));
    (*env)->ReleaseStringUTFChars(env, filePath, stringChars);
    if(detachable) (*dalvikJavaVMPtr)->DetachCurrentThread(dalvikJavaVMPtr);
}

JNIEXPORT void JNICALL Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openUri(JNIEnv *env, jclass clazz, jstring uri) {
    JNIEnv *dalvikEnv;char detachable = 0;
    if((*dalvikJavaVMPtr)->GetEnv(dalvikJavaVMPtr, (void **) &dalvikEnv, JNI_VERSION_1_6) == JNI_EDETACHED) {
        (*dalvikJavaVMPtr)->AttachCurrentThread(dalvikJavaVMPtr, &dalvikEnv, NULL);
        detachable = 1;
    }
    const char* stringChars = (*env)->GetStringUTFChars(env, uri, NULL);
    (*dalvikEnv)->CallStaticVoidMethod(dalvikEnv, class_AwtHost, method_OpenLink, (*dalvikEnv)->NewStringUTF(dalvikEnv, stringChars));
    (*env)->ReleaseStringUTFChars(env, uri, stringChars);
    if(detachable) (*dalvikJavaVMPtr)->DetachCurrentThread(dalvikJavaVMPtr);
}

// Java 17 caciocavallo (com.github.caciocavallosilano) uses a different package name than the
// Java 8 build above. Without these exports java.awt.Desktop#browse/open could never reach the
// launcher (links, "Add Jagex Account").
JNIEXPORT void JNICALL Java_com_github_caciocavallosilano_cacio_ctc_CTCDesktopPeer_openUri(JNIEnv *env, jclass clazz, jstring uri) {
    Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openUri(env, clazz, uri);
}

JNIEXPORT void JNICALL Java_com_github_caciocavallosilano_cacio_ctc_CTCDesktopPeer_openFile(JNIEnv *env, jclass clazz, jstring filePath) {
    // AwtHost#openLink only treats "file:" URIs as paths.
    const char* pathChars = (*env)->GetStringUTFChars(env, filePath, NULL);
    if (pathChars == NULL) return;
    size_t length = strlen(pathChars) + sizeof("file://");
    char* uriChars = malloc(length);
    if (uriChars != NULL) {
        snprintf(uriChars, length, "file://%s", pathChars);
        jstring uri = (*env)->NewStringUTF(env, uriChars);
        free(uriChars);
        if (uri != NULL) Java_net_java_openjdk_cacio_ctc_CTCDesktopPeer_openUri(env, clazz, uri);
    }
    (*env)->ReleaseStringUTFChars(env, filePath, pathChars);
}

JNIEXPORT void JNICALL Java_com_codyedwin_runelitemobile_AWTInputBridge_nativeClipboardReceived(JNIEnv *env, jclass clazz, jstring clipboardData, jstring clipboardDataMime) {
    if(method_SystemClipboardDataReceived == NULL || class_CTCClipboard == NULL) return;
    if (runtimeJNIEnvPtr_INPUT == NULL) {
        if (runtimeJavaVMPtr == NULL) {
            return;
        } else {
            (*runtimeJavaVMPtr)->AttachCurrentThread(runtimeJavaVMPtr, &runtimeJNIEnvPtr_INPUT, NULL);
        }
    }
    const char* dataChars = clipboardData != NULL ? (*env)->GetStringUTFChars(env, clipboardData, NULL) : NULL;
    const char* mimeChars = clipboardDataMime != NULL ? (*env)->GetStringUTFChars(env, clipboardDataMime, NULL) : NULL;
    (*runtimeJNIEnvPtr_INPUT)->CallStaticVoidMethod(runtimeJNIEnvPtr_INPUT, class_CTCClipboard, method_SystemClipboardDataReceived,
                                                    clipboardData != NULL ? (*runtimeJNIEnvPtr_INPUT)->NewStringUTF(runtimeJNIEnvPtr_INPUT, dataChars) : NULL,
                                                    clipboardDataMime != NULL ? (*runtimeJNIEnvPtr_INPUT)->NewStringUTF(runtimeJNIEnvPtr_INPUT, mimeChars) : NULL);
    if(dataChars != NULL) (*env)->ReleaseStringUTFChars(env, clipboardData, dataChars);
    if(mimeChars != NULL) (*env)->ReleaseStringUTFChars(env, clipboardDataMime, mimeChars);
}

static JNIEnv* attachInputThread() {
    if (runtimeJNIEnvPtr_INPUT == NULL && runtimeJavaVMPtr != NULL) {
        (*runtimeJavaVMPtr)->AttachCurrentThread(runtimeJavaVMPtr, &runtimeJNIEnvPtr_INPUT, NULL);
    }
    return runtimeJNIEnvPtr_INPUT;
}

static void initializeFrameMethods(JNIEnv* env) {
    if (field_y != NULL) return;
    class_Frame = (*env)->FindClass(env, "java/awt/Frame");
    method_GetFrames = (*env)->GetStaticMethodID(env, class_Frame, "getFrames", "()[Ljava/awt/Frame;");
    method_GetBounds = (*env)->GetMethodID(env, class_Frame, "getBounds", "(Ljava/awt/Rectangle;)Ljava/awt/Rectangle;");
    method_SetBounds = (*env)->GetMethodID(env, class_Frame, "setBounds", "(Ljava/awt/Rectangle;)V");
    class_Rectangle = (*env)->FindClass(env, "java/awt/Rectangle");
    constructor_Rectangle = (*env)->GetMethodID(env, class_Rectangle, "<init>", "()V");
    field_x = (*env)->GetFieldID(env, class_Rectangle, "x", "I");
    field_y = (*env)->GetFieldID(env, class_Rectangle, "y", "I");
}

JNIEXPORT void JNICALL
Java_com_codyedwin_runelitemobile_AWTInputBridge_nativeMoveWindow(JNIEnv *env, jclass clazz, jint xoff, jint yoff) {
    if (attachInputThread() == NULL) return;
    initializeFrameMethods(runtimeJNIEnvPtr_INPUT);
    jobject rectangle = (*runtimeJNIEnvPtr_INPUT)->NewObject(runtimeJNIEnvPtr_INPUT, class_Rectangle, constructor_Rectangle);
    jobjectArray frames = (*runtimeJNIEnvPtr_INPUT)->CallStaticObjectMethod(runtimeJNIEnvPtr_INPUT, class_Frame, method_GetFrames);
    for(jsize i = 0; i < (*runtimeJNIEnvPtr_INPUT)->GetArrayLength(runtimeJNIEnvPtr_INPUT, frames); i++) {
        jobject frame = (*runtimeJNIEnvPtr_INPUT)->GetObjectArrayElement(runtimeJNIEnvPtr_INPUT, frames, i);
        (*runtimeJNIEnvPtr_INPUT)->CallObjectMethod(runtimeJNIEnvPtr_INPUT, frame, method_GetBounds, rectangle);
        (*runtimeJNIEnvPtr_INPUT)->SetIntField(runtimeJNIEnvPtr_INPUT, rectangle,  field_x, (*runtimeJNIEnvPtr_INPUT)->GetIntField(runtimeJNIEnvPtr_INPUT, rectangle, field_x) + xoff);
        (*runtimeJNIEnvPtr_INPUT)->SetIntField(runtimeJNIEnvPtr_INPUT, rectangle,  field_y, (*runtimeJNIEnvPtr_INPUT)->GetIntField(runtimeJNIEnvPtr_INPUT, rectangle, field_y) + yoff);
        (*runtimeJNIEnvPtr_INPUT)->CallVoidMethod(runtimeJNIEnvPtr_INPUT, frame, method_SetBounds, rectangle);
        (*runtimeJNIEnvPtr_INPUT)->DeleteLocalRef(runtimeJNIEnvPtr_INPUT, frame);
    }
    (*runtimeJNIEnvPtr_INPUT)->DeleteLocalRef(runtimeJNIEnvPtr_INPUT, rectangle);
    (*runtimeJNIEnvPtr_INPUT)->DeleteLocalRef(runtimeJNIEnvPtr_INPUT, frames);
}
