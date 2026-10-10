#include "controls_overlay.h"
#include <jni.h>
#include <cstdio>
#include <exception>

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxPiPGeometryNative_begin(JNIEnv *, jobject) {
    return LinuxControlsOverlay::beginPiPGeometry();
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxPiPGeometryNative_cancel(JNIEnv *, jobject, jlong session) {
    LinuxControlsOverlay::cancelPiPGeometry(session);
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxPiPGeometryNative_sample(JNIEnv *env, jobject, jlong session, jlong canvas) {
    if (!canvas) return nullptr;
    try {
        HudGeometry geometry;
        if (!LinuxControlsOverlay::canvasGeometry(session, canvas, geometry)) return nullptr;
        const jint values[] = {geometry.x, geometry.y, geometry.width, geometry.height};
        auto array = env->NewIntArray(4);
        if (array) env->SetIntArrayRegion(array, 0, 4, values);
        return array;
    } catch (const std::exception &error) {
        std::fprintf(stderr, "Linux PiP geometry query: %s\n", error.what());
        return nullptr;
    }
}
extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxPiPGeometryNative_syncHud(
        JNIEnv *, jobject, jlong session, jlong canvas, jint x, jint y, jint width, jint height, jlong remainingNanos) {
    if (!canvas || width <= 0 || height <= 0) return 0;
    try {
        return static_cast<jint>(LinuxControlsOverlay::syncGeometry(session, canvas, HudGeometry{x, y, width, height}, remainingNanos));
    } catch (const std::exception &error) {
        std::fprintf(stderr, "Linux PiP HUD geometry sync: %s\n", error.what());
        return 0;
    }
}
extern "C" JNIEXPORT jintArray JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxPiPGeometryNative_observeHud(
        JNIEnv *env, jobject, jlong session, jlong canvas) {
    try {
        HudGeometry host, overlay;
        if (!LinuxControlsOverlay::observeGeometry(session, canvas, host, overlay)) return nullptr;
        const jint values[] = {host.x, host.y, host.width, host.height,
            overlay.x, overlay.y, overlay.width, overlay.height};
        auto array = env->NewIntArray(8);
        if (array) env->SetIntArrayRegion(array, 0, 8, values);
        return array;
    } catch (const std::exception &error) {
        std::fprintf(stderr, "Linux PiP HUD observation: %s\n", error.what());
        return nullptr;
    }
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxPiPGeometryNative_setHudSuppressed(
        JNIEnv *, jobject, jlong session, jboolean suppressed) {
    try { return LinuxControlsOverlay::setPiPHudSuppressed(session, suppressed == JNI_TRUE); }
    catch (const std::exception &error) {
        std::fprintf(stderr, "Linux PiP HUD suppression: %s\n", error.what());
        return false;
    }
}
