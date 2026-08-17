-- A switch the web page can throw when the R1 is not where it should be.
--
-- One row, because there is one device. The state is kept here rather than in
-- a Worker variable so that it survives a deploy and an isolate being recycled
-- — a kill switch that forgets is not one.
CREATE TABLE IF NOT EXISTS device_state (
  id          INTEGER PRIMARY KEY CHECK (id = 1),

  -- 'ok' or 'lost'. Nothing else is meaningful; anything unrecognised is
  -- treated as lost, because the failure that matters is a flag that quietly
  -- stops working.
  state       TEXT    NOT NULL DEFAULT 'ok',

  -- Why, and when. Both for the person reading this months later trying to
  -- remember whether the device was actually recovered.
  note        TEXT,
  changed_at  TEXT    NOT NULL
);

INSERT OR IGNORE INTO device_state (id, state, note, changed_at)
VALUES (1, 'ok', 'initial', datetime('now'));
