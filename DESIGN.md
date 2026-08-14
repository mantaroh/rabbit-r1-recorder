# Design as built

What the system does now, and where it departs from the original specification.
Every divergence below was forced by something measured on the device or in the
data — the reasons are recorded so they can be re-argued if the facts change.

日本語: [`DESIGN.ja.md`](DESIGN.ja.md)

## What this is

**A Rabbit R1 that records, and keeps, the audio around it.**

The recording is the artefact. Everything else here — transcription, search,
the question feature — is what today's models happen to be able to do with it,
and all of it can be redone later against the same bytes. The audio cannot be
redone. That asymmetry decides nearly every argument in this document.

On top of the archive: double-press the side button, ask a question, and a
self-hosted Hermes agent answers it with the preceding two minutes as context.

## Shape

```
  side button ──▶ KeyService (accessibility)
                       │ double press
                       ▼
  microphone ──▶ RecorderService ──▶ RingBuffer (150 s, memory)
   48 kHz mono        │  │                │
                      │  └─ 60 s Opus segments ──┐
                      │ VAD                      │ on question: utterance + 2 min
                      ▼                          ▼
                QueryController ──▶ PUT /v1/segments/{id}
                                             │
                                    Cloudflare Access
                                             ▼
                                     Worker ──▶ R2 (audio, kept)
                                             └─▶ Whisper ──▶ D1 (transcripts)
                                                              │
  ChatActivity ◀── transcript                                 │
       │                                                      │
       └──▶ Hermes gateway ──▶ agent ──MCP: lifelog_recent────┘
```

The agent fetches its own context. There is no query API carrying a transcript
plus a context blob: the R1 submits an ordinary prompt, and the agent decides
whether to call `lifelog_recent`. That removed an entire endpoint from the
original design and made "さっきの話" work without special-casing.

## Capture format

48 kHz stereo, stored as Opus at 32 kbps per channel.

**Sample rate is the only decision here that cannot be revisited.** A recording
band-limited to 8 kHz stays band-limited however good the model reading it
gets. The system ran at 16 kHz for its first weeks, which was a permanent loss
for every hour it covered.

The microphone was never the limit. The device's audio policy advertises
`8000 16000 32000 44100 48000` on `Built-In Mic`, and a 48 kHz capture measured
on the bench has no cliff where the old Nyquist was:

| Band | RMS | | Band | RMS |
| --- | --- | --- | --- | --- |
| 300 Hz – 1 kHz | −55.8 dB | | 8.5 – 10 kHz | −71.7 dB |
| 2 – 4 kHz | −64.6 dB | | 11 – 13 kHz | −70.4 dB |
| 5 – 7 kHz | −68.1 dB | | 15 – 17 kHz | −75.5 dB |
| | | | 18 – 20 kHz | −80.0 dB |

An upsampled 16 kHz signal falls off a cliff above 8 kHz. This does not:
11–13 kHz carries more than 8.5–10 kHz. The content is real.

Storage is the opposite kind of decision — reversible, and about money. Opus is
lossy, but at 48 kHz it keeps the full band the microphone gives us for a small
fraction of what PCM costs: **550 KB per minute in stereo, roughly 280 GB per
year**. Storing 16 kHz PCM losslessly, as this system used to, spent more bytes
to preserve less of the room.

Both channels are kept, and the reason is not the one it was meant to be — see
[Direction](#direction-and-why-there-is-none). They are genuinely distinct:
after the Opus round trip the difference signal sits 15 dB below the channels
rather than at the noise floor, so the encoder is carrying real stereo and not
dual-mono.

The format travels with each segment in a sidecar written when it closes. The
uploader used to report whatever the recorder was configured for *at upload
time*, which is wrong for anything still queued when the recorder restarts
under different settings — a mono file was archived labelled stereo before this
was noticed. A recording described wrongly is decoded wrongly forever.

## Retention

**None.** No R2 lifecycle rule on `audio/`, no cron, no sweep.

An earlier version expired both audio and transcripts after a day. That was
built when transcription was mistaken for the point of the system; it deleted
the one thing that cannot be regenerated in order to save the storage cost of
keeping it. Removing it recovered 327 recordings that had already lost their
index — see [`cloudflare/lifelog/src/index.ts`](cloudflare/lifelog/src/index.ts)
for the note that now sits where `RETENTION_SECONDS` used to.

Cost, at R2's $0.015/GB-month, growing monotonically:

| | Year 1 | Year 5 | 5-year total |
| --- | --- | --- | --- |
| Opus 48 kHz stereo, ~280 GB/yr | ~$4/mo | ~$19/mo | ~$570 |
| Photographs, ~7 GB/yr | negligible | | |
| Whisper, gated to ~11 % of the day | ~$3/mo | | |

Transcription, not storage, is now the larger running cost — and it is the one
that can be cut without losing anything permanent, because the audio stays and
can be re-transcribed at any time. See below.

## What the VAD is for

Gating **transcription**, never storage. Everything recorded is uploaded and
kept; only the decision to spend a Whisper call is gated.

Handing Whisper a silent minute is what makes it hallucinate — it returns
`Thank you.` or `ご視聴ありがとうございました` with no signal that it invented
them. Its own `vad_filter` (now on) suppresses most of that, but only after the
call has been made and billed, and roughly **11 % of what the device records is
speech**: 4–20 % in workday hours, 10–53 % at home in the evening, near zero
asleep.

**Where the measurement happens is forced by the codec.** Opus here is
effectively constant bit rate — a silent minute averages 271,859 bytes against
293,769 for a talkative one, with the ranges fully overlapping — so nothing
useful can be read off the packets without decoding. The Worker would need a
WASM Opus decoder to see a waveform; the device has the PCM already and is
computing RMS on every frame regardless.

So the device sends **one byte of loudness per second**, base64, alongside the
upload — 60 bytes a minute, about 500 KB a year — and the Worker applies the
threshold. The device reports a measurement; the Worker owns the policy.

| | RMS ÷ 16 |
| --- | --- |
| Quiet room, measured over a full minute | 6 – 16 |
| Threshold | **32** |
| Speech | 152 – 221 |

A segment is skipped only if fewer than 2 seconds of the 60 clear the
threshold. The asymmetry is deliberate: a wrong skip loses a transcript until
someone notices, a wrong transcribe costs $0.0005.

Nothing about this is irreversible. The envelope is stored, so
`POST /v1/admin/rejudge` re-applies a different threshold to history — with
`?dry=1` to see what would change — and re-queues anything newly judged voiced,
without reading a byte of audio. That is the reason to store the measurement
rather than only the verdict.

## Photographs

One frame in each direction every five minutes, 640 px, ~60 KB each.

The lens sits on a motorised arm with a single sensor, so "front and back"
means physically rotating between two shots, and that arm is audible on a
device whose purpose is to record audio. Two rules follow.

**A cycle is deferred while anyone is speaking**, up to two minutes, so the
motor does not run over a conversation. The shot is taken in the pause
afterwards.

**A cycle is skipped entirely when nothing has happened.** A room nobody has
entered produces two frames identical to the last two; a room with a
television produces the same thing while being far from silent. So a baseline
tracks what the room normally sounds like — a slow average over roughly ten
minutes — and a cycle only runs when the window since the last pair either
contained speech or peaked meaningfully above that baseline. A television left
on raises the baseline along with the peak and stops qualifying. Speech always
qualifies.

The per-second level this reads is the same one the VAD envelope already
computes.

## Direction, and why there is none

The stereo capture was meant to let the device point the lens at whoever was
talking. It cannot, and the reason is worth recording so it is not attempted
again.

**Level differences do not work.** Speaking from in front and then from behind
differs by 0.2 dB broadband and 0.3 dB above 4 kHz, with the distributions
overlapping. Sound whose wavelength exceeds the obstacle diffracts around it,
and speech at 100 Hz – 4 kHz is 8.6 cm – 3.4 m against a body about 8 cm
across. Measuring the high band, where the device is finally opaque, changed
nothing.

**Time of flight does not work either, for a different reason.** Diffraction
does not remove propagation delay, so cross-correlation was the better
instrument — and it found something: a frontal talker produces a lag of
exactly zero in 30 of 35 windows. A talker behind produces lags scattered from
−1 to −24 with no peak, which is a weak direct path being outvoted by
reflections, not a delay.

A frontal source landing reliably at zero says the two capsules are equidistant
from it — a left/right symmetric pair. A source directly behind is equidistant
too, so it also reads zero. That is the front-back ambiguity of any two-element
array, and it is geometry rather than tuning.

The arm compounds it: it rotates through the front-back axis, which is exactly
the axis the microphones cannot resolve. What they could resolve, left versus
right, is the one direction the arm cannot point.

Photographing both directions every cycle sidesteps all of this. Whoever is
talking is in one of the two frames.

## Asking a question

```
LIFELOG ──double press──▶ ARMED ──speech──▶ CAPTURING ──silence──▶ PROCESSING
   ▲                        │                                          │
   └──────────────────── timeout ─────────────────────────────── answer ┘
```

- **The gesture** reaches an AccessibilityService. Nothing else can see the
  side button; see [Operational notes](#operational-notes).
- **ARMED** buzzes and shows `ASK / Speak now`. The button is physically the
  power key, so the press dims the screen — without a screen and a buzz the
  device gives no sign it is listening. Times out after 8 s; a second press
  cancels.
- **CAPTURING** starts when the VAD hears speech, and captures from 300 ms
  before the press, or from where the speech actually started if that was
  earlier. Pressing mid-sentence keeps the words already in flight.
- **PROCESSING** begins after 2 s of silence. Not 1.2 s as originally
  specified: measured against real Japanese speech, 1.2 s cut sentences off
  mid-thought.

Round trip is roughly 8 seconds, of which about 4 is transcription.

## Rejecting hallucinated silence

Whisper, given a quiet room, returns its training data: `Thank you.`,
`ご視聴ありがとうございました`, `Продолжение следует...`. Out of 1254 segments
from one night, over 100 contained "Thank you" and the 3 a.m. hour was entirely
invented.

`no_speech_prob` is useless here — it came back as **0**, confidently claiming
speech, for one of those. What separates them is how much of the segment
Whisper actually placed words in:

| | speech ratio | language confidence |
| --- | --- | --- |
| Asleep | 0.012 – 0.060 | `en` at 0.38 – 0.53 |
| Conversation | 0.589 – 0.707 | `ja` at 0.99 – 1.00 |

An order of magnitude apart, so the threshold sits at **0.15**, in open space.

Filtered at read time, never at write time — and now for a second reason.
Beyond being able to revisit the threshold without paying to transcribe again,
a hallucinated transcript is a defect in the *index*, not in the recording. The
audio behind it is as real as any other minute.

## Operational notes

Four things about this device will bite anyone who forgets them.

**The side button belongs to the launcher.** CarrotOS takes it from
`com.r1.launcher/.PowerService`, an AccessibilityService, so it is handled
before window focus matters — which is why it works with the screen off and
why no ordinary app can intercept it. The launcher broadcasts nothing. A second
AccessibilityService requesting key filtering *does* receive the same events,
7 ms later, and returning `false` leaves the launcher's behaviour intact. No
`/system` patching, no root.

**Reinstalling silently stops key delivery.** The service rebinds and logs
`connected`, but `onKeyEvent` is never called again, and the launcher keeps
working so the device looks fine. Every install must toggle the service off and
on — and must do so *after* `am force-stop`, because stopping the app drops it
from the enabled list.

**A newly installed app has no network.** CarrotOS sets `REJECT_ALL` in the
network policy. The device shell pings the host in 18 ms while the app cannot
resolve anything, and `getActiveNetwork()` returns null — which is also what it
returns when there is genuinely no network, with no way to tell the two apart.
That is why link state comes from a `NetworkCallback` rather than
`getActiveNetwork()`, and why the uploader re-reads `ConnectivityManager`
before every pass: `onLost` fires when *a* network disappears, not when the
device goes offline, and taking it at face value once stranded 325 segments on
a device that could ping the server in 39 ms.

**Nothing starts recording by itself.** A microphone foreground service cannot
be started from the background on Android 14, so recording has to come through
the Activity: `am start -n com.r1.audioprobe/.MainActivity --ez autostart true`.
There is no boot receiver yet, which means a reboot stops the archive until
someone notices.

## What is not built

- **Restart after reboot.** The gap above. An archival device that stops
  because the battery died and came back is broken, and this one is.
- **Confirmation that the gate passes real speech.** The silent side is
  measured; the speech side rests on FINDINGS' 2440–3536 RMS for speech being
  5× the threshold. The first real conversation settles it, and a wrong
  threshold costs nothing permanent — the audio is archived and `rejudge`
  re-queues.
- **Integrity checking beyond `/v1/admin/reconcile`.** No checksums, no second
  copy. One bucket, one account, one bad decision away from the whole archive.
- **A context slice that is not 23 MB.** The two minutes handed to a question
  still go up as WAV, which stereo at 48 kHz has now doubled. It is uploaded
  synchronously, before Whisper even starts, so it is the largest part of the
  round trip. It should be Opus.
- **Recovery of the side button after a reboot.** Recording resumes; the
  accessibility service is dropped from `enabled_accessibility_services` and
  has to be re-enabled by hand, so the question feature stays dead until
  someone notices. The root shell on 127.0.0.1:1337 could rewrite it.
- **TTS.** Answers are read, not spoken.

## Measured hardware

See [`audioprobe/FINDINGS.md`](audioprobe/FINDINGS.md) for the numbers behind
the panel size, key codes, capture format, power draw and battery curve.
