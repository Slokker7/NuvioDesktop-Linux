# Linux native bridge: build/load milestone

This bridge links to libmpv and can be loaded through the existing Kotlin
`NativePlayerBridge`. `JNI_OnLoad` creates and destroys an uninitialized libmpv
handle to verify linkage and allocation. It does not initialize outputs or open media.

The existing `create(...)` JNI signature is preserved, but throws
`UnsupportedOperationException`: it cannot attach playback before Linux video
embedding exists. `dispose(0)` is safe; nonzero handles are unsupported. All other
player methods are unimplemented and raise `UnsatisfiedLinkError` if called.
There are no success-shaped playback stubs or alternative Linux player API.
The application still uses its Linux stub player surface.

## Build from the repository root

Requirements: JDK 17, CMake 3.24+, Ninja, a C++17 compiler, and libmpv headers/library.

```bash
MPV_ROOT="$(brew --prefix mpv)" ./gradlew :composeApp:buildLinuxPlayerBridge --no-daemon
```

`MPV_ROOT` may be any installation prefix containing `include/mpv/client.h`,
`include/mpv/render.h`, and `lib/libmpv.so`. The Gradle property
`-Pnuvio.linux.mpvRoot=/path/to/mpv` takes precedence over the environment variable.
No Homebrew path is hardcoded and pkg-config is not used. Without an explicit root,
CMake searches its normal header/library paths.

Output: `composeApp/build/native/linux/libplayer_bridge.so`. CMake/Ninja build
files are also under that ignored directory. The development binary's RPATH
includes the discovered libmpv directory and `$ORIGIN`; moving/removing that
installation requires rebuilding. This is not a redistributable runtime layout.

## JVM smoke test

```bash
MPV_ROOT="$(brew --prefix mpv)" ./gradlew :composeApp:desktopTest \
  --tests '*LinuxNativePlayerBridgeTest' -Pnuvio.linux.nativeSmokeTest=true --no-daemon
```

This explicitly builds the bridge, loads it through the real Kotlin loader, and
checks that `create(...)` reports unsupported playback through JNI. It opens no
window or media source. Ordinary app runs and tests do not depend on this build.

For direct CMake use, with `JAVA_HOME` pointing to a JDK:

```bash
cmake -S composeApp/src/desktopMain/native/linux -B composeApp/build/native/linux \
  -G Ninja -DCMAKE_BUILD_TYPE=Release -DJAVA_HOME="$JAVA_HOME" \
  -DMPV_ROOT="$(brew --prefix mpv)"
cmake --build composeApp/build/native/linux --parallel
```

The loader searches the existing development output locations only. It logs a
Linux load failure without poisoning Kotlin object initialization; an explicit
`ensureNativeLibraryLoaded()` call reports the failure. Windows/macOS loading,
packaging and player selection are unchanged. Video embedding, playback controls,
HTML overlays, PiP, gamepads, media keys and native Wayland support are deferred.
