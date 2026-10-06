#pragma once

#include <atomic>
#include <condition_variable>
#include <cstdint>
#include <functional>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

struct mpv_handle;

// Mirrors the Windows preview worker: one lazy, windowless decoder per player.
class LinuxSeekThumbnails {
public:
    using Generation = std::shared_ptr<std::atomic<uint64_t>>;
    using Delivery = std::function<void(int64_t, const std::string &, Generation, uint64_t)>;
    LinuxSeekThumbnails(std::string source, std::vector<std::string> headers, Delivery delivery);
    ~LinuxSeekThumbnails();
    void request(int64_t positionMs);
    void close();
#ifdef NUVIO_SEEK_THUMBNAIL_TESTING
    // Only the standalone integration-test target compiles these phase gates.
    using PhaseHook = std::function<void(const char *, uint64_t)>;
    void setPhaseHook(PhaseHook hook) { phaseHook_ = std::move(hook); }
#endif

private:
    void run();
    bool current(uint64_t generation) const;
    uint64_t command(const char **args);
    void phase(const char *name, uint64_t generation = 0);
    void destroyDecoder();
    std::string source_;
    std::vector<std::string> headers_;
    Delivery delivery_;
    Generation generation_ = std::make_shared<std::atomic<uint64_t>>(0);
    std::atomic<bool> stopping_{false};
    std::mutex mutex_;
    std::condition_variable changed_;
    std::thread worker_;
    int64_t positionMs_ = 0;
    mpv_handle *decoder_ = nullptr; // Borrowed for cancellation; only worker_ destroys it.
    uint64_t commandId_ = 0;
    uint64_t pendingCommand_ = 0; // Protected by mutex_, including submit/abort ordering.
#ifdef NUVIO_SEEK_THUMBNAIL_TESTING
    PhaseHook phaseHook_;
#endif
};
