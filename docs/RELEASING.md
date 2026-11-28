# Releasing

One run of the `release-binaries` workflow builds every platform and publishes them as one GitHub
release, tagged `v<version>` and titled "Eden NX2 <version>":

| Platform | Assets |
|---|---|
| Windows x86_64 | `eden-windows-x86_64.zip`, `eden-windows-x86_64-pgo.zip` |
| Linux x86_64 | AppImage and tar.gz, standard and PGO |
| Linux aarch64 | AppImage and tar.gz (no PGO build since 1.0.0) |
| macOS (Apple Silicon) | `eden-macos-arm64.zip`, only with **Build macOS** ticked |
| Android arm64 | `eden-android-arm64.apk` |

## Publish a release

1. Push the commits you want to release to `nx2`.
2. Optional: write the highlights. Either commit them as `RELEASE_NOTES.md` at the repository
   root, or type them into the run's **notes** field (it takes priority).
3. GitHub → **Actions** → **release-binaries** → **Run workflow**, branch `nx2`:
   - **Version**: the new version as MAJOR.MINOR.PATCH, e.g. `1.0.0`. It must be higher than the
     last release, or the run stops before building. See *Choosing the version* below.
   - **Publish the binaries as a GitHub release**: on.
   - **notes**: Markdown highlights (optional).
   - **Mark the release as a pre-release**: off by default, so the release becomes "Latest". Tick
     it for a test release users shouldn't get as the main download.
   - **Build macOS**: off by default. GitHub's macOS runners have rarely been available to this
     repository, and a macOS job without one leaves the run red. Tick it to try anyway.
4. Wait for the run (about 1–2 hours; the PGO and Android builds are the longest). The release
   appears under **Releases** when Windows, Linux and Android have built; if one of them fails,
   nothing is published, so start a new run. With **Build macOS** ticked, a missing macOS build
   doesn't stop the release: it goes out without it and says so. **Re-run failed jobs** on that run
   later: once macOS builds, the re-run adds its file to the published release and changes nothing
   else.

Releases are only published from these manual runs; pushes to `nx2` are build-checked but not released.

## Choosing the version

Versions are MAJOR.MINOR.PATCH, starting at 1.0.0:

- **PATCH** (1.0.0 → 1.0.1): fixes only, such as a crash fix.
- **MINOR** (1.0.1 → 1.1.0): a new Lichii or Hokkaido merge, new features or game fixes.
- **MAJOR** (1.4.2 → 2.0.0): a big change users should notice, such as dropping a platform or
  settings that don't carry over.

The number never goes down and is never reused: the updater offers only higher versions. Releases
before 1.0.0 were tagged `build-N` and count as older than every version.

## What the notes contain

- A short experimental-status line.
- **Highlights**: the notes field or `RELEASE_NOTES.md`.
- **Changes since** the previous release (its `v*` or earlier `build-*` tag): every commit, with a comparison
  link. The first release links to [MIRROR_CHANGES.md](./MIRROR_CHANGES.md) instead.
- Download tables and install notes for Android, Linux, Windows and macOS.

Edit the notes afterwards on the release page if needed.

## Updates inside the app

Desktop builds bake in the release tag (`v1.0.0`) and `EDEN_UPDATE_REPO` (this repository), and
the built-in updater offers this repository's releases with a higher version. The app shows the
version in its title bar and About dialog; the APK's version name is the same version.

Builds from before 1.0.0 (`build-5`, `build-7`) only recognize `build-N` tags, so their updater
doesn't see 1.0.0 and later: users of those builds download 1.0.0 once by hand.

## Android signing

Without secrets the APK is signed with the public debug key in `src/android/app`. To sign with
your own key, which you should keep using for every release because Android only installs updates
signed with the same key, add these repository secrets (Settings → Secrets and variables →
Actions):

| Secret | Value |
|---|---|
| `ANDROID_KEYSTORE_B64` | `base64 -w0 release.keystore` |
| `ANDROID_KEYSTORE_PASS` | Keystore and key password |
| `ANDROID_KEY_ALIAS` | Key alias |

Create a keystore once with:

```sh
keytool -genkeypair -v -keystore release.keystore -alias eden -keyalg RSA -keysize 4096 -validity 10000
```

The app id is `com.eden.eden_nx2`, so it installs next to the official Eden app. Never change
it: Android treats a new id as a different app, and users would lose their data.

## Android test builds

The `android` workflow builds an APK on every push. On `nx2` it updates the `android-latest`
pre-release, and an `android-<version>` tag publishes an Android-only release.
