# Private Linux media runtime

This is the shared source/version/feature policy for a project-built FFmpeg and
libmpv runtime. It is development infrastructure, **not completed packaging**.
The existing Linux JNI bridge consumes it through `MPV_ROOT`; no production
player options or JNI APIs change.

A distro libmpv may omit codecs or interop features. Homebrew was useful for
investigation but brings a moving dependency closure, development paths and a
host-selected ABI floor. This build mounts neither Homebrew nor host libraries.

## Build

Supported builder host: Linux x86_64, rootless Podman, Python 3.10+, Bash, curl, jq, GNU coreutils,
findutils, util-linux (`flock`). No host packages are installed by these scripts.
Internet access is needed for verified inputs and the disposable builder image.
The actual source compilation is CPU-only with `--network=none`.

From the repository root:

```sh
tools/linux/media-runtime/build.sh --jobs 4
```

Output: `build/linux-media-runtime/runtime` (a convenience symlink to a
validated generation). Optional `--work-dir DIR` selects dedicated storage
outside this recipe source tree. Every invocation, including `--clean`, creates
fresh extracted-source, build and install trees. Only SHA-verified downloads and
the builder image are cached; mutable component trees/stamps are never reused.
Generated output and downloads must never be added to Git.

The recipe identity includes the full manifest, recipe/test content (excluding
README and Python bytecode caches), architecture and actual builder image ID.
Download caches are SHA-256 addressed and rechecked. A corrupt download is never
executed: it must be replaced by freshly verified bytes or the build fails.
Fresh attempt directories prevent modified installed libraries or extracted
sources from becoming canonical through regenerated inventory. The internal
builder also refuses any existing source/build/install tree.

Export, software tests and two audit passes operate on an unpublished generation.
The audit compares exported config evidence to the actual fresh configuration
headers. A final inventory covers regular files, modes, symlinks and metadata;
a validation marker binds that inventory. The host rechecks it and the recipe/
builder identity before atomically renaming a replacement `runtime` symlink.
Failures, interruption and `--clean` leave the prior generation available. Old
attempts/generations are retained for inspection; remove unneeded ones manually,
never the target of `runtime` or a generation still used by a running process.
Never edit recipes during a build. Keep logs and the builder image for reproduction.

## Controlled ABI target

The AMD64 Ubuntu 22.04 base is pinned by digest. APT inputs come from a fixed
Ubuntu snapshot; Ubuntu repository signatures and package hashes remain checked.
A SHA-pinned Ubuntu CA data package bootstraps TLS without executing its scripts.
Only snapshot expiry checking is disabled. `builder-packages.tsv` records the
complete installed package versions, including transitive build inputs.

GCC 12 supports libplacebo's C++20 needs. The policy rejects requirements above
`GLIBC_2.35` and `GLIBCXX_3.4.30`. It targets a conservative Ubuntu 22.04-era native
media ABI, not every older distribution. Native host interfaces still need the
listed SONAMEs. This does not establish the JVM/Compose/GTK/WebKit whole-app ABI.
Ubuntu 22.04 standard security maintenance ends in May 2027; refresh this builder
before that, and monitor Universe packages separately. Frozen snapshots aid
reproduction and require deliberate security updates.

References: [Ubuntu release lifecycle](https://ubuntu.com/about/release-cycle),
[Ubuntu snapshots](https://snapshot.ubuntu.com/ubuntu/).

## Manifest and feature policy

`manifest.json` is canonical. It records immutable archives and verified SHA-256s,
licenses, classifications, reasons, options and patch lists. There are currently
no patches. FFmpeg's detached signature is verified with a pinned key fingerprint;
other archives are hash-verified official release/tag material. libdisplay-info's
original release archive is obtained from Debian's official source mirror.

The proven core pins are FFmpeg 9.0.2, mpv 0.41.0 and nv-codec-headers 12.1.14.0.
The remaining versions match the investigated stack where practical. glslang
replaces the POC's shaderc compiler to avoid its larger build dependency chain.
The generic Vulkan loader and headers match. libunibreak is private to avoid
incompatible distro SONAMEs; Xpresent is private because it is not installed on
all XWayland desktops; bzip2 is private because Ubuntu/Fedora use different
library SONAMEs; libdisplay-info supplies mpv's full DRM build path.

FFmpeg keeps broad software decoders, demuxers, parsers, network protocols and
filters. libdav1d supplies real AV1 software decoding. OpenSSL supplies HTTPS.
H.264, HEVC/Main10, VP9, AAC, AC-3/E-AC-3, PCM, Matroska, MP4, MPEG-TS and subtitle
parsing are retained. Hardware interfaces include CUDA/NVDEC/CUVID, VAAPI, DRM
and Vulkan Video. Encoder/muxer policy is intentionally small: wrapped frames,
PCM, PNG and MJPEG; null, image and SPDIF output. MJPEG preserves mpv's JPEG screenshot
contract without relying on a distro-specific libjpeg ABI. The SPDIF muxer is
required by the existing `audio-spdif` passthrough setting.

No nonfree/GPL FFmpeg switches, NVENC, nvcc, CUDA LLVM, NPP or CUDA toolkit are
used. Version-3 licensing is enabled for OpenSSL compatibility. The application
uses GPL mpv; this is a license inventory, not legal certification.

mpv enables shared libmpv, the diagnostic CLI, gpu-next/libplacebo, Vulkan,
X11/x11vk/wid, LuaJIT/OSC, libass, ALSA/PulseAudio, CUDA hardware frames/interop,
VAAPI-X11 and VAAPI-DRM/DRM. It exposes direct and copy modes supported by upstream.
Nothing in the build selects a vendor or forces copy-back globally.

## Private and platform boundary

Private: FFmpeg libraries, libmpv, libplacebo, dav1d, libass, libunibreak, LuaJIT,
libdisplay-info, bzip2, the generic Xpresent extension client, and the **generic**
Vulkan loader. glslang is statically
incorporated into libplacebo; its BSD-2/BSD-3/MIT/Apache notices are included.
The Vulkan loader's incorporated cJSON MIT notice is exported separately.

Platform: glibc and C++ runtime, kernel, X11/Wayland, libva/libdrm interfaces,
font stack, OpenSSL/zlib/lcms2, audio interfaces, GTK/GLib/WebKitGTK, and actual
GPU drivers. Exact allowed direct ELF SONAMEs are in the manifest. These are future
package/SDK dependencies; this tree does not make them disappear.

Build-only: Meson, NASM, nv-codec-headers, Vulkan headers, CA bootstrap and the
snapshot toolchain/development packages. No compiler or GPU driver is exported. Free Vulkan/nv-codec header notices are included because generated/inline
interface code is incorporated; these are not proprietary driver licenses.
The pinned hwdata PNP registry is a
builder-provided data input incorporated into libdisplay-info; its bytes are
checked against the upstream archive, and its GPL notices are exported.

The bundled Vulkan loader is open generic dispatch code, **not an ICD**. It lets
newer Vulkan headers and media code coexist with older native hosts. Vendor ICDs,
Mesa drivers and NVIDIA driver libraries remain platform-provided and are located
by normal loader discovery. It must be reconciled with the selected Flatpak SDK
loader when rebuilding there; do not ship duplicate global loaders.

NVIDIA interfaces are dynamically loaded. `libcuda.so.1` and `libnvcuvid.so.1`
are not bundled or mandatory `DT_NEEDED` entries. The pinned headers document
Linux driver floor 530.41.03. Neither CUDA toolkit nor libcudart is required.
A missing/old optional driver must leave software decoding available.

AMD and Intel are equally intended targets through compiled VAAPI/DRM/DMA-BUF
and Vulkan interfaces. Compilation is not physical validation. No scripts assume
NVIDIA exists, GPU 0 is correct, or display GPU equals decode GPU. Hybrid systems
require future actual device/interop tests; mpv/libplacebo retain device selection.

Reference: [nv-codec-headers compatibility](https://github.com/FFmpeg/nv-codec-headers/blob/n12.1.14.0/README).

## Renderer and decoder

Rendering and decoding are separate. Production remains `vo=gpu-next`,
`gpu-api=vulkan`, `gpu-context=x11vk`; **production hwdec is not enabled here**.
A future candidate is upstream `hwdec=auto` with software fallback after more
validation. Software decode works without GPU drivers in the CPU smoke tier.
Displaying the current Vulkan renderer still requires a usable graphics
implementation; decode fallback is not a new renderer fallback.

The existing windowless seek-thumbnail player remains `hwdec=no`, `vo=null`.
Its scaling, JPEG, temporary output and network loading contract must be validated
with the unchanged native tests.

## Runtime structure and linkage

```text
runtime/
  bin/{mpv,ffmpeg,ffprobe}
  include/mpv/{client.h,render.h,...}
  lib/libmpv.so -> libmpv.so.2 -> ...
  lib/<matching FFmpeg and selected private DSOs>
  lib/pkgconfig/<relative-prefix metadata>
  share/licenses/<private components>
  share/nuvio-media-runtime/
    config/{ffmpeg,mpv}.h   # Boolean definitions captured from this build
    validated.json         # Transaction-completion marker, not a signature
    manifest.json
    build-info.json
    builder-packages.tsv
    inventory.json
    compile-features.json
    elf-audit.json
    capabilities.json
```

Real ELF libraries use `DT_RUNPATH=$ORIGIN`; executables use
`DT_RUNPATH=$ORIGIN/../lib`. Every private DSO has its own RUNPATH, so transitive
resolution does not rely on inheritance. No `LD_LIBRARY_PATH`, `LD_PRELOAD`,
absolute development RPATH, glibc or proprietary GPU library is shipped.
The dynamic mpv client `.pc` prefix is relative to `pcfiledir`; static-link SDK
requirements are omitted because static linkage is not exported. Static archives, libtool files,
CMake exports, Meson build metadata and downloaded archives are excluded.

Exact configure/compiler output stays in the external build logs. Distributable
metadata records versions, options, package versions, hashes and sanitized build
information without host/build paths. Source file/debug prefixes are remapped.

## Audit and tests

These Python helpers use only the standard library; build orchestration is Bash.
Readelf/binutils and ldd are required for the ELF audit. Only run it on this
verified, trusted build output (ldd is not a tool for untrusted binaries).

```sh
runtime=$(realpath build/linux-media-runtime/runtime)
python3 tools/linux/media-runtime/scripts/audit-runtime.py "$runtime" > /tmp/nuvio-elf-audit.json
python3 tools/linux/media-runtime/scripts/smoke-test.py "$runtime" --output /tmp/nuvio-cpu-capabilities.json
# Separate tier: requires a real XWayland/X11 display and GPU drivers.
python3 tools/linux/media-runtime/scripts/smoke-test.py "$runtime" --hardware --output /tmp/nuvio-gpu-capabilities.json
```

The build runs the strict audit and CPU suite inside the GPU-free builder.
Synthetic offline fixtures cover H.264, HEVC, Main10, VP9, AV1, High10, AAC,
AC-3 and E-AC-3. Their hashes and generator commands are recorded; running the
suite requires no host encoder, system FFmpeg, network or GPU.

For each video fixture, ffprobe checks the recorded codec/profile/pixel format,
and FFmpeg must decode five frames. mpv uses the image output for this diagnostic
only: exactly five PNG outputs must exist and decode successfully. This rejects
premature one-frame success. High10 repeats the output assertion with `hwdec=auto`
to check software fallback in a CPU-only output path.

Audio decode writes PCM WAV through mpv: channel count, sample width, sample rate,
nonzero data and minimum decoded sample counts are checked. Bounded AC-3/E-AC-3
passthrough tests use null audio output, require actual SPDIF output initialization,
observed playback progress and EOF. No receiver or audio server is required.
The optional displayed GPU tier observes selected hwdec and minimum playback
position through a Lua script, verifies gpu-next output and EOF; this is progress
evidence, **not an exact rendered-frame counter**. It never changes production
player options. All subprocesses have strict timeouts.

The canonical manifest defines a closed executable set, exact private DSO files/
SONAMEs and symlink targets. Audit rejects unknown executable/ELF artifacts,
substituted symlinks, missing files, mode/hash changes, unresolved/unknown direct
dependencies, mandatory proprietary interfaces, bundled C/C++ platform libraries,
invalid RUNPATH, ABI ceiling violations and development-path leakage. Harmless
non-executable license/documentation files are permitted and inventoried.

All exported configuration and capability evidence is inventoried. During the
build, exported definitions are compared with the actual fresh build headers;
standalone audit compares the capture with JSON and independently queries program
identity/version, codec, muxer and hardware-mode availability. Capability metadata must agree.
Audit, marker creation and host verification share a playback-report validator:
the permanent vectors define required fixtures, frame/sample minima and formats.
Every required child must pass, including explicit High10 automatic software
fallback and both SPDIF progress/EOF checks; a contradictory summary is rejected.
Failed marker creation removes any previous candidate marker. Older reports lack
explicit video mode/EOF and PCM format fields and must be regenerated with the
current smoke suite before resealing and revalidation; the media binaries do not
need rebuilding for this report-only change.
No ldd/program execution occurs after structural/integrity checks fail.

The inventory and completion marker detect corruption/substitution, not an
attacker coherently rewriting an entire artifact and its inventory. They are not
signatures or an isolated loader namespace. RUNPATH cannot prevent preemption by
an already-loaded matching SONAME inside the JVM. Packaged-process validation
remains necessary. Only audit trusted project builds.

Two narrow exceptions permit upstream generic temporary-file templates in
libavutil and LuaJIT (`%sXXXXXX` and `lua_XXXXXX` in the system temporary directory).
They are source defaults, not developer paths. Audits record SONAMEs, NEEDED,
RUNPATH, ABI version requirements, resolved closure and per-ELF hashes.

Permanent adversarial checks (run runtime mutations in the builder, which has
patchelf; the orchestration test uses deterministic container fault injection):

```sh
python3 tools/linux/media-runtime/tests/test-runtime.py "$runtime" --output /tmp/runtime-mutations.json
# Optional --without-spdif OLD_RUNTIME exercises the bounded negative AC-3 test.
python3 tools/linux/media-runtime/tests/test-build-state.py "$runtime" build/linux-media-runtime/downloads --output /tmp/build-state.json
```

The orchestration test runs the actual host build script but replaces the
container producer. It tests fresh-workspace allocation, corrupted old state,
changed manifest identity, producer/validation failures, interrupted export and
publication recovery. It complements real fresh builds and interruption tests;
it does not claim to compile dependencies itself.

To test relocation, copy the complete runtime to a different directory and rerun
both commands without loader environment overrides. Preserve internal symlinks.

## Existing bridge development integration

The existing CMake accepts an environment/cache `MPV_ROOT`, requires
`include/mpv/client.h`, `include/mpv/render.h` and `lib/libmpv.so`, and links libmpv
normally. Without MPV_ROOT it falls back to normal path/library discovery. It
sets development BUILD_RPATH to `$ORIGIN` and the selected libmpv directory;
CMake can also add JNI/X11 dependency directories. This development bridge is
not itself the relocatable release artifact built here.

The existing Gradle task passes MPV_ROOT through, uses Release/Ninja and stages
`libplayer_bridge.so` under `composeApp/build/native/linux`. The loader uses
`System.load`; changing MPV_ROOT does not select arbitrary system libmpv at run
time. Native tests build the excluded seek-thumbnail probe explicitly. The test
flag below enables existing build/test dependencies. No loader/API changes are
needed. Use the task-local `--rerun` flag: native output changes can otherwise
leave Gradle reporting a cached test result.

```sh
export JAVA_HOME="$HOME/.local/jdks/temurin-17" # Or your existing Java 17.
export PATH="$JAVA_HOME/bin:$PATH"           # Existing CMake/Ninja must be on PATH.
export MPV_ROOT="$(realpath build/linux-media-runtime/runtime)"
# Set CMAKE_PREFIX_PATH only if existing X11 development discovery needs it.
./gradlew :composeApp:buildLinuxPlayerBridge :composeApp:compileKotlinDesktop \
  --no-daemon --no-configuration-cache
./gradlew :composeApp:desktopTest --rerun --tests '*LinuxNativePlayerBridgeTest' \
  -Pnuvio.linux.nativeSmokeTest=true --no-daemon --no-configuration-cache
```

A missing required *platform interface library* is a deployment dependency issue,
not the absence of an optional vendor decode driver. Future packages must declare
or SDK-provide those interfaces. CPU JNI/HUD tests can use a software X server;
none of that is installed by this build.

## Validation status

`capabilities.json` distinguishes COMPILED evidence from DETECTED/VALIDATED.
The artifact's default report is CPU-only: physical GPU validation is UNTESTED.
Host hardware evidence belongs in separate reports, not copied into every build
as a universal support claim.

AMD: **NOT YET HARDWARE VALIDATED**. Intel: **NOT YET HARDWARE VALIDATED**.
NVIDIA: **VALIDATED on one RTX 3050 6GB / driver 615.71.09 system**, with actual
4K HEVC Main10 playback through both NVDEC and Vulkan Video under XWayland.
Diagnostic `hwdec=auto` selected Vulkan Video; explicit `hwdec=nvdec` also passed.
These are CLI validation runs, not a production hwdec policy or validation of
all NVIDIA profiles/cards.

Correction validation used recipe SHA-256
`e01a4237ba0351d0fed8af31313500a5454b4d1a19e703ed45bb8e90e696fea3`.
A new empty download cache fetched and verified all 18 source archives and the
FFmpeg signature/key, including the corrected Meson tag archive. Two independent
clean builds passed both audits and CPU tests. Their 59 regular files, 31 symlinks,
all modes/hashes/targets, 19 ELF records and complete metadata were identical.

Each of the six video fixtures produced five decodable mpv images; High10's
automatic software fallback repeated that check. AAC, AC-3 and E-AC-3 each
produced 49,152 mono 48-kHz PCM samples. AC-3/E-AC-3 passthrough reached EOF with
at least 0.79 seconds of observed position. The permanent tests rejected all 16
audit mutations, a one-frame wrapper and the older runtime without SPDIF. The
host orchestration fault-injection suite passed all ten cases.

The unchanged bridge/Kotlin build passed with the corrected `MPV_ROOT`; all 20
existing native tests passed with zero skips. This includes subtitle/audio HUD,
shutdown/reopen, 100 fresh thumbnail decoder cycles, 2,041 rapid requests,
supersession/JPEG checks, 90 phase-shutdown cycles, ten pending HTTP cancellations
and failed/delayed-load recovery. No thumbnail or player source was changed.

A real replacement container was killed during export, before inventory creation.
The previous published runtime retained its complete inventory/audit/validation
marker and remained executable. A subsequent `--clean` recovery rebuilt from fresh
trees despite deliberately corrupted old libmpv, libass and mpv source files; its
complete artifact matched both independent clean builds. Fault injection also
confirmed that an invalid generation cannot replace the successful publication.

The corrected-runtime optional GPU tier passed. Its tiny H.264 fixture selected
Vulkan Video; the other tiny GPU-tier fixtures used software fallback. A separate
3840x2160 HEVC Main10 diagnostic on the NVIDIA host above selected Vulkan Video
with `hwdec=auto` and NVDEC with explicit `hwdec=nvdec`; each observed 1.967 seconds
of playback position through gpu-next and reached clean EOF. These remain host
diagnostics, not exact rendered-frame counts or production hwdec enablement.
Hybrid GPUs, other desktops, scaling, older hosts and package sandboxes still
require validation. No public multi-distribution compatibility claim is made.

For a second clean build, use a separate work directory (verified downloads may
be copied), compare file lists, audit fields, inventory, compile capabilities and
ELF SHA-256s. Record any differences; SOURCE_DATE_EPOCH and pinned inputs alone
are not proof of bit-for-bit reproducibility.

## Redistribution, source and maintenance

All private components have source references, archive hashes, licenses, build
options and empty explicit patch lists. `share/licenses` collects bundled
component and incorporated-data notices; `inventory.json` maps exported files to hashes. The combined
FFmpeg/OpenSSL configuration is version-3 LGPL, mpv is GPL and the complete
application's distribution obligations still require release review. No nonfree
FFmpeg build or proprietary driver payload is present. License files and URLs
alone do **not** complete corresponding-source obligations.

Future releases must publish the manifest, these exact recipes, any patches,
build metadata, notices and the required corresponding sources. Preserve source
archives externally for that work. This milestone creates no release/source
archive or legal certification.

Bundling moves security responsibility to Nuvio: monitor FFmpeg, mpv, libplacebo,
codec dependencies, Vulkan loader and builder advisories; schedule tested refreshes,
ship relevant emergency fixes, and preserve the last known-good manifest for
rollback. No monitoring/CI automation is implemented here.

Future DEB/AppImage should consume application-private media libraries without
installing libmpv globally or bundling glibc/drivers. Flatpak should rebuild from
the same source/version/feature policy in its SDK (`/app`) and use graphics driver
extensions; its ELF need not match native builds byte-for-byte. Package-specific
platform dependencies, SDK loader reconciliation and end-user installation are
future work. No DEB/AppImage/Flatpak or release workflow is implemented here.

## Separate existing-player follow-up

`mpv_set_option_string("audio-files-append", ...)` returns option-not-found with
both the existing Homebrew and private libmpv. This correction does not change
that pre-existing separate-audio-URL integration issue or weaken acceptance for
it. Native Wayland, JVM namespace isolation, hybrid/AMD/Intel hardware testing,
package dependency closure and replacement of the builder baseline remain
separate work. No `dlmopen` or production hwdec change is introduced.
