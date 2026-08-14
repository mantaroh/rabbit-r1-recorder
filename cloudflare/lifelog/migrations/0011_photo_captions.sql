-- Captions for the timelapse frames.
--
-- The same shape as a transcript: derived from the artefact, cheap to redo,
-- and therefore stored beside it rather than in place of it. A better model
-- in a few years re-reads the same JPEG.
--
-- Measured on one frame before building this: 3231 input tokens for a 640x480
-- image plus prompt, ~$0.0002 per photograph, five seconds a call. Five
-- seconds is why it goes through the queue instead of the upload path.
ALTER TABLE photos ADD COLUMN caption TEXT;
ALTER TABLE photos ADD COLUMN caption_at TEXT;

-- Neurons spent, so the running cost of the archive can be read out of the
-- archive rather than estimated from a pricing page.
ALTER TABLE photos ADD COLUMN caption_neurons REAL;

-- 'pending' until captioned, then 'captioned', or 'failed' when the model
-- refuses the image for good.
ALTER TABLE photos ADD COLUMN status TEXT NOT NULL DEFAULT 'pending';

ALTER TABLE photos ADD COLUMN error TEXT;

CREATE INDEX IF NOT EXISTS photos_status ON photos(status);
