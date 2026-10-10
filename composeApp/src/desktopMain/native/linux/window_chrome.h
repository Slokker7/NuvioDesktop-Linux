#pragma once

#include <cstdint>
#include <functional>
#include <map>
#include <vector>

namespace nuvio::chrome {
using Xid = unsigned long;
struct Hints {
    bool present = false;
    std::vector<unsigned long> words;
    bool operator==(const Hints &other) const { return present == other.present && words == other.words; }
};

// The production X11 adapter owns error trapping; fixtures exercise this same policy.
struct Backend {
    virtual ~Backend() = default;
    virtual bool ancestry(Xid window, bool &managed, Xid &parent) = 0;
    virtual bool read(Xid window, Hints &hints) = 0;
    virtual bool write(Xid window, const Hints &hints) = 0;
};

class Sessions {
public:
    Xid resolve(Backend &backend, Xid source);
    uint64_t begin(Backend &backend, Xid source);
    bool end(Backend &backend, uint64_t token, Xid source);
private:
    struct Saved { Xid window; Hints original; Hints applied; };
    uint64_t next_ = 1;
    std::map<uint64_t, Saved> saved_;
};
}

// Reuse the overlay's process-lifetime GTK thread and its existing X11 error-handler discipline.
// Never initializes GTK or waits for an AWT callback.
void postOnLinuxGtkThread(std::function<void()> action);
