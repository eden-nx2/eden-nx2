<!--
# SPDX-FileCopyrightText: Copyright 2025 Eden Emulator Project
# SPDX-License-Identifier: GPL-3.0-or-later

# SPDX-FileCopyrightText: 2018 yuzu Emulator Project
# SPDX-License-Identifier: GPL-2.0-or-later
-->

<p align="center">
  <img src="dist/qt_themes/default/icons/256x256/eden.png" alt="Eden" width="200">
</p>

# Eden (NX2)

Eden is an experimental, open-source Nintendo Switch and Switch 2 emulator written in C++, derived from yuzu and Sudachi.

This repository is Eden NX2 with every change from the [Lichii](https://github.com/zortos293/Lichii) fork applied on top: its performance work, game fixes, shader recompiler and Vulkan fixes, and tooling. [LICHII_CHANGES.md](LICHII_CHANGES.md) lists all of them. It is not an official Eden release and is not affiliated with Nintendo.

## Downloads and setup

Builds are published on this repository's releases page by the `release-binaries` workflow.

On Windows, extract the entire build archive and run **eden.exe** (or **eden-cli.exe** from a terminal). Only use games and system files you have legally obtained. Games, keys and firmware are not included.

### Existing saves and settings

Eden's usual data directories and configuration keys are used, so existing Eden saves, settings and shader caches carry over. Back up your saves before testing experimental builds.

## Compatibility and issue reports

Compatibility and performance vary by game, hardware and driver. Rendering errors, stutters and crashes remain possible. Passing automated tests does not establish that a game renders correctly.

When reporting an issue, include:

- The exact build, game version, CPU, GPU and driver version.
- Relevant settings and steps to reproduce the problem.
- A screenshot and the log from the affected session, after checking it for personal information.

Do not upload game files, keys, firmware or private account information.

## Building and contributing

See the [build guide](docs/Build.md), [user documentation](docs/user/README.md) and [coding guidelines](docs/Coding.md). The GUI build target remains `yuzu`; its output is `eden.exe` on Windows and `eden` on other desktop platforms.

Release builds pass `-DEDEN_BUILD_TAG=build-N`, `-DEDEN_BUILD_PLATFORM=<platform>` and `-DEDEN_UPDATE_REPO=<owner/repo>` so the update checker (`-DENABLE_UPDATE_CHECKER=ON`) follows this repository's `build-N` releases. Local builds leave these empty and never check for updates.

## Credits and license

This project inherits the work of the Eden, yuzu and Sudachi projects, the Eden NX2 work by Takoroka and the Eglantine contributors, and the Lichii fork by zortos293 and its contributors. Original copyright notices and third-party licenses are retained.

See [LICENSE.txt](LICENSE.txt) and the source files for applicable license terms.
