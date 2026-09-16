"""Durable outbox: captures are committed before OCR or any network request."""
from contextlib import contextmanager
import json
from pathlib import Path
import sqlite3
import time
import uuid


class Store:
    def __init__(self, directory):
        Path(directory).mkdir(parents=True, exist_ok=True)
        self.path = Path(directory) / "outbox.sqlite3"
        with self.connect() as db:
            db.executescript("""
                PRAGMA journal_mode=WAL;
                CREATE TABLE IF NOT EXISTS captures (
                    id TEXT PRIMARY KEY, taken_at TEXT NOT NULL, metadata TEXT NOT NULL,
                    png BLOB, jpeg BLOB, ocr_text TEXT,
                    attempts INTEGER NOT NULL DEFAULT 0, retry_at REAL NOT NULL DEFAULT 0,
                    error TEXT, created REAL NOT NULL
                );
                CREATE INDEX IF NOT EXISTS captures_retry ON captures(retry_at, created);
            """)

    @contextmanager
    def connect(self):
        db = sqlite3.connect(self.path, timeout=15)
        db.row_factory = sqlite3.Row
        try:
            with db:
                yield db
        finally:
            db.close()

    def add(self, taken_at, metadata, png):
        # Never identify by image content: identical screens at different times
        # are distinct records. Retries reuse this persisted identifier.
        record_id = "pc_" + uuid.uuid4().hex
        with self.connect() as db:
            db.execute("INSERT INTO captures(id,taken_at,metadata,png,created) VALUES(?,?,?,?,?)",
                       (record_id, taken_at, json.dumps(metadata), png, time.time()))
        return record_id

    def next(self):
        with self.connect() as db:
            return db.execute("SELECT * FROM captures WHERE retry_at<=? ORDER BY created LIMIT 1",
                              (time.time(),)).fetchone()

    def prepared(self, record_id, text, jpeg):
        with self.connect() as db:
            db.execute("UPDATE captures SET ocr_text=?, jpeg=?, png=NULL WHERE id=?", (text, jpeg, record_id))

    def get(self, record_id):
        with self.connect() as db:
            return db.execute("SELECT * FROM captures WHERE id=?", (record_id,)).fetchone()

    def acknowledge(self, record_id):
        with self.connect() as db:
            db.execute("DELETE FROM captures WHERE id=?", (record_id,))

    def failed(self, record_id, attempts, error):
        delay = min(3600, 5 * 2 ** min(attempts, 10))
        with self.connect() as db:
            db.execute("UPDATE captures SET attempts=attempts+1,retry_at=?,error=? WHERE id=?",
                       (time.time() + delay, error[:200], record_id))

    def stats(self):
        with self.connect() as db:
            row = db.execute("SELECT COUNT(*) AS pending, COALESCE(SUM(LENGTH(png)),0)+COALESCE(SUM(LENGTH(jpeg)),0) AS bytes, SUM(error IS NOT NULL) AS errors FROM captures").fetchone()
            return dict(row)

    def retry_now(self):
        with self.connect() as db:
            db.execute("UPDATE captures SET retry_at=0")
