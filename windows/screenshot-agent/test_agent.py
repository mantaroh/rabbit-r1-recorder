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
from transport import upload


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
        text, jpeg = asyncio.run(recognize(data.getvalue()))
        self.assertIn("12345", text)
        self.assertIn("画面", text.replace(" ", ""))
        self.assertEqual(Image.open(io.BytesIO(jpeg)).size, (1920, 1080))


if __name__ == "__main__":
    unittest.main()
