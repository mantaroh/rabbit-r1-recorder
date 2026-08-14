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
  /** Spoken language to assume. "auto" lets Whisper guess. See LANGUAGE below. */
  TRANSCRIBE_LANGUAGE?: string;
}

interface TranscribeMessage {
  segmentId: string;
  key: string;
  /** Carried per message so a queued segment keeps the language it arrived with. */
  language?: string;
}

/** Queue messages stay tiny — 128 KB cap — so they carry a key, never audio. */
const MAX_UPLOAD_BYTES = 100 * 1024 * 1024;

/**
 * There is no retention policy, and adding one needs a decision, not a commit.
 *
 * The audio is the artefact this system exists to keep. Transcription is a
 * side effect of today's models: the recording is what a better model, years
 * from now, gets to re-read. Deleting it to save storage trades the asset for
 * the receipt.
 *
 * A previous version expired both after a day — an R2 lifecycle rule on
 * `audio/` and an hourly sweep of this table. Both are gone. If a budget
 * eventually forces a policy, downsample or archive to a colder class; do not
 * delete, and do not let the row outlive or predecease the object it names.
 */

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
    if (request.method === "GET" && url.pathname === "/v1/admin/reconcile") {
      return reconcile(env);
    }
    if (request.method === "POST" && url.pathname === "/v1/admin/reindex") {
      return reindex(env, url);
    }
    if (request.method === "POST" && url.pathname === "/v1/admin/rejudge") {
      return rejudge(env, url);
    }
    if (request.method === "POST" && url.pathname === "/v1/admin/retranscribe") {
      return retranscribe(env, url);
    }
    if (request.method === "POST" && url.pathname === "/v1/admin/reflag") {
      return reflag(env, url);
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

  const envelopeRaw = url.searchParams.get("rms");
  const envelope = decodeEnvelope(envelopeRaw);
  const ratio = envelope ? voicedRatio(envelope) : null;

  // A question is always transcribed. It is short, it was asked deliberately,
  // and somebody is waiting for the answer — the wrong place to be thrifty.
  const silent = kind !== "query" && ratio !== null && ratio < MIN_VOICED_RATIO;

  await env.DB.prepare(
    `INSERT INTO segments
       (segment_id, device_id, kind, r2_key, codec, sample_rate,
        started_at, started_epoch, ended_at, duration_ms, bytes,
        received_at, status, rms_envelope, voiced_ratio)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15)
     ON CONFLICT(segment_id) DO UPDATE SET
       r2_key = excluded.r2_key,
       bytes = excluded.bytes,
       received_at = excluded.received_at,
       status = excluded.status,
       rms_envelope = excluded.rms_envelope,
       voiced_ratio = excluded.voiced_ratio,
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
      // 'silent' is a terminal state, not a failure: the audio is archived and
      // the envelope explains the decision, so a later threshold can revisit
      // it without the recording having been touched.
      silent ? "silent" : "pending",
      envelopeRaw,
      ratio,
    )
    .run();

  if (silent) {
    return json(
      {
        segment_id: segmentId,
        key,
        bytes: object?.size ?? null,
        status: "silent",
        voiced_ratio: ratio,
        duplicate: false,
      },
      202,
    );
  }

  // `sync=1` jumps the queue. A question about "what we were just saying"
  // needs the last couple of minutes transcribed *now*; going through the
  // queue puts them behind whatever backlog is already in flight, which on a
  // Wi-Fi-only upload policy can be hours of audio.
  // The device carries its own language setting; fall back to the Worker's.
  const language = languageFor(env, url.searchParams.get("language"));

  if (url.searchParams.get("sync") === "1") {
    try {
      const bytes = new Uint8Array(
        await (await env.BUCKET.get(key))!.arrayBuffer(),
      );
      const t = await runWhisper(bytes, env, language);
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
      await env.TRANSCRIBE_QUEUE.send({ segmentId, key, language: language ?? "auto" });
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

  await env.TRANSCRIBE_QUEUE.send({ segmentId, key, language: language ?? "auto" });

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
  const t = await runWhisper(bytes, env, languageFor(env, message.language));
  await storeTranscript(env, message.segmentId, t);
}

async function storeTranscript(env: Env, segmentId: string, t: Transcription) {
  await env.DB.prepare(
    `UPDATE segments
        SET transcript = ?1, status = 'transcribed', transcribed_at = ?2,
            speech_ratio = ?3, language = ?4, language_prob = ?5,
            word_count = ?6, stock_phrase = ?7, error = NULL
      WHERE segment_id = ?8`,
  )
    .bind(
      t.text,
      new Date().toISOString(),
      t.speechRatio,
      t.language,
      t.languageProb,
      t.wordCount,
      isStockPhrase(t.text) ? 1 : 0,
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
// --------------------------------------------------------- stock phrases ---

/**
 * Whisper's fallbacks when it has audio but nothing to say.
 *
 * These are artefacts of a training set full of video: sign-offs, subtitle
 * credits, thanks. Measured over 1900 segments, the top of the list is
 * "Thank you. Thank you." 272 times and "ご視聴ありがとうございました" 160.
 *
 * The speech_ratio filter does not reach them. "All right. All right." scores
 * 0.23 and "お疲れ様でした" 0.54, both clear of the 0.15 threshold, because
 * Whisper genuinely distributed words across the audio — they are just the
 * wrong words, produced from clattering plates and half-heard speech.
 */
const STOCK_PHRASES = [
  "thank you",
  "thanks for watching",
  "all right",
  "okay",
  "bye",
  "obrigado",
  "vamos lá",
  "amém",
  "gracias",
  "продолжение следует",
  "ご視聴ありがとうございました",
  "ありがとうございました",
  "お疲れ様でした",
  "おつかれさまでした",
  "次回もお楽しみに",
  "チャンネル登録お願いします",
  "エンディング",
];

/**
 * True when the transcript is *entirely* one stock phrase, repeated or not.
 *
 * Deliberately not a substring test. "ありがとうございました" and
 * "お疲れ様でした" are ordinary things for a person to say, and a rule that
 * struck them from the middle of a real sentence would quietly delete the
 * evidence it was meant to protect. What marks the hallucination is that the
 * phrase is the *whole* of a sixty-second minute.
 */
function isStockPhrase(text: string): boolean {
  const normalised = text
    .toLowerCase()
    .replace(/[.,!?。、！？…]/g, " ")
    .replace(/\s+/g, " ")
    .trim();
  if (!normalised) return false;

  for (const phrase of STOCK_PHRASES) {
    // Strip the phrase wherever it occurs; if nothing else was said, the
    // segment was Whisper repeating itself into the void.
    const stripped = normalised.split(phrase).join(" ").replace(/\s+/g, " ").trim();
    if (stripped === "") return true;
  }
  return false;
}

// -------------------------------------------------------------------- vad ---

/**
 * Whether a segment is worth handing to Whisper.
 *
 * Sending everything is what makes Whisper hallucinate: given a silent minute
 * it returns whatever its training data suggests — "Thank you.",
 * "ご視聴ありがとうございました" — with no signal that it invented them. Its
 * own `vad_filter` stops most of that, but only after the call has been made
 * and billed, and roughly 89 % of a day is not speech.
 *
 * The measurement happens on the device because that is the only place the
 * PCM exists without decoding Opus, and Opus here is effectively CBR — a
 * silent minute and a talkative one differ by 8 % in size, with the ranges
 * overlapping, so nothing useful can be read off the packets. The device
 * sends a raw envelope rather than a verdict; the policy below is the
 * Worker's, and can be re-run over stored envelopes when it changes.
 */

/** One byte per second, RMS >> 4, base64. */
function decodeEnvelope(raw: string | null): number[] | null {
  if (!raw) return null;
  try {
    const binary = atob(raw.replace(/-/g, "+").replace(/_/g, "/"));
    return Array.from(binary, (c) => c.charCodeAt(0));
  } catch {
    return null;
  }
}

/**
 * Measured ambient levels: quiet room 176, background activity 352, speech
 * 2440–3536. Divided by 16 that is 11, 22, and 152–221, so 32 (RMS 512) sits
 * above any room this device has been in and far below anything spoken.
 */
const VOICED_RMS = 32;

/**
 * Skip only when a segment is unambiguously empty. The asymmetry is
 * deliberate: a wrong skip loses a transcript of something real until someone
 * notices, while a wrong transcribe costs $0.0005. Two seconds of the minute
 * is enough to buy the call.
 */
const MIN_VOICED_RATIO = 0.033;

function voicedRatio(envelope: number[]): number {
  if (envelope.length === 0) return 1; // no evidence is not evidence of silence
  return envelope.filter((v) => v >= VOICED_RMS).length / envelope.length;
}

/**
 * Which language to tell Whisper it is listening to.
 *
 * Left to guess, it drifts: one drive produced `en`, `ja` and `ko` across
 * consecutive minutes of the same conversation, and the Korean one came back
 * as hangul transliterating Japanese. Road noise is enough to tip it. Naming
 * the language costs nothing and removes the whole failure mode.
 *
 * Order: the request wins (the device carries its own setting), then the
 * Worker's var, then Japanese. "auto" anywhere restores guessing.
 */
function languageFor(env: Env, requested?: string | null): string | null {
  const choice = requested || env.TRANSCRIBE_LANGUAGE || "ja";
  return choice === "auto" ? null : choice;
}

async function runWhisper(
  bytes: Uint8Array,
  env: Env,
  language: string | null,
): Promise<Transcription> {
  try {
    const result: any = await env.AI.run("@cf/openai/whisper-large-v3-turbo" as any, {
      audio: base64(bytes),
      ...(language ? { language } : {}),

      // The model ships its own VAD, off by default, and we were not asking
      // for it — the hallucinated "Thank you." over a silent 3 a.m. hour was
      // Whisper being handed silence and told to transcribe it. This drops
      // the silence before the decoder ever sees it.
      vad_filter: true,

      // Whisper feeds each window its own previous output as context, which
      // is how a stutter becomes a loop: "ん ん ん ん ん ん", the same
      // sentence three times, "Thank you. Thank you." Turning it off is the
      // documented remedy and costs a little cross-sentence coherence.
      condition_on_previous_text: false,

      // Anything quieter than this for two seconds is skipped rather than
      // guessed at.
      hallucination_silence_threshold: 2,
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
      ...(language ? { language } : {}),
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
        AND stock_phrase = 0
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
            AND s.stock_phrase = 0
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
            AND stock_phrase = 0
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
  const t = await runWhisper(bytes, env, languageFor(env, url.searchParams.get("language")));

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
  const lang = languageFor(env, url.searchParams.get("language"));
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
      await storeTranscript(env, row.segment_id, await runWhisper(bytes, env, lang));
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

/**
 * Compares what is in R2 against what the database knows about, read-only.
 *
 * The recording is the asset and the row is only the index to it, so the two
 * can drift in a way that costs nothing to store and everything to notice too
 * late: an orphaned object is audio that no query will ever return and no
 * transcription will ever run over. This says how much of that there is.
 */
async function reconcile(env: Env): Promise<Response> {
  const known = await env.DB.prepare(`SELECT segment_id FROM segments`).all<{
    segment_id: string;
  }>();
  const rows = new Set((known.results ?? []).map((r) => r.segment_id));

  let objects = 0;
  let bytes = 0;
  let orphanCount = 0;
  let orphanBytes = 0;
  const orphans: string[] = [];
  const seen = new Set<string>();

  let cursor: string | undefined;
  // 20 pages of 1000 is far more than this bucket holds; the cap only exists
  // so a runaway listing cannot burn the whole subrequest budget.
  for (let page = 0; page < 20; page += 1) {
    const listed = await env.BUCKET.list({ prefix: "audio/", cursor, limit: 1000 });
    for (const object of listed.objects) {
      objects += 1;
      bytes += object.size;
      const id = object.key.split("/").pop()!.replace(/\.[^.]+$/, "");
      seen.add(id);
      if (!rows.has(id)) {
        orphanCount += 1;
        orphanBytes += object.size;
        // Keys sort chronologically (audio/YYYY/MM/DD/…), so a bounded sample
        // is enough to see which days lost their index.
        if (orphans.length < 40) orphans.push(object.key);
      }
    }
    if (!listed.truncated) {
      cursor = undefined;
      break;
    }
    cursor = listed.cursor;
  }

  const missing = [...rows].filter((id) => !seen.has(id));

  return json({
    r2_objects: objects,
    r2_bytes: bytes,
    r2_hours: +(objects / 60).toFixed(1),
    d1_rows: rows.size,
    // Audio that survived without an index: recoverable, but invisible.
    orphan_objects: orphanCount,
    orphan_bytes: orphanBytes,
    orphan_sample: orphans,
    // Rows whose audio is gone: the recording was deleted, the text remains.
    rows_without_audio: missing.length,
    rows_without_audio_sample: missing.slice(0, 40),
    truncated: cursor !== undefined,
  });
}

/**
 * Rebuilds database rows for audio that has none.
 *
 * Everything a row needs is recoverable from the object itself: the device
 * names a segment for the instant it started, in local time, and that name is
 * the key. Which is the only reason the retention sweep was survivable —
 * had the audio gone instead of the index, nothing here would help.
 */
async function reindex(env: Env, url: URL): Promise<Response> {
  const limit = Math.min(intParam(url, "limit") ?? 200, 1000);
  const transcribe = url.searchParams.get("transcribe") !== "0";

  const known = await env.DB.prepare(`SELECT segment_id FROM segments`).all<{
    segment_id: string;
  }>();
  const rows = new Set((known.results ?? []).map((r) => r.segment_id));

  const todo: { key: string; size: number }[] = [];
  let cursor: string | undefined;
  for (let page = 0; page < 20 && todo.length < limit; page += 1) {
    const listed = await env.BUCKET.list({ prefix: "audio/", cursor, limit: 1000 });
    for (const object of listed.objects) {
      const id = object.key.split("/").pop()!.replace(/\.[^.]+$/, "");
      if (!rows.has(id) && todo.length < limit) {
        todo.push({ key: object.key, size: object.size });
      }
    }
    if (!listed.truncated) break;
    cursor = listed.cursor;
  }

  let restored = 0;
  let skipped = 0;
  for (const item of todo) {
    const id = item.key.split("/").pop()!.replace(/\.[^.]+$/, "");
    const startedAt = startedAtFromId(id);
    if (!startedAt) {
      skipped += 1;
      continue;
    }
    const extension = item.key.split(".").pop() ?? "wav";
    // WAV is a fixed bit rate, so the duration is arithmetic on the size; the
    // 44-byte header is the only thing that is not sample data.
    const durationMs =
      extension === "wav"
        ? Math.round(((item.size - 44) / (16000 * 2)) * 1000)
        : null;

    await env.DB.prepare(
      `INSERT INTO segments
         (segment_id, device_id, kind, r2_key, codec, sample_rate,
          started_at, started_epoch, ended_at, duration_ms, bytes,
          received_at, status)
       VALUES (?1,'r1','lifelog',?2,?3,16000,?4,?5,NULL,?6,?7,?8,'pending')
       ON CONFLICT(segment_id) DO NOTHING`,
    )
      .bind(
        id,
        item.key,
        extension,
        startedAt.iso,
        startedAt.epoch,
        durationMs,
        item.size,
        new Date().toISOString(),
      )
      .run();

    if (transcribe) {
      await env.TRANSCRIBE_QUEUE.send({
        segmentId: id,
        key: item.key,
        language: url.searchParams.get("language") ?? undefined,
      });
    }
    restored += 1;
  }

  return json({ candidates: todo.length, restored, skipped, queued: transcribe });
}

/** `seg_20260812_144858` → the instant the device started that segment, JST. */
function startedAtFromId(id: string): { iso: string; epoch: number } | null {
  const m = /^[a-z]+_(\d{4})(\d{2})(\d{2})_(\d{2})(\d{2})(\d{2})$/.exec(id);
  if (!m) return null;
  const [, y, mo, d, h, mi, s] = m;
  // The device stamps names in Asia/Tokyo, which is a fixed +09:00 — no DST to
  // get wrong.
  const iso = `${y}-${mo}-${d}T${h}:${mi}:${s}.000+09:00`;
  const epoch = Math.floor(Date.parse(iso) / 1000);
  return Number.isFinite(epoch) ? { iso, epoch } : null;
}

/**
 * Re-apply the speech threshold to stored envelopes.
 *
 * The threshold above is a guess checked against one device in a handful of
 * rooms. `?dry=1` reports what a different one would have decided; without it
 * the newly-voiced segments are queued for transcription. Either way no audio
 * is read, which is the whole reason the envelope is kept.
 */
async function rejudge(env: Env, url: URL): Promise<Response> {
  const rms = intParam(url, "rms") ?? VOICED_RMS;
  const floor = Number(url.searchParams.get("ratio") ?? MIN_VOICED_RATIO);
  const dry = url.searchParams.get("dry") === "1";
  const limit = Math.min(intParam(url, "limit") ?? 500, 2000);

  const rows = await env.DB.prepare(
    `SELECT segment_id, r2_key, status, rms_envelope
       FROM segments
      WHERE rms_envelope IS NOT NULL AND kind = 'lifelog'
      ORDER BY started_epoch DESC
      LIMIT ?1`,
  )
    .bind(limit)
    .all<{ segment_id: string; r2_key: string; status: string; rms_envelope: string }>();

  let wouldTranscribe = 0;
  let wouldSkip = 0;
  let queued = 0;

  for (const row of rows.results ?? []) {
    const envelope = decodeEnvelope(row.rms_envelope);
    if (!envelope) continue;
    const ratio = envelope.length
      ? envelope.filter((v) => v >= rms).length / envelope.length
      : 1;
    const voiced = ratio >= floor;
    if (voiced) wouldTranscribe += 1;
    else wouldSkip += 1;

    if (!dry && voiced && row.status === "silent") {
      await env.DB.prepare(
        `UPDATE segments SET status = 'pending', voiced_ratio = ?1 WHERE segment_id = ?2`,
      )
        .bind(ratio, row.segment_id)
        .run();
      await env.TRANSCRIBE_QUEUE.send({ segmentId: row.segment_id, key: row.r2_key });
      queued += 1;
    }
  }

  return json({
    examined: rows.results?.length ?? 0,
    rms_threshold: rms,
    ratio_threshold: floor,
    would_transcribe: wouldTranscribe,
    would_skip: wouldSkip,
    queued,
    dry,
  });
}

/**
 * Re-transcribe a window of already-transcribed audio.
 *
 * Distinct from backfill, which only picks up rows that never got quality
 * signals. This exists because the transcription settings themselves change —
 * a language, a VAD flag — and everything recorded before the change is
 * carrying worse text than the same bytes would produce today. The recordings
 * are kept precisely so that this is possible.
 */
async function retranscribe(env: Env, url: URL): Promise<Response> {
  const from = url.searchParams.get("from");
  const to = url.searchParams.get("to");
  if (!from || Number.isNaN(Date.parse(from))) return json({ error: "from required" }, 400);
  if (!to || Number.isNaN(Date.parse(to))) return json({ error: "to required" }, 400);

  const dry = url.searchParams.get("dry") === "1";
  const limit = Math.min(intParam(url, "limit") ?? 200, 1000);
  const language = url.searchParams.get("language") ?? undefined;

  const rows = await env.DB.prepare(
    `SELECT segment_id, r2_key FROM segments
      WHERE started_epoch >= ?1 AND started_epoch <= ?2
        AND r2_key IS NOT NULL
      ORDER BY started_epoch ASC
      LIMIT ?3`,
  )
    .bind(
      Math.floor(Date.parse(from) / 1000),
      Math.floor(Date.parse(to) / 1000),
      limit,
    )
    .all<{ segment_id: string; r2_key: string }>();

  const todo = rows.results ?? [];
  if (dry) return json({ matched: todo.length, dry: true });

  for (const row of todo) {
    await env.TRANSCRIBE_QUEUE.send({
      segmentId: row.segment_id,
      key: row.r2_key,
      language,
    });
  }

  return json({ matched: todo.length, queued: todo.length, language: language ?? "default" });
}

/**
 * Re-apply the stock-phrase test to transcripts already stored.
 *
 * Costs nothing and touches no audio — the text is right there. Exists so the
 * phrase list can be edited and the whole archive brought into line, rather
 * than only whatever happens to be transcribed next.
 */
async function reflag(env: Env, url: URL): Promise<Response> {
  const dry = url.searchParams.get("dry") === "1";
  const rows = await env.DB.prepare(
    `SELECT segment_id, transcript, stock_phrase FROM segments
      WHERE transcript IS NOT NULL AND transcript <> ''`,
  ).all<{ segment_id: string; transcript: string; stock_phrase: number }>();

  const changes: { id: string; to: number }[] = [];
  for (const row of rows.results ?? []) {
    const flag = isStockPhrase(row.transcript) ? 1 : 0;
    if (flag !== row.stock_phrase) changes.push({ id: row.segment_id, to: flag });
  }

  if (!dry) {
    for (const change of changes) {
      await env.DB.prepare(`UPDATE segments SET stock_phrase = ?1 WHERE segment_id = ?2`)
        .bind(change.to, change.id)
        .run();
    }
  }

  return json({
    examined: rows.results?.length ?? 0,
    changed: changes.length,
    newly_flagged: changes.filter((c) => c.to === 1).length,
    unflagged: changes.filter((c) => c.to === 0).length,
    dry,
  });
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
