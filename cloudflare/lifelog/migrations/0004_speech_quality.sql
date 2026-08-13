-- Whisper hallucinates on silence, and says nothing useful about it:
-- `no_speech_prob` came back as 0 — confidently "there is speech" — for a
-- 3 a.m. segment transcribed as "Thank you. Thank you.".
--
-- What does discriminate is how much of the segment the model actually placed
-- words in. That example: two spans, 4.48–6.10 s and 34.80–35.10 s, so 1.9
-- seconds of "speech" in 60 — a ratio of 0.03. Real conversation runs an order
-- of magnitude higher. `language_probability` helps too; it was 0.49 for
-- English in a Japanese household.
--
-- Store the signals rather than acting on them at write time: the threshold can
-- then be tuned against real data without re-running (and re-paying for)
-- transcription, and nothing is thrown away.
ALTER TABLE segments ADD COLUMN speech_ratio REAL;
ALTER TABLE segments ADD COLUMN language TEXT;
ALTER TABLE segments ADD COLUMN language_prob REAL;
ALTER TABLE segments ADD COLUMN word_count INTEGER;

CREATE INDEX IF NOT EXISTS idx_segments_speech ON segments(speech_ratio);
