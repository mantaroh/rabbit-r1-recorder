-- Time comparisons were being done on the ISO strings the device sent, which
-- carry a +09:00 offset, against bounds the Worker computed with toISOString(),
-- which are UTC with a Z. SQLite compares TEXT lexicographically, so
-- "…T22:44:58.000+09:00" >= "…T11:00:00.000Z" is a string comparison that
-- happens to be true and means nothing. A one-hour window returned 200 rows
-- from all over the day.
--
-- Store an integer instead. Epoch seconds have no format to disagree about.
ALTER TABLE segments ADD COLUMN started_epoch INTEGER;

-- Backfill. SQLite's strftime understands the ±HH:MM offset and normalises it.
UPDATE segments
   SET started_epoch = CAST(strftime('%s', started_at) AS INTEGER)
 WHERE started_epoch IS NULL;

CREATE INDEX IF NOT EXISTS idx_segments_epoch ON segments(started_epoch);
