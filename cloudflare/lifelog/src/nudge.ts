/**
 * 声かけ — whether the device may speak first, and what it says when it does.
 *
 * Everything that decides is here. The device reports only what it alone can
 * know — the Wi-Fi it is on, whether it is recording, how the room sounded in
 * the last two minutes, what the camera sees — and this module answers yes or
 * no. That split is the same one the interpreter's credential follows and it is
 * kept for the same reason: a gate the client evaluates is a gate the client
 * can skip. It also puts the schedule behind a deploy rather than behind an APK
 * install, and it puts every verdict in one table.
 *
 * Two phases, because the cheap checks are free and the good ones are not:
 *
 *   GET  /v1/nudge          the clock, the caps, and ten minutes of transcripts
 *   POST /v1/nudge/confirm  a photograph, two minutes of loudness, then the key
 *   POST /v1/nudge/outcome  whether anybody answered — the measurement half
 *
 * The camera on this device rides an audible motor arm, so nothing moves it
 * until the free checks have already passed.
 */

import { CAPTION_MODEL, captionInput, captionTextOf } from "./caption-model";

interface NudgeEnv {
  DB: D1Database;
  BUCKET: R2Bucket;
  AI: Ai;
  OPENAI_API_KEY?: string;
}

/**
 * Thresholds owned by index.ts and passed in rather than copied.
 *
 * `minSpeechRatio` is the same number `/v1/talk` counts spoken minutes with. If
 * this file kept its own copy, the two would answer "was there a conversation"
 * differently after the first time one of them was tuned.
 */
export interface NudgeThresholds {
  minSpeechRatio: number;
}

// ------------------------------------------------------------- the day ---

/** Minutes past midnight, JST. The device and the archive both work in it. */
const JST_OFFSET = 9 * 3600;

interface Slot {
  name: string;
  /** Minutes past midnight, JST. */
  at: number;
  /** How late the slot may still fire, in minutes after [at]. */
  window: number;
  /** The first thing said. Also shown on screen, so it is read as well as heard. */
  ask: string;
}

/**
 * The shape of a working day, guessed once and then measured.
 *
 * In source rather than in D1: a table would need a screen to edit it before it
 * earned its keep, and changing a time is one deploy. `nudge_log` records what
 * each slot actually achieved, which is the material for moving these.
 *
 * The windows are wide because the checks below refuse often — at 09:00 the
 * room may be empty, or someone may be on a call — and a slot with a narrow
 * window would simply never fire on the days it was most needed.
 */
const SLOTS: Slot[] = [
  {
    name: "morning",
    at: 9 * 60,
    window: 120,
    ask: "おはようございます。薬は飲みましたか。今日はどんな仕事の予定ですか。",
  },
  {
    name: "midday",
    at: 12 * 60 + 30,
    window: 120,
    ask: "仕事はどんな調子ですか。",
  },
  {
    name: "evening",
    at: 17 * 60 + 30,
    window: 120,
    ask: "今日の仕事は終われそうですか。",
  },
];

/** What the recurring check-in asks. It is the one that risks being a pest. */
const IDLE_ASK = "今、何をしていますか。";

/** The idle check-in runs between these, and never outside them. */
const IDLE_FROM = 10 * 60;
const IDLE_UNTIL = 21 * 60;

/** And no more often than this, measured from the last nudge of any kind. */
const IDLE_GAP_MIN = 120;

/**
 * Nothing at all between these hours.
 *
 * A separate rule from the slots, and deliberately blunt: the recording does
 * not stop at bedtime and neither does this poll, so the one thing that must be
 * impossible is a device that starts a conversation with a sleeping room.
 */
const QUIET_FROM = 22 * 60;
const QUIET_UNTIL = 8 * 60;

/** Nothing within this of the previous nudge, whichever slots they were. */
const MIN_GAP_MIN = 45;

/**
 * The cost ceiling, and the politeness ceiling — they happen to be the same
 * number. Four exchanges of ninety seconds is roughly $35 a year on the mini
 * tier, and five would be a device that talks more than it is talked to.
 */
const MAX_PER_DAY = 4;

/** A slot blocked by a conversation waits this long, this many times. */
const DEFER_MIN = 15;
const MAX_DEFERRALS = 3;

/** How far back the transcript check looks, and what counts as busy. */
const LOOKBACK_MIN = 10;
const BUSY_MINUTES = 2;

/**
 * Loudness above which a second counts as voiced, on the envelope's own scale —
 * raw RMS shifted down four bits. The same 32 the segment gate uses, chosen
 * against measured rooms: 11 quiet, 22 background activity, 152–221 speech.
 */
const VOICED_RMS = 32;

/**
 * Fraction of the last two minutes that has to be voiced before the room counts
 * as occupied by something already happening.
 *
 * A third, which is a lot: this test is the last one before the device speaks,
 * and it is looking for a conversation in progress rather than for a noise. It
 * cannot tell a conversation from a television — see the Unverified item in
 * BACKLOG.md — and the direction it fails in is silence, which is the right one.
 */
const BUSY_FRACTION = 0.33;

/** Hard stop on one exchange. The device enforces it too; this is the source. */
const MAX_SESSION_SECONDS = 180;

// --------------------------------------------------------------- model ---

const MODEL = "gpt-realtime-2.1-mini";
const CLIENT_SECRETS = "https://api.openai.com/v1/realtime/client_secrets";

/*
 * The presence check asks the same vision model that writes the captions
 * (CAPTION_MODEL), spoken to the same way.
 *
 * Until 2026-09-15 it asked llama-3.2-11b-vision, which answered はい to every
 * one of the 29 frames the log had judged -- an empty chair included -- so the
 * check never once refused. Put to scout, the same 29 came back with the
 * verdict on line 1 every time, and scout said いいえ to eight that had been
 * let through: seven of an empty chair, one of someone cooking with their back
 * turned across the kitchen. Looked at by eye, scout was right on all eight.
 */

/**
 * Asked for a verdict, not a caption.
 *
 * Two lines rather than one word: the reason is what makes the fortnight of
 * logs readable, because "いいえ" alone cannot be told from a model that
 * refused the image, and those want opposite fixes.
 */
export const PRESENCE_PROMPT =
  "この画像に、話しかけられる距離（おおよそ2メートル以内）にいて、" +
  "こちらを向いている人が写っていますか。" +
  "1行目に「はい」か「いいえ」だけを書き、2行目にその理由を20字程度で書いてください。";

/** Room for two short lines and no more. */
export const PRESENCE_MAX_TOKENS = 64;

/**
 * What the model is for, and what it is not for.
 *
 * Each line answers something. It asks and does not conclude, because a
 * check-in that summarises your day back to you is a report, not a
 * conversation. It refuses research and drafting, because that is the chat
 * screen's job and mixing them turns this into a worse version of it. And it
 * may disagree, because a voice that only ever agrees is the failure mode the
 * conversation-mode research spends most of its length on.
 */
const RULES = [
  "あなたは机の上に置かれた小さな端末の声です。日本語で話します。",
  "目的は短い声かけです。相手の様子をひとつかふたつ尋ねて、それで終わります。",
  "答えを要約したり、まとめたり、結論を出したりしないでください。",
  "調べ物・下書き・要約は引き受けないでください。それは別の画面の仕事です。",
  "相づちだけを返さないでください。理由を尋ねてよいし、違う見方を言ってもかまいません。",
  "相手が答えたら短く受け止めてください。続けて尋ねるのは多くても一度までにしてください。",
  "終わりは端末が決めます。締めくくるよう指示されたら、そこで質問をせずに終えてください。",
  "相手が忙しそうなら、引き止めずにすぐ終えてください。",
].join("\n");

/**
 * What the device asks for when it decides the check-in is over.
 *
 * Here rather than on the device because the words of this feature belong
 * together — the question that opens a session is chosen here, and so is the
 * sentence that closes it.
 *
 * It exists at all because of what happened without it. The device used to cut
 * the socket the moment it decided it was finished, which landed in the middle
 * of whatever the model had just asked: "それで今日は何を作ってるんですか?" and
 * then silence. Ending a conversation is a thing you say, not a thing you do.
 */
const FAREWELL =
  "会話を締めくくってください。新しい質問はせず、ひとことお礼か気遣いを述べて、" +
  "終わりであることがはっきり分かるように終えてください。";

// ------------------------------------------------------------ phase one ---

/**
 * Whether anything is due, decided on stored data alone.
 *
 * Answers `due: null` far more often than not, and says why in the same breath:
 * the device logs the reason, so an afternoon of silence is a query rather than
 * a guess.
 */
export async function nudgeDue(
  env: NudgeEnv,
  url: URL,
  thresholds: NudgeThresholds,
): Promise<Response> {
  const now = jst(Date.now());

  // Reported by the device because only the device knows them. Both are
  // preconditions rather than judgments — being away from home or having the
  // recording switched off are states in which this feature simply does not
  // apply, and neither is worth a row in the table.
  if (url.searchParams.get("home") !== "1") return nothing("not_home");
  if (url.searchParams.get("recording") !== "1") return nothing("not_recording");

  if (inQuietHours(now.minutes)) {
    return nothing("quiet_hours", minutesUntil(now.minutes, QUIET_UNTIL) * 60);
  }

  const today = await todaysLog(env, now.dayStart);
  const minted = today.filter((row) => row.verdict === "minted");

  if (minted.length >= MAX_PER_DAY) return nothing("daily_cap");

  const last = minted[minted.length - 1];
  if (last) {
    const since = now.epoch - last.decided_epoch;
    if (since < MIN_GAP_MIN * 60) {
      return nothing("too_soon", MIN_GAP_MIN * 60 - since);
    }
  }

  const slot = dueSlot(now, today, minted);
  if (!slot) return nothing("nothing_due");

  // The conversation check. Minutes in the last ten in which Whisper found real
  // words — the same test /v1/talk counts a spoken minute with, so the two
  // cannot disagree about what a conversation is.
  const spoken = await spokenMinutes(
    env,
    now.epoch - LOOKBACK_MIN * 60,
    thresholds.minSpeechRatio,
  );
  if (spoken >= BUSY_MINUTES) {
    const why = await defer(env, now, slot, today, "in_conversation");
    return nothing(why, DEFER_MIN * 60);
  }

  return json({
    due: slot.name,
    ask: slot.ask,
    need_presence: true,
    max_seconds: MAX_SESSION_SECONDS,
    spoken_minutes: spoken,
  });
}

/**
 * Which slot, if any, is inside its window and has not already had its turn.
 *
 * The timed three are once a day each and are finished by a `minted` or a
 * `refused` row. The idle one is not a slot in the same sense — it has no time
 * of its own, only a gap since the last nudge — so it is never finished, only
 * spaced out and, if nobody is answering it, dropped for the day.
 */
function dueSlot(now: Clock, today: LogRow[], minted: LogRow[]): Slot | null {
  for (const slot of SLOTS) {
    if (now.minutes < slot.at || now.minutes >= slot.at + slot.window) continue;
    const settled = today.some(
      (row) => row.slot === slot.name && row.verdict !== "deferred",
    );
    if (!settled) return slot;
  }

  if (now.minutes < IDLE_FROM || now.minutes >= IDLE_UNTIL) return null;

  const lastAny = minted[minted.length - 1];
  if (lastAny && now.epoch - lastAny.decided_epoch < IDLE_GAP_MIN * 60) return null;

  // Two in a row that nobody answered. Not a punishment for being out of the
  // room — an unreported outcome stays null and does not count — but a device
  // that has asked twice into a silence has learnt something about today.
  const idle = minted.filter((row) => row.slot === "idle");
  const recent = idle.slice(-2);
  if (recent.length === 2 && recent.every((row) => row.answered === 0)) return null;

  return { name: "idle", at: now.minutes, window: 1, ask: IDLE_ASK };
}

/**
 * Records that a due slot was blocked, and says whether it will be tried again.
 *
 * Written at most once every [DEFER_MIN] so that the count means elapsed time
 * rather than poll count — the device asks every two minutes, and a row per
 * poll would exhaust three deferrals in six.
 */
async function defer(
  env: NudgeEnv,
  now: Clock,
  slot: Slot,
  today: LogRow[],
  why: string,
): Promise<string> {
  const mine = today.filter((row) => row.slot === slot.name && row.verdict === "deferred");
  const latest = mine[mine.length - 1];
  if (latest && now.epoch - latest.decided_epoch < DEFER_MIN * 60) return why;

  // The idle check-in is never refused outright: it has no window to miss, and
  // the gap rule will bring it back when the room is quiet again.
  if (mine.length + 1 >= MAX_DEFERRALS && slot.name !== "idle") {
    await record(env, now, slot.name, "refused", why + "_gave_up");
    return why + "_gave_up";
  }

  await record(env, now, slot.name, "deferred", why);
  return why;
}

// ------------------------------------------------------------ phase two ---

/**
 * The two checks that cost something, and then the credential.
 *
 * The photograph arrives by id rather than in the body: the device has already
 * uploaded it through `PUT /v1/photos/{id}`, which is a route it holds anyway,
 * so the frame lands in the archive with a caption like every other frame and
 * this route reads it back out of R2. One upload path instead of two, and the
 * picture the presence check was made from is still there in a month when
 * somebody asks whether the check was any good.
 */
export async function confirmNudge(
  env: NudgeEnv,
  request: Request,
  thresholds: NudgeThresholds,
): Promise<Response> {
  if (!env.OPENAI_API_KEY) {
    return json(
      { error: "server missing OPENAI_API_KEY", hint: "wrangler secret put OPENAI_API_KEY" },
      500,
    );
  }

  let body: { slot?: string; photo_id?: string; envelope?: string; posture?: string };
  try {
    body = await request.json();
  } catch {
    return json({ error: "body must be JSON" }, 400);
  }

  const slot = SLOTS.find((s) => s.name === body.slot)
    ?? (body.slot === "idle" ? { name: "idle", at: 0, window: 1, ask: IDLE_ASK } : null);
  if (!slot) return json({ error: "unknown slot", slot: body.slot ?? null }, 400);

  const now = jst(Date.now());

  // Re-checked here rather than trusted from phase one. Minutes pass between
  // the two calls — the arm has to swing and the shutter has to fire — and the
  // caps are the part that must not be raced past.
  if (inQuietHours(now.minutes)) return refusal("quiet_hours");
  const today = await todaysLog(env, now.dayStart);
  const minted = today.filter((row) => row.verdict === "minted");
  if (minted.length >= MAX_PER_DAY) return refusal("daily_cap");

  // The fresh half of the conversation check. Segments reach D1 a minute or two
  // behind the room, so phase one was answering a slightly old question; this
  // one is answering it with audio that is seconds old.
  const busy = voicedFraction(body.envelope ?? "");
  if (busy !== null && busy >= BUSY_FRACTION) {
    const why = await defer(env, now, slot, today, "in_conversation");
    await noteBusy(env, now, slot.name, busy);
    return refusal(why, { voiced_fraction: round(busy) });
  }

  const presence = await lookForSomeone(env, body.photo_id ?? "");
  if (!presence.yes) {
    // A model that could not answer is not a room with nobody in it, and the
    // two must not be recorded as the same thing. Workers AI returned
    // "3040: Capacity temporarily exceeded" three times on the day this
    // shipped; treating those as refusals settled the midday slot and cost the
    // day a check-in that had a person sitting in front of the camera for it.
    if (presence.why !== "no_one_there") {
      const why = await defer(env, now, slot, today, presence.why);
      return refusal(why, { presence: presence.note });
    }

    await record(env, now, slot.name, "refused", presence.why, {
      photoId: body.photo_id ?? null,
      note: presence.note,
      presenceYes: 0,
      neurons: presence.neurons,
    });
    return refusal(presence.why, { presence: presence.note });
  }

  const material = await todaysMaterial(env, now.dayStart, thresholds.minSpeechRatio);
  const session = await mint(env, slot, material);
  if ("error" in session) {
    await record(env, now, slot.name, "refused", "mint_failed", {
      photoId: body.photo_id ?? null,
      note: session.detail.slice(0, 200),
      presenceYes: 1,
      neurons: presence.neurons,
    });
    return json(session.error, 502);
  }

  const id = await record(env, now, slot.name, "minted", "ok", {
    photoId: body.photo_id ?? null,
    note: presence.note,
    presenceYes: 1,
    neurons: presence.neurons,
  });

  return json({
    ok: true,
    id,
    slot: slot.name,
    opening: slot.ask,
    farewell: FAREWELL,
    client_secret: session.secret,
    expires_at: session.expiresAt,
    model: MODEL,
    url: `wss://api.openai.com/v1/realtime?model=${MODEL}`,
    max_seconds: MAX_SESSION_SECONDS,
  });
}

/**
 * Runs the frame past the vision model.
 *
 * A missing photograph is a refusal rather than an error: the arm may have been
 * busy, the shutter may have failed, and the answer to "may I speak" in that
 * case is no. Nothing here should ever fall open.
 */
async function lookForSomeone(
  env: NudgeEnv,
  photoId: string,
): Promise<{ yes: boolean; why: string; note: string | null; neurons: number | null }> {
  if (!/^[A-Za-z0-9_-]{8,64}$/.test(photoId)) {
    return { yes: false, why: "no_frame", note: null, neurons: null };
  }

  const row = await env.DB.prepare(`SELECT r2_key FROM photos WHERE photo_id = ?1`)
    .bind(photoId)
    .first<{ r2_key: string }>();
  if (!row) return { yes: false, why: "no_frame", note: null, neurons: null };

  const object = await env.BUCKET.get(row.r2_key);
  if (!object) return { yes: false, why: "no_frame", note: null, neurons: null };

  const bytes = new Uint8Array(await object.arrayBuffer());
  let result: any;
  try {
    result = await env.AI.run(
      CAPTION_MODEL as any,
      captionInput(bytes, PRESENCE_PROMPT, PRESENCE_MAX_TOKENS) as any,
    );
  } catch (error) {
    return {
      yes: false,
      why: "presence_check_failed",
      note: String(error).slice(0, 200),
      neurons: null,
    };
  }

  const text = captionTextOf(result);
  const neurons = Number(result?.usage?.neurons) || null;

  // The first line is the verdict. Anything that is not a yes is a no,
  // including an empty answer and a refusal to look at the picture.
  const first = text.split("\n")[0] ?? "";
  const yes = /はい|います|yes/i.test(first) && !/いいえ|いません/.test(first);

  return {
    yes,
    why: yes ? "ok" : "no_one_there",
    note: text.replace(/\s+/g, " ").slice(0, 200),
    neurons,
  };
}

/** Asks OpenAI for a client secret with the session already described. */
async function mint(
  env: NudgeEnv,
  slot: Slot,
  material: string,
): Promise<
  | { secret: string; expiresAt: number | null }
  | { error: Record<string, unknown>; detail: string }
> {
  const instructions = [
    RULES,
    "",
    `最初にあなたから話しかけてください。最初の一言はこれです: ${slot.ask}`,
    material
      ? "\n参考までに、今日この部屋で話されたことの抜粋です。" +
        "相手が触れなければ無理に持ち出さないでください。\n" +
        material
      : "",
  ].join("\n");

  let response: Response;
  try {
    response = await fetch(CLIENT_SECRETS, {
      method: "POST",
      headers: {
        authorization: `Bearer ${env.OPENAI_API_KEY}`,
        "content-type": "application/json",
      },
      body: JSON.stringify({
        session: {
          type: "realtime",
          model: MODEL,
          instructions,
          audio: {
            input: {
              // Same reasoning as the interpreter: this device sits on a desk
              // and hears a room, not a headset.
              noise_reduction: { type: "far_field" },
              transcription: { model: "gpt-realtime-whisper" },
              turn_detection: { type: "server_vad" },
            },
          },
        },
      }),
    });
  } catch (error) {
    return {
      error: { error: "could not reach OpenAI", detail: String(error).slice(0, 200) },
      detail: String(error),
    };
  }

  const text = await response.text();
  if (!response.ok) {
    // Passed through rather than flattened, for the reason interpret.ts gives:
    // a 401 means the key and a 400 means this request shape has drifted from
    // the API, and a device told only "failed" cannot tell those apart.
    return {
      error: { error: "OpenAI refused", status: response.status, detail: text.slice(0, 400) },
      detail: text,
    };
  }

  let parsed: any;
  try {
    parsed = JSON.parse(text);
  } catch {
    return { error: { error: "OpenAI returned unparseable JSON" }, detail: text.slice(0, 200) };
  }

  const secret = parsed?.value ?? parsed?.client_secret?.value ?? null;
  if (!secret) {
    return { error: { error: "OpenAI returned no client secret" }, detail: text.slice(0, 200) };
  }
  return { secret, expiresAt: parsed?.expires_at ?? null };
}

// ------------------------------------------------------------- outcome ---

/**
 * Whether anybody answered.
 *
 * The device reports this when the screen closes, and it is the only part of
 * the feature that cannot be measured from the archive: a minute of the owner
 * talking looks the same whether it was an answer or a phone call that happened
 * to start. Missing is allowed and means unknown, which is why the idle rule
 * above counts an explicit 0 rather than a falsy value.
 */
export async function nudgeOutcome(env: NudgeEnv, url: URL): Promise<Response> {
  const id = Number(url.searchParams.get("id"));
  if (!Number.isInteger(id) || id <= 0) return json({ error: "id must be a positive integer" }, 400);

  const answered = url.searchParams.get("answered") === "1" ? 1 : 0;
  const seconds = Number(url.searchParams.get("seconds"));

  const result = await env.DB.prepare(
    `UPDATE nudge_log SET answered = ?1, session_secs = ?2 WHERE id = ?3`,
  )
    .bind(answered, Number.isFinite(seconds) ? Math.round(seconds) : null, id)
    .run();

  return json({ id, answered, updated: result.meta.changes ?? 0 });
}

/**
 * The last fortnight of verdicts, for a person deciding whether the times are
 * right. Not on the device's route list: this is a page-and-laptop question.
 */
export async function nudgeHistory(env: NudgeEnv, url: URL): Promise<Response> {
  const days = Math.min(Math.max(Number(url.searchParams.get("days")) || 14, 1), 90);
  const from = Math.floor(Date.now() / 1000) - days * 86_400;

  const rows = await env.DB.prepare(
    `SELECT id, slot, decided_at, verdict, why, photo_id, presence_note,
            presence_yes, neurons, answered, session_secs
       FROM nudge_log WHERE decided_epoch >= ?1 ORDER BY decided_epoch DESC`,
  )
    .bind(from)
    .all();

  const all = rows.results ?? [];
  const spoke = all.filter((r: any) => r.verdict === "minted");
  return json({
    days,
    slots: SLOTS.map((s) => ({ name: s.name, at: clock(s.at), ask: s.ask })),
    idle: { from: clock(IDLE_FROM), until: clock(IDLE_UNTIL), gap_min: IDLE_GAP_MIN },
    spoken: spoke.length,
    answered: spoke.filter((r: any) => r.answered === 1).length,
    entries: all,
  });
}

// --------------------------------------------------------------- shared ---

interface Clock {
  epoch: number;
  /** Minutes past midnight, JST. */
  minutes: number;
  /** Epoch seconds of local midnight. */
  dayStart: number;
}

interface LogRow {
  slot: string;
  verdict: string;
  why: string | null;
  decided_epoch: number;
  answered: number | null;
}

function jst(nowMs: number): Clock {
  const epoch = Math.floor(nowMs / 1000);
  const shifted = epoch + JST_OFFSET;
  const dayIndex = Math.floor(shifted / 86_400);
  return {
    epoch,
    minutes: Math.floor((shifted - dayIndex * 86_400) / 60),
    dayStart: dayIndex * 86_400 - JST_OFFSET,
  };
}

function inQuietHours(minutes: number): boolean {
  return minutes >= QUIET_FROM || minutes < QUIET_UNTIL;
}

function minutesUntil(from: number, to: number): number {
  return from <= to ? to - from : 24 * 60 - from + to;
}

async function todaysLog(env: NudgeEnv, dayStart: number): Promise<LogRow[]> {
  const rows = await env.DB.prepare(
    `SELECT slot, verdict, why, decided_epoch, answered
       FROM nudge_log WHERE decided_epoch >= ?1 ORDER BY decided_epoch ASC`,
  )
    .bind(dayStart)
    .all<LogRow>();
  return rows.results ?? [];
}

/** Minutes since [from] in which Whisper found real words. */
async function spokenMinutes(
  env: NudgeEnv,
  from: number,
  minSpeechRatio: number,
): Promise<number> {
  const row = await env.DB.prepare(
    `SELECT COUNT(*) AS n FROM segments
      WHERE kind = 'lifelog' AND status = 'transcribed' AND stock_phrase = 0
        AND speech_ratio >= ?2 AND started_epoch >= ?1`,
  )
    .bind(from, minSpeechRatio)
    .first<{ n: number }>();
  return row?.n ?? 0;
}

/**
 * Today's transcripts, most recent last, for priming the session.
 *
 * Text input is cached at a fraction of the audio price, so this is nearly free
 * — and it is what makes the midday question able to refer to the morning
 * answer, which is the whole reason a device that already holds the recording
 * is the right place for this.
 */
async function todaysMaterial(
  env: NudgeEnv,
  dayStart: number,
  minSpeechRatio: number,
): Promise<string> {
  const rows = await env.DB.prepare(
    `SELECT started_at, transcript FROM segments
      WHERE kind = 'lifelog' AND status = 'transcribed' AND stock_phrase = 0
        AND speech_ratio >= ?2 AND started_epoch >= ?1
        AND transcript IS NOT NULL AND transcript != ''
      ORDER BY started_epoch DESC LIMIT 30`,
  )
    .bind(dayStart, minSpeechRatio)
    .all<{ started_at: string; transcript: string }>();

  // The query returns newest first and the prompt wants oldest first, so the
  // lines are prepended. Bounded while doing it, because an unbounded prompt is
  // an unbounded bill — and when something has to be dropped it should be the
  // oldest line, not the one from ten minutes ago.
  let out = "";
  for (const row of rows.results ?? []) {
    const line = `${row.started_at.slice(11, 16)} ${row.transcript.replace(/\s+/g, " ")}`;
    if (out.length + line.length > 2000) break;
    out = line + "\n" + out;
  }
  return out.trim();
}

/**
 * Fraction of the supplied envelope above the voiced threshold, or null when
 * there is no envelope to read.
 *
 * The bytes are the same per-second loudness the device writes beside every
 * segment — raw RMS shifted down four bits — so this is the identical
 * measurement the archive gate uses, taken two minutes fresher.
 */
function voicedFraction(base64: string): number | null {
  if (!base64) return null;
  let raw: string;
  try {
    raw = atob(base64);
  } catch {
    return null;
  }
  if (raw.length === 0) return null;

  let voiced = 0;
  for (let i = 0; i < raw.length; i += 1) {
    if (raw.charCodeAt(i) >= VOICED_RMS) voiced += 1;
  }
  return voiced / raw.length;
}

/**
 * Keeps the measured fraction beside the deferral it caused.
 *
 * Narrowed to deferrals on purpose. Deferrals are only written once every
 * fifteen minutes, so the most recent row for a slot can easily be the `minted`
 * one from earlier in the day — and writing a loudness reading onto the record
 * of a conversation that actually happened would be a lie in the one table this
 * feature is supposed to be judged from.
 */
async function noteBusy(env: NudgeEnv, now: Clock, slot: string, fraction: number) {
  await env.DB.prepare(
    `UPDATE nudge_log SET presence_note = ?1
      WHERE id = (SELECT MAX(id) FROM nudge_log
                   WHERE slot = ?2 AND decided_epoch >= ?3 AND verdict = 'deferred')`,
  )
    .bind(`voiced_fraction=${round(fraction)}`, slot, now.dayStart)
    .run();
}

async function record(
  env: NudgeEnv,
  now: Clock,
  slot: string,
  verdict: string,
  why: string,
  extra: {
    photoId?: string | null;
    note?: string | null;
    presenceYes?: number | null;
    neurons?: number | null;
  } = {},
): Promise<number> {
  const result = await env.DB.prepare(
    `INSERT INTO nudge_log
       (slot, decided_at, decided_epoch, verdict, why, photo_id, presence_note,
        presence_yes, neurons)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)`,
  )
    .bind(
      slot,
      new Date(now.epoch * 1000).toISOString(),
      now.epoch,
      verdict,
      why,
      extra.photoId ?? null,
      extra.note ?? null,
      extra.presenceYes ?? null,
      extra.neurons ?? null,
    )
    .run();
  return Number(result.meta.last_row_id ?? 0);
}

function clock(minutes: number): string {
  return `${String(Math.floor(minutes / 60)).padStart(2, "0")}:${String(minutes % 60).padStart(2, "0")}`;
}

function round(value: number): number {
  return Math.round(value * 100) / 100;
}

function nothing(why: string, retryAfterSeconds?: number): Response {
  return json({ due: null, why, retry_after_s: retryAfterSeconds ?? null });
}

/**
 * A refusal at phase two is a 200.
 *
 * It is an answer to the question that was asked, not a rejection of the
 * credential that asked it, and a 403 here would put "nobody was in the room"
 * in the same bucket as "this token is dead".
 */
function refusal(why: string, extra: Record<string, unknown> = {}): Response {
  return json({ ok: false, why, ...extra });
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
