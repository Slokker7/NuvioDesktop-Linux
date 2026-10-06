#include <jni.h>
#include <jawt.h>
#include <jawt_md.h>

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxAwtViewResolver_resolveX11Drawable(
    JNIEnv *env, jobject, jobject component
) {
    // The Kotlin entry point checks the X11 toolkit and EDT before entering JAWT.
    JAWT awt{};
    awt.version = JAWT_VERSION_1_4;
    JAWT_DrawingSurface *surface = nullptr;
    JAWT_DrawingSurfaceInfo *info = nullptr;
    bool locked = false;
    jlong drawable = 0;
    if (JAWT_GetAWT(env, &awt)) {
        surface = awt.GetDrawingSurface(env, component);
        if (surface) {
            locked = (surface->Lock(surface) & JAWT_LOCK_ERROR) == 0;
            if (locked) {
                info = surface->GetDrawingSurfaceInfo(surface);
                if (info && info->platformInfo) {
                    auto *x11 = static_cast<JAWT_X11DrawingSurfaceInfo *>(info->platformInfo);
                    if (x11->display) drawable = static_cast<jlong>(x11->drawable);
                }
            }
        }
    }
    if (info) surface->FreeDrawingSurfaceInfo(info);
    if (locked) surface->Unlock(surface);
    if (surface) awt.FreeDrawingSurface(surface);
    if (!drawable && !env->ExceptionCheck()) {
        jclass error = env->FindClass("java/lang/IllegalStateException");
        if (error) {
            env->ThrowNew(error, "JAWT did not provide an X11 drawable for the Linux AWT Canvas.");
            env->DeleteLocalRef(error);
        }
    }
    return drawable;
}
