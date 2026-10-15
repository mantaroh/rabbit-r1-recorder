/**
 * 記録の削除。R2 の実体、D1 の行、検索索引（DELETE トリガー）をまとめて消し、
 * 再送で戻ってこないように ID と時間帯を残す。
 *
 * 消す順番は R2 → D1。/v1/admin/reindex は「R2 にあって D1 に行がない音声」を
 * 行ごと復元するので、D1 を先に消して R2 の削除に失敗すると、次の reindex で
 * 戻ってくる。R2 を先に消しておけば、途中で失敗しても残るのは中身のない行で、
 * もう一度消せば片付く。
 */

export type MediaKind = "desktop" | "camera" | "audio";
export const MEDIA_KINDS: readonly MediaKind[] = ["desktop", "camera", "audio"];

export interface DeleteEnv {
  DB: D1Database;
  BUCKET: R2Bucket;
}

/** 写真の出所から、削除の種類へ。R1 と眼鏡はどちらもカメラ。 */
export function photoKind(source: string | null | undefined): MediaKind {
  return source === "desktop" ? "desktop" : "camera";
}

/**
 * 1回の時間帯削除で選べる長さの上限。
 * 日ビューから1日分を消すのは許すが、API を叩き間違えて全期間が消えるのは防ぐ。
 */
export const MAX_RANGE_SEC = 24 * 3600;

/** 1回の呼び出しで消す件数。残りは呼び出し側が繰り返す。 */
export const MAX_PER_CALL = 500;

/** D1 の1文あたりのバインド数の上限（100）に収まる大きさ。 */
const IN_CHUNK = 50;

export function chunk<T>(items: T[], size: number): T[][] {
  const out: T[][] = [];
  for (let i = 0; i < items.length; i += size) out.push(items.slice(i, i + size));
  return out;
}

/** 時間帯の指定を検証して UTC 秒にする。読めなければ理由を返す。 */
export function parseRange(
  from: string | null,
  to: string | null,
): { fromEpoch: number; toEpoch: number } | { error: string } {
  const f = Date.parse(from ?? "");
  const t = Date.parse(to ?? "");
  if (Number.isNaN(f) || Number.isNaN(t)) return { error: "from と to は ISO 8601 の時刻にしてください" };
  const fromEpoch = Math.floor(f / 1000);
  const toEpoch = Math.floor(t / 1000);
  if (toEpoch <= fromEpoch) return { error: "to は from より後にしてください" };
  if (toEpoch - fromEpoch > MAX_RANGE_SEC) return { error: "一度に指定できるのは24時間までです" };
  return { fromEpoch, toEpoch };
}

/** kinds=desktop,camera,audio を読む。知らないものは無視し、空なら null。 */
export function parseKinds(raw: string | null): MediaKind[] | null {
  const kinds = (raw ?? "").split(",").map((s) => s.trim())
    .filter((s): s is MediaKind => (MEDIA_KINDS as readonly string[]).includes(s));
  return kinds.length ? [...new Set(kinds)] : null;
}

/** この ID、またはこの時刻のこの種類が、削除済みか。PUT の入口で使う。 */
export async function isDeleted(env: DeleteEnv, kind: MediaKind, id: string, epoch: number): Promise<boolean> {
  const hit = await env.DB.prepare(
    `SELECT 1 FROM deleted_media WHERE id = ?1
     UNION ALL
     SELECT 1 FROM deleted_ranges WHERE kind = ?2 AND from_epoch <= ?3 AND to_epoch > ?3
     LIMIT 1`,
  )
    .bind(id, kind, epoch)
    .first();
  return hit !== null;
}

interface PhotoRow { photo_id: string; r2_key: string; caption_r2_key: string | null; source: string | null }
interface SegmentRow { segment_id: string; r2_key: string }

/** 消す対象。写真と音声を分けて持つ。 */
export interface Targets { photos: PhotoRow[]; segments: SegmentRow[] }

/** 行が持つ R2 のキー。キャプション用の縮小版も含める。 */
export function keysOf(targets: Targets): string[] {
  const keys: string[] = [];
  for (const p of targets.photos) {
    keys.push(p.r2_key);
    if (p.caption_r2_key) keys.push(p.caption_r2_key);
  }
  for (const s of targets.segments) keys.push(s.r2_key);
  return keys;
}

/** ID を指定して対象の行を引く。無い ID は結果に入らない。 */
export async function findById(env: DeleteEnv, kind: "photo" | "audio", ids: string[]): Promise<Targets> {
  const out: Targets = { photos: [], segments: [] };
  for (const part of chunk(ids, IN_CHUNK)) {
    const marks = part.map((_, i) => `?${i + 1}`).join(",");
    if (kind === "photo") {
      const r = await env.DB.prepare(
        `SELECT photo_id, r2_key, caption_r2_key, source FROM photos WHERE photo_id IN (${marks})`,
      ).bind(...part).all<PhotoRow>();
      out.photos.push(...(r.results ?? []));
    } else {
      const r = await env.DB.prepare(
        `SELECT segment_id, r2_key FROM segments WHERE segment_id IN (${marks})`,
      ).bind(...part).all<SegmentRow>();
      out.segments.push(...(r.results ?? []));
    }
  }
  return out;
}

/** 時間帯と種類で対象の行を引く。多すぎる分は次の呼び出しに回す。 */
export async function findInRange(
  env: DeleteEnv,
  fromEpoch: number,
  toEpoch: number,
  kinds: MediaKind[],
  limit = MAX_PER_CALL,
): Promise<Targets & { remaining: number }> {
  const sources: string[] = [];
  if (kinds.includes("desktop")) sources.push("'desktop'");
  if (kinds.includes("camera")) sources.push("'r1'", "'glasses'");

  let photos: PhotoRow[] = [];
  let photoCount = 0;
  if (sources.length) {
    // 古い行は source が NULL のことがあり、それは R1。
    const where = `taken_epoch >= ?1 AND taken_epoch < ?2 AND COALESCE(source, 'r1') IN (${sources.join(",")})`;
    photoCount = (await env.DB.prepare(`SELECT COUNT(*) AS n FROM photos WHERE ${where}`)
      .bind(fromEpoch, toEpoch).first<{ n: number }>())?.n ?? 0;
    photos = (await env.DB.prepare(
      `SELECT photo_id, r2_key, caption_r2_key, source FROM photos WHERE ${where} ORDER BY taken_epoch LIMIT ?3`,
    ).bind(fromEpoch, toEpoch, limit).all<PhotoRow>()).results ?? [];
  }

  let segments: SegmentRow[] = [];
  let segmentCount = 0;
  if (kinds.includes("audio")) {
    // 日ビューに出ない kind='query'（呼びかけ）もその時間帯の音声なので一緒に消す。
    const where = `started_epoch >= ?1 AND started_epoch < ?2`;
    segmentCount = (await env.DB.prepare(`SELECT COUNT(*) AS n FROM segments WHERE ${where}`)
      .bind(fromEpoch, toEpoch).first<{ n: number }>())?.n ?? 0;
    segments = (await env.DB.prepare(
      `SELECT segment_id, r2_key FROM segments WHERE ${where} ORDER BY started_epoch LIMIT ?3`,
    ).bind(fromEpoch, toEpoch, Math.max(0, limit - photos.length)).all<SegmentRow>()).results ?? [];
  }

  return {
    photos,
    segments,
    remaining: photoCount + segmentCount - photos.length - segments.length,
  };
}

/** R2 → D1 の順で消し、ID を削除済みとして残す。消した件数を返す。 */
export async function deleteTargets(env: DeleteEnv, targets: Targets, now = new Date()): Promise<{ photos: number; segments: number }> {
  // R2 の delete は1回1000キーまで。無いキーを渡しても失敗しない。
  for (const keys of chunk(keysOf(targets), 1000)) await env.BUCKET.delete(keys);

  const at = now.toISOString();
  const statements: D1PreparedStatement[] = [];
  const tomb = env.DB.prepare(`INSERT OR IGNORE INTO deleted_media (id, kind, deleted_at) VALUES (?1, ?2, ?3)`);
  for (const part of chunk(targets.photos, IN_CHUNK)) {
    const marks = part.map((_, i) => `?${i + 1}`).join(",");
    statements.push(env.DB.prepare(`DELETE FROM photos WHERE photo_id IN (${marks})`).bind(...part.map((p) => p.photo_id)));
  }
  for (const part of chunk(targets.segments, IN_CHUNK)) {
    const marks = part.map((_, i) => `?${i + 1}`).join(",");
    statements.push(env.DB.prepare(`DELETE FROM segments WHERE segment_id IN (${marks})`).bind(...part.map((s) => s.segment_id)));
  }
  for (const p of targets.photos) statements.push(tomb.bind(p.photo_id, photoKind(p.source), at));
  for (const s of targets.segments) statements.push(tomb.bind(s.segment_id, "audio", at));
  if (statements.length) await env.DB.batch(statements);

  return { photos: targets.photos.length, segments: targets.segments.length };
}

/**
 * 時間帯を削除済みとして残す。中身を消す前に書くので、その間に届いた再送も断れる。
 * 500件を超える時間帯は同じ指定で何度も呼ばれるので、同じ行は重ねない。
 */
export async function recordRange(env: DeleteEnv, kinds: MediaKind[], fromEpoch: number, toEpoch: number, now = new Date()): Promise<void> {
  const insert = env.DB.prepare(
    `INSERT INTO deleted_ranges (kind, from_epoch, to_epoch, deleted_at)
     SELECT ?1, ?2, ?3, ?4
      WHERE NOT EXISTS (SELECT 1 FROM deleted_ranges WHERE kind = ?1 AND from_epoch = ?2 AND to_epoch = ?3)`,
  );
  await env.DB.batch(kinds.map((k) => insert.bind(k, fromEpoch, toEpoch, now.toISOString())));
}
