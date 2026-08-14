-- Timelapse frames: one pair every five minutes, front and rear.
--
-- A separate table rather than a `kind` on segments. They share the bucket and
-- the auth and nothing else: no transcript, no speech ratio, no queue, and
-- none of the read paths that exist for audio would ever want to return one.
-- Folding them in would mean every audio query growing a clause to exclude
-- them.
CREATE TABLE IF NOT EXISTS photos (
  photo_id    TEXT PRIMARY KEY,
  device_id   TEXT NOT NULL,
  r2_key      TEXT NOT NULL,
  -- "front" (0°, toward the wearer) or "rear" (180°, away).
  facing      TEXT NOT NULL,
  taken_at    TEXT NOT NULL,
  taken_epoch INTEGER NOT NULL,
  bytes       INTEGER,
  sha256      TEXT,
  received_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS photos_taken_epoch ON photos(taken_epoch);
