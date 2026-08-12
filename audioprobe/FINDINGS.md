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
