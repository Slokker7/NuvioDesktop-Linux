#include "controls_overlay.h"
#include "pip_hud_visibility.h"
#include "pip_hud_geometry.h"
#include "pip_geometry_dispatch.h"
#include "window_chrome.h"

#include <gtk/gtk.h>
#include <gdk/gdkx.h>
#include <webkit2/webkit2.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <deque>
#include <future>
#include <mutex>
#include <stdexcept>
#include <thread>

namespace {
// WebKitGTK uses the default GLib context. One process-lifetime thread owns that
// context and all GTK objects. Dispatch with a source (invoke can run inline on
// the caller when the context is temporarily unowned).
class GtkThread {
public:
    static GtkThread &get() {
        static GtkThread instance;
        return instance;
    }

    static void syncIfStarted(std::function<void()> action) {
        if (auto *thread = started_.load()) thread->sync(std::move(action));
        else action(); // Do not initialize GTK for an audio-only/no-HUD player.
    }

    static bool postIfStarted(std::function<void()> action) {
        if (auto *thread = started_.load()) { thread->post(std::move(action)); return true; }
        return false;
    }

    void post(std::function<void()> action) {
        auto *task = new std::function<void()>(std::move(action));
        GSource *source = g_idle_source_new();
        g_source_set_priority(source, G_PRIORITY_DEFAULT);
        g_source_set_callback(source, [](gpointer data) -> gboolean {
            try { (*static_cast<std::function<void()> *>(data))(); }
            catch (const std::exception &error) {
                std::fprintf(stderr, "Linux controls: %s\n", error.what());
            }
            return G_SOURCE_REMOVE;
        }, task, [](gpointer data) { delete static_cast<std::function<void()> *>(data); });
        g_source_attach(source, g_main_context_default());
        g_source_unref(source);
    }

    void sync(std::function<void()> action) {
        if (std::this_thread::get_id() == owner_) { action(); return; }
        auto task = std::make_shared<std::packaged_task<void()>>(std::move(action));
        auto result = task->get_future();
        post([task] { (*task)(); });
        // No AWT round trip or wait for WebKit's subprocess/load/JS completion.
        // JNI create/dispose already run on Kotlin's native lifecycle workers.
        result.get();
    }

private:
    GtkThread() {
        std::promise<void> started;
        auto ready = started.get_future();
        std::thread([started = std::move(started)]() mutable {
            try {
                // Do not change the JVM's locale (mpv requires LC_NUMERIC=C).
                gtk_disable_setlocale();
                gdk_set_allowed_backends("x11");
                if (!gtk_init_check(nullptr, nullptr) ||
                    !GDK_IS_X11_DISPLAY(gdk_display_get_default())) {
                    throw std::runtime_error("GTK controls require an X11/XWayland display.");
                }
                started.set_value();
                gtk_main();
            } catch (...) { started.set_exception(std::current_exception()); }
        }).detach();
        // Initialization failure is reported to create, not to JNI_OnLoad.
        ready.get();
        // Capture the actual owner using the same dispatcher.
        sync([this] { owner_ = std::this_thread::get_id(); });
        started_ = this;
    }
    std::thread::id owner_;
    inline static std::atomic<GtkThread *> started_{nullptr};
};

std::string jsString(const std::string &value) {
    std::string result = "\"";
    const char hex[] = "0123456789abcdef";
    for (size_t i = 0; i < value.size(); ++i) {
        unsigned char ch = value[i];
        if (ch == '"' || ch == '\\') { result += '\\'; result += ch; }
        else if (ch < 0x20) {
            result += "\\u00";
            result += hex[ch >> 4]; result += hex[ch & 15];
        } else if (ch == 0xe2 && i + 2 < value.size() &&
                   static_cast<unsigned char>(value[i + 1]) == 0x80 &&
                   (static_cast<unsigned char>(value[i + 2]) == 0xa8 ||
                    static_cast<unsigned char>(value[i + 2]) == 0xa9)) {
            result += static_cast<unsigned char>(value[i + 2]) == 0xa8 ? "\\u2028" : "\\u2029";
            i += 2;
        } else result += ch;
    }
    return result + '"';
}
}

void postOnLinuxGtkThread(std::function<void()> action) {
    GtkThread::postIfStarted(std::move(action));
}

struct LinuxControlsOverlay::State : std::enable_shared_from_this<State> {
    // Inbox only is shared with JNI callers; all other fields belong to GTK.
    std::mutex inbox;
    std::string latestJson;
    std::deque<std::string> scripts;
    size_t scriptBytes = 0;
    bool deliveryPosted = false;
    std::atomic<bool> closing{false};
    GtkWidget *window = nullptr;
    WebKitWebView *view = nullptr;
    WebKitWebContext *webContext = nullptr;
    WebKitUserContentManager *manager = nullptr;
    Window host = None; // Raw AWT XID only; GDK must never own/query its lifetime.
    GdkDisplay *display = nullptr;
    GCancellable *evaluation = nullptr;
    guint timer = 0;
    bool ready = false;
    PiPHudVisibility pipHudVisibility;
    std::atomic<bool> pipHudSuppressed{false};
    double controlsUiScaleFactor = 1.0; // Transient; the shared snapshot owns the preference.
    bool hostWindowFocused = false; // GTK thread only; start hidden until AWT supplies focus.
    int x = 0, y = 0, width = 0, height = 0, lastScale = 0;
    Message message;
    Snapshot snapshot;

    bool overlayGeometry(HudGeometry &geometry) {
        if (!window || !gtk_widget_get_realized(window)) return false;
        Display *connection = gdk_x11_display_get_xdisplay(display);
        Window xid = gdk_x11_window_get_xid(gtk_widget_get_window(window)), child = None;
        XWindowAttributes attributes{};
        gdk_x11_display_error_trap_push(display);
        const bool found = XGetWindowAttributes(connection, xid, &attributes) &&
            XTranslateCoordinates(connection, xid, attributes.root, 0, 0, &geometry.x, &geometry.y, &child);
        const int error = gdk_x11_display_error_trap_pop(display);
        geometry.width = attributes.width; geometry.height = attributes.height;
        return found && !error;
    }

    void evaluate(const std::string &script) {
        if (!view || closing || !pipHudVisibility.normalWorkAllowed()) return;
        // JNI has no return value. Discard Promise/complex results, which WebKit
        // cannot serialize, without hiding actual script exceptions.
        std::string command = script + "\n;void 0;";
        webkit_web_view_evaluate_javascript(view, command.c_str(), command.size(), nullptr,
            nullptr, evaluation, [](GObject *object, GAsyncResult *result, gpointer) {
                GError *error = nullptr;
                JSCValue *value = webkit_web_view_evaluate_javascript_finish(
                    WEBKIT_WEB_VIEW(object), result, &error);
                if (value) g_object_unref(value);
                if (error) {
                    if (!g_error_matches(error, G_IO_ERROR, G_IO_ERROR_CANCELLED))
                        std::fprintf(stderr, "Linux controls JS: %s\n", error->message);
                    g_error_free(error);
                }
            }, nullptr); // Completion owns no Player/State pointer.
    }

    void deliver() {
        std::string json;
        std::deque<std::string> pending;
        {
            std::lock_guard<std::mutex> lock(inbox);
            deliveryPosted = false;
            if (!ready || closing || !pipHudVisibility.normalWorkAllowed()) return;
            json = latestJson;
            pending.swap(scripts);
            scriptBytes = 0;
        }
        if (!json.empty()) evaluate("window.playerControls(JSON.parse(" + jsString(json) + "))");
        for (const auto &script : pending) evaluate(script);
    }

    void scheduleDelivery() { // Called with inbox locked; coalesce frequent JSON updates.
        if (deliveryPosted || closing) return;
        deliveryPosted = true;
        GtkThread::get().post([self = shared_from_this()] { self->deliver(); });
    }

    static void received(WebKitUserContentManager *, WebKitJavascriptResult *result, gpointer data) {
        auto &self = *static_cast<State *>(data);
        if (self.closing) return;
        JSCValue *object = webkit_javascript_result_get_js_value(result);
        if (!jsc_value_is_object(object)) return;
        JSCValue *type = jsc_value_object_get_property(object, "type");
        JSCValue *value = jsc_value_object_get_property(object, "value");
        if (!jsc_value_is_string(type) ||
            (!jsc_value_is_undefined(value) && !jsc_value_is_number(value))) {
            g_object_unref(type); g_object_unref(value); return;
        }
        gchar *raw = jsc_value_to_string(type);
        std::string name = raw ? raw : "";
        g_free(raw);
        double number = jsc_value_is_number(value) ? jsc_value_to_double(value) : 0.0;
        g_object_unref(type); g_object_unref(value);
        if (name.empty() || name.size() > 128 || !std::isfinite(number)) return;
        // Keep malformed strings out of diagnostics and JNI modified UTF-8.
        if (!std::all_of(name.begin(), name.end(), [](unsigned char ch) {
            return (ch >= 'a' && ch <= 'z') || (ch >= 'A' && ch <= 'Z') ||
                (ch >= '0' && ch <= '9') || ch == '_';
        })) return;
        if (self.pipHudVisibility.suppressed() && name != "controlsReady") return;
        // Visibility/clearance updates can arrive often; log user actions, not frame traffic.
        if (name != "cursorVisibility" && name != "hudSubtitleClearance" &&
            name != "scrubChange" && name != "seekThumbnail")
            std::fprintf(stderr, "Linux controls message: %s\n", name.c_str());
        try {
            if (name == "controlsReady") {
                self.ready = true;
                self.deliver();
                self.syncPlayback();
            } else if (name == "setControlsUiScalePercent") {
                self.controlsUiScaleFactor = std::clamp(1.0 + number / 100.0, 0.5, 1.5);
                self.applyControlsZoom();
            } else if (self.message) self.message(name, number);
        } catch (const std::exception &error) {
            std::fprintf(stderr, "Linux controls message failure: %s\n", error.what());
        }
    }

    bool hostGeometry(XWindowAttributes &attributes, int &rootX, int &rootY) {
        Display *connection = gdk_x11_display_get_xdisplay(display);
        Window child = None;
        gdk_x11_display_error_trap_push(display);
        int found = XGetWindowAttributes(connection, host, &attributes);
        int translated = found ? XTranslateCoordinates(connection, host, attributes.root,
            0, 0, &rootX, &rootY, &child) : 0;
        int error = gdk_x11_display_error_trap_pop(display);
        if (!found || !translated || error) {
            std::fprintf(stderr, "Linux controls: host disappeared\n");
            return false; // Includes disappearance between the two queries.
        }
        return true;
    }

    void applyControlsZoom() { // GTK thread only, including script-message callbacks.
        if (closing || !window || !view) return;
        // Match Windows/macOS's 2x physical baseline without changing overlay bounds.
        // Absolute page zoom keeps WebKit layout and pointer coordinates in agreement.
        const int scale = std::max(1, gtk_widget_get_scale_factor(window));
        const double zoom = (2.0 / scale) * controlsUiScaleFactor;
        if (std::abs(webkit_web_view_get_zoom_level(view) - zoom) > 0.001)
            webkit_web_view_set_zoom_level(view, zoom);
    }

    bool layout(bool force = false, bool geometryOnly = false,
                const HudGeometry *expected = nullptr, const std::function<bool()> &current = [] { return true; }) {
        if (!window || !host || closing) return false;
        if (!geometryOnly && !pipHudVisibility.normalWorkAllowed()) return true;
        if (!geometryOnly) applyControlsZoom(); // No WebKit work in the one-shot PiP path.
        XWindowAttributes attributes{};
        int rootX = 0, rootY = 0;
        if (!hostGeometry(attributes, rootX, rootY)) return false;
        const HudGeometry desired{rootX, rootY, attributes.width, attributes.height};
        HudGeometry actual;
        const bool known = geometryOnly && overlayGeometry(actual);
        if (geometryOnly) {
            if (!current() || !expected || !(desired == *expected) || !known) return false;
            if (desired == actual) return true;
        }
        // Override-redirect bypasses WM stacking: a viewable Canvas alone does
        // not mean Nuvio is foreground. Only AWT's owning-window focus maps HUD.
        const bool canShow = hostWindowFocused && attributes.map_state == IsViewable;
        if (!canShow && !geometryOnly) {
            gtk_widget_hide(window);
            return true;
        }
        int scale = std::max(1, gtk_widget_get_scale_factor(window));
        // X coordinates are physical pixels; GTK/GDK geometry uses logical pixels.
        const auto logical = logicalHudGeometry(desired, scale);
        const int gx = logical.x, gy = logical.y, w = logical.width, h = logical.height;
        GdkWindow *native = gtk_widget_get_window(window);
        if (geometryOnly && !current()) return false;
        gdk_x11_display_error_trap_push(display);
        if (force || rootX != x || rootY != y || attributes.width != width ||
            attributes.height != height || scale != lastScale || !gtk_widget_get_visible(window)) {
            x = rootX; y = rootY; width = attributes.width; height = attributes.height; lastScale = scale;
            std::fprintf(stderr, "Linux controls: root %d,%d size %dx%d (GDK scale %d)\n",
                x, y, width, height, scale);
            gtk_window_move(GTK_WINDOW(window), gx, gy);
            gtk_window_resize(GTK_WINDOW(window), w, h);
            gdk_window_move_resize(native, gx, gy, w, h);
        }
        if (!geometryOnly && canShow && !gtk_widget_get_visible(window)) gtk_widget_show_all(window);
        // Preserve exact physical bounds even when root coordinates/sizes are not
        // divisible by GDK's scale. These requests target only our own toplevel.
        XMoveResizeWindow(gdk_x11_display_get_xdisplay(display), gdk_x11_window_get_xid(native),
            rootX, rootY, attributes.width, attributes.height);
        if (!geometryOnly && canShow) gdk_window_raise(native);
        int error = gdk_x11_display_error_trap_pop(display);
        if (error) {
            std::fprintf(stderr, "Linux controls: overlay geometry update failed\n");
            return false;
        }
        return true;
    }

    void syncPlayback() {
        if (!ready || closing || !snapshot || !pipHudVisibility.normalWorkAllowed()) return;
        std::string state = snapshot();
        if (!state.empty()) evaluate("window.playerUpdate(" + state + ")");
    }

    void create(uint64_t drawable, const std::string &url) {
        display = gdk_display_get_default();
        host = static_cast<Window>(drawable);
        XWindowAttributes attributes{};
        int rootX = 0, rootY = 0;
        if (!hostGeometry(attributes, rootX, rootY))
            throw std::runtime_error("X11 Canvas disappeared before controls creation.");
        GdkScreen *screen = gdk_display_get_default_screen(display);
        if (attributes.root != gdk_x11_window_get_xid(gdk_screen_get_root_window(screen)))
            throw std::runtime_error("X11 Canvas and GTK controls must use the same root screen.");
        GdkVisual *visual = gdk_screen_get_rgba_visual(screen);
        if (!visual) throw std::runtime_error("GTK controls require an X11 RGBA visual.");
        std::fprintf(stderr, "Linux controls: creation (ARGB override-redirect toplevel)\n");
        window = gtk_window_new(GTK_WINDOW_TOPLEVEL);
        g_object_ref_sink(window);
        gtk_window_set_screen(GTK_WINDOW(window), screen);
        gtk_window_set_decorated(GTK_WINDOW(window), FALSE);
        gtk_window_set_skip_taskbar_hint(GTK_WINDOW(window), TRUE);
        gtk_window_set_skip_pager_hint(GTK_WINDOW(window), TRUE);
        gtk_window_set_focus_on_map(GTK_WINDOW(window), FALSE);
        gtk_window_set_accept_focus(GTK_WINDOW(window), FALSE);
        gtk_widget_set_visual(window, visual);
        gtk_widget_set_app_paintable(window, TRUE);
        g_signal_connect(window, "draw", G_CALLBACK(+[](GtkWidget *, cairo_t *cr, gpointer) -> gboolean {
            cairo_save(cr);
            cairo_set_operator(cr, CAIRO_OPERATOR_SOURCE);
            cairo_set_source_rgba(cr, 0, 0, 0, 0);
            cairo_paint(cr);
            cairo_restore(cr);
            return FALSE;
        }), nullptr);
        manager = webkit_user_content_manager_new();
        g_signal_connect(manager, "script-message-received::player", G_CALLBACK(received), this);
        if (!webkit_user_content_manager_register_script_message_handler(manager, "player"))
            throw std::runtime_error("Unable to register WebKit player message handler.");
        // The default context has an atexit unref on the JVM exit thread. Its
        // WebKit timers must instead be destroyed on their owning GTK thread.
        webContext = webkit_web_context_new_ephemeral();
        view = WEBKIT_WEB_VIEW(g_object_new(WEBKIT_TYPE_WEB_VIEW,
            "web-context", webContext, "user-content-manager", manager, nullptr));
        g_object_ref_sink(view); // Retain across native-parent destruction notifications.
        evaluation = g_cancellable_new();
        WebKitSettings *settings = webkit_web_view_get_settings(view);
        webkit_settings_set_allow_file_access_from_file_urls(settings, TRUE);
        // Keep the transparent GTK surface CPU-painted for this spike; mpv's GPU
        // backend is untouched. Avoid an extra opaque accelerated child surface.
        webkit_settings_set_hardware_acceleration_policy(settings, WEBKIT_HARDWARE_ACCELERATION_POLICY_NEVER);
        GdkRGBA transparent{0, 0, 0, 0};
        webkit_web_view_set_background_color(view, &transparent);
        gtk_container_add(GTK_CONTAINER(window), GTK_WIDGET(view));
        g_signal_connect(view, "load-changed", G_CALLBACK(+[](WebKitWebView *, WebKitLoadEvent event, gpointer data) {
            if (event == WEBKIT_LOAD_STARTED) static_cast<State *>(data)->ready = false;
        }), this);
        g_signal_connect(view, "load-failed", G_CALLBACK(+[](WebKitWebView *, WebKitLoadEvent,
            const gchar *, GError *error, gpointer) -> gboolean {
            std::fprintf(stderr, "Linux controls load failed: %s\n", error->message);
            return FALSE;
        }), nullptr);
        g_signal_connect(view, "button-press-event", G_CALLBACK(+[](GtkWidget *widget, GdkEventButton *,
            gpointer) -> gboolean {
            // Internal WebView focus is enough for mouse controls. Leave X11
            // keyboard focus in AWT so a HUD click cannot hide its own window.
            gtk_widget_grab_focus(widget);
            return FALSE;
        }), nullptr);
        gtk_widget_realize(window);
        GdkWindow *native = gtk_widget_get_window(window);
        gdk_x11_display_error_trap_push(display);
        // Keep a root-level ARGB surface so the compositor can blend it over mpv.
        // Set override-redirect before mapping; the WM must not manage this window.
        gdk_window_set_override_redirect(native, TRUE);
        // Override-redirect windows receive no WM top-level frame-drawn replies.
        // Otherwise GDK's frame clock stalls after its first paint, including
        // WebKit viewport updates queued for the next frame.
        gdk_x11_window_set_frame_sync_enabled(native, FALSE);
        int error = gdk_x11_display_error_trap_pop(display);
        if (error) throw std::runtime_error("Unable to configure GTK controls toplevel.");
        if (!layout()) throw std::runtime_error("X11 Canvas disappeared during controls creation.");
        gdk_display_flush(display);
        timer = g_timeout_add(250, [](gpointer data) -> gboolean {
            auto &self = *static_cast<State *>(data);
            // The callback owns removal when it returns REMOVE; do not retain
            // its expired ID for the synchronous shutdown queued behind it.
            if (self.closing) { self.timer = 0; return G_SOURCE_REMOVE; }
            if (!self.layout()) { self.timer = 0; self.destroy(); return G_SOURCE_REMOVE; }
            try { self.syncPlayback(); }
            catch (const std::exception &error) { std::fprintf(stderr, "Linux controls state: %s\n", error.what()); }
            return G_SOURCE_CONTINUE;
        }, this);
        // Kotlin supplies the extracted controls.html URL and sibling assets.
        std::fprintf(stderr, "Linux controls: URL load\n");
        webkit_web_view_load_uri(view, url.c_str());
    }

    void destroy() { // GTK thread only; no subprocess completion is awaited.
        closing = true;
        if (timer) { g_source_remove(timer); timer = 0; }
        message = {}; snapshot = {};
        if (manager) {
            g_signal_handlers_disconnect_by_data(manager, this);
            webkit_user_content_manager_unregister_script_message_handler(manager, "player");
        }
        if (view) g_signal_handlers_disconnect_by_data(view, this);
        if (evaluation) { g_cancellable_cancel(evaluation); g_object_unref(evaluation); evaluation = nullptr; }
        if (window) {
            std::fprintf(stderr, "Linux controls: shutdown\n");
            gtk_widget_destroy(window);
            g_object_unref(window);
            window = nullptr;
        }
        if (view) { g_object_unref(view); view = nullptr; }
        if (webContext) { g_object_unref(webContext); webContext = nullptr; }
        if (manager) { g_object_unref(manager); manager = nullptr; }
        host = None; // No GDK finalizer or X request against the former Canvas.
        std::lock_guard<std::mutex> lock(inbox);
        scripts.clear(); scriptBytes = 0; latestJson.clear();
    }
};

std::unique_ptr<LinuxControlsOverlay> LinuxControlsOverlay::create(
    uint64_t drawable, const std::string &url, Message message, Snapshot snapshot) {
    if (url.empty()) return {}; // Existing audio/JNI smoke test has no controls page.
    auto state = std::make_shared<State>();
    state->message = std::move(message); state->snapshot = std::move(snapshot);
    GtkThread::get().sync([state, drawable, url] {
        try { state->create(drawable, url); }
        catch (...) { state->destroy(); throw; }
        geometryState_ = state;
    });
    return std::unique_ptr<LinuxControlsOverlay>(new LinuxControlsOverlay(std::move(state)));
}

LinuxControlsOverlay::~LinuxControlsOverlay() { close(); }

std::weak_ptr<LinuxControlsOverlay::State> LinuxControlsOverlay::geometryState_;

namespace {
std::atomic<uint64_t> pipSequence{0}, pipSession{0};
struct PiPGeometrySample { bool valid = false; HudGeometry canvas, overlay; };
auto currentPiPSession(uint64_t session) {
    return [session] { return session && pipSession.load() == session; };
}
auto postPiP = [](std::function<void()> action) { GtkThread::postIfStarted(std::move(action)); };
}

uint64_t LinuxControlsOverlay::beginPiPGeometry() {
    const auto session = ++pipSequence;
    pipSession = session;
    return session;
}
void LinuxControlsOverlay::cancelPiPGeometry(uint64_t session) {
    pipSession.compare_exchange_strong(session, 0);
}
bool LinuxControlsOverlay::pipWindowTask(std::function<bool()> query) {
    return dispatchPiPGeometry<bool>(postPiP, [] { return true; },
        [query](auto) { return query(); }).value_or(false);
}

bool LinuxControlsOverlay::setPiPHudSuppressed(uint64_t session, bool suppressed) {
    return dispatchPiPGeometry<bool>(postPiP, currentPiPSession(session), [suppressed](auto current) {
        auto state = geometryState_.lock();
        if (!state || state->closing || !state->window || !current()) return false;
        if (state->pipHudVisibility.suppressed() == suppressed) return true;
        XWindowAttributes canvas{};
        int rootX = 0, rootY = 0;
        if (!suppressed) {
            HudGeometry overlay;
            if (!state->hostGeometry(canvas, rootX, rootY) || !state->overlayGeometry(overlay) ||
                !(overlay == HudGeometry{rootX, rootY, canvas.width, canvas.height})) return false;
        }
        state->pipHudVisibility.setSuppressed(suppressed);
        state->pipHudSuppressed = suppressed;
        if (suppressed) {
            {
                std::lock_guard<std::mutex> lock(state->inbox);
                state->scripts.clear(); state->scriptBytes = 0;
            }
            gtk_widget_hide(state->window);
            // Subtitle rendering remains in mpv; hidden chrome reserves no subtitle clearance.
            if (state->message) state->message("hudSubtitleClearance", 0);
        } else {
            // Latest controls JSON is retained; transient UI scripts are discarded while hidden.
            state->deliver();
            // The Compose payload may still be queued on the EDT. Never expose compact chrome.
            state->evaluate("window.playerControls({pictureInPictureActive:false})");
            state->syncPlayback();
            state->applyControlsZoom();
            // Geometry was aligned while hidden and verified again above. No second resize.
            if (state->hostWindowFocused && canvas.map_state == IsViewable) {
                gtk_widget_show_all(state->window);
                gdk_window_raise(gtk_widget_get_window(state->window));
            }
        }
        gdk_display_flush(state->display);
        return true;
    }).value_or(false);
}

bool LinuxControlsOverlay::canvasGeometry(uint64_t session, uint64_t canvas, HudGeometry &geometry) {
    auto result = dispatchPiPGeometry<PiPGeometrySample>(postPiP, currentPiPSession(session), [canvas](auto) {
        PiPGeometrySample sample;
        auto state = geometryState_.lock();
        if (!state || state->closing || state->host != canvas) return sample;
        XWindowAttributes attributes{};
        sample.valid = state->hostGeometry(attributes, sample.canvas.x, sample.canvas.y) && attributes.map_state == IsViewable;
        sample.canvas.width = attributes.width; sample.canvas.height = attributes.height;
        return sample;
    });
    if (!result || !result->valid) return false;
    geometry = result->canvas;
    return true;
}

PiPHudRequest LinuxControlsOverlay::syncGeometry(uint64_t session, uint64_t canvas, const HudGeometry &expected, int64_t remainingNanos) {
    return dispatchPiPGeometry<PiPHudRequest>(postPiP, currentPiPSession(session), [canvas, expected](auto current) {
        auto state = geometryState_.lock();
        if (!state || state->closing || state->host != canvas) return PiPHudRequest::Failed;
        return requestPiPHudGeometry(expected, [&](HudGeometry &geometry) {
            XWindowAttributes attributes{};
            const bool valid = state->hostGeometry(attributes, geometry.x, geometry.y);
            geometry.width = attributes.width; geometry.height = attributes.height;
            return valid && attributes.map_state == IsViewable;
        }, [&] { return state->layout(true, true, &expected, current); },
        [&](HudGeometry &geometry) { return state->overlayGeometry(geometry); }, current);
    }, std::chrono::nanoseconds(std::clamp<int64_t>(remainingNanos, 0, 100'000'000))).value_or(PiPHudRequest::Failed);
}

bool LinuxControlsOverlay::observeGeometry(uint64_t session, uint64_t canvas, HudGeometry &host, HudGeometry &overlay) {
    auto result = dispatchPiPGeometry<PiPGeometrySample>(postPiP, currentPiPSession(session), [canvas](auto) {
        PiPGeometrySample sample;
        auto state = geometryState_.lock();
        if (!state || state->closing || state->host != canvas) return sample;
        XWindowAttributes attributes{};
        sample.valid = readPiPHudGeometry([&](HudGeometry &host) {
            const bool valid = state->hostGeometry(attributes, host.x, host.y) && attributes.map_state == IsViewable;
            host.width = attributes.width; host.height = attributes.height;
            return valid;
        }, [&](HudGeometry &overlay) { return state->overlayGeometry(overlay); }, sample.canvas, sample.overlay);
        return sample;
    });
    if (!result || !result->valid) return false;
    host = result->canvas; overlay = result->overlay;
    return true;
}

void LinuxControlsOverlay::updateControls(const std::string &json) {
    std::lock_guard<std::mutex> lock(state_->inbox);
    if (state_->closing) return;
    state_->latestJson = json;
    state_->scheduleDelivery();
}

void LinuxControlsOverlay::runJavaScript(const std::string &script) {
    std::lock_guard<std::mutex> lock(state_->inbox);
    if (state_->closing || state_->pipHudSuppressed) return;
    if (state_->scripts.size() >= 32 || script.size() > 1024 * 1024 - state_->scriptBytes)
        throw std::runtime_error("Linux controls startup script queue is full.");
    state_->scripts.push_back(script);
    state_->scriptBytes += script.size();
    state_->scheduleDelivery();
}

void LinuxControlsOverlay::setCursorHidden(bool hidden) {
    GtkThread::get().post([self = state_, hidden] {
        if (self->closing || !self->window) return;
        GdkCursor *cursor = hidden ? gdk_cursor_new_for_display(self->display, GDK_BLANK_CURSOR) : nullptr;
        gdk_window_set_cursor(gtk_widget_get_window(self->window), cursor);
        if (cursor) g_object_unref(cursor);
    });
}

void LinuxControlsOverlay::deliverSeekThumbnail(int64_t positionMs, const std::string &dataUrl,
    std::shared_ptr<std::atomic<uint64_t>> generation, uint64_t request) {
    GtkThread::get().post([self = state_, positionMs, dataUrl, generation = std::move(generation), request] {
        // Recheck at GTK delivery, not only after decoding: a newer request or
        // shutdown may have overtaken this task in the main-context queue.
        if (self->closing || !self->ready || generation->load() != request) return;
        self->evaluate("window.nuvioSeekThumbnailReady && window.nuvioSeekThumbnailReady(" +
            std::to_string(positionMs) + "," + jsString(dataUrl) + ")");
    });
}

void LinuxControlsOverlay::close() {
    if (!state_) return;
    state_->closing = true; // Suppress messages/queued tasks before synchronous GTK teardown.
    GtkThread::get().sync([self = state_] { self->destroy(); });
    state_.reset();
}

void LinuxControlsOverlay::setWindowFocused(bool focused) {
    GtkThread::get().post([self = state_, focused] {
        if (self->closing || !self->window) return;
        self->hostWindowFocused = focused;
        if (self->pipHudVisibility.suppressed()) return;
        if (!focused) gtk_widget_hide(self->window);
        else if (!self->layout(true)) self->destroy();
        gdk_display_flush(self->display);
    });
}

void LinuxControlsOverlay::finishPlayerShutdown(std::function<void()> action) {
    GtkThread::syncIfStarted(std::move(action));
}
