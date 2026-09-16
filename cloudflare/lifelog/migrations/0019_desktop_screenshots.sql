-- Original-resolution desktop OCR is separate from the vision description.
ALTER TABLE photos ADD COLUMN ocr_text TEXT;
ALTER TABLE photos ADD COLUMN capture_id TEXT;
ALTER TABLE photos ADD COLUMN monitor_id TEXT;
ALTER TABLE photos ADD COLUMN width INTEGER;
ALTER TABLE photos ADD COLUMN height INTEGER;

-- Rebuild the existing caption index with an additional searchable OCR column.
DROP TRIGGER IF EXISTS photos_fts_update;
DROP TRIGGER IF EXISTS photos_fts_delete;
DROP TABLE photos_fts;
CREATE VIRTUAL TABLE photos_fts USING fts5(
  caption, ocr_text, photo_id UNINDEXED, taken_at UNINDEXED, tokenize = 'trigram'
);
CREATE TRIGGER photos_fts_insert AFTER INSERT ON photos BEGIN
  INSERT INTO photos_fts(caption, ocr_text, photo_id, taken_at)
  SELECT new.caption, new.ocr_text, new.photo_id, new.taken_at
  WHERE COALESCE(new.caption, '') <> '' OR COALESCE(new.ocr_text, '') <> '';
END;
CREATE TRIGGER photos_fts_update AFTER UPDATE OF caption, ocr_text ON photos BEGIN
  DELETE FROM photos_fts WHERE photo_id = new.photo_id;
  INSERT INTO photos_fts(caption, ocr_text, photo_id, taken_at)
  SELECT new.caption, new.ocr_text, new.photo_id, new.taken_at
  WHERE COALESCE(new.caption, '') <> '' OR COALESCE(new.ocr_text, '') <> '';
END;
CREATE TRIGGER photos_fts_delete AFTER DELETE ON photos BEGIN
  DELETE FROM photos_fts WHERE photo_id = old.photo_id;
END;
INSERT INTO photos_fts(caption, ocr_text, photo_id, taken_at)
SELECT caption, ocr_text, photo_id, taken_at FROM photos
WHERE COALESCE(caption, '') <> '' OR COALESCE(ocr_text, '') <> '';
