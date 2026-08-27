-- The moment a person decides the device should say its one prepared line.
--
-- A counter, not a queue and not a flag. The device remembers the number it
-- last spoke and compares: a poll that is lost costs nothing, because the next
-- one two seconds later carries the same number, and a button pressed twice by
-- a nervous hand is still one number and so still one utterance. Neither the
-- line nor the audio is here — both are compiled into the app, because a cue
-- that has to reach a speech API before anybody hears it is a cue that fails
-- on conference Wi-Fi.
--
-- One row, because there is one device and one moment.
CREATE TABLE IF NOT EXISTS cue (
  id           INTEGER PRIMARY KEY CHECK (id = 1),

  seq          INTEGER NOT NULL DEFAULT 0,
  fired_at     TEXT,

  -- When the device last asked. This exists because the failure the whole
  -- feature is built around is a button that appears to work and reaches
  -- nothing: without a heartbeat, a device that is not on the stage screen and
  -- a device that is are indistinguishable from the page until the room is
  -- already watching.
  polled_at    TEXT,
  polled_epoch INTEGER
);

INSERT OR IGNORE INTO cue (id, seq) VALUES (1, 0);
