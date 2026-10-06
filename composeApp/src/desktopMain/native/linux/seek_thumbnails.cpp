#include "seek_thumbnails.h"

#include <mpv/client.h>
#include <glib.h>
#include <unistd.h>

#include <chrono>
#include <array>
#include <cstdio>
#include <fstream>
#include <limits>
#include <stdexcept>

namespace {
using Clock = std::chrono::steady_clock;
constexpr uint64_t quitId = std::numeric_limits<uint64_t>::max();
void option(mpv_handle *mpv, const char *name, const char *value) {
    int result = mpv_set_option_string(mpv, name, value);
    if (result < 0) throw std::runtime_error(std::string(name) + ": " + mpv_error_string(result));
}

struct Screenshot {
    gchar *path = nullptr;
    Screenshot() {
        int fd = g_file_open_tmp("nuvio-seek-XXXXXX.jpg", &path, nullptr);
        if (fd < 0) throw std::runtime_error("Cannot create seek preview temporary file.");
        ::close(fd);
    }
    ~Screenshot() { if (path) { ::unlink(path); g_free(path); } }
};
}

LinuxSeekThumbnails::LinuxSeekThumbnails(
    std::string source, std::vector<std::string> headers, Delivery delivery)
    : source_(std::move(source)), headers_(std::move(headers)), delivery_(std::move(delivery)) {}

LinuxSeekThumbnails::~LinuxSeekThumbnails() { close(); }

void LinuxSeekThumbnails::phase(const char *name, uint64_t generation) {
#ifdef NUVIO_SEEK_THUMBNAIL_TESTING
    if (phaseHook_) phaseHook_(name, generation);
#endif
}

void LinuxSeekThumbnails::request(int64_t positionMs) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_) return;
    positionMs_ = positionMs;
    ++*generation_;
    if (!worker_.joinable()) worker_ = std::thread([this] { run(); });
    changed_.notify_one();
}

void LinuxSeekThumbnails::close() {
    {
        std::lock_guard<std::mutex> lock(mutex_);
        if (!stopping_.exchange(true)) ++*generation_;
        if (decoder_) {
            if (pendingCommand_) mpv_abort_async_command(decoder_, pendingCommand_);
            // wakeup alone cannot cancel demux I/O. quit requests mpv's playback
            // cancellation while the owner remains alive to finish destruction.
            const char *quit[] = {"quit", nullptr};
            mpv_command_async(decoder_, quitId, quit);
            mpv_wakeup(decoder_);
        }
    }
    changed_.notify_one();
    phase("stopping");
    if (worker_.joinable()) worker_.join();
}

bool LinuxSeekThumbnails::current(uint64_t generation) const {
    return !stopping_ && generation_->load() == generation;
}

uint64_t LinuxSeekThumbnails::command(const char **args) {
    std::lock_guard<std::mutex> lock(mutex_);
    if (stopping_) return 0;
    uint64_t id = ++commandId_;
    if (mpv_command_async(decoder_, id, args) < 0)
        throw std::runtime_error(std::string("Preview command failed: ") + args[0]);
    pendingCommand_ = id;
    return id;
}

void LinuxSeekThumbnails::destroyDecoder() {
    mpv_handle *decoder;
    {
        std::lock_guard<std::mutex> lock(mutex_);
        decoder = decoder_;
        decoder_ = nullptr; // No concurrent wakeup/abort during destruction.
        pendingCommand_ = 0;
    }
    if (decoder) {
        const char *quit[] = {"quit", nullptr};
        mpv_command_async(decoder, quitId, quit);
        mpv_terminate_destroy(decoder); // Sole owner, after all worker API calls.
    }
}

void LinuxSeekThumbnails::run() {
    uint64_t processed = 0;
    while (!stopping_) {
        {
            std::unique_lock<std::mutex> lock(mutex_);
            changed_.wait(lock, [&] { return stopping_ || generation_->load() > processed; });
            if (stopping_) break;
            processed = generation_->load();
        }
        // A pending screenshot owns its path until mpv finishes destruction:
        // screenshot-to-file is not abortable in mpv 0.41 and may still write it.
        std::unique_ptr<Screenshot> screenshot;
        try {
            phase("initializing");
            if (stopping_) break;
            std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)> initializing(mpv_create(), mpv_terminate_destroy);
            if (!initializing) throw std::runtime_error("mpv_create failed.");
            option(initializing.get(), "config", "no");
            option(initializing.get(), "terminal", "no");
            option(initializing.get(), "osc", "no");
            option(initializing.get(), "audio", "no");
            option(initializing.get(), "vo", "null");
            option(initializing.get(), "pause", "yes");
            option(initializing.get(), "hwdec", "no");
            option(initializing.get(), "cache", "no");
            option(initializing.get(), "hr-seek", "no");
            option(initializing.get(), "vf", "lavfi=[scale=256:-2]");
            option(initializing.get(), "screenshot-format", "jpg");
            option(initializing.get(), "screenshot-jpeg-quality", "64");
            if (!headers_.empty()) {
                std::vector<mpv_node> values(headers_.size());
                for (size_t i = 0; i < headers_.size(); ++i) {
                    values[i].format = MPV_FORMAT_STRING;
                    values[i].u.string = headers_[i].data();
                }
                mpv_node_list list{};
                list.num = static_cast<int>(values.size()); list.values = values.data();
                mpv_node node{}; node.format = MPV_FORMAT_NODE_ARRAY; node.u.list = &list;
                if (mpv_set_option(initializing.get(), "http-header-fields", MPV_FORMAT_NODE, &node) < 0)
                    throw std::runtime_error("Cannot set preview stream headers.");
            }
            if (stopping_) break;
            if (mpv_initialize(initializing.get()) < 0) throw std::runtime_error("mpv_initialize failed.");
            {
                std::lock_guard<std::mutex> lock(mutex_);
                decoder_ = initializing.release();
            }
            const char *load[] = {"loadfile", source_.c_str(), nullptr};
            uint64_t loadId = command(load);
            phase("load-issued");
            bool reply = false, loaded = false, ready = false, slow = false;
            auto loadStarted = Clock::now();
            // Polling is interruptible. Crossing the old eight-second budget
            // retains the pending load; it never retires an otherwise live decoder.
            auto event = [&]() {
                mpv_event *e = mpv_wait_event(decoder_, 0.04);
                if (e->event_id == MPV_EVENT_COMMAND_REPLY) {
                    std::lock_guard<std::mutex> lock(mutex_);
                    if (pendingCommand_ == e->reply_userdata) pendingCommand_ = 0;
                }
                bool ended = e->event_id == MPV_EVENT_END_FILE &&
                    static_cast<mpv_event_end_file *>(e->data)->reason != MPV_END_FILE_REASON_REDIRECT;
                if (!stopping_ && (ended ||
                    e->event_id == MPV_EVENT_SHUTDOWN || e->event_id == MPV_EVENT_QUEUE_OVERFLOW))
                    throw std::runtime_error("Preview playback ended or lost event ordering.");
                return e;
            };
            phase("waiting-file");
            while (!stopping_ && !(reply && loaded && ready)) {
                auto *e = event();
                if (e->event_id == MPV_EVENT_COMMAND_REPLY && e->reply_userdata == loadId) {
                    if (e->error < 0) throw std::runtime_error("Preview loadfile failed.");
                    reply = true;
                }
                if (e->event_id == MPV_EVENT_START_FILE) loaded = ready = false;
                if (e->event_id == MPV_EVENT_FILE_LOADED) loaded = true;
                if (loaded && e->event_id == MPV_EVENT_PLAYBACK_RESTART) ready = true;
                if (!slow && Clock::now() - loadStarted >= std::chrono::seconds(8)) {
                    slow = true; phase("loading-slow");
                }
            }
            phase("initial-ready");
            // No seek has been issued until the initial restart is consumed.
            // One transition at a time: supersession discards delivery, never
            // abandons an in-flight seek whose late restart could satisfy the next.
            processed = 0;
            while (!stopping_) {
                int64_t position;
                uint64_t generation;
                {
                    std::unique_lock<std::mutex> lock(mutex_);
                    changed_.wait(lock, [&] { return stopping_ || generation_->load() > processed; });
                    if (stopping_) break;
                    position = positionMs_; generation = generation_->load();
                }
                processed = generation;
                std::string seconds = std::to_string(static_cast<double>(position) / 1000.0);
                const char *seek[] = {"seek", seconds.c_str(), "absolute+keyframes", nullptr};
                uint64_t seekId = command(seek);
                phase("seek-issued", generation);
                bool entered = false;
                reply = ready = false;
                auto deadline = Clock::now() + std::chrono::seconds(3);
                while (!stopping_ && !(reply && entered && ready)) {
                    if (Clock::now() >= deadline) throw std::runtime_error("Preview seek timed out.");
                    auto *e = event();
                    if (e->event_id == MPV_EVENT_COMMAND_REPLY && e->reply_userdata == seekId) {
                        if (e->error < 0) throw std::runtime_error("Preview seek failed.");
                        reply = true;
                    }
                    if (e->event_id == MPV_EVENT_SEEK) {
                        entered = true; phase("seek-entered", generation);
                    }
                    if (entered && e->event_id == MPV_EVENT_PLAYBACK_RESTART) ready = true;
                }
                phase("seek-ready", generation);
                if (!current(generation)) continue;
                screenshot = std::make_unique<Screenshot>();
                const char *capture[] = {"screenshot-to-file", screenshot->path, "video", nullptr};
                uint64_t captureId = command(capture);
                phase("capture-issued", generation);
                reply = false;
                deadline = Clock::now() + std::chrono::seconds(3);
                while (!stopping_ && !reply) {
                    if (Clock::now() >= deadline) throw std::runtime_error("Preview screenshot timed out.");
                    auto *e = event();
                    if (e->event_id == MPV_EVENT_COMMAND_REPLY && e->reply_userdata == captureId) {
                        if (e->error < 0) throw std::runtime_error("Preview screenshot failed.");
                        reply = true;
                    }
                }
                if (!current(generation)) {
                    if (!stopping_) screenshot.reset(); // Reply consumed; writer is done.
                    continue;
                }
                phase("readback", generation);
                if (!current(generation)) continue;
                std::ifstream input(screenshot->path, std::ios::binary);
                std::vector<unsigned char> bytes;
                std::array<char, 4096> buffer;
                while (current(generation) && input) {
                    input.read(buffer.data(), buffer.size());
                    bytes.insert(bytes.end(), buffer.data(), buffer.data() + input.gcount());
                }
                screenshot.reset();
                if (bytes.empty() || !current(generation)) continue;
                std::unique_ptr<gchar, decltype(&g_free)> encoded(g_base64_encode(bytes.data(), bytes.size()), g_free);
                std::string url = "data:image/jpeg;base64," + std::string(encoded.get());
                phase("delivery", generation);
                if (current(generation)) delivery_(position, url, generation_, generation);
            }
        } catch (const std::exception &error) {
            if (!stopping_) std::fprintf(stderr, "Linux seek preview: %s\n", error.what());
            // Retry only on a later request, never an autonomous retry loop.
        }
        destroyDecoder();
        screenshot.reset();
        phase("decoder-closed");
    }
}
