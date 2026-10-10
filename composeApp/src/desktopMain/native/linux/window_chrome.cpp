#include "window_chrome.h"

namespace nuvio::chrome {
namespace { constexpr unsigned long Decorations = 1UL << 1; }

Xid Sessions::resolve(Backend &backend, Xid source) {
    for (int depth = 0; source && depth < 64; ++depth) {
        bool managed = false;
        Xid parent = 0;
        if (!backend.ancestry(source, managed, parent)) return 0;
        if (managed) return source;
        if (parent == source) return 0;
        source = parent;
    }
    return 0;
}

uint64_t Sessions::begin(Backend &backend, Xid source) {
    Xid window = resolve(backend, source);
    if (!window) return 0;
    for (const auto &[token, saved] : saved_) if (saved.window == window) return token;
    Hints original;
    if (!backend.read(window, original)) return 0;
    if (original.present && (original.words.size() < 5 || original.words.size() > 64)) return 0;
    Hints applied = original;
    applied.present = true;
    if (!original.present) applied.words.assign(5, 0);
    applied.words[0] |= Decorations;
    applied.words[2] = 0;
    if (!(applied == original) && !backend.write(window, applied)) return 0;
    const uint64_t token = next_++;
    saved_.emplace(token, Saved{window, original, applied});
    return token;
}

bool Sessions::end(Backend &backend, uint64_t token, Xid source) {
    auto found = saved_.find(token);
    if (found == saved_.end()) return true;
    // Disposal explicitly abandons state: never make a request against the former XID.
    if (!source) { saved_.erase(found); return true; }
    const auto &saved = found->second;
    if (resolve(backend, source) != saved.window) return false;
    Hints current;
    if (!backend.read(saved.window, current)) return false;
    Hints restored = current;
    if (current == saved.applied) {
        restored = saved.original; // Includes restoring absence by deleting the property.
    } else if (current.present && current.words.size() >= 5 &&
               (current.words[0] & Decorations) && current.words[2] == 0) {
        // Another owner changed unrelated fields. Restore only our decoration fields.
        restored.words[0] = (current.words[0] & ~Decorations) |
            (saved.original.present ? saved.original.words[0] & Decorations : 0);
        restored.words[2] = saved.original.present ? saved.original.words[2] : 0;
    }
    // If decorations themselves changed externally, relinquish ownership without overwriting.
    if (!(restored == current) && !backend.write(saved.window, restored)) return false;
    saved_.erase(found);
    return true;
}
}
