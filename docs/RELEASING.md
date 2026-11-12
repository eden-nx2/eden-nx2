# Releasing

One run of the `release-binaries` workflow builds every platform and publishes them as one GitHub
release, tagged `build-<run number>`:

| Platform | Assets |
|---|---|
| Windows x86_64 | `eden-windows-x86_64.zip`, `eden-windows-x86_64-pgo.zip` |
| Linux x86_64 / aarch64 | AppImage and tar.gz, standard and PGO |
| macOS (Apple Silicon) | `eden-macos-arm64.zip` |
| Android arm64 | `eden-android-arm64.apk` |

## Publish a release

1. Push the commits you want to release to `nx2`.
2. Optional: write the highlights. Either commit them as `RELEASE_NOTES.md` at the repository
   root, or type them into the run's **notes** field (it takes priority).
3. GitHub → **Actions** → **release-binaries** → **Run workflow**, branch `nx2`:
   - **Publish the binaries as a GitHub release**: on.
   - **notes**: Markdown highlights (optional).
   - **Mark the release as a pre-release**: untick for a release users should get as "Latest".
4. Wait for the run (about 1–2 hours; the PGO and Android builds are the longest). The release
   appears under **Releases** when every platform has built. If one platform fails, nothing is
   published; re-run the failed jobs.

Pushing to the `nx2-experimental` branch also runs it, as a pre-release without highlights.

## What the notes contain

- A short experimental-status line.
- **Highlights**: the notes field or `RELEASE_NOTES.md`.
- **Changes since build-N**: every commit since the previous `build-*` tag, with a comparison
  link. The first release links to [MIRROR_CHANGES.md](./MIRROR_CHANGES.md) instead.
- Download tables and install notes for Android, Linux, Windows and macOS.

Edit the notes afterwards on the release page if needed.

## Updates inside the app

Desktop builds bake in `build-<run number>` and `EDEN_UPDATE_REPO` (this repository), so the
built-in updater offers newer `build-*` releases of this repository.

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
