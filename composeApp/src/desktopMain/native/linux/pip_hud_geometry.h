#pragma once
#include <algorithm>
#include <cmath>

struct HudGeometry {
    int x = 0, y = 0, width = 0, height = 0;
    bool operator==(const HudGeometry &other) const {
        return x == other.x && y == other.y && width == other.width && height == other.height;
    }
};

inline HudGeometry logicalHudGeometry(const HudGeometry &physical, int scale) {
    scale = std::max(1, scale);
    return HudGeometry{static_cast<int>(std::floor(static_cast<double>(physical.x) / scale)),
        static_cast<int>(std::floor(static_cast<double>(physical.y) / scale)),
        std::max(1, (physical.width + scale - 1) / scale),
        std::max(1, (physical.height + scale - 1) / scale)};
}

enum class PiPHudRequest { Failed = 0, Aligned = 1, Applied = 2, Stale = 3 };

// Reads actual rectangles only. Used by the JNI sampler; cannot issue geometry operations.
template<typename ReadCanvas, typename ReadOverlay>
bool readPiPHudGeometry(ReadCanvas readCanvas, ReadOverlay readOverlay, HudGeometry &canvas, HudGeometry &overlay) {
    return readCanvas(canvas) && readOverlay(overlay);
}

template<typename ReadCanvas, typename ReadOverlay>
bool observePiPHudGeometry(const HudGeometry &expected, ReadCanvas readCanvas, ReadOverlay readOverlay) {
    HudGeometry canvas, overlay;
    return readPiPHudGeometry(readCanvas, readOverlay, canvas, overlay) && canvas == expected && canvas == overlay;
}

// Issuing a request is NOT settlement. Kotlin observes on later EDT turns.
template<typename ReadCanvas, typename Apply, typename ReadOverlay, typename Current>
PiPHudRequest requestPiPHudGeometry(const HudGeometry &expected, ReadCanvas readCanvas,
        Apply apply, ReadOverlay readOverlay, Current current) {
    HudGeometry canvas, overlay;
    if (!current() || !readCanvas(canvas)) return PiPHudRequest::Failed;
    if (!(canvas == expected)) return PiPHudRequest::Stale;
    if (!readOverlay(overlay) || !current()) return PiPHudRequest::Failed;
    if (canvas == overlay) return PiPHudRequest::Aligned;
    if (apply()) return PiPHudRequest::Applied;
    if (current() && readCanvas(canvas) && !(canvas == expected)) return PiPHudRequest::Stale;
    return PiPHudRequest::Failed;
}
