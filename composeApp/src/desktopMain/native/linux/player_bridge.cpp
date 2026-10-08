#include <jni.h>
#include <mpv/client.h>
#include <glib.h>
#include "controls_overlay.h"
#include "awt_x11_lifecycle.h"
#include "seek_thumbnails.h"

#include <algorithm>
#include <atomic>
#include <clocale>
#include <cmath>
#include <cstdio>
#include <future>
#include <iomanip>
#include <limits>
#include <memory>
#include <locale>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <type_traits>
#include <unordered_map>
#include <vector>

namespace {
void throwJavaError(JNIEnv *env, const char *message) {
    if (env->ExceptionCheck()) return;
    jclass type = env->FindClass("java/lang/IllegalStateException");
    if (type) {
        env->ThrowNew(type, message);
        env->DeleteLocalRef(type);
    }
}

void checkMpv(int result, const char *operation) {
    if (result < 0) throw std::runtime_error(std::string(operation) + ": " + mpv_error_string(result));
}

std::string text(JNIEnv *env, jstring value) {
    if (!value) return {};
    // mpv needs standard UTF-8, not JNI's modified UTF-8 (notably for non-BMP filenames).
    jsize length = env->GetStringLength(value);
    std::vector<jchar> utf16(length);
    env->GetStringRegion(value, 0, length, utf16.data());
    if (env->ExceptionCheck()) throw std::runtime_error("Unable to read JNI string.");
    std::string result;
    for (size_t i = 0; i < utf16.size(); ++i) {
        uint32_t code = utf16[i];
        if (code >= 0xd800 && code <= 0xdbff && i + 1 < utf16.size() &&
            utf16[i + 1] >= 0xdc00 && utf16[i + 1] <= 0xdfff) {
            code = 0x10000 + ((code - 0xd800) << 10) + (utf16[++i] - 0xdc00);
        } else if (code >= 0xd800 && code <= 0xdfff) {
            code = 0xfffd;
        }
        if (code < 0x80) result += static_cast<char>(code);
        else {
            if (code >= 0x10000) result += static_cast<char>(0xf0 | (code >> 18));
            else if (code >= 0x800) result += static_cast<char>(0xe0 | (code >> 12));
            else result += static_cast<char>(0xc0 | (code >> 6));
            if (code >= 0x10000) result += static_cast<char>(0x80 | ((code >> 12) & 0x3f));
            if (code >= 0x800) result += static_cast<char>(0x80 | ((code >> 6) & 0x3f));
            result += static_cast<char>(0x80 | (code & 0x3f));
        }
    }
    return result;
}

std::string trimmed(const std::string &value) {
    const char *start = value.data();
    const char *end = start + value.size();
    while (start < end) {
        gunichar code = g_utf8_get_char_validated(start, end - start);
        if (code == static_cast<gunichar>(-1) || code == static_cast<gunichar>(-2) ||
            !g_unichar_isspace(code)) break;
        start = g_utf8_next_char(start);
    }
    while (start < end) {
        const char *previous = g_utf8_find_prev_char(start, end);
        if (!previous) break;
        gunichar code = g_utf8_get_char_validated(previous, end - previous);
        if (code == static_cast<gunichar>(-1) || code == static_cast<gunichar>(-2) ||
            !g_unichar_isspace(code)) break;
        end = previous;
    }
    return std::string(start, end);
}

std::string caseFolded(const std::string &value) {
    std::unique_ptr<gchar, decltype(&g_free)> valid(
        g_utf8_make_valid(value.data(), value.size()), g_free);
    std::unique_ptr<gchar, decltype(&g_free)> folded(g_utf8_casefold(valid.get(), -1), g_free);
    return folded.get();
}

std::string jsonString(const std::string &value) {
    // Keep JSON ASCII-only so NewStringUTF never receives standard four-byte UTF-8.
    std::string result = "\"";
    auto unicodeEscape = [&](gunichar unit) {
        char escaped[7];
        std::snprintf(escaped, sizeof(escaped), "\\u%04x", static_cast<unsigned>(unit));
        result += escaped;
    };
    for (size_t index = 0; index < value.size();) {
        gunichar code = static_cast<unsigned char>(value[index]);
        size_t bytes = 1;
        if (code >= 0x80) {
            code = g_utf8_get_char_validated(value.data() + index, value.size() - index);
            if (code == static_cast<gunichar>(-1) || code == static_cast<gunichar>(-2)) {
                code = 0xfffd;
            } else {
                bytes = g_utf8_next_char(value.data() + index) - (value.data() + index);
            }
        }
        index += bytes;
        if (code == '"' || code == '\\') {
            result += '\\';
            result += static_cast<char>(code);
        } else if (code > 0xffff) {
            code -= 0x10000;
            unicodeEscape(0xd800 + (code >> 10));
            unicodeEscape(0xdc00 + (code & 0x3ff));
        } else if (code < 0x20 || code >= 0x80) {
            unicodeEscape(code);
        } else {
            result += static_cast<char>(code);
        }
    }
    return result + '"';
}

std::string subtitleCodecDisplayName(const std::string &value) {
    std::string raw = trimmed(value);
    std::string codec = caseFolded(raw);
    auto contains = [&](const char *name) { return codec.find(name) != std::string::npos; };
    if (contains("eac3") || contains("e-ac-3") || contains("e ac-3")) {
        return contains("joc") || contains("atmos") ? "E-AC-3-JOC" : "E-AC-3";
    }
    if (contains("truehd") || contains("true hd")) return "TrueHD";
    if (contains("ac3") || contains("ac-3")) return "AC-3";
    if (contains("dts-hd") || contains("dtshd") || contains("dts hd")) return "DTS-HD";
    if (contains("dts") || codec == "dca") return "DTS";
    if (contains("aac")) return "AAC";
    if (contains("mp3") || contains("mpeg audio")) return "MP3";
    if (contains("mp2")) return "MP2";
    if (contains("opus")) return "Opus";
    if (contains("vorbis")) return "Vorbis";
    if (contains("flac")) return "FLAC";
    if (contains("alac")) return "ALAC";
    if (contains("pcm") || contains("wav")) return "WAV";
    if (contains("amr_wb") || contains("amr-wb")) return "AMR-WB";
    if (contains("amr_nb") || contains("amr-nb")) return "AMR-NB";
    if (contains("amr")) return "AMR";
    if (contains("iamf")) return "IAMF";
    if (contains("mpegh") || contains("mpeg-h")) return "MPEG-H";
    if (contains("pgs") || contains("hdmv")) return "PGS";
    if (contains("subrip") || codec == "srt") return "SRT";
    if (contains("ass") || contains("ssa")) return "SSA";
    if (contains("webvtt") || codec == "vtt") return "VTT";
    if (contains("ttml")) return "TTML";
    if (contains("mov_text") || contains("tx3g")) return "TX3G";
    if (contains("dvb")) return "DVB";
    return raw;
}

std::vector<std::string> strings(JNIEnv *env, jobjectArray array) {
    std::vector<std::string> result;
    if (!array) return result;
    for (jsize i = 0; i < env->GetArrayLength(array); ++i) {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(array, i));
        result.push_back(text(env, value));
        if (value) env->DeleteLocalRef(value);
    }
    return result;
}

template<class F> auto guarded(JNIEnv *env, F action) -> decltype(action()) {
    try {
        return action();
    } catch (const std::exception &error) {
        throwJavaError(env, error.what());
    } catch (...) {
        throwJavaError(env, "Unexpected Linux native player failure.");
    }
    if constexpr (!std::is_void_v<decltype(action())>) return {};
}

struct Player;
std::string audioTracksJson(Player &player);
std::string subtitleTracksJson(Player &player);

// Xlib's error handler belongs to the process, not to an mpv handle. Reserve
// the whole lifetime, including partial initialization and final restoration.
std::atomic<bool> x11PlayerLifetimeReserved{false};

struct Player {
    mpv_handle *mpv = nullptr;
    JavaVM *vm = nullptr;
    jobject sink = nullptr;
    jmethodID onEvent = nullptr;
    std::mutex operations;
    std::mutex callbacks;
    std::unique_ptr<LinuxControlsOverlay> controls;
    std::unique_ptr<LinuxSeekThumbnails> thumbnails;
    std::thread events;
    std::atomic<bool> stopping{false};
    std::atomic<bool> fileReady{false};
    std::atomic<bool> ended{false};
    bool playbackStarted = false; // Only accessed by the event thread.
    bool ownsX11Lifetime = false;

    void reserveX11Lifetime() {
        bool expected = false;
        if (!x11PlayerLifetimeReserved.compare_exchange_strong(expected, true))
            throw std::runtime_error("Linux X11 player is already active; dispose it before creating another player.");
        ownsX11Lifetime = true;
    }

    ~Player() { close(); }

    void close() {
        stopping = true;
        std::unique_ptr<LinuxControlsOverlay> closingControls;
        {
            std::lock_guard<std::mutex> lock(operations);
            closingControls = std::move(controls);
            if (mpv && events.joinable()) mpv_wakeup(mpv);
        }
        // GTK callbacks can query mpv; do not hold operations while waiting for GTK.
        // Disconnect/destroy the native child before releasing mpv or the JNI sink.
        if (closingControls) closingControls->close();
        // No operations/JAWT/GTK lock while waiting for the windowless decoder.
        if (thumbnails) thumbnails->close();
        // Never hold the operation lock across callbacks/join.
        if (events.joinable()) events.join();
        std::lock_guard<std::mutex> lock(operations);
        if (mpv) {
            JNIEnv *env = nullptr;
            int status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
            bool attached = status == JNI_EDETACHED;
            if (status != JNI_OK && (!attached ||
                vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) != JNI_OK))
                throw std::runtime_error("Unable to acquire JVM environment for Linux player shutdown.");
            {
                // The overlay is closed and the event thread joined: neither can
                // require the EDT or acquire AWT's lock while we wait for GTK/mpv.
                LinuxAwtX11Lifecycle awtLock(env);
                auto terminate = [this] {
                    mpv_terminate_destroy(mpv);
                    LinuxAwtX11Lifecycle::restoreToolkitHandler();
                };
                LinuxControlsOverlay::finishPlayerShutdown(terminate);
            }
            if (attached) vm->DetachCurrentThread();
            mpv = nullptr;
        }
        std::lock_guard<std::mutex> callbackLock(callbacks);
        if (sink) {
            JNIEnv *env = nullptr;
            int status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
            bool attached = status == JNI_EDETACHED;
            if (status == JNI_OK || (attached &&
                vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) == JNI_OK)) {
                env->DeleteGlobalRef(sink);
                if (attached) vm->DetachCurrentThread();
            }
            sink = nullptr;
        }
        // A rejected second create owns nothing. Repeated close must not release
        // a reservation subsequently acquired by another player.
        if (ownsX11Lifetime) {
            ownsX11Lifetime = false;
            x11PlayerLifetimeReserved = false;
        }
    }

    void emit(JNIEnv *env, const std::string &name, double value = 0.0) {
        std::lock_guard<std::mutex> lock(callbacks);
        if (stopping || !sink) return;
        jstring eventName = env->NewStringUTF(name.c_str());
        if (eventName) {
            env->CallVoidMethod(sink, onEvent, eventName, value);
            env->DeleteLocalRef(eventName);
        }
        if (env->ExceptionCheck()) {
            // A callback failure must not escape a native thread or terminate the process.
            env->ExceptionDescribe();
            env->ExceptionClear();
        }
    }

    void controlsMessage(const std::string &name, double value) {
        if (stopping) return;
        // The HUD's subtitle menu sends mpv's sid; Kotlin has no handler for this
        // native action. Audio's logical index is instead forwarded to Kotlin.
        if (name == "selectSubtitleTrack") {
            std::lock_guard<std::mutex> lock(operations);
            if (mpv && !stopping && value >= -1 && value <= INT32_MAX)
                property("sid", value < 0 ? "no" : std::to_string(std::llround(value)));
            return;
        }
        JNIEnv *env = nullptr;
        int status = vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6);
        bool attached = status == JNI_EDETACHED;
        if (status == JNI_OK || (attached &&
            vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) == JNI_OK)) {
            emit(env, name, value); // Existing Kotlin sink queues onto Swing.
            if (attached) vm->DetachCurrentThread();
        }
    }

    std::string controlsSnapshot() {
        std::lock_guard<std::mutex> lock(operations);
        if (!mpv || stopping) return {};
        double position = number("time-pos");
        double buffered = number("demuxer-cache-time", -1.0);
        if (buffered < position) buffered = position + number("demuxer-cache-duration");
        bool paused = flag("pause");
        bool loading = !fileReady || flag("seeking") || flag("paused-for-cache") ||
            (flag("core-idle") && !paused && !ended && !flag("eof-reached"));
        // Kotlin omits progress-only structural updates. Match the existing HUD
        // runtime contract using real mpv state.
        std::ostringstream json;
        json.imbue(std::locale::classic());
        json << "{\"duration\":" << number("duration") << ",\"position\":" << position
             << ",\"buffered\":" << buffered << ",\"paused\":" << (paused ? "true" : "false")
             << ",\"loading\":" << (loading ? "true" : "false")
             << ",\"audioTracks\":" << audioTracksJson(*this)
             << ",\"subtitleTracks\":" << subtitleTracksJson(*this) << "}";
        return json.str();
    }

    void pump(std::promise<bool> &attached) {
        JNIEnv *env = nullptr;
        bool ready = vm->AttachCurrentThreadAsDaemon(reinterpret_cast<void **>(&env), nullptr) == JNI_OK;
        attached.set_value(ready);
        if (!ready) return;
        while (!stopping) {
            mpv_event *event = mpv_wait_event(mpv, 0.2);
            if (stopping) break;
            switch (event->event_id) {
                case MPV_EVENT_FILE_LOADED:
                    fileReady = true;
                    ended = false;
                    emit(env, "fileLoaded");
                    break;
                case MPV_EVENT_PLAYBACK_RESTART:
                    playbackStarted = true;
                    ended = false;
                    emit(env, "playbackRestart");
                    break;
                case MPV_EVENT_END_FILE: {
                    auto *end = static_cast<mpv_event_end_file *>(event->data);
                    if (end && end->reason == MPV_END_FILE_REASON_ERROR) {
                        fileReady = false;
                        emit(env, std::string(playbackStarted ? "mpvPlaybackError:" : "mpvStartupError:") +
                            mpv_error_string(end->error));
                    } else if (end && end->reason == MPV_END_FILE_REASON_EOF) {
                        ended = true;
                    }
                    break;
                }
                case MPV_EVENT_SHUTDOWN:
                    stopping = true;
                    break;
                default:
                    break;
            }
        }
        vm->DetachCurrentThread();
    }

    double number(const char *name, double unavailable = 0.0) {
        double value = unavailable;
        if (mpv_get_property(mpv, name, MPV_FORMAT_DOUBLE, &value) < 0 || !std::isfinite(value)) return unavailable;
        return value;
    }

    bool flag(const char *name, bool unavailable = false) {
        int value = unavailable;
        if (mpv_get_property(mpv, name, MPV_FORMAT_FLAG, &value) < 0) return unavailable;
        return value != 0;
    }

    int64_t integer(const char *name, int64_t unavailable = 0) {
        int64_t value = unavailable;
        if (mpv_get_property(mpv, name, MPV_FORMAT_INT64, &value) < 0) return unavailable;
        return value;
    }

    std::string stringProperty(const char *name) {
        char *value = mpv_get_property_string(mpv, name);
        if (!value) return {};
        std::string result(value);
        mpv_free(value);
        return result;
    }

    void property(const char *name, const std::string &value) {
        int result = mpv_set_property_string(mpv, name, value.c_str());
        // Shared preferences include optional/init-only properties. Diagnose failures rather
        // than aborting playback; required commands below use checkMpv instead.
        if (result < 0) std::fprintf(stderr, "Linux mpv property %s: %s\n", name, mpv_error_string(result));
    }
};

// Opaque IDs avoid use-after-free when a snapshot/command races asynchronous disposal.
std::mutex playersLock;
std::unordered_map<jlong, std::shared_ptr<Player>> players;
std::atomic<jlong> nextHandle{1};

std::shared_ptr<Player> playerFromHandle(jlong handle) {
    std::lock_guard<std::mutex> lock(playersLock);
    auto found = players.find(handle);
    return found == players.end() ? nullptr : found->second;
}

template<class F> auto withPlayer(JNIEnv *env, jlong handle, F action) -> decltype(action(std::declval<Player &>())) {
    return guarded(env, [&]() {
        std::shared_ptr<Player> player;
        {
            std::lock_guard<std::mutex> lock(playersLock);
            auto found = players.find(handle);
            if (found == players.end()) throw std::runtime_error("Linux native player handle is not active.");
            player = found->second;
        }
        std::lock_guard<std::mutex> lock(player->operations);
        if (!player->mpv || player->stopping) throw std::runtime_error("Linux native player is disposed.");
        return action(*player);
    });
}

void option(mpv_handle *mpv, const char *name, const std::string &value) {
    checkMpv(mpv_set_option_string(mpv, name, value.c_str()), name);
}

void headerOptions(mpv_handle *mpv, std::vector<std::string> &headers) {
    if (headers.empty()) return;
    // Use the native string array so commas in individual header values stay in one header.
    std::vector<mpv_node> entries(headers.size());
    for (size_t i = 0; i < headers.size(); ++i) {
        entries[i].format = MPV_FORMAT_STRING;
        entries[i].u.string = headers[i].data();
    }
    mpv_node_list list{};
    list.num = static_cast<int>(entries.size());
    list.values = entries.data();
    mpv_node node{};
    node.format = MPV_FORMAT_NODE_ARRAY;
    node.u.list = &list;
    checkMpv(mpv_set_option(mpv, "http-header-fields", MPV_FORMAT_NODE, &node), "http-header-fields");
}

void seek(Player &player, jlong ms, const char *mode) {
    std::string seconds = std::to_string(static_cast<double>(ms) / 1000.0);
    const char *command[] = {"seek", seconds.c_str(), mode, nullptr};
    checkMpv(mpv_command(player.mpv, command), "seek");
    player.ended = false;
}

std::string chaptersJson(Player &player) {
    // One coherent property snapshot; no mpv-owned pointers escape its lifetime.
    struct ChapterList {
        mpv_node node{};
        ~ChapterList() { mpv_free_node_contents(&node); }
    } chapters;
    if (mpv_get_property(player.mpv, "chapter-list", MPV_FORMAT_NODE, &chapters.node) < 0 ||
        chapters.node.format != MPV_FORMAT_NODE_ARRAY || !chapters.node.u.list) return "[]";
    const mpv_node_list &list = *chapters.node.u.list;
    if (list.num > 0 && !list.values) return "[]";
    std::ostringstream json;
    json.imbue(std::locale::classic());
    json << std::setprecision(std::numeric_limits<double>::max_digits10) << '[';
    bool first = true;
    for (int index = 0; index < list.num; ++index) {
        const mpv_node &entry = list.values[index];
        if (entry.format != MPV_FORMAT_NODE_MAP || !entry.u.list) continue;
        const mpv_node_list &fields = *entry.u.list;
        if (!fields.keys || !fields.values) continue;
        double time = -1;
        std::string title;
        for (int field = 0; field < fields.num; ++field) {
            if (!fields.keys[field]) continue;
            const std::string key = fields.keys[field];
            const mpv_node &value = fields.values[field];
            if (key == "time") {
                if (value.format == MPV_FORMAT_DOUBLE) time = value.u.double_;
                else if (value.format == MPV_FORMAT_INT64) time = static_cast<double>(value.u.int64);
            } else if (key == "title" && value.format == MPV_FORMAT_STRING && value.u.string) {
                title = trimmed(value.u.string);
            }
        }
        if (!std::isfinite(time) || time < 0) continue;
        if (!first) json << ',';
        first = false;
        json << "{\"startTime\":" << time << ",\"title\":" << jsonString(title) << '}';
    }
    json << ']';
    return json.str();
}

void removeExternalSubtitleTracks(Player &player) {
    const int64_t count = player.integer("track-list/count");
    // Removing a track mutates track-list, so visit indices in reverse order.
    for (int64_t index = count - 1; index >= 0; --index) {
        std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (player.stringProperty((prefix + "type").c_str()) != "sub" ||
            !player.flag((prefix + "external").c_str())) continue;
        int64_t trackId = player.integer((prefix + "id").c_str(), -1);
        if (trackId < 0) continue;
        std::string id = std::to_string(trackId);
        const char *command[] = {"sub-remove", id.c_str(), nullptr};
        checkMpv(mpv_command(player.mpv, command), "sub-remove");
    }
}

std::string audioChannelLayoutName(const std::string &channels, int64_t count) {
    std::string normalized = trimmed(channels);
    std::string lower = caseFolded(normalized);
    if (!normalized.empty() && lower != "unknown") {
        if (lower == "mono") return "Mono";
        if (lower == "stereo") return "Stereo";
        return normalized;
    }
    switch (count) {
        case 1: return "Mono";
        case 2: return "Stereo";
        case 6: return "5.1";
        case 8: return "7.1";
        default: return count > 0 ? std::to_string(count) + "ch" : "";
    }
}

std::string audioTracksJson(Player &player) {
    const int64_t count = player.integer("track-list/count");
    int64_t logicalIndex = 0;
    std::string json = "[";
    for (int64_t index = 0; index < count; ++index) {
        std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (player.stringProperty((prefix + "type").c_str()) != "audio") continue;
        int64_t trackId = player.integer((prefix + "id").c_str(), logicalIndex + 1);
        std::string title = trimmed(player.stringProperty((prefix + "title").c_str()));
        std::string language = trimmed(player.stringProperty((prefix + "lang").c_str()));
        // The existing codec display helper also covers audio codecs.
        std::string codec = subtitleCodecDisplayName(player.stringProperty((prefix + "codec").c_str()));
        if (codec.empty()) codec = subtitleCodecDisplayName(player.stringProperty((prefix + "decoder-desc").c_str()));
        std::string channels = audioChannelLayoutName(player.stringProperty((prefix + "demux-channels").c_str()),
            player.integer((prefix + "demux-channel-count").c_str()));
        // Match Windows formatTrackTitle: title, language, then numbered fallback;
        // append channel layout and codec only when absent from the base label.
        std::string label = !title.empty() ? title : !language.empty() ? language :
            "Track " + std::to_string(logicalIndex + 1);
        std::string details;
        for (const auto &detail : {channels, codec}) {
            if (detail.empty() || caseFolded(label).find(caseFolded(detail)) != std::string::npos) continue;
            if (!details.empty()) details += ", ";
            details += detail;
        }
        if (!details.empty()) label += " (" + details + ")";
        if (logicalIndex > 0) json += ',';
        json += "{\"index\":" + std::to_string(logicalIndex) +
            ",\"id\":" + jsonString(std::to_string(trackId)) +
            ",\"label\":" + jsonString(label) + ",\"language\":" + jsonString(language) +
            ",\"selected\":" + (player.flag((prefix + "selected").c_str()) ? "true" : "false") +
            ",\"forced\":" + (player.flag((prefix + "forced").c_str()) ? "true" : "false") + '}';
        ++logicalIndex;
    }
    return json + ']';
}

std::string subtitleTracksJson(Player &player) {
    const int64_t count = player.integer("track-list/count");
    const int64_t primarySubtitleId = player.integer("current-tracks/sub/id", player.integer("sid", -1));
    int64_t logicalIndex = 0;
    std::string json = "[";
    for (int64_t index = 0; index < count; ++index) {
        std::string prefix = "track-list/" + std::to_string(index) + "/";
        if (player.stringProperty((prefix + "type").c_str()) != "sub") continue;
        int64_t trackId = player.integer((prefix + "id").c_str(), logicalIndex + 1);
        std::string title = trimmed(player.stringProperty((prefix + "title").c_str()));
        std::string language = trimmed(player.stringProperty((prefix + "lang").c_str()));
        std::string codec = subtitleCodecDisplayName(player.stringProperty((prefix + "codec").c_str()));
        if (codec.empty()) codec = subtitleCodecDisplayName(player.stringProperty((prefix + "decoder-desc").c_str()));
        bool selected = player.flag((prefix + "selected").c_str()) ||
            (primarySubtitleId >= 0 && trackId == primarySubtitleId);
        bool forced = player.flag((prefix + "forced").c_str());
        std::string label = !title.empty() ? title : !language.empty() ? language :
            "Subtitle " + std::to_string(logicalIndex + 1);
        if (!codec.empty() && caseFolded(label).find(caseFolded(codec)) == std::string::npos) {
            label += " (" + codec + ")";
        }
        if (logicalIndex > 0) json += ',';
        json += "{\"index\":" + std::to_string(logicalIndex) +
            ",\"id\":" + jsonString(std::to_string(trackId)) +
            ",\"label\":" + jsonString(label) + ",\"language\":" + jsonString(language) +
            ",\"selected\":" + (selected ? "true" : "false") +
            ",\"forced\":" + (forced ? "true" : "false") + '}';
        ++logicalIndex;
    }
    return json + ']';
}

jlong milliseconds(double seconds) {
    return static_cast<jlong>(std::llround(std::max(0.0, seconds) * 1000.0));
}
}

#define JNI_METHOD(type, name) extern "C" JNIEXPORT type JNICALL \
    Java_com_nuvio_app_features_player_desktop_NativePlayerBridge_##name

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *, void *) {
    return JNI_VERSION_1_6;
}

JNI_METHOD(jlong, create)(
    JNIEnv *env, jobject, jlong hostViewPtr, jstring sourceUrl, jstring sourceAudioUrl,
    jobjectArray headerLines, jboolean playWhenReady, jlong initialPositionMs,
    jdouble initialProgressFraction, jstring controlsPageUrl,
    jboolean /* nvidiaRtxSuperResolutionEnabled */, jboolean /* nvidiaRtxHdrEnabled */,
    jboolean /* isAnimeContent */, jstring /* animeSvpFilter */, jobjectArray extraMpvOptions,
    jobject eventSink
) {
    return guarded(env, [&]() -> jlong {
        if (hostViewPtr <= 0 || hostViewPtr > 0xffffffffLL) {
            throw std::runtime_error("Linux playback requires a nonzero X11 Canvas drawable.");
        }
        std::string source = text(env, sourceUrl);
        if (source.empty()) throw std::runtime_error("Linux playback source URL is empty.");
        if (!eventSink) throw std::runtime_error("Native player event sink is required.");
        auto player = std::make_shared<Player>();
        player->reserveX11Lifetime();
        if (env->GetJavaVM(&player->vm) != JNI_OK) throw std::runtime_error("GetJavaVM failed.");
        LinuxAwtX11Lifecycle::prepare(env);
        player->sink = env->NewGlobalRef(eventSink);
        if (!player->sink) throw std::runtime_error("Unable to retain native player event sink.");
        jclass sinkClass = env->GetObjectClass(eventSink);
        if (!sinkClass) throw std::runtime_error("Unable to read native player event sink class.");
        player->onEvent = env->GetMethodID(sinkClass, "onPlayerEvent", "(Ljava/lang/String;D)V");
        env->DeleteLocalRef(sinkClass);
        if (!player->onEvent) throw std::runtime_error("Missing onPlayerEvent(String, Double).");
        std::setlocale(LC_NUMERIC, "C");
        player->mpv = mpv_create();
        if (!player->mpv) throw std::runtime_error("mpv_create failed.");

        // Vendor-neutral default; explicit user options below retain precedence.
        option(player->mpv, "hwdec", "auto");
        for (std::string entry : strings(env, extraMpvOptions)) {
            if (entry.rfind("@nuvio-user:", 0) == 0) entry.erase(0, 12);
            if (entry.rfind("@nuvio-", 0) == 0) continue; // Profile metadata, not mpv options.
            auto equals = entry.find('=');
            if (equals == std::string::npos) continue;
            std::string name = entry.substr(0, equals);
            int result = mpv_set_option_string(player->mpv, name.c_str(), entry.substr(equals + 1).c_str());
            if (result < 0) std::fprintf(stderr, "Linux mpv option %s: %s\n", name.c_str(), mpv_error_string(result));
        }
        // These embedding/lifecycle boundaries must win over saved desktop options.
        option(player->mpv, "config", "no");
        option(player->mpv, "terminal", "no");
        option(player->mpv, "osc", "no");
        option(player->mpv, "input-default-bindings", "no");
        option(player->mpv, "input-vo-keyboard", "no");
        option(player->mpv, "input-cursor", "no");
        option(player->mpv, "window-dragging", "no");
        option(player->mpv, "stop-screensaver", "no");
        option(player->mpv, "keep-open", "yes");
        option(player->mpv, "idle", "yes");
        option(player->mpv, "vo", "gpu-next");
        option(player->mpv, "gpu-api", "vulkan");
        option(player->mpv, "gpu-context", "x11vk");
        option(player->mpv, "wid", std::to_string(hostViewPtr));
        option(player->mpv, "pause", playWhenReady ? "no" : "yes");
        if (initialPositionMs > 0) {
            option(player->mpv, "start", std::to_string(static_cast<double>(initialPositionMs) / 1000.0));
        } else if (std::isfinite(initialProgressFraction) && initialProgressFraction > 0.0) {
            option(player->mpv, "start", std::to_string(std::min(initialProgressFraction, 1.0) * 100.0) + "%");
        }
        auto headers = strings(env, headerLines);
        headerOptions(player->mpv, headers);
        std::string audio = text(env, sourceAudioUrl);
        if (!audio.empty()) option(player->mpv, "audio-files-append", audio);
        checkMpv(mpv_initialize(player->mpv), "mpv_initialize");
        const char *load[] = {"loadfile", source.c_str(), nullptr};
        checkMpv(mpv_command(player->mpv, load), "loadfile");
        std::promise<bool> attached;
        auto ready = attached.get_future();
        player->events = std::thread([raw = player.get(), attached = std::move(attached)]() mutable {
            raw->pump(attached);
        });
        if (!ready.get()) throw std::runtime_error("Unable to attach Linux mpv event thread to JVM.");
        std::weak_ptr<Player> weak = player;
        player->controls = LinuxControlsOverlay::create(hostViewPtr, text(env, controlsPageUrl),
            [weak](const std::string &name, double value) {
                if (auto current = weak.lock()) current->controlsMessage(name, value);
            },
            [weak] {
                if (auto current = weak.lock()) return current->controlsSnapshot();
                return std::string{};
            });
        if (player->controls) {
            player->thumbnails = std::make_unique<LinuxSeekThumbnails>(source, std::move(headers),
                [weak](int64_t position, const std::string &url,
                       LinuxSeekThumbnails::Generation generation, uint64_t request) {
                    if (auto current = weak.lock()) {
                        std::lock_guard<std::mutex> lock(current->operations);
                        if (!current->stopping && current->controls)
                            current->controls->deliverSeekThumbnail(position, url, std::move(generation), request);
                    }
                });
        }
        jlong handle = nextHandle++;
        std::lock_guard<std::mutex> lock(playersLock);
        players.emplace(handle, player);
        return handle;
    });
}

JNI_METHOD(void, dispose)(JNIEnv *env, jobject, jlong handle) {
    guarded(env, [&]() {
        std::shared_ptr<Player> player;
        {
            std::lock_guard<std::mutex> lock(playersLock);
            auto found = players.find(handle);
            if (found == players.end()) return; // Includes dispose(0) and repeated disposal.
            player = found->second;
            players.erase(found);
        }
        player->close();
    });
}

JNI_METHOD(void, setPaused)(JNIEnv *env, jobject, jlong handle, jboolean paused) {
    withPlayer(env, handle, [&](Player &p) {
        int value = paused == JNI_TRUE;
        checkMpv(mpv_set_property(p.mpv, "pause", MPV_FORMAT_FLAG, &value), "pause");
    });
}
JNI_METHOD(void, seekTo)(JNIEnv *env, jobject, jlong handle, jlong position) {
    withPlayer(env, handle, [&](Player &p) { seek(p, std::max<jlong>(0, position), "absolute+exact"); });
}
JNI_METHOD(void, seekBy)(JNIEnv *env, jobject, jlong handle, jlong offset) {
    withPlayer(env, handle, [&](Player &p) { seek(p, offset, "relative+exact"); });
}
JNI_METHOD(void, requestSeekThumbnail)(JNIEnv *env, jobject, jlong handle, jlong positionMs) {
    guarded(env, [&] {
        std::shared_ptr<Player> player;
        {
            std::lock_guard<std::mutex> lock(playersLock);
            auto found = players.find(handle);
            if (found == players.end()) return; // Upstream ignores requests after disposal.
            player = found->second;
        }
        std::lock_guard<std::mutex> lock(player->operations);
        if (!player->stopping && player->thumbnails) player->thumbnails->request(positionMs);
    });
}
JNI_METHOD(void, setMpvProperty)(JNIEnv *env, jobject, jlong handle, jstring name, jstring value) {
    withPlayer(env, handle, [&](Player &p) { p.property(text(env, name).c_str(), text(env, value)); });
}
JNI_METHOD(void, setSpeed)(JNIEnv *env, jobject, jlong handle, jfloat speed) {
    withPlayer(env, handle, [&](Player &p) {
        double value = speed;
        checkMpv(mpv_set_property(p.mpv, "speed", MPV_FORMAT_DOUBLE, &value), "speed");
    });
}
JNI_METHOD(void, setVolume)(JNIEnv *env, jobject, jlong handle, jfloat volume) {
    withPlayer(env, handle, [&](Player &p) {
        double value = volume;
        checkMpv(mpv_set_property(p.mpv, "volume", MPV_FORMAT_DOUBLE, &value), "volume");
    });
}
JNI_METHOD(void, setMute)(JNIEnv *env, jobject, jlong handle, jboolean muted) {
    withPlayer(env, handle, [&](Player &p) {
        int value = muted == JNI_TRUE;
        checkMpv(mpv_set_property(p.mpv, "mute", MPV_FORMAT_FLAG, &value), "mute");
    });
}
JNI_METHOD(jlong, positionMs)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) { return milliseconds(p.number("time-pos")); });
}
JNI_METHOD(jlong, durationMs)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) { return milliseconds(p.number("duration")); });
}
JNI_METHOD(jlong, bufferedPositionMs)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) {
        double position = p.number("time-pos");
        double end = p.number("demuxer-cache-time", -1.0);
        return milliseconds(end >= position ? end : position + p.number("demuxer-cache-duration"));
    });
}
JNI_METHOD(jboolean, isPaused)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean { return p.flag("pause"); });
}
JNI_METHOD(jboolean, isEnded)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean { return p.ended || p.flag("eof-reached"); });
}
JNI_METHOD(jboolean, isLoading)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean {
        return !p.fileReady || p.flag("seeking") || p.flag("paused-for-cache") ||
            (p.flag("core-idle") && !p.flag("pause") && !p.ended && !p.flag("eof-reached"));
    });
}
JNI_METHOD(jfloat, speed)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jfloat { return p.number("speed", 1.0); });
}
JNI_METHOD(jfloat, volume)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jfloat { return p.number("volume"); });
}
JNI_METHOD(jboolean, isMuted)(JNIEnv *env, jobject, jlong handle) {
    return withPlayer(env, handle, [](Player &p) -> jboolean { return p.flag("mute"); });
}
JNI_METHOD(void, setResizeMode)(JNIEnv *env, jobject, jlong handle, jint mode) {
    withPlayer(env, handle, [&](Player &p) {
        p.property("keepaspect", mode == 1 ? "no" : "yes");
        p.property("panscan", mode == 2 ? "1" : "0");
    });
}

JNI_METHOD(jstring, audioTracksJson)(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) return env->NewStringUTF("[]");
    return withPlayer(env, handle, [&](Player &p) {
        std::string json = audioTracksJson(p);
        return env->NewStringUTF(json.c_str());
    });
}
JNI_METHOD(jstring, chaptersJson)(JNIEnv *env, jobject, jlong handle) {
    return guarded(env, [&] {
        auto player = playerFromHandle(handle);
        if (!player) return env->NewStringUTF("[]");
        std::lock_guard<std::mutex> lock(player->operations);
        if (!player->mpv || player->stopping) return env->NewStringUTF("[]");
        return env->NewStringUTF(chaptersJson(*player).c_str());
    });
}
JNI_METHOD(void, toggleStatsOverlay)(JNIEnv *env, jobject, jlong handle) {
    guarded(env, [&] {
        auto player = playerFromHandle(handle);
        if (!player) return;
        std::lock_guard<std::mutex> lock(player->operations);
        if (!player->mpv || player->stopping) return;
        // The matching stats.lua is embedded in libmpv and loaded once per player.
        // Match upstream's void JNI contract: diagnose failure without stopping playback.
        const char *command[] = {"script-binding", "stats/display-stats-toggle", nullptr};
        int result = mpv_command(player->mpv, command);
        if (result < 0)
            std::fprintf(stderr, "Linux mpv stats overlay: %s\n", mpv_error_string(result));
    });
}
JNI_METHOD(jboolean, selectAudioTrack)(JNIEnv *env, jobject, jlong handle, jint trackId) {
    if (handle == 0) return JNI_FALSE;
    return withPlayer(env, handle, [&](Player &p) -> jboolean {
        // A stale/nonexistent aid can disable audio. Resolve only live audio IDs
        // under the same operation lock used for the actual selection.
        const int64_t count = p.integer("track-list/count");
        for (int64_t index = 0; index < count; ++index) {
            std::string prefix = "track-list/" + std::to_string(index) + "/";
            if (p.stringProperty((prefix + "type").c_str()) != "audio" ||
                p.integer((prefix + "id").c_str(), -1) != trackId || trackId < 0) continue;
            int64_t id = trackId;
            return mpv_set_property(p.mpv, "aid", MPV_FORMAT_INT64, &id) >= 0;
        }
        return JNI_FALSE;
    });
}

// Subtitle configuration is replayed by the shared controller immediately after create/fileLoaded.
JNI_METHOD(jstring, subtitleTracksJson)(JNIEnv *env, jobject, jlong handle) {
    if (handle == 0) return env->NewStringUTF("[]");
    return withPlayer(env, handle, [&](Player &p) {
        std::string json = subtitleTracksJson(p);
        return env->NewStringUTF(json.c_str());
    });
}
JNI_METHOD(void, setSubtitleDelayMs)(JNIEnv *env, jobject, jlong handle, jint delay) {
    withPlayer(env, handle, [&](Player &p) { p.property("sub-delay", std::to_string(delay / 1000.0)); });
}
JNI_METHOD(jboolean, selectSubtitleTrack)(JNIEnv *env, jobject, jlong handle, jint trackId) {
    // The shared startup policy can disable subtitles even without track enumeration.
    return withPlayer(env, handle, [&](Player &p) -> jboolean {
        std::string value = trackId < 0 ? "no" : std::to_string(trackId);
        return mpv_set_property_string(p.mpv, "sid", value.c_str()) >= 0;
    });
}
JNI_METHOD(void, addSubtitleUrl)(JNIEnv *env, jobject, jlong handle, jstring url) {
    withPlayer(env, handle, [&](Player &p) {
        std::string subtitle = text(env, url);
        if (subtitle.empty()) return;
        const char *command[] = {"sub-add", subtitle.c_str(), "select", nullptr};
        checkMpv(mpv_command(p.mpv, command), "sub-add");
    });
}
JNI_METHOD(void, clearExternalSubtitles)(JNIEnv *env, jobject, jlong handle) {
    withPlayer(env, handle, [&](Player &p) {
        removeExternalSubtitleTracks(p);
        checkMpv(mpv_set_property_string(p.mpv, "sid", "no"), "sid");
    });
}
JNI_METHOD(void, clearExternalSubtitlesAndSelect)(JNIEnv *env, jobject, jlong handle, jint trackId) {
    withPlayer(env, handle, [&](Player &p) {
        removeExternalSubtitleTracks(p);
        std::string value = trackId >= 0 ? std::to_string(trackId) : "no";
        checkMpv(mpv_set_property_string(p.mpv, "sid", value.c_str()), "sid");
    });
}
JNI_METHOD(void, setSubtitleAssStyleMode)(JNIEnv *env, jobject, jlong handle, jstring mode, jdouble scale) {
    withPlayer(env, handle, [&](Player &p) {
        p.property("sub-ass-override", text(env, mode));
        p.property("sub-scale", std::to_string(scale));
    });
}
JNI_METHOD(void, applySubtitleStyle)(
    JNIEnv *env, jobject, jlong handle, jstring color, jstring background, jstring outline,
    jfloat outlineSize, jboolean bold, jfloat fontSize, jint position, jstring font
) {
    withPlayer(env, handle, [&](Player &p) {
        std::string back = text(env, background);
        p.property("sub-color", text(env, color));
        p.property("sub-back-color", back);
        p.property("sub-outline-color", text(env, outline));
        p.property("sub-border-style", back.rfind("#00", 0) == 0 ? "outline-and-shadow" : "opaque-box");
        p.property("sub-outline-size", std::to_string(outlineSize));
        p.property("sub-bold", bold ? "yes" : "no");
        p.property("sub-font-size", std::to_string(fontSize));
        p.property("sub-pos", std::to_string(position));
        p.property("sub-font", text(env, font));
    });
}

JNI_METHOD(void, updateControls)(JNIEnv *env, jobject, jlong handle, jstring json) {
    withPlayer(env, handle, [&](Player &p) { if (p.controls) p.controls->updateControls(text(env, json)); });
}
JNI_METHOD(void, runJavaScript)(JNIEnv *env, jobject, jlong handle, jstring script) {
    withPlayer(env, handle, [&](Player &p) { if (p.controls) p.controls->runJavaScript(text(env, script)); });
}
// controls.js emits cursorVisibility, which the shared Kotlin controller routes here.
JNI_METHOD(void, setCursorHidden)(JNIEnv *env, jobject, jlong handle, jboolean hidden) {
    withPlayer(env, handle, [&](Player &p) { if (p.controls) p.controls->setCursorHidden(hidden); });
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_player_desktop_LinuxPlayerControlsBridge_setWindowFocused(
    JNIEnv *env, jobject, jlong handle, jboolean focused) {
    withPlayer(env, handle, [&](Player &p) {
        if (p.controls) p.controls->setWindowFocused(focused == JNI_TRUE);
    });
}
// Media session integration is still absent.
JNI_METHOD(void, setMediaSessionMetadata)(JNIEnv *, jobject, jlong, jstring, jstring, jstring) {}
// X11's mpv VO owns expose/resize/redraw, unlike the Windows D3D redraw workaround.
JNI_METHOD(void, forceVideoRedraw)(JNIEnv *, jobject, jlong) {}
