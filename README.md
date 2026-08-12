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
| `audioprobe/` | **R1 Audio Probe** — always-on capture harness used to prove a 24/7 lifelog is viable on this hardware |
| `cloudflare/lifelog/` | Worker that ingests audio into R2, transcribes it with Workers AI Whisper into D1, and exposes it to the agent over MCP |

Reference docs:

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

## Always-on capture

`audioprobe` ran unattended on battery with the screen off:

- **no frame loss** — captured frames matched the expected 10/s exactly
- **no stalls, no reinitialisations, no write errors**
- continuous disk writing costs **essentially nothing** in power (52.5 mA with,
  51.6 mA without), so VAD is worth doing for upload volume and transcription
  cost, not for battery
- 107 MB/h as raw PCM16

## Lifelog pipeline

```
R1 ──PUT /v1/segments/{id}──▶ Worker ──▶ R2 (audio)
                                 │
                                 └──▶ Queue ──▶ Workers AI Whisper ──▶ D1
                                                                        │
                        Hermes agent ◀── MCP: lifelog_recent / _search ─┘
```

The segment id is the R2 object key, so a device retry is idempotent at the
storage layer. `?sync=1` skips the queue for the case that needs an answer now —
measured at 7.6 s end to end for 60 s of audio.

Because the agent reaches the lifelog through MCP tools, resolving "さっきの話"
needs no dedicated query API and no context plumbing on the device: the agent
decides on its own to go and look.

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
