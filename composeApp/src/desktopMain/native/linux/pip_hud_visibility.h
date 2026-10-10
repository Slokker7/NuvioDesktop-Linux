#pragma once

// GTK-thread-owned explicit suppression wins over focus, periodic layout and delivery.
class PiPHudVisibility {
public:
    bool setSuppressed(bool value) {
        if (suppressed_ == value) return false;
        suppressed_ = value;
        return true;
    }
    bool suppressed() const { return suppressed_; }
    bool normalWorkAllowed() const { return !suppressed_; }
private:
    bool suppressed_ = false;
};
