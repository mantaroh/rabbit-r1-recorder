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
  accelerometer ─▶ Motion ─┬─ posture      │
                           └─ shake ──▶ camera / chat
                       ▼
  microphone ──▶ RecorderService ──▶ RingBuffer (150 s, memory)
   48 kHz stereo      │  │                │
                      │  ├─ 60 s Opus segments ─┐
                      │  │  + RMS envelope      │ on question: utterance + 2 min
                      │  ├─ Timelapse ──▶ jpeg ─┤
                      │  └─ Positions ──▶ fixes │
                      ▼                         ▼
                QueryController ──▶ PUT /v1/segments/{id}, /v1/photos/{id}
                                    POST /v1/positions
                                             │
                                    Cloudflare Access
                                             ▼
                                     Worker ──▶ R2 (audio + photos, kept)
                                             ├─▶ Whisper ──▶ D1 (transcripts)
                                             ├─▶ vision  ──▶ D1 (captions)
                                             └─────────────▶ D1 (fixes)
                                                              │
  ChatActivity ◀── transcript   browser ──▶ GET / , /v1/map/* ┤
       │                                    timeline · map    │
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
| Photographs, a few GB/yr | negligible | | |
| Map archive, 1.48 GB once | ~$0.27/yr, flat | | |
| Whisper, gated to ~11 % of the day | ~$3/mo | | |
| Captioning, ~$0.00026 an image | under $1/mo | | |

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

One frame in each direction per cycle, 640 px, ~60 KB each.

The lens sits on a motorised arm with a single sensor, so "front and back"
means physically rotating between two shots, and that arm is audible on a
device whose purpose is to record audio. Four rules follow.

**The cadence depends on where the device is.**

| | Interval |
| --- | --- |
| On the home Wi-Fi | 15 minutes |
| Anywhere else | 5 minutes |
| Home, and quiet, and dark | stopped |

Home is where the interesting frames are rarest — the same room, the same
furniture, hour after hour — and away is where they are densest and least
repeatable. The verdict is read from the SSID, and it is now written to the
metrics whenever it changes, because getting it wrong does not fail: it
silently triples the shutter rate and reads as a busy timelapse rather than as
a fault. It did exactly that for 48 minutes before anyone noticed.

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
qualifies. The stop rule is conjunctive: quiet *and* dark *and* home, so a dark
room away from home is still photographed.

**In the stand, only the screen side is photographed.** Sitting in its stand the
rear camera is looking at a wall that will be the same wall in fifteen minutes.
Skipping it halves the cycle and saves an arm swing on a device whose arm can be
heard.

The stand is recognised by its angle, and the angle is unmistakable:

| Resting position | Reading | Tilt |
| --- | --- | --- |
| Upright on a desk | (0.08, 9.60, −0.07) | 0° |
| Upright on a desk | (0.06, 9.58, 0.61) | 3.6° |
| **The printed stand** | **(0.04, 8.75, 4.03)** | **24.7°** |

The stand is [Rabbit r1 Stand](https://makerworld.com/en/models/406818-rabbit-r1-stand)
by dylanfonz, printed on a 3D printer here. **24.7° is a property of that model,
not of stands in general** — a different design, or the device seated in a case,
will read something else, and the band below is the thing to check first if the
photographs quietly go back to two a cycle.

Twenty degrees of separation, and the stand reads steady to a hundredth of a g
across samples. A desk that is not quite level cannot cross that, so the band is
12–45° with about eight degrees of margin on each side. The upper bound is not
arbitrary either: past roughly 47° gravity lands more on Z than on Y and the
device stops classifying as upright at all.

This was first written as "upright, still and charging", on the assumption that
a stand's tilt could never be told from a desk's. The stand had not been printed
yet. Now that it has, **the direct measurement beats the proxy in both
directions**: a cable does not turn a desk into a stand, and a full battery —
which stops reporting as charging — does not take the device out of one.

A device standing vertically on a desk therefore still photographs both ways.
That is the safe direction to be wrong in: a redundant frame of a wall costs one
shutter, a missed frame of a room is gone.

The front frame is never the one dropped. It holds the room and the people, and
it is where the darkness measurement comes from — skipping it would quietly
disable the asleep rule above.

The per-second level this reads is the same one the VAD envelope already
computes.

**Each frame is captioned once, by a vision model, on arrival.** About
$0.00026 an image — a rounding error against the audio — and it is what makes
the photographs searchable at all, since nothing else about a JPEG is. The
caption is an index entry, not a record: it is filtered and re-derivable, and
the frame behind it stands on its own.

Orientation is per-camera and was calibrated against the stock camera app:
front 180°, rear 0°. Both were overridden to 0° once, on the reasoning that a
sensor is a sensor, which turned every selfie upside down. The original values
were right.

## Position

A fix every fifteen minutes at home and **every minute away** — the timelapse's
split, for a different reason and now at a different rate.

Five minutes away was the first guess and it drew badly. At walking pace that
is four hundred metres between points, and a line through those cuts every
corner it meets. A minute is about eighty metres, close enough to the fixes'
own 8–14 m accuracy that the line stops being a guess about the route.

What made that affordable is that the cost was measured rather than assumed.
Across ten fixes on 2026-08-17 a warm receiver answered in **2.2 to 6.0
seconds**, mean 3.5 — so a minute apart it is powered about 6 % of the time,
against a measured 75 mA baseline. **The expensive case is failure, not
success**: an attempt with no sky runs a flat 30 seconds before the platform
gives up, which is why the home interval is not the one that was shortened.

**There is no network location on this device.** `dumpsys location` lists
passive, fused and gps and nothing else: CarrotOS ships no Play Services, so
the AOSP fused provider is a thin wrapper over GPS and passive only repeats
what something else asked for. Indoors that means no fix at all, however long
you wait, and waiting costs the receiver. At home the position is therefore
both already known and mostly unobtainable, and spending radio to fail every
five minutes buys nothing.

**A failed fix is written down.** A gap in a track otherwise reads as "the
device was off", which is a different fact from "the device was indoors", and
only one of the two is a fault worth chasing.

Positions are unlike segments and photographs in a way that changes the
durability contract. There is no object in R2 behind them — the row *is* the
artefact — so a lost batch is simply lost. Hence a batch POST, idempotent on
`(device_id, recorded_epoch)` so the device can resend anything it is unsure
about, and a local queue handed off by rename rather than truncate so a fix
taken mid-upload cannot fall down the gap between read and write.

Latitude and longitude are range-checked rather than merely parsed. A row that
cannot be a place on Earth is worse than a missing row: it is a hole in the map
that looks like a journey.

**Accuracy is stored and never filtered on.** A 2 km fix and a 5 m fix drawn
the same way turn a walk down a street into a walk through the buildings beside
it, so the viewer is given the number and decides. It can grey out a bad fix;
it cannot invent one that was discarded on the way in.

## Placement, and shaking

One accelerometer listener, two derived signals: the low frequencies say which
way is down, the high frequencies say how hard the device is moving. The same
arrangement as the per-second RMS, which already feeds both the speech gate and
the timelapse — there is no reason to run a sensor twice to answer two
questions about one motion.

The raw accelerometer, not the platform's `GRAVITY` and `LINEAR_ACCELERATION`.
Those look like the obvious choice and are AOSP fusion sensors here, so asking
for them can pull the gyroscope into the fusion to produce what a two-line
filter already gives. The accelerometer also reports `minRate=50Hz`, so there
is no slower setting to ask for: registering at all means 50 Hz.

Posture is debounced three seconds and motion is not announced at all. Picking
the device up sweeps through two or three postures on the way, and `moving`
flips every couple of seconds in a pocket, which would turn the metrics log
into a pedometer.

**Shakes are counted in peaks, and the mapping was measured.** How many peaks a
wrist produces when its owner shakes it twice is a fact about the wrist:

| Intent | Peaks measured |
| --- | --- |
| "twice" | 2, 2, 2, 2, 2 |
| "three times" | 3, 4, 4, 5, 4 |

The distributions do not overlap, so the boundary has one place to sit: two
peaks opens the camera, three or more opens the chat. The guess this replaced
assumed the count tracks the word, and it does not — three shakes of a wrist
reverse direction four times — which put the first gesture of the session on
the wrong side.

The threshold is 14 m/s². Forty-seven minutes passed between the deliberate
gestures and the next one with no false detections, on a device that was picked
up, set down and turned over in between. That is evidence and not a verdict:
the device was on a desk, and walking is where a shake threshold usually fails.

## The map

Self-hosted, entirely. The archive, the style, the renderer, the fonts and the
icons all come out of R2 through the Worker, and the page makes no third-party
request at all.

The immediate reason is that the PMTiles distributor asks for exactly this —
「本サイトのPMTiles URLをアプリから直接参照する利用はご遠慮ください」 — and
offers the archive for download instead. The other reasons were already true: no
API key, no per-request billing, no third party in the path of a private log,
and R2 charges nothing for egress. 1.48 GB costs about twenty-five cents a year.

**PMTiles is one file addressed by byte offset**, so serving it means answering
range requests — which this Worker already did, for dragging the scrub bar on a
minute of audio. Same mechanism, different bytes. The style's one reference back
to the original host is rewritten on the way into the bucket, or every map view
would hotlink the thing that was mirrored to avoid exactly that.

Fonts are the one asymmetry. There are 256 glyph ranges per fontstack and six
stacks in this style: mirroring them all is 1536 files, nearly all for scripts
this map will never draw, fetched in one burst from a volunteer-run server for
the sake of a personal archive. They are pulled through on first use instead —
a viewport asks for about a dozen, once, and after that they are local. 812 ms
for the first request to a CJK range including the store, 282 ms for the same
range afterwards.

Fixes are drawn as points sized by their accuracy, joined by a line. The line
gives the points an order and nothing more: a straight segment between two
sightings five minutes apart is not a claim about the route between them, and
the points stay visible so it cannot be read as one.

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

## The day, and who decides it

The device runs itself. **07:00 starts recording, 23:00 asks whether to stop**,
and if nobody answers it asks again every ten minutes — while the recording
keeps running. That asymmetry is the whole design of the prompt: an unanswered
question must not be able to end the archive, because the most likely reason
for silence is that nobody was there.

**A decision lasts until the next morning, not until midnight.** The first
version compared against "today", so answering "stop" at 23:04 was undone at
00:00 and the device resumed at 00:09 — found in the logs, not by noticing.
The evening answer is also persisted, because reinstalls and reboots are
frequent here and either would otherwise quietly overturn an instruction that
was given deliberately.

The same rule now covers everything that decides *how* the recording is made.
Wake lock, capture source, codec and the recording flag used to be fields on
the settings screen, so every launch silently reset them to their defaults.
Anything that decides how the recording is made has to outlive the screen that
sets it.

## What the device shows

Three screens, in the order they are met:

- **Home** is a five-row menu — 話す, 記録, 待受, 設定, Hermes — turned through
  with the wheel, one row lit at a time. It was a column of small Android
  buttons first, which on this device means the one interaction it is worst at:
  hitting a 30 dp target on a 240 dp panel. The app registers as a HOME
  activity, so the device returns here rather than to the stock launcher.
- **Standby** takes over after a minute of no input, **and only while
  charging**. Whatever it shows holds the panel awake, and doing that on
  battery turns a day of recording into an afternoon. The wheel steps between
  screens and never dismisses; leaving is the side button's job.
- **The evening prompt** is styled after the R1 rather than after Android,
  because it is the screen most likely to be seen by someone who was not
  looking for it.

All three are driven by Android's focus system rather than by intercepting keys
at the Activity. A clickable view is focusable, so it takes the D-pad before
`onKeyDown` ever runs — the first version of every one of these screens
overrode `onKeyDown` and the wheel did nothing at all.

The side button doing two jobs at once is a trap in its own right. It activates
the focused row *and* reaches KeyService, which waits 440 ms to decide the
press was single and then dismisses standby — so picking 待受 from the menu
opened standby and immediately closed it again. The handler is now told when
the button went down, and refuses to dismiss anything that appeared after
that.

## Browsing it

`GET /` serves a single-file web UI: one day at a time, audio and photographs
interleaved in time, with search across the whole archive, and a second tab
holding the same day's track. No build step and no dependencies — the Worker is
the entire backend and this is the entire frontend. The map is the one thing
that needs a library, and it loads on first use rather than on every open; see
[The map](#the-map).

It authenticates by trusting Cloudflare Access, because a browser cannot attach
a bearer token to a navigation. See [Operational notes](#operational-notes) for
what that assumption rests on.

Silent segments are listed and hidden behind a toggle rather than omitted. The
page has to be able to agree with the bucket: a recording with no transcript is
still a recording, and a page that quietly drops them would make the archive
look smaller than it is.

## Operational notes

Five things about this device will bite anyone who forgets them.

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

**Recording can only be started from a visible Activity.** A microphone
foreground service cannot be started from the background on Android 14, so
everything that wants recording to resume — the boot receiver, the morning
start, a shell — goes the long way round through the Activity:
`am start -n com.r1.audioprobe/.MainActivity --ez autostart true`. The `-f
0x20000000` flag matters when the Activity is already up; without it `am start`
brings the task forward and throws the extras away, and the command looks like
it worked.

**Nothing outside Cloudflare Access is checked.** The Worker accepts any
request carrying a `Cf-Access-Jwt-Assertion` header without verifying the
signature. That is sound only while Access covers every path of the custom
domain and there is no `workers.dev` route — both true today, and both a single
dashboard edit away from not being. The device's bearer token is the second
factor, and it also opens the admin endpoints, one of which rewrites objects in
R2. Ingest and administration should not share a credential; they do.

## What is not built

- **A second copy of the archive.** Bytes are now checksummed end to end — the
  device hashes what it read off its own disk and R2 refuses a write that does
  not match — so corruption in transit is caught. Loss is not. One bucket, one
  account, one bad decision away from all of it.
- **Separate credentials for ingest and administration.** See above.
- **A context slice that is not 23 MB.** The two minutes handed to a question
  still go up as WAV, which stereo at 48 kHz has now doubled. It is uploaded
  synchronously, before Whisper even starts, so it is the largest part of the
  round trip. It should be Opus.
- **Recovery of the side button after a reboot.** Recording resumes; the
  accessibility service is dropped from `enabled_accessibility_services` and
  has to be re-enabled by hand, so the question feature stays dead until
  someone notices. The root shell on 127.0.0.1:1337 could rewrite it.
- **TTS.** Answers are read, not spoken.
- **A shake threshold set from measurements.** 14 m/s² was reasoned about, not
  measured, and a walk tripped it once in 55 minutes — which is why the gesture
  now requires the screen to be on. That removes the symptom without answering
  the question; peak magnitudes are being logged so the number can be set the
  way the two-versus-three boundary was. See
  [`BACKLOG.md`](BACKLOG.md#unverified-the-shake-threshold).
- **A conversation mode**, designed and shelved rather than merely absent. The
  measurement half is built — `GET /v1/talk` reports how many minutes of each
  day contained speech — because a mode that declines to run on days you
  already talked needs a threshold taken from real days. See
  [`BACKLOG.md`](BACKLOG.md).

## Measured hardware

See [`audioprobe/FINDINGS.md`](audioprobe/FINDINGS.md) for the numbers behind
the panel size, key codes, capture format, power draw and battery curve.
