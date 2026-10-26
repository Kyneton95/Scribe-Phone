# Bark Pocket — Android prototype 0.2

A small Android phone client for a self-hosted transcription server. It records
short phrases, posts each phrase as a WAV file to your existing HTTP endpoint,
appends the returned text in order, and can export one plain UTF-8 `.txt` file
into a folder that Syncthing already syncs.

**Repository:** <https://github.com/Kyneton95/Scribe-Phone> (private)
**Build workflow:** <https://github.com/Kyneton95/Scribe-Phone/actions/workflows/build-apk.yml>

## Delivery status

- Compiled successfully with JDK 17.0.21, Gradle 8.9, Android Gradle Plugin
  8.7.3, Android SDK 35 and Build Tools 35.0.0.
- The debug APK is `app/build/outputs/apk/debug/app-debug.apk`; a hand-off copy
  and checksum are in `dist/` and in the GitHub pre-release.
  Local hand-off build: **57,513 bytes**, SHA-256
  `2c5839334f7818f3756d0b1630d3455e415451ff6b6b7712aeaa4a419d6e20a7`.
- APK integrity and metadata were checked with `apksigner`, `aapt2` and
  `unzip -t`; Android lint reports **0 errors** (5 warnings). See
  [VALIDATION.txt](VALIDATION.txt).
- **Not device-tested.** S24 microphone/notification/lock-screen behaviour, the
  real LAN server, and Syncthing sync still need a physical device test.
- Requires **Android 14+** (minSdk 34, targetSdk 35). The Samsung S24 is the
  intended test device.
- Java + native Android widgets, no third-party runtime libraries. Deliberately
  smaller than a Compose app for a first prototype.
- The APK is **debug-signed**. A build made on a different machine/runner can
  use a different debug key, so a later APK may require uninstalling the old
  prototype first. Export notes before uninstalling; uninstalling deletes
  private drafts/audio. A stable release signing key is deferred.

## What it does

- Choose **Dictate** or **Dictate + document** before starting.
- Tap **Start listening**, then **Stop listening**; Stop also appears in the
  notification.
- Captures mono 16 kHz PCM continuously while the foreground service runs.
- Sends phrases after ~700 ms of quiet, or at a ~13-second hard cap.
- Appends results in order, using your **existing** transcription server.
- Copy/share text; document mode saves **one** UTF-8 `.txt` after Stop and the
  queue drains.
- No Markdown, AI model, headers, metadata or formatting in the exported file.
- Choose a local folder already synced by Syncthing. The app does not run sync.
- Private session recovery, ordered pending WAV queue, explicit retry.
- Launcher mode shortcuts; Quick Settings tile opens the mode chooser.
- No account, analytics, advertising, external AI provider or built-in cloud
  sync.

## Get an APK

### Option A — prebuilt APK from the GitHub pre-release

Download the APK from
<https://github.com/Kyneton95/Scribe-Phone/releases> (the repository is
private, so sign in to GitHub first). Copy it to the phone, open it, and allow
installation from the file manager if prompted. A SHA-256 checksum is attached
to the release.

For this hand-off, the identical build is also at
`dist/BarkPocket-0.2-debug.apk` in the local workspace with
`dist/BarkPocket-0.2-debug.apk.sha256` (the `dist/` folder is intentionally not
committed):

```bash
cd dist
sha256sum -c BarkPocket-0.2-debug.apk.sha256
```

### Option B — GitHub Actions (no local Android SDK required)

1. Push to `main`/`master`, or open **Actions → Build prototype APK → Run
   workflow**.
2. Once successful, download the `BarkPocket-debug-apk` artifact.
3. Unzip it and transfer `app-debug.apk` to your phone; install it.
4. The artifact also contains the SHA-256 checksum.

This workflow compiles the app; it does **not** upload recordings, settings or
notes. It uses the committed Gradle wrapper, so CI and local builds use the
same Gradle 8.9.

### Option C — local Android build

Install JDK 17, Android SDK platform 35 and Build Tools 35.0.0. A real Gradle
wrapper is included, so a separate Gradle installation is not required.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # or create local.properties with sdk.dir
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

If your SDK is elsewhere, create `local.properties`:

```properties
sdk.dir=/absolute/path/to/Android/Sdk
```

Android Studio can open this Gradle project directly.

## First run

1. Phone and server on the same trusted LAN. Check the phone can reach:
   `http://192.168.20.14:24100/health` (replace with your server's address).
2. Open Bark Pocket; the full endpoint defaults to:
   `http://192.168.20.14:24100/v1/audio/transcriptions`.
3. Save connection settings. Leave the API token blank unless your server
   requires it.
4. Choose the local Syncthing folder using the system picker. Do **not** choose
   a cloud provider if you want local-only notes. No broad storage permission
   is needed.
5. Select **Dictate + document**, tap Start, and allow microphone and
   notification access.
6. Speak normally, pause, check text appears, then tap Stop.
7. Wait for pending phrases to finish. Confirm a
   `Voice_<timestamp>_<id>.txt` file appears in the chosen phone folder, then
   on your PC through Syncthing.
8. Copy/share if desired. **Discard / new session** clears only private session
   data; already exported text files are **not** deleted.

## Shortcuts / S24

- Long-press the app icon: **Dictate** / **Dictate + save** select the mode,
  then tap Start. These do **not** silently start the microphone.
- Add Bark Pocket from the Quick Settings tile editor: it opens the
  app/mode chooser.
- Assign the Samsung Side-button double-press to open Bark Pocket in phone
  settings. The Side button is an app launcher, not a guaranteed recording
  toggle.
- Start in the visible app; stop in the app or the recording notification.
- The foreground microphone service and bounded wake lock are intended to allow
  screen-off recording. Verify this on **your** One UI build before relying on
  it.
- If Samsung suspends it, inspect the app's battery settings; do not disable all
  phone power management globally. Calls/other microphone users can interrupt
  capture.

## Backend contract — no server changes required

POST the **full configured URL** as `multipart/form-data`:

- `file`: standalone phrase `.wav`, `audio/wav`, mono 16 kHz signed 16-bit PCM.

Expected HTTP 2xx JSON:

```json
{"text": "The returned transcript."}
```

Only one HTTP request is active at a time. Connect timeout 15 s, read timeout
180 s. Optional `Authorization: Bearer <token>`. Redirects are not followed.
No model/language field is required by the existing FastAPI endpoint. The
server may still perform its own segmentation for other clients/long files.

## Privacy, retention and security

- Both modes keep a **private on-phone recovery draft** and pending phrase
  audio. **Dictate means no automatic `.txt` export, not zero disk
  persistence.**
- Acknowledged audio is deleted; failed audio stays for Retry. Discard removes
  the private draft and remaining audio.
- Android backup is disabled for this app.
- The API token is in app-private preferences, **not** hardware-encrypted
  credential storage.
- HTTP is allowed for the current LAN server and is **not encrypted**. Do not
  expose that endpoint publicly. Use trusted HTTPS/private networking for
  remote use.
- No certificate-validation bypasses are included. Self-signed certificates
  need a proper trust setup.
- Other clients' long server jobs can delay phone dictation.

## Limits / important tests

- Not a system keyboard: no automatic insertion into other apps. Use
  Copy/Share.
- Phrase-live, not word-by-word. Latency includes server inference time.
- Simple fixed RMS gate `0.012`; quiet speech may be missed and noise may
  trigger it. Test a quiet voice, normal voice and background noise. Adjust the
  threshold in `VoiceService.java` only after testing; a production VAD is
  deferred.
- Uninterrupted speech is cut at ~13 s; a word crossing the boundary may
  suffer.
- Speech shorter than 200 ms may be ignored as noise. Do not use as an archival
  meeting recorder without validating transcription against the original audio.
- One recoverable session at a time. Copy/save then Discard before a new
  session.
- Maximum recording 1 hour; maximum 120 queued phrases before recording stops.
- A force-stop/process kill can lose the **current** in-memory phrase (up to
  ~13 s). Already committed WAV phrases and transcript survive for manual
  Retry.
- Recovery and failed uploads require opening the app and tapping Retry; no
  automatic restart of the microphone, boot receiver or hidden recording.
- The chosen folder must remain writable. Save failures keep the private draft.
- Text export is one final file, not continuously mirrored during recording.
- Test airplane mode, server failure, permission denial, screen off, rotation,
  interruption by calls, app restart, folder permission loss and Syncthing
  sync.

## Changes in 0.2

- Pinned `buildToolsVersion '35.0.0'` so AGP does not fall back to the
  undeclared Build Tools 34.0.0.
- Added the Gradle 8.9 wrapper and switched the GitHub Actions workflow to it.
- Fixed foreground-service start paths that could crash with
  `ForegroundServiceDidNotStartInTimeException`; every `startForegroundService`
  path now enters the foreground or calls `stopSelf`.
- Retry now uses a `dataSync` foreground service and no longer asks for
  microphone permission just to upload pending audio.
- Added a proper adaptive launcher icon, notification icon, monochrome themed
  icon and string resources.
- Added explicit microphone-permission checking before opening `AudioRecord`.
- Replaced hardcoded radio-button IDs with resource IDs, used the non-deprecated
  `TileService` API, and persisted both read and write access to the export
  folder.
- Added `dataExtractionRules` that exclude the app's private draft, token and
  audio from cloud backup and device-to-device transfer.
- Recorded real build, lint and APK verification results in
  [VALIDATION.txt](VALIDATION.txt).

## Design references

- <https://developer.android.com/develop/background-work/services/fgs/service-types>
- <https://developer.android.com/training/data-storage/shared/documents-files>
