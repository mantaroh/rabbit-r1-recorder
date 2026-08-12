-- Segments are keyed by the id the device generated, so a retry after a
-- network failure lands on the same row instead of duplicating a transcript.
CREATE TABLE IF NOT EXISTS segments (
  segment_id    TEXT PRIMARY KEY,
  device_id     TEXT NOT NULL,
  kind          TEXT NOT NULL DEFAULT 'lifelog',   -- lifelog | query
  r2_key        TEXT NOT NULL,
  codec         TEXT,
  sample_rate   INTEGER,
  -- Wall-clock times from the device. Uploads can be deferred for days on a
  -- Wi-Fi-only policy, so ordering must never come from arrival time.
  started_at    TEXT NOT NULL,
  ended_at      TEXT,
  duration_ms   INTEGER,
  bytes         INTEGER,
  received_at   TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'pending',   -- pending | transcribed | failed
  transcript    TEXT,
  transcribed_at TEXT,
  error         TEXT
);

CREATE INDEX IF NOT EXISTS idx_segments_started ON segments(started_at);
CREATE INDEX IF NOT EXISTS idx_segments_status  ON segments(status);
CREATE INDEX IF NOT EXISTS idx_segments_device  ON segments(device_id, started_at);

-- Keyword search over transcripts. Kept in step with `segments` by triggers so
-- callers only ever write to the base table.
CREATE VIRTUAL TABLE IF NOT EXISTS segments_fts USING fts5(
  transcript,
  segment_id UNINDEXED,
  started_at UNINDEXED
);

CREATE TRIGGER IF NOT EXISTS segments_fts_insert
AFTER UPDATE OF transcript ON segments
WHEN new.transcript IS NOT NULL AND new.transcript <> ''
BEGIN
  DELETE FROM segments_fts WHERE segment_id = new.segment_id;
  INSERT INTO segments_fts(transcript, segment_id, started_at)
  VALUES (new.transcript, new.segment_id, new.started_at);
END;

CREATE TRIGGER IF NOT EXISTS segments_fts_delete
AFTER DELETE ON segments
BEGIN
  DELETE FROM segments_fts WHERE segment_id = old.segment_id;
END;
