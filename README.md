<p align="center"><img src="docs/icon.svg" alt="Janeway icon" width="128"></p>

# Janeway

Music tagger for Android, modelled on [MusicBrainz Picard](https://picard.musicbrainz.org/).

## Features

- Pick a music folder once; Janeway reads the tags and groups files into albums.
- Find the album on MusicBrainz by title and artist, by pasting a MusicBrainz release link, or by audio fingerprint (AcoustID).
- Check the file-to-track matching before saving.
- Write the same tags Picard writes, including the MusicBrainz IDs, and embed the front cover from the Cover Art Archive.

Supported formats are those of [TagLib](https://taglib.org/): MP3, FLAC, Ogg Vorbis, Opus, M4A, WAV, AIFF, WMA, APE, WavPack and others. Fingerprinting works for every format Android can decode.

## AcoustID API key

"Identify by audio" needs an AcoustID application API key, which is built into the app. Register an application at <https://acoustid.org/new-application> once, then provide the key to the build:

- GitHub Actions: repository secret `ACOUSTID_API_KEY`
  ```bash
  gh secret set ACOUSTID_API_KEY
  ```
- Local builds: `acoustid.apiKey=<key>` in `local.properties` (gitignored), or the `ACOUSTID_API_KEY` environment variable

A build without a key works, but "Identify by audio" then reports that the key is missing. AcoustID application keys identify the app rather than a user, and anyone can read one from an APK; this is how Picard ships its key too.

## Install

Every push to `main` builds a debug APK on GitHub Actions; download it from the run's artifacts. Pushing a tag like `v0.2.0` also publishes a GitHub release with the APK attached, which works with [Obtainium](https://github.com/ImranR98/Obtainium) for automatic updates.

### Signing key

Android only installs an update when it is signed with the same key as the installed app. Without further setup, every machine signs with its own debug key, so an APK from CI cannot replace one built locally.

To share one key, create it once at `androidApp/debug.keystore`. The file is gitignored; do not commit it, since anyone with it can sign updates for your installed app.

```bash
keytool -genkeypair -keystore androidApp/debug.keystore -storetype PKCS12 -storepass android -keypass android -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10000 -dname "CN=Android Debug,O=Android,C=US"
```

Then store it as the repository secret `DEBUG_KEYSTORE_BASE64`, which the workflow uses when it is set:

```bash
base64 -i androidApp/debug.keystore | gh secret set DEBUG_KEYSTORE_BASE64
```

Keep a backup of the key. Losing it means uninstalling the app once before an APK signed with a new key installs.

## Build

Requires JDK 17+, the Android SDK (platform 37) and NDK 27.1. The first build downloads the Chromaprint sources.

```bash
./gradlew :androidApp:assembleDebug
```

The APK is written to `androidApp/build/outputs/apk/debug/androidApp-debug.apk`.

```bash
./gradlew :shared:testAndroidHostTest
```

## Layout

- `shared/src/commonMain`: UI (Compose Multiplatform), MusicBrainz, AcoustID and Cover Art Archive clients, album clustering, track matching, tag mapping
- `shared/src/androidMain`: folder access through the Storage Access Framework, tag I/O through [TagLib](https://github.com/Kyant0/taglib)
- `chromaprint`: [Chromaprint](https://github.com/acoustid/chromaprint) built with the NDK, fed with PCM from Android's MediaCodec
- `shared/src/iosMain`: stubs; iOS targets compile but have no folder access, tag I/O or fingerprinting yet
- `androidApp`: Android entry point and icon

## License

Janeway is free software: you can redistribute it and/or modify it under the terms of the GNU General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).

Third-party components keep their own licenses: Chromaprint is LGPL 2.1 as a whole (MIT for its own code, plus parts of FFmpeg) and is linked statically; TagLib is LGPL 2.1 / MPL 1.1, and its Android binding is Apache 2.0.

Janeway is an independent project. It is not affiliated with or endorsed by the MetaBrainz Foundation (MusicBrainz, Picard, Cover Art Archive) or AcoustID. It uses their public web services and follows their usage rules.
