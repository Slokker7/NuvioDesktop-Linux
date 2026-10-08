// Standalone real-libmpv integration harness. Phase gates are absent from JNI builds.
#include "seek_thumbnails.h"
#include <mpv/client.h>
#include <chrono>
#include <clocale>
#include <condition_variable>
#include <cstdlib>
#include <iostream>
#include <sstream>
#include <utility>

extern "C" int __real_mpv_initialize(mpv_handle *);
extern "C" int __wrap_mpv_initialize(mpv_handle *mpv) {
    const int result = __real_mpv_initialize(mpv);
    if (result < 0) return result;
    // Check the real auxiliary player's effective options, independent of GPU availability.
    // Link wrapping is confined to this test executable; production has no interception.
    for (const auto &[name, expected] : {std::pair{"hwdec", "no"}, std::pair{"vo", "null"}}) {
        char *value = mpv_get_property_string(mpv, name);
        const bool matches = value && std::string(value) == expected;
        mpv_free(value);
        if (!matches) {
            std::cerr << "Thumbnail policy violation: " << name << std::endl;
            std::abort();
        }
    }
    return result;
}

int main(int argc, char **argv) {
    if (argc != 2) return 2;
    std::setlocale(LC_NUMERIC, "C");
    std::mutex output, gateMutex;
    std::condition_variable gateChanged;
    std::string gate;
    auto line = [&](const std::string &value) {
        std::lock_guard<std::mutex> lock(output);
        std::cout << value << std::endl;
    };
    std::unique_ptr<LinuxSeekThumbnails> player;
    auto open = [&] {
        player = std::make_unique<LinuxSeekThumbnails>(argv[1], std::vector<std::string>{},
            [&](int64_t position, const std::string &url, auto generation, uint64_t request) {
                if (generation->load() == request)
                    line("IMAGE " + std::to_string(position) + " " + url);
            });
        player->setPhaseHook([&](const char *name, uint64_t generation) {
            std::unique_lock<std::mutex> lock(gateMutex);
            if (std::string(name) == "stopping") {
                gate.clear(); gateChanged.notify_all();
            }
            line("PHASE " + std::string(name) + " " + std::to_string(generation));
            gateChanged.wait(lock, [&] { return gate != name; });
        });
        line("OPENED");
    };
    auto close = [&] {
        auto start = std::chrono::steady_clock::now();
        if (player) { player->close(); player.reset(); }
        line("CLOSED " + std::to_string(std::chrono::duration_cast<std::chrono::microseconds>(
            std::chrono::steady_clock::now() - start).count()));
    };
    for (std::string input; std::getline(std::cin, input);) {
        std::istringstream command(input);
        std::string name; command >> name;
        if (name == "OPEN") { close(); open(); }
        else if (name == "REQUEST") {
            int64_t position; command >> position;
            if (player) player->request(position);
        } else if (name == "GATE") {
            std::lock_guard<std::mutex> lock(gateMutex);
            command >> gate; line("GATED " + gate);
        } else if (name == "RELEASE") {
            std::lock_guard<std::mutex> lock(gateMutex);
            gate.clear(); gateChanged.notify_all(); line("RELEASED");
        } else if (name == "CLOSE") close();
        else if (name == "QUIT") break;
    }
    close();
}
