#!/usr/bin/env bash
# No downloaded code runs here. Every archive/key/signature is checked before use.
source "$(dirname -- "$0")/common.sh"
need curl; need jq; need sha256sum
# Fail before process substitution if the canonical input is malformed.
jq -e '.schema_version == 1 and .architecture == "x86_64" and
    ([.sources[].name] | length == (unique | length)) and
    all(.sources[]; (.sha256 | test("^[0-9a-f]{64}$")) and
        (.url | startswith("https://")) and (.options | type == "array")) and
    all(.builder.packages[]; test("^[a-z0-9][a-z0-9+.-]*$"))' "$MEDIA_MANIFEST" >/dev/null || fail 'Invalid manifest'
cache=${1:?Usage: fetch.sh DOWNLOAD_DIRECTORY}
mkdir -p -- "$cache"
fetch() {
    local url=$1 hash=$2 target="$cache/$2.archive"
    [[ $url == https://* && $hash =~ ^[0-9a-f]{64}$ ]] || fail 'Invalid source URL/hash'
    if [[ -f $target ]] && printf '%s  %s\n' "$hash" "$target" | sha256sum -c - >/dev/null 2>&1; then return; fi
    local temporary
    temporary=$(mktemp "$cache/download.XXXXXX")
    if ! curl --fail --location --proto '=https' --proto-redir '=https' --tlsv1.2 \
        --connect-timeout 30 --max-time 600 --output "$temporary" "$url"; then
        rm -f -- "$temporary"; fail "Download failed: $url"
    fi
    printf '%s  %s\n' "$hash" "$temporary" | sha256sum -c - || { rm -f -- "$temporary"; fail "Hash mismatch: $url"; }
    mv -- "$temporary" "$target"
}
while IFS=$'\t' read -r url hash; do fetch "$url" "$hash"; done < <(
    jq -r '.sources[] | [.url,.sha256] | @tsv' "$MEDIA_MANIFEST")
while IFS=$'\t' read -r url hash; do fetch "$url" "$hash"; done < <(
    jq -r '.sources[] | select(.signature) | .signature | [.url,.sha256], [.key_url,.key_sha256] | @tsv' "$MEDIA_MANIFEST")
