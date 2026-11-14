# Janeway

Android music tagger modelled on MusicBrainz Picard. Kotlin Multiplatform with Compose Multiplatform; Android is the only working target.

## Commands

```bash
./gradlew :androidApp:assembleDebug            # APK: androidApp/build/outputs/apk/debug/androidApp-debug.apk
./gradlew :shared:testAndroidHostTest          # all unit tests (commonTest + androidHostTest)
./gradlew :shared:compileKotlinIosSimulatorArm64   # keeps shared code free of Android-only APIs
```

Run all three before committing. GitHub Actions (`.github/workflows/android.yml`) runs the first two on every push; a `v*` tag also publishes a release with the APK.

## Layout

- `shared/src/commonMain`: UI (`ui/App.kt`, `ui/AppViewModel.kt`), MusicBrainz, AcoustID and Cover Art Archive clients, album clustering, track matching, tag issue checks
- `shared/src/androidMain`: folder access (Storage Access Framework), tag I/O through TagLib, scan cache, cover header probe
- `shared/src/androidHostTest`: JVM tests that need Android-side code, with audio fixtures in `resources/covers`
- `chromaprint`: Chromaprint built with the NDK (fetched by CMake), PCM from MediaCodec
- `androidApp`: entry point, icon, build-time AcoustID key (`BuildConfig.ACOUSTID_API_KEY`)

## Workflow

- Commit directly to `main`; no feature branches. Conventional Commits.
- Check UI changes on the emulator with `scripts/emulator-test.sh` (see its header). It uses a throwaway AVD; do not use personal AVDs, they may have a lock screen PIN that keeps storage locked.

## Pitfalls

- TagLib (Kyant0 binding) closes the file descriptor it is given. Pass `descriptor.dup().detachFd()`, never the descriptor itself.
- TagLib's `savePropertyMap` replaces all tags; `writeTags` merges with the existing map first.
- Raise `ScanCache.VERSION` whenever `CachedFile` changes, or old caches are misread.
- MusicBrainz allows 1 request per second and requires the User-Agent in `MusicBrainzClient`; it answers 503 when rate limited. AcoustID allows 3 per second.
- The MusicBrainz `tracks` search field counts tracks per medium, not per release; do not filter searches on it.
- Placeholder values ("Unknown Artist", "Album", "Track 05", ...) live in `library/TagIssues.kt` and count as missing, also for clustering. Test data must not use them as real names.
- `com.android.kotlin.multiplatform.library` (AGP 9) is used for `shared`; it has no native build support, which is why Chromaprint is its own `com.android.library` module.
- compileSdk is 37 because current Compose libraries require it.
- The AcoustID key comes from the `ACOUSTID_API_KEY` secret in CI or `acoustid.apiKey` in `local.properties`. The signing key comes from the `DEBUG_KEYSTORE_BASE64` secret, or `androidApp/debug.keystore` locally.
- The user's shell is zsh: unquoted `$VAR` holding several arguments is not split into words.
