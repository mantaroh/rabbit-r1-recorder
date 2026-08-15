# rabbit-r1-recorder

Turning a Rabbit R1 running [CarrotOS](https://github.com/carrot-os) into a
device that records and keeps the audio around it, photographs the room it is
in, and answers questions about both — with a Cloudflare Worker as the archive
and a self-hosted [Hermes](https://github.com/NousResearch/hermes-agent) agent
as the thing that reads it.

Everything here was built against a real device. Where a number appears in the
docs — a key code, a current draw, a transcription latency — it was measured,
not assumed.

## What is in here

| Module | What it is |
| --- | --- |
| `core/` | Android library: the motorised camera arm (`R1Motor`), headless Camera2 stills, the shared idle timer |
| `app/` | **R1 Camera** — preview, shutter, and direct control of the lens angle |
| `hermes/` | Chat client for a Hermes gateway: streaming replies, tool activity, approvals, push-to-talk, photo attachments. Now compiled *into* `audioprobe` as a source set; still builds standalone |
| `audioprobe/` | **R1 Audio Probe** — the app that actually runs on the device: continuous stereo recording, timelapse photography, the chat, the home screen and the standby display |
| `cloudflare/lifelog/` | Worker that ingests audio and photographs into R2, transcribes and captions them into D1, serves a web UI, and exposes the archive to the agent over MCP |
| `relay/` | Reports Codex and Claude Code usage from the machine those tools run on, so the standby display can show real figures |

Reference docs:

- [`DESIGN.md`](DESIGN.md) — how the system works and why it diverges from the
  original spec ([日本語](DESIGN.ja.md))
- [`hermes/PROTOCOL.md`](hermes/PROTOCOL.md) — the Hermes gateway wire protocol,
  reverse-engineered from the agent source and verified against a live server
- [`hermes/UI-SPEC.md`](hermes/UI-SPEC.md) — UI design for a 240×320 dp panel
- [`audioprobe/FINDINGS.md`](audioprobe/FINDINGS.md) — measured device behaviour

## The device, measured

The published spec for the R1 is not a reliable guide. What the hardware
actually does:

| | |
| --- | --- |
| Panel | **480 × 640 px**, 320 dpi → **240 × 320 dp** of layout space |
| Wheel | `KEYCODE_DPAD_UP` / `KEYCODE_DPAD_DOWN` — key events, not rotary motion |
| Side button | `KEYCODE_BUTTON_1` (188), auto-repeats while held; also activates the focused view |
| Back key | **Does not exist.** Every screen needs an on-screen exit |
| Camera | Single 3264×2448 sensor on a motorised arm; 0° selfie, 90° parked, 180° outward |
| Microphone | Two capsules, left/right symmetric. Advertises up to 48 kHz; 16 kHz mono is merely the default |
| Battery | ~1010 mAh. **52 mA** recording mono with no camera; **117 mA** for the shipping configuration — stereo plus timelapse — measured over 48 minutes unplugged |

## Keeping the audio

The recording is the artefact. Transcription, search and the question feature
are what today's models can do with it, and can all be redone later against the
same bytes; the audio cannot be. Nothing is deleted.

```
R1 ──PUT /v1/segments/{id}──▶ Worker ──▶ R2 (audio, kept)
 48 kHz stereo, Opus 32 kbps   │  │
   + 1 byte/s loudness         │  └─▶ Whisper ──▶ D1 (transcripts)
                               │       (only if the envelope says speech)
   ──PUT /v1/photos/{id}────▶  └────▶ R2 (jpeg) ──▶ vision model ──▶ D1 (captions)
                                                       │
                     browser ──▶ GET / (day timeline) ─┤
                Hermes agent ◀── MCP: lifelog_recent / _search
```

48 kHz because sample rate is the one choice that cannot be revisited — the
mic reaches well past 8 kHz, and the first weeks of 16 kHz recording threw that
away permanently. Opus because it keeps the whole band for ~550 KB a minute in
stereo, about 280 GB a year.

Double-press the side button and ask a question out loud, and a Hermes agent
answers it with the preceding two minutes as context. Because the agent reaches
the transcripts through MCP tools, resolving "さっきの話" needs no dedicated
query API and no context plumbing on the device: the agent decides on its own
to go and look.

## A day on the device

- **07:00** — recording starts by itself. It also starts at boot, through the
  Activity, because Android 14 will not let a background component start a
  microphone service.
- **All day** — 60-second Opus segments upload over unmetered Wi-Fi. A front
  and rear photograph every 15 minutes on the home network and every 5 minutes
  away from it, skipped entirely when the room is quiet, dark and unchanged.
- **23:00** — a full-screen question asks whether to stop for the night, and
  asks again every 10 minutes until answered. Not answering leaves the
  recording running. The answer lasts until the next morning rather than until
  midnight.
- **Idle for a minute, while charging** — the standby display takes over: clock,
  today's recording, the last thing said, Codex and Claude Code usage, Hermes
  tasks. The wheel steps between screens; the side button leaves.

The home screen is a five-row menu turned through with the wheel — 話す, 記録,
待受, 設定, Hermes. The app also registers as a HOME activity, so the device
comes back to it rather than to the stock launcher.

## Building

No Android Studio required.

```powershell
$env:JAVA_HOME = "<path to a JDK 17>"
$env:ANDROID_HOME = "$env:USERPROFILE\Android\Sdk"
.\gradlew.bat assembleDebug              # all modules
.\gradlew.bat :audioprobe:assembleDebug  # the one that runs on the device
```

Needs JDK 17, Android SDK `platforms;android-35` and `build-tools;35.0.0`.
Point `local.properties` at your SDK.

After every install, toggle the accessibility service off and on — see
[Operational notes](DESIGN.md#operational-notes) for why the side button
otherwise goes quiet with no sign of it.

The Worker:

```bash
cd cloudflare/lifelog
npm install
npx wrangler d1 migrations apply r1-lifelog --remote && npx wrangler deploy
```

The `&&` matters. Chaining with `;` has twice deployed code against a schema
that had not been migrated.

## Security notes

**The device is not a trust boundary.** CarrotOS exposes an **unauthenticated
root shell on `127.0.0.1:1337`** so the launcher can drive the camera motor. Any
installed app can use it, and can read any other app's files. Treat the R1 as
dedicated hardware: do not install untrusted APKs, and keep the credentials on
it scoped as narrowly as possible.

Consequences worth naming rather than assuming:

- **`.MainActivity` is exported** — it has to be, to be a launcher and a home
  screen. It honours launch extras that start recording, run the microphone
  probe, open the chat and set the Wi-Fi the camera may photograph from, so any
  installed app can trigger those. This is strictly weaker than the root shell
  above, which is the reason it is tolerated rather than the reason it is fine.
- **The device's bearer token also opens the admin endpoints.** `repair-wav`
  rewrites objects in R2 and `retranscribe` spends money. A lost R1 is a lost
  archive-mutation capability, not just a lost upload capability. Splitting
  ingest from admin is the fix and is not done.
- **The Worker trusts `Cf-Access-Jwt-Assertion` without verifying it.** That is
  sound only while Cloudflare Access covers *every* path of the custom domain —
  there is no `workers.dev` route, so Access is the only way in. If an Access
  policy is ever narrowed to a path prefix, everything outside it becomes
  reachable by anyone willing to send that header themselves.

No credentials are committed here, and none appear in the history. The apps read
theirs from on-device settings; the Worker reads its bearer from a Wrangler
secret; the usage relay reads its own from a `0600` environment file.
