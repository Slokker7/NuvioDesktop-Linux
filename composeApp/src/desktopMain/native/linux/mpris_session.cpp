#include <jni.h>
#include <gio/gio.h>
#include <algorithm>
#include <cmath>
#include <cstdio>
#include <memory>
#include <string>
#include <thread>

namespace {
constexpr auto path = "/org/mpris/MediaPlayer2";
constexpr auto rootInterface = "org.mpris.MediaPlayer2";
constexpr auto playerInterface = "org.mpris.MediaPlayer2.Player";
constexpr auto xml = R"(<node>
<interface name="org.mpris.MediaPlayer2">
 <method name="Raise"/><method name="Quit"/>
 <property name="CanQuit" type="b" access="read"/>
 <property name="CanRaise" type="b" access="read"/>
 <property name="HasTrackList" type="b" access="read"/>
 <property name="Identity" type="s" access="read"/>
 <property name="SupportedUriSchemes" type="as" access="read"/>
 <property name="SupportedMimeTypes" type="as" access="read"/>
</interface>
<interface name="org.mpris.MediaPlayer2.Player">
 <method name="Play"/><method name="Pause"/><method name="PlayPause"/>
 <method name="Stop"/><method name="Next"/><method name="Previous"/>
 <method name="Seek"><arg name="Offset" type="x" direction="in"/></method>
 <method name="SetPosition"><arg name="TrackId" type="o" direction="in"/><arg name="Position" type="x" direction="in"/></method>
 <method name="OpenUri"><arg name="Uri" type="s" direction="in"/></method>
 <signal name="Seeked"><arg name="Position" type="x"/></signal>
 <property name="PlaybackStatus" type="s" access="read"/>
 <property name="Metadata" type="a{sv}" access="read"/>
 <property name="Position" type="x" access="read"><annotation name="org.freedesktop.DBus.Property.EmitsChangedSignal" value="false"/></property>
 <property name="CanPlay" type="b" access="read"/>
 <property name="CanPause" type="b" access="read"/>
 <property name="CanSeek" type="b" access="read"/>
 <property name="CanGoNext" type="b" access="read"/>
 <property name="CanGoPrevious" type="b" access="read"/>
 <property name="CanControl" type="b" access="read"><annotation name="org.freedesktop.DBus.Property.EmitsChangedSignal" value="false"/></property>
 <property name="Rate" type="d" access="readwrite"/>
 <property name="MinimumRate" type="d" access="read"/>
 <property name="MaximumRate" type="d" access="read"/>
 <property name="Volume" type="d" access="readwrite"/>
</interface></node>)";

struct State {
    gint64 token = 0, position = 0, duration = 0, seekSerial = 0;
    std::string track, title, album, artwork, status = "Stopped";
    int season = 0, episode = 0;
    bool seek = false, next = false, previous = false;
    double rate = 1, volume = 1;
    bool active() const { return !track.empty(); }
};

struct Service {
    JavaVM *vm = nullptr;
    jobject sink = nullptr, lostCallback = nullptr;
    jmethodID command = nullptr, run = nullptr;
    GMainContext *context = g_main_context_new();
    GMainLoop *loop = g_main_loop_new(context, false);
    GDBusConnection *bus = nullptr;
    GDBusNodeInfo *info = nullptr;
    guint registrations[2] = {};
    std::thread watcher;
    State state; // Confined to context; no mpv pointers or controller JNI references.
    bool lost = false;

    template<typename F> void java(F fn) {
        JNIEnv *env = nullptr;
        if (vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK) return;
        fn(env);
        if (env->ExceptionCheck()) env->ExceptionClear();
        vm->DetachCurrentThread();
    }
    void notifyLost() {
        if (lost) return;
        lost = true;
        java([&](JNIEnv *env) { env->CallVoidMethod(lostCallback, run); });
    }
    void send(const char *method, double value = 0, const char *track = "") {
        if (lost || !state.active()) return;
        java([&](JNIEnv *env) {
            auto name = env->NewStringUTF(method);
            auto id = env->NewStringUTF(track);
            env->CallVoidMethod(sink, command, static_cast<jlong>(state.token), name, value, id);
            if (env->ExceptionCheck()) env->ExceptionClear();
            env->DeleteLocalRef(name); env->DeleteLocalRef(id);
        });
    }
    GVariant *property(const char *iface, const char *name) {
        const std::string n(name);
        if (std::string(iface) == rootInterface) {
            if (n == "Identity") return g_variant_new_string("Nuvio");
            if (n == "SupportedUriSchemes" || n == "SupportedMimeTypes") return g_variant_new_strv(nullptr, 0);
            return g_variant_new_boolean(false);
        }
        if (n == "PlaybackStatus") return g_variant_new_string(state.status.c_str());
        if (n == "Position") return g_variant_new_int64(state.position);
        if (n == "Rate") return g_variant_new_double(state.rate);
        if (n == "MinimumRate") return g_variant_new_double(0.5);
        if (n == "MaximumRate") return g_variant_new_double(4.0);
        if (n == "Volume") return g_variant_new_double(state.volume);
        if (n == "Metadata") {
            GVariantBuilder b;
            g_variant_builder_init(&b, G_VARIANT_TYPE_VARDICT);
            if (state.active()) {
                g_variant_builder_add(&b, "{sv}", "mpris:trackid", g_variant_new_object_path(state.track.c_str()));
                g_variant_builder_add(&b, "{sv}", "xesam:title", g_variant_new_string(state.title.c_str()));
                if (!state.album.empty()) g_variant_builder_add(&b, "{sv}", "xesam:album", g_variant_new_string(state.album.c_str()));
                if (!state.artwork.empty()) g_variant_builder_add(&b, "{sv}", "mpris:artUrl", g_variant_new_string(state.artwork.c_str()));
                if (state.duration > 0) g_variant_builder_add(&b, "{sv}", "mpris:length", g_variant_new_int64(state.duration));
                // MPRIS allows application-specific keys. There is no standard season-number key.
                if (state.season > 0) g_variant_builder_add(&b, "{sv}", "nuvio:seasonNumber", g_variant_new_int32(state.season));
                if (state.episode > 0) g_variant_builder_add(&b, "{sv}", "xesam:trackNumber", g_variant_new_int32(state.episode));
            }
            return g_variant_builder_end(&b);
        }
        if (n == "CanControl") return g_variant_new_boolean(true);
        if (n == "CanGoNext") return g_variant_new_boolean(state.active() && state.next);
        if (n == "CanGoPrevious") return g_variant_new_boolean(state.active() && state.previous);
        if (n == "CanSeek") return g_variant_new_boolean(state.active() && state.seek);
        return g_variant_new_boolean(state.active()); // CanPlay/CanPause
    }
    void publish(State value) {
        const auto old = state;
        GVariantBuilder changed;
        g_variant_builder_init(&changed, G_VARIANT_TYPE_VARDICT);
        // Compare actual typed values, so rapid updates replace the complete metadata map.
        constexpr const char *names[] = {"PlaybackStatus", "Metadata", "CanPlay", "CanPause",
            "CanSeek", "CanGoNext", "CanGoPrevious", "Rate", "Volume"};
        GVariant *before[9];
        for (int i = 0; i < 9; ++i) before[i] = g_variant_ref_sink(property(playerInterface, names[i]));
        state = std::move(value);
        for (int i = 0; i < 9; ++i) {
            auto *after = g_variant_ref_sink(property(playerInterface, names[i]));
            if (!g_variant_equal(before[i], after)) g_variant_builder_add(&changed, "{sv}", names[i], after);
            g_variant_unref(before[i]); g_variant_unref(after);
        }
        auto *values = g_variant_ref_sink(g_variant_builder_end(&changed));
        if (!lost && g_variant_n_children(values)) g_dbus_connection_emit_signal(bus, nullptr, path,
            "org.freedesktop.DBus.Properties", "PropertiesChanged",
            g_variant_new("(s@a{sv}@as)", playerInterface, g_variant_ref(values), g_variant_new_strv(nullptr, 0)), nullptr);
        g_variant_unref(values);
        if (!lost && state.active() && (state.seekSerial != old.seekSerial ||
            (state.track == old.track && std::abs(state.position - old.position) > 2000000))) {
            g_dbus_connection_emit_signal(bus, nullptr, path, playerInterface, "Seeked",
                g_variant_new("(x)", state.position), nullptr);
        }
    }
    ~Service() {
        if (watcher.joinable()) {
            auto *stop = g_idle_source_new();
            g_source_set_callback(stop, +[](gpointer p) -> gboolean {
                g_main_loop_quit(static_cast<Service *>(p)->loop); return G_SOURCE_REMOVE;
            }, this, nullptr);
            g_source_attach(stop, context); g_source_unref(stop);
            watcher.join(); // All callbacks/publications finish before deleting JNI refs.
        }
        if (bus) {
            g_signal_handlers_disconnect_by_data(bus, this);
            for (auto id : registrations) if (id) g_dbus_connection_unregister_object(bus, id);
            g_dbus_connection_close_sync(bus, nullptr, nullptr); // Releases the name, even on failure.
            g_object_unref(bus);
        }
        if (info) g_dbus_node_info_unref(info);
        JNIEnv *env = nullptr;
        if (vm && vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) == JNI_OK) {
            if (sink) env->DeleteGlobalRef(sink);
            if (lostCallback) env->DeleteGlobalRef(lostCallback);
        }
        // Destroy any queued publication sources while their Service still exists.
        g_main_loop_unref(loop); g_main_context_unref(context);
    }
};

void unsupported(GDBusMethodInvocation *call) {
    g_dbus_method_invocation_return_dbus_error(call, "org.freedesktop.DBus.Error.NotSupported", "Unsupported Nuvio operation");
}
const GDBusInterfaceVTable vtable = {
    +[](GDBusConnection *, const gchar *, const gchar *, const gchar *iface, const gchar *method,
        GVariant *args, GDBusMethodInvocation *call, gpointer data) {
        auto &s = *static_cast<Service *>(data);
        const std::string m(method);
        if (std::string(iface) != playerInterface || m == "Stop" || m == "OpenUri") { unsupported(call); return; }
        if (s.state.active()) {
            if (m == "Seek" && s.state.seek) {
                gint64 offset; g_variant_get(args, "(x)", &offset);
                s.send(method, static_cast<double>(offset));
            } else if (m == "SetPosition" && s.state.seek) {
                const char *track; gint64 position;
                g_variant_get(args, "(&ox)", &track, &position);
                if (s.state.track == track && position >= 0 && position <= s.state.duration)
                    s.send(method, static_cast<double>(position), track);
            } else if ((m == "Next" && s.state.next) || (m == "Previous" && s.state.previous) ||
                       m == "Play" || m == "Pause" || m == "PlayPause") s.send(method);
        }
        g_dbus_method_invocation_return_value(call, nullptr);
    },
    +[](GDBusConnection *, const gchar *, const gchar *, const gchar *iface, const gchar *name,
        GError **, gpointer data) -> GVariant * { return static_cast<Service *>(data)->property(iface, name); },
    +[](GDBusConnection *, const gchar *, const gchar *, const gchar *, const gchar *name,
        GVariant *value, GError **error, gpointer data) -> gboolean {
        auto &s = *static_cast<Service *>(data);
        double number = g_variant_get_double(value);
        if (!std::isfinite(number)) {
            g_set_error_literal(error, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS, "Value must be finite"); return false;
        }
        if (std::string(name) == "Volume") s.send(name, std::max(0.0, std::min(2.0, number)));
        else if (number == 0) s.send("Pause");
        else if (number >= 0.5 && number <= 4.0) s.send(name, number);
        else { g_set_error_literal(error, G_DBUS_ERROR, G_DBUS_ERROR_INVALID_ARGS, "Rate outside supported range"); return false; }
        return true;
    }, {}
};

// GetStringUTFChars uses modified UTF-8 (not valid D-Bus UTF-8 for emoji). Convert UTF-16 explicitly.
std::string utf8(JNIEnv *env, jstring value) {
    if (!value) return {};
    auto *chars = env->GetStringChars(value, nullptr);
    if (!chars) return {};
    auto *converted = g_utf16_to_utf8(reinterpret_cast<const gunichar2 *>(chars), env->GetStringLength(value), nullptr, nullptr, nullptr);
    env->ReleaseStringChars(value, chars);
    std::string result = converted ? converted : "";
    g_free(converted); return result;
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxMprisNative_acquire(JNIEnv *env, jobject, jobject sink, jobject lost, jstring name, jstring busAddress) {
    auto s = std::make_unique<Service>();
    env->GetJavaVM(&s->vm);
    s->sink = env->NewGlobalRef(sink); s->lostCallback = env->NewGlobalRef(lost);
    auto cls = env->GetObjectClass(sink);
    s->command = env->GetMethodID(cls, "command", "(JLjava/lang/String;DLjava/lang/String;)V"); env->DeleteLocalRef(cls);
    cls = env->GetObjectClass(lost);
    s->run = env->GetMethodID(cls, "run", "()V"); env->DeleteLocalRef(cls);
    if (!s->sink || !s->lostCallback || !s->command || !s->run) return 0;
    const auto busName = utf8(env, name);
    GError *error = nullptr;
    g_main_context_push_thread_default(s->context);
    auto *address = busAddress ? g_strdup(utf8(env, busAddress).c_str()) :
        g_dbus_address_get_for_bus_sync(G_BUS_TYPE_SESSION, nullptr, &error);
    if (address) {
        s->bus = g_dbus_connection_new_for_address_sync(address,
            static_cast<GDBusConnectionFlags>(G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT | G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION),
            nullptr, nullptr, &error);
        g_free(address);
    }
    if (s->bus) {
        g_dbus_connection_set_exit_on_close(s->bus, false);
        s->info = g_dbus_node_info_new_for_xml(xml, &error);
        if (s->info) for (int i = 0; i < 2 && !error; ++i)
            s->registrations[i] = g_dbus_connection_register_object(s->bus, path, s->info->interfaces[i], &vtable, s.get(), nullptr, &error);
        GVariant *reply = !error ? g_dbus_connection_call_sync(s->bus, "org.freedesktop.DBus", "/org/freedesktop/DBus",
            "org.freedesktop.DBus", "RequestName", g_variant_new("(su)", busName.c_str(), 4u),
            G_VARIANT_TYPE("(u)"), G_DBUS_CALL_FLAGS_NONE, 1500, nullptr, &error) : nullptr;
        guint result = 0;
        if (reply) { g_variant_get(reply, "(u)", &result); g_variant_unref(reply); }
        // DO_NOT_QUEUE: a second application cannot steal or queue behind the existing owner.
        if (result != 1) { g_main_context_pop_thread_default(s->context); g_clear_error(&error); return 0; }
        g_signal_connect(s->bus, "closed", G_CALLBACK(+[](GDBusConnection *, gboolean, GError *, gpointer data) {
            static_cast<Service *>(data)->notifyLost();
        }), s.get());
    }
    g_main_context_pop_thread_default(s->context);
    if (!s->bus || error) { g_clear_error(&error); std::fprintf(stderr, "Linux MPRIS unavailable; playback continues\n"); return 0; }
    s->watcher = std::thread([p = s.get()] {
        g_main_context_push_thread_default(p->context);
        g_main_loop_run(p->loop);
        g_main_context_pop_thread_default(p->context);
    });
    return reinterpret_cast<jlong>(s.release());
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxMprisNative_publish(JNIEnv *env, jobject, jlong handle, jlong token,
    jstring track, jstring title, jstring album, jstring artwork, jint season, jint episode, jstring status,
    jlong duration, jlong position, jboolean seek, jboolean next, jboolean previous, jdouble rate, jdouble volume, jlong seekSerial) {
    if (!handle) return;
    auto *s = reinterpret_cast<Service *>(handle);
    struct Update { Service *service; State state; };
    auto *u = new Update{s, {token, position, duration, seekSerial, utf8(env, track), utf8(env, title),
        utf8(env, album), utf8(env, artwork), utf8(env, status), season, episode,
        static_cast<bool>(seek), static_cast<bool>(next), static_cast<bool>(previous), rate, volume}};
    auto *source = g_idle_source_new();
    g_source_set_callback(source, +[](gpointer data) -> gboolean {
        auto *u = static_cast<Update *>(data); u->service->publish(std::move(u->state)); return G_SOURCE_REMOVE;
    }, u, +[](gpointer data) { delete static_cast<Update *>(data); });
    g_source_attach(source, s->context); g_source_unref(source);
}

extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxMprisNative_release(JNIEnv *, jobject, jlong handle) {
    if (handle) delete reinterpret_cast<Service *>(handle);
}
