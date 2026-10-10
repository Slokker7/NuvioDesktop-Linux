// Headless source-route guards complement the native policy and fake-window tests.
const fs = require('node:fs'), path = require('node:path'), assert = require('node:assert/strict');
const read = f => fs.readFileSync(path.join(__dirname, '..', f), 'utf8');
const cpp = read('controls_overlay.cpp');
const body = (start, end = null) => {
  const first = cpp.indexOf(start);
  assert(first >= 0, `Missing production path: ${start}`);
  const last = end === null ? cpp.length : cpp.indexOf(end, first + start.length);
  assert(last > first, `Missing production boundary after ${start}: ${end}`);
  return cpp.slice(first, last);
};
assert(body('    bool layout(', '    void syncPlayback()').indexOf('!pipHudVisibility.normalWorkAllowed()') < body('    bool layout(', '    void syncPlayback()').indexOf('applyControlsZoom()'));
assert(body('    void deliver()', '    void scheduleDelivery()').indexOf('!pipHudVisibility.normalWorkAllowed()') < body('    void deliver()', '    void scheduleDelivery()').indexOf('pending.swap(scripts)'));
assert(body('    void syncPlayback()', '    void create(').includes('!pipHudVisibility.normalWorkAllowed()'));
const focus = body('void LinuxControlsOverlay::setWindowFocused');
assert(focus.indexOf('pipHudVisibility.suppressed()') < focus.indexOf('layout(true)'));
const suppression = body('bool LinuxControlsOverlay::setPiPHudSuppressed', 'bool LinuxControlsOverlay::canvasGeometry');
assert(!/destroy\(|load_uri|gtk_window_new|queue_draw|set_size_request/.test(suppression));
assert(suppression.includes('suppressed() == suppressed'));
assert(suppression.includes('state->deliver()') && suppression.includes('state->syncPlayback()'));
assert(suppression.indexOf('overlay == HudGeometry') < suppression.indexOf('gtk_widget_show_all'));
assert(!suppression.includes('state->latestJson ='));
assert(body('void LinuxControlsOverlay::updateControls', 'void LinuxControlsOverlay::runJavaScript').includes('state_->latestJson = json'));
assert(body('void LinuxControlsOverlay::runJavaScript', 'void LinuxControlsOverlay::setCursorHidden').includes('state_->pipHudSuppressed'));
console.log('HUD suppression routes: periodic/focus/delivery blocked, latest JSON retained, transient scripts dropped, aligned reveal and no recreation verified');
