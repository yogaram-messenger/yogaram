#!/bin/sh
set -eu
if [ $# -ne 1 ] || [ ! -d "$1/source/src/MyTelegram.Messenger" ]; then
  echo "usage: apply-opengram-overlay.sh /path/to/opengram" >&2
  exit 1
fi
root=$(CDPATH= cd -- "$(dirname "$0")" && pwd)
dest=$1/source/src
mkdir -p "$dest/YogaRam.EmailAuth"
cp -a "$root/YogaRam.EmailAuth/"*.cs "$root/YogaRam.EmailAuth/"*.csproj "$dest/YogaRam.EmailAuth/"
cd "$root/opengram-overlay"
find . -type f ! -name '*.md' | while IFS= read -r file; do
  mkdir -p "$dest/$(dirname "$file")"
  cp "$file" "$dest/$file"
done
echo "overlay applied to $1"
