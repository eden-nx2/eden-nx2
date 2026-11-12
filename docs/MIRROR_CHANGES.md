# What this branch contains

This branch is built from three repositories:

| Source | What it adds |
|---|---|
| [Eden NX2](https://git.eden-emu.dev/Takoroka/eden-nx2) (Takoroka) | Switch 2 support on top of Eden (base: Eden `26a03d247`, Oct 26 2026) |
| [Eden](https://github.com/zortos293/Lichii) `nx2` @ `5b0954691` (Nov 11 2026) | ~1,200 commits after the Eden base: all of Takoroka's NX2 work, Eglantine's, and Eden's own renderer, CPU and compatibility work |
| [Eden](https://github.com/pavelgajdo/Eden) `nx2` @ `32c408955` (Nov 11 2026) | Fork of Eden (split at `09b78bd2d`, Nov 6) with Android phone work |

Eden is a fork of Eden, and Eden already contains Eden NX2, so the branch is
**Eden's tip, then Eden's own commits cherry-picked on top (`-x`, so each one names its
source), then one commit restoring Eden's branding.**

## Eden (everything up to its `nx2` tip)

All of Eden is included. Some of the larger performance items:

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
  NUP/NX archive support, and the "Eden Dark" theme (Eden Dark).

## Eden commits applied

61 of Eden's 122 commits that Eden lacks were applied. Most cherry-picked cleanly. The
ones marked *adapted* conflicted with newer Eden code and were rewritten onto it.

**Android build and platform**
- `std::atomic_ref` replaced with compiler builtins for Android's libc++ (`3c3d73bd`, `5a535577`; *adapted* to Eden's per-processor reservation layout).
- Adreno detection, custom drivers on non-Adreno phones (Mesa RADV on Xclipse), `.nup` games, loose ExeFS game folders, all-files access prompt, `.nomedia` in game folders, not listing loose dumps' romfs/exefs, shader-progress crash fix, language list mapping.
- AV1 movies decoded with MediaCodec; decoded-frame wake-up.
- NCE: pointer-authentication instructions neutralized; NCE layout pass sees patched modules.
- Version from `android-*` tags.

**Phone memory (all Android-only)**
- Scene loads: chunked ASTC→BC transcoding (64 MiB at a time), smaller decode-image pool, staging buffers capped at 256 MiB pooled, NUP cache 128 MiB (`b181701b`, `5497a8fe`; *adapted* to Eden's `HostMipCount` trimming). Transcoded ASTC keeps mips ≤ 1024 px (≤ 512 px with the later commit); `EDEN_NX2_ASTC_MAX_DIM` overrides it.
- Per-game "Limit memory during scene loads" setting; GPU budget within the phone's RAM; swap given back before Samsung's low-memory killer acts; downscaled targets' originals freed.
- Eden's PC memory trade-offs (MADV_COLD, larger staging pool, 5/8-of-budget texture collection) kept off on phones.

**Rendering and timing**
- Ocarina of Time's task/mesh draws go through compute emulation on Android (`a3657841` + `28bccc5e`, *combined*; desktop keeps native mesh draws).
- Software HMMA on RADV: quad-broadcast A operands and cheaper fp16 rounding (`4d3d2446`, *adapted*: kept Eden's unrolled K8 structure and added these two parts; 115 → 58 ms/frame on Xclipse).
- OoT movie frames scaled instead of running its network; OoT anti-aliasing setting.
- Fire Emblem: Fortune's Weave: DLSS prepass of its 131072 update recognized (*adapted*), temporal replacement on Android, frame-ahead present.
- Pokémon Pokopia and PRAGMATA buffer copy-back rules; Pokopia and Fire Emblem present a frame ahead; frame-ahead setting (Auto/Off/On).
- Occlusion queries polled instead of waited on forever, and given up shortly once the GPU passed their bank (fixes freezes/0 fps on Xclipse RADV).
- Semaphore acquires drain the back's queued work without waiting for its idle submit (`a2ce678a`, *adapted*).
- Garbage-collector evictions downloaded behind one GPU wait.
- Held guest writes joined per page before applying (*adapted* inside Eden's lock).
- Clear-overwritten render-target upload skip limited to Ocarina of Time (Fast Fusion black screen).
- vi2 `GetRecordedTimestamps` (Orbitals / Unreal frame tracking); 1080p maximum buffer reported on Android.

**Diagnostics**
- `EDEN_NX2_HANG_WATCH`, `EDEN_NX2_SPIRV_DUMP`, `EDEN_NX2_WAIT_TRACE`, `EDEN_NX2_COMPUTE_KEYS`, `EDEN_LOG_FILTER`, env.txt on Android, per-pipeline driver statistics, device-loss and memory-growth diagnostics.

## Eden commits not applied

| Commits | Reason |
|---|---|
| `8a1f9e1e`, `2d7ce100` (macro DMA read snapshots, OoT preflush) | Eden solves the same drains with `WaitForWrites` and async read-back; both at once would conflict. |
| `35a5ced4`, `46626b64`, `58a03f21` (semaphore acquire work) | Superseded by Eden's drain-free acquires (#949) and semaphore mirror (#979). |
| `2f19d915` (1 ms acquire valve) | Already in Eden. |
| `b17d8ba7` (sysmix PurgeStream) | Already in Eden. |
| `9cda6158` (skip OoT mesh draws on Xclipse) | Replaced by the compute emulation above. |
| `5360c8e7` + revert `a070e080`, `5d1ce7d9` + revert `5d615faf` | Each pair cancels out. |
| Branding, README, release notes and Eden CI commits | Replaced by Eden branding; `android.yml` was taken over with Eden names. |
| ~30 commits Eden took from Eden PRs | Already in Eden (often newer). |

## Eden branding

- Executables `eden`, `eden-cli`, `eden-room`, `eden-updater`; Windows/macOS/Linux icons, installer,
  desktop entry, MIME package (now with `.nup`, `.nsz`, `.xcz`) and AppStream data are Eden's.
- About dialog is Eden's; "Eden Dark" is now the optional **Eden Dark** theme, and Eden's default
  theme is back. The first launch no longer switches theme.
- Data in Eden's folders. The profile importer runs only when Eden has no configuration yet, and
  never imports "Eden into Eden".
- Android: application id `dev.eden.eden_emulator`, app name Eden.
- Updater: follows `EDEN_UPDATE_REPO` (CI sets it to the repository that builds the release);
  empty for local builds.
- Kept on purpose: credit links to upstream PRs, internal macro names (`EGLANTINE_STREAMLINE`, ...),
  and the in-app compatibility report/search, which still uses Eden's site `lichii.zortos.me`.
