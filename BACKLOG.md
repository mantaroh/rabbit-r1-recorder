# Backlog

Work that is designed, or built, but not finished — kept here rather than in a
chat log, so that picking it up again means reading rather than re-deciding.

Two kinds, and they are not the same kind:

- **Unverified** — shipped, but a claim the documentation makes rests on
  something nobody has observed. Cheap to close and dangerous to forget,
  because built-and-unproven reads exactly like built-and-working.
- **Shelved** — whole features worked out far enough to cost and to find the
  hard parts, then put down deliberately.

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

## Unverified: the shake threshold

**Narrowed 2026-08-17**, by a 55-minute walk. The position half of this entry
is closed; see below for what it found.

### What is still unproven

The gesture no longer fires with the screen off, which removes the failure the
walk produced, but **the 14 m/s² threshold itself has still never been set from
data.** It was picked by reasoning about walking and running, and the one real
false positive was not measured — peak magnitude was not being recorded at the
time.

It is now. Every accepted run logs `peak_ms2` alongside `peaks`. Once a few
weeks of ordinary use have gone by:

```bash
adb shell "run-as com.r1.audioprobe grep shake files/probe.jsonl"
```

Deliberate gestures and accidents should separate on `peak_ms2` the way two
shakes and three separated on `peaks`. If they do, the threshold moves to the
gap between them and the screen-on gate can be reconsidered — being able to
reach the camera without waking the device was the point of the gesture, and it
was given up to stop one false launch an hour rather than because it was a bad
idea.

If they do not separate, the gate is the answer and this entry closes.

### Closed: the position log

Verified end to end on the same walk.

| | |
| --- | --- |
| Fixes | 10, one every 5 minutes exactly |
| Accuracy | 294 m on the first, cold; **8–14 m** on every one after |
| Home/away | Switched to `home:false` on leaving Wi-Fi, so the away cadence engaged |
| Upload | One batch of 10 on return, `position_upload_ok` |
| Coordinates | 31.574 → 31.593 → 31.576, a route out and back rather than a scatter |

The coordinate order is unambiguous in the data rather than merely plausible:
longitude reads 130.55, which cannot be a latitude, so a transposition would
have been rejected by the range check rather than drawn as a journey.

Two things this measured that were not the point of the test: `speed_mps` and
`bearing_deg` come back as zero from this provider even when moving, so neither
is worth storing except as evidence they are useless; and the first fix after
going outside costs about five minutes and 294 m of error, which is the price
of having no network location to warm the receiver with.

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
