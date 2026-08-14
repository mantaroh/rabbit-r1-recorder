-- The latest usage reading pushed from the machine Codex and Claude Code run
-- on.
--
-- Pushed rather than polled. The tunnel to that machine is remotely managed,
-- so exposing a new hostname means editing Cloudflare's dashboard and adding
-- an Access policy; pushing to a Worker the device already authenticates
-- against needs neither, and opens no inbound path to a personal machine.
--
-- One row, replaced each time. History would be a different feature with a
-- different shape, and the standby screen only ever asks "what is it now".
CREATE TABLE IF NOT EXISTS usage_snapshot (
  id          INTEGER PRIMARY KEY CHECK (id = 1),
  body        TEXT NOT NULL,
  -- When the reporting machine measured it, from its own clock.
  generated_at INTEGER,
  -- When it arrived here. Both, because a stale push and a stalled clock look
  -- the same from one of them alone.
  received_at TEXT NOT NULL
);
