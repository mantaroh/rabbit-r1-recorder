"""End-to-end check against wrangler.desktop-test.jsonc, never production."""
import argparse
import asyncio
from datetime import datetime, timezone
import io
import json
from pathlib import Path
import tempfile
import time
from urllib import request, parse
from PIL import Image, ImageDraw, ImageFont
from agent import Agent
from capture import enable_dpi, take
from config import defaults
from store import Store


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--real-displays", action="store_true", help="Capture actual screens; otherwise use synthetic text")
    args = parser.parse_args()
    endpoint = "http://127.0.0.1:8799"

    def api(path, body=None):
        data = json.dumps(body).encode() if body else None
        req = request.Request(endpoint + path, data=data,
                              headers={"Authorization": "Bearer desktop-test-admin", "Content-Type": "application/json"})
        with request.urlopen(req, timeout=15) as response:
            return json.load(response)

    with tempfile.TemporaryDirectory(prefix="lifelog-e2e-") as directory:
        agent = Agent(Path(directory), {**defaults(), "endpoint": endpoint, "token": "desktop-test-ingest"})
        if args.real_displays:
            enable_dpi()
            count = take(agent.store, "pc-local-test")
            assert count > 0, "No available displays"
        else:
            image = Image.new("RGB", (1920, 1080), "white")
            ImageDraw.Draw(image).text((80, 80), "画面記録 特別検証語 12345", fill="black", font=ImageFont.truetype("C:/Windows/Fonts/meiryo.ttc", 40))
            png = io.BytesIO()
            image.save(png, "PNG")
            stamp = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
            # Same content twice must produce distinct records.
            for index in range(2):
                agent.store.add(stamp, dict(capture_id=("a" if index == 0 else "b") * 32,
                                           device_id="pc-local-test", monitor_id="test", width=1920, height=1080), png.getvalue())
            count = 2
        with agent.store.connect() as db:
            expected = {r[0]: json.loads(r[1]) for r in db.execute("SELECT id,metadata FROM captures")}
            ids = set(expected)
        for _ in range(count):
            agent.process_one()
        assert agent.store.stats()["pending"] == 0, agent.send_status
        context = api("/v1/context?before_sec=120")
        found = [p for p in context["photos"] if p["photo_id"] in ids]
        assert len(found) == count, "Missing screenshots in context"
        assert all(p["source"] == "desktop" and p["ocr_text"] for p in found)
        assert context["text"] == "", "Screenshot text leaked into speech"
        for photo_id, metadata in expected.items():
            req = request.Request(endpoint + "/v1/media/photo/" + photo_id,
                                  headers={"Authorization": "Bearer desktop-test-admin"})
            with request.urlopen(req, timeout=15) as response:
                size = Image.open(io.BytesIO(response.read())).size
            assert size == (metadata["width"], metadata["height"])
        day = api("/v1/day?date=" + datetime.now().date().isoformat())
        shots = [shot for entry in day["entries"] if entry["kind"] == "photo" for shot in entry["shots"] if shot["id"] in ids]
        assert len(shots) == count and all(shot["label"].startswith("PC") and shot["ocr_text"] for shot in shots)
        # Exercise FTS and LIKE using a real OCR substring; never print screen text.
        line = next(line for line in found[0]["ocr_text"].splitlines() if len(line.strip()) >= 4).strip()
        for word, mode in [(line[:4], "fts"), (line[:2], "scan")]:
            result = api("/v1/search?q=" + parse.quote(word) + "&limit=100")
            assert result["mode"] == mode
            assert any(r["id"] == found[0]["photo_id"] for r in result["results"])
        result = api("/mcp", {"jsonrpc": "2.0", "id": 1, "method": "tools/call",
                              "params": {"name": "lifelog_search", "arguments": {"query": line[:4], "limit": 100}}})
        assert not result.get("error"), result.get("error")
        assert any(p["source"] == "desktop" for p in json.loads(result["result"]["content"][0]["text"])["results"])
        # Queue consumer is asynchronous even locally.
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            context = api("/v1/context?before_sec=120")
            found = [p for p in context["photos"] if p["photo_id"] in ids]
            if all(p["caption"] for p in found):
                break
            time.sleep(0.5)
        assert all(p["caption"] == "ローカル検証用のPC画面です。" for p in found)
        print(json.dumps(dict(captures=count, native_displays=args.real_displays,
                              ocr_characters=[len(p["ocr_text"]) for p in found],
                              upload=True, outbox_empty=True, fts=True, scan=True, context=True, mcp=True,
                              queue_caption=True, media_dimensions=True, day_view=True)))


if __name__ == "__main__":
    main()
