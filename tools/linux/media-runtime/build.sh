#!/usr/bin/env bash
# CPU-only, rootless container build. Only recipe, verified downloads and work tree
# are mounted; neither Homebrew nor host library directories enter the builder.
source "$(dirname -- "$0")/scripts/common.sh"
for tool in podman jq sha256sum realpath flock python3; do need "$tool"; done
[[ $(uname -s) == Linux && $(uname -m) == x86_64 ]] || fail 'Native builder supports Linux x86_64 only'
work="$MEDIA_TOOLS/../../../build/linux-media-runtime"
jobs=4
while (($#)); do
    case "$1" in
        --work-dir) work=${2:?}; shift 2 ;;
        --jobs) jobs=${2:?}; shift 2 ;;
        --clean) shift ;; # Every invocation already uses fresh source/build/install trees.
        --help) printf 'Usage: build.sh [--work-dir DIR] [--jobs N] [--clean]\n'; exit 0 ;;
        *) fail "Unknown option: $1" ;;
    esac
done
[[ $jobs =~ ^[1-9][0-9]*$ ]] || fail '--jobs must be a positive integer'
work=$(realpath -m -- "$work")
[[ $work != / && $work != "$MEDIA_TOOLS" && $work != *,* && $work != *:* ]] || fail 'Unsafe work directory'
[[ $work != "$MEDIA_TOOLS/"* ]] || fail 'Work directory must be outside the recipe source tree'
mkdir -p -- "$work"
exec 9>"$work/.build-lock"
flock -n 9 || fail "Another build owns $work"
[[ ! -e $work/runtime || -L $work/runtime ]] || fail "$work/runtime is not a managed symlink"
"$MEDIA_TOOLS/scripts/fetch.sh" "$work/downloads"
# Hash uncommitted recipes as well as the complete policy; never use Git HEAD alone.
recipe_hash=$(cd "$MEDIA_TOOLS" && find . -type f ! -name README.md ! -path '*/__pycache__/*' -print0 | LC_ALL=C sort -z | xargs -0 sha256sum | sha256sum | cut -d' ' -f1)
base=$(jq -er '.builder.base_image' "$MEDIA_MANIFEST")
[[ $base == *@sha256:* ]] || fail 'Builder base must be digest-pinned'
image="localhost/nuvio-media-builder:$recipe_hash"
context="$work/builder-context"
mkdir -p "$context"
jq '{builder:.builder}' "$MEDIA_MANIFEST" > "$context/manifest.json"
ca_hash=$(source_field ca-certificates sha256)
cp "$work/downloads/$ca_hash.archive" "$context/ca-certificates.deb"
if ! podman image exists "$image"; then
    podman build --layers --network=host --file "$MEDIA_TOOLS/Containerfile" \
        --build-arg "BASE_IMAGE=$base" --build-arg "CA_SHA256=$ca_hash" \
        --build-arg "APT_SNAPSHOT=$(jq -er '.builder.apt_snapshot' "$MEDIA_MANIFEST")" \
        --tag "$image" "$context"
fi
image_id=$(podman image inspect --format '{{.Id}}' "$image")
identity=$(printf '%s\n' "$recipe_hash" "$image_id" x86_64 | sha256sum | cut -d' ' -f1)
mkdir -p "$work/builds"
# Only verified downloads and the builder image are reusable. Never consume a
# previous extracted tree, install prefix or component stamp, even with --clean.
job=$(mktemp -d "$work/builds/$identity.XXXXXX")
printf '%s\n' "$identity" > "$job/identity"
printf '%s\n' "$recipe_hash" > "$job/recipe-hash"
printf '%s\n' "$image_id" > "$job/builder-image-id"
# Compilation has no network and no GPU/device access. keep-id prevents host files
# being owned by subordinate IDs. SELinux labels on the repository are untouched.
podman run --rm --network=none --userns=keep-id \
    --security-opt label=disable --cap-drop=all --security-opt no-new-privileges \
    --mount "type=bind,src=$MEDIA_TOOLS,dst=/recipes,ro" \
    --mount "type=bind,src=$work/downloads,dst=/downloads,ro" \
    --mount "type=bind,src=$job,dst=/work" \
    "$image" bash /recipes/scripts/build-runtime.sh "$jobs"
# The container marks only an audited, smoke-tested generation valid. Recheck
# its complete inventory before publishing; the old symlink is never unlinked.
python3 "$MEDIA_TOOLS/scripts/artifact_contract.py" "$job/runtime" --validated \
    --recipe-hash "$recipe_hash" --builder-image-id "$image_id"
temporary_link="$work/.runtime-${job##*/}"
trap 'rm -f -- "$temporary_link"' EXIT
ln -s "builds/${job##*/}/runtime" "$temporary_link"
mv -Tf -- "$temporary_link" "$work/runtime"
printf '\nRuntime: %s/runtime\nMPV_ROOT=%s/runtime\n' "$work" "$work"
