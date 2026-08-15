# Phase 0 findings — Rabbit R1 always-on capture

Measured on the device, not inferred. Every number here came from
`:audioprobe` writing `files/probe.jsonl` on a real R1 running CarrotOS
(Android 14), 2026-08-12.

Run conditions unless stated: `MIC` source, partial wake lock held, screen off,
**on battery**, Wi-Fi connected, continuous PCM write to disk.

## Settled

| Question | Answer |
| --- | --- |
| 16 kHz mono PCM16 native? | **Yes** — `getMinBufferSize` = 1280, no resampling path needed |
| Capture survives screen off? | **Yes** — 8 h continuous, `frames` matched the expected 10/s to within a rounding sample across 962 reports |
| Capture survives on battery? | **Yes** — unplugged throughout, `stalls`/`reinits`/`write_errors` all **0** |
| Foreground service (`microphone`) on CarrotOS? | **Yes** — starts from a visible Activity, survives screen off |
| Is the stream real audio? | **Yes** — longest run of exact zeros across a 60 s segment was **0.2–0.3 ms**; a dropout would show as tens to hundreds of ms |
| Audio quality | **Good** — confirmed by listening to pulled segments |
| Segment continuity | 32 consecutive 60 s WAVs, timestamps stepping by exactly 60 s, **no gaps** |
| Thermal | `thermal_status` stayed **0** throughout |
| Unmetered-network detection | Works — Wi-Fi reports `NET_CAPABILITY_NOT_METERED = true` |

### Power

| Configuration | Mean current | Estimated runtime (1010 mAh) |
| --- | --- | --- |
| Capture only, no disk write | 51.6 mA | ~19.6 h |
| Capture **+ continuous disk write** | 52.5 mA (recent half) | ~19.3 h |

**Continuous disk writing is essentially free.** 32 KB/s is nothing to the eMMC;
what costs power is keeping the CPU and audio path alive at all. The first
readings after unplugging showed 160–200 mA, but that was a transient — the
steady state converges to ~52 mA.

Consequence for the design: **VAD earns its place through upload volume,
storage, and server-side transcription cost — not through battery.** Gating
capture on speech will not meaningfully extend runtime.

### Storage

`107 MB/h` as raw PCM16 → **1.2 GB per 12 h**. The device reports **104 GB
free**, so a Wi-Fi-only upload policy can defer for days without pressure.
Opus is needed for the *link*, not for the disk.

### Ambient levels (useful for VAD thresholds)

| Condition | RMS | Peak |
| --- | --- | --- |
| Quiet room | 176 | 7.6 % |
| Background activity | 352 | 38.9 % |
| Speech present | 2440–3536 | 57–70 % |

An order of magnitude separates speech from the noise floor, so a VAD threshold
has plenty of headroom.

### How much of a day is actually speech

From 589 segments carrying Whisper's per-segment timings:

| Hour, JST | Voiced share |
| --- | --- |
| 03 (asleep, 1 segment) | 3.2 % |
| 12 – 19 (workday) | 4.4 – 19.9 %, mean ≈ 11 % |
| 20 – 23 (home) | 10.2 – 52.8 %, mean ≈ 31 % |

**≈ 11 % of a full day including sleep.** This is the number that decides
whether a lifelog is affordable, and it went unmeasured while the lifelog was
running ungated: the VAD in this module has only ever gated the question path,
never the recording.

Treat 11 % as a floor for what an energy VAD would pass — it is Whisper's word
coverage, and an RMS gate additionally passes fans, traffic and keystrokes.

### Workers AI silently degrades to a weaker model

For 12.5 hours straight — 2026-08-12 23:08 to 08-13 11:39 — every call to
`@cf/openai/whisper-large-v3-turbo` failed and fell through to `@cf/openai/whisper`.
749 of 1348 segments took the fallback.

Nothing looked wrong. Transcripts kept arriving and the device saw HTTP 200s.
Only the fallback reports no per-segment timings, so those rows carry
`speech_ratio IS NULL` — and since the hallucination filter deliberately lets
NULL through rather than erase history, they are neither filtered nor
distinguishable in a query result.

The lesson is not to remove the fallback: a weaker transcript beats a lost
segment. It is that **a fallback which reports nothing is indistinguishable
from a healthy path**, and this one ran for half a day before a query about
something else turned it up. The ratio of NULL to non-NULL `speech_ratio` over
a recent window is the cheapest available health check.

### Hardware input (from `:hermes`'s probe screen)

| Control | Event |
| --- | --- |
| Wheel up / down | `KEYCODE_DPAD_UP` (19) / `KEYCODE_DPAD_DOWN` (20) |
| Side button | `KEYCODE_BUTTON_1` (188), auto-repeats ~50 ms while held |
| Back | **Does not exist** — nothing ever emits `KEYCODE_BACK` |

The side button reaches a foreground app directly, so double-click detection
does **not** require forking the CarrotOS launcher — only the
app-not-foreground case would.

## The fuel gauge is coarse, not broken

At 32 minutes into the run this looked like a dead gauge: `CAPACITY` still 100,
`CHARGE_COUNTER` still 1010000, `dumpsys battery` agreeing with both, despite
~28 mAh of confirmed discharge. That reading was wrong — the gauge simply does
not move on that timescale.

Over hours it tracks cleanly and linearly:

| Elapsed | Level | `charge_uah` |
| --- | --- | --- |
| 4.4 h | 83 % | 838300 |
| 5.3 h | 79 % | 797900 |
| 6.5 h | 73 % | 737300 |
| 8.0 h | 66 % | 666600 |

≈ **4.2 %/h**, i.e. **~24 h from full** on a single charge while recording
continuously to disk.

So battery-percentage logic *is* implementable — but it reacts slowly. Anything
that needs a fast signal (a sudden load, a failing cell) has to read
`CURRENT_NOW`, which updates immediately.

Note the two estimates disagree: 53 mA against a nominal 1010 mAh predicts
~19 h, while the observed drain gives ~24 h. The pack is likely larger than the
nominal figure. Prefer the observed rate.

## CarrotOS blocks a newly installed app's network

A fresh install lands in the network policy as **`REJECT_ALL`**:

```
UID=10078  policy=262144 (REJECT_ALL)
```

The symptom is confusing rather than obvious. From the device shell everything
is fine — `ping lifelog.mantaroh.org` resolves and answers in 18 ms — but the
app cannot resolve anything, and `ConnectivityManager.getActiveNetwork()`
returns **null** even with Wi-Fi connected and validated. That null is the
documented behaviour for "the app is not allowed to use the network", and it is
indistinguishable from "there is no network". The `NetworkCallback` registered
by the same app still reports `wifi / unmetered / validated` correctly, so link
state should be read from the callback, not from `getActiveNetwork()`.

Cleared over adb:

```
adb shell cmd netpolicy add restrict-background-whitelist <uid>
```

Consequences for the real recorder:

- **Assume the first run cannot reach the network.** Surface it in the UI
  rather than silently queueing forever; "nothing is being sent" and
  "everything is sent" look identical otherwise.
- Whether a user can lift this from the device's own settings is untested.
- `android.permission.INTERNET` is easy to omit when a module starts life as a
  capture-only probe. The failure text is
  `Permission denied (missing INTERNET permission?)`.

## The side button, and how to see it

An ordinary app never receives it. The CarrotOS launcher takes it from an
AccessibilityService of its own:

```
com.r1.launcher/.PowerService   android.accessibilityservice.AccessibilityService
08-13 11:43:41.823  D/R1Power(1849): key code=188 sc=116 name=KEYCODE_BUTTON_1 ptt=true
```

`sc=116` is `KEY_POWER` — the side button is physically the power key, remapped
to `BUTTON_1`. Because it arrives through accessibility it is handled before
window focus matters, which is why it works with the screen off and why a
foreground app cannot intercept it. The launcher broadcasts nothing: its only
exported components are the HOME activity, an SMS receiver, and
profileinstaller.

**A second AccessibilityService requesting key filtering does receive the same
events**, measured:

```
11:43:41.823  R1Power(launcher)
11:43:41.830  KeyService(ours)          +7 ms
11:43:42.066  R1Power
11:43:42.073  KeyService  gap=242ms  double=true
```

Both services see every press, double-press detection works, and returning
`false` from `onKeyEvent` leaves the launcher's own behaviour untouched
(`ptt=true` still logged).

So the design's plan to fork `R1Launcher.apk` is unnecessary: no `/system`
modification, no root, no re-patching after an OS update. The cost is that the
user must enable the service under Accessibility. Over adb:

```
adb shell settings put secure enabled_accessibility_services \
  com.r1.launcher/com.r1.launcher.PowerService:com.r1.audioprobe/com.r1.audioprobe.KeyService
adb shell settings put secure accessibility_enabled 1
```

Keep the launcher's own service in that list — replacing it would break the
device's own button handling.

**Reinstalling the APK silently stops key delivery.** The service rebinds and
logs `connected, flags=32`, but `onKeyEvent` is never called again; the
launcher keeps receiving the same presses, so the device looks fine. Toggling
the service off and on restores it, and every install has to do this:

```
adb shell settings put secure enabled_accessibility_services <launcher-only>
adb shell settings put secure enabled_accessibility_services <launcher>:<ours>
```

Also: the button is the power key, so the launcher dims the screen on every
press. The query flow returns `false` from `onKeyEvent` deliberately — consuming
it would break the launcher — so that behaviour is inherited. Suppressing it
would mean consuming the second press of a pair.

### VAD timing, measured

The design proposed 1200 ms of silence to end an utterance. On real Japanese
speech that cuts sentences off: "先ほどの話は…" ended after 3.9 s with only the
opening words captured. Pauses inside a Japanese sentence are longer than that.
2000 ms holds the sentence together, at the cost of 800 ms more latency per
question.

## Still open

- **Without the wake lock.** Everything above holds *with* a partial wake lock.
  The probe has a toggle; the cheaper configuration is untested.
- **Long run.** 32 minutes proves the mechanism, not endurance. Overnight and
  then 72 h are still required (memory growth, `reinits` accumulation, thermal
  drift, whether the fuel gauge ever moves).
- **SORACOM SIM.** Cellular is expected to report metered, but that is the
  premise of the Wi-Fi-only policy and has not been observed.
- **`VOICE_RECOGNITION` vs `MIC`.** Not yet compared under identical conditions.

## Since Phase 0 (2026-08-15)

Everything above was measured at 16 kHz mono with no camera. The shipping
configuration is 48 kHz stereo with the timelapse running, and it costs
considerably more.

### Power, shipping configuration

| Configuration | Mean current | Estimated runtime (1010 mAh) |
| --- | --- | --- |
| 48 kHz **mono**, no camera | 61.9 mA | ~16 h |
| 48 kHz **stereo** + timelapse | 117 mA | ~8.6 h |

The second figure is 48 minutes unplugged, 5% of the gauge. Part of the
increase is real — stereo doubles the encode, and each timelapse cycle opens
the sensor and swings the arm twice. Part of it was not: the device had
misread the network as away-from-home, so it was photographing every 5 minutes
instead of every 15, and being "away" also disables the quiet-and-dark skip.
The two contributions have not been separated since; a clean stereo-only
measurement is still owed.

### Server side

Superseded. The lifelog Worker exists — R2 ingest, Whisper transcription into
D1, photo captioning, a web UI and an MCP surface — and the Hermes gateway is
not in that path at all. See [`../DESIGN.md`](../DESIGN.md). The note that used
to sit here recorded that Hermes had no ingestion path, which remains true and
is no longer relevant.

### Resolved from "Still open"

- **Long run** — days of continuous recording, no memory growth or `reinit`
  accumulation observed.
- **`VOICE_RECOGNITION` vs `MIC`** — still not compared. `MIC` is what runs.
- **Without the wake lock** — still untested.
