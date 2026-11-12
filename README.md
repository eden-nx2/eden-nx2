<!--
# SPDX-FileCopyrightText: Copyright 2026 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

# SPDX-FileCopyrightText: 2018 yuzu Emulator Project
# SPDX-License-Identifier: GPL-2.0-or-later
-->

<h1 align="center">
  <br>
  <img src="./dist/qt_themes/default/icons/256x256/eden.png" alt="Eden" width="200">
  <br>
  <b>Eden NX2</b>
  <br>
</h1>

<h4 align="center"><b>Eden NX2</b> is a free and open-source Nintendo Switch emulator based on Eden. It
carries Eden NX2's experimental Switch 2 support together with the performance and compatibility
work of the Lichii (desktop) and Hokkaido (Android) forks, with Eden's artwork.
<br>
Builds for Windows, Linux, macOS and Android.
</h4>

## What this branch is

Eden NX2 is Takoroka's experimental Switch 2 branch of Eden
([git.eden-emu.dev/Takoroka/eden-nx2](https://git.eden-emu.dev/Takoroka/eden-nx2)). Its
work was continued in Eglantine and then in
[Lichii](https://github.com/zortos293/Lichii), and
[Hokkaido](https://github.com/pavelgajdo/Hokkaido) forked Lichii for Android phones.
Both forks renamed the emulator.

This branch is Lichii's `nx2` tip with Hokkaido's own changes on top, released as **Eden NX2** on
every platform with Eden's icons. It installs next to official Eden instead of replacing it: the
Android app is `com.eden.eden_nx2`, the desktop entry and macOS bundle are `com.eden.eden_nx2` and
`com.eden.eden-nx2`, and its data lives in its own `eden-nx2` folders. The executables keep
Eden's file names (`eden`, `eden-cli`).
[docs/MIRROR_CHANGES.md](./docs/MIRROR_CHANGES.md) lists what came from where and how each
conflict was resolved.

This is an independent build, not an official Eden release. It is not affiliated with Nintendo.

## Data folders

Settings, saves and caches are in `%APPDATA%\eden-nx2` on Windows, and in the `eden-nx2`
directories under `$XDG_DATA_HOME`, `$XDG_CONFIG_HOME` and `$XDG_CACHE_HOME` on Linux and macOS,
apart from official Eden's `eden` folders. On the first GUI launch, Eden NX2 offers to import an
existing Eden, Citron, Sudachi, Suyu or yuzu profile (Eden's shader caches are kept). A portable
`user/` folder next to the executable still takes priority. On Android the app has its own storage.

## Building

See the build guides under [docs/](./docs/). CI is under `.github/workflows`:

- `release-binaries.yml` builds Windows, Linux and macOS releases (`eden-<platform>` assets). It
  passes `-DEDEN_UPDATE_REPO=${{ github.repository }}`, so the built-in updater follows this
  repository's releases. Local builds follow none.
- `android.yml` builds the arm64 APK on every push; an `android-<version>` tag publishes it.
- `build-check.yml` builds pull requests.

## Compatibility and issue reports

Switch 2 support is experimental. Compatibility and performance vary by game, hardware and
driver; rendering errors, stutters and crashes remain possible. Only use games and system files
you have legally obtained. Games, keys and firmware are not included.

## License

Eden is licensed under the GPLv3 (or any later version). Refer to the [LICENSE.txt](./LICENSE.txt)
file. Credit for the Switch 2 work goes to Takoroka and the Eden NX2, Eglantine, Lichii and
Hokkaido contributors; see the commit history.
