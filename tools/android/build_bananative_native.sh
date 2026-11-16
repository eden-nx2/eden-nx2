#!/bin/bash
# SPDX-FileCopyrightText: Copyright 2026 Hokkaido contributors
# SPDX-License-Identifier: GPL-3.0-or-later
#
# Builds Banana Native's own native libraries (the game hosts and their helpers) from a checkout, for
# Hokkaido's :banana module to take over the release's: build_bananative_native.sh <checkout> <out dir> [title ID,...]
# Then build Hokkaido with -PbananaNativeLibs=<out dir>. The packer's libraries stay the release's.
# Needs the NDK Banana Native names (android/app/build.gradle: ndkVersion) and JAVA_HOME (JDK 17 or later).
set -euo pipefail

if [ $# -lt 2 ]; then
    echo "usage: $0 <Banana Native checkout> <out dir> [title ID,...]" >&2
    exit 2
fi
checkout=$1
out=$2
games=${3:-}

app="$checkout/android/app"
if [ ! -f "$app/local.properties" ]; then
    sdk=${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}
    if [ -z "$sdk" ]; then
        echo "$app/local.properties is missing: set ANDROID_HOME" >&2
        exit 1
    fi
    printf 'sdk.dir=%s\n' "$(cygpath -m "$sdk" 2>/dev/null || echo "$sdk")" > "$app/local.properties"
fi

args=(externalNativeBuildRelease -PnxPackEngine=fake --console=plain -q)
if [ -n "$games" ]; then
    args+=("-PnxGames=$games")
fi
gradlew=./gradlew
if [ -f "$app/gradlew.bat" ] && [ "${OS:-}" = "Windows_NT" ]; then
    gradlew=./gradlew.bat
fi
(cd "$app" && "$gradlew" "${args[@]}")

# The newest CMake output of the release build type (AGP names its folder by a hash of the configuration).
obj=$(ls -td "$app"/build/intermediates/cxx/RelWithDebInfo/*/obj/arm64-v8a | head -1)
mkdir -p "$out"
rm -f "$out"/*.so
cp "$obj"/*.so "$out"/
echo "Built from $(git -C "$checkout" describe --tags --always --dirty):"
ls "$out"
