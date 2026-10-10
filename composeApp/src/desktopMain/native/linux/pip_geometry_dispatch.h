#pragma once
#include <atomic>
#include <chrono>
#include <cstdint>
#include <functional>
#include <future>
#include <memory>
#include <optional>

// PiP only: all queued captures own their lifetime. Timeout never leaves references
// to the JNI caller's stack. A cancelled/expired queued request is a no-op.
template<typename T, typename Post, typename Current, typename Action>
std::optional<T> dispatchPiPGeometry(Post post, Current current, Action action,
        std::chrono::nanoseconds timeout = std::chrono::milliseconds(100)) {
    using Clock = std::chrono::steady_clock;
    struct Request {
        std::atomic<bool> cancelled{false};
        Clock::time_point deadline;
        std::promise<std::optional<T>> result;
    };
    auto request = std::make_shared<Request>();
    request->deadline = Clock::now() + timeout;
    auto result = request->result.get_future();
    auto valid = [request, current] {
        return !request->cancelled.load() && Clock::now() < request->deadline && current();
    };
    post([request, action, valid] {
        try {
            std::optional<T> value;
            if (valid()) {
                auto completed = action(valid);
                if (valid()) value = std::move(completed);
            }
            request->result.set_value(std::move(value));
        } catch (...) { request->result.set_exception(std::current_exception()); }
    });
    if (result.wait_until(request->deadline) != std::future_status::ready) {
        request->cancelled = true;
        return std::nullopt;
    }
    if (!valid()) return std::nullopt;
    return result.get();
}

// A chrome acquisition may finish after the caller times out. The FIFO GTK queue
// owns both the token and deferred rollback, including that in-flight case.
template<typename Post, typename Acquire, typename Release>
uint64_t acquirePiPChrome(Post post, Acquire acquire, Release release,
        std::chrono::nanoseconds timeout = std::chrono::milliseconds(100)) {
    auto token = std::make_shared<uint64_t>(0); // Accessed only by queued work.
    auto result = dispatchPiPGeometry<uint64_t>(post, [] { return true; },
        [token, acquire](auto) { *token = acquire(); return *token; }, timeout);
    if (!result) {
        post([token, release] { if (*token) release(*token); });
        return 0;
    }
    return *result;
}
