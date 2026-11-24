# OpenSteves

An open source Android player for [Rick Steves Europe Audio Tours](https://www.ricksteves.com/watch-read-listen/audio/audio-tours).

## Features

- Download tours, maps, and transcripts for offline use.
- Play audio in the background with notification and lock-screen controls.
- Listen together and share audio over Bluetooth, with playback controls on every phone.
- Import your own audio and save downloaded audio to your device.

Pair phones in Android Bluetooth settings before starting a shared session. Shared sessions require no Wi-Fi or mobile data.

## Requirements

- Android 8.0 or newer device.
- Android Studio or the Android Gradle plugin toolchain.
- JDK 21 and Android SDK 36.1 for building.

## Build

```sh
./gradlew :app:assembleDebug
```

The debug APK is generated at `app/build/outputs/apk/debug/app-debug.apk`.

See the [build workflow](.github/workflows/build-apks.yml) for CI signing and publishing configuration.

## Content

Audio, maps, images, and transcripts belong to their respective copyright holders.
OpenSteves is not affiliated with Rick Steves Europe. Tour content is not bundled with the application.

## Playlists and custom guides

The app now includes a **Playlists** tab. A playlist is an ordered sequence of tour stops grouped by day. Stops can link to an official tour in the library, or be **custom guides** written for places the official catalog does not cover.

Custom guides are modular — a header, practical information, a map/orientation block, and chapters with an image, caption, and text for each stop along the way. Chapter text can be read aloud with Android's built-in text-to-speech, with play/pause per chapter. The bundled London playlist includes deep custom guides for the Tower of London, Tower Bridge, Oxford, the Science Museum, the Natural History Museum, the National Gallery, and the Guildhall. See `docs/CONTENT_FORMAT.md` for the guide format and `docs/CONTENT_ATTRIBUTION.md` for image credits.
