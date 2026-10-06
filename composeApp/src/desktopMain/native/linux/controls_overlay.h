#pragma once

#include <cstdint>
#include <functional>
#include <memory>
#include <string>

// Linux/X11 only. GTK types and its thread stay outside the shared JNI contract.
class LinuxControlsOverlay {
public:
    using Message = std::function<void(const std::string &, double)>;
    using Snapshot = std::function<std::string()>;

    static std::unique_ptr<LinuxControlsOverlay> create(
        uint64_t drawable, const std::string &url, Message message, Snapshot snapshot);
    ~LinuxControlsOverlay();
    void updateControls(const std::string &json);
    void runJavaScript(const std::string &script);
    void setCursorHidden(bool hidden);
    void setWindowFocused(bool focused);
    void close();
    // Serialize mpv's process-global X error-handler teardown with GTK's traps.
    static void finishPlayerShutdown(std::function<void()> action);

private:
    struct State;
    explicit LinuxControlsOverlay(std::shared_ptr<State> state) : state_(std::move(state)) {}
    std::shared_ptr<State> state_;
};
