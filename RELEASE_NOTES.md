**Eden NX2 1.0.0**: the first versioned release of Eden NX2, an unofficial, experimental build of Eden with Switch 2 support. It is Eden NX2 (Takoroka) continued in Eglantine and [Lichii](https://github.com/zortos293/Lichii) **build 366**, with the Android work of [Hokkaido](https://github.com/pavelgajdo/Hokkaido) **0.7.1**, for Windows, Linux and Android. This is not an official Eden release.

> [!IMPORTANT]
> **Android: the earlier APKs (build-5 and build-7) closed right after opening.** This release fixes that. Install it over build-7 as usual.
>
> **Desktop: builds 5 and 7 won't offer this update themselves**, because releases now have version numbers. Download 1.0.0 once from this page; from then on the built-in updater offers new versions.

### Before you start

- **Installs next to official Eden.** Eden NX2 has its own app ID (`com.eden.eden_nx2` on Android) and its own `eden-nx2` data folders, so it doesn't touch an existing Eden installation.
- **Bring your settings over.** On the first desktop launch, Eden NX2 offers to import an Eden, Citron, Sudachi, Suyu or yuzu profile. *Keep Old Data* (copy) is the safe choice and leaves the original untouched.
- **Your own files are needed.** Dump your keys, firmware and games from your own console. None are included or provided.

### What's new since build 7

**Android**
- **Fixed: the app closing on launch.**
- **Banana Native v0.3.45**, the second engine (Game properties → **Engine**: *Eden NX2*, *Banana Native* or *Auto*). Its new options are in Game properties: full vegetation, RAM saver, soft textures, and **Build all shaders now**. Starts are smoother, including games whose shader library is older than expected.
- Pokémon Pokopia paces frames better.
- The CPU stays on **NCE** (native execution) by default.

**All platforms**
- **Precompile All Shaders** for each game: build its shaders before playing, instead of stuttering through them.
- Switch 2 Edition games boot and update more reliably.
- Ocarina of Time FPS Unlock: physics, Deku webs, day/night speed, Epona's fence jumps and cloth are fixed.
- The Duskbloods: FSR 3.1, and its black and rainbow textures fixed.
- Switch Sports Resort's black water fixed; Xenoblade fixes.
- Safer GPU memory use on graphics cards with little VRAM.
- The Switch 2 Pro Controller works over Bluetooth on Windows.
- Shaders that are still compiling no longer freeze the game by default: you may see brief pop-in instead (Graphics → Advanced).
- **Version numbers**: the title bar and About show *Eden NX2 1.0.0*, and the updater follows versions from now on.

### Known issues

- Switch 2 support is **experimental**: many games have rendering errors, stutter or crash, and performance varies a lot between devices.
- Banana Native supports only some games, and only on Android.
- No macOS build in this release.

### Credits

Takoroka and the Eden team (Eden NX2), Eglantine, zortos293 and Lichii's contributors, pavelgajdo and Hokkaido's contributors, and moceure (Banana Native, MIT). Eden NX2 is GPL-3.0. What came from where: [MIRROR_CHANGES.md](https://github.com/eden-nx2/eden-nx2/blob/nx2/docs/MIRROR_CHANGES.md).

Optional online play goes through **Nextendo**, a third-party online service (not Nintendo), and is off by default (Configure → Network). Eden NX2 is not affiliated with Nintendo or the official Eden project. Report problems on this repository's [Issues](https://github.com/eden-nx2/eden-nx2/issues).
