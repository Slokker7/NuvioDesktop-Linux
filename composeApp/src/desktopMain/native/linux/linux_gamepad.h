#pragma once

#include <linux/input.h>
#include <array>
#include <bitset>
#include <cstdint>
#include <string>
#include <vector>

namespace nuvio::gamepad {
constexpr int Slots = 8;
constexpr int Stride = 8;
constexpr int MaxEventsPerPoll = 256;
constexpr int MaxDiscoveryEntries = 512;
constexpr uint64_t DiscoveryIntervalMs = 2000;
using Sample = std::array<int, Stride>;

struct Capabilities {
    std::bitset<KEY_CNT> keys;
    std::bitset<ABS_CNT> axes;
    std::array<input_absinfo, ABS_CNT> ranges{};
    bool accelerometer = false;
};
struct State {
    std::bitset<KEY_CNT> keys;
    std::array<int, ABS_CNT> axes{};
};
struct Node {
    std::string path;
    // Node identity, not model identity: identical controllers remain separate.
    uint64_t device = 0;
    uint64_t inode = 0;
    bool operator==(const Node& other) const {
        return path == other.path && device == other.device && inode == other.inode;
    }
};
enum class ReadResult { Event, Empty, Interrupted, Failed };

// Only the device boundary is virtual; fixtures exercise the production reader and mapper.
class System {
public:
    virtual ~System() = default;
    virtual uint64_t nowMs() = 0;
    virtual bool enumerate(std::vector<Node>& nodes) = 0;
    virtual int open(const Node& node, Capabilities& caps, State& state) = 0;
    virtual ReadResult read(int fd, input_event& event) = 0;
    virtual bool snapshot(int fd, const Capabilities& caps, State& state) = 0;
    virtual void close(int fd) = 0;
};

bool qualifies(const Capabilities& caps);
Sample normalize(const Capabilities& caps, const State& state);

class Reader {
public:
    explicit Reader(System& system) : system_(system) {}
    ~Reader();
    Reader(const Reader&) = delete;
    Reader& operator=(const Reader&) = delete;
    int poll(int slotMask, int* output);
private:
    struct Device {
        enum class Sync { Normal, AwaitReport, DiscardBacklog, VerifySnapshot };
        int fd = -1;
        Node node;
        Capabilities caps;
        State pending;
        Sample committed{};
        Sync sync = Sync::Normal;
        uint32_t packet = 0;
    };
    System& system_;
    std::array<Device, Slots> devices_{};
    uint64_t nextDiscoveryMs_ = 0;
    void disconnect(Device& device);
    void discover();
    bool drain(Device& device);
};
} // namespace nuvio::gamepad
