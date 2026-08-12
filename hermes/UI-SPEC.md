# R1 Hermes Client — UI proposal

Fills the gap where the product spec stops (§2.3). Derived from measured
hardware and the protocol in [PROTOCOL.md](PROTOCOL.md), not from a scaled-down
phone layout.

## The constraint that drives everything

| | Measured | Spec said |
| --- | --- | --- |
| Panel | **480 × 640 px** | 480 × 480 |
| Density | 320 dpi (xhdpi, ×2.0) | — |
| **Usable layout space** | **240 × 320 dp** | — |

240 × 320 dp is smaller than a 2008 Android handset. Nothing survives being
merely "responsive" at this size — each screen gets one job.

### Derived: `cols = 34`

`session.create` / `session.resume` take `cols`, and the gateway hard-wraps its
output to it. At 11 sp monospace (22 px, ~13.2 px advance) with 8 dp side
padding: `(480 − 32) / 13.2 ≈ 34`.

Sending the default `80` would make every agent reply wrap twice — once by the
gateway at 80, again by the TextView at 34 — producing ragged half-lines. **Send
34.** Re-send on any font-size change.

## Text entry is the real problem

There is no keyboard, and a soft IME over 240 dp leaves ~120 dp of transcript.
Typing is not a viable primary input on this device.

We already have working transcription (`/api/audio/transcribe?profile=r1`,
`gpt-transcribe`, verified end to end). So: **voice is the primary composer,
the IME is the fallback** — not a "future enhancement" as the spec framed it.

Push-to-talk lives on both an always-visible `🎙 Hold` button and the hardware
side button. Release → upload → **submit**: speak-and-go, no confirmation step.
Requiring a tap on `Send` after every utterance defeats the point of voice on a
device where the alternative costs half the screen.

The safety net is Stop rather than review: a misheard prompt appears in the
transcript and `session.interrupt` kills the turn. Typed text still goes through
the composer and `Send`.

## Screens

Five, one job each.

```
  Sessions ──select──▶ Chat ──┬──▶ Approval  (modal, event-driven)
     │                        ├──▶ Clarify   (modal, event-driven)
     │                        └──▶ Tools     (drill-down from the tool strip)
     └──long-press──▶ Settings
```

### 1. Sessions — launch screen

```
┌──────────────────────────────┐ 240dp
│ Hermes            ● online   │ 16dp  status strip
├──────────────────────────────┤
│ ▸ + New session              │
│ ──────────────────────────── │
│ ▸ Refactor the auth module   │
│   working · 12 msgs · 3m ago │       ← status from session.active_list
│ ──────────────────────────── │
│ ▸ Fix the flaky CI job       │
│   idle · 40 msgs · 2h ago    │
│ ──────────────────────────── │
│ ▸ Draft release notes        │
│   idle · 8 msgs · yesterday  │
└──────────────────────────────┘ 320dp
```

Merge `session.active_list` (live, has `status`) with `session.list`
(persisted). Live sessions sort first — a `working` session is the one you most
likely walked away from.

`LiveSessionStatus` maps to a leading glyph so state is readable without
colour: `idle ·` / `starting ◦` / `waiting ?` / `working ●`.

### 2. Chat — the main screen

```
┌──────────────────────────────┐
│ gpt-5.6-sol      ● working   │ 16dp  model · status · ctx%
│                        ctx 34%│
├──────────────────────────────┤
│ you                          │
│ check why the build fails    │
│                              │
│ hermes                       │ ~230dp  transcript, wheel-scrolled
│ Looking at the CI config     │
│ now. The failure is in the   │
│ test matrix…▌                │       ← streaming cursor
│                              │
├──────────────────────────────┤
│ ▶ read_file ci.yml    1.2s   │ 18dp  tool strip (only while active)
├──────────────────────────────┤
│ [ 🎙 hold to talk ]    [⌨]  │ 40dp  composer
└──────────────────────────────┘
```

While a turn is running the composer's primary button becomes **`■ Stop`** →
`session.interrupt`. One reachable action, no menu. `session.interrupt` also
denies outstanding approvals and clears queued prompts, so Stop genuinely stops.

**Streaming.** `message.delta` arrives pre-coalesced (~33 ms server-side
batches). Append into a `StringBuilder` and invalidate on a ~20 fps ticker
rather than per frame. Auto-scroll to bottom **only when already at the
bottom** — yanking the view while the user reads back is the classic chat bug.

**Reasoning.** `reasoning.delta` / `thinking.delta` render dimmed and collapsed
to the last line. There is no room for a thinking panel; the last line is enough
to show it is alive.

**Tool strip.** One line, the most recent active tool, with elapsed time.
`tool.start` shows it, `tool.complete` marks it and fades after 2 s. Tapping
opens the Tools drill-down with the full list of the turn. A vertical tool feed
would eat the transcript.

### 3. Approval — modal, full screen

```
┌──────────────────────────────┐
│ ⚠ Approval required          │
├──────────────────────────────┤
│ Run a shell command          │       ← description
│                              │
│ ┌──────────────────────────┐ │
│ │ rm -rf build/ && make    │ │       ← command, monospace,
│ │ release                  │ │         scrollable, never elided
│ └──────────────────────────┘ │
│                              │
│  [ Allow once ]              │
│  [ Always allow ]            │       ← hidden when allowPermanent=false
│  [ Deny ]                    │
└──────────────────────────────┘
```

Full screen, not a sheet. This is a security decision on a 240 dp panel; it must
be deliberate and the command must be fully readable. **Never truncate
`command`** — make it scroll.

`allowPermanent: false` means the backend will not honour a permanent allow, so
the middle button is removed, not disabled.

Wheel moves the selection; side button confirms. Responds with
`approval.respond {session_id, choice, all}`.

### 4. Clarify — modal

`clarify.request` carries `{question, choices, requestId}`. With `choices`, a
wheel-selectable list. With `choices: null`, the voice composer with the
question pinned above. Responds `clarify.respond {request_id, answer}`.

Note `clarify.respond` is served with `allow_expired=True` — a late answer
resolves gracefully, so the client does not need to race the server's timeout.

### 5. Settings — long-press from Sessions

Base URL, Access client id/secret, Hermes session token, profile (default
`r1`), and a **Test connection** button that runs `/api/health` → WS
`gateway.ready` → `transcribe` with a 1-second clip and reports which hop
failed. Bring-up showed the failure modes are hop-specific (403 vs 401 vs 400
Host vs 502) and indistinguishable from a generic "cannot connect".

## Hardware input

| Control | Sessions | Chat | Modal |
| --- | --- | --- | --- |
| Wheel | scroll list | scroll transcript | move selection |
| Side button (hold) | — | push-to-talk | — |
| Side button (tap) | open | Stop while running | confirm |
| Touch | tap to select | tap composer / tool strip | tap a button |

### Measured key codes

Taken from the input probe on the device:

| Control | Event |
| --- | --- |
| Wheel up | `KEYCODE_DPAD_UP` (19) |
| Wheel down | `KEYCODE_DPAD_DOWN` (20) |
| Side button | `KEYCODE_BUTTON_1` (188) |
| Source | `257` (keyboard \| dpad) on all of them |

Three consequences:

- The wheel reports **key events, not** `SOURCE_ROTARY_ENCODER` motion, so
  there is no `AXIS_SCROLL` to read — handle it in `onKeyDown`. (The camera
  app's `DPAD_UP`/`DPAD_DOWN` guess happens to be right.)
- The side button **auto-repeats while held** — `KEY_DOWN` roughly every 50 ms,
  a single `KEY_UP` on release. Push-to-talk therefore works: start recording on
  the first `KEY_DOWN`, ignore repeats, stop on `KEY_UP`. Tap versus hold is a
  press-duration test.
- **There is no Back key.** Nothing emitted `KEYCODE_BACK` at all. Every screen
  needs an on-screen exit; a screen that consumes keys and relies on Back to
  leave traps the user (the first probe build did exactly that).

## Event → UI mapping

| Event | Effect |
| --- | --- |
| `gateway.ready` | connection established; enable composer |
| `session.info` | model, ctx%, cwd in the status strip |
| `status.update` | status word + glyph |
| `message.start` | open an assistant bubble |
| `message.delta` | append to buffer (20 fps repaint) |
| `message.complete` | finalise bubble, drop the cursor |
| `reasoning.delta` / `thinking.delta` | dimmed last line |
| `tool.start` / `tool.generating` / `tool.progress` | tool strip |
| `tool.complete` | mark + fade |
| `approval.request` | Approval modal |
| `clarify.request` | Clarify modal |
| `sudo.request` / `secret.request` | reuse the Clarify modal, masked input |
| `notification.show` / `clear` | transient toast in the status strip |
| `subagent.*` | count badge only — no room for a spawn tree |
| `gateway.stderr` / `gateway.protocol_error` | error banner |

## Reconnect

The socket will drop — it is a handheld on Wi-Fi.

1. Re-mint the Access-authenticated WS credential (Access service token headers
   are static; the Hermes session token is pinned in the host env, so neither
   expires mid-session — unlike the 30 s browser ticket flow).
2. `session.resume {session_id, cols: 34}`.
3. `inflight` in the result carries the turn that was streaming when the socket
   dropped — replay it into the transcript rather than showing a gap.
4. Exponential backoff, 1 s → 30 s, with a visible `● reconnecting` state.

## Build order

1. Connection layer — WS, JSON-RPC, event types, reconnect + resume
2. Settings + Test connection (nothing else is debuggable without it)
3. Chat: transcript, streaming, Stop
4. Approval / Clarify modals
5. Voice composer
6. Sessions list
7. Tools drill-down

Steps 1–2 are fully determined by the verified protocol and can start now.
