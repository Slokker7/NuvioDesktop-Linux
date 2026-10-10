#include "pip_hud_geometry.h"
#include <cstdlib>
#include <iostream>

int main() {
    unsigned checks = 0;
    auto check = [&](bool ok) {
        if (!ok) { std::cerr << "Failed check " << checks + 1 << '\n'; std::exit(1); }
        ++checks;
    };
    const HudGeometry expected{1420, 790, 480, 270}, stale{320, 590, 1280, 376};
    HudGeometry canvas = expected, overlay = expected;
    unsigned applies = 0;
    bool current = true;
    auto readCanvas = [&](HudGeometry &out) { out = canvas; return true; };
    auto readOverlay = [&](HudGeometry &out) { out = overlay; return true; };
    auto apply = [&] { ++applies; overlay = canvas; return true; };
    auto valid = [&] { return current; };
    for (int i = 0; i < 100; ++i)
        check(requestPiPHudGeometry(expected, readCanvas, apply, readOverlay, valid) == PiPHudRequest::Aligned);
    check(applies == 0); // Exact regression: 100 aligned requests, ZERO apply calls.
    overlay = stale;
    check(requestPiPHudGeometry(expected, readCanvas, apply, readOverlay, valid) == PiPHudRequest::Applied);
    check(applies == 1);
    check(observePiPHudGeometry(expected, readCanvas, readOverlay));
    overlay = stale; // Simulate delayed allocation undoing the initial request.
    for (int i = 0; i < 100; ++i) check(!observePiPHudGeometry(expected, readCanvas, readOverlay));
    check(applies == 1); // Observation never retries mutation.
    check(requestPiPHudGeometry(expected, readCanvas, [] { return true; }, readOverlay, valid) == PiPHudRequest::Applied);
    check(!observePiPHudGeometry(expected, readCanvas, readOverlay)); // APPLIED does not mean aligned.
    canvas = stale;
    check(requestPiPHudGeometry(expected, readCanvas, apply, readOverlay, valid) == PiPHudRequest::Stale);
    check(applies == 1);
    canvas = expected;
    check(requestPiPHudGeometry(expected, readCanvas, [] { return false; }, readOverlay, valid) == PiPHudRequest::Failed);
    check(requestPiPHudGeometry(expected, [](HudGeometry &) { return false; }, apply, readOverlay, valid) == PiPHudRequest::Failed);
    check(requestPiPHudGeometry(expected, readCanvas, apply, [](HudGeometry &) { return false; }, valid) == PiPHudRequest::Failed);
    check(requestPiPHudGeometry(expected, readCanvas, apply, [&](HudGeometry &out) {
        out = overlay; current = false; return true;
    }, valid) == PiPHudRequest::Failed);
    check(applies == 1); // Cancelled during readback, before apply.
    current = true;
    for (int scale : {1, 2}) {
        for (auto root : {expected, HudGeometry{-1919, -1079, 481, 271}}) {
            canvas = root; overlay = stale;
            check(requestPiPHudGeometry(root, readCanvas, apply, readOverlay, valid) == PiPHudRequest::Applied);
            check(observePiPHudGeometry(root, readCanvas, readOverlay));
            const auto logical = logicalHudGeometry(root, scale);
            check(logical.x * scale <= root.x && root.x - logical.x * scale < scale);
            check(logical.y * scale <= root.y && root.y - logical.y * scale < scale);
            check(logical.width * scale >= root.width && logical.width * scale - root.width < scale);
            check(logical.height * scale >= root.height && logical.height * scale - root.height < scale);
        }
    }
    check(!observePiPHudGeometry(expected, [](HudGeometry &) { return false; }, readOverlay));
    canvas = expected;
    check(!observePiPHudGeometry(expected, readCanvas, [](HudGeometry &) { return false; }));
    std::cout << "PiP HUD geometry: " << checks << " checks passed\n";
}
