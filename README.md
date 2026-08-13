# rabbit-r1-recorder

Turning a Rabbit R1 running [CarrotOS](https://github.com/carrot-os) into a
personal audio recorder and a hardware client for a self-hosted
[Hermes](https://github.com/NousResearch/hermes-agent) agent.

Everything here was built against a real device. Where a number appears in the
docs — a key code, a current draw, a transcription latency — it was measured,
not assumed.

## What is in here

| Module | What it is |
| --- | --- |
| `core/` | Android library: the motorised camera arm (`R1Motor`) and Camera2 capture, shared by the apps |
| `app/` | **R1 Camera** — preview, shutter, and direct control of the lens angle |
| `hermes/` | **R1 Hermes** — chat client for a Hermes gateway: streaming replies, tool activity, approvals, push-to-talk, photo attachments |
| `audioprobe/` | **R1 Audio Probe** — double-press the side button to ask a question about the conversation around you |
| `cloudflare/lifelog/` | Worker that ingests audio into R2, transcribes it with Workers AI Whisper into D1, and exposes it to the agent over MCP |

Reference docs:

- [`DESIGN.md`](DESIGN.md) — how the system works and why it diverges from the
  original spec
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
| Side button | `KEYCODE_BUTTON_1` (188), auto-repeats while held |
| Back key | **Does not exist.** Every screen needs an on-screen exit |
| Camera | Single 3264×2448 sensor on a motorised arm; 0° selfie, 90° parked, 180° outward |
| Microphone | 16 kHz mono PCM captures natively |
| Battery | ~1010 mAh; continuous recording draws ~53 mA → 20+ hours |

## Asking the device a question

Double-press the side button, ask out loud, and the answer comes back from a
Hermes agent that has been handed the two minutes of conversation preceding the
question.

```
R1 ──PUT /v1/segments/{id}?sync=1──▶ Worker ──▶ R2 (audio, 1 day)
                                        │
                                        └──▶ Workers AI Whisper ──▶ D1 (1 day)
                                                                     │
                       Hermes agent ◀── MCP: lifelog_recent / _search ┘
```

Nothing is recorded to disk and nothing is uploaded until a question is asked.
Audio lives in a 150-second in-memory ring buffer and is otherwise overwritten
in place. Both stores expire after a day.

Because the agent reaches the transcripts through MCP tools, resolving "さっき
の話" needs no dedicated query API and no context plumbing on the device: the
agent decides on its own to go and look.

This started as a continuous 24/7 lifelog, which worked — 18 hours unattended
on battery, no frame loss, no stalls, 107 MB/h — and was then removed on
purpose. [`DESIGN.md`](DESIGN.md) records why, and what continuous transcription
of a quiet room actually returns.

## Building

No Android Studio required.

```powershell
$env:JAVA_HOME = "<path to a JDK 17>"
$env:ANDROID_HOME = "$env:USERPROFILE\Android\Sdk"
.\gradlew.bat assembleDebug          # all modules
.\gradlew.bat :hermes:assembleDebug  # one of them
```

Needs JDK 17, Android SDK `platforms;android-35` and `build-tools;35.0.0`.
Point `local.properties` at your SDK.

The Worker:

```bash
cd cloudflare/lifelog
npm install
npx wrangler deploy
```

## Security notes

CarrotOS exposes an **unauthenticated root shell on `127.0.0.1:1337`** so the
launcher can drive the camera motor. Any installed app can use it, and can read
any other app's files. Treat the device as dedicated hardware: do not install
untrusted APKs, and keep credentials that live on it scoped as narrowly as
possible.

No credentials are committed here. The apps read theirs from on-device settings;
the Worker reads its bearer from a Wrangler secret.
