# Lichii changes on top of Eden NX2

This mirror is Eden NX2 with every change Lichii (github.com/zortos293/Lichii) made on top of it.

## Lineage

```
Takoroka's Eden NX2  ──►  Djihads80/Eglantine (nx2)  ──►  zortos293/Lichii (nx2)
(git.eden-emu.dev)        public GitHub home of the       forked from Eglantine
                          NX2 work. Takoroka/Mako is
                          its main author (442 commits
                          since June 2026)
```

* **Base Lichii builds on:** Eglantine `nx2` @ `e736dfde9e45bc3640935e373927814883803d95`
  (2026-11-05, "Merge PR #567: kernel: Fix OOT startup by implementing WaitIfEqual64").
  This mirror has a tag for it: `eden-nx2-base`.
* **Lichii tip covered:** `a7a281757` (2026-11-06).
* **Size of the change:** 169 commits (141 non-merge). 562 files changed, +61,951 / −4,833 lines.
  * Emulator source (`src/`, excluding tests): ~333 files, ~+21k lines
  * Tests (`src/tests/`): 118 files, +23.8k lines
  * Tooling (`tools/`): 41 files, +12.6k lines
  * Docs, CI and markdown: 56 files

## Branding in this mirror

Branch `main` carries the original **Eden** branding. Every functional Lichii change is kept.
Branch `lichii-nx2` is Lichii exactly as published, with Lichii branding.

* App name, window titles, dialogs, About box and Discord text say **Eden**. The executables
  are `eden` / `eden.exe` / `eden.app` (plus `eden-cli`).
* Eden's original logo files are restored: `eden.ico`, `eden.icns`, the 256px `eden.png`
  and the `dev.eden_emu.eden` SVG, desktop and metainfo files. Eglantine had replaced their
  contents and Lichii had added its own. The Lichii artwork, the icon generator and the
  unused `eglantine.icns` are removed. Translations already used "Eden" and now match the
  source strings again.
* The updater is off unless `-DENABLE_UPDATE_CHECKER=ON`. Lichii's hard-coded
  `zortos293/Lichii` is replaced by the `EDEN_UPDATE_REPO` CMake option. Release assets
  are named `eden-<platform>`. Lichii's `LICHII_BUILD_TAG` / `LICHII_BUILD_PLATFORM` are
  now `EDEN_BUILD_TAG` / `EDEN_BUILD_PLATFORM`. The release workflow passes
  `${{ github.repository }}`, builds on pushes to `main`, and publishes `eden-*` files.
* The README is rewritten for Eden. Lichii's crypto donation addresses are removed and
  Lichii is credited. The Arch package is renamed `eden-nx2-git`: set `_repo_url` in its
  PKGBUILD.
* Left unchanged on purpose: internal identifiers that users never see
  (`EGLANTINE_STREAMLINE`, `EGLANTINE_*` env vars, `eglantine.profdata`,
  `dist/eglantine.entitlements`), `cpmfile.json`'s prebuilt FFmpeg download from
  Djihads80/Eglantine, and developer notes in `docs/` and `AGENTS.md`.

## How to use the deliverables

| File | What it is |
|---|---|
| `eden-nx2-lichii.bundle` | Full git mirror. Branch `main` is Lichii with Eden branding + this file, branch `lichii-nx2` is exact Lichii, tag `eden-nx2-base` is the Eden NX2 base. |
| `lichii-vs-eden-nx2-base.diff` | One combined patch: base → Lichii. Verified to apply cleanly to `e736dfde9`, and the result is byte-identical to Lichii's tree (`df0288618…`). |
| `reference-patches/` | 141 per-commit patches. These are for reading. They do **not** replay in order with `git am`, because Lichii wrote many of them on older bases and merged them in. Use the combined diff or the bundle to apply. |

```sh
git clone eden-nx2-lichii.bundle eden-nx2-lichii
cd eden-nx2-lichii
git remote set-url origin <your new empty repo URL>
git push origin --all && git push origin --tags
```

To apply on your own Eden NX2 checkout instead:

```sh
git checkout e736dfde9            # or the nearest equivalent commit
git apply --index lichii-vs-eden-nx2-base.diff
```

## Upstream changes newer than Lichii's base (not included)

Eglantine `nx2` has 9 commits after `e736dfde9` that Lichii has not merged. A test merge
conflicts in 4 files:

* `src/video_core/buffer_cache/buffer_cache.h`: upstream #571 (re-applies "size native
  storage buffers from the mapping only in DLSS passes") and the scheduler-owner guard,
  against Lichii's rewritten `StorageBufferBinding` / indirect SSBO binding and
  `EDEN_NX2_INLINE_PAGE_WRITES`
* `src/video_core/engines/kepler_compute.h`: upstream `IsDlssDispatch` /
  `EmulatesNativeTensorPrograms` against Lichii's `ProcessGameDlss` / `TraceDlssContract`
* `src/video_core/gpu_split.cpp`: upstream #639 (split-scheduler races, `BackQuiet`, idle
  hook) against Lichii's `Drain(DrainSite)` profiling and `Backoff`
* `README.md`

These are logic conflicts in the GPU split and buffer cache. They need to be resolved and
then tested on real hardware, so this mirror leaves them out.

## What Lichii changed, by area

Commit hashes are Lichii's. Several fixes appear twice because Lichii re-applied them
after an upstream merge.

### 1. Performance
* `badc91ebc`: less NX2 pipeline and cache-tracking overhead. Indexed JIT invalidation
  ranges, batched page updates, native driver compiles queued on pipeline workers.
* `5f6066b26` (#184): promotes the 17 enabled "perf9" fast paths. 79 files. Also brings
  frame-dump buffer, sampler and launch records, and debug-server memory tools.
* `b06d1818e` (#194): Frizti port covering buffer, submission and pipeline-cache
  performance, plus FSR3 fixes.
* GPU split thread: `9479d8fa6` (#54) stops draining before every DMA entry. `9960298c9`
  (#59) moves front-side buffer flushes to the back thread. `e3d8e9c60` (#70) drains
  command pages only after a write.
* Buffer and texture cache: `3165a2b6f` (#103) uploads CPU writes onto GPU pages instead of
  downloading them. `0bf0f834b` (#104) downloads only Preflushable pages. `7c0b5e791` (#146)
  caches MaxContinuousRange. `27226d256` (#141) skips the upload of a render target that a
  clear overwrites entirely. `5a50f50e0` (#193).
* `d1b7f69df`: isolated pipeline-cache files and block-aware eviction.
* `3b1d6eb9c` (#22): Apple silicon. Fastmem on 16 KiB-page hosts, POSIX shm backing,
  `EDEN_NX2_FPS_LOG`.
* `f8f29b576` (#71): faster shader translation. Pipelines a draw waits on are built inline.
* `a7a281757` (#224): opt-in `EDEN_NX2_ASYNC_DISCARD`, which skips native draws whose
  pipeline isn't built yet.
* Timing and pacing: `9feec2650` (#120) and `90e2d85bb` (#137) fix early MWAITX/UMWAIT
  wakes. `c9bdbaa38` (#290) fixes a lost wake when worker tasks are cancelled. `b785035c5`
  and `7b953db74` (upstream #547) pace presents. `2940d9fab` (#84) uses native ns
  timestamps on Ampere.

### 2. Game-specific fixes
* **Mario Kart World:** `d7ce3d8c0` paged memory and GPU sync stalls. `5bef7354b` readback
  sync and present pacing.
* **Fire Emblem Fortune's Weave:** `62bc7538b` black screen and shader gaps. `b9d6b4a71`
  upscaler jitter.
* **Mario Tennis Fever:** `3140648a6` (#7) boot and rendering. `38337aa38` (#38) device loss
  on the first court. `de8e2defd` framebuffer layers and clear ranges. Tessellation lowering
  in `7a9dd3710`/`28cf8e206` and `078b720a2`/`be5bff1d3`.
* **Star Fox:** `4a58842d2`/`7c27fdec8` direct ULDC.U16. `2c1314aa0` DLSS spatial passes.
  `0f9b1c31e` cutscene colours.
* **Kirby Air Riders:** `546cb6782` (#56) white world, geometry, FMUL and atomic pointers.
  `f8f29b576` (#71).
* **Yoshi:** `fe8d0dda8` (#66) IMNMX and buffer reductions, plus the Yoshi shader forms in #62.
* **Donkey Kong Bananza:** `8644f32fb` pointer fixes and diagnostics. `ca6b8e3ca`/`e942bb7e8`
  routed global atomic add.
* **"Green-hat kid adventure":** `c9ebb5c3a` (#87) boot and render. `b8c812b18` (#112)
  1080p upscaler gathers. `f26ad432a` (#150).
* **Ocarina of Time (Switch 2):** `b32847784` (#100) door NaN. `5b89a3daf` (#121) white
  flashes. `f356d39ba` (#144) compute forms. `18cfe8556` (#295) misaligned 64/128-bit SSBO.
  Built-in **Max Resolution** patch `99ac3d595` (#124) and **Dark's Dynamic FPS** mod
  `55532f2d8` (#240).
* **AMD GPUs:** `77cbc3e7c` (#127) black scenes. `68caa6c75` (#273) shader typing, atomic
  retries and compiler stalls.

### 3. Shader recompiler (general)
`d48f75bdf` BRA.U (#55), `cf1beed4b` Ampere register-handle image ops (#57), `325e0a867`
uniform shifts (#58), `d9748a5d5` dynamic texture arrays (#65), `1ca9f7d5c` geometry EXIT
(#74), `a6e5217c7` 32-bit global ATOM.CAS (#140), `448fdaed9` atomics behind unmapped
pointers (#47), `95e06df7a` experimental NV cooperative-matrix lowering (#5). SPIR-V fixes
`07a155d7b`, `8ff071d33`, `af0b66e57`, `c1fcb8dab`, `b3a015e12` (#69).

### 4. Vulkan and renderer robustness
`11a110898` readback coherence, texture strides and packed-draw state. `fae3bc64a` GPU
command snapshot selection. `724aede4e` (#89) depth-stencil copies. `509809ddf` (#88) and
`6b7c516b0` (upstream #427) swapchain recreation. `ec3a455c6` (upstream #544) alpha-test
key. `fe6a388a4` (#142) drops draws after a failed pipeline. `f2b81dfaa` (#67) Aftermath
dumps. `86efad39c`, `87a86486d`.

### 5. Video, audio, services and core
`45e4cfb18` NX2 video fixes. `add792212` (#49), `5c6153bc1` (#48) and `f12b753f0` (#52)
video readiness. `7e1d64555` (#138) VP9 movie crash, and eden-cli can restart a game.
`e219d24c5` (#284) AV1 on MinGW. `4f9b9624b` (#75) S16 system mixer audio. `c6ac0566e` (#64)
streamplay HID cmd 300. `0db1cdbd8` (#119) Switch 2 XCI clock detection. `703da11d1`
(upstream #543) nvdrv command lists. `286117c7e` (#77).

### 6. Large integration and upstream-port commits
`60ef6d275` "COMMIT TO nx2 source" (214 files, +11.7k: Lichii's initial bulk import of its
fixes). `4285e58d0` (#15) build 104 integration. `8cd5c6a44`, `b3f983a6b` and `f6a8f5132`
re-apply fixes on a refreshed base. `d5b258210` (#62) ports Eglantine PRs #505, #475, #329
and #123.

### 7. Debugging and tooling (opt-in, no effect on normal play)
`30d6061f5` (#111) live debug server and MCP adapter (`EDEN_NX2_DEBUG_SERVER`). Offline
shader capture and replay tools: `09477e13a`, `51c850a79` (#60), `cc52d21d3`, `f314dc672`,
`4336c210d`. Diagnostics: `e2ccb4e17`, `ba2d291ba`, `7d9596a4b`, `5c3581501`.

### 8. Build, tests, CI and platform
macOS ARM release builds and signing: `4941b0eee`, `9a4600916`, `25239eecd`, `5bf6a5c85`,
`2a29776bb`. Windows and MSVC: `e3b4e7566` (#129), `edf916344` (#76). Tests:
`9c3493975` (#8, includes a real AVX-512 CLZ bug fix), `5868088c3`, `2801b3323`,
`a8421cebb`, `0239f29a7`. CI: `18297d7dd`, `7d7dc8d5f`, `29571ea9d`, `f176172f4`.

### 9. Branding and docs (not emulator behaviour; replaced by Eden branding on `main`)
`219ed4ff6` rebrands the desktop app to **Lichii**, and `c505c7f68` fixes the build after
it. `2818f85a8` and `7daab0992` add an updater that checks **Lichii's** GitHub releases.
README, AGENTS.md and donation edits. `62d852508` removes upstream `.github` policy files.
A plain `git revert` of the rebrand conflicts with later updater and CI commits. To
de-brand, the app name, icons and updater URL have to be edited by hand.

## Full commit list (oldest first)

| Commit | Date | Author | Subject |
|---|---|---|---|
| `60ef6d275` | 2026-10-31 | Zortos | COMMIT TO nx2 source |
| `11a110898` | 2026-11-01 | Zortos | Fix NX2 readback coherence and rendering |
| `4941b0eee` | 2026-10-31 | zortos293 | ci: add macOS ARM release builds |
| `18297d7dd` | 2026-10-31 | zortos293 | ci: run platform builds on pull requests |
| `9a4600916` | 2026-10-31 | zortos293 | build: fix explicit macOS architecture detection |
| `25239eecd` | 2026-10-31 | zortos293 | build: apply Clang source flags to AppleClang |
| `98d524f70` | 2026-10-31 | zortos293 | build: handle uncached CPU read flush results explicitly |
| `5bf6a5c85` | 2026-11-01 | zortos293 | macos: sign nested binaries before sealing the app bundle |
| `2a29776bb` | 2026-11-01 | zortos293 | macos: correct lipo architecture-check argument order |
| `badc91ebc` | 2026-11-01 | Zortos | Reduce NX2 pipeline and cache-tracking overhead |
| `ef7160cc4` | 2026-11-01 | zortos293 | README: describe Zortos fork and its fixes |
| `2818f85a8` | 2026-11-01 | Zortos | Update from GitHub releases with per-branch build names (#6) |
| `62d852508` | 2026-11-01 | Zortos | remove the claude and agents.md and codeowners stuff from github |
| `9c3493975` | 2026-11-01 | Antonio Arenas | Fix the failing unit tests (incl. an AVX-512 CLZ bug) and run them in CI (#8) |
| `95e06df7a` | 2026-11-01 | Mattscreative | Add guarded experimental NV cooperative-matrix lowering (#5) |
| `3140648a6` | 2026-11-01 | MythosOfs | Mario Tennis Fever: boot and rendering fixes (#7) |
| `4285e58d0` | 2026-11-02 | Zortos | Integrate build 104 while preserving fork fixes (#15) |
| `62bc7538b` | 2026-11-02 | Zortos | Fix Fire Emblem Fortune's Weave black screen and shader gaps |
| `d7ce3d8c0` | 2026-11-02 | Zortos | Fix paged memory access and GPU sync stalls in Mario Kart World |
| `5bef7354b` | 2026-11-02 | Zortos | Harden MKW GPU readback sync and present pacing |
| `d1b7f69df` | 2026-11-02 | Zortos | Isolate fork pipeline cache files and add block-aware eviction |
| `8cd5c6a44` | 2026-11-02 | Zortos | Restore game compatibility fixes on the refreshed upstream base |
| `b3f983a6b` | 2026-11-02 | Zortos | Checkpoint game fixes on the refreshed upstream base |
| `fae3bc64a` | 2026-11-02 | Zortos | Fix GPU command snapshot selection after synchronization |
| `219ed4ff6` | 2026-11-02 | Zortos | Rebrand desktop emulator as Lichii |
| `c505c7f68` | 2026-11-02 | Zortos | Fix nx2 release build after the Lichii rename |
| `b9d6b4a71` | 2026-11-02 | Zortos | Compensate Fire Emblem upscaler jitter without frame history |
| `3b1d6eb9c` | 2026-11-03 | Radu Ursache | macOS: Apple silicon performance and build 104 fixes (#22) |
| `7d7dc8d5f` | 2026-11-03 | Zortos | Improve release build caching and remove inherited contribution policies (#31) |
| `f69023f55` | 2026-11-03 | Zortos | Add artwork credit to README |
| `29571ea9d` | 2026-11-03 | Zortos | Fix release publishing when the branch advances during a build (#32) |
| `f6a8f5132` | 2026-11-03 | zortos293 | Preserve Windows PGO shader, audio, upscaling and NUP changes |
| `3b9016a19` | 2026-11-03 | zortos293 | Document the game bug workflow in AGENTS.md |
| `8fb43bae7` | 2026-11-03 | zortos293 | AGENTS.md: add Bananza, Kirby and Yoshi program ids |
| `60fcc6699` | 2026-11-03 | zortos293 | AGENTS.md: record the PC build command |
| `9a435b584` | 2026-11-04 | Zortos | Merge upstream Eglantine nx2 (#37) |
| `de8e2defd` | 2026-11-03 | zortos293 | vulkan: port minimum framebuffer layers and bound clear ranges |
| `8644f32fb` | 2026-11-03 | zortos293 | video_core: port pointer fixes and add Bananza frame diagnostics |
| `e2ccb4e17` | 2026-11-04 | zortos293 | video: trace mode-dependent missing meshes and shadow resources |
| `ba2d291ba` | 2026-11-04 | zortos293 | diagnostics: retain compute failures and measure host-thread CPU time |
| `7d9596a4b` | 2026-11-04 | zortos293 | shader: capture rejected native stages before the lowering gate |
| `7a9dd3710` | 2026-11-04 | zortos293 | shader: lower captured native tessellation patch operations |
| `639209a6d` | 2026-11-04 | zortos293 | AGENTS.md: escalate stuck fixes to Opus 5.5 |
| `ca6b8e3ca` | 2026-11-04 | zortos293 | shader_recompiler: implement routed global atomic add |
| `b785035c5` | 2026-11-04 | zortos293 | core: pace presents using the current requested period |
| `078b720a2` | 2026-11-04 | zortos293 | shader: lower native tessellation evaluation coordinate reads |
| `4a58842d2` | 2026-11-04 | zortos293 | shader: lower Star Fox direct ULDC.U16 loads |
| `19a4cf549` | 2026-11-04 | zortos293 | tests: document partial tennis evaluation capture provenance |
| `5c3581501` | 2026-11-04 | zortos293 | shader: capture headers and downstream stages for native rejection diagnostics |
| `87a86486d` | 2026-11-04 | zortos293 | vulkan: identify unrecorded native draws without implying queue cancellation |
| `09477e13a` | 2026-11-04 | zortos293 | tools: replay native shader captures and serialized pipeline environments offline |
| `45e4cfb18` | 2026-11-04 | zortos293 | Port upstream NX2 video fixes and trace movie presentation |
| `62eb1a695` | 2026-11-04 | zortos293 | Document upstream video audit and owner capture workflow |
| `07a155d7b` | 2026-11-04 | zortos293 | spirv: define the narrow storage CAS helper for every fallback path |
| `8ff071d33` | 2026-11-04 | zortos293 | spirv: do not infer stage-specific builtins from geometry passthrough masks |
| `28cf8e206` | 2026-11-04 | zortos293 | shader: lower captured native tessellation patch operations |
| `be5bff1d3` | 2026-11-04 | zortos293 | shader: lower native tessellation evaluation coordinate reads |
| `c1fcb8dab` | 2026-11-04 | zortos293 | shader: port coordinated storage-routing and opcode diagnostic prerequisites |
| `e942bb7e8` | 2026-11-04 | zortos293 | shader_recompiler: implement routed global atomic add |
| `f314dc672` | 2026-11-04 | zortos293 | tools: accept the serialized surface pixel-format domain in shader caches |
| `7c27fdec8` | 2026-11-04 | zortos293 | shader: lower Star Fox direct ULDC.U16 loads |
| `4336c210d` | 2026-11-04 | zortos293 | tools: pass explicit tessellation stage into native lowering |
| `af0b66e57` | 2026-11-04 | zortos293 | spirv: lower global memory helpers without descriptor aliases |
| `cc52d21d3` | 2026-11-04 | zortos293 | tools: support v48 caches and publish verified shader sweep results |
| `86efad39c` | 2026-11-04 | zortos293 | texture_cache: bound debug descriptor poisoning after upstream sync |
| `38337aa38` | 2026-11-04 | Zortos | Fix Mario Tennis Fever device loss when the first court loads (#38) |
| `0827d6bd7` | 2026-11-04 | zortos293 | Keep AGENTS.md from #36 |
| `2c1314aa0` | 2026-11-04 | zortos293 | video_core: recognize Star Fox DLSS spatial replacement passes |
| `0f9b1c31e` | 2026-11-04 | zortos293 | video_core: accept Star Fox's captured cutscene colour pair |
| `6dd32626e` | 2026-11-04 | Zortos | Merge upstream Eglantine nx2 (01dda0f47d) and port tessellation guard from #426 (#50) |
| `448fdaed9` | 2026-11-04 | Zortos | shader_recompiler: keep buffers for atomics behind unmapped native pointers (#47) |
| `add792212` | 2026-11-04 | Zortos | Keep compressed video inputs beyond the readiness watermark (#49) |
| `5c6153bc1` | 2026-11-04 | Zortos | nx2: stop video readiness notifications during teardown (#48) |
| `935c5bdb0` | 2026-11-04 | Zortos | AGENTS.md: add four more program ids (#51) |
| `f12b753f0` | 2026-11-04 | Zortos | nx2: count video inputs with Size() in the finalize trace (#52) |
| `9479d8fa6` | 2026-11-04 | Zortos | dma_pusher: stop draining the split GPU thread before every entry (#54) |
| `d48f75bdf` | 2026-11-04 | Zortos | shader: lower native BRA.U with fail-closed field validation (#55) |
| `9960298c9` | 2026-11-04 | Zortos | video_core: run front-side buffer flushes on the GPU split back thread (#59) |
| `cf1beed4b` | 2026-11-04 | Zortos | shader: lower Ampere register-handle formatted image loads and stores (#57) |
| `325e0a867` | 2026-11-04 | Zortos | shader: lower uniform shifts with an immediate high word (#58) |
| `d5b258210` | 2026-11-04 | Zortos | Port selected upstream Eglantine fixes and open PRs (#62) |
| `5868088c3` | 2026-11-04 | Zortos | tests: fix audio_core -Werror failures under clang (#61) |
| `51c850a79` | 2026-11-04 | Zortos | tools: add read-only static shader extraction and library coverage replay (#60) |
| `2801b3323` | 2026-11-04 | Zortos | tests: entries parsed without a pending acquire keep their snapshot (#53) |
| `546cb6782` | 2026-11-04 | Zortos | Kirby Air Riders: fix the white world, plus geometry, FMUL and atomic-pointer lowering and a frame dump (#56) |
| `d9748a5d5` | 2026-11-04 | Zortos | shader: preserve dynamic texture arrays with separate samplers (#65) |
| `c6ac0566e` | 2026-11-04 | Zortos | streamplay: return guest HID shared memory for command 300 (#64) |
| `fe8d0dda8` | 2026-11-04 | Zortos | shader: lower Yoshi full-width IMNMX and buffer reductions (#66) |
| `f2b81dfaa` | 2026-11-04 | Zortos | vulkan: preserve Aftermath crash dumps across launches and decode failures (#67) |
| `b3a015e12` | 2026-11-04 | Zortos | maxwell-spirv: build against the current SPIR-V emitter (#69) |
| `e3d8e9c60` | 2026-11-04 | Zortos | video_core: drain draw-written command pages only after a write, and keep fence work on the split's back (#70) |
| `f8f29b576` | 2026-11-04 | Zortos | Kirby Air Riders: faster shader translation, build waited-on pipelines inline, race diagnostics (#71) |
| `d7fd2f283` | 2026-11-04 | Zortos | video_core: release fences that carry downloads after the downloads land (#72) |
| `a3e94bf4e` | 2026-11-04 | Zortos | Revert "video_core: release fences that carry downloads after the downloads land (#72)" (#73) |
| `1ca9f7d5c` | 2026-11-04 | Zortos | shader: compare relocation targets as signed in the geometry EXIT check (#74) |
| `4f9b9624b` | 2026-11-04 | Zortos | audio: accept and play S16 system mixer output streams (#75) |
| `edf916344` | 2026-11-04 | Zortos | Fix MSVC /we warnings in Star Fox colour match and native shader lowering (#76) |
| `286117c7e` | 2026-11-04 | Zortos | core: guard the window trace session with a mutex instead of atomic<shared_ptr> (#77) |
| `2940d9fab` | 2026-11-04 | Zortos | video_core: use native nanosecond timestamps for Ampere (#84) |
| `7daab0992` | 2026-11-04 | Zortos | Fix Lichii release update checks and platform downloads (#82) |
| `c9ebb5c3a` | 2026-11-05 | Zortos | kernel, shader: boot and render the green-hat kid adventure (#87) |
| `724aede4e` | 2026-11-04 | cr1ckt | vulkan: fix invalid depth-stencil buffer copies in ReinterpretImage (#89) |
| `b32847784` | 2026-11-05 | Zarigis | shader: shadow ISBEWR data registers (Ocarina door NaN) (#100) |
| `3165a2b6f` | 2026-11-05 | Zarigis | buffer_cache: upload CPU writes onto GPU-written pages instead of downloading them (#103) |
| `0bf0f834b` | 2026-11-05 | Zarigis | buffer_cache: download only the Preflushable pages of a GPU write at fences (#104) |
| `a99ec7c19` | 2026-11-05 | Zortos | README: add crypto donation section (#107) |
| `cffe294e9` | 2026-11-05 | Zortos | README: reword the donation section (#108) |
| `a8421cebb` | 2026-11-05 | Gabe Rives-Corbett | tests: fix signed/unsigned comparisons that break MSVC builds (#97) |
| `509809ddf` | 2026-11-05 | matt petters | fix(vulkan): wait for the device before recreating the swapchain (#88) |
| `f176172f4` | 2026-11-05 | zortos293 | ci: keep Lichii's release-binaries workflow only |
| `7b953db74` | 2026-11-05 | zortos293 | nx2_gsv: pace presents for the presenting process (upstream #547) |
| `703da11d1` | 2026-11-05 | zortos293 | nvdrv: copy command lists submitted behind a queued GPU map later (upstream #543) |
| `6b7c516b0` | 2026-11-05 | zortos293 | vulkan: handle an out-of-date swapchain before acquiring (upstream #427) |
| `ec3a455c6` | 2026-11-05 | zortos293 | vulkan: round the alpha test reference to 1/255 in the pipeline key (upstream #544) |
| `0239f29a7` | 2026-11-05 | zortos293 | tests: build with GCC 13 -Werror |
| `30d6061f5` | 2026-11-05 | Zortos | debugger: opt-in live debug server and MCP adapter (#111) |
| `b8c812b18` | 2026-11-05 | Zortos | shader: lower the green-hat kid adventure's 1080p upscaler gathers (#112) |
| `5edea2b57` | 2026-11-05 | zortos293 | AGENTS: pair RenderDoc captures with live emulator diagnostics |
| `9feec2650` | 2026-11-05 | Zortos | common: retry unsignalled MWAITX wakes until the Event deadline (#120) |
| `99ac3d595` | 2026-11-05 | Zortos | file_sys: built-in Max Resolution patch for Ocarina of Time (#124) |
| `5b89a3daf` | 2026-11-05 | Kaj | vk_scheduler: pause, don't reset, the occlusion counter at each submission (Ocarina white flashes) (#121) |
| `e3b4e7566` | 2026-11-05 | resadent | cmake: define NOMINMAX for Windows clang-cl builds (#129) |
| `90e2d85bb` | 2026-11-05 | Kaj | common: retry early UMWAIT wakes until the Event deadline (#137) |
| `fe6a388a4` | 2026-11-05 | Felipe Lima | vulkan: drop draws recorded after a graphics pipeline that failed to build (#142) |
| `77cbc3e7c` | 2026-11-05 | Inc0rporate | shader: count nested returns before moving demotes to the end (AMD black scene) (#127) |
| `0db1cdbd8` | 2026-11-05 | Paulius | core: detect Switch 2 XCI metadata before selecting the guest clock (#119) |
| `7c0b5e791` | 2026-11-05 | Nyaku | video_core: cache MaxContinuousRange until the GPU page table changes (#146) |
| `7e1d64555` | 2026-11-06 | Paulo Tumabing | Fix VP9 movie crash (dirty memory gather race) and let eden-cli restart a game (#138) |
| `f26ad432a` | 2026-11-05 | Zortos | Port the green-hat kid adventure rendering fixes to nx2 (#150) |
| `f356d39ba` | 2026-11-05 | Kradnah | shader: lower the remaining Ocarina compute forms (#144) |
| `a6e5217c7` | 2026-11-06 | Paulo Tumabing | shader_recompiler: implement 32-bit global ATOM.CAS (#140) |
| `27226d256` | 2026-11-05 | Kaj | texture_cache: skip the upload of a new render target a clear overwrites whole (#141) |
| `5f6066b26` | 2026-11-06 | Zortos | video_core: promote the 17 enabled perf9 paths (#184) |
| `5a50f50e0` | 2026-11-06 | Zortos | buffer_cache: match collected range lengths to tracker size type (#193) |
| `b06d1818e` | 2026-11-06 | Zortos | video_core: port scoped Frizti performance and FSR3 fixes (#194) |
| `55532f2d8` | 2026-11-06 | Zortos | file_sys: bundle Dark's Dynamic FPS mod for Ocarina (#240) |
| `68caa6c75` | 2026-11-06 | zortos293 | shader: fix AMD shader typing, atomic retries and compiler stalls |
| `e219d24c5` | 2026-11-06 | Zortos | externals: include AV1 decoding in MinGW builds (#284) |
| `18cfe8556` | 2026-11-06 | Zortos | shader, vulkan: split misaligned 64/128-bit SSBO accesses and order compute writes (#295) |
| `c9bdbaa38` | 2026-11-06 | Zortos | common: prevent a lost wake when cancelling worker tasks (#290) |
| `a7a281757` | 2026-11-06 | Bruno Silvestre | video_core: opt-in EDEN_NX2_ASYNC_DISCARD to skip native draws with unbuilt pipelines (#224) |
