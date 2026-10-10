#include <jni.h>
#include <gio/gio.h>
#include <gio/gunixfdlist.h>
#include <unistd.h>
#include <atomic>
#include <cstdio>
#include <memory>
#include <stdexcept>
#include <string>
#include <thread>
#include "dbus_connection.h"

namespace {
constexpr int timeoutMs = 1500;
constexpr const char *reason = "Nuvio playback";
constexpr const char *portal = "org.freedesktop.portal.Desktop";
constexpr const char *saver = "org.freedesktop.ScreenSaver";
constexpr const char *login = "org.freedesktop.login1";
using Variant = std::unique_ptr<GVariant, decltype(&g_variant_unref)>;

Variant call(GDBusConnection *bus, const char *name, const char *path, const char *iface,
             const char *method, GVariant *args, const GVariantType *result) {
    GError *error = nullptr;
    auto *value = g_dbus_connection_call_sync(bus, name, path, iface, method, args, result,
                                             G_DBUS_CALL_FLAGS_NONE, timeoutMs, nullptr, &error);
    if (!value) {
        // Service/method only: no bus address, stream URL, credentials or machine path in logs.
        g_clear_error(&error);
        throw std::runtime_error(std::string(name) + "." + method + " unavailable");
    }
    return Variant(value, g_variant_unref);
}

GDBusConnection *connectBus(GBusType type) {
    GError *error = nullptr;
    auto *bus = nuvio::dbus::connect(type, timeoutMs, &error);
    if (!bus) { g_clear_error(&error); throw std::runtime_error("D-Bus connection failed"); }
    return bus;
}

std::string serviceOwner(GDBusConnection *bus, const char *service) {
    // Activation is capability based; no desktop/environment-name guessing.
    try { call(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
               "StartServiceByName", g_variant_new("(su)", service, 0), G_VARIANT_TYPE("(u)")); }
    catch (const std::exception &) { /* An already-running service need not be activatable. */ }
    auto reply = call(bus, "org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus",
                      "GetNameOwner", g_variant_new("(s)", service), G_VARIANT_TYPE("(s)"));
    const char *owner = nullptr;
    g_variant_get(reply.get(), "(&s)", &owner);
    return owner;
}

struct Lease {
    JavaVM *vm = nullptr;
    jobject callback = nullptr;
    GMainContext *context = g_main_context_new();
    GMainLoop *loop = g_main_loop_new(context, false);
    GDBusConnection *session = nullptr, *system = nullptr;
    std::string owner, systemOwner, request;
    guint cookie = 0, ownerSignal = 0, systemSignal = 0, responseSignal = 0;
    bool hasCookie = false;
    int fd = -1, response = -1;
    std::atomic<bool> lost{false};
    std::atomic<bool> monitoring{false};
    std::thread watcher;

    void notifyLost() {
        if (lost.exchange(true)) return;
        std::fprintf(stderr, "Linux inhibition: backend disconnected; ownership invalidated\n");
        if (!monitoring.load()) { g_main_loop_quit(loop); return; }
        JNIEnv *env = nullptr;
        if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return;
        auto cls = env->GetObjectClass(callback);
        auto run = cls ? env->GetMethodID(cls, "run", "()V") : nullptr;
        if (run) env->CallVoidMethod(callback, run);
        if (env->ExceptionCheck()) env->ExceptionClear();
        if (cls) env->DeleteLocalRef(cls);
        vm->DetachCurrentThread();
    }

    void watch(GDBusConnection *bus, const char *name, guint &subscription) {
        g_signal_connect(bus, "closed", G_CALLBACK(+[](GDBusConnection *, gboolean, GError *, gpointer data) {
            static_cast<Lease *>(data)->notifyLost();
        }), this);
        subscription = g_dbus_connection_signal_subscribe(bus, "org.freedesktop.DBus", "org.freedesktop.DBus",
            "NameOwnerChanged", "/org/freedesktop/DBus", name, G_DBUS_SIGNAL_FLAGS_NONE,
            +[](GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *, GVariant *, gpointer data) {
                static_cast<Lease *>(data)->notifyLost();
            }, this, nullptr);
    }

    void startWatcher() {
        monitoring = true;
        watcher = std::thread([this] {
            g_main_context_push_thread_default(context);
            g_main_loop_run(loop);
            g_main_context_pop_thread_default(context);
        });
    }

    ~Lease() {
        if (watcher.joinable()) {
            // Attach a source, not invoke(): invoke may execute inline before the loop starts.
            auto *stop = g_idle_source_new();
            g_source_set_callback(stop, +[](gpointer data) -> gboolean {
                g_main_loop_quit(static_cast<Lease *>(data)->loop); return G_SOURCE_REMOVE;
            }, this, nullptr);
            g_source_attach(stop, context);
            g_source_unref(stop);
            watcher.join();
        }
        auto detach = [this](GDBusConnection *bus, guint signal) {
            if (!bus) return;
            g_signal_handlers_disconnect_by_data(bus, this);
            if (signal) g_dbus_connection_signal_unsubscribe(bus, signal);
        };
        detach(session, ownerSignal);
        detach(system, systemSignal);
        if (session && responseSignal) g_dbus_connection_signal_unsubscribe(session, responseSignal);
        // Use the unique owner, never a restarted service's potentially recycled cookie/handle.
        if (session && !g_dbus_connection_is_closed(session)) {
            try {
                if (!request.empty()) call(session, owner.c_str(), request.c_str(),
                    "org.freedesktop.portal.Request", "Close", nullptr, G_VARIANT_TYPE_UNIT);
                if (hasCookie) call(session, owner.c_str(), "/org/freedesktop/ScreenSaver",
                    saver, "UnInhibit", g_variant_new("(u)", cookie), G_VARIANT_TYPE_UNIT);
            } catch (const std::exception &) { /* Connection close is the final ownership barrier. */ }
        }
        if (fd >= 0) close(fd);
        for (auto *bus : {session, system}) if (bus) {
            g_dbus_connection_close_sync(bus, nullptr, nullptr);
            g_object_unref(bus);
        }
        if (callback) {
            JNIEnv *env = nullptr;
            if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK)
                env->DeleteGlobalRef(callback);
        }
        g_main_loop_unref(loop);
        g_main_context_unref(context);
    }
};

struct ContextScope {
    GMainContext *context;
    explicit ContextScope(GMainContext *value) : context(value) { g_main_context_push_thread_default(context); }
    ~ContextScope() { g_main_context_pop_thread_default(context); }
};

void acquirePortal(Lease &lease) {
    lease.owner = serviceOwner(lease.session, portal);
    lease.watch(lease.session, portal, lease.ownerSignal);
    // Subscribe before calling Inhibit: Response can precede the method reply.
    lease.responseSignal = g_dbus_connection_signal_subscribe(lease.session, lease.owner.c_str(),
        "org.freedesktop.portal.Request", "Response", nullptr, nullptr, G_DBUS_SIGNAL_FLAGS_NONE,
        +[](GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *, GVariant *args, gpointer data) {
            auto &state = *static_cast<Lease *>(data);
            guint response = 2;
            g_variant_get_child(args, 0, "u", &response);
            state.response = static_cast<int>(response);
            g_main_loop_quit(state.loop);
        }, &lease, nullptr);
    GVariantBuilder options;
    g_variant_builder_init(&options, G_VARIANT_TYPE_VARDICT);
    g_variant_builder_add(&options, "{sv}", "handle_token", g_variant_new_string("nuvio_playback"));
    g_variant_builder_add(&options, "{sv}", "reason", g_variant_new_string(reason));
    auto reply = call(lease.session, lease.owner.c_str(), "/org/freedesktop/portal/desktop",
        "org.freedesktop.portal.Inhibit", "Inhibit", g_variant_new("(su@a{sv})", "", 12, g_variant_builder_end(&options)),
        G_VARIANT_TYPE("(o)"));
    const char *handle = nullptr;
    g_variant_get(reply.get(), "(&o)", &handle);
    lease.request = handle;
    auto *timeout = g_timeout_source_new(timeoutMs);
    g_source_set_callback(timeout, +[](gpointer data) -> gboolean {
        g_main_loop_quit(static_cast<Lease *>(data)->loop); return G_SOURCE_REMOVE;
    }, &lease, nullptr);
    g_source_attach(timeout, lease.context);
    g_main_loop_run(lease.loop);
    g_source_destroy(timeout);
    g_source_unref(timeout);
    g_dbus_connection_signal_unsubscribe(lease.session, lease.responseSignal);
    lease.responseSignal = 0;
    if (lease.response != 0 || lease.lost) throw std::runtime_error("portal refused/timed out");
}

void acquireFallback(Lease &lease) {
    lease.owner = serviceOwner(lease.session, saver);
    lease.watch(lease.session, saver, lease.ownerSignal);
    auto reply = call(lease.session, lease.owner.c_str(), "/org/freedesktop/ScreenSaver", saver,
                      "Inhibit", g_variant_new("(ss)", "Nuvio", reason), G_VARIANT_TYPE("(u)"));
    g_variant_get(reply.get(), "(u)", &lease.cookie);
    lease.hasCookie = true;
    // ScreenSaver alone does not guarantee that the session's automatic suspend is inhibited.
    // Only accept the fallback when the complementary logind idle/sleep lock also succeeds.
    lease.system = connectBus(G_BUS_TYPE_SYSTEM);
    lease.systemOwner = serviceOwner(lease.system, login);
    lease.watch(lease.system, login, lease.systemSignal);
    GUnixFDList *fds = nullptr;
    GError *error = nullptr;
    Variant lock(g_dbus_connection_call_with_unix_fd_list_sync(lease.system, lease.systemOwner.c_str(),
        "/org/freedesktop/login1", "org.freedesktop.login1.Manager", "Inhibit",
        g_variant_new("(ssss)", "idle:sleep", "Nuvio", reason, "block"), G_VARIANT_TYPE("(h)"),
        G_DBUS_CALL_FLAGS_NONE, timeoutMs, nullptr, &fds, nullptr, &error), g_variant_unref);
    if (!lock) { g_clear_error(&error); if (fds) g_object_unref(fds); throw std::runtime_error("logind lock unavailable"); }
    gint index = -1;
    g_variant_get(lock.get(), "(h)", &index);
    lease.fd = fds ? g_unix_fd_list_get(fds, index, &error) : -1;
    if (fds) g_object_unref(fds);
    if (lease.fd < 0) { g_clear_error(&error); throw std::runtime_error("logind returned no lock FD"); }
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxInhibitionNative_acquire(JNIEnv *env, jobject, jobject callback) {
    for (bool portalAttempt : {true, false}) {
        try {
            auto lease = std::make_unique<Lease>();
            env->GetJavaVM(&lease->vm);
            lease->callback = env->NewGlobalRef(callback);
            if (!lease->callback) return 0;
            {
                ContextScope context(lease->context);
                lease->session = connectBus(G_BUS_TYPE_SESSION);
                if (portalAttempt) acquirePortal(*lease); else acquireFallback(*lease);
            }
            lease->startWatcher();
            std::fprintf(stderr, "Linux inhibition: acquired %s\n", portalAttempt ? "portal Idle+Suspend" : "ScreenSaver+logind idle:sleep");
            return reinterpret_cast<jlong>(lease.release());
        } catch (const std::exception &error) {
            std::fprintf(stderr, "Linux inhibition: %s failed (%s)\n", portalAttempt ? "portal" : "fallback", error.what());
        }
    }
    std::fprintf(stderr, "Linux inhibition: no supported backend; playback continues\n");
    return 0;
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxInhibitionNative_release(JNIEnv *, jobject, jlong handle) {
    if (!handle) return;
    delete reinterpret_cast<Lease *>(handle);
    std::fprintf(stderr, "Linux inhibition: released\n");
}
