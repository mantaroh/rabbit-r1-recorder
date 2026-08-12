# Hermes gateway protocol

Notes taken from the Hermes Agent source on the `Ryzen` host
(`~/.hermes/hermes-agent`, NousResearch/hermes-agent @ `26b3918`). This is what
the R1 client implements against.

## Deployment (verified end to end)

    R1  ──HTTPS/WSS──▶  hermes-api.mantaroh.org
                        │  Cloudflare Access  (service token; no token ⇒ 403)
                        ▼
                        cloudflared tunnel   (HTTP Host Header rewritten to `localhost`)
                        ▼
                        127.0.0.1:9119       hermes serve  (systemd user unit)

Every request carries **two** credentials — they authenticate different hops:

| Header / param | Checked by | Notes |
| --- | --- | --- |
| `CF-Access-Client-Id` + `CF-Access-Client-Secret` | Cloudflare Access | Policy action must be **Service Auth**, not Allow — Allow expects browser identity and redirects a token-only client to the IdP login |
| `X-Hermes-Session-Token: <token>` | Hermes (REST) | `Authorization: Bearer <token>` also accepted |
| `?token=<token>` | Hermes (WebSocket) | Browsers cannot set headers on a WS upgrade; a native client could, but Hermes reads the query param here |

The session token comes from `HERMES_DASHBOARD_SESSION_TOKEN`, pinned in
`~/.hermes/hermes-serve.env` (mode 600) on the host. Left unset, `hermes serve`
mints a random one per boot and only injects it into the SPA HTML — unusable
for a native client.

Two traps worth remembering:

- **Binding `127.0.0.1` disables Hermes' own auth gate.** `should_require_auth()`
  returns False for loopback, so `/api/health` reports `auth_required: false`.
  The gated routes still demand the session token, but the OAuth/password gate
  never engages. Cloudflare Access is doing the real perimeter work.
- **Hermes rejects a foreign `Host`.** Bound to loopback it only accepts
  `localhost` / `127.0.0.1` (± port); a request arriving as
  `Host: hermes-api.mantaroh.org` gets
  `400 Invalid Host header`. The tunnel's HTTP Host Header override fixes this
  for every route at once.

Public paths that need no session token: `/api/health`, `/api/status`,
`/api/config/defaults`, `/api/config/schema`, `/api/model/info`,
`/api/dashboard/themes`, `/api/dashboard/plugins`, `/api/cron/fire`.

## Transport

`hermes serve` runs a FastAPI dashboard. The agent chat is **not** `/api/console`
(that is the dashboard's slash-command console). It is:

    WSS  <base>/api/ws   →  tui_gateway.ws.handle_ws

From `tui_gateway/ws.py`:

> Reuses `tui_gateway.server.dispatch` verbatim so every RPC method, every slash
> command, every approval/clarify/sudo flow, and every agent event flows through
> the same handlers whether the client is Ink over stdio or an **iOS / web client
> over WebSocket**.
>
> Wire protocol: identical to stdio — **newline-delimited JSON-RPC** in both
> directions. The server emits a `gateway.ready` event immediately after accept.

Confirmed against the live server — events arrive wrapped in a `method: "event"`
notification, with the discriminator on `params.type` and the body on
`params.payload`:

```json
{"jsonrpc":"2.0","method":"event","params":{"type":"gateway.ready","payload":{"skin":{...}}}}
```

So the R1 client is a first-class citizen of an existing, supported transport
rather than a scraper of the web UI.

### Auth

Two credential shapes (`hermes_cli/dashboard_auth/ws_tickets.py`):

| Mode | How |
| --- | --- |
| loopback | `?token=<session token>` on the upgrade |
| gated | `POST /api/auth/ws-ticket` (authenticated REST) → `?ticket=<ticket>` |

Tickets are **single-use with a 30 second TTL**, so the client must mint one per
connect — including every reconnect. Browsers cannot set `Authorization` on a WS
upgrade, which is why the credential rides in the query string.

Additional server-side gates that will close the socket:

| Close code | Meaning |
| --- | --- |
| 4401 | auth rejected |
| 4403 | Origin / host guard, or embedded chat disabled |
| 4408 | client guard |

`_DASHBOARD_EMBEDDED_CHAT_ENABLED` must be on or `/api/ws` closes with 4403.

## Requests (client → server)

| Method | Key params | Result |
| --- | --- | --- |
| `session.create` | `cols`, `cwd?`, `title?`, `model?`, `provider?`, `reasoning_effort?`, `profile?`, `parent_session_id?` | `{session_id, info}` |
| `session.resume` | `session_id`, `cols`, `omit_messages?`, `profile?` | `{session_id, messages[], status, running, inflight?, info, message_count, started_at}` |
| `session.active_list` | — | `{sessions[]}` — live sessions with `status` |
| `session.list` | — | `{sessions[]}` — persisted history |
| `session.activate` | `session_id` | like resume |
| `prompt.submit` | `session_id`, `text`, `queued?`, `surface?` | turn starts; output arrives as events |
| `session.interrupt` | `session_id` | `{status: "interrupted"}` — this is Stop |
| `approval.respond` | `session_id`, `choice` (default `"deny"`), `all?` | `{resolved}` |
| `clarify.respond` | `request_id`, `answer` | resolves the pending question |
| `sudo.respond` / `secret.respond` | `request_id`, `password` / `value` | — |
| `tools.list` / `tools.show` | — | tool catalogue |
| `complete.slash` / `complete.path` | prefix | completion items |

`session.interrupt` also denies every outstanding approval (`resolve_all=True`)
and clears queued prompts, so Stop is a single call.

## Events (server → client)

Notifications carrying `params.type`:

| Group | Types |
| --- | --- |
| lifecycle | `gateway.ready`, `gateway.stderr`, `gateway.protocol_error`, `gateway.start_timeout` |
| streaming | `message.start`, `message.delta`, `message.interim`, `message.complete` |
| reasoning | `reasoning.delta`, `reasoning.available`, `thinking.delta` |
| tools | `tool.start`, `tool.generating`, `tool.progress`, `tool.complete` |
| interaction | `approval.request`, `clarify.request`, `sudo.request`, `secret.request` |
| status | `status.update`, `session.info`, `notification.show`, `notification.clear` |
| subagents | `subagent.start`, `subagent.progress`, `subagent.complete`, `subagent.tool`, `subagent.thinking` |
| voice | `voice.status`, `voice.transcript`, `wake.detected` |

`message.delta`, `reasoning.delta` and `thinking.delta` are **coalesced by the
server** into ~33 ms batches, so the client receives bursts rather than one frame
per token. Everything a user must see promptly (tool, approval, status,
completion) is non-streaming and flushes the buffer ahead of itself, so ordering
is preserved.

## Payload shapes

From the Ink client's own types (`ui-tui/src/types.ts`,
`ui-tui/src/gatewayTypes.ts`) — the R1 client mirrors these:

```ts
ApprovalReq  { command, description, choices?, allowPermanent?, smartDenied? }
ClarifyReq   { question, choices: string[] | null, requestId }
ActiveTool   { id, name, context?, verboseArgs?, startedAt? }
LiveSessionStatus = 'idle' | 'starting' | 'waiting' | 'working'
SessionActiveItem { id, status, title?, preview?, model?, last_active?, message_count? }
SessionInfo  { model, tools, skills, usage, cwd, profile_name?, reasoning_effort? }
Usage        { input, output, total, context_percent?, context_used?, cost_usd? }
```

`allowPermanent: false` means the backend will not honour a permanent allow, so
the client must hide any "Always allow" affordance for that request.

## Speech recognition

Hermes already owns transcription, so the R1 client uploads audio and never
holds a provider key.

    POST <base>/api/audio/transcribe[?profile=<name>]
    { "data_url": "data:audio/mp4;base64,<...>", "mime_type": "audio/mp4" }
    → { "ok": true, "transcript": "...", "provider": "openai" }

Contract details that shape the recorder:

| Constraint | Value |
| --- | --- |
| Max upload | 25 MiB |
| Accepted MIME | `audio/aac`, `audio/flac`, `audio/m4a`, `audio/mp3`, `audio/mp4`, `audio/mpeg`, `audio/ogg`, `audio/wav`, `audio/webm`, `video/webm` |
| Empty transcript | `{"ok": true, "transcript": ""}` — silence, **not** an error |

The empty-transcript rule matters: it lets a VAD / wake-word loop re-listen on a
quiet turn instead of surfacing a failure toast. Android's `MediaRecorder` with
`MPEG_4` + `AAC` produces `audio/mp4`, which is on the accepted list.

### The `r1` profile

The model is **host configuration, not client code** — `hermes_cli/config_defaults.py`
documents the allowed values inline:

```python
"openai": {
    "model": "whisper-1",  # whisper-1, gpt-4o-mini-transcribe, gpt-4o-transcribe, gpt-transcribe
}
```

`stt.provider` is per-profile, and the host default drives every other surface
(Telegram, Discord, CLI voice mode). So the R1 gets its own profile instead of
moving the whole host onto OpenAI:

```
hermes profile create r1 --clone      # → /home/mantaroh/.hermes/profiles/r1
r1 config set stt.provider openai
r1 config set stt.openai.model gpt-transcribe
```

Resulting split on the Ryzen host:

| Profile | `stt.provider` | model |
| --- | --- | --- |
| default | `local` | whisper `base` |
| `r1` | `openai` | `gpt-transcribe` |

`--clone` carries `.env` across, so the profile has its own `OPENAI_API_KEY`
without touching the default.

**Every R1 call must therefore carry `?profile=r1`** — on `/api/audio/transcribe`
and on `session.create` / `session.resume` (which take `profile` as an RPC
param). Omitting it silently falls back to the launch profile, which still runs
local whisper.

**The key stays on the host.** CarrotOS exposes a root shell on
`127.0.0.1:1337` to every installed app, so an OpenAI key stored on the R1 would
be readable by any other APK. Proxying through Hermes avoids putting a billable
credential on a device with no meaningful app isolation.

## Notes for the R1 client

- `cols` is part of `session.create` / `session.resume`. The gateway wraps output
  to it, so it must reflect the R1's usable character width, not a default 80.
- Voice events already exist server-side, so the spec's future voice control has
  a protocol path (`voice.status`, `voice.transcript`, `wake.detected`).
- Reconnect must re-mint a ticket and re-`session.resume`; `inflight` in the
  resume result carries a turn that was streaming when the socket dropped.
