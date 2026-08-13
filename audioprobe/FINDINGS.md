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

## Still open

- **Without the wake lock.** Everything above holds *with* a partial wake lock.
  The probe has a toggle; the cheaper configuration is untested.
- **Long run.** 32 minutes proves the mechanism, not endurance. Overnight and
  then 72 h are still required (memory growth, `reinits` accumulation, thermal
  drift, whether the fuel gauge ever moves).
- **SORACOM SIM.** Cellular is expected to report metered, but that is the
  premise of the Wi-Fi-only policy and has not been observed.
- **`VOICE_RECOGNITION` vs `MIC`.** Not yet compared under identical conditions.

## Server side does not exist yet

Unrelated to the device, but blocking the same feature: Hermes has **no**
lifelog ingestion path. `kotoba-whisper` appears nowhere in the codebase (local
STT is faster-whisper), and `/v1/r1/audio` and `/v1/r1/query` are not
implemented. See `../hermes/PROTOCOL.md` for what does exist.
