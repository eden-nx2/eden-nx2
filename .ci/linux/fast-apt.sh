#!/bin/bash
# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

# Points apt at the fastest of a few Ubuntu mirrors before CI installs packages. A hosted runner's
# default mirror sometimes serves a few hundred KB/s, which turns a one-minute install into an hour.
# Each candidate downloads the same package index for a few seconds and the fastest wins; the
# runner's own mirror stays when it is fast enough or nothing else answers.

set -uo pipefail

arch=$(dpkg --print-architecture)
release=$(. /etc/os-release && echo "$VERSION_CODENAME")
if [[ "$arch" == amd64 ]]; then
    candidates=(http://azure.archive.ubuntu.com/ubuntu https://mirrors.edge.kernel.org/ubuntu
                http://archive.ubuntu.com/ubuntu http://us.archive.ubuntu.com/ubuntu)
    pattern='https\?://[A-Za-z0-9.-]*archive\.ubuntu\.com/ubuntu/\?'
else
    candidates=(http://ports.ubuntu.com/ubuntu-ports https://mirrors.edge.kernel.org/ubuntu-ports)
    pattern='https\?://[A-Za-z0-9.-]*ports\.ubuntu\.com/ubuntu-ports/\?'
fi

sources=()
for file in /etc/apt/sources.list.d/ubuntu.sources /etc/apt/sources.list; do
    [[ -f "$file" ]] && grep -q "$pattern" "$file" && sources+=("$file")
done
current=$(grep -oh "$pattern" "${sources[@]}" /dev/null 2>/dev/null | head -n1 | sed 's#/$##')

# Bytes per second over at most 6 s of the main package index (several MB).
speed() {
    curl -s -o /dev/null --max-time 6 -w '%{speed_download}' \
        "$1/dists/${release}/main/binary-${arch}/Packages.gz" | cut -d. -f1
}

best="" best_speed=0
for mirror in ${current:+"$current"} "${candidates[@]}"; do
    s=$(speed "$mirror")
    s=${s:-0}
    echo "apt mirror $mirror: $((s / 1024)) KiB/s"
    if (( s > best_speed )); then
        best=$mirror best_speed=$s
    fi
done

if [[ -n "$best" && -n "$current" && "$best" != "$current" ]]; then
    echo "Using $best instead of $current"
    sudo sed -i "s#${pattern}#${best}/#g" "${sources[@]}"
fi

# A stalled download is retried rather than waited on.
printf '%s\n' 'Acquire::Retries "5";' 'Acquire::http::Timeout "30";' 'Acquire::https::Timeout "30";' \
    | sudo tee /etc/apt/apt.conf.d/80-eden-ci >/dev/null
exit 0
