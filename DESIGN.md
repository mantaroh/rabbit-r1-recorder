# Design as built

What the system does now, and where it departs from the original specification.
Every divergence below was forced by something measured on the device or in the
data — the reasons are recorded so they can be re-argued if the facts change.

日本語: [`DESIGN.ja.md`](DESIGN.ja.md)

## What this is

A Rabbit R1 that answers questions about the conversation happening around it.

Double-press the side button, ask, and the answer arrives from a self-hosted
Hermes agent that has been given the preceding two minutes as context. The
device is otherwise silent: nothing is written to disk, nothing is uploaded.

It is **not** a continuous lifelog. It was built as one first, ran for 18 hours,
and the recording was then removed on purpose — see [Why the lifelog
went](#why-the-lifelog-went).

## Shape

```
  side button ──▶ KeyService (accessibility)
                       │ double press
                       ▼
  microphone ──▶ RecorderService ──▶ RingBuffer (150 s, memory only)
                       │                   │
                       │ VAD               │ on question: utterance + 2 min
                       ▼                   ▼
                 QueryController ──▶ PUT /v1/segments/{id}?sync=1
                                             │
                                    Cloudflare Access
                                             ▼
                                     Worker ──▶ R2 (audio, 1 day)
                                             └─▶ Whisper ──▶ D1 (text, 1 day)
                                                              │
  ChatActivity ◀── transcript                                 │
       │                                                      │
       └──▶ Hermes gateway ──▶ agent ──MCP: lifelog_recent────┘
```

Two things are worth noticing about that diagram.

The agent fetches its own context. There is no query API carrying a transcript
plus a context blob: the R1 submits an ordinary prompt, and the agent decides
whether to call `lifelog_recent`. That removed an entire endpoint from the
original design and made "さっきの話" work without special-casing.

The ring buffer is the only place audio lives by default. It is memory, it is
150 seconds long, and it is overwritten continuously.

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
- Then the two minutes before the question go up, the question follows with
  `sync=1`, and the transcript is handed to the chat client.

Round trip is roughly 8 seconds, of which about 4 is transcription.

## Retention

One day, enforced in two places because neither knows about the other:

| What | Where | How |
| --- | --- | --- |
| Audio | R2 | bucket lifecycle rule `expire-audio-1d` on `audio/` |
| Transcripts | D1 | hourly cron sweeping `started_epoch` |

Both measure from the segment's **own start time**, not from when it arrived.
An upload deferred until the device found Wi-Fi still expires on schedule.

## Why the lifelog went

The continuous version worked. It ran 18 hours unattended on battery with no
dropped frames, and the agent could summarise an arbitrary hour of the day. It
was removed because the question feature does not need it — two minutes of
context on demand does the same work — and because what it produced was mostly
noise:

- **Most of it was fiction.** Whisper, given a quiet room, returns its training
  data: `Thank you.`, `ご視聴ありがとうございました`,
  `Продолжение следует...`, `Hello everyone, welcome back to my channel`. Out
  of 1254 segments, over 100 contained "Thank you". The 3 a.m. hour was
  entirely hallucinated.
- **It cost ~$22/month and uploaded 2.4 GB a day** — fine on Wi-Fi, absurd on
  a SIM.

**That cost figure is for an ungated pipeline, and should not be used as an
argument against a lifelog as such.** The VAD was written, and it was only ever
wired to the question path — `query.tick(...)`. The lifelog wrote and shipped
every frame, silence included, which is also what generated the hallucinations
above: no segment of pure silence would have been uploaded had the VAD gated
the write. See below for what gating it would actually have saved.

The hallucination problem is now filtered server-side, so the lifelog could
come back. `Lifelog: ON` restores it — ungated, until the VAD is moved in front
of the write path.

## What a VAD in front would change

Measured from the 589 segments in the last 24 hours that carry Whisper's own
per-segment timings (the rest fell to a fallback model that reports none):

| Hour, JST | Voiced share of recorded audio |
| --- | --- |
| 03 (asleep, 1 segment) | 3.2 % |
| 12 – 19 (workday) | 4.4 – 19.9 %, mean ≈ 11 % |
| 20 – 23 (home, talking) | 10.2 – 52.8 %, mean ≈ 31 % |

Extrapolated across a full day including sleep, **roughly 11 % of what the
device records is speech.** Transcribing only that costs about $2.4/month
rather than $22, and ships ~260 MB/day rather than 2.4 GB.

Two things stop that from being the honest saving:

- `speech_ratio` is Whisper's word coverage, not an energy VAD's decision. An
  RMS gate also passes air conditioning, traffic and keyboards. The measured
  headroom is good — background RMS 352 against 2440–3536 for speech — but
  a noisy room passes more than 11 %.
- A 2 s hangover plus pre-roll pads every utterance, so scattered short
  remarks cost far more than their voiced seconds.

Call it **70–85 % saved, so $3–7/month**. Not $22, and not $2.4. The point
stands either way: gating the lifelog on speech changes its cost by an order of
magnitude, and that was never tried before switching it off.

## Rejecting hallucinated silence

`no_speech_prob` is useless here — it came back as **0**, confidently claiming
speech, for a segment transcribed as "Thank you. Thank you." at 3 a.m.

What separates them is how much of the segment Whisper actually placed words
in:

| | speech ratio | language confidence |
| --- | --- | --- |
| Asleep | 0.012 – 0.060 | `en` at 0.38 – 0.53 |
| Conversation | 0.589 – 0.707 | `ja` at 0.99 – 1.00 |

An order of magnitude apart, so the threshold sits at **0.15**, in open space.

The signals are stored and filtered at read time, not dropped at write time.
Transcription is billed per minute: a threshold baked into ingestion could only
ever be revised by paying to re-run the whole corpus.

## Cost

| | |
| --- | --- |
| Workers AI Whisper | $0.00051 / audio minute |
| R2 | $0.015/GB-month, egress free, 10 GB free tier |
| Questions at ~2.5 min of audio each | well under $1/month |
| The continuous lifelog it replaced, ungated | ~$22/month |
| The same lifelog with the VAD in front | ~$3–7/month, estimated |

## Operational notes

Three things about this device will bite anyone who forgets them.

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

That last one is why link state comes from a `NetworkCallback` rather than
`getActiveNetwork()`, and why the uploader re-reads `ConnectivityManager`
before every pass: `onLost` fires when *a* network disappears, not when the
device goes offline, and taking it at face value once stranded 325 segments on
a device that could ping the server in 39 ms.

## What is not built

- **VAD in front of the lifelog write path.** The gate exists and drives the
  question flow; it has never gated the recording. Anyone turning
  `Lifelog: ON` back on should wire it first — see above for the arithmetic.
- **Opus.** Implemented and building, toggle off. With the lifelog gone the
  volume no longer justifies the risk of an untested codec path.
- **TTS.** Answers are read, not spoken.
- **Wake word.** The gesture is the only trigger.
- **72-hour soak.** 18 hours passed cleanly; the battery lasts ~20, so longer
  runs need mains power.

## Measured hardware

See [`audioprobe/FINDINGS.md`](audioprobe/FINDINGS.md) for the numbers behind
the panel size, key codes, capture format, power draw and battery curve.
