# Backlog

Work that is designed, or built, but not finished — kept here rather than in a
chat log, so that picking it up again means reading rather than re-deciding.

Three kinds, and they are not the same kind:

- **Unverified** — shipped, but a claim the documentation makes rests on
  something nobody has observed. Cheap to close and dangerous to forget,
  because built-and-unproven reads exactly like built-and-working.
- **Shelved** — whole features worked out far enough to cost and to find the
  hard parts, then put down deliberately.
- **Agreed** — designed, chosen, and about to be built. It sits here until it
  runs on the device, at which point the description moves to DESIGN.md and
  only what remains unproven stays behind.

DESIGN.md's [What is not built](DESIGN.md#what-is-not-built) stays what it is:
one line per gap. The detail lives here.

---

## Closed: privilege separation and the lost-device switch

**Done 2026-08-17.** Kept here only as the record of what the answer was.

The device holds a credential scoped to the seven routes it uses. `ADMIN_TOKEN`
opens the destructive endpoints from a laptop, `AGENT_TOKEN` opens `/mcp` from
the Hermes gateway, and a person at the browser is admitted by their Access
assertion carrying `email` — a service token carries `common_name` with an
empty `sub` instead, which was read off a live request rather than taken from
the documentation. So the device dropping its bearer no longer passes as the
browser.

And `POST /v1/device?state=lost` kills the device credential outright, from a
button on the web page. Read from D1 on every device request rather than
cached: the first version cached it per isolate for thirty seconds and the
drill caught one isolate refusing an upload while another still served a read.

---

## Unverified: whether a deliberate shake clears 22 m/s²

**Reopened 2026-08-18** by three false camera launches in ninety seconds while
the device was being carried.

### What the accidents measured

The magnitude logging added the day before did its job:

| time | peaks | peak m/s² | fired |
| --- | --- | --- | --- |
| 19:35:51 | 2 | 17 | camera |
| 19:36:04 | 2 | 14 | camera |
| 19:36:33 | 2 | 17 | camera |
| 19:37:13 | 2 | 18 | blocked, screen off |

All two-peak runs, all just over the guessed threshold of 14, and three of them
with the screen on — so the screen-on gate added the day before could not help.
Peak count cannot separate these from a deliberate double shake, because a
deliberate double shake is also two peaks.

### What is unproven

The threshold is now 22, which clears the whole observed accident cluster with
four to spare. **Nobody has measured a deliberate gesture's magnitude.** They
were not being recorded when the two-versus-three boundary was calibrated, so
22 might be under a real shake, or over it.

The test is the same one that worked before: shake it twice, then three times,
a few of each, then

```bash
adb shell "run-as com.r1.audioprobe grep shake files/probe.jsonl"
```

If deliberate gestures come back at 25 and above, 22 is right and this closes.
If they land near 18, the gesture cannot be told from a stride by strength, and
the fallback is `span_ms` — now logged alongside — because a deliberate shake
is a burst of a few hundred milliseconds while a carried device produces pairs
spread across a stride.

---

## Unverified: whether the night counts as talking

**Raised 2026-08-20**, sideways, from asking whether sleep could be read out of
the recording.

`GET /v1/talk` states in its own response what it cannot separate: owner,
family member, the far side of a video call, television. **Sustained non-speech
is not on that list, and nobody has looked.**

The mechanism is not subtle. The voiced test is a loudness threshold —
`VOICED_RMS = 32`, chosen against measured levels of 11 quiet, 22 background
activity, 152–221 speech. Snoring is loud. It clears 32 without difficulty, so
a snoring minute counts as a voiced minute, and being over `MIN_VOICED_RATIO`
it is also handed to Whisper and billed, to come back either empty or invented.

Two things rest on it. `/v1/talk` is the built half of the conversation mode
below, and that gate's threshold is supposed to come from a fortnight of real
days — a fortnight with the nights folded into it is the wrong fortnight. And
the hallucination work assumed the expensive case was silence; silence is
handled, while a loud minute with no words in it is not.

The check needs no new code, only the columns 0004 and 0005 already store:

```bash
cd cloudflare/lifelog && npx wrangler d1 execute r1-lifelog --remote --command "
SELECT (started_epoch + 32400) / 3600 % 24 AS hour_jst,
       COUNT(*) AS segments,
       SUM(CASE WHEN voiced_ratio >= 0.033 THEN 1 ELSE 0 END) AS voiced,
       SUM(CASE WHEN voiced_ratio >= 0.033 AND status = 'transcribed'
                 AND speech_ratio IS NOT NULL AND speech_ratio < 0.15
                THEN 1 ELSE 0 END) AS loud_but_wordless
  FROM segments WHERE kind = 'lifelog'
 GROUP BY hour_jst ORDER BY hour_jst"
```

If `loud_but_wordless` piles up between 01 and 05 while the daytime hours stay
clear, this is confirmed and the night is being counted and paid for. If it is
flat across the day it is something else, and this closes.

The repair would not be a higher threshold — snoring really is as loud as the
test says. It is a second axis: **loud and wordless is not conversation**, and
`speech_ratio` already separates the two. Note that this is the first step of
the sleep item below with its sign reversed: what is contamination to
`/v1/talk` is the signal there.

---

## Shelved: 会話モード — a conversation mode

**Shelved 2026-08-17.** Design agreed, technical route established, not started.

### Why

Working remotely means days with almost no conversation, and conversation
frequency is not a soft variable. The JPHC cohort followed 50–79 year-olds for
over a decade: people who spoke with someone less than once a month carried
**2.06×** the dementia risk of people who spoke daily, and talking with several
people daily fell to 0.88× and 0.80×. Frequency, not duration and not content,
is what the number tracks.

The literature on AI as the conversational partner is genuinely two-sided, and
both sides constrain the design:

- **It works.** Generative-AI conversation services measurably improved memory
  and reduced depressive symptoms in older adults, largely by remembering past
  conversations and drawing out personal recollection.
- **It backfires two ways.** Delegating the thinking produces *cognitive debt* —
  MIT (2025) measured markedly lower brain activity and worse subsequent recall
  in people who handed writing and reasoning to a model. And because an AI
  never disagrees and never gets tired of you, it can displace the harder human
  conversations rather than supplement them, accelerating the isolation it
  appears to relieve.

### The reason this device is the right place for it

Any device can host a chatbot. **This one already knows how much its owner
talked today** — it has measured a voiced ratio every minute since 2026-08-12.
That makes it possible to build the one safeguard the research actually asks
for: an AI that structurally refuses to be a substitute, by declining to run on
days when there was already conversation.

Without that measurement the feature is just another chatbot with a warning
label. With it, the warning is enforced.

### Already built

`GET /v1/talk?days=N` (`cloudflare/lifelog/src/index.ts`, committed 878c844,
**not yet deployed**) reports per local day: minutes recorded, minutes whose
envelope cleared the VAD threshold, minutes where Whisper found real words.

It stands on its own — it answers "how much do I actually talk" with no model
and no cost — and it is the prerequisite for everything below, because the gate
threshold has to come from a fortnight of real days rather than from a guess.

Its limitation is load-bearing and is repeated in the response body: **it
measures minutes containing speech, not conversation.** Owner, family member,
far side of a video call and television are not distinguished. Fine for someone
alone in a room; worthless on a train.

### Design as agreed

**Session shape: the agent interviews you about your own day.** It pulls the
actual transcripts of today and yesterday and asks about them. This is the form
no other device can offer — the reminiscence research relies on the AI
remembering the conversation, whereas this one has the recording. It also lands
exactly on the research's distinction between *the same old stories* (which do
nothing) and *recent events* (which are the exercise).

**The gate: it does not open on days you already talked.** Above the threshold
the device shows the number and closes. Enforced in the Worker at token-mint
time, not on the device — a gate the client evaluates is a gate the client can
skip.

**Caps: 10 minutes, once a day.** A supplement has a size.

Rules for the agent, each answering a specific finding:

| Finding | Rule |
| --- | --- |
| Same old stories do nothing; recent events are the exercise | Material is today's and yesterday's transcripts, never older unless asked |
| Bidirectional: ask, recall, empathise | The agent asks and the human answers. It does not summarise or conclude |
| Cognitive debt from delegated thinking | No research, drafting or summarising in this mode — that is what the ordinary chat is for |
| Constant affirmation drives avoidance of people | It may disagree, must ask for reasoning, and may not answer with bare agreement |
| Must not replace human contact | The gate above |

### Technical route

Speech-to-speech rather than STT → LLM → TTS. Going through text puts a pause
in every turn, and the pause is what stops it feeling like conversation.

**Model:** OpenAI `gpt-realtime-2.1` / `gpt-realtime-2.1-mini`, announced
2026-07-06. Both do speech-to-speech; the mini tier gained reasoning and tool
use in this release. Tier undecided.

| | audio in / M | audio out / M | 10-min session | ~150 sessions/yr |
| --- | --- | --- | --- | --- |
| `gpt-realtime-2.1` | $32 | $64 | ~$0.42 | ~$63/yr |
| `gpt-realtime-2.1-mini` | $10 | $20 | ~$0.13 | ~$20/yr |

Cached text input is $0.4/M, so priming each session with the day's transcripts
is nearly free. Note that the gate is also the cost control: run daily and the
full tier is $153/yr, more than the rest of the system put together.

**Credential flow.** The API key cannot live on the device — every installed app
on CarrotOS can take a root shell, so a key on the device is not a key. The
Worker mints a short-lived client secret and returns only that:

```
device ──POST /v1/session──▶ Worker
                              ├ check today's spoken minutes in D1
                              │    over threshold → refuse, return the number
                              ├ bake today's transcripts into instructions
                              └ POST /v1/realtime/client_secrets ──▶ ek_…
device ──wss://api.openai.com/v1/realtime  (connects with ek_…)
```

Minting on the server also means the day's material is already in the session,
so there is no second round trip and the device needs no lifelog credentials on
this path. Beta tutorials that POST to `/realtime/sessions` are stale; the
current endpoints are `/v1/realtime/client_secrets` and `/v1/realtime/calls`.

Hermes stays where it is. The ordinary chat and the side-button question keep
going through the gateway and its MCP tools; only this mode branches to OpenAI,
because latency is the whole point of this one and is not the point of those.

### Unsolved, and needs the device to solve

1. **Microphone contention.** The recorder holds `AudioRecord` on `MIC` around
   the clock and a second capture client will not simply work alongside it. The
   likely answer is to branch off the existing `RingBuffer`, which already has
   the PCM: 48 kHz stereo down to 24 kHz mono is an exact 2:1 decimation plus a
   channel average. It also means the session lands in the archive like every
   other minute, which is the right outcome.
2. **Acoustic echo.** Played through the speaker, the model hears itself and
   interrupts itself. Platform AEC comes with `VOICE_COMMUNICATION`, which the
   lifelog deliberately does not use — it captures raw `MIC`. So either
   half-duplex over the speaker (suppress input while the agent talks, and lose
   barge-in) or full duplex with an earphone. **This decides whether the
   conversation can be interrupted, which is most of what makes it feel real.**
   Undecided.
3. Whether a platform TTS engine exists on CarrotOS — now moot on this route,
   but it decides the fallback if speech-to-speech is abandoned.

### Explicitly not being built

**A screening feature.** The same transcripts would support tracking vocabulary
diversity, speech rate and utterance length over months, and the cited systems
reach 80–90% accuracy at flagging MCI. A trend line of one's own speech is a
measurement and would be honest. A verdict is not: an 80–90% accurate
classifier run daily against one person generates false alarms constantly, and
this device has no business telling its owner it suspects cognitive decline.
If this is ever built it is a graph with no conclusion attached, and it is a
separate decision from the one above.

### Sources

- [Introducing gpt-realtime](https://openai.com/index/introducing-gpt-realtime/)
- [gpt-realtime-2 model reference](https://developers.openai.com/api/docs/models/gpt-realtime-2)
- [gpt-realtime-2.1 / -2.1-mini announcement](https://community.openai.com/t/new-realtime-models-on-the-api-gpt-realtime-2-1-and-gpt-realtime-2-1-mini/1385896)
- [Realtime API over WebSocket](https://developers.openai.com/api/docs/guides/realtime-websocket)
- [Realtime API pricing, measured](https://hackernoon.com/openai-realtime-api-pricing-in-2026-real-world-data-from-4000-measured-sessions)

---

## Shelved: telling one voice from another

**Shelved 2026-08-20.** Costed and the hard parts found; not started. Raised by
asking whether the server could tell speakers apart in a day of recording.

### What it would fix

`GET /v1/talk` carries a limitation load-bearing enough to be repeated in its
own response body: it measures minutes containing speech, not conversation.
Owner, family member, the far side of a video call and the television all count
the same. That is the sentence a speaker signal would delete, and the gate in
the conversation mode above rests on exactly that distinction — a threshold
that cannot tell a conversation from a television is measuring the wrong thing.

The archive would gain the query it does not have: what a named person said,
rather than what was said in a room.

### One word, two features, and they cost differently

| | What it produces | What it needs |
| --- | --- | --- |
| **Diarization** | "speaker A / speaker B" — how many voices, and which turn belongs to which. No names | A transcriber that emits speaker turns |
| **Identification** | "this is the owner", "this is 妻" | Enrolled voiceprints, an embedding per utterance, and clustering across the archive |

The useful queries all want the second one. The first is a step on the way, not
a smaller version of it.

### The pipeline has neither

Whisper returns a string and nothing else (`cloudflare/lifelog/src/index.ts`,
`runWhisper`). `segments` has no speaker column; migrations 0004 and 0005 added
quality and loudness signals only. Nothing needs to be removed to make room —
there is simply no such field anywhere.

### Three obstacles, in order of how hard they are to move

1. **The minute boundary.** Segments are transcribed independently, so a
   diarizing model's `speaker_0` is a label local to one segment. There is no
   reason the next minute's `speaker_0` is the same person, and a day is 1440
   of them. Utterances also straddle the cut. Continuity across segments is not
   a setting on a diarizing transcriber — it is speaker embeddings clustered
   over the whole archive, which is a second piece of machinery.
2. **Direction is already closed.** The obvious cheap cue is which side the
   voice came from, and it was measured and does not exist: see
   [Direction, and why there is none](DESIGN.md#direction-and-why-there-is-none).
   Level differences are 0.2 dB with overlapping distributions, and
   cross-correlation puts a frontal talker at zero and a talker behind at zero
   too. That is the front-back ambiguity of a two-element array, so it is
   geometry and not tuning. This door does not reopen.
3. **Far field, and 32 kbps per channel.** Voiceprint embeddings lose accuracy
   to reverberation and to bitrate, and a device on a desk collects both. It
   has not been measured here, and it is the measurement that decides whether
   identification is possible at all rather than merely expensive.

### Cost, and why the VAD gate is a precondition

Twenty-four hours a day is 43,200 minutes a month. At the order of $0.004 a
minute that is roughly $180 a month, which is more than the whole archive costs
in a year (`DESIGN.md`, storage table). The measured voiced share of a day is
about 11% (`audioprobe/FINDINGS.md`), and the silent-segment gate already
exists in `upload`. Behind it the same work is roughly $20 a month.

So the gate is a precondition rather than an optimisation, and the arithmetic
above is list prices multiplied out — not a quote, and worth redoing before
anything is committed to.

### Nothing here expires

The audio in R2 is deliberately never deleted, and `/v1/admin/retranscribe`
already exists. Whatever this turns into can be applied backwards over
everything recorded so far, which is a good reason to decide late and a bad
reason to decide badly.

### The part that is not technical

A voiceprint is a 個人識別符号 under the 個人情報保護法. Recording one's own day
and storing biometric templates of the people who walk through it are different
kinds of data, and the second one is a decision to take before anybody is
enrolled rather than after. It also decides where the templates may live, which
constrains the routes above.

### Open questions

- **Whether Workers AI can do it at all.** Deepgram's own API takes a `diarize`
  flag, and its Nova-3 is understood to be in the Workers AI catalogue
  alongside Whisper — but neither the catalogue entry nor whether the binding
  passes that parameter through has been checked from this repository. If it
  does, within-segment labels are nearly free; if not, the route leaves
  Cloudflare.
- **Which of the two features is wanted.** Undecided, and it changes everything
  downstream. Within-segment labels are a small change to the queue consumer;
  identification is a new store, a nightly clustering job, and an enrolment
  step.
- **Embedding accuracy on this microphone.** Unmeasured. See obstacle 3.

---

## Shelved: reading the night back

**Shelved 2026-08-20.** Costed, and blocked on a question that is not
technical. The device records through the night, so the material exists whether
or not anything is ever done with it.

### Why this one is unusually cheap

Every other feature here starts by needing data nobody has. This one does not.
`rms_envelope` has held one byte per second for every segment since migration
0005 — **a second-by-second acoustic timeline of every night already
recorded**, queryable without decoding a single Opus frame.

Two properties make it better material than it looks:

- **It is not degraded by the codec.** The bytes are computed on the device
  from the PCM in hand (`RecorderService.accumulate`), before Opus ever sees
  it. Whatever 32 kbps does to a quiet room, it did not do it to these numbers.
- **It is already calibrated.** 11 quiet, 22 background activity, 152–221
  speech, and the reasoning behind the numbers is written down next to them.

### What is visible in it, and what is not

Snoring sits in the loud range and produces no words, which is the signature:
`voiced_ratio` high, `speech_ratio` low. Both columns already exist, so the
first look costs a query — see the Unverified item above, which is the same
query asked for the opposite reason.

**Periodicity is reachable, barely.** Breathing runs 12–20 a minute, 0.2–0.33
Hz, against a 1 Hz sampling rate whose Nyquist limit is 0.5 Hz. That is three
to five samples per cycle: enough for autocorrelation over a window of minutes
to recover the period, nowhere near enough for the shape of an individual
snore. The clinical marker for an apnoea is a pause of ten seconds or more, and
ten seconds is ten samples — a gap that size, bracketed by loud breathing
either side, is a shape this resolution can hold.

**Ordinary breathing is not visible and will not become visible.** The byte is
RMS shifted down four bits, so one step is 16 raw units against a quiet room of
176. Snoring clears that quantisation easily; quiet respiration lives inside a
single step of it. Anything needing the quiet end has to go to the audio in R2,
which is a different and more expensive project.

### The question that blocks it

**Where the device spends the night.** Measured runtime in the shipping
configuration is 8.6 hours (`audioprobe/FINDINGS.md`), so crossing a night
means sitting on the charger, and whether the charging place is the bedroom
has not been stated. If it is not, everything above describes an empty room.
This is the first thing to settle and it costs nothing to settle.

Second, and already written up: **whose snoring**. See the voice item above.
Another person in the room and the audio cannot attribute any of it.

### It is not a diagnosis, and this repository has already decided that

There is no SpO2, no airflow measurement, and no sensing of the sleeper at all
— the accelerometer is in the device, not in the bed. The apnoea-hypopnoea
index is defined partly on oxygen desaturation, so there is nothing here that
substitutes for it, and the audio-only literature is not uniformly kind about
what can be claimed from sound alone.

The precedent is directly above, under **Explicitly not being built** in the
conversation mode: a classifier of 80–90% accuracy, run daily against one
person, generates false alarms constantly, and a device has no business
delivering that verdict to its owner. The same rule applies unchanged. If this
is built it is **a timeline and a trend with no conclusion attached**, and the
interpretation belongs to a person, and to a doctor if it comes to that.

### Stages, cheapest first

| | What it is | Cost |
| --- | --- | --- |
| 0 | The hourly query over stored columns: is anything being recorded at night at all | None |
| 1 | Autocorrelation over stored envelopes — period, and gaps in it | None; a pure function over data already in D1 |
| 2 | Going to the audio in R2 to classify what the loud minutes actually are | The first real decision, and the first bill |

Stage 0 answers whether the rest is worth designing, which is the right order:
measure before specifying, rather than specifying and then discovering the
device was in the wrong room.

### Open questions

- **The bedroom question above.** Unanswered, and it gates everything.
- **Whether the night is currently being transcribed and billed.** See the
  Unverified item; it is the same measurement.
- **What a snore actually reads as on this envelope.** The 152–221 figure is
  speech at conversational distance. Snoring from a bed across a room has never
  been measured here, and if it lands near 32 rather than well above it, the
  separation this rests on is not there.

---

## Agreed: 声かけ — the device speaks first

**Agreed 2026-08-22.** Design settled, not started. The sibling of the
conversation mode above and deliberately not the same feature: that one is a
supplement for days with no conversation in them and refuses to run on days
there was some, while this one is a check-in on an ordinary working day — the
morning question about medication and what the day holds, the midday one about
how work is going, the evening one about whether it will finish. Frequency,
brevity and *timing* are the whole of it. It does not carry the anti-isolation
argument, so it does not carry that gate either.

What it must never be is a device that talks to an empty room, or over a
meeting. Those two are the design.

### The judgment is the server's

The device reports facts only it can know — which Wi-Fi it is on, whether it is
recording, how the room sounded in the last two minutes, what the camera sees.
Every decision made from those facts is the Worker's.

This is the same reasoning the conversation mode gave for minting at the server:
a gate the client evaluates is a gate the client can skip. It buys three more
things here. The schedule changes with a deploy rather than an APK install and a
reinstall on a device whose accessibility service has to be toggled by hand
afterwards. The conversation checks want D1 — `speech_ratio` per minute is
already there and the device does not have it. And every verdict lands in one
table, so "why did it not speak all afternoon" is a query rather than a guess.

### Two phases, because the cheap checks are free and the good ones are not

Asking a vision model every minute costs money, and this device's camera is on a
motorised arm that is audible in its own recording. So the schedule and the
conversation history run first, on nothing, and the camera only moves for a slot
that has already survived them.

```
device, every 2 min            Worker                            D1 / Workers AI
  GET /v1/nudge ─────────────▶ 1 enabled, home, recording
  ?home=1&recording=1          2 quiet hours (22:00–08:00 JST)
                               3 slot due, not fired today,
                                 45 min since the last one, 4 a day
                               4 in conversation? last 10 min ──▶ segments
  ◀── {"due":null,"why":"in_conversation","retry_after_s":900}
  ◀── {"due":"morning","need_presence":true}

  one front-facing frame, up the ordinary photo route
  PUT /v1/photos/{id} ───────▶ R2 + the caption queue, like every other frame

  POST /v1/nudge/confirm ────▶ 5 fresh audio: still quiet?
  {slot, photo_id, envelope,   6 is a person in front of it? ───▶ vision model
   posture}                    7 mint, with today's material baked in
  ◀── {"ok":true,"id":12,"client_secret":"ek_…","url":"wss://…"}
  ◀── {"ok":false,"why":"no_one_there"}

  wss://api.openai.com/v1/realtime  — NudgeActivity, on screen, up to 3 min

  POST /v1/nudge/outcome?id=12&answered=1&seconds=64
```

The frame travels as an id rather than in the body. The device already holds
`PUT /v1/photos/{id}`, so sending it that way puts the picture in the archive
with a caption like every other frame, and the presence check reads it back out
of R2 — one upload path instead of two, and the frame the check was made from
is still there in a month when somebody asks whether the check was any good.

`ok:false` is a 200. It is an answer to a question, not a refusal of the
credential, and returning 403 for it would put "nobody was in the room" in the
same bucket as "this token is dead".

### The three checks, and what each one gets wrong

**The schedule.** Slots in JST, in the Worker's source rather than in D1 — a
table would need a screen to edit it before it earned its keep, and a time
change is one deploy. Defaults: 09:00 morning, 12:30 midday, 17:30 evening, and
at home an idle check-in every two hours between 10:00 and 21:00. One firing per
slot per day, nothing within 45 minutes of the previous one, and after two in a
row that nobody answered the idle ones stop until tomorrow. The timed three do
not stop, because not answering the morning question is not evidence that the
morning question was unwanted.

*Wrong when:* the working day moves. There is no calendar here and the schedule
does not know about holidays; a Sunday at home gets the same three questions as
a Tuesday. Deliberate for now — see below.

**Whether someone is already talking.** Minutes in the last ten where Whisper
found real words: `speech_ratio >= MIN_SPEECH_RATIO` and not a stock phrase, the
same test `/v1/talk` counts spoken minutes with. Over the threshold the slot is
deferred fifteen minutes, up to three times, and then dropped for the day — the
idle check-in excepted, since it has no window to miss and the gap rule brings
it back on its own once the room is quiet. Confirmed again at phase two against
audio that is seconds rather than minutes old, because segments reach D1 a
minute or two behind the room.

Deferrals are recorded at most one every fifteen minutes rather than once per
poll. The device asks every two minutes, so counting polls would spend all three
deferrals in six.

*Wrong when:* the television is on, or — per the Unverified item above — someone
is asleep and snoring. Both make the device believe it is interrupting and keep
it quiet. That is the safe direction of the two, and it is the same measurement
error, so fixing it there fixes it here.

**Whether anybody is there.** One front-facing frame at the moment the slot
comes due, through `@cf/meta/llama-3.2-11b-vision-instruct` — the model already
captioning every photograph in this archive, so no new dependency and no new
bill beyond the call itself. Asked for a yes or no and a one-line reason, not a
caption: is there a person in this frame, close enough and facing the camera to
be spoken to.

*Wrong when:* it is any person, not this person. Telling one human from another
is the voiceprint item further up and it is shelved. On a device that lives on
one desk this is a small risk and a named one. Also unmeasured: nobody has run
this model against a 640-pixel frame of somebody at arm's length from this
particular lens. The first fortnight logs the verdict *and* the frame id, so the
question is answered from real frames instead of from a guess about them.

### On the device

Very little of this is new, which is why it is worth building now rather than
alongside the conversation mode where it was first written down.

- **Microphone contention was the unsolved problem there and is solved here.**
  `RecorderService.audioTap` hands a share of the live capture to whatever is in
  front, and the interpreter has been running through it for a week. 48 kHz
  stereo to 24 kHz mono is the same 2:1 decimation plus channel average.
- **`Nudge.kt`** — the poll, the confirm, and the session. The socket handling,
  the `AudioTrack`, and the media-volume claim-and-restore are the interpreter's;
  the common half comes out of `Interpreter.kt` rather than being copied.
- **`NudgeActivity.kt`** — full screen, `setShowWhenLocked` and `setTurnScreenOn`
  as `StopPromptActivity` does, the lines of the exchange as they arrive, and an
  exit. The opening line is on the panel before the speaker has finished saying
  it, because somebody who looks up at the third word should still be able to
  read the question.
- **The device decides when it ends, and then says so.** A WebSocket gives the
  model no way to hang up, so the device has to — and the first version did it
  silently, cutting the socket on the event that completed the model's own
  follow-up question. The room got "それで今日は何を作ってるんですか?" and then
  nothing. **Ending a conversation is something you say**, so now the exchange
  runs until three answers or twenty seconds of not answering, the model is
  asked for a closing remark with no question in it, and the session ends once
  that has been heard. Underneath sit two bounds that depend on nothing
  behaving: 45 seconds with no events at all, and the three minutes the Worker
  sends down. Leaving the screen ends it too.
- **`RecorderService`** — one more tick beside `applySchedule`, at most every two
  minutes and only while at home and recording. The photograph borrows
  `Timelapse`'s `StillCamera`, so the two need an interlock: a cycle in flight
  means the slot waits rather than fighting the arm for the lens.
- **Settings** — one switch. The times live on the server and do not appear here.

The conversation is recorded like every other minute of the day. It goes to R2,
it is transcribed, and it is searchable. That is the intent rather than a side
effect: the device asking what the day held, and the answer to it, are exactly
the kind of minute this archive exists for.

### Cost

`gpt-realtime-2.1-mini`, $10/M audio in and $20/M out. An exchange of ninety
seconds is about $0.02–0.03; four a day is roughly $35 a year. Text input is
cached at $0.4/M, so priming each session with the day's transcripts and the
slot's question is close to free.

The vision check is Workers AI neurons, a handful of calls a day against the 96
photographs a day already being captioned — inside the noise of the existing
bill.

### Explicitly not being built

- **A calendar.** It would settle "is this a meeting" outright, and it is an
  OAuth integration in a Worker that currently holds no third-party identity at
  all. If the audio test proves inadequate this is the answer, but it is a
  separate piece of work and it should be justified by a measurement rather than
  by the intuition that meetings are hard to detect.
- **A face-detection pass on the device.** Camera2 exposes one; whether this
  sensor supports it is unknown, and it would move the presence decision back to
  the client to save a few cents a month.
- **The conversation mode's daily gate.** These are check-ins, not a substitute
  for human contact, and refusing to say good morning because there was a
  conversation yesterday would be applying an argument that does not reach here.
  If both features ship, they share the schedule table and nothing else.

### Unsolved

1. ~~**Acoustic echo, carried over unchanged.**~~ **Closed 2026-08-22, by
   happening.** It was carried over from the conversation mode on the grounds
   that the interpreter lives with it; the first check-in ever made showed why
   that reasoning did not transfer. The interpreter is spoken to first, so its
   own voice arrives into a conversation already in progress. This one speaks
   first into a silent room, so the only thing the microphone can hear is
   itself.

   Measured: audio began at 13:10:16.06, `input_audio_buffer.speech_started`
   fired at 13:10:17.12, the transcription of that "reply" arrived a second
   later, and the session closed at 13:10:28 having answered its own question.
   The archive's own recording of the minute settles it — one voice, the same
   question twice, the second cut off, no other person in the room:

   > どんな調子ですか?今日は何か忙しそうですか?それともちょっと?
   > 仕事はどんな調子ですか?今日は何?

   The fix is the one named here before it happened: the microphone is shut
   while the speaker has anything left to play, plus 600 ms. Silence of the same
   length is sent rather than nothing, so the far side still measures the pause
   before a person answers. Barge-in is gone, as predicted.

   The part worth keeping in mind is that `answered` was recorded as 1. A gate
   that fails this way does not look like a fault in the log — it looks like a
   successful conversation — which is why the transcript comparison stayed in as
   a second test even though the gate should make it unreachable.
2. **What the vision model actually does on this lens.** Above; measured over
   the first fortnight rather than assumed.
3. **Whether being asked at 12:30 specifically is welcome.** The schedule is a
   guess with a deploy behind it. `nudge_log` records the slot, the verdict, and
   whether anybody answered, which is the material for changing it.

### Built on 2026-08-22, and what is still unproven

The Worker, the migration, the poll, the session and the screen are written and
they compile. What has actually been *run* is the free half: against a local D1,
`GET /v1/nudge` was made to answer `not_home`, `not_recording`, `too_soon`,
`daily_cap`, `in_conversation` and a live `due: "idle"`, two polls during one
conversation were confirmed to leave one deferral row rather than two, and
`POST /v1/nudge/outcome` updated the row it was given.

Neither of the two checks that cost money has been run at all. The presence
check needs Workers AI, which is not available in the local dev runtime, and the
mint needs a real request to OpenAI. So the shape of the `client_secrets` body
for a general realtime session — as opposed to the translation session the
interpreter mints — is taken from the documentation and has never been answered
by the API. The Worker passes OpenAI's own error text through for exactly this
reason: the first failure will say which field is wrong rather than "failed".

**Deployed and installed the same day.** The migration went to the remote D1,
the Worker went out as version `76419ec6`, and the APK went on the device — at
which point `enabled_accessibility_services` lost the side-button service again,
exactly as [Operational notes](DESIGN.md#operational-notes) warns, and had to be
written back.

Both paid checks then answered on the first attempt. The vision model was given
a real frame and said はい for 16.9 neurons, describing where in the picture the
person was; OpenAI issued a client secret for `gpt-realtime-2.1-mini`, so the
request shape that had never been confirmed was right.

The first live check-in fired at 13:10 and failed in the way recorded under
Unsolved below. Everything up to the speaking worked: the poll came back
`due: midday`, the arm took a frame at luma 143, the presence check passed, the
credential was minted, and the screen took over from the standby display. What
did not work was the microphone.

Still unproven after all that: whether the schedule is any good, and whether the
presence check holds up on frames with nobody in them — every frame it has been
shown so far had a person in it, so it has never yet had the chance to say no.

### Turns

Design: 2 planned / 2 actual — one to survey the ground and propose, one to
settle the four choices and write this down. Implementation: 1 planned /
1 actual.

## Built: an avatar on the standby display, holding the news

The standby display becomes a character rather than a page of text: the model
stands on the panel and holds a board with the current headline on it.

Written down the night the talk prop was built, because that prop answered most
of the questions this feature would otherwise have had to ask first.

### What is now known rather than assumed

All of it measured on the device on 2026-08-27, building 一言:

- The R1 runs **WebView 147** (Chromium) over **OpenGL ES 3.2** on a PowerVR
  GE8320. three.js and `@pixiv/three-vrm` bundled to one classic script render
  a **19 MB VRM 1.0 model** on it, with spring bones, blinking and expression
  weights, at a frame rate that reads as smooth on a 480×640 panel.
- Assets reach the page through `WebViewClient.shouldInterceptRequest` under a
  host that does not exist. That gives an ordinary origin — needed for `fetch`
  — without adding androidx.webkit, which this app deliberately does not have.
- The model was on screen well inside a twelve-second budget from a cold start.
  The load time itself was never measured; it wants measuring before anything
  depends on it.
- Four failures that all look identical from the outside, each of which cost an
  hour: a `GONE` WebView is never laid out and the page measures itself at zero
  by zero; `Box3` read before `updateMatrixWorld` puts the camera at knee
  height; the head bone is not nine tenths of the way up every rig (0.28 of
  0.72 on one model); and `VRMUtils.rotateVRM0` does not leave every 0.x model
  facing the viewer. Framing from the bounding box after the matrices are
  updated, and taking the facing from `meta.metaVersion`, survives a model
  swap. See `audioprobe/stage/entry.js`.
- Drawing at five frames a second while nothing is happening is invisible and
  cheap. Anything on screen has to be drawn properly.

### Why the battery objection mostly is not one

A 3D scene running all day would be indefensible on a 1010 mAh device. The
standby display is not all day: `Idle` only fires while charging, and
`SignageActivity.applyKeepAwake` only holds the panel awake while charging. So
the avatar renders on a desk with a cable in it and nowhere else, which is the
same condition under which the display already keeps a bright panel lit.

What does want measuring is the *idle* draw, since the panel is up for hours
rather than the five minutes the talk needed.

### The part that is genuinely hard, and it is not the 3D

The board.

`HeadlinesScreen` already settled this argument once, and the note above it
says so: at a size that fits four Japanese headlines on a 240 dp panel, none of
them can be read from across a desk. It shows **one headline, large, for
fifteen seconds**. A board held by a character is by definition smaller than
the panel the headline currently gets all of — so the charming version is
strictly worse at the one thing the screen exists for.

Three ways out, in order of how much they give up:

1. **Board holds the headline.** Most faithful to the idea, least readable.
   Only viable if the character is small and the board is most of the frame,
   at which point it is a text screen with a mascot in the corner.
2. **Character beside the text.** The headline keeps its size and position; the
   model stands to one side of it, or behind it at low contrast. Readable, and
   the least interesting version of the idea.
3. **Board holds the source, text holds the headline.** The board carries the
   feed name — short, fixed, legible at any size — and the headline stays as
   HTML underneath. The board earns its place by saying something the text no
   longer has to.

Three is the one to build. One is the one to try first for a day, because the
argument above is about reading and this screen is glanced at, and that
distinction has already been wrong once here.

### How the board would be made

A `THREE.Mesh` plane parented to the hand bone —
`vrm.humanoid.getNormalizedBoneNode("rightHand")` — with a `CanvasTexture` for
its face, redrawn when the headline changes and marked `needsUpdate`. Canvas 2D
draws Japanese in this WebView. The arm goes into a holding pose the same way
the talk prop takes the model out of its T-pose: setting rotations on the
normalized rig, which three-vrm copies onto the real bones each update.

### Where it did not fit, and what it cost to make it

`SignageScreen` hands back a `View` and `SignageActivity.show()` swaps one for
another. An avatar built that way would parse a 19 MB model on every turn of
the wheel. The avatar had to become the standby display's *frame*, with the
existing screens drawn over it, rather than one more screen in the rotation —
which was a larger change to that Activity than it sounds, and was most of the
work.

It is now two children of the same frame: the page underneath, built once in
`onCreate` and left alone, and a `content` container that `show()` swaps
screens into. `content` takes the top 52% of the panel and carries a scrim that
is opaque for its first quarter and clear by halfway down; the screens, which
paint themselves black, are made transparent over it. The two numbers that have
to agree — `CONTENT_FRACTION` in the Activity and `STANDBY_CROP` in
`entry.js` — are commented as agreeing, because nothing enforces it.

### The licence is not the constraint it looked like

This was written down wrong the first time and the correction is worth keeping,
because the wrong version was about to cost the headline its space.

The model's own metadata says `creditNotation: required`. **The licence it
points at says the opposite.** The VN3 agreement shipped with the model set
answers "Credit for use" with **Not necessary**, and the line reading
`SSS LLC.` under "Credit notation" is the wording to use *if* a credit is
shown, not an instruction to show one. `commercialUsage` in the metadata is
`personalNonProfit`, which the agreement does bear out — publicising for
non-profit purposes is what it permits.

So the standby display does not owe a permanent credit in a corner, and the
headline keeps its panel. The metadata enum is coarse and the exporter picks
from a short list; the agreement is the document that governs.

The credit is still worth showing, because it costs almost nothing in the right
place and being correct here is cheap. That place is a licence screen in 設定,
not the panel — see below. The talk prop keeps its credit under the caption,
which is a few minutes of a screen nobody is reading anyway.

Read from `ミニずんだもん_使用ライセンスvn3.pdf` in the model set. The Japanese
original does not extract as text; the English edition shipped alongside it
does, and the sentences quoted above are from that. Terms may change —
<https://zunko.jp/guideline.html> is where the licensor says so.

## Proposed: a licence screen in 設定

There is not one, and the app has quietly accumulated things that want naming:
OkHttp (Apache-2.0), and — since the talk prop — three.js and
`@pixiv/three-vrm` (both MIT) bundled into `assets/stage/bundle.js`, plus a
character model under its own agreement.

One screen behind 設定, listing each component, its licence, and the full text
where the licence asks for it. Apache-2.0 does ask; MIT asks for the copyright
notice and permission text. Nothing here is a hardship and none of it is
currently written down anywhere a person could find it.

The reason to do it is not only tidiness. It gives every future credit — the
next model, the next library — a place to go that is not the panel, which is
the argument that nearly cost the standby display its headline above.

### Explicitly not proposed

- Lip sync. The page never sees audio; the talk prop moves the mouth on a
  shaped oscillator and that is all this would get.
- The avatar speaking. That is 声かけ, which has its own screen and its own
  reasons for taking over the panel.
- Lip sync, and the avatar speaking: both still stand.
- Animation clips were on this list — "VRMA would want a second dependency and a
  second licence question, for movement a sway and a blink already cover" — and
  were built anyway on 2026-08-30, see below. Both costs were real and both were
  small. The reasoning was not wrong about the price; it was wrong that a sway
  covers it, because a sway is not something a person can *do* to the thing.

### Open questions

- The idle draw, in milliamps, over an hour. Everything above assumes it is
  small next to a lit panel and nothing has measured it.
- Whether the model belongs in the APK or in R2 behind the Worker. Nineteen
  megabytes is a fifth of the current APK, and the device already knows how to
  fetch things.
- Whether the character should react to anything — a segment uploading, a
  question being asked — or simply stand there. Reacting is where this stops
  being a wallpaper and starts being a second thing to debug.

### Built on 2026-08-30

Option three, as argued above: the board carries the feed name and the headline
stays as text over it. Option one was never tried for its day — the board was
legible enough holding a source that the question of it holding a headline
stopped being interesting.

What is on the device:

- `StagePage` holds the WebView, the asset host and the four failures worth
  knowing about; `CueActivity` and `SignageActivity` both build from it. The
  page is one bundle with two modes — `cueSpeak`/`cueIdle` for the talk,
  `stageStandby`/`stageBoard` for the panel — because a second page would pay
  for the model and the GL surface twice.
- The board is a plane parented to the right hand for the sway, but placed and
  turned in the camera's axes every frame. Parenting alone put it through the
  character's own torso: a rig can orient its wrist any way it likes.
- The arm follows the board rather than the mode. Most standby screens have
  nothing to hold up, and a raised arm with nothing in it reads as a fault.
- Feed names are broken over two lines and then three before the type is
  allowed to shrink. "ギズモード・ジャパン" takes two; "はてなブックマーク -
  人気エントリー - 総合" takes three and is the worst case there is.
- `UploadSettings.avatar`, on by default. Off puts the display back exactly as
  it was, which includes giving `HeadlinesScreen` its feed name back — the
  screens are told whether the board is really up, so moving something onto it
  is reversible.

### A tap plays a motion, added 2026-08-30

The standby display had one gesture and it dismissed the screen — a second way
out that nothing pointed at, next to the side button that the code already
called the way out. A tap now plays one clip from pixiv's VRMA_MotionPack,
round the six in order, and the side button is the only way back.

- The clips are read only after the panel enters standby. The talk shares the
  page and cannot play one; making it read five megabytes to find that out
  would be the second dependency earning its bad reputation.
- Nothing puts a rig back. An action that has finished leaves the bones on its
  last frame, which after the knee bend is a character stuck in a crouch, so
  the whole normalized rest pose is captured at load and written back when a
  clip ends.
- The board is hidden while a clip runs. It is held in the hand the clips move.
- A tap during a clip is ignored rather than queued or restarted. Cut short a
  motion reads as a fault; queued, as a device that stopped listening.
- `VRMA_04` is drawing and firing a weapon and is not used.
- The clips must not be committed. The pack forbids redistributing them "in a
  state where they can be extracted", which a public repository is. Same
  treatment as the model: gitignored, and named in `stage/README.md`.

It also found a real bug in the frame loop. `getDelta` resets the clock whether
or not anything is drawn with it, so every throttled frame threw its slice
away and everything driven by time ran at half speed. Invisible in a sway.
Not invisible at all in a motion clip.

### Still unproven

- **The idle draw, in milliamps, over an hour.** Unchanged from the open
  question below, and it stayed open: the device measures battery current, and
  on a full battery on a charger that number sits near zero whatever the GPU is
  doing. Measuring it wants either a meter on the cable or a run off charge
  with the panel forced awake, and neither is a thing the device can do to
  itself.
- A headline of four lines reaches into the fade and sits over the character's
  ears. It is readable and it is not nothing.

### Turns

Design: 1 planned / 1 actual, written from a prop that had just answered the
technical half. Implementation: 1 planned / 4 actual — one to build it and
three spent entirely on where the board sits and how large its type can be,
which is the half of this that no amount of design settled.

Motion on tap: 1 planned / 1 actual. The clips retargeted and played first try;
what cost anything was noticing that the rig had to be put back afterwards, and
that the frame throttle had been lying about time all along.
