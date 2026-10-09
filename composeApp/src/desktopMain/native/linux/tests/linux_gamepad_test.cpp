#include "linux_gamepad.h"
#include <algorithm>
#include <deque>
#include <functional>
#include <iostream>
#include <map>
#include <stdexcept>

using namespace nuvio::gamepad;
#define CHECK(condition) do { if (!(condition)) throw std::runtime_error(std::string(__func__) + ":" + std::to_string(__LINE__) + " " #condition); } while (false)

void axis(Capabilities& c, int code, int low, int high) {
    c.axes.set(code); c.ranges[code].minimum = low; c.ranges[code].maximum = high;
}
Capabilities standard(bool playstation = false) {
    Capabilities c;
    for (int k : {BTN_SOUTH, BTN_EAST, BTN_WEST, BTN_NORTH, BTN_TL, BTN_TR,
                  BTN_SELECT, BTN_START, BTN_THUMBL, BTN_THUMBR, BTN_MODE}) c.keys.set(k);
    for (int a : {ABS_X, ABS_Y, ABS_RX, ABS_RY}) axis(c, a, playstation ? 0 : -32768, playstation ? 255 : 32767);
    if (playstation) { c.keys.set(BTN_TL2); c.keys.set(BTN_TR2); }
    axis(c, ABS_Z, 0, playstation ? 255 : 1023); axis(c, ABS_RZ, 0, playstation ? 255 : 1023);
    axis(c, ABS_HAT0X, -1, 1); axis(c, ABS_HAT0Y, -1, 1);
    return c;
}
State rest(const Capabilities& c) {
    State s;
    for (int a = 0; a < ABS_CNT; ++a) s.axes[a] = c.ranges[a].minimum;
    for (int a : {ABS_X, ABS_Y, ABS_RX, ABS_RY, ABS_HAT0X, ABS_HAT0Y})
        s.axes[a] = c.ranges[a].minimum + (static_cast<int64_t>(c.ranges[a].maximum) - c.ranges[a].minimum + 1) / 2;
    return s;
}
input_event event(int type, int code, int value) {
    input_event e{}; e.type = type; e.code = code; e.value = value; return e;
}

struct FakeSystem : System {
    struct Pad {
        Capabilities caps = standard();
        State state = rest(caps);
        std::deque<input_event> events;
        bool denied = false, failed = false, snapshotFailed = false;
        int emptyInterruptions = 0;
        std::function<void(Pad&)> afterSnapshot;
    };
    std::map<std::string, Pad> pads;
    std::vector<Node> nodes;
    std::map<int, std::string> handles;
    int next = 1, opens = 0, closes = 0, reads = 0, snapshots = 0, enumerations = 0;
    bool directoryFailed = false;
    uint64_t time = 0, clockStep = DiscoveryIntervalMs;
    uint64_t nowMs() override { const auto now = time; time += clockStep; return now; }
    Pad& add(const std::string& path, uint64_t inode = 1) {
        nodes.push_back({path, 13, inode});
        return pads[path];
    }
    bool enumerate(std::vector<Node>& out) override { ++enumerations; out = nodes; return !directoryFailed; }
    int open(const Node& n, Capabilities& c, State& s) override {
        ++opens;
        auto& p = pads.at(n.path);
        if (p.denied) return -1;
        c = p.caps; s = p.state;
        handles[next] = n.path;
        return next++;
    }
    ReadResult read(int fd, input_event& out) override {
        ++reads;
        auto& p = pads.at(handles.at(fd));
        if (p.failed) return ReadResult::Failed;
        if (p.events.empty()) {
            if (p.emptyInterruptions > 0) { --p.emptyInterruptions; return ReadResult::Interrupted; }
            return ReadResult::Empty;
        }
        out = p.events.front(); p.events.pop_front(); return ReadResult::Event;
    }
    bool snapshot(int fd, const Capabilities&, State& s) override {
        ++snapshots;
        auto& p = pads.at(handles.at(fd)); s = p.state;
        if (p.afterSnapshot) p.afterSnapshot(p);
        return !p.snapshotFailed;
    }
    void close(int fd) override { CHECK(handles.erase(fd) == 1); ++closes; }
};

void qualification() {
    auto c = standard(); CHECK(qualifies(c));
    c.accelerometer = true; CHECK(!qualifies(c));
    c = standard(); c.keys.set(BTN_TOUCH); CHECK(!qualifies(c));
    c = standard(); c.axes.set(ABS_MT_POSITION_X); CHECK(!qualifies(c));
    c = standard(); c.keys.reset(); CHECK(!qualifies(c));
    c = standard(); c.axes.reset(); CHECK(!qualifies(c));
    // Generic digital pad, no sticks required.
    for (int k : {BTN_DPAD_UP, BTN_DPAD_DOWN, BTN_DPAD_LEFT, BTN_DPAD_RIGHT}) c.keys.set(k);
    CHECK(qualifies(c));
}
void buttonsAndHats() {
    auto c = standard(); auto s = rest(c);
    const std::pair<int, int> mapping[] = {{BTN_SOUTH, 0x1000}, {BTN_EAST, 0x2000}, {BTN_WEST, 0x4000},
        {BTN_NORTH, 0x8000}, {BTN_TL, 0x100}, {BTN_TR, 0x200}, {BTN_START, 0x10},
        {BTN_SELECT, 0x20}, {BTN_THUMBL, 0x40}, {BTN_THUMBR, 0x80},
        {BTN_DPAD_UP, 1}, {BTN_DPAD_DOWN, 2}, {BTN_DPAD_LEFT, 4}, {BTN_DPAD_RIGHT, 8}};
    for (auto [code, bit] : mapping) {
        c.keys.set(code); s.keys.reset(); s.keys.set(code); CHECK(normalize(c, s)[0] == bit);
    }
    s.keys.reset(); s.keys.set(BTN_MODE); CHECK(normalize(c, s)[0] == 0);
    s.axes[ABS_HAT0X] = -1; s.axes[ABS_HAT0Y] = 1; CHECK(normalize(c, s)[0] == 6);
    s.axes[ABS_HAT0X] = 1; s.axes[ABS_HAT0Y] = -1; CHECK(normalize(c, s)[0] == 9);
}
void stickNormalization() {
    for (bool sony : {false, true}) {
        const auto c = standard(sony); auto s = rest(c);
        for (int index = 3; index <= 6; ++index) CHECK(normalize(c, s)[index] == 0);
        const int axes[] = {ABS_X, ABS_Y, ABS_RX, ABS_RY};
        for (int i = 0; i < 4; ++i) {
            const int code = axes[i], sign = i % 2 ? -1 : 1;
            s.axes[code] = c.ranges[code].minimum; CHECK(normalize(c, s)[i + 3] == -32767 * sign);
            s.axes[code] = c.ranges[code].maximum; CHECK(normalize(c, s)[i + 3] == 32767 * sign);
        }
    }
    auto c = standard(); auto s = rest(c);
    axis(c, ABS_X, 10, 10); CHECK(normalize(c, s)[3] == 0);
    axis(c, ABS_X, -100, 100); s.axes[ABS_X] = 1000; CHECK(normalize(c, s)[3] == 32767);
}
void triggerNormalization() {
    for (bool sony : {false, true}) {
        auto c = standard(sony); auto s = rest(c);
        CHECK(normalize(c, s)[1] == 0 && normalize(c, s)[2] == 0);
        s.axes[ABS_Z] = c.ranges[ABS_Z].maximum / 2; CHECK(normalize(c, s)[1] == 127);
        s.axes[ABS_Z] = c.ranges[ABS_Z].maximum; s.axes[ABS_RZ] = c.ranges[ABS_RZ].maximum;
        CHECK(normalize(c, s)[1] == 255 && normalize(c, s)[2] == 255);
    }
    auto c = standard(); axis(c, ABS_Z, -32768, 32767); auto s = rest(c);
    CHECK(normalize(c, s)[1] == 0); s.axes[ABS_Z] = 0; CHECK(normalize(c, s)[1] == 127);
}
void alternateTriggers() {
    auto c = standard(); c.axes.reset(ABS_RX); c.axes.reset(ABS_RY);
    axis(c, ABS_Z, -100, 100); axis(c, ABS_RZ, -100, 100);
    axis(c, ABS_BRAKE, 0, 1023); axis(c, ABS_GAS, 0, 1023);
    auto s = rest(c); s.axes[ABS_Z] = 100; s.axes[ABS_RZ] = -100;
    s.axes[ABS_BRAKE] = 1023; s.axes[ABS_GAS] = 512;
    const auto result = normalize(c, s);
    CHECK(result[1] == 255 && result[2] == 127 && result[5] == 32767 && result[6] == 32767);
    c = standard(); axis(c, ABS_HAT2Y, 0, 255); axis(c, ABS_HAT2X, 0, 255); s = rest(c);
    s.axes[ABS_HAT2Y] = 255; CHECK(normalize(c, s)[1] == 255 && normalize(c, s)[2] == 0);
}
void digitalAndAmbiguousTriggers() {
    auto c = standard(); c.axes.reset(ABS_RX); c.axes.reset(ABS_RY);
    auto s = rest(c); s.axes[ABS_Z] = 1023; s.axes[ABS_RZ] = 1023;
    CHECK(normalize(c, s)[1] == 0 && normalize(c, s)[2] == 0);
    c.keys.set(BTN_TL2); c.keys.set(BTN_TR2); s.keys.set(BTN_TL2); s.keys.set(BTN_TR2);
    CHECK(normalize(c, s)[1] == 255 && normalize(c, s)[2] == 255);
}
void hybridAnalogTriggersWin() {
    const auto c = standard(true); auto s = rest(c);
    for (int left : {0, 20, 59, 60, 61, 100, 255}) {
        for (int right : {0, 20, 59, 60, 61, 100, 255}) {
            s.axes[ABS_Z] = left; s.axes[ABS_RZ] = right;
            for (bool leftDown : {false, true}) for (bool rightDown : {false, true}) {
                s.keys[BTN_TL2] = leftDown; s.keys[BTN_TR2] = rightDown;
                const auto sample = normalize(c, s);
                CHECK(sample[1] == left && sample[2] == right);
            }
        }
    }
}
void independentAnalogTriggers() {
    for (bool left : {false, true}) for (bool degenerate : {false, true}) {
        auto c = standard(true);
        const int good = left ? ABS_Z : ABS_RZ, missing = left ? ABS_RZ : ABS_Z;
        if (degenerate) axis(c, missing, 10, 10); else c.axes.reset(missing);
        auto s = rest(c); s.axes[good] = 100;
        const int analog = left ? 1 : 2, digital = left ? 2 : 1;
        CHECK(normalize(c, s)[analog] == 100 && normalize(c, s)[digital] == 0);
        s.keys.set(BTN_TL2); s.keys.set(BTN_TR2);
        CHECK(normalize(c, s)[analog] == 100 && normalize(c, s)[digital] == 255);
        // Removing the established right stick must not turn Z/RZ into triggers.
        c.axes.reset(ABS_RX); c.axes.reset(ABS_RY); s.keys.reset();
        CHECK(normalize(c, s)[1] == 0 && normalize(c, s)[2] == 0);
    }
    auto c = standard(true); c.axes.reset(ABS_Z); c.axes.reset(ABS_RZ); auto s = rest(c);
    for (bool left : {false, true}) for (bool right : {false, true}) {
        s.keys[BTN_TL2] = left; s.keys[BTN_TR2] = right;
        CHECK(normalize(c, s)[1] == (left ? 255 : 0));
        CHECK(normalize(c, s)[2] == (right ? 255 : 0));
    }
}
void independentAlternateTriggers() {
    for (auto codes : {std::pair{ABS_BRAKE, ABS_GAS}, std::pair{ABS_HAT2Y, ABS_HAT2X}}) {
        for (bool left : {false, true}) {
            auto c = standard(true); const int code = left ? codes.first : codes.second;
            axis(c, code, 0, 255); auto s = rest(c);
            s.axes[code] = 100; s.axes[left ? ABS_RZ : ABS_Z] = 60;
            s.keys.set(BTN_TL2); s.keys.set(BTN_TR2);
            CHECK(normalize(c, s)[left ? 1 : 2] == 100);
            CHECK(normalize(c, s)[left ? 2 : 1] == 60);
        }
    }
    auto c = standard(true);
    axis(c, ABS_BRAKE, 0, 255); axis(c, ABS_GAS, 0, 255);
    axis(c, ABS_HAT2Y, 0, 255); axis(c, ABS_HAT2X, 0, 255);
    auto s = rest(c); s.axes[ABS_BRAKE] = 20; s.axes[ABS_GAS] = 60;
    s.axes[ABS_HAT2Y] = 100; s.axes[ABS_HAT2X] = 100;
    s.axes[ABS_Z] = 255; s.axes[ABS_RZ] = 255;
    CHECK(normalize(c, s)[1] == 20 && normalize(c, s)[2] == 60);
}
void eventFixtures() {
    for (int kind = 0; kind < 3; ++kind) {
        FakeSystem sys; auto& p = sys.add("event0");
        p.caps = standard(kind == 1);
        if (kind == 2) { axis(p.caps, ABS_X, -1000, 1000); p.caps.keys.set(BTN_DPAD_UP); }
        p.state = rest(p.caps);
        Reader reader(sys); std::array<int, 64> out{}; CHECK(reader.poll(255, out.data()) == 1);
        p.events = {event(EV_KEY, BTN_SOUTH, 1), event(EV_ABS, ABS_X, p.caps.ranges[ABS_X].maximum),
            event(EV_ABS, ABS_Z, p.caps.ranges[ABS_Z].maximum), event(EV_SYN, SYN_REPORT, 0)};
        CHECK(reader.poll(1, out.data()) == 1);
        CHECK(out[0] == 0x1000 && out[1] == 255 && out[3] == 32767 && out[7] == 1);
    }
}
void reportBoundary() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data()); p.events.push_back(event(EV_KEY, BTN_SOUTH, 1));
    r.poll(1, out.data()); CHECK(out[0] == 0);
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0)); r.poll(1, out.data()); CHECK(out[0] == 0x1000);
    p.events = {event(EV_KEY, BTN_SOUTH, 0), event(EV_SYN, SYN_REPORT, 0)};
    r.poll(1, out.data()); CHECK(out[0] == 0);
}
void droppedRecovery() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data()); p.state.keys.set(BTN_EAST);
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_KEY, BTN_SOUTH, 1)};
    r.poll(1, out.data()); CHECK(sys.snapshots == 0 && out[0] == 0);
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0)); r.poll(1, out.data());
    CHECK(sys.snapshots == 1 && out[0] == 0x2000);
    p.snapshotFailed = true; p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    CHECK(r.poll(1, out.data()) == 0 && out[0] == 0 && sys.handles.empty());
}
void droppedBacklogAcrossPolls() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0),
        event(EV_ABS, ABS_X, 32767), event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0)};
    for (int i = 0; i < MaxEventsPerPoll * 3; ++i) p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    p.events.push_back(event(EV_ABS, ABS_X, 0)); p.events.push_back(event(EV_KEY, BTN_SOUTH, 0));
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    int polls = 0;
    do {
        const int before = sys.reads;
        CHECK(r.poll(1, out.data()) == 1);
        CHECK(out[0] == 0 && out[3] == 0);
        CHECK(sys.reads - before <= MaxEventsPerPoll);
        CHECK(++polls <= 5);
    } while (!p.events.empty());
    CHECK(polls > 1 && sys.snapshots == 1 && out[7] == 1);
    // Events after the verified snapshot still follow normal report boundaries.
    p.events = {event(EV_ABS, ABS_X, 32767), event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0)};
    r.poll(1, out.data()); CHECK(out[0] == 0x1000 && out[3] == 32767);
}
void droppedSnapshotArrivals() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    p.afterSnapshot = [&](FakeSystem::Pad& pad) {
        if (sys.snapshots != 1) return;
        pad.state.keys.set(BTN_EAST); pad.state.axes[ABS_X] = -32768;
        pad.events = {event(EV_ABS, ABS_X, 32767), event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0)};
        for (int i = 0; i < MaxEventsPerPoll; ++i) pad.events.push_back(event(EV_SYN, SYN_REPORT, 0));
        pad.events.push_back(event(EV_ABS, ABS_X, -32768));
        pad.events.push_back(event(EV_KEY, BTN_SOUTH, 0));
        pad.events.push_back(event(EV_KEY, BTN_EAST, 1));
        pad.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    };
    int before = sys.reads; r.poll(1, out.data());
    CHECK(sys.reads - before == MaxEventsPerPoll && out[0] == 0 && out[3] == 0 && out[7] == 0);
    before = sys.reads; r.poll(1, out.data());
    CHECK(sys.reads - before <= MaxEventsPerPoll && sys.snapshots == 2);
    CHECK(out[0] == 0x2000 && out[3] == -32767 && out[7] == 1);
}
void droppedPartialReportAndDisconnect() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    p.afterSnapshot = [&](FakeSystem::Pad& pad) {
        if (sys.snapshots != 1) return;
        pad.state.axes[ABS_X] = 32767;
        pad.events.push_back(event(EV_ABS, ABS_X, 32767));
    };
    r.poll(1, out.data()); CHECK(sys.snapshots == 1 && out[3] == 0);
    r.poll(1, out.data()); CHECK(sys.snapshots == 1 && out[3] == 0);
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    r.poll(1, out.data()); CHECK(sys.snapshots == 2 && out[3] == 32767);
    p.events = {event(EV_SYN, SYN_DROPPED, 0)}; r.poll(1, out.data());
    p.failed = true;
    CHECK(r.poll(1, out.data()) == 0 && out[3] == 0 && sys.handles.empty() && sys.closes == 1);
}
void droppedContinuousArrivalsBounded() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    p.afterSnapshot = [](FakeSystem::Pad& pad) {
        pad.events.push_back(event(EV_ABS, ABS_X, 32767));
        pad.events.push_back(event(EV_KEY, BTN_SOUTH, 1));
        pad.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    };
    for (int poll = 0; poll < 4; ++poll) {
        const int reads = sys.reads, snapshots = sys.snapshots;
        CHECK(r.poll(1, out.data()) == 1);
        CHECK(sys.reads - reads == MaxEventsPerPoll && sys.snapshots - snapshots <= MaxEventsPerPoll);
        CHECK(out[0] == 0 && out[3] == 0 && out[7] == 0);
    }
    p.afterSnapshot = {}; p.state.keys.set(BTN_EAST); p.state.axes[ABS_X] = -32768;
    r.poll(1, out.data()); CHECK(out[0] == 0x2000 && out[3] == -32767 && out[7] == 1);
}
void droppedRollsBackUnpublishedReports() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data());
    p.events = {event(EV_ABS, ABS_X, 32767), event(EV_KEY, BTN_SOUTH, 1), event(EV_SYN, SYN_REPORT, 0),
        event(EV_SYN, SYN_DROPPED, 0)};
    r.poll(1, out.data()); CHECK(out[0] == 0 && out[3] == 0 && out[7] == 0);
    // A repeated overflow restarts the report barrier.
    p.events = {event(EV_SYN, SYN_REPORT, 0), event(EV_SYN, SYN_DROPPED, 0)};
    r.poll(1, out.data()); CHECK(sys.snapshots == 0 && out[3] == 0);
    p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
    p.state.keys.set(BTN_EAST); p.state.axes[ABS_X] = -32768;
    r.poll(1, out.data()); CHECK(out[0] == 0x2000 && out[3] == -32767);
    // Retain the last published state, rather than inventing a release during resync.
    p.events.push_back(event(EV_SYN, SYN_DROPPED, 0));
    r.poll(1, out.data()); CHECK(out[0] == 0x2000 && out[3] == -32767);
}
void droppedSnapshotBudgetBoundary() {
    for (bool arrivals : {false, true}) {
        FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
        r.poll(255, out.data()); p.state.keys.set(BTN_EAST); p.state.axes[ABS_X] = -32768;
        p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
        for (int i = 0; i < MaxEventsPerPoll - 3; ++i) p.events.push_back(event(EV_SYN, SYN_REPORT, 0));
        int before = sys.reads; r.poll(1, out.data());
        CHECK(sys.reads - before == MaxEventsPerPoll && sys.snapshots == 1);
        CHECK(out[0] == 0 && out[3] == 0 && out[7] == 0); // Candidate is not published without verification.
        if (arrivals) {
            p.state = rest(p.caps);
            p.events = {event(EV_ABS, ABS_X, 0), event(EV_KEY, BTN_EAST, 0), event(EV_SYN, SYN_REPORT, 0)};
        }
        r.poll(1, out.data());
        CHECK(out[0] == (arrivals ? 0 : 0x2000) && out[3] == (arrivals ? 0 : -32767));
        CHECK(sys.snapshots == (arrivals ? 2 : 1) && out[7] == 1);
    }
}
void droppedInterruptedReadIsNotEmpty() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data()); p.state.keys.set(BTN_EAST); p.state.axes[ABS_X] = -32768;
    p.events = {event(EV_SYN, SYN_DROPPED, 0), event(EV_SYN, SYN_REPORT, 0)};
    p.afterSnapshot = [&](FakeSystem::Pad& pad) {
        if (sys.snapshots == 1) pad.emptyInterruptions = MaxEventsPerPoll;
    };
    const int before = sys.reads; r.poll(1, out.data());
    CHECK(sys.reads - before == MaxEventsPerPoll && sys.snapshots == 1);
    CHECK(out[0] == 0 && out[3] == 0 && out[7] == 0);
    r.poll(1, out.data()); CHECK(out[0] == 0x2000 && out[3] == -32767 && out[7] == 1);
}
void boundedDrain() {
    FakeSystem sys; auto& p = sys.add("event0"); Reader r(sys); std::array<int, 64> out{};
    r.poll(255, out.data()); sys.reads = 0;
    for (int i = 0; i < 1000; ++i) p.events.push_back(event(EV_ABS, ABS_X, i));
    r.poll(1, out.data()); CHECK(sys.reads == MaxEventsPerPoll && p.events.size() == 1000 - MaxEventsPerPoll);
    CHECK(out[3] == 0); // No partial report published.
}
void stableSlotsAndIdenticalModels() {
    FakeSystem sys; sys.add("event0").state.keys.set(BTN_SOUTH); sys.add("event1").state.keys.set(BTN_EAST);
    Reader r(sys); std::array<int, 64> out{};
    CHECK(r.poll(255, out.data()) == 3 && out[0] == 0x1000 && out[8] == 0x2000);
    std::reverse(sys.nodes.begin(), sys.nodes.end());
    CHECK(r.poll(255, out.data()) == 3 && out[0] == 0x1000 && out[8] == 0x2000 && sys.opens == 2);
    sys.nodes.push_back(sys.nodes.front()); r.poll(255, out.data()); CHECK(sys.opens == 2);
}
void disconnectAndReuse() {
    FakeSystem sys; auto& p = sys.add("event0"); p.state.keys.set(BTN_SOUTH); p.state.axes[ABS_X] = 32767;
    Reader r(sys); std::array<int, 64> out{}; r.poll(255, out.data());
    p.failed = true; CHECK(r.poll(1, out.data()) == 0 && out[0] == 0 && out[3] == 0 && sys.closes == 1);
    p.failed = false; p.state = rest(p.caps);
    CHECK(r.poll(255, out.data()) == 1 && out[0] == 0 && sys.opens == 2);
}
void replacedNodeHasDisconnectBarrier() {
    FakeSystem sys; sys.add("event0"); Reader r(sys); std::array<int, 64> out{}; r.poll(255, out.data());
    sys.nodes[0].inode = 2;
    const int mask = r.poll(255, out.data()); CHECK((mask & 1) == 0 && sys.closes == 1);
    // Replacement may occupy another free slot immediately, but never the retired slot.
    CHECK(mask == 2); CHECK(r.poll(255, out.data()) == 2);
}
void unplugFromDirectory() {
    FakeSystem sys; sys.add("event0"); Reader r(sys); std::array<int, 64> out{}; r.poll(255, out.data());
    sys.nodes.clear(); CHECK(r.poll(255, out.data()) == 0 && sys.handles.empty());
}
void slotLimit() {
    FakeSystem sys; for (int i = 0; i < 10; ++i) sys.add("event" + std::to_string(i));
    Reader r(sys); std::array<int, 64> out{};
    CHECK(r.poll(255, out.data()) == 255 && sys.handles.size() == 8);
    sys.nodes.erase(sys.nodes.begin());
    CHECK(r.poll(255, out.data()) == 254); // Full capacity: retired slot stays clear for one poll.
    CHECK(r.poll(255, out.data()) == 255 && sys.handles.size() == 8);
}
void permissionAndDirectoryRecovery() {
    FakeSystem sys; auto& p = sys.add("event0"); p.denied = true;
    Reader r(sys); std::array<int, 64> out{}; CHECK(r.poll(255, out.data()) == 0);
    p.denied = false; CHECK(r.poll(255, out.data()) == 1);
    sys.directoryFailed = true; CHECK(r.poll(255, out.data()) == 1);
    sys.directoryFailed = false; CHECK(r.poll(255, out.data()) == 1);
}
void discoveryCadenceAndCleanup() {
    FakeSystem sys; sys.add("event0"); std::array<int, 64> out{};
    for (int i = 0; i < 20; ++i) {
        Reader r(sys); CHECK(r.poll(255, out.data()) == 1);
        const int scans = sys.enumerations;
        for (int j = 0; j < 10; ++j) r.poll(1, out.data());
        CHECK(sys.enumerations == scans);
    }
    CHECK(sys.opens == 20 && sys.closes == 20 && sys.handles.empty());
}
void rejectUnrelatedDevices() {
    FakeSystem sys; sys.add("sensor").caps.accelerometer = true; sys.add("pad");
    Reader r(sys); std::array<int, 64> out{};
    CHECK(r.poll(255, out.data()) == 1 && sys.closes == 1 && sys.handles.size() == 1);
}
void fullMaskDoesNotFloodDiscovery() {
    FakeSystem sys; sys.clockStep = 0;
    for (int i = 0; i < 8; ++i) sys.add("event" + std::to_string(i));
    Reader r(sys); std::array<int, 64> out{};
    for (int i = 0; i < 100; ++i) CHECK(r.poll(255, out.data()) == 255);
    CHECK(sys.enumerations == 1);
    sys.time = DiscoveryIntervalMs;
    r.poll(255, out.data()); CHECK(sys.enumerations == 2);
}

int main() {
    const std::pair<const char*, std::function<void()>> tests[] = {
        {"qualification", qualification}, {"buttons and both dpad forms", buttonsAndHats},
        {"stick normalization", stickNormalization}, {"trigger normalization", triggerNormalization},
        {"alternate triggers", alternateTriggers}, {"digital and ambiguous triggers", digitalAndAmbiguousTriggers},
        {"hybrid analog triggers authoritative", hybridAnalogTriggersWin},
        {"independent analog and digital triggers", independentAnalogTriggers},
        {"independent alternate trigger sources", independentAlternateTriggers},
        {"Xbox PlayStation generic event fixtures", eventFixtures}, {"report boundary", reportBoundary},
        {"SYN_DROPPED recovery", droppedRecovery}, {"bounded drain", boundedDrain},
        {"SYN_DROPPED backlog across polls", droppedBacklogAcrossPolls},
        {"SYN_DROPPED arrivals during snapshot", droppedSnapshotArrivals},
        {"SYN_DROPPED partial report and disconnect", droppedPartialReportAndDisconnect},
        {"SYN_DROPPED continuous arrivals bounded", droppedContinuousArrivalsBounded},
        {"SYN_DROPPED unpublished reports and repeated overflow", droppedRollsBackUnpublishedReports},
        {"SYN_DROPPED snapshot at poll budget boundary", droppedSnapshotBudgetBoundary},
        {"SYN_DROPPED interrupted read is not EAGAIN", droppedInterruptedReadIsNotEmpty},
        {"stable slots and identical models", stableSlotsAndIdenticalModels}, {"disconnect and reuse", disconnectAndReuse},
        {"replacement barrier", replacedNodeHasDisconnectBarrier}, {"directory removal", unplugFromDirectory},
        {"eight slot limit", slotLimit}, {"permission recovery", permissionAndDirectoryRecovery},
        {"discovery cadence and cleanup", discoveryCadenceAndCleanup}, {"unrelated nodes", rejectUnrelatedDevices},
        {"full mask scan limit", fullMaskDoesNotFloodDiscovery},
    };
    int failed = 0;
    for (const auto& [name, test] : tests) {
        try { test(); std::cout << "PASS " << name << '\n'; }
        catch (const std::exception& e) { ++failed; std::cerr << "FAIL " << name << ": " << e.what() << '\n'; }
    }
    std::cout << std::size(tests) << " tests, " << failed << " failures\n";
    return failed ? 1 : 0;
}
