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
its normal software fallback. This default is set before custom options, so an
explicit `hwdec=no` or another supported mode still wins. Linux continues to skip
`@nuvio-` profile metadata; it does not import Windows decoder/profile settings.
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
- Separate audio: `audio-files-append` preserves an entire URL as one entry.
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
- `selectSubtitleTrack`: basic `sid` mapping, including disabling subtitles during
  startup. Track enumeration/UI is still deferred.
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
Detailed HTTP status/log classification, video/HDR metadata and the production
profile/event system are deferred. Generic mpv error messages reach the existing
error callback; this does not promise full upstream stream-recovery semantics.

Shared `extraMpvOptions` are passed through, including unwrapped `@nuvio-user:`
entries; other `@nuvio-` profile metadata is ignored. Invalid optional options and
runtime properties are diagnosed to stderr without aborting playback. Embedding,
autoplay, headers and source-audio options remain authoritative. External mpv
configuration files are disabled for this spike. The Linux surface bypasses the
advanced desktop HDR/RTX/anime/SVP profile pass and SVP startup handshake. Windows
and macOS keep their existing paths.

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

## Deliberately absent

`setMediaSessionMetadata` remains an explicit void no-op: Linux media sessions
are deferred. `forceVideoRedraw` is also a no-op: mpv's X11 VO
owns exposes and resize redraws. These calls do not claim playback success or
supply invented playback state.

Every other unexported `NativePlayerBridge` method remains unsupported and raises
`UnsatisfiedLinkError` if called: track/chapter enumeration, audio track selection, external
subtitle management, video/SVP profiling, stats
scripts, native window chrome/fullscreen/PiP, gamepads, media identity and system
idle/foreground queries. Track discovery is currently caught by the shared
controller and provides no track UI. Optional shortcuts for these deferred
features should not be used during acceptance testing.

No PiP, gamepads, media keys, screensaver inhibition, Linux native fullscreen,
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
