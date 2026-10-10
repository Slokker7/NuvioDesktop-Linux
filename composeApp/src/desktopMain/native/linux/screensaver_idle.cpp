// Read-only idle queries, independent of playback inhibition and player/GTK window lifetimes.
#include <jni.h>
#include <gio/gio.h>
#include <cstdint>
#include <cstring>
#include <limits>
#include "dbus_connection.h"
#ifdef NUVIO_HAVE_XSS
#include <dlfcn.h>
#include <X11/Xlib.h>
#include <X11/extensions/scrnsaver.h>
#endif

namespace {
struct IdleProbe {
    GDBusConnection* bus = nullptr;
    int selected = -1;
#ifdef NUVIO_HAVE_XSS
    void* xss = nullptr;
    decltype(&XScreenSaverQueryExtension) queryExtension = nullptr;
    decltype(&XScreenSaverAllocInfo) allocInfo = nullptr;
    decltype(&XScreenSaverQueryInfo) queryInfo = nullptr;
    Display* display = nullptr;
    XScreenSaverInfo* info = nullptr;
#endif
    ~IdleProbe() {
        if (bus) {
            g_dbus_connection_close_sync(bus, nullptr, nullptr);
            g_object_unref(bus);
        }
#ifdef NUVIO_HAVE_XSS
        if (info) XFree(info);
        if (display) XCloseDisplay(display);
        if (xss) dlclose(xss);
#endif
    }

    int64_t query(int backend) {
        if (backend < 3) {
            if (!bus || g_dbus_connection_is_closed(bus)) return -1;
            const bool mutter = backend == 2;
            const char* name = mutter ? "org.gnome.Mutter.IdleMonitor" : "org.freedesktop.ScreenSaver";
            const char* path = mutter ? "/org/gnome/Mutter/IdleMonitor/Core" :
                (backend == 0 ? "/org/freedesktop/ScreenSaver" : "/ScreenSaver");
            GError* error = nullptr;
            GVariant* reply = g_dbus_connection_call_sync(bus, name, path, name,
                mutter ? "GetIdletime" : "GetSessionIdleTime", nullptr,
                mutter ? G_VARIANT_TYPE("(t)") : G_VARIANT_TYPE("(u)"),
                G_DBUS_CALL_FLAGS_NO_AUTO_START, 500, nullptr, &error);
            if (error) g_error_free(error);
            if (!reply) return -1;
            guint64 milliseconds = 0;
            if (mutter) g_variant_get(reply, "(t)", &milliseconds);
            else {
                guint seconds = 0;
                g_variant_get(reply, "(u)", &seconds);
                milliseconds = static_cast<guint64>(seconds) * 1000;
            }
            g_variant_unref(reply);
            return milliseconds <= static_cast<guint64>(std::numeric_limits<int64_t>::max()) ?
                static_cast<int64_t>(milliseconds) : -1;
        }
#ifdef NUVIO_HAVE_XSS
        // XWayland does not supply a global Wayland idle clock. Never treat it as one.
        const char* session = g_getenv("XDG_SESSION_TYPE");
        if (g_getenv("WAYLAND_DISPLAY") || !session || std::strcmp(session, "x11") != 0) return -1;
        if (!xss) {
            // An optional idle capability must not become a player-library load dependency.
            xss = dlopen("libXss.so.1", RTLD_LAZY | RTLD_LOCAL);
            if (!xss) return -1;
            queryExtension = reinterpret_cast<decltype(queryExtension)>(dlsym(xss, "XScreenSaverQueryExtension"));
            allocInfo = reinterpret_cast<decltype(allocInfo)>(dlsym(xss, "XScreenSaverAllocInfo"));
            queryInfo = reinterpret_cast<decltype(queryInfo)>(dlsym(xss, "XScreenSaverQueryInfo"));
        }
        if (!queryExtension || !allocInfo || !queryInfo) return -1;
        if (!display) {
            display = XOpenDisplay(nullptr);
            if (!display) return -1;
            int event = 0, error = 0;
            if (!queryExtension(display, &event, &error)) return -1;
            info = allocInfo();
        }
        if (info && queryInfo(display, DefaultRootWindow(display), info))
            return static_cast<int64_t>(info->idle);
#endif
        return -1;
    }
};
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_screensaver_LinuxIdleNative_open(JNIEnv*, jobject) {
    auto* probe = new IdleProbe();
    // Do not auto-launch a session bus in headless/dev environments.
    if (g_getenv("DBUS_SESSION_BUS_ADDRESS")) {
        GError* error = nullptr;
        // Private connection: disposal cannot affect another GIO session-bus user.
        probe->bus = nuvio::dbus::connect(G_BUS_TYPE_SESSION, 1500, &error);
        if (error) g_error_free(error);
    }
    return reinterpret_cast<jlong>(probe);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_screensaver_LinuxIdleNative_query(JNIEnv*, jobject, jlong handle) {
    auto* probe = reinterpret_cast<IdleProbe*>(handle);
    if (!probe) return -1;
    if (probe->selected >= 0) {
        const auto idle = probe->query(probe->selected);
        if (idle >= 0) return idle;
        probe->selected = -1;
    }
    for (int backend = 0; backend < 4; ++backend) {
        const auto idle = probe->query(backend);
        if (idle >= 0) {
            probe->selected = backend;
            return idle;
        }
    }
    return -1;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_screensaver_LinuxIdleNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<IdleProbe*>(handle);
}
