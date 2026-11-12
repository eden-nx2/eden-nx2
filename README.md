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

<h4 align="center"><b>Eden</b> is a free and open-source Nintendo Switch emulator. This branch carries
Eden NX2's experimental Switch 2 support together with the performance and compatibility work of
the Lichii (desktop) and Hokkaido (Android) forks, under Eden's own name and artwork.
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

This branch is Lichii's `nx2` tip with Hokkaido's own changes on top, and the Eden branding
restored on every platform: the `eden` executables, Eden's icons, desktop entry and
`dev.eden_emu.eden` IDs, the Android app `dev.eden.eden_emulator`, and Eden's data folders.
[docs/MIRROR_CHANGES.md](./docs/MIRROR_CHANGES.md) lists what came from where and how each
conflict was resolved.

This is an independent build, not an official Eden release. It is not affiliated with Nintendo.

## Data folders

Settings, saves and caches are in Eden's usual folders: `%APPDATA%\eden` on Windows, and the
`eden` directories under `$XDG_DATA_HOME`, `$XDG_CONFIG_HOME` and `$XDG_CACHE_HOME` on Linux and
macOS. An existing Eden profile is used as it is. When Eden has no configuration yet, the first
GUI launch offers to import a Citron, Sudachi, Suyu or yuzu profile. A portable `user/` folder
next to the executable still takes priority.

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
