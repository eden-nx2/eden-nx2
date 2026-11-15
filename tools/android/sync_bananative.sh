#!/usr/bin/env bash
# SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
# SPDX-License-Identifier: GPL-3.0-or-later

# Moves the Banana Native that Hokkaido embeds (src/android/banana) to another release:
#
#   tools/android/sync_bananative.sh <BananaNative clone> <tag> <the tag's release APK> <APK URL>
#
# Copies the tag's Android sources into upstream/, applies patches/, reads the release's BuildConfig
# values into buildconfig.txt and pins the APK in UPSTREAM.txt. Afterwards compare the tag's
# android/app/src/{main,ondevice}/AndroidManifest.xml with src/main/AndroidManifest.xml, then build.
# DEXDUMP names dexdump if it is not in the newest Android SDK build-tools.
set -euo pipefail

repo=$1 tag=$2 apk=$3 url=$4
root=$(cd "$(dirname "$0")/../.." && pwd)
module=$root/src/android/banana

sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-$HOME/AppData/Local/Android/Sdk}}
dexdump=${DEXDUMP:-}
if [ -z "$dexdump" ]; then
    tools=$(ls -d "$sdk"/build-tools/*/ | sort -V | tail -1)
    dexdump=$tools/dexdump
    [ -x "$dexdump" ] || dexdump=$tools/dexdump.exe
fi

# Sources: the tag's, with Hokkaido's patches.
rm -rf "$module/upstream"
mkdir -p "$module/upstream"
git -C "$repo" archive "$tag" android/app/src/main/java android/app/src/main/res \
    android/app/src/main/AndroidManifest.xml android/app/src/ondevice android/app/src/variant-play |
    tar -x -C "$module/upstream" --strip-components=3
for patch in "$module"/patches/*.patch; do
    patch -d "$module/upstream" -p1 --no-backup-if-mismatch < "$patch"
done

# BuildConfig: the values the release was built with (they match its native libraries).
work=$(mktemp -d)
trap 'rm -rf "$work"' EXIT
unzip -q -o "$apk" 'classes*.dex' -d "$work"
{
    echo "# BuildConfig fields of the Banana Native release this module embeds, read from its classes.dex (tools/android/sync_bananative.sh): name, Java type and Java literal, separated by the first two spaces"
    for dex in "$work"/classes*.dex; do "$dexdump" "$dex" 2>/dev/null; done | awk '
        /Class descriptor  : .Ldev\/nxnative\/android\/BuildConfig;/ { p = 1 }
        p && /Instance fields/ { exit }
        p && /^ *name *: / { n = $0; sub(/^ *name *: \x27/, "", n); sub(/\x27$/, "", n) }
        p && /^ *type *: / { t = $0; sub(/^ *type *: \x27/, "", t); sub(/\x27$/, "", t) }
        p && /^ *value *: / {
            v = $0; sub(/^ *value *: /, "", v)
            ty = (t == "Z") ? "boolean" : (t == "I") ? "int" : (t == "Ljava/lang/String;") ? "String" : t
            if (n != "APPLICATION_ID" && n != "BUILD_TYPE" && n != "DEBUG") printf "%s %s %s\n", n, ty, v
        }'
} > "$module/buildconfig.txt"

cat > "$module/UPSTREAM.txt" <<EOF
# The Banana Native release this module embeds (tools/android/sync_bananative.sh writes this file).
repo = https://git.eden-emu.dev/moceure/BananaNative
tag = $tag
commit = $(git -C "$repo" rev-parse "$tag^{commit}")
apk_url = $url
apk_sha256 = $(sha256sum "$apk" | cut -d' ' -f1)
EOF

echo "Banana Native $tag synced: $(grep -c . "$module/buildconfig.txt") BuildConfig lines; check the manifests, then build."
