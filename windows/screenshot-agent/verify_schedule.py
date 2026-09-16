"""37-second, real-screen local test of the configured 30-second scheduler."""
import json
from pathlib import Path
import tempfile
import time
from unittest.mock import patch
import agent
from capture import enable_dpi, take
from config import defaults


with tempfile.TemporaryDirectory(prefix="lifelog-schedule-") as directory:
    enable_dpi()
    service = agent.Agent(Path(directory), {**defaults(), "interval_seconds": 30, "endpoint": "http://127.0.0.1:8799", "token": "desktop-test-ingest"})
    calls = []

    def observed(*args):
        count = take(*args)
        calls.append((time.monotonic(), count))
        return count

    with patch.object(agent, "take", side_effect=observed):
        service.start()
        try:
            time.sleep(32)
            service.paused.set()
            paused_count = len(calls)
            time.sleep(2)
            assert len(calls) == paused_count
            service.paused.clear()
            service.wake.set()
            time.sleep(3)
        finally:
            service.stop.set()
            service.wake.set()
            for thread in service.threads:
                thread.join(timeout=25)
    assert len(calls) == 3, calls
    assert 29 <= calls[1][0] - calls[0][0] <= 32, calls
    assert all(count > 0 for _, count in calls), calls
    assert service.store.stats()["pending"] == 0, service.send_status
    print(json.dumps(dict(cycles=len(calls), screens_per_cycle=[n for _, n in calls],
                          interval_seconds=round(calls[1][0] - calls[0][0], 2), pause_resume=True, outbox_empty=True)))
