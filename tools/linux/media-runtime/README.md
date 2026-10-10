# Private Linux media runtime

This is the shared source/version/feature policy for a project-built FFmpeg and
libmpv runtime. It is development infrastructure, **not completed packaging**.
The existing Linux JNI bridge consumes it through `MPV_ROOT`. The runtime build
does not set player options or change JNI APIs.

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
licenses, classifications, reasons, options and patch lists. mpv 0.41.0 has two
upstream backports, described below; all dependency pins are unchanged.
FFmpeg's detached signature is verified with a pinned key fingerprint;
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
`gpu-api=vulkan`, `gpu-context=x11vk`. The Linux main bridge defaults to upstream
`hwdec=auto`, before explicit user options, with mpv's normal software fallback.
mpv owns backend safety selection; Nuvio does not maintain a vendor-specific
decoder list. Software decode works without GPU drivers in the CPU smoke tier.
Displaying the current Vulkan renderer still requires a usable graphics
implementation; decode fallback is not a new renderer fallback.

The existing windowless seek-thumbnail player remains `hwdec=no`, `vo=null`.
Its scaling, JPEG, temporary output and network loading contract must be validated
with the unchanged native tests.

### Upstream safe-selection backport

mpv remains **0.41.0**, with the two whitelist removals from upstream commit
[`d20d108d94e288263a536dfaac1eda995b9a434e`](https://github.com/mpv-player/mpv/commit/d20d108d94e288263a536dfaac1eda995b9a434e).
The explicit patch is `patches/mpv-0.41-disable-vulkan-auto-safe.patch`.
It removes `HWDEC_FLAG_WHITELIST` from `vulkan` and `vulkan-copy`, retaining
`HWDEC_FLAG_AUTO` and all compiled Vulkan Video features. Explicit
`hwdec=vulkan` and `hwdec=vulkan-copy` remain available for expert opt-in.
The Vulkan renderer is unchanged.

Upstream removed these methods from safe automatic selection because of ongoing
stability regressions. Backporting that decision avoids making Vulkan Video an
automatic public decode path; it does not claim Vulkan is globally broken.
`auto`/`auto-safe` exclude both methods, and `auto-copy` excludes Vulkan-copy.
For the relevant methods compiled here, safe candidates are NVDEC, VAAPI,
NVDEC-copy and VAAPI-copy, followed by software fallback. Availability and codec
support still determine the result. AMD/Intel physical hardware remains unvalidated.

The original local HEVC-to-H.264 Vulkan Video crash inside NVIDIA's fence wait
has **no established root cause**. A subsequent forensic campaign completed
637 replacements without reproducing it. This backport adopts upstream policy;
it does not fix or explain that crash. Explicit Vulkan retains that unresolved
risk on the tested stack.

The manifest records upstream provenance, patch SHA-256 and before/after source
hashes. The builder verifies those hashes, applies with zero fuzz, and checks the
resulting source before compilation and during audit. The exported patch and
`applied-patches.json` are inventoried; audit and publication recheck them against
the canonical recipe. `safe-hwdec.json` records the source whitelist and compiled
explicit modes. Changing or removing the patch changes recipe identity or fails
validation. The CPU smoke report also rejects automatic Vulkan candidates.

Candidate evidence comes from mpv 0.41's `[vd] Looking at hwdec ...` event, after
whitelist filtering but before device creation. `hwdec_candidates` retains the
codec-qualified names in log order; `backend` separately records actual decoding
(`no` means software). `Trying hardware decoding via ...` is too late to detect
failed device creation and is not proof of successful decoding. Only decoder
events are parsed; Vulkan renderer messages do not count as decoder candidates.
For `auto-copy`, safe direct candidates can appear before mpv's later copy filter,
but the selected backend must be software or a copy backend. Both Vulkan methods
remain excluded by the safe whitelist. Explicit Vulkan modes are accepted.

The targeted test runs during every build. An optional displayed host diagnostic
uses a local compatible HEVC fixture without making a GPU mandatory in CPU CI:

```sh
python3 tools/linux/media-runtime/tests/test-safe-hwdec.py "$runtime" \
  --hardware-fixture /path/to/local-hevc-main10.mkv --output /tmp/safe-hwdec-gpu.json
```

The default targeted tier runs real H.264/HEVC playback with `auto` and `auto-copy`
in the GPU-free builder, plus 19 parser/policy boundary cases. Its permanent
negative-control option uses a real unpatched mpv 0.41 runtime. Run it in that
same GPU-free builder (with these runtime/source directories mounted):

```sh
python3 tools/linux/media-runtime/tests/test-safe-hwdec.py "$runtime" \
  --source-dir "$patched_source" --unpatched-runtime "$unpatched_runtime" \
  --log-dir /tmp/hwdec-control-logs --output /tmp/safe-hwdec-cpu.json
```

Four positive and four negative playback controls require five decodable frames
and software EOF. Each negative must reject actual Vulkan candidate consideration
and demonstrate failed device creation before any `Trying` event. Omitting the
optional unpatched runtime is explicitly reported as `NOT REQUESTED`; ordinary
CI needs no GPU or second mpv build. Displayed tests run separately.

### Upstream CUDA mapper failure backport

The second patch, `patches/mpv-0.41-cuda-mapper-failure.patch`, is the complete,
unadapted upstream commit
[`89b95243ef1590ae06d17c2e3deeb1d82098a941`](https://github.com/mpv-player/mpv/commit/89b95243ef1590ae06d17c2e3deeb1d82098a941),
including its author/message. It follows the safe-selection patch in manifest
order. Patch and before/after hashes cover all three modified CUDA source files.

Failure of CUDA `ext_init` now makes `mapper_init` return failure, so the existing
mapper owner destroys partial state and returns NULL instead of exposing an
invalid mapper to frame mapping. The backport also zero-initializes GL/Vulkan
interop state, initializes Unix semaphore FDs to -1, preserves imported FD
ownership and removes the extra GL failure-path context pop. GL remains disabled
in this recipe; its upstream fix is retained in the verified source. This fixes
error propagation, not VRAM exhaustion, and changes no hwdec or renderer policy.
Mapper rejection does not guarantee a decoder switch: a transient failure can
recover on a later frame, while persistent failure can keep dropping frames.

Every build runs `tests/test-cuda-mapper.py` after compiling mpv. It compiles the
actual pinned mapper and mapper-creation implementation with test-only device
stubs, the build's compiler flags and allocator objects. Success and failure at
each of three planes check returned ownership, partial cleanup and balanced
CUDA context operations, without requiring a GPU or real OOM. A pre-backport
build must demonstrate the inverse result with `--expect-broken`. No injection
hook or helper is shipped in the runtime. Inside the corresponding builder:

```sh
python3 /recipes/tests/test-cuda-mapper.py /work/build/mpv --output /work/logs/cuda-mapper.json
# Read-only older build, with writable output outside it:
python3 /recipes/tests/test-cuda-mapper.py /work/build/mpv --expect-broken --output /tmp/cuda-negative.json
```

Use `build.sh --work-dir build/linux-cuda-failure-fix` for a separate candidate;
keep the validated runtime untouched for A/B testing. The existing fresh-build
policy rebuilds unchanged dependency pins rather than reusing mutable component
trees. This backport does not implement shader policy or complete G07/G08.

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
premature one-frame success. All six video fixtures repeat the output assertion
with `hwdec=auto` in the GPU-absent builder, requiring software decode and clean
EOF. Automatic candidates must not include Vulkan or Vulkan-copy. The optional
GPU tier retains this recorded CPU evidence and performs separate displayed tests.

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
Every required child must pass, including all six automatic software
fallback and both SPDIF progress/EOF checks; a contradictory summary is rejected.
Failed marker creation removes any previous candidate marker. Older runtimes lack
the backport and its required evidence: rebuild from the current recipe rather
than resealing old binaries with new metadata. Already-patched binaries do not
need recompiling for the candidate-parser correction: regenerate capabilities
and targeted-test reports, then audit, inventory and validate the artifact.
Old `attempted_hwdecs` reports lack required candidate/backend evidence and are
rejected. Preserve original compilation provenance when refreshing reports;
do not relabel old binaries as a fresh build of the updated recipe.
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

Initial safety-backport validation, before the candidate-parser correction, used
recipe SHA-256
`38a7c7d7b7fdb43721029db2ed1c228920a2ca545eec851b0ab8c207906ad4dc`.
Two independent fresh builds passed source/signature/patch verification, CPU
smoke tests, both audits, inventory/marker validation and publication. Their
62 regular files, 31 symlinks, all modes/hashes/targets, 19 ELF records and complete
metadata were identical, including patch provenance and safe-selection evidence.
All six codecs produced five decodable images under `auto` and reached clean
software EOF in the GPU-absent builder. Twenty audit mutations, 182 invalid
playback reports and twelve build-state cases passed their expected rejection/
recovery checks. The bridge/Kotlin build and all 21 native tests passed.

On the available RTX 3050 6GB / driver 615.71.09, a bounded 4K HEVC Main10
diagnostic selected NVDEC with `auto` and NVDEC-copy with `auto-copy`. Explicit
Vulkan and Vulkan-copy also initialized, advanced playback and reached EOF.
This validates one NVIDIA host, not AMD/Intel hardware or every NVIDIA profile.

The patched production candidate also passed five independent JVMs with 25
source replacements each: 95 NVDEC results and 30 High10 software fallbacks.
All 35 HEVC-to-H.264 transitions used NVDEC at both ends. Every source advanced,
all JVMs exited cleanly, and sampled output/decoder drops were zero. Per-source
logs contained no automatic Vulkan candidate. CMake, ELF linkage and all five
live process maps identified the freshly built private media libraries.
Main-player overrides `no`, `auto-copy` and explicit `vulkan` retained precedence.
Safe-auto seek/rapid-seek, pause/resume, close/reopen and shutdown checks passed;
the native thumbnail probe retained `hwdec=no`, `vo=null`. Bounded PulseAudio,
audio-track, built-in/local/HTTP-addon subtitle, HUD control and focus hide/restore
checks passed. A 35-second 4K safe-auto sample used `cuda/p010` frames with mean
process CPU 9.1%, GPU utilization 9.6% and decoder utilization 6.5%, and zero
output/decoder drops. This was broadly comparable to the earlier explicit NVDEC
diagnostic; GPU utilization includes other desktop activity.

The candidate-parser correction was validated without recompiling media binaries.
Reports regenerated from the patched runtime were byte-identical across repeated
runs; all 19 ELF files and original compilation provenance remained unchanged.
The permanent matrix passed 19 parser cases (9 accepted, 10 rejected), four real
patched CPU controls, and four real unpatched CPU rejections. The unpatched
controls completed software playback after failed Vulkan device creation, with
no `Trying` event. The ordinary targeted entry point also rejected substituted
unpatched binaries behind copied patched metadata. Displayed auto/auto-copy
selected NVDEC/NVDEC-copy; explicit Vulkan/Vulkan-copy passed. Audits, inventory
and marker verification, 20 artifact mutations and 193 invalid-report mutations
passed. The original CPU-observation blind spot is closed; production behavior
and the upstream backport are unchanged.

The historical results below describe the **unpatched** runtime. In particular,
its automatic Vulkan selections are not the current safe-selection policy.
Fresh backport validation must use the new recipe identity and generated reports.

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
options and explicit patch lists; mpv lists the one upstream backport above.
`share/licenses` collects bundled
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

`audio-files-append` is CLI/config syntax and returns option-not-found through
libmpv's option API. The Linux bridge now sets `audio-files` as a native one-entry
array before initialization. The maintainer retested the previously failing YouTube
trailer: integrated video, separate audio and seeking passed. This does not establish
universal YouTube/provider reliability.
Native Wayland, JVM namespace isolation, hybrid/AMD/Intel hardware testing,
package dependency closure and replacement of the builder baseline remain
separate work. The safety backport introduces no `dlmopen`, renderer or lifecycle
change; the Linux main-player policy remains `hwdec=auto`.
