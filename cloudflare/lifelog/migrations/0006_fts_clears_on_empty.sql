-- The FTS trigger only fired when the new transcript was non-empty, so a
-- transcript that was corrected *to* empty left its old text in the index.
--
-- That is exactly what re-transcribing does to a hallucination: "All right.
-- All right." over a silent minute becomes "", the trigger declines to fire,
-- and the phrase stays searchable forever with a row behind it that no longer
-- contains it.
--
-- Split the two halves: always drop the stale row, insert only when there is
-- something to index.
DROP TRIGGER IF EXISTS segments_fts_insert;

CREATE TRIGGER segments_fts_update
AFTER UPDATE OF transcript ON segments
BEGIN
  DELETE FROM segments_fts WHERE segment_id = new.segment_id;
  INSERT INTO segments_fts(transcript, segment_id, started_at)
  SELECT new.transcript, new.segment_id, new.started_at
   WHERE new.transcript IS NOT NULL AND new.transcript <> '';
END;
