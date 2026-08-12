-- The default unicode61 tokenizer cannot segment Japanese: it treats a whole
-- run of CJK between spaces as one token, so searching 負け犬 misses a
-- transcript containing 負け犬が何. Measured on real data — 5 occurrences,
-- 0 hits.
--
-- trigram indexes every 3-character window instead, which gives substring
-- matching that works for CJK without a morphological analyser.
DROP TRIGGER IF EXISTS segments_fts_insert;
DROP TRIGGER IF EXISTS segments_fts_delete;
DROP TABLE IF EXISTS segments_fts;

CREATE VIRTUAL TABLE segments_fts USING fts5(
  transcript,
  segment_id UNINDEXED,
  started_at UNINDEXED,
  tokenize = 'trigram'
);

CREATE TRIGGER segments_fts_insert
AFTER UPDATE OF transcript ON segments
WHEN new.transcript IS NOT NULL AND new.transcript <> ''
BEGIN
  DELETE FROM segments_fts WHERE segment_id = new.segment_id;
  INSERT INTO segments_fts(transcript, segment_id, started_at)
  VALUES (new.transcript, new.segment_id, new.started_at);
END;

CREATE TRIGGER segments_fts_delete
AFTER DELETE ON segments
BEGIN
  DELETE FROM segments_fts WHERE segment_id = old.segment_id;
END;

-- Backfill everything already transcribed.
INSERT INTO segments_fts(transcript, segment_id, started_at)
SELECT transcript, segment_id, started_at
  FROM segments
 WHERE transcript IS NOT NULL AND transcript <> '';
