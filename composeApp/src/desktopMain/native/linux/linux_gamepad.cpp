#include "linux_gamepad.h"

#include <algorithm>
#include <cerrno>
#include <cctype>
#include <chrono>
#include <dirent.h>
#include <fcntl.h>
#include <memory>
#include <sys/ioctl.h>
#include <sys/stat.h>
#include <unistd.h>

namespace nuvio::gamepad {
namespace {
bool axis(const Capabilities& caps, int code) {
    return caps.axes[code] && caps.ranges[code].maximum > caps.ranges[code].minimum;
}
bool pair(const Capabilities& caps, int x, int y) { return axis(caps, x) && axis(caps, y); }

int scale(const Capabilities& caps, const State& state, int code, bool stick = false) {
    if (!axis(caps, code)) return 0;
    const auto& range = caps.ranges[code];
    const int64_t low = range.minimum, high = range.maximum;
    const int64_t value = std::clamp<int64_t>(state.axes[code], low, high);
    if (!stick) return static_cast<int>((value - low) * 255 / (high - low));
    // Integer midpoint matches common 0..255 and -32768..32767 kernel ranges.
    const int64_t center = low + (high - low + 1) / 2;
    if (value == center) return 0;
    return value < center
        ? static_cast<int>(-(center - value) * 32767 / (center - low))
        : static_cast<int>((value - center) * 32767 / (high - center));
}

class PosixSystem final : public System {
    template<size_t N> static bool bits(int fd, unsigned long request, std::bitset<N>& result) {
        std::array<unsigned char, (N + 7) / 8> bytes{};
        if (ioctl(fd, request, bytes.data()) < 0) return false;
        result.reset();
        for (size_t i = 0; i < N; ++i) result[i] = (bytes[i / 8] & (1u << (i % 8))) != 0;
        return true;
    }
public:
    uint64_t nowMs() override {
        return std::chrono::duration_cast<std::chrono::milliseconds>(
            std::chrono::steady_clock::now().time_since_epoch()).count();
    }
    bool enumerate(std::vector<Node>& nodes) override {
        const auto closeDirectory = [](DIR* directory) { closedir(directory); };
        const std::unique_ptr<DIR, decltype(closeDirectory)> directory(opendir("/dev/input"), closeDirectory);
        if (!directory) return false;
        // Count every entry, including unrelated entries, to bound discovery work.
        for (int count = 0; count < MaxDiscoveryEntries; ++count) {
            const auto* entry = readdir(directory.get());
            if (!entry) break;
            const std::string name(entry->d_name);
            if (name.size() <= 5 || name.compare(0, 5, "event") != 0 ||
                !std::all_of(name.begin() + 5, name.end(), [](unsigned char c) { return std::isdigit(c); })) continue;
            const std::string path = "/dev/input/" + name;
            struct stat info{};
            if (lstat(path.c_str(), &info) == 0 && S_ISCHR(info.st_mode))
                nodes.push_back({path, static_cast<uint64_t>(info.st_rdev), static_cast<uint64_t>(info.st_ino)});
        }
        std::sort(nodes.begin(), nodes.end(), [](const Node& a, const Node& b) { return a.path < b.path; });
        return true;
    }

    int open(const Node& node, Capabilities& caps, State& state) override {
        const int fd = ::open(node.path.c_str(), O_RDONLY | O_NONBLOCK | O_CLOEXEC | O_NOFOLLOW);
        if (fd < 0) return -1; // EACCES/ENOENT are retried at the next discovery pass.
        struct stat info{};
        std::bitset<INPUT_PROP_CNT> properties;
        bool valid = fstat(fd, &info) == 0 && S_ISCHR(info.st_mode) &&
            node.device == static_cast<uint64_t>(info.st_rdev) && node.inode == static_cast<uint64_t>(info.st_ino) &&
            bits(fd, EVIOCGBIT(EV_KEY, (KEY_CNT + 7) / 8), caps.keys) &&
            bits(fd, EVIOCGBIT(EV_ABS, (ABS_CNT + 7) / 8), caps.axes);
        if (bits(fd, EVIOCGPROP((INPUT_PROP_CNT + 7) / 8), properties))
            caps.accelerometer = properties[INPUT_PROP_ACCELEROMETER];
        for (int i = 0; valid && i < ABS_CNT; ++i) {
            if (caps.axes[i] && ioctl(fd, EVIOCGABS(i), &caps.ranges[i]) < 0) valid = false;
        }
        if (!valid || !qualifies(caps) || !snapshot(fd, caps, state)) {
            ::close(fd);
            return -1;
        }
        return fd;
    }

    ReadResult read(int fd, input_event& event) override {
        const auto size = ::read(fd, &event, sizeof(event));
        if (size == sizeof(event)) return ReadResult::Event;
        if (size < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) return ReadResult::Empty;
        if (size < 0 && errno == EINTR) return ReadResult::Interrupted;
        return ReadResult::Failed;
    }

    bool snapshot(int fd, const Capabilities& caps, State& state) override {
        State fresh;
        if (!bits(fd, EVIOCGKEY((KEY_CNT + 7) / 8), fresh.keys)) return false;
        for (int i = 0; i < ABS_CNT; ++i) {
            if (!caps.axes[i]) continue;
            input_absinfo info{};
            if (ioctl(fd, EVIOCGABS(i), &info) < 0) return false;
            fresh.axes[i] = info.value;
        }
        state = fresh;
        return true;
    }
    void close(int fd) override { ::close(fd); }
};
} // namespace

bool qualifies(const Capabilities& caps) {
    // Standard gamepad face controls plus navigation. Reject sensor and touch collections.
    return !caps.accelerometer && !caps.keys[BTN_TOUCH] && !caps.axes[ABS_MT_POSITION_X] &&
        caps.keys[BTN_SOUTH] && caps.keys[BTN_EAST] &&
        (pair(caps, ABS_X, ABS_Y) || pair(caps, ABS_HAT0X, ABS_HAT0Y) ||
         (caps.keys[BTN_DPAD_UP] && caps.keys[BTN_DPAD_DOWN] &&
          caps.keys[BTN_DPAD_LEFT] && caps.keys[BTN_DPAD_RIGHT]));
}

Sample normalize(const Capabilities& caps, const State& state) {
    Sample result{};
    constexpr std::pair<int, int> buttons[] = {
        {BTN_DPAD_UP, 1}, {BTN_DPAD_DOWN, 2}, {BTN_DPAD_LEFT, 4}, {BTN_DPAD_RIGHT, 8},
        {BTN_START, 0x10}, {BTN_SELECT, 0x20}, {BTN_THUMBL, 0x40}, {BTN_THUMBR, 0x80},
        {BTN_TL, 0x100}, {BTN_TR, 0x200}, {BTN_SOUTH, 0x1000}, {BTN_EAST, 0x2000},
        {BTN_WEST, 0x4000}, {BTN_NORTH, 0x8000},
    };
    for (auto [code, bit] : buttons) if (caps.keys[code] && state.keys[code]) result[0] |= bit;
    // BTN_MODE has no existing logical binding (also ignored by Windows).
    if (axis(caps, ABS_HAT0X)) result[0] |= state.axes[ABS_HAT0X] < 0 ? 4 : state.axes[ABS_HAT0X] > 0 ? 8 : 0;
    if (axis(caps, ABS_HAT0Y)) result[0] |= state.axes[ABS_HAT0Y] < 0 ? 1 : state.axes[ABS_HAT0Y] > 0 ? 2 : 0;
    result[3] = scale(caps, state, ABS_X, true);
    result[4] = -scale(caps, state, ABS_Y, true);

    int rightX = ABS_RX, rightY = ABS_RY;
    if (pair(caps, ABS_BRAKE, ABS_GAS)) {
        // Android-style layouts explicitly separate trigger axes from Z/RZ right stick.
        if (!pair(caps, ABS_RX, ABS_RY) && pair(caps, ABS_Z, ABS_RZ)) {
            rightX = ABS_Z; rightY = ABS_RZ;
        }
    }
    // Resolve each side independently; only an established RX/RY stick disambiguates
    // Z/RZ as triggers. Never reuse the Android-style Z/RZ stick as trigger axes.
    const auto triggerSource = [&](int dedicated, int hat, int z) {
        if (axis(caps, dedicated)) return dedicated;
        if (axis(caps, hat)) return hat;
        return pair(caps, ABS_RX, ABS_RY) && axis(caps, z) ? z : -1;
    };
    const int leftTrigger = triggerSource(ABS_BRAKE, ABS_HAT2Y, ABS_Z);
    const int rightTrigger = triggerSource(ABS_GAS, ABS_HAT2X, ABS_RZ);
    // Z/RZ without an established right stick or separate triggers is ambiguous; don't guess.
    if (pair(caps, rightX, rightY)) {
        result[5] = scale(caps, state, rightX, true);
        result[6] = -scale(caps, state, rightY, true);
    }
    result[1] = leftTrigger >= 0 ? scale(caps, state, leftTrigger)
        : caps.keys[BTN_TL2] && state.keys[BTN_TL2] ? 255 : 0;
    result[2] = rightTrigger >= 0 ? scale(caps, state, rightTrigger)
        : caps.keys[BTN_TR2] && state.keys[BTN_TR2] ? 255 : 0;
    return result;
}

Reader::~Reader() { for (auto& device : devices_) disconnect(device); }

void Reader::disconnect(Device& device) {
    if (device.fd >= 0) system_.close(device.fd);
    device = Device{};
}

void Reader::discover() {
    std::vector<Node> nodes;
    if (!system_.enumerate(nodes)) return; // A transient directory failure is not a disconnection.
    if (nodes.size() > MaxDiscoveryEntries) nodes.resize(MaxDiscoveryEntries);
    int retired = 0;
    for (int i = 0; i < Slots; ++i) {
        auto& device = devices_[i];
        if (device.fd >= 0 && std::find(nodes.begin(), nodes.end(), device.node) == nodes.end()) {
            disconnect(device);
            retired |= 1 << i;
        }
    }
    for (const auto& node : nodes) {
        if (std::any_of(devices_.begin(), devices_.end(), [&](const Device& d) { return d.fd >= 0 && d.node == node; })) continue;
        int slot = 0;
        while (slot < Slots && (devices_[slot].fd >= 0 || (retired & (1 << slot)))) ++slot;
        if (slot == Slots) break;
        Device fresh;
        fresh.node = node;
        fresh.fd = system_.open(node, fresh.caps, fresh.pending);
        if (fresh.fd < 0) continue;
        if (!qualifies(fresh.caps)) { system_.close(fresh.fd); continue; }
        fresh.committed = normalize(fresh.caps, fresh.pending);
        devices_[slot] = std::move(fresh);
    }
    // Retired slots cannot be reused until a later poll: Kotlin must observe the disconnect.
}

bool Reader::drain(Device& device) {
    using Sync = Device::Sync;
    const Sample published = device.committed;
    for (int i = 0; i < MaxEventsPerPoll; ++i) {
        input_event event{};
        const auto read = system_.read(device.fd, event);
        if (read == ReadResult::Failed) return false;
        if (read == ReadResult::Interrupted) continue; // EINTR does not prove the queue is empty.
        if (read == ReadResult::Empty) {
            if (device.sync == Sync::DiscardBacklog) {
                // EVIOCGABS does not flush old ABS events. Drain to EAGAIN first,
                // then verify that no events arrived during the multi-ioctl snapshot.
                if (!system_.snapshot(device.fd, device.caps, device.pending)) return false;
                device.sync = Sync::VerifySnapshot;
                continue;
            }
            if (device.sync == Sync::VerifySnapshot) {
                device.committed = normalize(device.caps, device.pending);
                device.committed[7] = static_cast<int>(++device.packet & 0x7fffffff);
                device.sync = Sync::Normal;
            }
            break;
        }
        if (event.type == EV_SYN && event.code == SYN_DROPPED) {
            device.sync = Sync::AwaitReport;
            // Reports consumed earlier in this poll have not reached Kotlin yet.
            device.committed = published;
            continue;
        }
        if (device.sync != Sync::Normal) {
            // Discard history, including arrivals during a snapshot. A new snapshot
            // after their report/queue boundary recovers the genuinely current state.
            device.sync = event.type == EV_SYN && event.code == SYN_REPORT
                ? Sync::DiscardBacklog : Sync::AwaitReport;
            continue;
        }
        if (event.type == EV_SYN && event.code == SYN_REPORT) {
            device.committed = normalize(device.caps, device.pending);
            device.committed[7] = static_cast<int>(++device.packet & 0x7fffffff);
        } else {
            if (event.type == EV_KEY && event.code < KEY_CNT && device.caps.keys[event.code])
                device.pending.keys[event.code] = event.value != 0;
            if (event.type == EV_ABS && event.code < ABS_CNT && device.caps.axes[event.code])
                device.pending.axes[event.code] = event.value;
        }
    }
    return true;
}

int Reader::poll(int slotMask, int* output) {
    std::fill(output, output + Slots * Stride, 0);
    if ((slotMask & 0xff) == 0xff) {
        const auto now = system_.nowMs();
        // With eight connected pads a narrow poll also has mask 0xff. Bound scans natively too.
        if (now >= nextDiscoveryMs_) {
            nextDiscoveryMs_ = now + DiscoveryIntervalMs;
            discover();
        }
    }
    int connected = 0;
    for (int i = 0; i < Slots; ++i) {
        auto& device = devices_[i];
        if (device.fd < 0) continue;
        // Always check open devices, even if the caller's last mask omitted a slot.
        if (!drain(device)) { disconnect(device); continue; }
        connected |= 1 << i;
        std::copy(device.committed.begin(), device.committed.end(), output + i * Stride);
    }
    return connected;
}
} // namespace nuvio::gamepad

#ifndef NUVIO_GAMEPAD_TEST
#include <jni.h>
#include <new>
namespace {
struct Session {
    nuvio::gamepad::PosixSystem system;
    nuvio::gamepad::Reader reader{system};
};
}
extern "C" JNIEXPORT jlong JNICALL
Java_com_nuvio_app_features_input_LinuxGamepadNative_open(JNIEnv*, jobject) {
    return reinterpret_cast<jlong>(new (std::nothrow) Session);
}
extern "C" JNIEXPORT jint JNICALL
Java_com_nuvio_app_features_input_LinuxGamepadNative_poll(JNIEnv* env, jobject, jlong handle, jint mask, jintArray out) {
    if (!handle || !out || env->GetArrayLength(out) < 64) return 0;
    std::array<int, 64> state{};
    int connected = 0;
    try { connected = reinterpret_cast<Session*>(handle)->reader.poll(mask, state.data()); }
    catch (...) { state.fill(0); } // Allocation failure must not unwind through JNI.
    env->SetIntArrayRegion(out, 0, 64, state.data());
    return connected;
}
extern "C" JNIEXPORT void JNICALL
Java_com_nuvio_app_features_input_LinuxGamepadNative_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Session*>(handle);
}
#endif
