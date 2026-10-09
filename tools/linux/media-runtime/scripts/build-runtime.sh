#!/usr/bin/env bash
# Runs only inside the pinned builder. Do not run this on the development host.
source "$(dirname -- "$0")/common.sh"
[[ $MEDIA_TOOLS == /recipes && -f /builder-packages.tsv ]] || fail 'Use build.sh (isolated builder required)'
jobs=${1:?}
# A resumed/altered install or source tree cannot authenticate itself by making
# a new inventory. build.sh supplies an empty attempt directory on every run.
for directory in src build prefix tools stage runtime; do
    [[ ! -e /work/$directory && ! -L /work/$directory ]] || fail "Non-fresh build state: $directory; use build.sh"
done
unset LD_LIBRARY_PATH LD_PRELOAD CPATH C_INCLUDE_PATH CPLUS_INCLUDE_PATH
export LC_ALL=C TZ=UTC SOURCE_DATE_EPOCH=$(jq -r .source_date_epoch "$MEDIA_MANIFEST")
export MEDIA_BUILD_PREFIX=/work/prefix
export CC=$(jq -r .builder.cc "$MEDIA_MANIFEST") CXX=$(jq -r .builder.cxx "$MEDIA_MANIFEST")
export PATH=/work/tools/bin:/usr/bin:/bin
export CFLAGS='-O2 -fPIC -ffile-prefix-map=/work=. -fdebug-prefix-map=/work=.'
export CXXFLAGS="$CFLAGS"
export CPPFLAGS='-I/work/prefix/include'
# Build-time only: mpv executes its CLI to generate metadata before installation.
# Every exported ELF is rewritten and audited for ORIGIN-only RUNPATH below.
export LDFLAGS='-L/work/prefix/lib -Wl,--enable-new-dtags,-rpath,/work/prefix/lib'
export LIBRARY_PATH=/work/prefix/lib
export PKG_CONFIG_LIBDIR=/work/prefix/lib/pkgconfig:/usr/lib/x86_64-linux-gnu/pkgconfig:/usr/share/pkgconfig
unset PKG_CONFIG_PATH
umask 022
mkdir -p /work/{src,build,prefix,tools,stage,logs}
# Recheck hashes inside the network-disabled builder before executing any source.
while IFS=$'\t' read -r name hash; do
    [[ $name == ca-certificates ]] && continue
    printf '%s  %s\n' "$hash" "/downloads/$hash.archive" | sha256sum -c -
    mkdir "/work/src/$name"
    tar --extract --file "/downloads/$hash.archive" --directory "/work/src/$name" \
        --strip-components=1 --no-same-owner --no-same-permissions
done < <(jq -r '.sources[] | [.name,.sha256] | @tsv' "$MEDIA_MANIFEST")
python3 /recipes/scripts/source_patches.py /work/src
mkdir -p /work/gpg
chmod 700 /work/gpg
sig=$(jq -r '.sources[] | select(.name=="FFmpeg") | .signature.sha256' "$MEDIA_MANIFEST")
key=$(jq -r '.sources[] | select(.name=="FFmpeg") | .signature.key_sha256' "$MEDIA_MANIFEST")
fingerprint=$(jq -r '.sources[] | select(.name=="FFmpeg") | .signature.fingerprint' "$MEDIA_MANIFEST")
printf '%s  %s\n' "$sig" "/downloads/$sig.archive" "$key" "/downloads/$key.archive" | sha256sum -c -
gpg --homedir /work/gpg --batch --import "/downloads/$key.archive" > /work/logs/signature.log 2>&1
gpg --homedir /work/gpg --batch --status-fd 1 --verify "/downloads/$sig.archive" "/downloads/$(source_field FFmpeg sha256).archive" >> /work/logs/signature.log 2>&1
rg_status=$(awk '$1=="[GNUPG:]" && $2=="VALIDSIG" {print $3}' /work/logs/signature.log)
[[ $rg_status == "$fingerprint" ]] || fail 'Unexpected FFmpeg signing fingerprint'
meson() { python3 /work/src/meson/meson.py "$@"; }
merge_stage() {
    cp -a /work/stage/usr/. /work/prefix/
    # The development prefix is used ONLY while building. Final .pc files are
    # rewritten to pcfiledir, and no CMake/libtool build metadata is distributed.
    find /work/prefix/lib/pkgconfig -name '*.pc' -exec sed -i 's|^prefix=/usr$|prefix=/work/prefix|' {} + 2>/dev/null || true
}
prepare_stage() { rm -rf /work/stage; mkdir -p /work/stage; }
cmake_build() {
    local name=$1; shift
    cmake -S "/work/src/$name" -B "/work/build/$name" -G Ninja \
        -DCMAKE_BUILD_TYPE=Release -DCMAKE_INSTALL_PREFIX=/usr -DCMAKE_INSTALL_LIBDIR=lib \
        -DCMAKE_PREFIX_PATH=/work/prefix "$@"
    cmake --build "/work/build/$name" --parallel "$jobs"
    prepare_stage
    DESTDIR=/work/stage cmake --install "/work/build/$name"
    merge_stage
}
meson_build() {
    local name=$1; shift
    meson setup --reconfigure "/work/build/$name" "/work/src/$name" \
        --prefix=/usr --libdir=lib --buildtype=release --default-library=shared --wrap-mode=nodownload "$@"
    meson compile -C "/work/build/$name" -j "$jobs"
    prepare_stage
    DESTDIR=/work/stage meson install -C "/work/build/$name" --no-rebuild
    merge_stage
}
autoconf_build() {
    local name=$1; shift
    mkdir -p "/work/build/$name"
    (cd "/work/build/$name"; "/work/src/$name/configure" --prefix=/usr "$@"; make -j "$jobs")
    prepare_stage
    make -C "/work/build/$name" DESTDIR=/work/stage install
    merge_stage
}
run_component() {
    local name=$1; shift
    printf '\nBuilding %s (log: logs/%s.log)\n' "$name" "$name"
    (trap 'tail -80 "/work/logs/$name.log" >&2' ERR; "$@") > "/work/logs/$name.log" 2>&1
}
build_nasm() {
    mkdir -p /work/build/nasm
    (cd /work/build/nasm; /work/src/nasm/configure --prefix=/work/tools; make -j "$jobs"; make install)
}
build_nvheaders() { make -C /work/src/nv-codec-headers PREFIX=/work/prefix install; }
build_lua() {
    make -C /work/src/LuaJIT -j "$jobs" PREFIX=/usr CC="$CC" XCFLAGS="$CFLAGS" BUILDMODE=dynamic
    prepare_stage
    make -C /work/src/LuaJIT PREFIX=/usr DESTDIR=/work/stage install
    merge_stage
}
build_bzip2() {
    mapfile -t opts < <(source_options bzip2)
    make -C /work/src/bzip2 "${opts[@]}" -j "$jobs" CC="$CC" CFLAGS="$CFLAGS -D_FILE_OFFSET_BITS=64"
    cp -a /work/src/bzip2/libbz2.so.1.0.8 /work/prefix/lib/
    ln -sfn libbz2.so.1.0.8 /work/prefix/lib/libbz2.so.1.0
    ln -sfn libbz2.so.1.0 /work/prefix/lib/libbz2.so
    cp /work/src/bzip2/bzlib.h /work/prefix/include/
}
build_ffmpeg() {
    mkdir -p /work/build/FFmpeg
    mapfile -t opts < <(source_options FFmpeg)
    (cd /work/build/FFmpeg; /work/src/FFmpeg/configure --prefix=/usr --cc="$CC" --cxx="$CXX" "${opts[@]}"; make -j "$jobs")
    prepare_stage
    make -C /work/build/FFmpeg DESTDIR=/work/stage install
    merge_stage
}
run_component nasm build_nasm
run_component nv-codec-headers build_nvheaders
run_component Vulkan-Headers cmake_build Vulkan-Headers
mapfile -t opts < <(source_options Vulkan-Loader)
run_component Vulkan-Loader cmake_build Vulkan-Loader "${opts[@]}"
mapfile -t opts < <(source_options glslang)
run_component glslang cmake_build glslang "${opts[@]}"
mapfile -t opts < <(source_options dav1d)
run_component dav1d meson_build dav1d "${opts[@]}"
run_component LuaJIT build_lua
mapfile -t opts < <(source_options libunibreak)
run_component libunibreak autoconf_build libunibreak "${opts[@]}"
mapfile -t opts < <(source_options libass)
run_component libass autoconf_build libass "${opts[@]}"
# This factual registry becomes generated C in libdisplay-info. Verify the
# packaged build input against its pinned source and ship its notices.
cmp /work/src/hwdata/pnp.ids /usr/share/hwdata/pnp.ids
run_component libdisplay-info meson_build libdisplay-info
mapfile -t opts < <(source_options libplacebo)
run_component libplacebo meson_build libplacebo "${opts[@]}"
run_component bzip2 build_bzip2
run_component FFmpeg build_ffmpeg
mapfile -t opts < <(source_options libXpresent)
run_component libXpresent autoconf_build libXpresent "${opts[@]}"
mapfile -t opts < <(source_options mpv)
run_component mpv meson_build mpv "${opts[@]}"
python3 /recipes/tests/test-cuda-mapper.py /work/build/mpv --output /work/logs/cuda-mapper.json
# Export a deliberately small runtime; no compiler, driver, static archives,
# libtool files, CMake exports or build-time registry/code generators.
runtime=/work/runtime
mkdir -p "$runtime"/{bin,lib/pkgconfig,include/mpv,share/nuvio-media-runtime,share/licenses}
cp -a /work/prefix/lib/*.so* "$runtime/lib/"
cp -a /work/prefix/bin/{mpv,ffmpeg,ffprobe} "$runtime/bin/"
cp -a /work/prefix/include/mpv/. "$runtime/include/mpv/"
# Public development metadata is for the dynamic mpv client API only.
# Static linkage is intentionally unsupported, so omit private SDK requirements.
cp -a /work/prefix/lib/pkgconfig/mpv.pc "$runtime/lib/pkgconfig/"
sed -i 's|/work/prefix|${prefix}|g; s|^prefix=.*$|prefix=${pcfiledir}/../..|; /^Requires.private:/d; /^Libs.private:/d' "$runtime/lib/pkgconfig/mpv.pc"
while IFS= read -r -d '' file; do
    if [[ $(head -c4 "$file") == $'\177ELF' ]]; then
        strip --strip-debug "$file"
        case "$file" in
            "$runtime"/bin/*) patchelf --set-rpath '$ORIGIN/../lib' "$file" ;;
            *) patchelf --set-rpath '$ORIGIN' "$file" ;;
        esac
    fi
done < <(find "$runtime" -type f -print0)
python3 /recipes/scripts/inventory.py "$runtime"
# Exercise the new unpublished generation, then seal all evidence into the
# inventory. Build-time config is checked against the actual fresh build tree.
python3 /recipes/scripts/smoke-test.py "$runtime" --output /work/logs/capabilities.json
cp /work/logs/capabilities.json "$runtime/share/nuvio-media-runtime/capabilities.json"
python3 /recipes/tests/test-safe-hwdec.py "$runtime" --source-dir /work/src --output /work/logs/safe-hwdec.json
cp /work/logs/safe-hwdec.json "$runtime/share/nuvio-media-runtime/safe-hwdec.json"
python3 /recipes/scripts/artifact_contract.py "$runtime" --seal
python3 /recipes/scripts/audit-runtime.py "$runtime" --build-dir /work/build > /work/logs/elf-audit.json
cp /work/logs/elf-audit.json "$runtime/share/nuvio-media-runtime/elf-audit.json"
python3 /recipes/scripts/artifact_contract.py "$runtime" --seal
python3 /recipes/scripts/audit-runtime.py "$runtime" --build-dir /work/build > /work/logs/final-audit.json
cmp /work/logs/final-audit.json /work/logs/elf-audit.json
python3 /recipes/scripts/artifact_contract.py "$runtime" --mark-validated
printf 'Runtime build and CPU audit complete.\n'
