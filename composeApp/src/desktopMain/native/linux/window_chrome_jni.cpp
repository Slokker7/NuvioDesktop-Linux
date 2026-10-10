#include "window_chrome.h"
#include "controls_overlay.h"
#include "pip_geometry_dispatch.h"
#include <jni.h>
#include <gtk/gtk.h>
#include <gdk/gdkx.h>
#include <X11/Xatom.h>
#include <cstdio>
#include <exception>

namespace {
class X11Backend final : public nuvio::chrome::Backend {
    GdkDisplay *gdk_ = gdk_display_get_default();
    Display *display_ = gdk_x11_display_get_xdisplay(gdk_);
    Atom state_ = XInternAtom(display_, "WM_STATE", False);
    Atom motif_ = XInternAtom(display_, "_MOTIF_WM_HINTS", False);
public:
    bool isFloating(nuvio::chrome::Xid window, int width, int height,
                    int left, int right, int top, int bottom, bool checkFrame) {
        const Atom state = XInternAtom(display_, "_NET_WM_STATE", False);
        const Atom fullscreen = XInternAtom(display_, "_NET_WM_STATE_FULLSCREEN", False);
        const Atom maximizedHorz = XInternAtom(display_, "_NET_WM_STATE_MAXIMIZED_HORZ", False);
        const Atom maximizedVert = XInternAtom(display_, "_NET_WM_STATE_MAXIMIZED_VERT", False);
        const Atom extents = XInternAtom(display_, "_NET_FRAME_EXTENTS", False);
        gdk_x11_display_error_trap_push(gdk_);
        Atom type = None;
        int format = 0;
        unsigned long count = 0, remaining = 0;
        unsigned char *data = nullptr;
        const int status = XGetWindowProperty(display_, window, state, 0, 64, False,
            XA_ATOM, &type, &format, &count, &remaining, &data);
        bool floating = status == Success && !remaining &&
            (type == None || (type == XA_ATOM && format == 32));
        if (floating && type != None) {
            const auto *states = reinterpret_cast<unsigned long *>(data);
            for (unsigned long index = 0; index < count; ++index) {
                if (states[index] == fullscreen || states[index] == maximizedHorz || states[index] == maximizedVert) floating = false;
            }
        }
        if (data) XFree(data);
        data = nullptr;
        const int frameStatus = XGetWindowProperty(display_, window, extents, 0, 4, False,
            XA_CARDINAL, &type, &format, &count, &remaining, &data);
        // Fullscreen ownership/state may clear before XDecoratedPeer receives its restored insets.
        // Those late insets reshape the old windowed bounds; wait for AWT to consume them first.
        bool frameMatches = frameStatus == Success && !remaining && type == None;
        if (frameStatus == Success && !remaining && type == XA_CARDINAL && format == 32 && count == 4) {
            const auto *frame = reinterpret_cast<unsigned long *>(data);
            frameMatches = frame[0] == static_cast<unsigned long>(left) && frame[1] == static_cast<unsigned long>(right) &&
                frame[2] == static_cast<unsigned long>(top) && frame[3] == static_cast<unsigned long>(bottom);
        }
        if (data) XFree(data);
        XWindowAttributes attributes{};
        // Zero size requests a WM-state-only round trip. Post-chrome PiP verifies
        // the actual Canvas separately; managed-client/AWT inset accounting can lag.
        const bool geometryMatches = (width == 0 && height == 0) ||
            (XGetWindowAttributes(display_, window, &attributes) &&
                attributes.width == width && attributes.height == height);
        const int error = gdk_x11_display_error_trap_pop(gdk_);
        return !error && floating && (!checkFrame || frameMatches) && geometryMatches;
    }
    bool ancestry(nuvio::chrome::Xid window, bool &managed, nuvio::chrome::Xid &parent) override {
        gdk_x11_display_error_trap_push(gdk_);
        XWindowAttributes attributes{};
        Atom type = None;
        int format = 0;
        unsigned long count = 0, remaining = 0;
        unsigned char *data = nullptr;
        const int status = XGetWindowProperty(display_, window, state_, 0, 2, False,
            state_, &type, &format, &count, &remaining, &data);
        managed = status == Success && type == state_ && format == 32 && count == 2;
        if (data) XFree(data);
        Window root = None, ancestor = None, *children = nullptr;
        unsigned int childCount = 0;
        const bool valid = XGetWindowAttributes(display_, window, &attributes) &&
            XQueryTree(display_, window, &root, &ancestor, &children, &childCount);
        if (children) XFree(children);
        parent = ancestor == root ? None : ancestor;
        const int error = gdk_x11_display_error_trap_pop(gdk_);
        managed = managed && !attributes.override_redirect;
        return valid && !error;
    }
    bool read(nuvio::chrome::Xid window, nuvio::chrome::Hints &hints) override {
        gdk_x11_display_error_trap_push(gdk_);
        Atom type = None;
        int format = 0;
        unsigned long count = 0, remaining = 0;
        unsigned char *data = nullptr;
        const int status = XGetWindowProperty(display_, window, motif_, 0, 64, False,
            AnyPropertyType, &type, &format, &count, &remaining, &data);
        const int error = gdk_x11_display_error_trap_pop(gdk_);
        const bool valid = !error && status == Success && !remaining &&
            (type == None || (type == motif_ && format == 32 && count >= 5));
        if (valid) {
            hints.present = type != None;
            if (hints.present) {
                auto *words = reinterpret_cast<unsigned long *>(data);
                hints.words.assign(words, words + count); // Xlib represents format-32 items as longs.
            }
        }
        if (data) XFree(data);
        return valid;
    }
    bool write(nuvio::chrome::Xid window, const nuvio::chrome::Hints &hints) override {
        gdk_x11_display_error_trap_push(gdk_);
        if (hints.present) XChangeProperty(display_, window, motif_, motif_, 32, PropModeReplace,
            reinterpret_cast<const unsigned char *>(hints.words.data()), hints.words.size());
        else XDeleteProperty(display_, window, motif_);
        // PropertyNotify is the WM refresh request; never unmap/remap or reparent the client.
        XFlush(display_);
        return gdk_x11_display_error_trap_pop(gdk_) == 0;
    }
};
nuvio::chrome::Sessions sessions; // Accessed exclusively on the existing GTK thread.
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxWindowChromeNative_isFloating(JNIEnv *, jobject, jlong source,
        jint width, jint height, jint left, jint right, jint top, jint bottom, jboolean checkFrame) {
    bool floating = false;
    if (!source) return JNI_FALSE;
    try {
        floating = LinuxControlsOverlay::pipWindowTask([=] {
            X11Backend backend;
            const auto client = sessions.resolve(backend, static_cast<unsigned long>(source));
            return client && backend.isFloating(client, width, height, left, right, top, bottom, checkFrame == JNI_TRUE);
        });
    } catch (const std::exception &error) { std::fprintf(stderr, "Linux compact chrome: %s\n", error.what()); }
    return floating ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxWindowChromeNative_begin(JNIEnv *, jobject, jlong source) {
    uint64_t token = 0;
    if (!source) return 0;
    try {
        token = acquirePiPChrome(postOnLinuxGtkThread, [source] {
            X11Backend backend;
            return sessions.begin(backend, static_cast<unsigned long>(source));
        }, [source](uint64_t expired) {
            X11Backend backend;
            if (!sessions.end(backend, expired, static_cast<unsigned long>(source)))
                std::fprintf(stderr, "Linux compact chrome: timed-out entry rollback failed\n");
        });
    } catch (const std::exception &error) { std::fprintf(stderr, "Linux compact chrome: %s\n", error.what()); }
    return static_cast<jlong>(token);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxWindowChromeNative_end(JNIEnv *, jobject, jlong token, jlong source) {
    bool success = false;
    if (!token) return JNI_TRUE;
    try {
        // A geometry timeout must not immediately block the EDT again during rollback.
        success = LinuxControlsOverlay::pipWindowTask([=] {
            X11Backend backend;
            const bool restored = sessions.end(backend, token, source);
            if (!restored) std::fprintf(stderr, "Linux compact chrome: restoration failed\n");
            return restored;
        });
    } catch (const std::exception &error) { std::fprintf(stderr, "Linux compact chrome: %s\n", error.what()); }
    return success ? JNI_TRUE : JNI_FALSE;
}
