-- Per-second loudness for each segment, measured on the device where the PCM
-- already exists, and the voiced fraction the Worker derived from it.
--
-- The envelope is stored rather than just the verdict. A threshold is a guess
-- until it has been checked against a few weeks of real days, and re-deriving
-- it from stored numbers costs nothing, while re-deriving it from the audio
-- would mean decoding Opus for every segment ever recorded.
--
-- 60 bytes per minute of audio, base64 — about 500 KB per year.
ALTER TABLE segments ADD COLUMN rms_envelope TEXT;

-- Fraction of seconds in the segment above the speech threshold. NULL for
-- rows uploaded before the device sent an envelope; those were transcribed
-- unconditionally and cannot be re-judged without the audio.
ALTER TABLE segments ADD COLUMN voiced_ratio REAL;
