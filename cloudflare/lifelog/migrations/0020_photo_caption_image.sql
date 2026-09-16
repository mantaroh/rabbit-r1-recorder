-- A smaller copy of the image for the vision model, kept beside the original.
-- NULL means the caption is written from r2_key, as it always was.
ALTER TABLE photos ADD COLUMN caption_r2_key TEXT;
