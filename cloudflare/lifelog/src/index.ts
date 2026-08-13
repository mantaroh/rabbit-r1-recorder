/**
 * R1 lifelog ingestion.
 *
 *   device ──PUT /v1/segments/{id}──▶ R2 (audio)  +  D1 (row)  +  Queue
 *                                                                  │
 *                                          Whisper ◀── consumer ───┘
 *                                             │
 *                                             ▼
 *                                        D1 transcript
 *
 * The upload returns as soon as the bytes are durable. Transcription is a
 * separate stage because it is slow, fails differently, and must be able to
 * retry without asking the device to send the audio again — the device may
 * have been on a train when it uploaded.
 */

import { handleMcp } from "./mcp";

interface Env {
  BUCKET: R2Bucket;
  DB: D1Database;
  TRANSCRIBE_QUEUE: Queue<TranscribeMessage>;
  AI: Ai;
  INGEST_TOKEN: string;
}

interface TranscribeMessage {
  segmentId: string;
  key: string;
}

/** Queue messages stay tiny — 128 KB cap — so they carry a key, never audio. */
const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;

const CODEC_EXTENSIONS: Record<string, string> = {
  wav: "wav",
  opus: "opus",
  ogg: "ogg",
  m4a: "m4a",
  mp4: "m4a",
  mp3: "mp3",
  flac: "flac",
  webm: "webm",
};

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (url.pathname === "/health") {
      return json({ ok: true });
    }

    const denied = authorise(request, env);
    if (denied) return denied;

    if (request.method === "PUT" && url.pathname.startsWith("/v1/segments/")) {
      return putSegment(request, env, url);
    }
    if (request.method === "GET" && url.pathname.startsWith("/v1/segments/")) {
      return getSegment(env, url);
    }
    if (request.method === "GET" && url.pathname === "/v1/context") {
      return getContext(env, url);
    }
    if (request.method === "GET" && url.pathname === "/v1/search") {
      return search(env, url);
    }
    if (request.method === "GET" && url.pathname === "/v1/stats") {
      return stats(env);
    }
    if (request.method === "GET" && url.pathname === "/v1/debug/whisper") {
      return debugWhisper(env, url);
    }
    if (request.method === "POST" && url.pathname === "/v1/admin/backfill") {
      return backfill(env, url);
    }

    // Same auth as everything else, so the agent uses the token it already has.
    if (url.pathname === "/mcp") {
      return handleMcp(request, {
        context: (at, beforeSec) => contextData(env, at, beforeSec),
        search: (q, limit) => searchData(env, q, limit),
        stats: () => statsData(env),
      });
    }

    return json({ error: "not found" }, 404);
  },

  async queue(batch: MessageBatch<TranscribeMessage>, env: Env): Promise<void> {
    for (const message of batch.messages) {
      try {
        await transcribe(message.body, env);
        message.ack();
      } catch (error) {
        // Let the queue redeliver: a Whisper hiccup should not cost the audio,
        // which is already durable in R2.
        console.error("transcribe failed", message.body.segmentId, error);
        await env.DB.prepare(
          `UPDATE segments SET error = ?1 WHERE segment_id = ?2`,
        )
          .bind(String(error).slice(0, 500), message.body.segmentId)
          .run();
        message.retry();
      }
    }
  },
} satisfies ExportedHandler<Env>;

// ------------------------------------------------------------------ auth ---

/**
 * A shared bearer token. Cloudflare Access sits in front of this Worker in
 * production and is the real perimeter, but Access cannot be exercised in
 * local dev, and a second factor at the origin means a misconfigured Access
 * policy does not immediately expose the ingest path.
 */
function authorise(request: Request, env: Env): Response | null {
  const expected = env.INGEST_TOKEN;
  if (!expected) return json({ error: "server missing INGEST_TOKEN" }, 500);

  const header = request.headers.get("authorization") ?? "";
  const presented = header.startsWith("Bearer ") ? header.slice(7) : "";
  if (!presented || !timingSafeEqual(presented, expected)) {
    return json({ error: "unauthorized" }, 401);
  }
  return null;
}

function timingSafeEqual(a: string, b: string): boolean {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

// ---------------------------------------------------------------- ingest ---

async function putSegment(request: Request, env: Env, url: URL): Promise<Response> {
  const segmentId = url.pathname.slice("/v1/segments/".length);
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(segmentId)) {
    return json({ error: "segment id must be 8-64 chars of [A-Za-z0-9_-]" }, 400);
  }

  const startedAt = url.searchParams.get("started_at");
  if (!startedAt || Number.isNaN(Date.parse(startedAt))) {
    return json({ error: "started_at must be an ISO 8601 timestamp" }, 400);
  }

  const deviceId = url.searchParams.get("device_id") ?? "unknown";
  const kind = url.searchParams.get("kind") === "query" ? "query" : "lifelog";
  const codec = (url.searchParams.get("codec") ?? "wav").toLowerCase();
  const extension = CODEC_EXTENSIONS[codec] ?? "bin";
  const sampleRate = intParam(url, "sample_rate");
  const endedAt = url.searchParams.get("ended_at");

  // Re-uploading a segment we already transcribed would bill another Whisper
  // run and duplicate the text; the device retries freely, so check first.
  const existing = await env.DB.prepare(
    `SELECT status, r2_key FROM segments WHERE segment_id = ?1`,
  )
    .bind(segmentId)
    .first<{ status: string; r2_key: string }>();

  if (existing && existing.status !== "failed") {
    return json({
      segment_id: segmentId,
      key: existing.r2_key,
      status: existing.status,
      duplicate: true,
    });
  }

  if (!request.body) return json({ error: "empty body" }, 400);
  const declared = Number(request.headers.get("content-length") ?? "0");
  if (declared > MAX_UPLOAD_BYTES) return json({ error: "too large" }, 413);

  const day = startedAt.slice(0, 10).replace(/-/g, "/");
  const key = `audio/${day}/${segmentId}.${extension}`;

  const object = await env.BUCKET.put(key, request.body, {
    httpMetadata: {
      contentType: request.headers.get("content-type") ?? "application/octet-stream",
    },
    customMetadata: { segmentId, deviceId, kind, startedAt },
  });

  const durationMs =
    endedAt && !Number.isNaN(Date.parse(endedAt))
      ? Date.parse(endedAt) - Date.parse(startedAt)
      : null;

  await env.DB.prepare(
    `INSERT INTO segments
       (segment_id, device_id, kind, r2_key, codec, sample_rate,
        started_at, started_epoch, ended_at, duration_ms, bytes,
        received_at, status)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,'pending')
     ON CONFLICT(segment_id) DO UPDATE SET
       r2_key = excluded.r2_key,
       bytes = excluded.bytes,
       received_at = excluded.received_at,
       status = 'pending',
       error = NULL`,
  )
    .bind(
      segmentId,
      deviceId,
      kind,
      key,
      codec,
      sampleRate,
      startedAt,
      // Ordering and windowing run off this, never off the string: the device
      // sends a +09:00 offset and any bound computed here is UTC, and SQLite
      // would compare the two lexicographically.
      Math.floor(Date.parse(startedAt) / 1000),
      endedAt,
      durationMs,
      object?.size ?? null,
      new Date().toISOString(),
    )
    .run();

  // `sync=1` jumps the queue. A question about "what we were just saying"
  // needs the last couple of minutes transcribed *now*; going through the
  // queue puts them behind whatever backlog is already in flight, which on a
  // Wi-Fi-only upload policy can be hours of audio.
  if (url.searchParams.get("sync") === "1") {
    try {
      const bytes = new Uint8Array(
        await (await env.BUCKET.get(key))!.arrayBuffer(),
      );
      const t = await runWhisper(bytes, env);
      await storeTranscript(env, segmentId, t);

      return json({
        segment_id: segmentId,
        key,
        bytes: object?.size ?? null,
        status: "transcribed",
        transcript: t.text,
        speech_ratio: t.speechRatio,
        duplicate: false,
      });
    } catch (error) {
      // The bytes are already durable, so fall back to the queue rather than
      // failing the upload — the caller loses immediacy, not the recording.
      console.error("sync transcribe failed, queueing", segmentId, error);
      await env.TRANSCRIBE_QUEUE.send({ segmentId, key });
      return json(
        {
          segment_id: segmentId,
          key,
          status: "pending",
          sync_error: String(error).slice(0, 200),
          duplicate: false,
        },
        202,
      );
    }
  }

  await env.TRANSCRIBE_QUEUE.send({ segmentId, key });

  return json(
    {
      segment_id: segmentId,
      key,
      bytes: object?.size ?? null,
      status: "pending",
      duplicate: false,
    },
    202,
  );
}

/** Lets a client poll rather than guess when a deferred segment is ready. */
async function getSegment(env: Env, url: URL): Promise<Response> {
  const segmentId = url.pathname.slice("/v1/segments/".length);
  const row = await env.DB.prepare(
    `SELECT segment_id, status, started_at, ended_at, bytes,
            transcript, transcribed_at, error
       FROM segments WHERE segment_id = ?1`,
  )
    .bind(segmentId)
    .first();
  return row ? json(row) : json({ error: "not found" }, 404);
}

// ----------------------------------------------------------- transcribe ---

async function transcribe(message: TranscribeMessage, env: Env): Promise<void> {
  const object = await env.BUCKET.get(message.key);
  if (!object) {
    // The audio is gone; retrying forever would just fill the DLQ.
    await env.DB.prepare(
      `UPDATE segments SET status = 'failed', error = 'r2 object missing'
       WHERE segment_id = ?1`,
    )
      .bind(message.segmentId)
      .run();
    return;
  }

  const bytes = new Uint8Array(await object.arrayBuffer());
  const t = await runWhisper(bytes, env);
  await storeTranscript(env, message.segmentId, t);
}

async function storeTranscript(env: Env, segmentId: string, t: Transcription) {
  await env.DB.prepare(
    `UPDATE segments
        SET transcript = ?1, status = 'transcribed', transcribed_at = ?2,
            speech_ratio = ?3, language = ?4, language_prob = ?5,
            word_count = ?6, error = NULL
      WHERE segment_id = ?7`,
  )
    .bind(
      t.text,
      new Date().toISOString(),
      t.speechRatio,
      t.language,
      t.languageProb,
      t.wordCount,
      segmentId,
    )
    .run();
}

interface Transcription {
  text: string;
  /** Fraction of the segment the model actually placed words in. */
  speechRatio: number | null;
  language: string | null;
  languageProb: number | null;
  wordCount: number | null;
}

/**
 * The two Whisper models on Workers AI take different input shapes — the turbo
 * model wants base64, the original wants a byte array — and the docs do not
 * spell either out. Try the cheaper/faster one, fall back rather than lose the
 * segment over an input-format mismatch. Only turbo returns the per-segment
 * timings the quality signals are derived from.
 */
async function runWhisper(bytes: Uint8Array, env: Env): Promise<Transcription> {
  try {
    const result: any = await env.AI.run("@cf/openai/whisper-large-v3-turbo" as any, {
      audio: base64(bytes),
    } as any);
    const text = result?.text;
    if (typeof text !== "string") {
      throw new Error("turbo returned no text: " + JSON.stringify(result).slice(0, 200));
    }

    const info = result?.transcription_info ?? {};
    const duration = Number(info?.duration) || null;
    const spans: any[] = Array.isArray(result?.segments) ? result.segments : [];
    const spoken = spans.reduce((total, s) => {
      const start = Number(s?.start), end = Number(s?.end);
      return total + (Number.isFinite(start) && Number.isFinite(end) && end > start ? end - start : 0);
    }, 0);

    return {
      text: text.trim(),
      speechRatio: duration ? Math.min(1, spoken / duration) : null,
      language: typeof info?.language === "string" ? info.language : null,
      languageProb: Number.isFinite(info?.language_probability)
        ? Number(info.language_probability)
        : null,
      wordCount: Number.isFinite(result?.word_count) ? Number(result.word_count) : null,
    };
  } catch (error) {
    console.warn("whisper turbo failed, falling back", error);
    const result: any = await env.AI.run("@cf/openai/whisper" as any, {
      audio: [...bytes],
    } as any);
    // The fallback reports no timings, so it carries no quality signal.
    return {
      text: String(result?.text ?? "").trim(),
      speechRatio: null,
      language: null,
      languageProb: null,
      wordCount: Number.isFinite(result?.word_count) ? Number(result.word_count) : null,
    };
  }
}

function base64(bytes: Uint8Array): string {
  let binary = "";
  const chunk = 0x8000; // btoa on the whole array blows the call stack
  for (let i = 0; i < bytes.length; i += chunk) {
    binary += String.fromCharCode(...bytes.subarray(i, i + chunk));
  }
  return btoa(binary);
}

// ----------------------------------------------------------- retrieval ---

/**
 * "What was being said just before this moment" — the lookup that lets the
 * agent resolve "さっきの話" without the device holding any transcript itself.
 */
async function getContext(env: Env, url: URL): Promise<Response> {
  const at = url.searchParams.get("at") ?? new Date().toISOString();
  if (Number.isNaN(Date.parse(at))) return json({ error: "bad at" }, 400);
  return json(await contextData(env, at, intParam(url, "before_sec") ?? 120));
}

/**
 * Segments below this much measured speech are Whisper hallucinating over
 * silence. Measured on a night of real recording:
 *
 *   asleep        ratio 0.012 – 0.060, language en at 0.38 – 0.53
 *   conversation  ratio 0.589 – 0.707, language ja at 0.99 – 1.00
 *
 * An order of magnitude apart, so 0.15 sits in open space rather than on a
 * boundary. Rows are kept and filtered at read time, which lets the threshold
 * be revisited without paying to transcribe again.
 *
 * Rows written before the signal existed have `speech_ratio IS NULL` and are
 * let through: dropping them would silently erase history.
 */
const MIN_SPEECH_RATIO = 0.15;

export async function contextData(env: Env, at: string, beforeSec: number, minRatio?: number) {
  const atEpoch = Math.floor(Date.parse(at) / 1000);
  const fromEpoch = atEpoch - beforeSec;
  const from = new Date(fromEpoch * 1000).toISOString();
  const floor = minRatio ?? MIN_SPEECH_RATIO;

  const rows = await env.DB.prepare(
    `SELECT segment_id, started_at, ended_at, transcript, speech_ratio
       FROM segments
      WHERE status = 'transcribed'
        AND transcript IS NOT NULL AND transcript <> ''
        AND started_epoch >= ?1 AND started_epoch <= ?2
        AND (speech_ratio IS NULL OR speech_ratio >= ?3)
      ORDER BY started_epoch ASC
      LIMIT 200`,
  )
    .bind(fromEpoch, atEpoch, floor)
    .all();

  const segments = rows.results ?? [];
  return {
    at,
    before_sec: beforeSec,
    from,
    min_speech_ratio: floor,
    count: segments.length,
    // A single blob is what an agent actually wants to read.
    text: segments.map((r: any) => r.transcript).join(" ").trim(),
    segments,
  };
}

async function search(env: Env, url: URL): Promise<Response> {
  const q = url.searchParams.get("q");
  if (!q) return json({ error: "q required" }, 400);
  return json(await searchData(env, q, intParam(url, "limit") ?? 20));
}

export async function searchData(env: Env, q: string, rawLimit: number) {
  const limit = Math.min(rawLimit, 100);
  const term = q.trim();

  // The trigram tokenizer indexes 3-character windows, so it cannot match a
  // query shorter than that — and in Japanese one- and two-character terms
  // (敵, 駅, 予定) are ordinary things to search for. Measured: 負け犬 hits,
  // トモ does not. Scan for those instead of returning a confident zero.
  const useFts = [...term].length >= 3;

  const rows = useFts
    ? await env.DB.prepare(
        `SELECT f.segment_id, f.started_at, s.transcript, s.speech_ratio
           FROM segments_fts f
           JOIN segments s ON s.segment_id = f.segment_id
          WHERE segments_fts MATCH ?1
            AND (s.speech_ratio IS NULL OR s.speech_ratio >= ?3)
          ORDER BY s.started_epoch DESC
          LIMIT ?2`,
      )
        .bind(`"${term.replace(/"/g, '""')}"`, limit, MIN_SPEECH_RATIO)
        .all()
    : await env.DB.prepare(
        `SELECT segment_id, started_at, transcript, speech_ratio
           FROM segments
          WHERE transcript LIKE ?1 ESCAPE '\\'
            AND (speech_ratio IS NULL OR speech_ratio >= ?3)
          ORDER BY started_epoch DESC
          LIMIT ?2`,
      )
        .bind(`%${term.replace(/[\\%_]/g, (c) => "\\" + c)}%`, limit, MIN_SPEECH_RATIO)
        .all();

  return {
    q: term,
    mode: useFts ? "fts" : "scan",
    count: rows.results?.length ?? 0,
    results: rows.results ?? [],
  };
}

/**
 * Returns Whisper's raw reply for an already-stored segment.
 *
 * Only `text` is kept in the database, so it is not otherwise visible whether
 * the model reports a no-speech signal. If it does, silence can be rejected on
 * the server without any change on the device.
 */
async function debugWhisper(env: Env, url: URL): Promise<Response> {
  const segmentId = url.searchParams.get("segment_id");
  if (!segmentId) return json({ error: "segment_id required" }, 400);

  const row = await env.DB.prepare(
    `SELECT r2_key FROM segments WHERE segment_id = ?1`,
  )
    .bind(segmentId)
    .first<{ r2_key: string }>();
  if (!row) return json({ error: "unknown segment" }, 404);

  const object = await env.BUCKET.get(row.r2_key);
  if (!object) return json({ error: "audio missing" }, 404);
  const bytes = new Uint8Array(await object.arrayBuffer());

  // Report the derived signals, not raw JSON: the raw reply runs to kilobytes
  // of per-word timings and truncating it makes the output unparseable.
  const t = await runWhisper(bytes, env);

  // Optionally persist, so a backfill can reuse this path.
  if (url.searchParams.get("store") === "1") {
    await storeTranscript(env, segmentId, t);
  }

  return json({
    segment_id: segmentId,
    key: row.r2_key,
    text: t.text.slice(0, 300),
    speech_ratio: t.speechRatio,
    language: t.language,
    language_prob: t.languageProb,
    word_count: t.wordCount,
    stored: url.searchParams.get("store") === "1",
  });
}

/**
 * Re-transcribes rows that predate the quality signals, in small batches.
 *
 * Kept as an explicit admin call rather than something automatic: it re-runs
 * Whisper, so it costs money, and the caller should decide when and how much.
 */
async function backfill(env: Env, url: URL): Promise<Response> {
  const limit = Math.min(intParam(url, "limit") ?? 20, 50);

  const rows = await env.DB.prepare(
    `SELECT segment_id, r2_key FROM segments
      WHERE speech_ratio IS NULL AND status = 'transcribed'
      ORDER BY started_epoch ASC
      LIMIT ?1`,
  )
    .bind(limit)
    .all<{ segment_id: string; r2_key: string }>();

  const todo = rows.results ?? [];
  let done = 0;
  let failed = 0;

  for (const row of todo) {
    try {
      const object = await env.BUCKET.get(row.r2_key);
      if (!object) {
        // Audio gone; mark it so the backfill does not retry it forever.
        await env.DB.prepare(
          `UPDATE segments SET speech_ratio = -1 WHERE segment_id = ?1`,
        )
          .bind(row.segment_id)
          .run();
        failed += 1;
        continue;
      }
      const bytes = new Uint8Array(await object.arrayBuffer());
      await storeTranscript(env, row.segment_id, await runWhisper(bytes, env));
      done += 1;
    } catch (error) {
      console.error("backfill failed", row.segment_id, error);
      failed += 1;
    }
  }

  const remaining = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM segments WHERE speech_ratio IS NULL AND status = 'transcribed'`,
  ).first<{ n: number }>();

  return json({ requested: limit, done, failed, remaining: remaining?.n ?? 0 });
}

async function stats(env: Env): Promise<Response> {
  return json(await statsData(env));
}

export async function statsData(env: Env) {
  const row = await env.DB.prepare(
    `SELECT
       COUNT(*) AS total,
       SUM(status = 'pending')     AS pending,
       SUM(status = 'transcribed') AS transcribed,
       SUM(status = 'failed')      AS failed,
       SUM(bytes)                  AS bytes,
       MIN(started_at)             AS first_at,
       MAX(started_at)             AS last_at
     FROM segments`,
  ).first();
  return row ?? {};
}

// ----------------------------------------------------------------- util ---

function intParam(url: URL, name: string): number | null {
  const raw = url.searchParams.get(name);
  if (raw === null) return null;
  const value = Number(raw);
  return Number.isFinite(value) ? Math.trunc(value) : null;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
