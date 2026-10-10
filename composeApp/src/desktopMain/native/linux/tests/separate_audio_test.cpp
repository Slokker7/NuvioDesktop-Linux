// Exercises the production helper with real libmpv. Never creates a window,
// initializes GTK/JNI, accesses the network, or opens an audio device.
#include "separate_audio.h"
#include <chrono>
#include <cstdlib>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <stdexcept>
#include <vector>

namespace {
void require(bool condition, const std::string &message) {
    if (!condition) throw std::runtime_error(message);
}
void check(int result, const char *operation) {
    require(result >= 0, std::string(operation) + ": " + mpv_error_string(result));
}
using Player = std::unique_ptr<mpv_handle, decltype(&mpv_terminate_destroy)>;
Player player() {
    Player mpv(mpv_create(), mpv_terminate_destroy);
    require(bool(mpv), "mpv_create");
    for (const auto &[name, value] : std::vector<std::pair<const char *, const char *>>{
             {"config", "no"}, {"load-scripts", "no"}, {"ytdl", "no"},
             {"vo", "null"}, {"ao", "null"}, {"hwdec", "no"},
             {"terminal", "no"}, {"pause", "yes"}, {"idle", "yes"}}) {
        check(mpv_set_option_string(mpv.get(), name, value), name);
    }
    return mpv;
}
std::vector<std::string> audioFiles(mpv_handle *mpv) {
    mpv_node node{};
    check(mpv_get_property(mpv, "options/audio-files", MPV_FORMAT_NODE, &node), "get audio-files");
    const auto cleanup = std::unique_ptr<mpv_node, decltype(&mpv_free_node_contents)>(
        &node, mpv_free_node_contents);
    require(node.format == MPV_FORMAT_NODE_ARRAY, "audio-files must be an array");
    std::vector<std::string> values;
    for (int i = 0; i < node.u.list->num; ++i) {
        const auto &entry = node.u.list->values[i];
        require(entry.format == MPV_FORMAT_STRING, "audio-files entry must be a string");
        values.emplace_back(entry.u.string);
    }
    return values;
}
std::string property(mpv_handle *mpv, const std::string &name) {
    char *value = mpv_get_property_string(mpv, name.c_str());
    require(value != nullptr, "get " + name);
    std::string result(value);
    mpv_free(value);
    return result;
}
void load(mpv_handle *mpv, const std::string &video) {
    const char *command[] = {"loadfile", video.c_str(), nullptr};
    check(mpv_command(mpv, command), "loadfile");
    auto deadline = std::chrono::steady_clock::now() + std::chrono::seconds(5);
    while (std::chrono::steady_clock::now() < deadline) {
        auto *event = mpv_wait_event(mpv, 0.1);
        if (event->event_id == MPV_EVENT_FILE_LOADED) return;
        require(event->event_id != MPV_EVENT_END_FILE, "media failed to load");
    }
    throw std::runtime_error("file-loaded timeout");
}
struct Media {
    std::filesystem::path directory;
    Media() {
        char pattern[] = "/tmp/nuvio-separate-audio-XXXXXX";
        char *created = mkdtemp(pattern);
        require(created != nullptr, "mkdtemp");
        directory = created;
        std::ofstream video(directory / "video.y4m", std::ios::binary);
        video << "YUV4MPEG2 W2 H2 F25:1 Ip A1:1 C420jpeg\n";
        for (int i = 0; i < 50; ++i) video << "FRAME\n" << std::string(6, char(128));
        std::ofstream audio(directory / "audio,part:one.wav", std::ios::binary);
        auto little = [&](unsigned value, int bytes) {
            for (int i = 0; i < bytes; ++i) audio.put(char(value >> (8 * i)));
        };
        constexpr unsigned dataBytes = 16000 * 2 * 2;
        audio << "RIFF"; little(36 + dataBytes, 4); audio << "WAVEfmt ";
        little(16, 4); little(1, 2); little(1, 2); little(16000, 4);
        little(32000, 4); little(2, 2); little(16, 2);
        audio << "data"; little(dataBytes, 4); audio << std::string(dataBytes, '\0');
        require(video.good() && audio.good(), "write media fixtures");
    }
    ~Media() { std::filesystem::remove_all(directory); }
};
}

int main() {
    try {
        {
            auto mpv = player();
            require(mpv_set_option_string(mpv.get(), "audio-files-append", "unused.wav") ==
                        MPV_ERROR_OPTION_NOT_FOUND,
                    "negative control must reproduce the rejected option");
        }
        std::cout << "PASS unsupported CLI suffix negative control\n";
        {
            auto mpv = player();
            check(setLinuxSeparateAudio(mpv.get(), ""), "absent audio");
            check(mpv_initialize(mpv.get()), "initialize");
            require(audioFiles(mpv.get()).empty(), "absent audio must leave default empty");
        }
        std::cout << "PASS absent audio leaves defaults\n";
        {
            auto mpv = player();
            check(mpv_set_option_string(mpv.get(), "audio-files", "existing.wav"), "existing audio");
            check(setLinuxSeparateAudio(mpv.get(), ""), "absent audio");
            check(mpv_initialize(mpv.get()), "initialize");
            require(audioFiles(mpv.get()) == std::vector<std::string>{"existing.wav"},
                    "absent audio must not configure or clear audio-files");
        }
        std::cout << "PASS absent audio preserves existing options\n";
        {
            auto mpv = player();
            const std::string url = "https://example.invalid/音声,a:b;part?sig=x%2Cy&list=1,2#fragment";
            check(setLinuxSeparateAudio(mpv.get(), url), "separate audio before initialize");
            check(mpv_initialize(mpv.get()), "initialize");
            require(audioFiles(mpv.get()) == std::vector<std::string>{url},
                    "entire URL must survive initialization as one exact entry");
            // No loadfile: this URL is never fetched.
        }
        std::cout << "PASS URL delimiters and UTF-8 survive as one entry\n";
        Media media;
        for (bool separate : {true, false}) {
            auto mpv = player();
            const auto audio = (media.directory / "audio,part:one.wav").string();
            check(setLinuxSeparateAudio(mpv.get(), separate ? audio : ""), "separate audio");
            check(mpv_initialize(mpv.get()), "initialize");
            const auto video = (media.directory / "video.y4m").string();
            load(mpv.get(), video);
            require(property(mpv.get(), "path") == video, "video URL must load unchanged");
            int videos = 0, audios = 0;
            const int tracks = std::stoi(property(mpv.get(), "track-list/count"));
            for (int i = 0; i < tracks; ++i) {
                const auto prefix = "track-list/" + std::to_string(i) + "/";
                const auto type = property(mpv.get(), prefix + "type");
                if (type == "video") ++videos;
                if (type == "audio") {
                    ++audios;
                    require(property(mpv.get(), prefix + "external") == "yes", "audio must be external");
                    require(property(mpv.get(), prefix + "external-filename") == audio, "exact audio path");
                    require(property(mpv.get(), prefix + "selected") == "yes", "audio must be selected");
                }
            }
            require(videos == 1 && audios == (separate ? 1 : 0), "expected video/audio tracks");
            std::cout << "PASS real video load " << (separate ? "with" : "without") << " separate audio\n";
        }
        std::cout << "6 regression groups passed\n";
    } catch (const std::exception &error) {
        std::cerr << "FAIL " << error.what() << '\n';
        return 1;
    }
}
