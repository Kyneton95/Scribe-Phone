# Scribe — floating dictation for Android

A private, self-hosted dictation bubble. Scribe lives as a small floating orb
over your other apps. Tap it to open a chat-style card, speak, pause while you
think, then add the finished draft to **one note per day** in a Syncthing folder.

- **Repository:** <https://github.com/Kyneton95/Scribe-Phone> (private)
- **Build workflow:** <https://github.com/Kyneton95/Scribe-Phone/actions/workflows/build-apk.yml>
- **Release:** <https://github.com/Kyneton95/Scribe-Phone/releases>

> The visible app is now called **Scribe**. The package remains
> `au.local.scribephone` so a 0.3.x build can update a 0.2/0.3 install. The old
> Dictate/Dictate + document modes and per-session text files are gone.
>
> **0.6.2:** scribe/pen mark on the floating bubble to match the setup title
> mark — the orb now shows the same stationery icon instead of the mic glyph.
>
> **0.6.1:** scribe/pen mark beside “Scribe” on the setup header and expanded
> card header, replacing the sparkle.
>
> **0.6.0:** UI refresh — stationery-inspired Edit/Copy/Clear/Folder icons,
> clearer stop/mic glyphs, tighter copy throughout setup and the card
> (“Save draft” vs “Add to today’s note”), clearer model-manager wording. The
> calm paper-and-graphite palette is unchanged.
>
> **0.5.2:** fixed the on-device model crash: sherpa now reads downloaded
> model files from their absolute filesystem paths instead of treating them as
> bundled Android assets. Verified with Moonshine on an S24+ running Android 16.
>
> **0.5.1:** local-model crash diagnostics and packaging hardening. Native
> libraries are now extracted at install (`extractNativeLibs=true`), the app
> requests a large heap, and local inference catches/logs `Throwable` failures
> instead of letting the upload thread die. If a native crash remains, please
> send the crash log; see “On-device transcription”.
>
> **0.5.0:** optional on-device transcription. The APK ships only the
> sherpa-onnx runtime; Whisper Tiny, Moonshine Tiny and Parakeet TDT are
> downloaded on demand from inside Scribe, installed into app-private storage
> and selected with an **On-device** engine toggle. No model weights are
> bundled. See “On-device transcription”.
>
> **0.4.3:** server failures are clearer. An empty HTTP 2xx response is now
> reported as an empty proxy/URL response instead of a JSON “end of input”
> error; pasted tokens are normalised (an accidental `Bearer` prefix is
> removed), and a bare host URL is retried once at
> `/v1/audio/transcriptions`. Cloudflare/WAF can still block POST before the
> token reaches the server; see Troubleshooting.
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

- **Version:** 0.6.4-floating (versionCode 15) — ready for daily use
- **APK:** `dist/Scribe-0.6.4-debug.apk` — built fresh, lint clean
- **SHA-256:** `cfbe1485aa969418ca1d4bd8d66d71333aa15d076cbc24ae9f116bb5f5dfbecd`
- **Package:** `au.local.scribephone` (updated from `au.local.barkpocket`)
- **APK is debug-signed** — update directly over any earlier local build
- **Not device-tested on all configurations** — see NOTES.txt for caveats

This is a functional, day-to-day prototype. Future refinements are being
considered but the core flow (dictate → draft → daily Markdown note) is stable.

## Install

## Install

1. Copy `Scribe-0.6.2-debug.apk` to the phone and open it.
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

## On-device transcription (optional)

Scribe can transcribe locally instead of POSTing audio to your server. The APK
contains only the **sherpa-onnx 2.0.1 runtime** (arm64-v8a); it does **not**
contain any model weights. Models are explicit user downloads from the
sherpa-onnx GitHub releases and live in the app’s private storage.

| Model | Family | Download | Installed | Notes |
|---|---|---:|---:|---|
| Whisper Tiny English | Whisper int8 | ~113 MB | ~103 MB | Fastest; good default |
| Moonshine Tiny English | Moonshine int8 | ~103 MB | ~125 MB | Low latency |
| Parakeet TDT 0.6B v2 | NeMo transducer int8 | ~460 MB | ~631 MB | Best accuracy; slower on CPU |

All three are English-only in this build. Android’s system DownloadManager
performs the download, so it can continue with the screen closed. Installation
(extracting the `.tar.bz2`) should be done with Scribe open; the screen is kept
on while extracting.

### Install and use

1. In setup, choose **ENGINE → On-device**. If no model is installed, the model
   manager opens automatically.
2. Tap **Download** beside a model. Wait for **Downloaded — tap Install**, then
   tap **Install**.
3. Tap **Use** on the installed model. This selects it and switches the engine
   to on-device.
4. Start the floating bubble as usual. Audio is transcribed by the model and
   never sent to the server.
5. Return to **Manage on-device models** at any time to switch models or
   **Delete** one and reclaim its storage.

The APK is **arm64-v8a only** because that is the only ABI packaged from the
sherpa runtime. If local mode fails to start, the model may be missing or the
device may not support the native runtime; switch back to **Server** or
reinstall the model.

### Privacy and limits

- In local mode, recorded phrases and transcripts stay on the phone. Only the
  explicit model download contacts GitHub.
- CPU-only inference: Whisper Tiny/Moonshine are suitable for everyday notes;
  Parakeet is heavier and can take noticeably longer per phrase.
- The model archives are third-party artifacts: Whisper MIT, Moonshine MIT,
  Parakeet CC-BY-4.0. Their licences remain with the model files.
- There is no model checksum beyond the archive extraction and required-file
  verification; a failed install can be deleted and retried.

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

## Troubleshooting a transcription error

- **`end of input at character 0 of`** means the app received a successful
  HTTP response with an empty body. It is not a JSON parsing bug and usually
  not the bearer token. The URL or a proxy is answering before the transcription
  service.
- Use the **full path**, for example
  `https://host/v1/audio/transcriptions`, not just `https://host`. A bare host
  URL is retried once at that path by 0.4.3, but the direct path is clearer.
- If the domain is behind **Cloudflare**, a WAF/Bot Fight rule can return
  `HTTP 200` with `content-length: 0` for POSTs. Allow POST for the
  transcription path, or use the server’s LAN URL
  (`http://192.168.x.x:port/v1/audio/transcriptions`) to bypass Cloudflare.
- **`Server HTTP 401/403`** is an authentication/proxy rejection. Paste only the
  key; an accidental `Bearer ` prefix is stripped by 0.4.3. If a token was
  shared in a chat or screenshot, rotate it on the server.
- **`Server returned HTTP 200 but not JSON`** shows the first part of the body;
  an HTML challenge page means the proxy, not the app, returned the response.

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
