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
import { fetchMapAssets, serveMap } from "./map";
import { interpretTargets, mintInterpretSession } from "./interpret";
import { UI_HTML } from "./ui";

interface Env {
  BUCKET: R2Bucket;
  DB: D1Database;
  TRANSCRIBE_QUEUE: Queue<TranscribeMessage>;
  AI: Ai;
  INGEST_TOKEN: string;
  /**
   * Opens the destructive endpoints, and is deliberately not on the device.
   * Absent means nobody can reach them at all, which is the safe default for a
   * deploy that has not been given one yet.
   */
  ADMIN_TOKEN?: string;
  /** Reaches /mcp and nothing else; lives on the Hermes gateway. */
  AGENT_TOKEN?: string;
  /** The feed reader Worker; see the note on /v1/feeds below. */
  READER: Fetcher;
  /** Held so the device never has to; see interpret.ts. */
  OPENAI_API_KEY?: string;
  /** Spoken language to assume. "auto" lets Whisper guess. See LANGUAGE below. */
  TRANSCRIBE_LANGUAGE?: string;
}

/**
 * One queue carries both jobs. They differ only in which model reads the
 * object, and a second queue would mean a second consumer, a second dead
 * letter queue and two backlogs to reason about instead of one.
 */
interface TranscribeMessage {
  segmentId: string;
  key: string;
  /** Carried per message so a queued segment keeps the language it arrived with. */
  language?: string;
  /** Absent means audio, for messages queued before captioning existed. */
  kind?: "audio" | "photo";
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

    const denied = authorise(request, env, url);
    if (denied) return denied;

    if (request.method === "PUT" && url.pathname.startsWith("/v1/segments/")) {
      return putSegment(request, env, url);
    }
    if (request.method === "GET" && url.pathname.startsWith("/v1/segments/")) {
      return getSegment(env, url);
    }
    if (request.method === "PUT" && url.pathname.startsWith("/v1/photos/")) {
      return putPhoto(request, env, url);
    }
    if (request.method === "GET" && url.pathname === "/v1/photos") {
      return listPhotos(env, url);
    }
    if (request.method === "GET" && url.pathname === "/v1/debug/caption") {
      return debugCaption(env, url);
    }
    if (request.method === "POST" && url.pathname === "/v1/admin/caption-backfill") {
      return captionBackfill(env, url);
    }
    if (request.method === "GET" && (url.pathname === "/" || url.pathname === "/ui")) {
      return new Response(UI_HTML, {
        headers: { "content-type": "text/html; charset=utf-8" },
      });
    }
    if (request.method === "GET" && url.pathname === "/v1/day") {
      return dayView(env, url);
    }
    if (request.method === "GET" && url.pathname === "/v1/talk") {
      return talkVolume(env, url);
    }
    // The feed reader, which is a different Worker with a different database
    // and no handle on this one's. It is unroutable from outside — no domain,
    // no workers.dev — so this is its only door, and it inherits the perimeter
    // that already guards everything above rather than needing its own.
    if (url.pathname.startsWith("/v1/feeds/")) {
      const inner = new URL(request.url);
      inner.pathname = url.pathname.replace("/v1/feeds/", "/feeds/");
      return env.READER.fetch(new Request(inner, request));
    }
    if (request.method === "POST" && url.pathname === "/v1/interpret/session") {
      return mintInterpretSession(env, url);
    }
    if (request.method === "GET" && url.pathname === "/v1/interpret/targets") {
      return interpretTargets();
    }
    if (request.method === "GET" && url.pathname.startsWith("/v1/map/")) {
      return serveMap(request, env, url);
    }
    if (request.method === "POST" && url.pathname === "/v1/admin/map-fetch") {
      return fetchMapAssets(env, url);
    }
    if (url.pathname === "/v1/positions") {
      return request.method === "POST"
        ? putPositions(request, env)
        : listPositions(env, url);
    }
    if (url.pathname === "/v1/usage") {
      return request.method === "PUT" ? putUsage(request, env) : getUsage(env);
    }
    if (request.method === "GET" && url.pathname.startsWith("/v1/media/")) {
      return serveMedia(request, env, url);
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
    if (request.method === "POST" && url.pathname === "/v1/admin/repair-wav") {
      return repairWav(env, url);
    }

    // Same auth as everything else, so the agent uses the token it already has.
    if (url.pathname === "/mcp") {
      return handleMcp(request, {
        context: (at, beforeSec) => contextData(env, at, beforeSec),
        search: (q, limit) => searchData(env, q, limit),
        stats: () => statsData(env),
        // Forwarded to the reader Worker. The agent sees one MCP server; that
        // the headlines live in a different database with no reach into the
        // archive is this side's business, not the agent's.
        feedsLatest: (limit, category) =>
          reader(env, `/feeds/latest?limit=${limit}` +
            (category ? `&category=${encodeURIComponent(category)}` : "")),
        feedsSearch: (q, limit) =>
          reader(env, `/feeds/search?q=${encodeURIComponent(q)}&limit=${limit}`),
      });
    }

    return json({ error: "not found" }, 404);
  },

  async queue(batch: MessageBatch<TranscribeMessage>, env: Env): Promise<void> {
    for (const message of batch.messages) {
      const isPhoto = message.body.kind === "photo";
      try {
        if (isPhoto) {
          await captionPhoto(message.body, env);
        } else {
          await transcribe(message.body, env);
        }
        message.ack();
      } catch (error) {
        // Let the queue redeliver: a model hiccup should not cost the
        // recording, which is already durable in R2.
        console.error("job failed", message.body.segmentId, error);
        const table = isPhoto ? "photos" : "segments";
        const column = isPhoto ? "photo_id" : "segment_id";
        await env.DB.prepare(
          `UPDATE ${table} SET error = ?1 WHERE ${column} = ?2`,
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
 * Who is asking, and what that lets them do.
 *
 * The device carries a token in a pocket, on hardware where any installed app
 * can open a root shell. It should therefore be able to do the seven things it
 * actually does and nothing else — and for a long time it could do all
 * twenty-nine, including rewriting objects in R2 and spending money on Whisper.
 * Losing the R1 meant losing the archive, not just losing a recorder.
 */
type Principal = "admin" | "device" | "agent";

/**
 * Exactly what the device calls, verified against its source rather than
 * guessed. Anything not on this list gets a 403 even with a valid device
 * token.
 */
const DEVICE_ROUTES: Array<{ method: string; path: RegExp }> = [
  { method: "PUT", path: /^\/v1\/segments\/[A-Za-z0-9_-]{8,64}$/ },
  { method: "PUT", path: /^\/v1\/photos\/[A-Za-z0-9_-]{8,64}$/ },
  { method: "POST", path: /^\/v1\/positions$/ },
  { method: "GET", path: /^\/v1\/day$/ },
  { method: "GET", path: /^\/v1\/feeds\/latest$/ },
  { method: "GET", path: /^\/v1\/usage$/ },
  { method: "PUT", path: /^\/v1\/usage$/ },
  { method: "POST", path: /^\/v1\/interpret\/session$/ },
];

function principalFor(request: Request, env: Env): Principal | null {
  const header = request.headers.get("authorization") ?? "";
  const presented = header.startsWith("Bearer ") ? header.slice(7) : "";

  // Checked first, so that a laptop sending both tokens is an administrator.
  if (env.ADMIN_TOKEN && presented && timingSafeEqual(presented, env.ADMIN_TOKEN)) {
    return "admin";
  }
  if (env.INGEST_TOKEN && presented && timingSafeEqual(presented, env.INGEST_TOKEN)) {
    return "device";
  }
  if (env.AGENT_TOKEN && presented && timingSafeEqual(presented, env.AGENT_TOKEN)) {
    return "agent";
  }

  // A browser cannot attach a bearer to a navigation, so the web UI leans on
  // the perimeter instead. Access stamps this header onto every request it
  // lets through, and Access is the only way in: the workers.dev route does
  // not serve this Worker, so there is no path to it that skips the check.
  //
  // The assertion is not verified here. Doing so properly means fetching and
  // caching Cloudflare's signing keys, and it would only defend against
  // someone who could already reach the origin directly — which is the thing
  // that is closed off.
  //
  // Note what this does *not* separate: the device also holds an Access
  // service token, so a lost R1 can present this header with no bearer and be
  // treated as the browser. That closes the destruction path — admin
  // endpoints demand the admin bearer specifically — but not the read path.
  // See BACKLOG.
  if (request.headers.get("cf-access-jwt-assertion")) return "admin";

  return null;
}

function authorise(request: Request, env: Env, url: URL): Response | null {
  if (!env.INGEST_TOKEN) return json({ error: "server missing INGEST_TOKEN" }, 500);

  const who = principalFor(request, env);
  if (who === "admin") return null;

  // The agent reads the archive through its tools and writes nothing. It runs
  // on a server rather than in a pocket, so losing it is a different kind of
  // problem from losing the R1 — but it still has no business rewriting R2.
  if (who === "agent") {
    if (url.pathname === "/mcp") return null;
    return json({ error: "forbidden for this credential", path: url.pathname }, 403);
  }

  if (who === "device") {
    const allowed = DEVICE_ROUTES.some(
      (route) => route.method === request.method && route.path.test(url.pathname),
    );
    if (allowed) return null;
    // 403 rather than 404: the caller is authenticated and the route exists.
    // Saying so is not a leak — whoever holds this token can read the source.
    return json({ error: "forbidden for this credential", path: url.pathname }, 403);
  }

  // A browser cannot attach a bearer to a navigation, so the web UI leans on
  // the perimeter instead. Access stamps this header onto every request it
  // lets through, and Access is the only way in: the workers.dev route does
  // not serve this Worker, so there is no path to it that skips the check.
  //
  // The assertion is not verified here. Doing so properly means fetching and
  // caching Cloudflare's signing keys, and it would only defend against
  // someone who could already reach the origin directly — which is the thing
  // that is closed off.
  if (request.headers.get("cf-access-jwt-assertion")) return null;

  return json({ error: "unauthorized" }, 401);
}

/**
 * Calls the reader Worker over the service binding.
 *
 * The hostname is a formality — a service binding does not resolve DNS or
 * leave the account — but a URL has to have one.
 */
async function reader(env: Env, path: string): Promise<unknown> {
  const response = await env.READER.fetch(new Request(`https://reader.internal${path}`));
  if (!response.ok) throw new Error(`reader returned ${response.status}`);
  return response.json();
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
  const channels = intParam(url, "channels");
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

  // The device sends the digest of what it hashed off its own disk. Handing it
  // to R2 makes the write fail on a mismatch rather than faithfully storing
  // whatever arrived — a segment corrupted in transit would otherwise surface
  // years later as a transcript that reads slightly wrong, with nothing to say
  // it was ever damaged.
  const sha256 = url.searchParams.get("sha256")?.toLowerCase() ?? null;
  if (sha256 !== null && !/^[0-9a-f]{64}$/.test(sha256)) {
    return json({ error: "sha256 must be 64 hex characters" }, 400);
  }

  let object: R2Object | null;
  try {
    object = await env.BUCKET.put(key, request.body, {
      httpMetadata: {
        contentType: request.headers.get("content-type") ?? "application/octet-stream",
      },
      customMetadata: { segmentId, deviceId, kind, startedAt },
      ...(sha256 ? { sha256 } : {}),
    });
  } catch (error) {
    // Nothing is stored under this key, so the device retrying is the right
    // outcome; 422 tells it the bytes were wrong rather than the server.
    console.error("upload rejected", segmentId, error);
    return json(
      { segment_id: segmentId, error: "checksum mismatch", detail: String(error).slice(0, 200) },
      422,
    );
  }

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
        received_at, status, rms_envelope, voiced_ratio, sha256, channels)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17)
     ON CONFLICT(segment_id) DO UPDATE SET
       r2_key = excluded.r2_key,
       bytes = excluded.bytes,
       received_at = excluded.received_at,
       status = excluded.status,
       rms_envelope = excluded.rms_envelope,
       voiced_ratio = excluded.voiced_ratio,
       sha256 = excluded.sha256,
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
      sha256,
      channels,
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

// -------------------------------------------------------------------- ui ---

/**
 * One day, audio and photographs interleaved in time.
 *
 * Photographs arrive in front/rear pairs seconds apart and belong together on
 * the page, so they are grouped by the minute they were taken rather than
 * listed twice.
 *
 * Silent segments are included. They carry no transcript, but the recording
 * exists and is the thing being archived — leaving them out would make the
 * page disagree with the bucket. The UI hides them by default.
 */
async function dayView(env: Env, url: URL): Promise<Response> {
  const date = url.searchParams.get("date") ?? new Date().toISOString().slice(0, 10);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json({ error: "date must be YYYY-MM-DD" }, 400);

  // The device stamps +09:00 and started_epoch is UTC seconds; a day is the
  // local one, so the window is shifted rather than the timestamps.
  const from = Math.floor(Date.parse(`${date}T00:00:00+09:00`) / 1000);
  const to = from + 86_400;

  const segments = await env.DB.prepare(
    `SELECT segment_id, started_at, status, transcript, stock_phrase, voiced_ratio
       FROM segments
      WHERE started_epoch >= ?1 AND started_epoch < ?2 AND kind = 'lifelog'
      ORDER BY started_epoch ASC
      LIMIT 2000`,
  )
    .bind(from, to)
    .all<any>();

  const photos = await env.DB.prepare(
    `SELECT photo_id, taken_at, taken_epoch, facing, caption
       FROM photos
      WHERE taken_epoch >= ?1 AND taken_epoch < ?2
      ORDER BY taken_epoch ASC
      LIMIT 1000`,
  )
    .bind(from, to)
    .all<any>();

  const entries: any[] = [];
  let withText = 0;

  for (const row of segments.results ?? []) {
    // A stock phrase is Whisper talking to itself; the recording stays, the
    // text does not get shown.
    const text = row.stock_phrase ? "" : String(row.transcript ?? "").trim();
    if (text) withText += 1;
    entries.push({
      kind: "audio",
      at: row.started_at,
      id: row.segment_id,
      status: row.status,
      voiced: row.voiced_ratio,
      text,
    });
  }

  // Group the pair that was taken together.
  const byMinute = new Map<number, any[]>();
  for (const row of photos.results ?? []) {
    const minute = Math.floor(row.taken_epoch / 60);
    if (!byMinute.has(minute)) byMinute.set(minute, []);
    byMinute.get(minute)!.push(row);
  }
  for (const [, shots] of byMinute) {
    entries.push({
      kind: "photo",
      at: shots[0].taken_at,
      shots: shots.map((s: any) => ({
        id: s.photo_id,
        facing: s.facing,
        caption: s.caption ?? "",
      })),
    });
  }

  entries.sort((a, b) => String(a.at).localeCompare(String(b.at)));

  return json({
    date,
    entries,
    stats: {
      segments: segments.results?.length ?? 0,
      with_text: withText,
      photos: photos.results?.length ?? 0,
    },
  });
}

// ------------------------------------------------------------- positions ---

/** One batch is a few hours of five-minute fixes; far more is a bug. */
const MAX_POSITIONS_PER_BATCH = 500;

/**
 * A batch of fixes.
 *
 * Posted as an array rather than one at a time because a fix is about eighty
 * bytes and the device is often offline when it takes one — the interesting
 * positions are the ones away from home, which is exactly where the upload
 * cannot go out. Batching means the backlog costs one request, not three
 * hundred.
 *
 * Idempotent by primary key: the device may resend a batch it is unsure about
 * and the repeats are absorbed. That matters more here than for audio, because
 * there is no object in R2 to compare against — the row is the whole artefact,
 * so a lost batch is lost, and the device is meant to retry freely.
 */
async function putPositions(request: Request, env: Env): Promise<Response> {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return json({ error: "body must be JSON" }, 400);
  }

  const items = Array.isArray(body) ? body : (body as any)?.positions;
  if (!Array.isArray(items)) {
    return json({ error: "expected an array of positions" }, 400);
  }
  if (items.length > MAX_POSITIONS_PER_BATCH) {
    return json({ error: `at most ${MAX_POSITIONS_PER_BATCH} positions per batch` }, 413);
  }

  const receivedAt = new Date().toISOString();
  const statements: D1PreparedStatement[] = [];
  const rejected: unknown[] = [];

  const insert = env.DB.prepare(
    `INSERT OR IGNORE INTO positions
       (device_id, recorded_at, recorded_epoch, lat, lon,
        accuracy_m, altitude_m, speed_mps, bearing_deg, provider, received_at)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)`,
  );

  for (const raw of items) {
    const p = raw as any;
    const at = String(p?.recorded_at ?? "");
    const epoch = Date.parse(at);
    const lat = Number(p?.lat);
    const lon = Number(p?.lon);

    // Range-checked rather than merely parsed. A transposed pair still lands
    // inside the ranges, but a corrupted one usually does not, and a row that
    // cannot be a place on Earth is worse than a missing row: it is a hole in
    // the map that looks like a journey.
    const usable =
      Number.isFinite(epoch) &&
      Number.isFinite(lat) && lat >= -90 && lat <= 90 &&
      Number.isFinite(lon) && lon >= -180 && lon <= 180;

    if (!usable) {
      rejected.push({ recorded_at: p?.recorded_at, lat: p?.lat, lon: p?.lon });
      continue;
    }

    statements.push(
      insert.bind(
        String(p?.device_id ?? "unknown"),
        at,
        Math.floor(epoch / 1000),
        lat,
        lon,
        numberOrNull(p?.accuracy_m),
        numberOrNull(p?.altitude_m),
        numberOrNull(p?.speed_mps),
        numberOrNull(p?.bearing_deg),
        p?.provider ? String(p.provider).slice(0, 32) : null,
        receivedAt,
      ),
    );
  }

  if (statements.length) await env.DB.batch(statements);

  // The count is what the device needs to decide whether to delete its copy,
  // and the rejects are echoed back so a systematic fault shows up in one
  // response rather than as a slow thinning of the track.
  return json({ accepted: statements.length, rejected: rejected.length, rejects: rejected.slice(0, 5) });
}

function numberOrNull(value: unknown): number | null {
  const n = Number(value);
  return Number.isFinite(n) ? n : null;
}

/**
 * The track for one local day, oldest first.
 *
 * Wildly inaccurate fixes are returned rather than filtered — with their
 * accuracy, so whatever draws them can decide. A viewer can grey out a 2 km
 * fix; it cannot invent one that was dropped here.
 */
async function listPositions(env: Env, url: URL): Promise<Response> {
  const date = url.searchParams.get("date") ?? new Date(Date.now() + 9 * 3600e3)
    .toISOString()
    .slice(0, 10);
  if (!/^\d{4}-\d{2}-\d{2}$/.test(date)) return json({ error: "date must be YYYY-MM-DD" }, 400);

  const from = Math.floor(Date.parse(`${date}T00:00:00+09:00`) / 1000);
  const to = from + 86_400;

  const rows = await env.DB.prepare(
    `SELECT recorded_at, recorded_epoch, lat, lon, accuracy_m, speed_mps, provider
       FROM positions
      WHERE recorded_epoch >= ?1 AND recorded_epoch < ?2
      ORDER BY recorded_epoch ASC
      LIMIT 2000`,
  )
    .bind(from, to)
    .all();

  return json({ date, count: rows.results?.length ?? 0, positions: rows.results ?? [] });
}

// ------------------------------------------------------------------ talk ---

/**
 * How many minutes of each recent day contained speech.
 *
 * The JPHC cohort found daily conversation frequency, not duration or content,
 * is what tracks with dementia risk over a decade — and frequency is the one
 * thing a device that has been listening since August can actually report
 * about its owner rather than guess.
 *
 * Two counts, because they answer different questions and disagree usefully:
 *
 *   voiced   the envelope cleared the VAD threshold. Available for every
 *            segment ever recorded, including the ones never transcribed, so
 *            this is the honest denominator.
 *   spoken   Whisper found words, the ratio cleared 0.15, and it was not a
 *            stock phrase. Fewer minutes, higher confidence.
 *
 * **This does not measure conversation.** It measures minutes containing
 * speech. It cannot separate the owner's voice from a family member's, from
 * the other side of a video call, or from a television — the microphones are
 * left/right symmetric and diarisation is not done. For someone working alone
 * in a quiet room the proxy is decent; on a train it is worthless. Anything
 * built on this number has to survive being wrong about which human was
 * talking, which is why the number is shown and never interpreted.
 */
async function talkVolume(env: Env, url: URL): Promise<Response> {
  const days = Math.min(Math.max(intParam(url, "days") ?? 14, 1), 90);

  // Days are local. The device stamps +09:00 and started_epoch is UTC seconds,
  // so the bucket boundary is shifted rather than the timestamps — the same
  // trick dayView uses, and it has to stay the same or the two pages disagree
  // about which minutes belong to yesterday.
  const JST_OFFSET = 9 * 3600;
  const now = Math.floor(Date.now() / 1000);
  const from = Math.floor((now + JST_OFFSET) / 86_400) * 86_400 - JST_OFFSET
    - (days - 1) * 86_400;

  const rows = await env.DB.prepare(
    `SELECT
        CAST((started_epoch + ?2) / 86400 AS INTEGER) AS day_index,
        COUNT(*) AS segments,
        SUM(CASE WHEN voiced_ratio >= ?3 THEN 1 ELSE 0 END) AS voiced,
        SUM(CASE WHEN status = 'transcribed' AND stock_phrase = 0
                  AND speech_ratio >= ?4 THEN 1 ELSE 0 END) AS spoken
       FROM segments
      WHERE kind = 'lifelog' AND started_epoch >= ?1
      GROUP BY day_index
      ORDER BY day_index ASC`,
  )
    .bind(from, JST_OFFSET, MIN_VOICED_RATIO, MIN_SPEECH_RATIO)
    .all<{ day_index: number; segments: number; voiced: number; spoken: number }>();

  // A segment is a minute, so counts are minutes. That equivalence is worth
  // stating rather than assuming: it holds because SEGMENT_SECONDS is 60 on
  // the device, and it silently stops holding if that ever changes.
  const byDay = (rows.results ?? []).map((r) => ({
    date: new Date((r.day_index * 86_400 - JST_OFFSET) * 1000)
      .toISOString()
      .slice(0, 10),
    recorded_minutes: r.segments,
    voiced_minutes: r.voiced,
    spoken_minutes: r.spoken,
  }));

  const today = byDay[byDay.length - 1] ?? null;
  const withData = byDay.filter((d) => d.recorded_minutes > 0);
  const median = (values: number[]) => {
    if (!values.length) return null;
    const sorted = [...values].sort((a, b) => a - b);
    return sorted[Math.floor(sorted.length / 2)];
  };

  return json({
    days,
    from: new Date(from * 1000).toISOString(),
    by_day: byDay,
    today,
    // A median rather than a mean: one long day on a train, where the
    // microphone hears a carriage rather than a conversation, would drag an
    // average up and make every other day look quiet by comparison.
    median_voiced_minutes: median(withData.map((d) => d.voiced_minutes)),
    median_spoken_minutes: median(withData.map((d) => d.spoken_minutes)),
    caveat:
      "minutes containing speech, not conversation: the owner, another person, " +
      "a call, and a television are not distinguished",
  });
}

/**
 * Streams an object straight out of R2.
 *
 * Range requests are honoured because an audio element asks for them when the
 * user drags the scrub bar, and a minute of Opus is small enough that getting
 * this wrong is invisible until the day someone opens an hour-long one.
 */
async function serveMedia(request: Request, env: Env, url: URL): Promise<Response> {
  const rest = url.pathname.slice("/v1/media/".length);
  const slash = rest.indexOf("/");
  if (slash < 0) return json({ error: "bad media path" }, 400);

  const kind = rest.slice(0, slash);
  const id = decodeURIComponent(rest.slice(slash + 1));

  let key: string | null = null;
  let contentType = "application/octet-stream";

  if (kind === "audio") {
    const row = await env.DB.prepare(`SELECT r2_key, codec FROM segments WHERE segment_id = ?1`)
      .bind(id)
      .first<{ r2_key: string; codec: string }>();
    if (!row) return json({ error: "not found" }, 404);
    key = row.r2_key;
    contentType = row.codec === "opus" ? "audio/ogg" : "audio/wav";
  } else if (kind === "photo") {
    const row = await env.DB.prepare(`SELECT r2_key FROM photos WHERE photo_id = ?1`)
      .bind(id)
      .first<{ r2_key: string }>();
    if (!row) return json({ error: "not found" }, 404);
    key = row.r2_key;
    contentType = "image/jpeg";
  } else {
    return json({ error: "media kind must be audio or photo" }, 400);
  }

  const range = request.headers.get("range");
  const object = await env.BUCKET.get(key!, range ? { range: request.headers } : undefined);
  if (!object) return json({ error: "object missing" }, 404);

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("content-type", contentType);
  headers.set("etag", object.httpEtag);
  // Immutable: an object is written once under a key that is never reused.
  headers.set("cache-control", "private, max-age=31536000, immutable");
  headers.set("accept-ranges", "bytes");

  // Only when the client actually asked. R2 reports a range on a plain GET
  // too — covering the whole object — and answering 206 to a request that
  // carried no Range header is a lie about what was sent.
  if (range && object.range && "offset" in object.range) {
    const offset = object.range.offset ?? 0;
    const length = object.range.length ?? object.size - offset;
    headers.set("content-range", `bytes ${offset}-${offset + length - 1}/${object.size}`);
    return new Response(object.body, { status: 206, headers });
  }

  headers.set("content-length", String(object.size));
  return new Response(object.body, { headers });
}

// ----------------------------------------------------------------- usage ---

/**
 * The latest reading from the machine Codex and Claude Code run on.
 *
 * Stored as the JSON it arrived as, not parsed into columns. The two tools
 * report different shapes — one has a real percentage, the other only tokens —
 * and that asymmetry is theirs to change, not something to freeze into a
 * schema here.
 */
async function putUsage(request: Request, env: Env): Promise<Response> {
  const text = await request.text();
  if (text.length > 64 * 1024) return json({ error: "too large" }, 413);

  let generatedAt: number | null = null;
  try {
    generatedAt = Number(JSON.parse(text)?.generated_at) || null;
  } catch {
    return json({ error: "body must be JSON" }, 400);
  }

  await env.DB.prepare(
    `INSERT INTO usage_snapshot (id, body, generated_at, received_at)
     VALUES (1, ?1, ?2, ?3)
     ON CONFLICT(id) DO UPDATE SET
       body = excluded.body,
       generated_at = excluded.generated_at,
       received_at = excluded.received_at`,
  )
    .bind(text, generatedAt, new Date().toISOString())
    .run();

  return json({ stored: true, generated_at: generatedAt });
}

async function getUsage(env: Env): Promise<Response> {
  const row = await env.DB.prepare(
    `SELECT body, generated_at, received_at FROM usage_snapshot WHERE id = 1`,
  ).first<{ body: string; generated_at: number | null; received_at: string }>();

  if (!row) return json({ available: false, reason: "nothing reported yet" }, 200);

  // Age is what decides whether the numbers can be trusted, and the device
  // should not have to work it out from two clocks.
  const ageSeconds = row.generated_at
    ? Math.max(0, Math.floor(Date.now() / 1000) - row.generated_at)
    : null;

  return json({
    available: true,
    age_seconds: ageSeconds,
    received_at: row.received_at,
    usage: JSON.parse(row.body),
  });
}

// ---------------------------------------------------------------- photos ---

/**
 * A timelapse frame. Stored and indexed; never transcribed, never queued.
 *
 * The same durability contract as audio — the row is written only after the
 * bytes are in R2, and the device deletes its copy only on a 2xx — because a
 * photograph of a moment is exactly as unrepeatable as a recording of one.
 */
async function putPhoto(request: Request, env: Env, url: URL): Promise<Response> {
  const photoId = url.pathname.slice("/v1/photos/".length);
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(photoId)) {
    return json({ error: "photo id must be 8-64 chars of [A-Za-z0-9_-]" }, 400);
  }

  const takenAt = url.searchParams.get("taken_at");
  if (!takenAt || Number.isNaN(Date.parse(takenAt))) {
    return json({ error: "taken_at must be an ISO 8601 timestamp" }, 400);
  }

  const facing = url.searchParams.get("facing") === "rear" ? "rear" : "front";
  const deviceId = url.searchParams.get("device_id") ?? "unknown";

  const existing = await env.DB.prepare(
    `SELECT r2_key FROM photos WHERE photo_id = ?1`,
  )
    .bind(photoId)
    .first<{ r2_key: string }>();
  if (existing) {
    return json({ photo_id: photoId, key: existing.r2_key, duplicate: true });
  }

  if (!request.body) return json({ error: "empty body" }, 400);

  const sha256 = url.searchParams.get("sha256")?.toLowerCase() ?? null;
  if (sha256 !== null && !/^[0-9a-f]{64}$/.test(sha256)) {
    return json({ error: "sha256 must be 64 hex characters" }, 400);
  }

  const day = takenAt.slice(0, 10).replace(/-/g, "/");
  const key = `photo/${day}/${photoId}.jpg`;

  let object: R2Object | null;
  try {
    object = await env.BUCKET.put(key, request.body, {
      httpMetadata: { contentType: "image/jpeg" },
      customMetadata: { photoId, deviceId, facing, takenAt },
      ...(sha256 ? { sha256 } : {}),
    });
  } catch (error) {
    return json({ photo_id: photoId, error: "checksum mismatch" }, 422);
  }

  await env.DB.prepare(
    `INSERT INTO photos
       (photo_id, device_id, r2_key, facing, taken_at, taken_epoch, bytes,
        sha256, received_at)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)
     ON CONFLICT(photo_id) DO NOTHING`,
  )
    .bind(
      photoId,
      deviceId,
      key,
      facing,
      takenAt,
      Math.floor(Date.parse(takenAt) / 1000),
      object?.size ?? null,
      sha256,
      new Date().toISOString(),
    )
    .run();

  // Captioning takes about five seconds, so it goes behind the queue for the
  // same reason transcription does: the upload returns once the bytes are
  // durable, which is the part that cannot be retried later.
  await env.TRANSCRIBE_QUEUE.send({ segmentId: photoId, key, kind: "photo" });

  return json({ photo_id: photoId, key, bytes: object?.size ?? null }, 201);
}

/**
 * What the vision model is asked for.
 *
 * Japanese, because the transcripts are and a search that has to work across
 * two languages works well in neither. Concrete and short: the caption exists
 * so a frame can be found later, not so it can be read for pleasure, and
 * every token is billed.
 */
const CAPTION_PROMPT =
  "この写真に写っているものを日本語で簡潔に説明してください。" +
  "人物の有無、場所の様子、目立つ物を2〜3文で。推測は避け、見えるものだけを述べてください。";

/**
 * Room for the answer, and a ceiling on what a runaway one can cost.
 *
 * The measurement that justified this feature came back truncated at 128, so
 * this is higher; output is 14x the price of input per token, which is why it
 * is not higher still.
 */
const CAPTION_MAX_TOKENS = 220;

/** Captions one photograph and records what it cost. */
async function captionPhoto(message: TranscribeMessage, env: Env): Promise<void> {
  const object = await env.BUCKET.get(message.key);
  if (!object) {
    await env.DB.prepare(
      `UPDATE photos SET status = 'failed', error = 'r2 object missing'
        WHERE photo_id = ?1`,
    )
      .bind(message.segmentId)
      .run();
    return;
  }

  const bytes = new Uint8Array(await object.arrayBuffer());
  const result: any = await env.AI.run(
    "@cf/meta/llama-3.2-11b-vision-instruct" as any,
    { image: [...bytes], prompt: CAPTION_PROMPT, max_tokens: CAPTION_MAX_TOKENS } as any,
  );

  const text = String(result?.response ?? "").trim();
  await env.DB.prepare(
    `UPDATE photos
        SET caption = ?1, caption_at = ?2, caption_neurons = ?3,
            status = 'captioned', error = NULL
      WHERE photo_id = ?4`,
  )
    .bind(
      text,
      new Date().toISOString(),
      Number(result?.usage?.neurons) || null,
      message.segmentId,
    )
    .run();
}

/**
 * Runs one stored photograph through the vision model and reports what it
 * cost, so the price of captioning the archive is measured rather than
 * guessed.
 *
 * The pricing page bills vision input per token and does not say how many
 * tokens an image becomes — the model tiles at 560x560, so a 640x480 frame is
 * somewhere between one and four tiles and the estimate spans 4x. The raw
 * response is returned unedited because whether it carries a usage block is
 * exactly what needs finding out.
 */
async function debugCaption(env: Env, url: URL): Promise<Response> {
  const photoId = url.searchParams.get("photo_id");
  if (!photoId) return json({ error: "photo_id required" }, 400);

  const row = await env.DB.prepare(`SELECT r2_key FROM photos WHERE photo_id = ?1`)
    .bind(photoId)
    .first<{ r2_key: string }>();
  if (!row) return json({ error: "not found" }, 404);

  const object = await env.BUCKET.get(row.r2_key);
  if (!object) return json({ error: "image missing" }, 404);
  const bytes = new Uint8Array(await object.arrayBuffer());

  const prompt = url.searchParams.get("prompt") ??
    "この写真に写っているものを日本語で簡潔に説明してください。";

  const started = Date.now();
  try {
    const result: any = await env.AI.run(
      "@cf/meta/llama-3.2-11b-vision-instruct" as any,
      { image: [...bytes], prompt, max_tokens: 128 } as any,
    );
    return json({
      photo_id: photoId,
      image_bytes: bytes.length,
      elapsed_ms: Date.now() - started,
      // Verbatim: the usage block, if there is one, is the whole point.
      raw: result,
    });
  } catch (error) {
    return json({ photo_id: photoId, error: String(error).slice(0, 400) }, 500);
  }
}

/** What was in front of the device over a window; metadata only, no bytes. */
async function listPhotos(env: Env, url: URL): Promise<Response> {
  const at = url.searchParams.get("at") ?? new Date().toISOString();
  if (Number.isNaN(Date.parse(at))) return json({ error: "bad at" }, 400);
  const beforeSec = intParam(url, "before_sec") ?? 3600;
  const atEpoch = Math.floor(Date.parse(at) / 1000);

  const rows = await env.DB.prepare(
    `SELECT photo_id, facing, taken_at, bytes, r2_key, caption, status
       FROM photos
      WHERE taken_epoch >= ?1 AND taken_epoch <= ?2
      ORDER BY taken_epoch ASC
      LIMIT 500`,
  )
    .bind(atEpoch - beforeSec, atEpoch)
    .all();

  return json({ at, before_sec: beforeSec, count: rows.results?.length ?? 0, photos: rows.results });
}

/**
 * Captions photographs that predate captioning, oldest first.
 *
 * Bounded per call rather than looping to exhaustion: each one is a five
 * second model call, and a request that tried to do hundreds would hit the
 * Worker's limits long before it ran out of work.
 */
async function captionBackfill(env: Env, url: URL): Promise<Response> {
  const limit = Math.min(intParam(url, "limit") ?? 50, 500);

  const rows = await env.DB.prepare(
    `SELECT photo_id, r2_key FROM photos
      WHERE caption IS NULL AND status != 'failed'
      ORDER BY taken_epoch ASC
      LIMIT ?1`,
  )
    .bind(limit)
    .all<{ photo_id: string; r2_key: string }>();

  const todo = rows.results ?? [];
  for (const row of todo) {
    await env.TRANSCRIBE_QUEUE.send({
      segmentId: row.photo_id,
      key: row.r2_key,
      kind: "photo",
    });
  }

  const remaining = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM photos WHERE caption IS NULL AND status != 'failed'`,
  ).first<{ n: number }>();

  return json({ queued: todo.length, remaining: remaining?.n ?? 0 });
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
  const known = await env.DB.prepare(
    `SELECT segment_id, bytes, sha256 FROM segments`,
  ).all<{ segment_id: string; bytes: number | null; sha256: string | null }>();
  const rows = new Map(
    (known.results ?? []).map((r) => [r.segment_id, r]),
  );

  let objects = 0;
  let bytes = 0;
  let orphanCount = 0;
  let orphanBytes = 0;
  let checksummed = 0;
  const orphans: string[] = [];
  // An object whose size no longer matches the row is the shape silent
  // corruption takes: still present, still listed, quietly not what was
  // stored. Cheap to check on every listing, so there is no reason not to.
  const sizeMismatch: string[] = [];
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
      const row = rows.get(id);
      if (!row) {
        orphanCount += 1;
        orphanBytes += object.size;
        // Keys sort chronologically (audio/YYYY/MM/DD/…), so a bounded sample
        // is enough to see which days lost their index.
        if (orphans.length < 40) orphans.push(object.key);
      } else {
        if (row.sha256) checksummed += 1;
        if (row.bytes !== null && row.bytes !== object.size && sizeMismatch.length < 40) {
          sizeMismatch.push(`${object.key} db=${row.bytes} r2=${object.size}`);
        }
      }
    }
    if (!listed.truncated) {
      cursor = undefined;
      break;
    }
    cursor = listed.cursor;
  }

  const missing = [...rows.keys()].filter((id) => !seen.has(id));

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
    size_mismatches: sizeMismatch.length,
    size_mismatch_sample: sizeMismatch,
    // How much of the archive R2 verified against a device-computed digest.
    with_checksum: checksummed,
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

/**
 * Rebuilds the WAV header on segments that arrived without one.
 *
 * The recorder used to write 44 zero bytes and fill them in on close, so any
 * segment interrupted by a crash, an install or a flat battery reached R2 as
 * headerless PCM. Whisper rejects those with `AiError: 3010` and they sit at
 * status 'pending' forever — archived, intact, and unreadable by anything.
 *
 * Every field is recoverable: mono 16-bit is the only format this device has
 * ever captured, and the sample rate is in the row. The writer no longer
 * produces these, but the ones already stored are only broken in their first
 * 44 bytes and there is no reason to leave them that way.
 */
async function repairWav(env: Env, url: URL): Promise<Response> {
  const dry = url.searchParams.get("dry") === "1";
  const limit = Math.min(intParam(url, "limit") ?? 50, 200);

  const rows = await env.DB.prepare(
    `SELECT segment_id, r2_key, sample_rate FROM segments
      WHERE status = 'pending' AND r2_key LIKE '%.wav'
      ORDER BY started_epoch ASC
      LIMIT ?1`,
  )
    .bind(limit)
    .all<{ segment_id: string; r2_key: string; sample_rate: number | null }>();

  let repaired = 0;
  let alreadyValid = 0;
  let missing = 0;

  for (const row of rows.results ?? []) {
    const object = await env.BUCKET.get(row.r2_key);
    if (!object) {
      missing += 1;
      continue;
    }
    const bytes = new Uint8Array(await object.arrayBuffer());
    if (bytes.length <= 44) {
      missing += 1;
      continue;
    }

    const riff = String.fromCharCode(bytes[0], bytes[1], bytes[2], bytes[3]);
    if (riff === "RIFF") {
      alreadyValid += 1;
      continue;
    }

    if (dry) {
      repaired += 1;
      continue;
    }

    const rate = row.sample_rate ?? 16000;
    bytes.set(wavHeader(bytes.length - 44, rate), 0);
    await env.BUCKET.put(row.r2_key, bytes, {
      httpMetadata: { contentType: "audio/wav" },
    });
    await env.TRANSCRIBE_QUEUE.send({ segmentId: row.segment_id, key: row.r2_key });
    repaired += 1;
  }

  return json({
    examined: rows.results?.length ?? 0,
    repaired,
    already_valid: alreadyValid,
    missing,
    dry,
  });
}

/** Canonical 44-byte mono 16-bit PCM header. */
function wavHeader(dataBytes: number, sampleRate: number): Uint8Array {
  const header = new Uint8Array(44);
  const view = new DataView(header.buffer);
  const ascii = (offset: number, text: string) => {
    for (let i = 0; i < text.length; i += 1) header[offset + i] = text.charCodeAt(i);
  };

  ascii(0, "RIFF");
  view.setUint32(4, 36 + dataBytes, true);
  ascii(8, "WAVE");
  ascii(12, "fmt ");
  view.setUint32(16, 16, true);
  view.setUint16(20, 1, true); // PCM
  view.setUint16(22, 1, true); // mono
  view.setUint32(24, sampleRate, true);
  view.setUint32(28, sampleRate * 2, true); // byte rate
  view.setUint16(32, 2, true); // block align
  view.setUint16(34, 16, true);
  ascii(36, "data");
  view.setUint32(40, dataBytes, true);
  return header;
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
