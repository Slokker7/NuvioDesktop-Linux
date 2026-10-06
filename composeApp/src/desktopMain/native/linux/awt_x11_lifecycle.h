#pragma once

#include <jni.h>
#include <jawt.h>
#include <X11/Xlib.h>
#include <mutex>
#include <stdexcept>

// OpenJDK's XToolkit installs its handler once. mpv 0.41's X11 VO replaces
// it and resets it to Xlib's fatal default during teardown. Keep the real
// toolkit handler, rather than introducing another error handler or ignoring errors.
class LinuxAwtX11Lifecycle {
public:
    static void prepare(JNIEnv *env) {
        std::call_once(prepared_, [env] {
            awt_.version = JAWT_VERSION_1_4;
            if (!JAWT_GetAWT(env, &awt_) || !awt_.Lock || !awt_.Unlock)
                throw std::runtime_error("Linux player shutdown requires the JAWT global lock.");
            jclass wrapper = env->FindClass("sun/awt/X11/XlibWrapper");
            if (!wrapper) throw std::runtime_error("Linux player requires OpenJDK's XToolkit.");
            jmethodID install = env->GetStaticMethodID(wrapper, "SetToolkitErrorHandler", "()J");
            if (!install) {
                env->DeleteLocalRef(wrapper);
                throw std::runtime_error("Unable to access the XToolkit error handler.");
            }
            // Discover the toolkit handler by installing that known, non-default
            // handler and immediately restoring the previous owner. Never set NULL
            // to inspect ownership. The caller owns the process-wide Linux X11
            // lifetime reservation; this runs before the first mpv instance exists.
            {
                LinuxAwtX11Lifecycle lock(env);
                jlong previous = env->CallStaticLongMethod(wrapper, install);
                if (env->ExceptionCheck()) {
                    env->DeleteLocalRef(wrapper);
                    throw std::runtime_error("Unable to access the XToolkit error handler.");
                }
                // Reinstalling the same toolkit handler returns its actual address;
                // even an unexpectedly absent previous owner never requires NULL.
                toolkitHandler_ = reinterpret_cast<XErrorHandler>(env->CallStaticLongMethod(wrapper, install));
                if (previous) XSetErrorHandler(reinterpret_cast<XErrorHandler>(previous));
            }
            env->DeleteLocalRef(wrapper);
            if (!toolkitHandler_ || env->ExceptionCheck())
                throw std::runtime_error("Unable to preserve the XToolkit error handler.");
        });
    }

    explicit LinuxAwtX11Lifecycle(JNIEnv *env) : env_(env) { awt_.Lock(env_); }
    ~LinuxAwtX11Lifecycle() {
        // JAWT unlock calls Java; preserve any pending JNI exception around it.
        jthrowable error = env_->ExceptionOccurred();
        if (error) env_->ExceptionClear();
        awt_.Unlock(env_);
        if (error) { env_->Throw(error); env_->DeleteLocalRef(error); }
    }
    LinuxAwtX11Lifecycle(const LinuxAwtX11Lifecycle &) = delete;
    LinuxAwtX11Lifecycle &operator=(const LinuxAwtX11Lifecycle &) = delete;

    // Called on the GTK thread (if initialized), immediately after mpv teardown,
    // while the disposing thread still owns the JAWT global lock. No GTK error
    // trap or AWT request can overlap mpv's temporary default-handler interval.
    // The native lifetime reservation excludes any other Linux mpv player until
    // this restoration and the rest of native shutdown have completed.
    static void restoreToolkitHandler() { XSetErrorHandler(toolkitHandler_); }

private:
    inline static std::once_flag prepared_;
    inline static JAWT awt_{};
    inline static XErrorHandler toolkitHandler_ = nullptr;
    JNIEnv *env_;
};
