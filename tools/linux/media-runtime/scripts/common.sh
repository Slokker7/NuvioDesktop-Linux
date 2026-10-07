#!/usr/bin/env bash
set -euo pipefail
export PYTHONDONTWRITEBYTECODE=1
MEDIA_TOOLS=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/.." && pwd)
MEDIA_MANIFEST="$MEDIA_TOOLS/manifest.json"
fail() { printf 'media-runtime: %s\n' "$*" >&2; exit 1; }
need() { command -v "$1" >/dev/null || fail "Required tool missing: $1"; }
source_field() { jq -er --arg name "$1" --arg field "$2" '.sources[] | select(.name == $name) | .[$field]' "$MEDIA_MANIFEST"; }
source_options() { jq -r --arg name "$1" '.sources[] | select(.name == $name) | .options[] | gsub("@BUILD_PREFIX@"; env.MEDIA_BUILD_PREFIX // "@BUILD_PREFIX@")' "$MEDIA_MANIFEST"; }
