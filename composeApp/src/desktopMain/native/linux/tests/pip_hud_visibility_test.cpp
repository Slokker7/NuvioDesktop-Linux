#include "pip_hud_visibility.h"
#include <cstdlib>
#include <iostream>

int main() {
    auto check = [](bool ok) { if (!ok) { std::cerr << "Visibility check failed\n"; std::exit(1); } };
    PiPHudVisibility state;
    check(state.normalWorkAllowed());
    check(state.setSuppressed(true));
    for (int i = 0; i < 100; ++i) {
        check(!state.setSuppressed(true));
        check(state.suppressed());
        check(!state.normalWorkAllowed()); // periodic layout, focus, delivery and playback
    }
    check(state.setSuppressed(false));
    check(state.normalWorkAllowed());
    check(!state.setSuppressed(false));
    check(state.setSuppressed(true));
    PiPHudVisibility replacement;
    check(replacement.normalWorkAllowed());
    std::cout << "PiP HUD visibility: default, idempotency, suppressed work and fresh instance passed\n";
}
