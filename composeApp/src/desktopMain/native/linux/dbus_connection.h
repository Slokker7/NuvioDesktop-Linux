#pragma once

#include <gio/gio.h>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace nuvio::dbus {
// Method timeouts do not cover connection authentication/Hello. Connection setup
// needs cancellation independent of a GLib main loop, on the existing IO worker.
class Deadline {
    GCancellable* cancellable_ = g_cancellable_new();
    std::mutex mutex_;
    std::condition_variable wake_;
    bool finished_ = false;
    std::thread timer_;
public:
    explicit Deadline(int milliseconds) : timer_([this, milliseconds] {
        std::unique_lock<std::mutex> lock(mutex_);
        if (!wake_.wait_for(lock, std::chrono::milliseconds(milliseconds), [this] { return finished_; })) {
            lock.unlock();
            g_cancellable_cancel(cancellable_);
        }
    }) {}
    ~Deadline() {
        { std::lock_guard<std::mutex> lock(mutex_); finished_ = true; }
        wake_.notify_one();
        timer_.join();
        g_object_unref(cancellable_);
    }
    GCancellable* get() const { return cancellable_; }
};

inline GDBusConnection* connect(GBusType type, int timeoutMs, GError** error) {
    Deadline deadline(timeoutMs);
    gchar* address = g_dbus_address_get_for_bus_sync(type, deadline.get(), error);
    if (!address) return nullptr;
    auto* bus = g_dbus_connection_new_for_address_sync(address,
        static_cast<GDBusConnectionFlags>(G_DBUS_CONNECTION_FLAGS_AUTHENTICATION_CLIENT |
                                         G_DBUS_CONNECTION_FLAGS_MESSAGE_BUS_CONNECTION),
        nullptr, deadline.get(), error);
    g_free(address);
    if (bus) g_dbus_connection_set_exit_on_close(bus, false);
    return bus;
}

}
