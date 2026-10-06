#include <jni.h>
#include <mpv/client.h>

namespace {
void throwUnsupported(JNIEnv *env) {
    jclass exceptionClass = env->FindClass("java/lang/UnsupportedOperationException");
    if (exceptionClass) {
        env->ThrowNew(exceptionClass, "Linux native video embedding is not implemented yet.");
        env->DeleteLocalRef(exceptionClass);
    }
}
}

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    // Verify real libmpv linkage and allocation/cleanup without initializing outputs or
    // opening media. A successful library load does not imply playback support.
    mpv_handle *probe = mpv_create();
    if (!probe) return JNI_ERR;
    mpv_terminate_destroy(probe);
    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_create(
    JNIEnv *env,
    jobject /* bridge */,
    jlong /* hostViewPtr */,
    jstring /* sourceUrl */,
    jstring /* sourceAudioUrl */,
    jobjectArray /* headerLines */,
    jboolean /* playWhenReady */,
    jlong /* initialPositionMs */,
    jdouble /* initialProgressFraction */,
    jstring /* controlsPageUrl */,
    jboolean /* nvidiaRtxSuperResolutionEnabled */,
    jboolean /* nvidiaRtxHdrEnabled */,
    jboolean /* isAnimeContent */,
    jstring /* animeSvpFilter */,
    jobjectArray /* extraMpvOptions */,
    jobject /* eventSink */
) {
    // Keep the complete shared ABI. Returning a headless handle here would incorrectly
    // claim that the requested source was attached to a rendering surface.
    throwUnsupported(env);
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_dispose(
    JNIEnv *env,
    jobject /* bridge */,
    jlong handle
) {
    if (handle == 0) return;
    // This milestone never hands a player handle to Kotlin.
    throwUnsupported(env);
}
