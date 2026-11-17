**First public release of Eden NX2**: an unofficial, experimental build of Eden with Switch 2 support. It is Eden NX2 (Takoroka) continued in Eglantine and [Lichii](https://github.com/zortos293/Lichii) **build 316**, with the Android work of [Hokkaido](https://github.com/pavelgajdo/Hokkaido) **0.7.0**, released under the Eden NX2 name on Windows, Linux, macOS and Android. This is not an official Eden release.

### Before you start

- **Installs next to official Eden.** Eden NX2 has its own app ID (`com.eden.eden_nx2` on Android) and its own `eden-nx2` data folders, so it doesn't touch an existing Eden installation.
- **Bring your settings over.** On the first launch on desktop, Eden NX2 offers to import an Eden, Citron, Sudachi, Suyu or yuzu profile (Eden's shader caches come along). The offer only appears while Eden NX2 has no configuration of its own yet.
- **Your own files are needed.** Dump your keys, firmware and games from your own console. None are included or provided.
- **Updates.** The desktop builds' built-in updater offers newer releases from this page.

### What's new

**Android**
- **Two engines.** Game properties → **Engine**: *Eden NX2* (the emulator), *Banana Native* or *Auto*. [Banana Native](https://git.eden-emu.dev/moceure/BananaNative) runs a supported game's code natively and draws with Vulkan. A game needs a one-time preparation before its first start in it. Auto uses Banana Native for the games its profiles mark as playable (Donkey Kong Bananza among them) and the emulator for the rest. Both engines share the same save folder, GPU driver and resolution scale. Packs exported from Banana Native's own app can be imported in Game properties.
- **Frame skip** setting (0–3) for every game, and phone memory limits for scene loads.
- Custom GPU drivers on Adreno and other Vulkan GPUs (such as Mesa RADV on Xclipse), `.nup` games and loose ExeFS game folders.

**All platforms**
- Faster GPU emulation: split GPU threads, shader cache v2 with shader prebuilding before the game starts, compute emulation of mesh shaders for GPUs without them.
- Ocarina of Time: FPS Unlock, an anti-aliasing option (Game / No sharpening), DLSS and FSR 4 on PC.
- Mario Kart World 60 FPS mode, and stable dynamic resolution.
- Fire Emblem: Fortune's Weave upscaling that keeps detail over time (temporal by default).
- Donkey Kong Bananza: the tutorial video renders correctly, including on AMD/RADV.
- Optional online play through **Nextendo**, a third-party online service (not Nintendo): Configure → Network. It is **off by default**.
- LAN play for local wireless games without a room, IPv6 networking, solid NSZ/NCZ support, Switch 1 add-ons shown on Switch 2 Edition games.
- Many fixes for Intel, AMD and NVIDIA GPUs, and texture and buffer cache memory improvements.

### Known issues

- Switch 2 support is **experimental**: many games have rendering errors, stutter or crash, and performance varies a lot between devices.
- Banana Native supports only some games, and only on Android.
- macOS builds aren't notarized (see the macOS section below). If GitHub had no macOS machine free when this release was built, the macOS files are added later.

### Credits

Takoroka and the Eden team (Eden NX2), Eglantine, zortos293 and Lichii's contributors, pavelgajdo and Hokkaido's contributors, and moceure (Banana Native, MIT). Eden NX2 is GPL-3.0. What came from where: [MIRROR_CHANGES.md](https://github.com/eden-nx2/eden-nx2/blob/nx2/docs/MIRROR_CHANGES.md).

Eden NX2 is not affiliated with Nintendo or the official Eden project. Report problems on this repository's [Issues](https://github.com/eden-nx2/eden-nx2/issues).
