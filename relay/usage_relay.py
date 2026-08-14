#!/usr/bin/env python3
"""
Reports how much of Codex and Claude Code has been used, over HTTP.

Runs on the machine those tools actually run on, because that is the only
place the numbers exist. Neither is reachable from the Hermes gateway: it
knows its own token accounting and nothing about either subscription.

The two are not symmetrical, and the output says so rather than papering over
it:

  Codex writes its rate limits into every session log — used_percent, the
  window they apply to, when they reset, the plan. That is the real figure,
  straight from the provider.

  Claude Code writes token counts and no limits at all. There is no percentage
  to report, so this reports tokens over the same windows and leaves the
  percentage absent. Inventing one from an assumed quota would be a number
  that looks authoritative and is guessed.

Read-only. It opens files the tools have already written and never touches
credentials.
"""

from __future__ import annotations

import glob
import json
import os
import time

CLAUDE_PROJECTS = os.path.expanduser("~/.claude/projects")
CODEX_SESSIONS = os.path.expanduser("~/.codex/sessions")

# Windows the answer is reported over, in hours.
WINDOWS = (5, 24, 168)

# How long a computed answer is reused. Walking a few hundred session logs
# costs a second or so; a standby screen asking every couple of minutes does
# not need it recomputed each time.
CACHE_SECONDS = 60


def _recent_files(root: str, pattern: str, max_age_s: float) -> list[str]:
    """Session logs touched within the window, newest first."""
    cutoff = time.time() - max_age_s
    found = []
    for path in glob.iglob(os.path.join(root, pattern), recursive=True):
        try:
            if os.path.getmtime(path) >= cutoff:
                found.append(path)
        except OSError:
            continue
    found.sort(key=os.path.getmtime, reverse=True)
    return found


def codex_limits() -> dict:
    """
    The most recent rate_limits block Codex recorded.

    Newest first and stop at the first hit: these are a snapshot of the account
    at the time of the call, so the latest one is the only one that means
    anything. An older session's numbers are not a smaller usage, they are a
    stale reading.
    """
    for path in _recent_files(CODEX_SESSIONS, "**/*.jsonl", 14 * 86400):
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                # Read backwards-ish: the limits are attached to responses, so
                # the useful ones are near the end.
                lines = handle.readlines()
        except OSError:
            continue

        for line in reversed(lines):
            if '"rate_limits"' not in line:
                continue
            try:
                found = _find_key(json.loads(line), "rate_limits")
            except json.JSONDecodeError:
                continue
            if not isinstance(found, dict):
                continue

            primary = found.get("primary") or {}
            secondary = found.get("secondary") or {}
            return {
                "available": True,
                "plan": found.get("plan_type"),
                "primary": _window(primary),
                "secondary": _window(secondary) if secondary else None,
                "measured_at": int(os.path.getmtime(path)),
                "source": "codex session log",
            }

    return {"available": False, "reason": "no rate_limits found in recent sessions"}


def _window(block: dict) -> dict | None:
    if not block:
        return None
    return {
        "used_percent": block.get("used_percent"),
        "window_minutes": block.get("window_minutes"),
        "resets_at": block.get("resets_at"),
    }


def _find_key(node, key: str):
    """Depth-first search for a key, because the nesting is not documented."""
    if isinstance(node, dict):
        if key in node:
            return node[key]
        for value in node.values():
            found = _find_key(value, key)
            if found is not None:
                return found
    elif isinstance(node, list):
        for value in node:
            found = _find_key(value, key)
            if found is not None:
                return found
    return None


def claude_tokens() -> dict:
    """
    Tokens Claude Code has spent, per window.

    No percentage: Claude Code stores no limits anywhere on this machine, and a
    percentage against an assumed quota would be a guess wearing the costume of
    a measurement.
    """
    now = time.time()
    buckets = {hours: {"input": 0, "output": 0, "cache_read": 0, "messages": 0}
               for hours in WINDOWS}
    longest = max(WINDOWS) * 3600

    for path in _recent_files(CLAUDE_PROJECTS, "**/*.jsonl", longest):
        try:
            with open(path, "r", encoding="utf-8", errors="replace") as handle:
                for line in handle:
                    if '"usage"' not in line:
                        continue
                    try:
                        entry = json.loads(line)
                    except json.JSONDecodeError:
                        continue

                    stamp = _timestamp(entry.get("timestamp"))
                    if stamp is None:
                        continue
                    usage = _find_key(entry, "usage")
                    if not isinstance(usage, dict):
                        continue

                    age_h = (now - stamp) / 3600
                    for hours in WINDOWS:
                        if age_h <= hours:
                            bucket = buckets[hours]
                            bucket["input"] += usage.get("input_tokens", 0) or 0
                            bucket["output"] += usage.get("output_tokens", 0) or 0
                            bucket["cache_read"] += (
                                usage.get("cache_read_input_tokens", 0) or 0
                            )
                            bucket["messages"] += 1
        except OSError:
            continue

    return {
        "available": True,
        "used_percent": None,
        "note": "Claude Code stores no rate limits locally; tokens only",
        "plan": claude_plan(),
        "windows": {f"{hours}h": buckets[hours] for hours in WINDOWS},
        "source": "claude session logs",
    }


def claude_plan() -> dict:
    """
    Which subscription this is, from the credentials Claude Code already wrote.

    Worth reporting and not worth turning into a percentage. The tier names a
    plan whose limits are published as approximate message counts that vary
    with model and context length, so dividing tokens by them would produce a
    figure with tens of percent of error wearing the costume of a measurement.
    The tier itself is a fact; the denominator is not.
    """
    path = os.path.expanduser("~/.claude/.credentials.json")
    try:
        with open(path, "r", encoding="utf-8") as handle:
            oauth = json.load(handle).get("claudeAiOauth") or {}
    except (OSError, json.JSONDecodeError):
        return {}

    return {
        "subscription": oauth.get("subscriptionType"),
        "rate_limit_tier": oauth.get("rateLimitTier"),
    }


def _timestamp(value) -> float | None:
    if isinstance(value, (int, float)):
        return float(value)
    if isinstance(value, str):
        try:
            from datetime import datetime

            return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
        except ValueError:
            return None
    return None


_cache: dict = {"at": 0.0, "body": None}


def snapshot() -> dict:
    if time.time() - _cache["at"] < CACHE_SECONDS and _cache["body"]:
        return _cache["body"]

    body = {
        "generated_at": int(time.time()),
        "codex": codex_limits(),
        "claude_code": claude_tokens(),
    }
    _cache["at"] = time.time()
    _cache["body"] = body
    return body


def push(url: str, token: str, access_id: str, access_secret: str) -> int:
    """
    Sends the reading to the lifelog Worker.

    Pushing rather than serving. The tunnel out of this machine is remotely
    managed, so publishing a hostname means editing Cloudflare's dashboard and
    adding an Access policy for it; posting to a Worker the device already
    authenticates against needs neither, and opens no inbound path to a
    personal machine. It also means the display keeps showing the last known
    figures while this machine is asleep, which polling would not.
    """
    import urllib.request

    body = json.dumps(snapshot(), ensure_ascii=False).encode("utf-8")
    request = urllib.request.Request(url, data=body, method="PUT")
    request.add_header("Content-Type", "application/json")
    request.add_header("Authorization", f"Bearer {token}")
    if access_id:
        request.add_header("CF-Access-Client-Id", access_id)
        request.add_header("CF-Access-Client-Secret", access_secret)

    with urllib.request.urlopen(request, timeout=30) as response:
        return response.status


def main() -> None:
    url = os.environ.get("USAGE_PUSH_URL", "")
    if not url:
        # No destination configured: print the reading and exit. This is how
        # the numbers get checked without a round trip through Cloudflare.
        print(json.dumps(snapshot(), ensure_ascii=False, indent=2))
        return

    status = push(
        url,
        os.environ.get("USAGE_PUSH_TOKEN", ""),
        os.environ.get("USAGE_PUSH_ACCESS_ID", ""),
        os.environ.get("USAGE_PUSH_ACCESS_SECRET", ""),
    )
    print(f"pushed: {status}")


if __name__ == "__main__":
    main()
