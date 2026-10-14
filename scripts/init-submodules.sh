#!/usr/bin/env bash
# Check out the native/Gradle submodules pinned in submodule-pins.txt.
# URLs come from .gitmodules. Does not register gitlinks in this repo.
set -euo pipefail
cd "$(dirname "$0")/.."

declare -A URLS
path=""
url=""
while IFS= read -r line || [[ -n "$line" ]]; do
  line="${line#"${line%%[![:space:]]*}"}"
  case "$line" in
    path\ =\ *) path="${line#path = }" ;;
    url\ =\ *)
      url="${line#url = }"
      URLS["$path"]="$url"
      ;;
  esac
done < .gitmodules

while read -r path sha _; do
  [[ -z "${path:-}" || "$path" == \#* ]] && continue
  url="${URLS[$path]:-}"
  if [[ -z "$url" ]]; then
    echo "no url for $path" >&2
    exit 1
  fi
  if [[ -e "$path/.git" || -f "$path/CMakeLists.txt" || -f "$path/build.gradle" || -f "$path/Android.mk" ]]; then
    echo "skip $path (already present)"
    continue
  fi
  echo "fetch $path @ $sha"
  rm -rf "$path"
  mkdir -p "$path"
  git -C "$path" init -q
  git -C "$path" remote add origin "$url"
  if ! git -C "$path" fetch --depth 1 origin "$sha"; then
    echo "shallow fetch failed, cloning $url"
    rm -rf "$path"
    git clone "$url" "$path"
  fi
  git -C "$path" checkout --detach "$sha"
done < submodule-pins.txt

echo "submodules ready"
