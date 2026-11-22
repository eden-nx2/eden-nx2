# What this branch contains

This branch is built from three repositories:

| Source | What it adds |
|---|---|
| [Eden NX2](https://git.eden-emu.dev/Takoroka/eden-nx2) (Takoroka) | Switch 2 support on top of Eden (base: Eden `26a03d247`, Oct 26 2026) |
| [Lichii](https://github.com/zortos293/Lichii) `nx2` @ `7e2316d42` ([build 366](https://github.com/zortos293/Lichii/releases/tag/build-366), Nov 20 2026) | ~1,200 commits after the Eden base: all of Takoroka's NX2 work, Eglantine's, and Lichii's own renderer, CPU and compatibility work |
| [Hokkaido](https://github.com/pavelgajdo/Hokkaido) `nx2` @ `feb08ad9f` (0.7.1, Nov 21 2026) | Fork of Lichii (split at `09b78bd2d`, Nov 6) with Android phone work, and Banana Native as a second Android engine |

Hokkaido is a fork of Lichii, and Lichii already contains Eden NX2, so the branch is
**Lichii's tip, then Hokkaido's own commits cherry-picked on top (`-x`, so each one names its
source), then one commit restoring Eden's branding.** Newer Lichii builds are merged in
(`git merge`), and `tools/eden-nx2/apply_branding.py` renames any new Lichii references to Eden
(see [UPDATING.md](./UPDATING.md)).

| Lichii build | Merge commit | Brings |
|---|---|---|
| build 160 (`2eb5f08`) | `df68f409` | LAN play for local wireless games without a room (#1359; works with Lichii and ldn_mitm), game list row layout, folder counts, title IDs and favourite stars (#1371), quieter would-block socket logs (#1384) |
| build 228 (`be1f4d9`) | `85f3f2fd` | 245 commits: Ocarina FPS Unlock fixes, solid NSZ/NCZ, Mario Kart World 60 FPS mode, Intel/AMD/NVIDIA shader and hang fixes, texture/buffer cache memory work, trigger rumble, SteamGridDB art, GPU trace record/replay, crash and hang diagnostics. Lichii renamed its sources (`src/lichii`, `io.github.zortos293.lichii`, `LICHII_*`); here they are `src/eden`, `io.github.zortos293.eden`, `EDEN_*` |
| build 316 (`5a66c89`) | `08c491c9` | 141 commits: Nextendo online play (friends, presence, one account per user profile), IPv6 sockets, frame pacing that counts game frames, Fire Emblem's DLSS replacement temporal by default, DK Bananza tutorial video fix, Ocarina FPS Unlock and No sharpening fixes, GPU address space leak fix (FF Resonance), solid NCZ and ticket fixes, Switch 1 add-ons on Switch 2 Editions, texture cache budget and scaling-limit fixes, optional Nsight Aftermath test builds and Windows symbols. Its conflicts were re-merged with both sides branded first, so only real code differences were resolved by hand |
| build 341 (`9c9a1df`) | `cc826411` | 145 commits: The Duskbloods' black and rainbow textures (GPU-written images written back through their own address space, keeping newer data), Switch Sports Resort's black water, OoT's handheld upscale pass replaced with FSR/DLSS, Switch 2 Pro Controller over Bluetooth on Windows, new shader instructions (Orbitals and others), shaders no longer waited for by default (brief pop-in instead of a freeze; Graphics → Advanced), Nextendo account and Gamesync fixes, VP9 decoding fix, MoltenVK query path. Hokkaido's batched collector downloads gave way to Lichii's reworked write-backs (see below) |
| build 366 (`7e2316d`) | `efbddfa2` | 181 commits: per-game Precompile All Shaders, Switch 2 Edition boot and update fixes, Ocarina FPS Unlock fixes (physics, Deku webs, day/night, Epona, cloth), FSR 3.1 for The Duskbloods, Xenoblade fixes, safer GPU memory on small cards, native NCE validated on Linux ARM64, opt-in Hypervisor CPU on Apple Silicon, iiDB artwork, Ryujinx save linking. Lichii's NCE page protection runs off Android; Android keeps the signal-handler path with Hokkaido's NCE fixes, and NCE stays Android's default CPU backend (Lichii made Dynarmic the default on every ARM64 build) |

Hokkaido commits picked after the first import (Nov 15 2026): `04096bcc` (free the scale helper's views and framebuffers with their original), `cf1501f5` (`EDEN_NX2_GPU_LIST_DUMP`), `5e2c4b38`, `74cb62b5`, `a1f6cd13` (NCE loads from GPU-cached pages taken as reads, Pokémon Pokopia only: its sky renders), `d4df53b3` (reactive flushing on for Pokopia under NCE, folded into `Settings::ReactiveFlushing()`), and Lichii #1825 (`1ecdc49d`). Not taken: `31f7a2c3` (Lichii's batched ASTC decode already submits once after its last batch), `7e410b85` + `d029ce76` (revert each other), `c8d7f3fa` (fixes a test list this tree does not have), docs and CI.

Hokkaido 0.7.0 (Nov 15–17 2026), picked after build 316:

- **Banana Native** (`44cf0048` and 26 follow-ups up to `8d935d8e`): [Banana Native](https://git.eden-emu.dev/moceure/BananaNative) (MIT) built into the Android mainline flavor as a second engine. Game properties has an **Engine** item: Auto, Eden NX2 or Banana Native; Auto picks Banana Native for the games its profiles call playable (Donkey Kong Bananza among them). Banana Native runs the game's code natively and draws NVN with Vulkan; it uses this app's GPU driver, save folder and resolution scale, shows this app's in-game menu, and imports packs exported from its own app. Its Android sources are in `src/android/banana/upstream` (v0.3.41); its native libraries come from its release APK, which the build downloads once and checks against the SHA-256 in `src/android/banana/UPSTREAM.txt`.
- Frame skip (from Hokkaido PR #19): `EDEN_NX2_FRAMESKIP`, `_PARTS`, `_AUTO` (drops only late frames), per-title values for it and `EDEN_NX2_FPS_LIMIT`, draws that write memory always kept, and a per-game Frame skip setting (0–3) on Android.
- Colour targets a draw samples are copied first (`cd48347d`, *adapted* to Lichii's delta draws: fixes DK Bananza's tiled video panel on RADV); disjoint query copies with declared binding strides (`c5cfb2bc`).
- Save delete and import also clear Banana Native's save transaction files.

Hokkaido 0.7.1 (Nov 18–21 2026), picked after build 366: Banana Native v0.3.45 with its options in Game properties (full vegetation, RAM saver, soft textures) and Build all shaders now, Prepare in its own work folder, games started from the dump this app opens, Try anyway packs starting without preparing again, games with an older shader library that still plays starting, and Pokemon Pokopia's presents waiting for the previous frame only. Its ports of Lichii fixes are in build 366 already, and `f0941a20` (occlusion reads timed from their tick) was already done at the build 228 merge.

Not taken from 0.7.0, because build 316 already has them: `b1e4264a` and `47972ec1` (NCE XZR and SIGILL), `eabbeb6d` (syncpoint mirror, #844), `93f57a50` (guest RAM released on stop), `3f8de877`, `e9054534`, `d7b38358`, `f6272e51` (shader fixes and their test), `846d09bc` (sparse block check), `54af45e6` and `ce219677` (Mario Kart World timestamps). Dropped at build 341: Hokkaido's batched collector downloads (one GPU wait for every GPU-written image a collector pass evicts, ~110 ms a second in Ocarina of Time on phones). Lichii rewrote that path to write each image back through its own address space and keep newer data; the batch wrote back through the bound channel's memory, which is what Lichii fixed, so Lichii's version is kept. Hokkaido's Ocarina-only limit on skipping the upload of render targets a clear overwrites stays.

Left out: `a85ba18b` (write protection dropped for pages the CPU rewrites every frame), whose page-tracking state does not cover the tracking paths Lichii added since, so mixing the two could leave pages stale. Hokkaido's docs are not taken.

## Lichii (everything up to its `nx2` tip)

All of Lichii is included. Some of the larger performance items:

- GPU thread split (front/back), drain-free semaphore acquires, async read-back, cheaper per-draw
  work on the back thread (DK Bananza 11.65 → 14.83 fps).
- Shader cache v2 as the Vulkan pipeline cache, shader precache before game start, rebuilding
  evicted pipelines from kept SPIR-V, a RAM-bounded pipeline cache, and less shader-compilation work.
- HMMA on cooperative matrices (Ocarina of Time +12–24 %, RDNA4 41 → 60 fps) and an unrolled
  software HMMA fallback.
- Compute emulation of task/mesh pipelines on GPUs without `VK_EXT_mesh_shader`.
- dynarmic: per-processor reservation cache lines, plain loads for LDAR/LDAPR/LDAXR on x86.
- Texture and buffer cache: skipped redundant uploads, batched invalidation, bounded host mip
  chains, and more.
- DLSS / FSR 4 for Ocarina of Time, native HDR output, an FPS limit, frame stats overlay,
  NUP/NX archive support, LAN play without a room, and the "Eden Dark" theme (Lichii Dark).

## Hokkaido commits applied

61 of Hokkaido's 122 commits that Lichii lacks were applied. Most cherry-picked cleanly. The
ones marked *adapted* conflicted with newer Lichii code and were rewritten onto it.

**Android build and platform**
- `std::atomic_ref` replaced with compiler builtins for Android's libc++ (`3c3d73bd`, `5a535577`; *adapted* to Lichii's per-processor reservation layout).
- Adreno detection, custom drivers on non-Adreno phones (Mesa RADV on Xclipse), `.nup` games, loose ExeFS game folders, all-files access prompt, `.nomedia` in game folders, not listing loose dumps' romfs/exefs, shader-progress crash fix, language list mapping.
- AV1 movies decoded with MediaCodec; decoded-frame wake-up.
- NCE: pointer-authentication instructions neutralized; NCE layout pass sees patched modules.
- Version from `android-*` tags.

**Phone memory (all Android-only)**
- Scene loads: chunked ASTC→BC transcoding (64 MiB at a time), smaller decode-image pool, staging buffers capped at 256 MiB pooled, NUP cache 128 MiB (`b181701b`, `5497a8fe`; *adapted* to Lichii's `HostMipCount` trimming). Transcoded ASTC keeps mips ≤ 1024 px (≤ 512 px with the later commit); `EDEN_NX2_ASTC_MAX_DIM` overrides it.
- Per-game "Limit memory during scene loads" setting; GPU budget within the phone's RAM; swap given back before Samsung's low-memory killer acts; downscaled targets' originals freed.
- Lichii's PC memory trade-offs (MADV_COLD, larger staging pool, 5/8-of-budget texture collection) kept off on phones.

**Rendering and timing**
- Ocarina of Time's task/mesh draws go through compute emulation on Android (`a3657841` + `28bccc5e`, *combined*; desktop keeps native mesh draws).
- Software HMMA on RADV: quad-broadcast A operands and cheaper fp16 rounding (`4d3d2446`, *adapted*: kept Lichii's unrolled K8 structure and added these two parts; 115 → 58 ms/frame on Xclipse).
- OoT movie frames scaled instead of running its network; OoT anti-aliasing setting.
- Fire Emblem: Fortune's Weave: DLSS prepass of its 131072 update recognized (*adapted*), temporal replacement on Android, frame-ahead present.
- Pokémon Pokopia and PRAGMATA buffer copy-back rules; Pokopia and Fire Emblem present a frame ahead; frame-ahead setting (Auto/Off/On).
- Occlusion queries polled instead of waited on forever, and given up shortly once the GPU passed their bank (fixes freezes/0 fps on Xclipse RADV).
- Semaphore acquires drain the back's queued work without waiting for its idle submit (`a2ce678a`, *adapted*).
- Garbage-collector evictions downloaded behind one GPU wait.
- Held guest writes joined per page before applying (*adapted* inside Lichii's lock).
- Clear-overwritten render-target upload skip limited to Ocarina of Time (Fast Fusion black screen).
- vi2 `GetRecordedTimestamps` (Orbitals / Unreal frame tracking); 1080p maximum buffer reported on Android.

**Diagnostics**
- `EDEN_NX2_HANG_WATCH`, `EDEN_NX2_SPIRV_DUMP`, `EDEN_NX2_WAIT_TRACE`, `EDEN_NX2_COMPUTE_KEYS`, `EDEN_LOG_FILTER`, env.txt on Android, per-pipeline driver statistics, device-loss and memory-growth diagnostics.

## Hokkaido commits not applied

| Commits | Reason |
|---|---|
| `8a1f9e1e`, `2d7ce100` (macro DMA read snapshots, OoT preflush) | Lichii solves the same drains with `WaitForWrites` and async read-back; both at once would conflict. |
| `35a5ced4`, `46626b64`, `58a03f21` (semaphore acquire work) | Superseded by Lichii's drain-free acquires (#949) and semaphore mirror (#979). |
| `2f19d915` (1 ms acquire valve) | Already in Lichii. |
| `b17d8ba7` (sysmix PurgeStream) | Already in Lichii. |
| `9cda6158` (skip OoT mesh draws on Xclipse) | Replaced by the compute emulation above. |
| `5360c8e7` + revert `a070e080`, `5d1ce7d9` + revert `5d615faf` | Each pair cancels out. |
| Branding, README, release notes and Hokkaido CI commits | Replaced by Eden branding; `android.yml` was taken over with Eden names. |
| ~30 commits Hokkaido took from Lichii PRs | Already in Lichii (often newer). |

## Name and branding

Released as **Eden NX2**, with Eden's artwork, so it installs next to official Eden:

- Name "Eden NX2" in window titles, About, menus, dialogs and translations, the Linux desktop entry
  and AppStream data, the macOS bundle (`com.eden.eden-nx2`) and the Windows installer. The
  executables keep Eden's file names (`eden`, `eden-cli`, `eden-room`, `eden-updater`).
- Linux desktop, MIME and AppStream id `com.eden.eden_nx2`; the MIME package registers `.nup`,
  `.nsz` and `.xcz` too.
- Data in its own `eden-nx2` folders. On first launch it offers to import an official Eden profile
  (keeping its shader caches) or a Citron, Sudachi, Suyu or yuzu one. The importer runs only while
  Eden NX2 has no configuration yet.
- Android: application id `com.eden.eden_nx2`, app name Eden NX2 (Optimized keeps its
  `com.miHoYo.Yuanshen` package spoof; Legacy is `com.eden.eden_nx2_legacy`).
- Lichii's dark theme is the optional **Eden Dark** theme; Eden's default theme is the default.
- In-game defaults stay "Eden": the profile username, the console's device name and the network
  nickname, which games see, and the "Exit Eden" hotkey's settings key.
- Updater: follows `EDEN_UPDATE_REPO` (CI sets it to the repository that builds the release);
  empty for local builds. Release assets are `eden-<platform>`.
- Kept on purpose: credit links to upstream PRs, internal macro names (`EGLANTINE_STREAMLINE`, ...),
  and the in-app compatibility report/search, which still uses Lichii's site `lichii.zortos.me`.
