-- Marks a transcript that is nothing but one of Whisper's stock phrases.
--
-- Not deleted, and not merged into speech_ratio: the recording is real, the
-- measurement is real, and only the text is wrong. Hiding it is a property of
-- the index, so this is a flag the read paths consult rather than an edit to
-- anything that was observed.
--
-- Needed because the speech_ratio filter does not catch these. "All right.
-- All right." scores 0.23 and "お疲れ様でした" 0.54, both well clear of the
-- 0.15 threshold, because Whisper really did place words across the segment —
-- they are simply the wrong words.
ALTER TABLE segments ADD COLUMN stock_phrase INTEGER NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS segments_stock_phrase ON segments(stock_phrase);
