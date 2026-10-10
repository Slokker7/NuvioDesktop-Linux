# Linux X11/XWayland embedding spike

The main Linux player now uses the existing `NativePlayerHost` AWT Canvas and
shared `NativePlayerController`. `LinuxAwtViewResolver` checks that the Canvas is
displayable, resolution runs on the EDT, and the toolkit is `sun.awt.X11.XToolkit`.
A small JNI entry point then locks the JAWT drawing surface, reads
`JAWT_X11DrawingSurfaceInfo.drawable`, and releases all JAWT resources. A missing
peer/drawable or non-X11 toolkit produces a player error rather than an uncaught
EDT exception. Native Wayland drawing surfaces are not supported.

The unchanged shared `create(...)` signature receives that XID. libmpv uses
`wid=<XID>`, `vo=gpu-next`, `gpu-api=vulkan`, and `gpu-context=x11vk`. It renders in
its own child window **inside the Canvas**, not a separate top-level player.
No DISPLAY, screen number, or installation path is hardcoded. XWayland must be
available and AWT/libmpv must connect to the same X server through the environment.
libmpv must include gpu-next and the X11/Vulkan GPU context, with a usable Vulkan
driver; no automatic Wayland backend is selected.
The toolkit class check deliberately limits this development spike to OpenJDK's
X11 toolkit; other/native Wayland AWT implementations require a later path.

Vulkan avoids the Homebrew Mesa EGL path that rendered the main player through
llvmpipe on the Bazzite/NVIDIA development system. No automatic renderer fallback
is added: video-output initialization occurs during media loading, and the current
error path does not reliably distinguish renderer failure from source failure.
Retrying there would change player ownership and teardown. Other GPU/runtime
combinations and an explicit compatibility fallback remain follow-up work; an
ordered `x11vk,x11egl` context list alone is not a validated fallback.

With the [private media runtime](../../../../../tools/linux/media-runtime/README.md),
the main player defaults to `hwdec=auto`. mpv chooses a usable backend and retains
its normal software fallback. An explicit `hwdec=no` or another supported mode
can override this default in Replace/Full mode; Add preserves Nuvio's default.
Linux reads `@nuvio-config-mode` but skips other `@nuvio-` profile metadata; it
does not import Windows-only decoder or advanced enhancement settings.
The renderer requirements above remain enforced after custom options. Seek
previews retain their independent `hwdec=no`, `vo=null` configuration.

The private runtime backports upstream mpv's removal of Vulkan/Vulkan-copy from
safe automatic selection; explicit Vulkan remains available. Nuvio still requests
`auto`, with backend safety owned by mpv. Patched-runtime tests on the available
RTX 3050 / driver 615.71.09 selected NVDEC for the local 4K HEVC Main10 fixture;
`auto-copy` selected NVDEC-copy, and explicit Vulkan still initialized.
VAAPI support is compiled, but AMD/Intel hardware remains unvalidated. Decode fallback does not
remove the established Vulkan renderer requirement. The opt-in native suite
checks effective default/override options without requiring hardware decoding,
and its thumbnail probe checks the helper's effective software-only options.

## Implemented contract

- `create`, `dispose`: mpv initialization, source loading, partial-failure cleanup,
  idempotent disposal, and opaque handles protected against commands racing disposal.
- HTTP headers: native mpv string array preserves commas inside individual values.
- Separate audio: `audio-files` is set before initialization as a native one-entry
  array, preserving the entire URL without CLI list parsing.
- Autoplay/pause and resume: initial `pause`, absolute `start` in seconds, or percentage
  `start` when no absolute position is supplied.
- `setPaused`, `seekTo`, `seekBy`, `setSpeed`, `speed`.
- `positionMs`, `durationMs`, `bufferedPositionMs`, `isLoading`, `isEnded`, `isPaused`.
  Values come from mpv properties/events; unknown duration/position/cache is represented
  as zero/current position until mpv makes it available.
- `setVolume`, `volume`, `setMute`, `isMuted`, `setMpvProperty`, `setResizeMode`.
- `setSubtitleDelayMs`, `setSubtitleAssStyleMode`, `applySubtitleStyle`: basic mpv
  property mapping required by the shared controller's startup configuration. This
  is not the full upstream ASS track-sensitive styling system.
- Audio/subtitle track enumeration and selection, and external subtitle management,
  through the existing shared track menus.
- `chaptersJson`, `toggleStatsOverlay`: chapter metadata and mpv diagnostics, as
  described below.
- `updateControls`, `runJavaScript`: the existing controls page runs in WebKitGTK.
- `setCursorHidden`: cursor visibility on the GTK controls overlay, as requested by
  the existing HUD. No global cursor/hotkey integration is added.

## WebKitGTK controls overlay spike

`controls_overlay.cpp` owns a single process-lifetime GTK thread and the default
GLib context. GTK initialization is X11-only and does not change the JVM locale.
Every GTK/WebKit object is created, modified and destroyed on that thread. JNI
updates use idle sources rather than `g_main_context_invoke`, which can execute
inline on a calling thread when the context is not owned.

A realized, undecorated, override-redirect GTK toplevel with an RGBA visual stays
on the X11 root, positioned above the existing Canvas. It is never reparented
into the Canvas: a transparent child can obscure the mpv surface with black
rather than composite over its pixels. A separate ARGB toplevel lets the desktop
compositor blend transparent areas over the video. GTK is app-paintable and clears
to fully transparent; WebKit's background is also transparent. Hardware
acceleration remains disabled for this spike; the main video uses mpv's
`wid`/Vulkan/X11 path independently. GDK frame synchronization stays disabled because an override-redirect
window is not managed by the WM and receives no WM frame-drawn replies.

Only the raw Canvas XID is retained, with no foreign `GdkWindow` wrapper or GDK
finalizer. A 250 ms timer uses trapped `XGetWindowAttributes` and
`XTranslateCoordinates` to resolve the Canvas dimensions and position relative
to its X11 root. Both queries are trapped, including disappearance between them.
The geometry is converted from physical pixels to GDK logical units using the
current scale factor. Native move/resize requests keep the overlay's physical
bounds exact even for dimensions/coordinates not divisible by that scale. Only
the overlay is raised/moved; mpv's window is never changed. An unmapped Canvas
hides the overlay; a vanished Canvas stops the timer and destroys it on GTK.
Mouse clicks focus the WebView; its existing JavaScript owns keyboard messages.
No WM-managed independent desktop window or native Wayland surface is created.

Linux applies `desktopUiScalePercent` live through native WebKit page zoom,
using the same 2x physical baseline as Windows/macOS and the shared -50..+50%
user multiplier. Shared settings reapply the saved scale when the player is
recreated. Bottom control-icon scale remains a separate CSS multiplier.
Shared responsive sizing uses the viewport at the native baseline, so zooming
out cannot enlarge the CSS controls and cancel the requested reduction.

The supplied `controlsPageUrl` loads unchanged; file URLs can read local sibling
assets. The registered `player` script handler uses WebKit's JSC object/property
API, validates the action string and finite numeric value, and handles
`controlsReady` by flushing the latest JSON and queued scripts in order. JSON is
escaped as a JavaScript string before `window.playerControls(JSON.parse(...))`.
The script inbox is bounded to 32 scripts / 1 MiB and reports overflow instead
of silently dropping startup calls. A real mpv snapshot drives the existing
`window.playerUpdate` runtime contract; no track list is invented.

Back/Escape/close and other messages reach the existing Kotlin event sink;
navigation stays in Kotlin. `selectAudioTrack` is forwarded as its logical index;
track discovery remains unavailable. The native `selectSubtitleTrack` message,
which has no Kotlin event handler, applies the existing basic `sid` mapping.
The usual `selectBuiltInSubtitleTrack` message goes to Kotlin unchanged.

Player shutdown first moves the overlay out under the operation lock, then
synchronously disconnects signals, cancels JS evaluations, removes the timer and
destroys the GTK overlay without holding the mpv lock. It then joins the mpv event
thread and releases mpv/JNI resources. The existing Kotlin lifecycle workers do
creation/disposal; GTK never makes an AWT round trip or waits for page loading or
JavaScript completion. Message/snapshot callbacks retain only a weak Player;
queued tasks retain independent overlay state and ignore it once closing starts.
JS completion callbacks retain no Player/overlay pointer. The GTK dispatcher
stays alive for the next stream rather than reinitializing GTK per player.
Each overlay owns an explicit ephemeral WebKit context and releases it on GTK;
the default context's process-exit cleanup would run on the JVM exit thread and
can abort when destroying WebKit timers owned by the GTK thread.

The daemon event thread delivers `fileLoaded`, `playbackRestart`, and
`mpvStartupError:`/`mpvPlaybackError:` on failures. The existing Kotlin event sink
queues them onto Swing and rejects superseded generations. `playbackRestart` is
necessary for the shared engine to publish real snapshots. EOF and buffering state
are queried through properties/events. Disposal stops/wakes/joins the event thread,
releases its global JNI reference, and calls `mpv_terminate_destroy`.
Decoded colour metadata also produces `videoParams`, solely to keep SDR colour
presets off HDR/unknown sources. Detailed HTTP status/log classification and the
advanced HDR/profile event system are deferred. Generic mpv error messages reach the existing
error callback; this does not promise full upstream stream-recovery semantics.

Shared `extraMpvOptions` retain the existing configuration-mode contract. In Off,
the shared controller omits custom entries. Add accepts custom options only when
Nuvio has not already configured that key successfully; Replace lets custom
values override matching preferences. In Full, the shared controller omits its
preferences, and supplied application requirements remain authoritative. Linux
retains `hwdec=auto` as its fallback even in Full, with explicit overrides allowed.
Matching outer single/double quotes are removed from `@nuvio-user:` values, as on
Windows; repeated custom keys use the last value. Other `@nuvio-` metadata is
ignored. Invalid optional options and runtime properties are diagnosed to stderr
without aborting playback. Embedding, autoplay, headers and source-audio options
remain authoritative in every mode. External mpv configuration files remain
disabled; Full does not import a filesystem `mpv.conf`. The Linux surface bypasses the
advanced desktop HDR/RTX/anime/SVP profile pass and SVP startup handshake. Windows
and macOS keep their existing paths.

## Base configuration and picture presets

Nuvio's video profiles are application-defined property bundles, not named mpv
profiles or filesystem `mpv.conf` sections. Linux uses the shared Neutral,
Cinematic, Vivid and Custom colour values on confirmed SDR sources. Unknown/HDR
sources remain neutral, including when the saved HDR setting requests tonemapping:
HDR output controls are not implemented by this base profile. Four equalizer
properties (`contrast`, `brightness`, `saturation`, `gamma`) update live; Custom
reset restores zero offsets without leaving Custom. Replace preserves matching
custom properties during refresh; Full skips the application profile entirely.

Before `mpv_initialize`, ordinary defaults follow the Windows `gpu-next` baseline:
`spline36`/`lanczos`/`mitchell` scaling, antiring, sigmoid/correct downscaling,
fruit/10-bit temporal dithering, debanding, streaming cache, `hr-seek=no` and
`volume-max=200`. Remote sources also receive Windows' ordinary reconnect options
(no retry-on-HTTP-error option). These are **defaults, all custom-overridable**,
not additional integration requirements. Full omits them. macOS retains its own
lighter OpenGL baseline; Windows-only decoder, DXGI low-VRAM detection, HDR, RTX,
SVP and shader policies are not imported.

Creation order is: safe `hwdec=auto` fallback and ordinary defaults; shared source
and settings options (including the buffer preset and curated mpv menu); custom
options according to Off/Add/Replace/Full; existing source title/speed requirements;
then native embedding, playback-start, headers and source-audio requirements.
Only then do initialization and `loadfile` run. After file loading, the Linux base
profile applies colour and runtime buffer limits with the same custom-mode guard.
`config=no`, `gpu-next`, Vulkan/x11vk and the host XID remain enforced as before.
Initial options/custom mode are rebuilt on source creation; changing them requires
reopening the source for the complete configuration to take effect.

Linux buffer presets reuse the Windows limits: Metered 10/10 seconds, Low Data
15/30, Balanced 60/120 and Resilient 180/600 (readahead/cache). Byte limits are
32/8, 64/16, 256/64 and 1024/128 MiB (forward/back). The I/O ring stays at 1 MiB;
only time limits and the 0.25-second startup threshold scale with playback speed.
The shared Metered pause clamp and resume/seek release now use these Linux limits.
Native creation without shared settings uses the Windows Resilient fallback.

The existing profile-scoped settings store remains the sole persistence owner.
Fresh installations default to Neutral/Balanced; existing buffer migration remains
unchanged. Source replacement and Back/reopen rebuild initial options from saved
settings and reapply colour after source classification. The temporary Metered
pause clamp is not saved or carried into a replacement player.

## Seek previews

Seek previews now follow the Windows contract: the first request starts one
in-process, windowless libmpv decoder using the player's source and HTTP headers.
It seeks independently and delivers JPEG data URLs scaled with `scale=256:-2` to
`window.nuvioSeekThumbnailReady(positionMs, dataUrl)`. Initial playback readiness
is consumed before seeking. Only one seek is in flight: its SEEK/restart transition
is retired before seeking to the newest pending request. The callback retains the
original requested millisecond timestamp, including for keyframe seeks.

Slow loads remain pending beyond eight seconds; failed contexts retry only on a
later request. Load/seek/screenshot commands use asynchronous replies. Disposal
invalidates delivery, requests abort/quit, wakes event waiting, and joins the sole
decoder owner before main-player teardown. mpv 0.41 cannot abort screenshot encoding;
its temporary file is retained until writer completion or context destruction.
Initialization, final libmpv destruction and OS file I/O have no universal API time
bound, so measured local shutdown latency is not an absolute shutdown guarantee.
Preview failure leaves the existing timestamp-only HUD; real streaming validation
is still required. The opt-in smoke suite explicitly builds a separate phase-gated
test executable; those hooks are not compiled into the JNI library.

## Chapters and mpv diagnostics

`chaptersJson(Long): String` reads one live mpv `chapter-list` node snapshot and
returns `[{"startTime":0.125,"title":"Chapter title"}]`. Times are floating-point
seconds with double precision, in mpv order. There is no index or selected-state
field, and no separate chapter-selection API: the shared timeline seeks in
milliseconds. Invalid/non-finite/negative times are omitted; missing titles become
empty strings. Demuxer-supplied names are preserved, including mpv's `(unnamed)`
for a Matroska chapter without a display name. Linux trims titles using GLib's
Unicode-aware rules, broadly aligned with macOS. Windows trims a narrower ASCII
set (space, tab, CR, LF), and the shared HUD may apply additional JavaScript-side
trimming. Whitespace normalization is therefore not guaranteed to be byte-for-byte
identical across platforms; the chapter JSON schema and functional behavior remain
unchanged. Titles use the existing JSON escaping helper. Empty/unavailable properties and
missing/closing handles return `[]`. The node is freed before returning, under the
same lifecycle lock as other mpv operations; no cache or polling thread is added.

The shared player queries chapters after source/duration initialization and sends
them in its controls-state JSON, separately from native playback snapshots. The
existing HUD displays named chapter markers and hover/seek-preview titles; it
filters blank titles and sorts by time. It does not provide a chapter menu or
current-chapter field. Source replacement recreates the native player, so queries
cannot retain the old source's chapters. Changes within a single source retain
the existing shared refresh behavior.

`toggleStatsOverlay(Long): void` sends the upstream command
`script-binding stats/display-stats-toggle`. The matching mpv 0.41 `stats.lua` is
embedded in the private libmpv's LuaJIT-enabled build and loaded once per player.
It needs no loose script, installation path, user configuration, or runtime
change. `config=no` and `osc=no` remain enabled; neither disables built-in stats.
The script renders through mpv's OSD, below the transparent WebKit HUD. Existing
MPV diagnostics menu/shortcut wiring toggles it on/off without creating another
script instance. Source replacement resets it to hidden; disposal destroys its
script and overlay. The thumbnail decoder remains independent.

As on Windows/macOS, the JNI toggle has no boolean return. A command failure
(for example, `load-stats-overlay=no`) produces a stderr diagnostic and leaves
playback running. The existing shared UI cannot acknowledge failure through this
void contract. Missing/closed handles are safe no-ops. Linux adds no stats pages
or custom diagnostics panel; page rendering and behavior remain mpv-owned.

The opt-in native suite generates a small MJPEG Matroska with 111 adversarial
chapters, exercises the real JNI/JSON and HUD paths, races queries against close,
and replaces chaptered/unchaptered sources 20 times with stats visible. Stats
tests observe the built-in script's active page bindings and cover absent support.
These fixtures and observers are test-only and require no external encoder.

## Playback sleep/screensaver inhibition

Linux consumes the shared `keepScreenAwake` intent: playing, or loading while
playback is requested, without a playback error. One process-wide owner combines
composition requests into a single session lease; pause, error, player teardown
and application exit release it. Blocking acquisition/release runs on an IO worker,
independent of GTK, mpv and native-window lifetime. Late acquisitions are released
after disposal; repeated intent does not create duplicate leases.

The preferred backend is the XDG desktop Inhibit portal with Idle + Suspend
flags (12), an empty parent-window identifier and a checked asynchronous Response.
If unavailable or refused, the fallback requires both a freedesktop ScreenSaver
cookie and a logind `idle:sleep` block FD; partial acquisition is unwound. Requests
use private D-Bus connections, explicit release and connection/FD cleanup on exit.
Backend loss invalidates ownership and permits one event-driven reconnect per
intent transition. Failure logs a diagnostic and leaves playback usable; there is
no polling, interactive authorization or persistent power-setting change.

`stop-screensaver=no` remains deliberate: this session owner handles inhibition,
avoiding mpv's additional X11 screensaver/DPMS policy. The backend is independent
of the X11/XWayland video surface. Real playback and inhibitor ownership were
validated on GNOME Wayland with the XWayland player; other desktops depend on
their portal or fallback services and have not been physically validated. The
opt-in real-session test in `LinuxPlaybackInhibitorTest` uses GNOME's inhibitor
observer; the remaining lifecycle tests use a fake backend. No hardware suspend
is performed. Nuvio's idle dimming shade and optional shutdown remain separate.

## Deliberately absent

`setMediaSessionMetadata` remains an explicit void no-op: Linux media sessions
are deferred. `forceVideoRedraw` is also a no-op: mpv's X11 VO
owns exposes and resize redraws. These calls do not claim playback success or
supply invented playback state.

Every other unexported `NativePlayerBridge` method remains unsupported and raises
`UnsatisfiedLinkError` if called: video/SVP profiling,
native window chrome/fullscreen/PiP, gamepads, media identity and system
idle/foreground queries. Optional shortcuts for these deferred
features should not be used during acceptance testing.

No PiP, gamepads, media keys, Linux native fullscreen,
native Wayland rendering or packaging is added. Advanced HUD actions may still
reach deferred bridge methods; this is a controls transport/embedding spike.
Physical keyboard focus and transparent compositing over real video need manual
testing under the Bazzite/XWayland compositor, including after resize/DPI changes.

## Build and run from the repository root

Requirements: JDK 17 with JNI/JAWT, CMake 3.24+, Ninja, C++17 compiler, X11 development
headers, libmpv headers/library, pkg-config, GTK3 and WebKitGTK 4.1 development
files (`gtk+-3.0`, `webkit2gtk-4.1`). No pkg-config lookup is used for mpv.

With X11 headers in system paths:

```bash
MPV_ROOT="$(brew --prefix mpv)" ./gradlew :composeApp:buildLinuxPlayerBridge --no-daemon
```

When X11 headers are also in Homebrew, supply both header prefixes using standard CMake discovery (xorgproto may be installed but not linked into the common prefix):

```bash
CMAKE_PREFIX_PATH="$(brew --prefix libx11):$(brew --prefix xorgproto)" MPV_ROOT="$(brew --prefix mpv)" \
  ./gradlew :composeApp:buildLinuxPlayerBridge --no-daemon
./gradlew :composeApp:compileKotlinDesktop --no-daemon
./gradlew :composeApp:run --no-daemon
```

`MPV_ROOT` is accepted by CMake as a cache variable or environment variable. The
Gradle property `-Pnuvio.linux.mpvRoot=/path/to/mpv` overrides the environment.
Without a root, normal header/library discovery is used. `CMAKE_PREFIX_PATH` can
identify any nonstandard dependency prefix; no Homebrew location is in source.

Output: `composeApp/build/native/linux/libplayer_bridge.so`. The existing Linux
loader already searches this development location. CMake's build RUNPATH includes
libmpv/JAWT directories and `$ORIGIN`; this remains a local build, not a portable
runtime distribution. Loader/staging logic and packaging are unchanged. The build
is still opt-in, so a missing bridge cannot block ordinary application launch;
attempting playback reports a player error.

```bash
CMAKE_PREFIX_PATH="$(brew --prefix libx11):$(brew --prefix xorgproto)" MPV_ROOT="$(brew --prefix mpv)" \
  ./gradlew :composeApp:desktopTest --tests '*LinuxNativePlayerBridgeTest' \
  -Pnuvio.linux.nativeSmokeTest=true --no-daemon
```

The opt-in smoke test checks loading, rejected invalid handles/unrealized surfaces,
and (with a display) a temporary non-focusable AWT Canvas with a silent local WAV
and null audio output. It checks events, resume, pause, seek, properties and repeated
disposal without network requests. A separate GUI transport test uses a small
local HTML/JS fixture to check sibling assets, queued scripts/JSON escaping,
message forwarding, resize/movement, repeated disposal, overlay recreation and
Canvas destruction before native disposal. It does not test the full
controls page, mouse/physical keyboard input, alpha compositing or real video.

## Manual acceptance on Bazzite GNOME/Wayland

1. Build the bridge, then launch Nuvio with the commands above in the same session.
2. Open a movie/show and select a stream. Verify moving video stays inside the Nuvio
   player area; check audio and that no separate top-level mpv window appears.
3. Verify the existing HUD is visible over moving video with transparent empty
   regions, not a black/opaque replacement. Test mouse input, pause, seek, Back,
   the close button and Escape while the WebView has focus. Confirm the existing
   Kotlin navigation handles exits. Advanced/deferred HUD actions are not covered.
4. Resize the ordinary window, check stacking and geometry, and repeat with a
   resumed title. Check physical keyboard shortcuts after clicking the HUD.
5. Exit playback and open another stream. Check for crashes or orphaned native windows.
6. Report a black surface, missing audio, focus/keyboard problems, incorrect sizing,
   or teardown crashes, along with the Linux/JAWT/mpv errors in the console/log.

Actual video rendering, GPU/driver compatibility, audio-device selection and teardown
under real stream switching remain manual acceptance items. XWayland embedding is
not native Wayland support; pure Wayland/AWT without an X11 drawable needs a later
rendering approach.

### In-app idle dimming (J07 / P07)

`DesktopScreensaver` delegates Linux installation to `LinuxScreensaver`, which reuses the
upstream owned black `JWindow`, opacity, blank cursor, fade and owner bounds tracking.
Linux uses the POPUP window type: GNOME otherwise constrains the shade to the work
area, leaving part of a fullscreen owner uncovered. Because this bypasses WM stacking,
the shade is only mapped while its owner has AWT focus and unmaps immediately on focus
loss/minimization. Focus return resets app-local idle. The existing Linux HUD focus
adapter also observes an owner-root property: while dimmed it hides the separate
always-raised native HUD, restoring it after fade-out, without changing native renderer
code or keyboard focus. At partial opacity the video remains dimmed but HUD controls
are hidden until dismissal.
The dim-only controller reads the existing settings and mounted `playerActive` flag:
paused/buffering players still use the playback delay; `activeDuringPlayback=false`
suppresses dimming for the entire player lifecycle. Changing settings or closing the
player reevaluates the current idle interval, without starting a new timer.

A separate daemon worker queries input idle once per second, outside AWT and outside
the playback inhibitor. Its read-only capability order is:

1. `org.freedesktop.ScreenSaver.GetSessionIdleTime` (seconds), first at the standard
   object path, then the older `/ScreenSaver` path. This is an optional extension,
   **not guaranteed by the [idle inhibition specification](https://specifications.freedesktop.org/idle-inhibit/latest/)**.
   GNOME here advertises it but returns `NotSupported`.
2. Optional [`org.gnome.Mutter.IdleMonitor.GetIdletime`](https://gitlab.gnome.org/GNOME/mutter/-/blob/main/data/dbus-interfaces/org.gnome.Mutter.IdleMonitor.xml)
   (milliseconds), without checking desktop/distribution names. On GNOME/Wayland this
   also observes input consumed by the native WebKit HUD.
3. XScreenSaver `XScreenSaverQueryInfo` on an explicitly identified **X11 session**,
   if built with the optional Xss headers. `libXss.so.1` is loaded only on demand;
   its absence cannot prevent the player bridge from loading. Never used as a global
   idle clock on Wayland/XWayland.
4. Monotonic app-local AWT mouse, key and wheel activity. Local activity always wins
   over an older session sample; observed session activity survives service failure.

D-Bus calls do not activate services and have a 500 ms reply timeout. A failed source
is released and rediscovered after 60 seconds; samples older than three seconds are
ignored. Closing the installation removes input/window listeners, stops the timer,
disposes the shade (including its fade timer/bounds listener), and queues native
release on the same worker that owns the probe. Missing bridge/services are nonfatal.
No settings, fake input, DPMS, compositor policy or system power commands are used.
Shutdown settings remain stored/displayed as before but have no Linux action.

Wayland does not guarantee a global input/foreground API. The compositor-specific
Mutter service is optional; the Wayland `ext-idle-notify` protocol is not implemented
by this milestone. Logind `IdleHint`/session presence and ScreenSaver `GetActive` are
policy/boolean states, not elapsed input-idle clocks suitable for configurable delays.
On an unsupported Wayland compositor the AWT fallback cannot see input swallowed by
the native HUD. The shade covers only its owner and never requests always-on-top.
The current Linux HUD deliberately retains AWT owner keyboard focus, so app-local
focus suffices for the shade's visibility policy. No Linux global foreground-process
claim is made; the Windows shutdown foreground-process policy is not ported.

Verification: run `:composeApp:desktopTest` filtered to `*LinuxScreensaver*`,
`*ScreensaverSettingsTest`, `*LinuxPlaybackInhibitorTest`, `*LinuxHudUiScaleTest` and
`*LinuxNativePlayerBridgeTest` with Temurin 17. Opt into real JNI tests with
`-Pnuvio.linux.nativeSmokeTest=true` and point `MPV_ROOT` at the existing runtime.
The separate native lifecycle/failure suite is:

```sh
cmake --build composeApp/build/native/linux --target linux_screensaver_idle_probe
python3 composeApp/src/desktopMain/native/linux/tests/screensaver_idle_test.py \
  composeApp/build/native/linux/linux_screensaver_idle_probe
```

It uses a private D-Bus daemon and PyGObject, covering standard/legacy paths, Mutter,
missing/denied/timed-out services, service failure, invalid ranges and repeated
open/query/close. Real compositor stacking, mixed DPI and native HUD input still need
session validation; unit tests alone do not prove those behaviors.
