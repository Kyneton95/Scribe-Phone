# Scribe — floating dictation for Android

A private, self-hosted dictation bubble. Scribe lives as a small floating orb
over your other apps. Tap it to open a chat-style card, speak, pause while you
think, then add the finished draft to **one note per day** in a Syncthing folder.

- **Repository:** <https://github.com/Kyneton95/Scribe-Phone> (private)
- **Build workflow:** <https://github.com/Kyneton95/Scribe-Phone/actions/workflows/build-apk.yml>
- **Verified CI build:** <https://github.com/Kyneton95/Scribe-Phone/actions/runs/40285743453>
- **Release:** <https://github.com/Kyneton95/Scribe-Phone/releases>

> The visible app is now called **Scribe**. The package remains
> `au.local.barkpocket` so a 0.3.x build can update a 0.2/0.3 install. The old
> Dictate/Dictate + document modes and per-session text files are gone.
>
> **0.4.2:** opening the app when setup is already complete no longer inflates
> the setup screen first. Scribe keeps a blank paper handoff, starts the floating
> service from `onResume`, and finishes; the launcher preview is disabled. Setup
> still appears when configuration or permissions are incomplete.
>
> **0.4.1 fix:** daily commits now append for real. Android document providers
> rejected the old SAF query API with `Pre-Android-O query format not supported`,
> so Scribe never found the existing file; `text/plain` also turned the requested
> `.md` into `.md.txt`. 0.4.1 uses the modern SAF query API and `text/markdown`,
> remembers the returned document URI, and consolidates same-day shards into one
> `.md`.
>
> **0.4.0:** paper-and-graphite palette with almost no gradients, `## HH:mm`
> Markdown sections, a square stop glyph, bigger minimize/close controls,
> swipe-to-minimize and drag-to-bottom dismissal. The 0.3.1 launch/expansion
> crash fixes are retained.

## Delivery status

- Built successfully with **JDK 17.0.21, Gradle 8.9, AGP 8.7.3, Android SDK
  35 and Build Tools 35.0.0** (pinned).
- **APK:** `dist/Scribe-0.4.2-debug.apk` — **149,795 bytes**
- **SHA-256:**
  `a82e780f1a04ba15eac6abe354714f137c03585fa981960fc2a8cdc7ea37cc61`
- Android lint: **0 errors** (26 warnings, mostly hardcoded localization strings
  and the required adaptive-icon folder).
- APK integrity, manifest components and signature were checked with
  `apksigner`, `aapt2` and `unzip -t`; see [VALIDATION.txt](VALIDATION.txt).
- **Not device-tested.** The floating overlay, microphone foreground service,
  notification, lock-screen, real server and Syncthing sync still need testing
  on the S24. No phone or KVM-capable emulator was available here.
- Host-side smoke tests passed for setup-activity launch, panel
  construct/render/measure/add, and foreground-service start + panel expansion.
  They found the 0.3 launch crash fixed in 0.3.1, exercised the new paper card
  in 0.4.0, and the 0.4.1 SAF test merged three `.md(...).txt` shards into one
  ordered `.md` and appended a second commit to the same file. The 0.4.2 launch
  test confirms a configured launch contains only the blank handoff View and
  does not inflate the setup form.
- Debug-signed only. A later build from another machine/runner can use a
  different debug key; switching builds may require uninstalling first.

## Install

1. Copy `Scribe-0.4.2-debug.apk` to the phone and open it.
2. Allow installation from your file manager if prompted.
3. Open **Scribe** once and complete the short setup:
   - **Microphone** — used only while dictating.
   - **Floating bubble** — the system *Display over other apps* permission.
   - **Daily notes** — pick the local folder Syncthing already syncs.
   - **Server** — the full transcription URL, plus an optional bearer token.
4. Tap **Start floating bubble**. The setup screen closes and the orb appears
   over the home screen/other apps.
5. Tap the orb for the chat card. Tap the big mic button, speak, pause, then
   **Add to today’s note**. The orb’s dot means a draft is waiting.

Long-press the orb to start or stop dictation without opening the card. Drag it
to move it; it snaps to the nearest side. To dismiss the bubble, drag the orb
into the **Close** target at the bottom centre. The notification also has Stop /
Add to note / Hide actions. A **Scribe settings** launcher shortcut is available
from the app icon long-press menu.

When the card is open:

- **Swipe down** on the grip/title at the top, or fling down in the chat while it
  is at the top, to minimise back to the orb.
- The larger **minus** button minimises; the larger **X** closes the bubble and
  its foreground service.
- The settings, minimise and close buttons are 42–48 dp touch targets.

## How the dictation flow works

- Only voice dictation and commit remain; there are no modes to choose.
- A phrase ends after a **configurable pause** — 2 s, 4 s (default) or
  6 s — or a **30 s hard cap**, so thinking pauses are less likely to become
  separate sentences. Choose the pause length in setup.
- Each phrase is transcribed by your server, then stitched into the current
  draft. If the next phrase starts lower-case or with a connector such as
  “and / but / so / because / then / which / that / or / also”, the previous
  full stop is replaced with a space or comma. Real sentence boundaries are
  kept.
- The draft is editable: use **Edit** in the card to fix names, punctuation or
  joins before committing.
- **Add to today’s note** stops any recording, waits for the upload queue, then
  appends the draft to the daily file. Nothing is appended automatically while
  you are still thinking.
- **Clear** removes only the current draft and pending phrase audio; already
  written daily notes are untouched.

## One file per day

Committed text goes to:

```text
Scribe_YYYY-MM-DD.md
```

in the chosen folder, using the phone’s local date. The app finds an existing
Markdown file with that name and appends a section; it does not create a new
note for each session.

Each commit is appended as a Markdown section with a 24-hour local time:

```markdown
## 17:09

The transcript for that session.

## 17:42

The next transcript.
```

The time is exactly `HH:mm` — no seconds, AM/PM, date or timezone. The date is
only in the file name. Sections are separated by a blank line. The file is not
continuously mirrored while recording.

If 0.4.0 left same-day shards such as `Scribe_2026-10-26.md.txt` or
`Scribe_2026-10-26.md(2).txt`, the next commit merges their contents into
`Scribe_2026-10-26.md` in timestamp order, verifies the merge, then deletes the
shards the provider allows it to delete. Files from earlier dates are untouched.
If a shard cannot be deleted, it is ignored rather than appended to again.

## Permissions and why Scribe needs them

| Permission | Purpose |
|---|---|
| `RECORD_AUDIO` | Capture a phrase only while you are dictating. |
| `SYSTEM_ALERT_WINDOW` | Show the floating orb and chat card over other apps. This is a special “Display over other apps” access, not a normal prompt. |
| `POST_NOTIFICATIONS` | Show the ongoing bubble/recording notification. |
| `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` | Keep the bubble and microphone session alive when another app is in front. |
| `INTERNET` | POST phrase WAVs to your server. |
| `WAKE_LOCK` | Keep the CPU awake during a bounded dictation session. |

Scribe always runs its bubble as a **microphone-type foreground service**,
started from the visible setup activity. This is deliberate: Android 14
restricts starting a new microphone foreground service from a background
overlay tap. The service is idle (not recording) until you press the mic, and
the system privacy indicator only appears while `AudioRecord` is active. The
ongoing notification is the trade-off for a persistent floating button; use its
Hide action, the card’s X button, or drag the orb to the Close target to stop
it.

Scribe does not start at boot. Opening the app after setup hands off directly to
the bubble/card without inflating the setup screen; the setup form only appears
when configuration or permissions are incomplete. Opening it again while the
bubble is running opens the card.

## Backend contract — no server changes required

POST the full configured URL as `multipart/form-data`:

- `file`: standalone phrase WAV, `audio/wav`, mono 16 kHz signed 16-bit PCM.

Expected HTTP 2xx JSON:

```json
{"text": "The returned transcript."}
```

Connect timeout 15 s, read timeout 180 s. Optional
`Authorization: Bearer <token>`. Redirects are not followed. Only one upload is
active at a time; phrases are sent in order. No model/language field is required
by the existing FastAPI endpoint.

## Privacy and retention

- The draft and pending WAVs are private app files until you commit or clear
  them. Committing deletes the draft and acknowledged audio.
- The API token is in app-private preferences, not hardware-backed credential
  storage.
- HTTP is unencrypted and intended for a trusted LAN. Do not expose the endpoint
  publicly; use trusted HTTPS/private networking remotely.
- No certificate-validation bypasses, accounts, analytics, advertising,
  external AI provider or built-in cloud sync.
- Android cloud backup and device-to-device transfer are disabled for Scribe’s
  private data via `allowBackup="false"` and `dataExtractionRules`.
- Scribe never writes to the daily file until you press Add to today’s note.

## Limits and things to test

- The overlay is a special-access feature; some OEM builds aggressively remove
  or restrict bubbles. Check Scribe’s battery setting on One UI without
  disabling all phone power management.
- The pause threshold (default 4 s) uses a fixed RMS gate (`0.012`); quiet
  speech may be missed and noise may trigger it. The 30 s cap can still cut a
  word. Tap Stop to send immediately, or edit the draft if the server inserts
  punctuation at an awkward point.
- Speech shorter than 200 ms is ignored as noise. Do not use this as an
  archival meeting recorder without checking the transcript against the audio.
- One draft at a time; maximum 120 queued phrases; maximum one-hour session.
- A force-stop/process kill can lose the current in-memory phrase (up to 30 s).
  Committed WAV phrases and the private draft survive for Retry.
- Recovery and failed uploads require tapping Retry in the card; the microphone
  is never restarted silently.
- The notes folder must stay writable and its persisted permission intact.
- The card cannot host an IME, so **Edit** opens a small full-screen editor.
- Test airplane mode, server failure, permission denial, screen off, rotation,
  calls, app restart, folder permission loss, overlay revocation, lock screen
  and Syncthing sync on the actual S24.

## Build

Requirements: JDK 17, Android SDK platform 35 and Build Tools 35.0.0. The Gradle
8.9 wrapper is committed.

```bash
export ANDROID_HOME="$HOME/Android/Sdk"   # or create local.properties with sdk.dir
./gradlew :app:assembleDebug :app:lintDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Output: `app/build/outputs/apk/debug/app-debug.apk`.

The GitHub Actions workflow installs SDK 35, builds the debug APK, runs lint,
writes a SHA-256 checksum and uploads both as the `Scribe-debug-apk`
artifact.

## Changes in 0.4.2

- Removed the setup-screen flash on launch. `MainActivity` now checks whether
  setup is complete before calling `buildUi()`; a configured launch uses a
  blank paper handoff instead.
- The bubble is started from `onResume()` rather than a 220 ms delayed post, so
  the handoff is quicker while the activity is still visible for Android 14
  microphone-FGS eligibility.
- Added `android:windowDisablePreview="true"` to a launch-only theme so the
  system does not draw a full setup preview first.
- If the service fails to become ready, the real setup UI is built and the error
  is shown; the blank handoff is not left behind.
- Added a host test asserting a configured launch contains only the blank
  handoff View, not the setup layout.

## Changes in 0.4.1

- Fixed daily appending at the SAF layer. `DocumentsProvider` on Android 14
  rejects the old five-argument `ContentResolver.query()`; Scribe now uses
  `query(..., Bundle, CancellationSignal)`, so existing files are found.
- Markdown files are created with the `text/markdown` MIME type instead of
  `text/plain`, so the provider no longer renames `name.md` to `name.md.txt`.
- The returned document URI is persisted for the day; subsequent commits open
  that exact document even if a provider changes its display name.
- Same-day `.md.txt` / `.md(n).txt` shards are merged into one ordered `.md`,
  verified, and deleted. Subsequent commits append to the same `.md`.
- Daily writes now rewrite the verified full content instead of relying on the
  provider’s `wa` append mode, which was also losing earlier sections during the
  host simulation.
- Added a host-side SAF test with a fake `DocumentsProvider` that reproduces the
  `.md(...).txt` shard pattern and proves the repair.

## Changes in 0.4.0

- Added a paper-and-pencil visual language: warm paper, graphite ink, pencil
  yellow, terracotta and sage; the dot-grid/ruled background replaced the
  animated neon aurora, and all deliberate gradients were removed from the
  panels, buttons and orb.
- Daily notes are now Markdown (`.md`) with one `## HH:mm` section per commit,
  using 24-hour local time only.
- Replaced the stretched stop glyph with a true square and larger, square
  content padding.
- Enlarged the card’s minimise and close controls, added a minimise (minus)
  button distinct from close (X), and added swipe-down/header-grip minimise.
- Added a bottom-centre **Close** drop target: drag the orb into it to remove
  the bubble and stop its service.
- Kept the 0.3.1 decor-crash fix and panel-failure isolation.

## Changes in 0.3.1

- Fixed the startup `NullPointerException` from calling
  `Window.getInsetsController()` before the decor view existed. The decor is
  now created first in both setup and the draft editor.
- Isolated card expansion: a failure now removes the broken panel, logs the
  stack trace, shows the exception in a toast, and leaves the bubble running.
- Screen width/height now fall back to display metrics if a service context
  reports empty window metrics, and the panel width/height are clamped positive.
- `refreshUi` can no longer take down the service if a card render fails.

## Changes in 0.3

- Replaced the full-screen form and mode selector with a draggable gradient
  bubble and an expanding chat-style card.
- Opening the configured app now summons the bubble instead of a separate app
  screen; the settings/onboarding screen appears only when needed.
- Removed Dictate/Dictate + document modes. One record button, one commit.
- Commit now appends to a **single daily file** (daily filename described above).
- Raised the silence boundary from 700 ms to a configurable 2/4/6 s (default
  4 s) and the phrase cap from 13 s to 30 s; added pause-aware joining, a
  pause-length setting and an editable draft.
- Added a live waveform, pulsing/recording orb, aurora setup background, spring
  panel animation, caption pill, haptics, adaptive/monochrome icons and a
  cohesive palette.
- Added overlay permission onboarding, a foreground-service bubble, quick
  settings tile and settings/draft editor activities.
- Kept private drafts/audio out of backup and device transfer.

## Design references

- <https://developer.android.com/develop/background-work/services/fgs/service-types>
- <https://developer.android.com/develop/ui/views/notifications/notification-permission>
- <https://developer.android.com/reference/android/provider/Settings#ACTION_MANAGE_OVERLAY_PERMISSION>
- <https://developer.android.com/training/data-storage/shared/documents-files>
