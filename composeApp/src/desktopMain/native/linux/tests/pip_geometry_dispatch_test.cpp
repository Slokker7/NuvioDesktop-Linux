#include "pip_geometry_dispatch.h"
#include <cstdlib>
#include <iostream>
#include <thread>
#include <deque>

int main() {
    unsigned checks = 0;
    auto check = [&](bool ok) {
        if (!ok) { std::cerr << "Failed check " << checks + 1 << '\n'; std::exit(1); }
        ++checks;
    };
    using namespace std::chrono_literals;
    unsigned applies = 0;
    auto current = [] { return true; };
    auto apply = [&](auto valid) { if (valid()) ++applies; return 42; };
    // Successful dispatch uses the exact production helper, without GTK/X11.
    std::thread worker;
    auto threaded = [&](std::function<void()> action) { worker = std::thread(std::move(action)); };
    auto result = dispatchPiPGeometry<int>(threaded, current, apply, 1s);
    worker.join();
    check(result == 42 && applies == 1);

    std::function<void()> queued;
    auto queue = [&](std::function<void()> action) { queued = std::move(action); };
    result = dispatchPiPGeometry<int>(queue, current, apply, 0ms);
    check(!result);
    queued(); // Expired before start: no late mutation.
    check(applies == 1);

    std::atomic<bool> session{true};
    auto stillCurrent = [&] { return session.load(); };
    result = dispatchPiPGeometry<int>([&](auto action) {
        session = false; action(); // Exit/new generation before the queued action starts.
    }, stillCurrent, apply, 1s);
    check(!result && applies == 1);

    session = true;
    std::atomic<bool> began{false};
    std::promise<void> release;
    auto released = release.get_future().share();
    result = dispatchPiPGeometry<int>(threaded, stillCurrent, [&](auto valid) {
        began = true;
        released.wait(); // Simulated stalled native read; caller's deadline must still work.
        if (valid()) ++applies;
        return 99;
    }, 100ms);
    check(!result);
    release.set_value();
    worker.join();
    check(began.load());
    check(applies == 1); // Deadline passed while running, guard before mutation rejects it.

    session = true;
    result = dispatchPiPGeometry<int>(threaded, stillCurrent, [&](auto valid) {
        session = false; // Stale generation during a read, before apply.
        if (valid()) ++applies;
        return 99;
    }, 1s);
    worker.join();
    check(!result && applies == 1);
    unsigned acquisitions = 0, releases = 0;
    auto acquire = [&] { ++acquisitions; return uint64_t{77}; };
    auto undo = [&](uint64_t token) { check(token == 77); ++releases; };
    // Synchronous fake FIFO: successful production acquisition retains its token.
    check(acquirePiPChrome([](auto action) { action(); }, acquire, undo, 1s) == 77);
    check(acquisitions == 1 && releases == 0);
    std::deque<std::function<void()>> fifo;
    auto post = [&](std::function<void()> action) { fifo.push_back(std::move(action)); };
    check(acquirePiPChrome(post, acquire, undo, 0ms) == 0);
    while (!fifo.empty()) { auto action = std::move(fifo.front()); fifo.pop_front(); action(); }
    check(acquisitions == 1 && releases == 0); // Expired before start: no mutation.
    // Finish acquisition after its deadline on the worker. Cleanup is then queued
    // behind that action, never races token assignment, and runs exactly once.
    std::promise<void> unblock;
    auto unblocked = unblock.get_future().share();
    unsigned posts = 0;
    auto delayed = [&](std::function<void()> action) {
        if (++posts == 1) worker = std::thread(std::move(action));
        else fifo.push_back(std::move(action));
    };
    check(acquirePiPChrome(delayed, [&] { unblocked.wait(); return acquire(); }, undo, 100ms) == 0);
    unblock.set_value(); worker.join();
    while (!fifo.empty()) { auto action = std::move(fifo.front()); fifo.pop_front(); action(); }
    check(acquisitions == 2 && releases == 1);
    std::cout << "PiP geometry dispatch: " << checks << " checks passed\n";
}
