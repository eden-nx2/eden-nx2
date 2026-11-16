# Updating from Lichii and Hokkaido

`nx2` follows [Lichii](https://github.com/zortos293/Lichii)'s `nx2` builds and takes
[Hokkaido](https://github.com/pavelgajdo/Hokkaido)'s own Android changes. Lichii names files,
directories, identifiers and strings after itself; `tools/eden-nx2/apply_branding.py` turns
them into Eden / Eden NX2, so an update only needs its real code conflicts resolved by hand.

## A new Lichii build

```sh
git fetch https://github.com/zortos293/Lichii refs/tags/build-NNN:refs/tags/lichii-build-NNN
git merge --no-ff lichii-build-NNN
```

Resolve the conflicts:

- **Branding only** (names, icons, ids, translations `dist/languages/*.ts`, Android
  `strings.xml`, docs, `AGENTS.md`): take Lichii's side, `git checkout --theirs -- <file>`.
  The script renames it afterwards.
- **`README.md`, `docs/MIRROR_CHANGES.md`, `docs/RELEASING.md`, `docs/UPDATING.md`**: ours.
- **Code**: merge by hand. Most conflicts are with the Hokkaido changes listed in
  [MIRROR_CHANGES.md](./MIRROR_CHANGES.md); keep both sides' intent. Lichii often ports the same
  fix later; then prefer Lichii's version.
- Paths Lichii renamed that we renamed again (Lichii's `src/lichii` is our `src/eden`) usually
  merge on their own. A file our side changed that Lichii deleted (`UD`) is normally one it moved:
  remove ours.

Then:

```sh
python3 -I tools/eden-nx2/apply_branding.py   # renames, Eden NX2 names, checks
git add -A
git commit                                    # the merge commit; list the code conflicts
```

The script ends with checks: no path or text may still say Lichii (except upstream links and
Lichii's compatibility site), build files must not point at missing assets, and no
`std::atomic_ref` may appear outside the tests: Lichii only builds for desktop, and Android's
libc++ lacks it (use `__atomic_load_n` and friends). No Android resource may be defined twice
in one `values*` folder either: Lichii keeps Eden's synthwave theme (`eden_error`, `EdenSlider`,
`Theme.Eden.Main`) beside its own, so the script names Lichii's own `EdenNX2` (`eden_nx2_error`,
`Theme.EdenNX2.Main`); a new clash gets a line in `ANDROID_SPECIFIC`. A merge can also repeat a
block both sides added: delete one copy. And every string the app uses must be defined: taking one side's
`strings.xml` drops the other side's strings, so add those back. Fix what it
reports, rerun it until it passes, and build before pushing (`build-check` runs on every push).

## Hokkaido commits

Hokkaido is a fork of Lichii. Take only its own commits: the ones it copied from Lichii PRs
arrive with the Lichii merges.

```sh
git fetch https://github.com/pavelgajdo/Hokkaido nx2:refs/remotes/hokkaido/nx2
git log --no-merges --format='%h %an | %s' <last picked>..hokkaido/nx2
git cherry-pick -x <commit>...
python3 -I tools/eden-nx2/apply_branding.py && git add -A && git commit --amend --no-edit
```

Skip Hokkaido's branding, release notes and CI commits, and pairs that revert each other.
Hokkaido's code still uses names Lichii has since changed (`YUZU_PAGESIZE` is `EDEN_PAGESIZE`,
`Settings::ReactiveFlushingEnabled()` is `Settings::ReactiveFlushing()`, ...): use ours.

Record what was merged and picked in the tables of [MIRROR_CHANGES.md](./MIRROR_CHANGES.md).
