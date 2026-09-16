import asyncio
import io
import json
from pathlib import Path
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

from PIL import Image, ImageDraw, ImageFont
import capture
import config
from store import Store
from transport import multipart, upload


class OutboxTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name)
        self.store = Store(self.directory)

    def add(self):
        return self.store.add("2026-09-16T00:00:00Z", dict(device_id="pc-test", capture_id="a" * 32,
                              monitor_id="DISPLAY1", width=1920, height=1080), b"png")

    def test_identical_frames_remain_distinct_across_restart(self):
        first, second = self.add(), self.add()
        self.assertNotEqual(first, second)
        self.assertEqual(Store(self.directory).stats()["pending"], 2)

    def test_failed_send_retains_frame_and_delays_retry(self):
        record_id = self.add()
        self.store.failed(record_id, 0, "offline")
        self.assertIsNone(self.store.next())
        self.assertEqual(self.store.get(record_id)["png"], b"png")
        self.store.retry_now()
        self.assertEqual(self.store.next()["id"], record_id)

    def test_prepare_is_durable_until_receipt(self):
        record_id = self.add()
        self.store.prepared(record_id, "文字", b"jpeg")
        row = Store(self.directory).get(record_id)
        self.assertEqual(row["ocr_text"], "文字")
        self.assertEqual(row["jpeg"], b"jpeg")
        self.assertIsNone(row["png"])
        self.store.acknowledge(record_id)
        self.assertEqual(self.store.stats()["pending"], 0)

    def test_caption_copy_is_kept_until_receipt(self):
        record_id = self.add()
        self.store.prepared(record_id, "文字", b"jpeg", b"small")
        self.assertEqual(Store(self.directory).get(record_id)["caption_jpeg"], b"small")
        self.assertEqual(self.store.stats()["bytes"], len(b"jpeg") + len(b"small"))

    def test_outbox_from_before_the_caption_copy_still_opens(self):
        import sqlite3
        older = self.directory / "older"
        older.mkdir()
        db = sqlite3.connect(older / "outbox.sqlite3")
        db.executescript("""
            CREATE TABLE captures (
                id TEXT PRIMARY KEY, taken_at TEXT NOT NULL, metadata TEXT NOT NULL,
                png BLOB, jpeg BLOB, ocr_text TEXT,
                attempts INTEGER NOT NULL DEFAULT 0, retry_at REAL NOT NULL DEFAULT 0,
                error TEXT, created REAL NOT NULL);
            INSERT INTO captures(id,taken_at,metadata,jpeg,ocr_text,created)
            VALUES('pc_old','2026-09-16T00:00:00Z','{}',x'ffd8','文字',1);
        """)
        db.commit()
        db.close()
        store = Store(older)
        self.assertIsNone(store.get("pc_old")["caption_jpeg"])
        Store(older)  # Opening twice must not try to add the column again.

    def test_two_screens_every_time_even_without_change(self):
        screens = [("left", (-1920, 0, 0, 1080)), ("right", (0, 0, 2560, 1440))]
        with patch.object(capture, "desktop_available", return_value=True), patch.object(capture, "monitors", return_value=screens), patch.object(capture.ImageGrab, "grab", return_value=Image.new("RGB", (50, 30))):
            self.assertEqual(capture.take(self.store, "pc-test"), 2)
            self.assertEqual(capture.take(self.store, "pc-test"), 2)
        with self.store.connect() as db:
            rows = db.execute("SELECT metadata FROM captures ORDER BY created").fetchall()
        meta = [json.loads(r[0]) for r in rows]
        self.assertEqual(len(meta), 4)
        self.assertEqual(meta[0]["capture_id"], meta[1]["capture_id"])
        self.assertNotEqual(meta[0]["capture_id"], meta[2]["capture_id"])

    def test_locked_desktop_does_not_create_fake_frame(self):
        with patch.object(capture, "desktop_available", return_value=False):
            self.assertEqual(capture.take(self.store, "pc-test"), 0)

    def test_bad_receipt_is_not_success(self):
        record_id = self.add()
        self.store.prepared(record_id, "画面", b"jpeg")
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, *args): return b'{"photo_id":"wrong","source":"r1"}'
        class Opener:
            def open(self, req, **kwargs):
                response = Response()
                if req.get_method() == "GET":
                    response.read = lambda *args: b'{"ok":true,"desktop_upload":1}'
                return response
        with self.assertRaises(RuntimeError):
            upload(dict(endpoint="http://localhost:8787", access_id="", token="test"), self.store.get(record_id), Opener())
        self.assertEqual(self.store.stats()["pending"], 1)

    def test_old_server_never_receives_screenshot_bytes(self):
        record_id = self.add()
        self.store.prepared(record_id, "画面", b"jpeg")
        methods = []
        class Response:
            status = 200
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, *args): return b'{"ok":true}'
        class Opener:
            def open(self, req, **kwargs):
                methods.append(req.get_method())
                return Response()
        with self.assertRaisesRegex(RuntimeError, "未対応"):
            upload(dict(endpoint="http://localhost:8787", access_id="", token="test"), self.store.get(record_id), Opener())
        self.assertEqual(methods, ["GET"])

    def test_caption_copy_travels_as_its_own_part(self):
        body, _ = multipart(b"ORIGINAL", {"ocr_text": ""}, b"SMALL")
        self.assertIn(b'name="caption_image"; filename="caption.jpg"\r\nContent-Type: image/jpeg\r\n\r\nSMALL\r\n', body)
        self.assertIn(b'name="image"; filename="screen.jpg"\r\nContent-Type: image/jpeg\r\n\r\nORIGINAL\r\n', body)
        body, _ = multipart(b"ORIGINAL", {"ocr_text": ""}, None)
        self.assertNotIn(b"caption_image", body)

    def test_upload_sends_the_caption_copy_it_has(self):
        record_id = self.add()
        self.store.prepared(record_id, "画面", b"jpeg", b"small")
        sent = []
        class Response:
            status = 201
            def __enter__(self): return self
            def __exit__(self, *args): pass
            def read(self, *args): return json.dumps({"photo_id": record_id, "source": "desktop"}).encode()
        class Opener:
            def open(self, req, **kwargs):
                response = Response()
                if req.get_method() == "GET":
                    response.status = 200
                    response.read = lambda *args: b'{"ok":true,"desktop_upload":1}'
                else:
                    sent.append(req.data)
                return response
        upload(dict(endpoint="http://localhost:8787", access_id="", token="test"), self.store.get(record_id), Opener())
        self.assertIn(b'name="caption_image"', sent[0])

    def test_default_interval_is_one_minute(self):
        self.assertEqual(config.defaults()["interval_seconds"], 60)

    def test_second_instance_is_told_the_first_is_running(self):
        from agent import single_instance
        first = single_instance(self.directory)
        self.assertIsNotNone(first)
        try:
            # Reading the locked byte used to raise PermissionError here,
            # which surfaced as a startup failure instead of this answer.
            self.assertIsNone(single_instance(self.directory))
        finally:
            first.close()
        again = single_instance(self.directory)
        self.assertIsNotNone(again)
        again.close()

    def test_resume_wake_does_not_capture_twice_immediately(self):
        from agent import Agent
        service = Agent(self.directory, {**config.defaults(), "token": "test", "interval_seconds": 0.2})
        stamps = []
        def captured(*args):
            stamps.append(time.monotonic())
            if len(stamps) == 3:
                service.stop.set()
            return 1
        # A pending resume signal at a due time caused two immediate frames.
        service.wake.set()
        with patch("agent.take", side_effect=captured):
            thread = threading.Thread(target=service.capture_loop)
            thread.start()
            thread.join(timeout=3)
            service.stop.set()
            thread.join(timeout=1)
        self.assertEqual(len(stamps), 3)
        self.assertTrue(all(b - a >= 0.15 for a, b in zip(stamps, stamps[1:])), stamps)

    def test_dpapi_roundtrip_does_not_store_plain_credentials(self):
        configured = {**config.defaults(), "token": "test-secret-123", "access_id": "test-id", "access_secret": "test-access-secret"}
        config.save(configured, self.directory)
        self.assertEqual(config.load(self.directory), configured)
        self.assertNotIn("test-secret-123", (self.directory / "config.json").read_text())

    def test_only_localhost_may_use_plain_http(self):
        configured = {**config.defaults(), "token": "test", "endpoint": "http://example.com"}
        with self.assertRaises(ValueError): config.validate(configured)
        configured["endpoint"] = "http://127.0.0.1:8787"
        config.validate(configured)


class WindowsOcrTests(unittest.TestCase):
    def test_japanese_text_at_original_resolution(self):
        from ocr import recognize
        image = Image.new("RGB", (1920, 1080), "white")
        font = ImageFont.truetype("C:/Windows/Fonts/meiryo.ttc", 40)
        ImageDraw.Draw(image).text((80, 80), "画面記録 動作確認 12345", font=font, fill="black")
        data = io.BytesIO()
        image.save(data, "PNG")
        text, jpeg, small = asyncio.run(recognize(data.getvalue()))
        self.assertIn("12345", text)
        self.assertIn("画面", text.replace(" ", ""))
        self.assertEqual(Image.open(io.BytesIO(jpeg)).size, (1920, 1080))
        self.assertEqual(Image.open(io.BytesIO(small)).size, (960, 540))


class CaptionCopyTests(unittest.TestCase):
    def test_long_edge_becomes_960(self):
        from ocr import caption_copy
        for size, expected in [((2560, 1440), (960, 540)), ((1080, 1920), (540, 960)), ((800, 600), (800, 600))]:
            small = Image.open(io.BytesIO(caption_copy(Image.new("RGB", size, "white"))))
            self.assertEqual(small.format, "JPEG")
            self.assertEqual(small.size, expected)


if __name__ == "__main__":
    unittest.main()
