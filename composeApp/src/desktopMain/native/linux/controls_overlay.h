#pragma once

#include <atomic>
#include <cstdint>
#include <functional>
#include <memory>
#include <string>
#include "pip_hud_geometry.h"

// Linux/X11 only. GTK types and its thread stay outside the shared JNI contract.
class LinuxControlsOverlay {
public:
    using Message = std::function<void(const std::string &, double)>;
    using Snapshot = std::function<std::string()>;

    static std::unique_ptr<LinuxControlsOverlay> create(
        uint64_t drawable, const std::string &url, Message message, Snapshot snapshot);
    static uint64_t beginPiPGeometry();
    static void cancelPiPGeometry(uint64_t session);
    static bool setPiPHudSuppressed(uint64_t session, bool suppressed);
    static bool canvasGeometry(uint64_t session, uint64_t canvas, HudGeometry &geometry);
    static PiPHudRequest syncGeometry(uint64_t session, uint64_t canvas, const HudGeometry &expected, int64_t remainingNanos);
    static bool observeGeometry(uint64_t session, uint64_t canvas, HudGeometry &host, HudGeometry &overlay);
    // Bounded PiP readiness/cleanup only; does not initialize GTK. Captures must own their data.
    static bool pipWindowTask(std::function<bool()> query);
    ~LinuxControlsOverlay();
    void updateControls(const std::string &json);
    void runJavaScript(const std::string &script);
    void deliverSeekThumbnail(int64_t positionMs, const std::string &dataUrl,
        std::shared_ptr<std::atomic<uint64_t>> generation, uint64_t request);
    void setCursorHidden(bool hidden);
    void setWindowFocused(bool focused);
    void close();
    // Serialize mpv's process-global X error-handler teardown with GTK's traps.
    static void finishPlayerShutdown(std::function<void()> action);

private:
    struct State;
    static std::weak_ptr<State> geometryState_;
    explicit LinuxControlsOverlay(std::shared_ptr<State> state) : state_(std::move(state)) {}
    std::shared_ptr<State> state_;
};
