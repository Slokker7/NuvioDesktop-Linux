#include "window_chrome.h"
#include <cstdlib>
#include <iostream>
#include <map>

using namespace nuvio::chrome;
namespace {
void require(bool value) { if (!value) { std::cerr << "FAILED\n"; std::abort(); } }
struct Fixture final : Backend {
    struct Window { bool managed; Xid parent; Hints hints; };
    std::map<Xid, Window> windows{{1, {true, 0, {}}}, {2, {false, 1, {}}}, {3, {false, 2, {}}}};
    int queries = 0, writes = 0;
    bool failRead = false, failWrite = false;
    bool ancestry(Xid window, bool &managed, Xid &parent) override {
        ++queries;
        auto found = windows.find(window);
        if (found == windows.end()) return false;
        managed = found->second.managed; parent = found->second.parent; return true;
    }
    bool read(Xid window, Hints &hints) override {
        if (failRead || !windows.count(window)) return false;
        hints = windows.at(window).hints; return true;
    }
    bool write(Xid window, const Hints &hints) override {
        if (failWrite || !windows.count(window)) return false;
        ++writes; windows.at(window).hints = hints; return true;
    }
};
}
int main() {
    int groups = 0;
    { Fixture f; Sessions s; require(s.resolve(f, 3) == 1); auto t = s.begin(f, 3);
      require(t && f.windows[1].hints == Hints{true, {2, 0, 0, 0, 0}});
      require(!f.windows[2].hints.present && !f.windows[3].hints.present);
      require(s.end(f, t, 3) && !f.windows[1].hints.present); ++groups; }
    { Fixture f; Sessions s; Hints original{true, {31, 17, 23, 41, 99, 7}};
      f.windows[1].hints = original; auto t = s.begin(f, 3);
      require(f.windows[1].hints.words == std::vector<unsigned long>({31, 17, 0, 41, 99, 7}));
      require(s.end(f, t, 3) && f.windows[1].hints == original); ++groups; }
    { Fixture f; Sessions s; for (int i = 0; i < 20; ++i) {
        auto t = s.begin(f, 3); auto writes = f.writes;
        require(s.begin(f, 2) == t && f.writes == writes);
        require(s.end(f, t, 3)); writes = f.writes;
        require(s.end(f, t, 3) && f.writes == writes);
      } ++groups; }
    { Fixture f; Sessions s; require(!s.begin(f, 0) && !s.begin(f, 999));
      f.windows[1].managed = false; require(!s.begin(f, 3) && !f.writes); ++groups; }
    { Fixture f; Sessions s; f.windows[1] = {false, 3, {}};
      require(!s.begin(f, 3) && f.queries == 64 && !f.writes); ++groups; }
    { Fixture f; Sessions s; f.windows[1] = {false, 1, {}};
      require(!s.begin(f, 3) && f.queries == 3); ++groups; }
    { Fixture f; Sessions s; f.failRead = true; require(!s.begin(f, 3) && !f.writes);
      f.failRead = false; f.failWrite = true; require(!s.begin(f, 3));
      f.failWrite = false; require(s.begin(f, 3)); ++groups; }
    { Fixture f; Sessions s; f.windows[1].hints = {true, {2, 0}};
      require(!s.begin(f, 3) && !f.writes); ++groups; }
    { Fixture f; Sessions s; f.windows[1].hints = {true, std::vector<unsigned long>(65)};
      require(!s.begin(f, 3) && !f.writes); ++groups; }
    { Fixture f; Sessions s; auto t = s.begin(f, 3); f.windows.erase(1);
      require(!s.end(f, t, 3)); auto queries = f.queries;
      require(s.end(f, t, 0) && f.queries == queries); ++groups; }
    { Fixture f; Sessions s; auto t = s.begin(f, 3);
      f.windows[4] = {true, 0, {}}; f.windows[2].parent = 4;
      require(!s.end(f, t, 3) && f.writes == 1); require(s.end(f, t, 0)); ++groups; }
    { Fixture f; Sessions s; Hints original{true, {3, 17, 1, 0, 0}};
      f.windows[1].hints = original; auto t = s.begin(f, 3);
      f.windows[1].hints.words[1] = 99; f.windows[1].hints.words[0] |= 8;
      require(s.end(f, t, 3)); require(f.windows[1].hints == Hints{true, {11, 99, 1, 0, 0}}); ++groups; }
    { Fixture f; Sessions s; auto t = s.begin(f, 3);
      f.windows[1].hints.words[2] = 123; auto current = f.windows[1].hints;
      require(s.end(f, t, 3) && f.windows[1].hints == current); ++groups; }
    { Fixture f; Sessions s; auto t = s.begin(f, 3); f.failWrite = true;
      require(!s.end(f, t, 3)); f.failWrite = false;
      require(s.end(f, t, 3) && !f.windows[1].hints.present); ++groups; }
    { Fixture f; Sessions s; f.windows[1].hints = {true, {2, 7, 0, 3, 9}};
      auto t = s.begin(f, 3); require(t && !f.writes);
      require(s.end(f, t, 3) && !f.writes); ++groups; }
    std::cout << groups << " compact chrome fixture groups passed\n";
}
