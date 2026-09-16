# 写真の SSE-C 暗号化 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 反映後に lifelog の R2 へ置く写真（原寸と PC の縮小版）を SSE-C で暗号化し、Worker 経由の読み出しでは今までどおり JPEG として扱えるようにする。

**Architecture:** 鍵は Worker の secret `PHOTO_SSEC_KEY`。R2 バインディングの `put` / `get` に `ssecKey` を渡す。どの写真が暗号化されているかは D1 の `photos.sealed` で持ち、鍵と `sealed` の扱いは新しい `src/photo-store.ts` に閉じ込める。

**Tech Stack:** Cloudflare Workers、R2（SSE-C）、D1、TypeScript、Vitest（`node:sqlite` で本物の migration を流すテスト）

**Spec:** `docs/superpowers/specs/2026-09-16-photo-ssec-design.md`

## Global Constraints

- 作業ディレクトリは `cloudflare/lifelog/`。テストは `npm test`、型は `npm run typecheck`。
- secret 名は `PHOTO_SSEC_KEY`。値は 64 桁の hex。
- D1 の列は `photos.sealed INTEGER NOT NULL DEFAULT 0`。migration は `0021_photo_sealed.sql` で、改行は LF。
- 対象は `photos.r2_key` と `photos.caption_r2_key` の 2 つだけ。音声と地図タイルは変えない。
- 既存の写真（`sealed = 0`）は、平文のまま今までどおり読めること。
- 鍵が未設定なら、平文で置いて `sealed = 0` にする。`sealed = 1` を鍵なしで読もうとしたらエラーにする。
- `wrangler secret put`、本番 D1 への migration、`wrangler deploy`、本番 R2 への書き込みは、どれも実行前に利用者の承認を取る。承認前に `npx wrangler whoami` で対象アカウントを確かめて伝える。
- コミットメッセージに AI やツールの名前を入れない。署名の trailer も付けない。

---

## ファイル構成

| ファイル | 役割 |
| --- | --- |
| `src/photo-store.ts`（新規） | 鍵の検証、`put` 用オプション、`sealed` に応じた `get`、鍵がないときの例外 |
| `migrations/0021_photo_sealed.sql`（新規） | `photos.sealed` の追加 |
| `src/index.ts` | `Env` に secret を追加。写真の受け取り・配信・キャプション生成・キャプションのデバッグを `photo-store.ts` 経由にする |
| `src/nudge.ts` | `NudgeEnv` に secret を追加。在室判定の画像読み出しを `photo-store.ts` 経由にする |
| `test/photo-store.test.ts`（新規） | `photo-store.ts` の単体テスト |
| `test/photo-ssec.test.ts`（新規） | 本物の migration を流した D1 で、受け取り・配信・キャプション生成を通すテスト |

---

### Task 1: SSE-C の挙動を確かめる（使い捨ての調査）

ここで作るものはコミットしない。結果だけを、この計画の末尾「調査結果」に書く。
結果によって Task 3 と Task 4 の分岐が決まる。

**Files:**
- Create: `<scratchpad>/ssec-probe/probe.ts`
- Create: `<scratchpad>/ssec-probe/wrangler.jsonc`

**Interfaces:**
- Consumes: なし
- Produces: 調査結果 4 項目（sha256 併用、鍵なし get、Range 併用、miniflare の対応）

- [ ] **Step 1: 調査用 Worker を書く**

`probe.ts`:

```ts
// 使い捨て。SSE-C の挙動を 4 点だけ確かめる。
interface Env { BUCKET: R2Bucket }

const KEY = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
const OBJECT = "probe/ssec/probe.bin";

async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", bytes);
  return [...new Uint8Array(digest)].map(b => b.toString(16).padStart(2, "0")).join("");
}

async function attempt<T>(fn: () => Promise<T>): Promise<unknown> {
  try { return { ok: true, value: await fn() }; }
  catch (error) { return { ok: false, error: String(error).slice(0, 300) }; }
}

export default {
  async fetch(_request: Request, env: Env): Promise<Response> {
    const body = new TextEncoder().encode("0123456789abcdefghij");
    const report: Record<string, unknown> = {};

    report.put_with_sha256 = await attempt(async () =>
      (await env.BUCKET.put(OBJECT, body, { ssecKey: KEY, sha256: await sha256Hex(body) }))?.size);
    report.put_with_wrong_sha256 = await attempt(async () =>
      (await env.BUCKET.put(OBJECT + ".bad", body, { ssecKey: KEY, sha256: "0".repeat(64) }))?.size);
    report.get_with_key = await attempt(async () =>
      await (await env.BUCKET.get(OBJECT, { ssecKey: KEY }))?.text());
    report.get_without_key = await attempt(async () =>
      await (await env.BUCKET.get(OBJECT))?.text());
    report.get_range_with_key = await attempt(async () => {
      const o = await env.BUCKET.get(OBJECT, { ssecKey: KEY, range: { offset: 5, length: 5 } });
      return { text: await o?.text(), range: o?.range, size: o?.size };
    });
    report.head_without_key = await attempt(async () => (await env.BUCKET.head(OBJECT))?.size);

    await env.BUCKET.delete([OBJECT, OBJECT + ".bad"]);
    return Response.json(report);
  },
};
```

`wrangler.jsonc`（本番と同じバケットを指す。書き込むのは `probe/ssec/` の下だけで、最後に消す）:

```jsonc
{
  "name": "ssec-probe",
  "main": "probe.ts",
  "compatibility_date": "2026-08-01",
  "workers_dev": false,
  "r2_buckets": [{ "binding": "BUCKET", "bucket_name": "r1-lifelog-audio" }]
}
```

- [ ] **Step 2: ローカル（miniflare）で動かす**

承認は不要（ローカルの R2 だけを使う）。

Run（`<scratchpad>/ssec-probe` で）: `npx --prefix <repo>/cloudflare/lifelog wrangler dev --local --port 8799` をバックグラウンドで起動し、`curl -s localhost:8799` の結果を控えてから停止する。
Expected: JSON が返る。`get_without_key` が失敗するかどうかで、miniflare が SSE-C を扱うかがわかる。

- [ ] **Step 3: 本番バケットで動かす（要承認）**

`npx wrangler whoami` でアカウントを確かめ、「本番バケット `r1-lifelog-audio` の `probe/ssec/` に 2 オブジェクトを置き、最後に消す」ことを伝えて承認を取る。

Run: `npx --prefix <repo>/cloudflare/lifelog wrangler dev --remote --port 8799` をバックグラウンドで起動し、`curl -s localhost:8799` の結果を控えてから停止する。
Expected:
- `put_with_sha256.ok = true`
- `put_with_wrong_sha256.ok = false`
- `get_with_key.value = "0123456789abcdefghij"`
- `get_without_key.ok = false`
- `get_range_with_key.value.text = "56789"`

- [ ] **Step 4: 結果を記録し、分岐を決める**

この計画の末尾「調査結果」に 4 項目を書く。次のどれかに当たったら、先に進まず利用者に相談する。

| 結果 | 対応 |
| --- | --- |
| `get_without_key` が成功した | 目的を満たさない。方式から見直す |
| `put_with_sha256` が失敗した | Task 3 の「分岐 B」を使う |
| `put_with_wrong_sha256` が成功した（照合されていない） | Task 3 の「分岐 B」を使う |
| `get_range_with_key` が失敗した | Task 4 の「分岐 R」を使う |

- [ ] **Step 5: 後片付け**

`wrangler dev` のプロセスが残っていないことを確かめる。`npx wrangler r2 object get r1-lifelog-audio/probe/ssec/probe.bin --remote` が「見つからない」で終わることを確かめる（読み取りのみ）。

---

### Task 2: `photo-store.ts`

**Files:**
- Create: `cloudflare/lifelog/src/photo-store.ts`
- Test: `cloudflare/lifelog/test/photo-store.test.ts`

**Interfaces:**
- Consumes: なし
- Produces:
  - `interface PhotoStoreEnv { BUCKET: R2Bucket; PHOTO_SSEC_KEY?: string }`
  - `class PhotoKeyMissing extends Error`
  - `photoPutOptions(env: PhotoStoreEnv): { sealed: boolean; ssec: { ssecKey?: string } }`
  - `getPhoto(env: PhotoStoreEnv, key: string, sealed: boolean, options?: R2GetOptions): Promise<R2ObjectBody | null>`

- [ ] **Step 1: 失敗するテストを書く**

`test/photo-store.test.ts`:

```ts
import { describe, expect, it, vi } from "vitest";
import { getPhoto, photoPutOptions, PhotoKeyMissing } from "../src/photo-store";

const KEY = "ab".repeat(32);

function env(key?: string) {
  return { BUCKET: { get: vi.fn(async () => null) } as any, PHOTO_SSEC_KEY: key };
}

describe("photoPutOptions", () => {
  it("seals when a key is configured", () => {
    expect(photoPutOptions(env(KEY))).toEqual({ sealed: true, ssec: { ssecKey: KEY } });
  });
  it("stores in the clear when no key is configured", () => {
    expect(photoPutOptions(env())).toEqual({ sealed: false, ssec: {} });
    expect(photoPutOptions(env("  "))).toEqual({ sealed: false, ssec: {} });
  });
  it("refuses a key R2 would not accept rather than storing in the clear", () => {
    expect(() => photoPutOptions(env("abc"))).toThrow(/64 hex/);
    expect(() => photoPutOptions(env("zz".repeat(32)))).toThrow(/64 hex/);
  });
});

describe("getPhoto", () => {
  it("reads a clear photo without a key, keeping the caller's options", async () => {
    const e = env(KEY);
    const range = { offset: 1, length: 2 };
    await getPhoto(e, "photo/a.jpg", false, { range });
    expect(e.BUCKET.get).toHaveBeenCalledWith("photo/a.jpg", { range });
  });
  it("reads a sealed photo with the key", async () => {
    const e = env(KEY);
    await getPhoto(e, "photo/a.jpg", true);
    expect(e.BUCKET.get).toHaveBeenCalledWith("photo/a.jpg", { ssecKey: KEY });
  });
  it("fails a sealed read when the key is gone, without asking R2", async () => {
    const e = env();
    await expect(getPhoto(e, "photo/a.jpg", true)).rejects.toBeInstanceOf(PhotoKeyMissing);
    expect(e.BUCKET.get).not.toHaveBeenCalled();
  });
});
```

- [ ] **Step 2: 失敗を確かめる**

Run: `npx vitest run test/photo-store.test.ts`
Expected: FAIL（`../src/photo-store` が見つからない）

- [ ] **Step 3: 実装する**

`src/photo-store.ts`:

```ts
/**
 * Where photographs meet R2's customer-provided-key encryption (SSE-C).
 *
 * The point is that someone looking at the bucket directly -- the dashboard,
 * an R2 API token, a bucket made public by mistake -- gets bytes that are not
 * a picture. The Worker holds the key and still reads every photo, because
 * captioning and the nudge presence check both need to.
 *
 * Which photos are sealed lives in D1 (`photos.sealed`) rather than being
 * discovered by trying: a key handed to R2 for an object stored without one
 * is an error, so guessing would double every read of the older photos.
 */

export interface PhotoStoreEnv {
  BUCKET: R2Bucket;
  /** 64 hex characters. Absent means new photos are stored in the clear. */
  PHOTO_SSEC_KEY?: string;
}

/** A sealed photo was asked for and the Worker has no key to open it with. */
export class PhotoKeyMissing extends Error {
  constructor() {
    super("photo key missing");
    this.name = "PhotoKeyMissing";
  }
}

function configuredKey(env: PhotoStoreEnv): string | null {
  const key = env.PHOTO_SSEC_KEY?.trim() ?? "";
  return key === "" ? null : key;
}

/**
 * The options a new photo is stored with, and whether they seal it.
 *
 * A malformed key throws instead of falling back to the clear: the upload
 * fails and the device retries, which is noticed. Quietly storing in the clear
 * after a key was meant to be set is not.
 */
export function photoPutOptions(env: PhotoStoreEnv): { sealed: boolean; ssec: { ssecKey?: string } } {
  const key = configuredKey(env);
  if (key === null) return { sealed: false, ssec: {} };
  if (!/^[0-9a-f]{64}$/i.test(key)) {
    throw new Error("PHOTO_SSEC_KEY must be 64 hex characters");
  }
  return { sealed: true, ssec: { ssecKey: key } };
}

/** Reads a photo the way it was stored. */
export async function getPhoto(
  env: PhotoStoreEnv,
  key: string,
  sealed: boolean,
  options: R2GetOptions = {},
): Promise<R2ObjectBody | null> {
  if (!sealed) return env.BUCKET.get(key, options);
  const ssecKey = configuredKey(env);
  if (ssecKey === null) throw new PhotoKeyMissing();
  return env.BUCKET.get(key, { ...options, ssecKey });
}
```

`clear photo` のテストは `get` に `{ range }` がそのまま渡ることを見ている。`options` の既定値 `{}` で呼ばれた場合は `get(key, {})` になるが、R2 の挙動は `undefined` と同じなので問題ない。

- [ ] **Step 4: 通ることを確かめる**

Run: `npx vitest run test/photo-store.test.ts` と `npm run typecheck`
Expected: 6 件 PASS、型エラーなし。`R2GetOptions` に `ssecKey` がないと言われたら、`worker-configuration.d.ts` を `npm run types` で作り直す（読み取りのみの生成）。

- [ ] **Step 5: コミット**

```bash
git add cloudflare/lifelog/src/photo-store.ts cloudflare/lifelog/test/photo-store.test.ts
git commit -m "Add photo store helpers for SSE-C"
```

---

### Task 3: 受け取りで暗号化し、`sealed` を記録する

**Files:**
- Create: `cloudflare/lifelog/migrations/0021_photo_sealed.sql`
- Modify: `cloudflare/lifelog/src/index.ts:32-54`（`Env`）
- Modify: `cloudflare/lifelog/src/index.ts:1319-1376`（`putPhoto` の保存と INSERT）
- Test: `cloudflare/lifelog/test/photo-ssec.test.ts`

**Interfaces:**
- Consumes: `photoPutOptions(env)`（Task 2）
- Produces: `photos.sealed` 列（Task 4 が読む）。`Env.PHOTO_SSEC_KEY?: string`

- [ ] **Step 1: 失敗するテストを書く**

`test/photo-ssec.test.ts`（D1 の組み立ては `test/desktop-corpus.test.ts` と同じ形）:

```ts
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import worker from "../src/index";

const KEY = "cd".repeat(32);
const at = "2026-09-16T01:00:00.000Z";
let db: DatabaseSync;
let env: any;

function prepare(sql: string) {
  let values: unknown[] = [];
  const execute = (method: "all" | "get" | "run") => {
    const statement = db.prepare(sql);
    return values.length
      ? statement[method](Object.fromEntries(values.map((v, i) => [String(i + 1), v])) as any)
      : statement[method]();
  };
  return {
    bind(...args: unknown[]) { values = args; return this; },
    async all() { return { results: execute("all") }; },
    async first() { return execute("get") ?? null; },
    async run() { return execute("run"); },
  };
}

function jpeg(width: number, height: number) {
  return new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x04, 0x00, 0x00,
    0xff, 0xc0, 0x00, 0x11, 0x08, height >> 8, height & 0xff, width >> 8, width & 0xff,
    0x03, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1, 0xff, 0xd9]);
}

function cameraUpload(id: string) {
  return new Request(`https://test/v1/photos/${id}?taken_at=${at}`, {
    method: "PUT", headers: { authorization: "Bearer ingest" }, body: "jpeg-bytes",
  });
}

function desktopUpload(id: string) {
  const form = new FormData();
  form.set("image", new Blob([new Uint8Array([255, 216, 255, 217])], { type: "image/jpeg" }), "screen.jpg");
  form.set("caption_image", new Blob([jpeg(960, 540)], { type: "image/jpeg" }), "caption.jpg");
  form.set("metadata", JSON.stringify({ capture_id: "a".repeat(32), monitor_id: "DISPLAY1", width: 2560, height: 1440, ocr_text: "x" }));
  return new Request(`https://test/v1/photos/${id}?source=desktop&taken_at=${at}&device_id=pc-test`, {
    method: "PUT", headers: { authorization: "Bearer ingest" }, body: form,
  });
}

function sealedOf(id: string) {
  return (db.prepare("SELECT sealed FROM photos WHERE photo_id = ?").get(id) as any).sealed;
}

beforeEach(() => {
  db = new DatabaseSync(":memory:");
  for (const name of readdirSync("migrations").filter(n => n.endsWith(".sql")).sort()) {
    db.exec(readFileSync("migrations/" + name, "utf8"));
  }
  env = {
    INGEST_TOKEN: "ingest", ADMIN_TOKEN: "admin", DB: { prepare },
    BUCKET: { put: vi.fn(async () => ({ size: 10 })), get: vi.fn(async () => null) },
    TRANSCRIBE_QUEUE: { send: vi.fn(async () => {}) },
    AI: { run: vi.fn(async () => ({ response: "机の上にノートがある。" })) },
  };
});
afterEach(() => db.close());

describe("storing photos", () => {
  it("seals a camera photo when the key is set", async () => {
    env.PHOTO_SSEC_KEY = KEY;
    expect((await worker.fetch(cameraUpload("camera0001"), env)).status).toBe(201);
    expect(env.BUCKET.put.mock.calls[0][2]).toMatchObject({ ssecKey: KEY });
    expect(sealedOf("camera0001")).toBe(1);
  });
  it("seals the desktop original and its caption copy alike", async () => {
    env.PHOTO_SSEC_KEY = KEY;
    expect((await worker.fetch(desktopUpload("pc_00000000000000aa"), env)).status).toBe(201);
    expect(env.BUCKET.put).toHaveBeenCalledTimes(2);
    for (const call of env.BUCKET.put.mock.calls) expect(call[2]).toMatchObject({ ssecKey: KEY });
    expect(sealedOf("pc_00000000000000aa")).toBe(1);
  });
  it("stores in the clear and says so when no key is set", async () => {
    expect((await worker.fetch(cameraUpload("camera0002"), env)).status).toBe(201);
    expect(env.BUCKET.put.mock.calls[0][2]).not.toHaveProperty("ssecKey");
    expect(sealedOf("camera0002")).toBe(0);
  });
});
```

- [ ] **Step 2: 失敗を確かめる**

Run: `npx vitest run test/photo-ssec.test.ts`
Expected: FAIL（`no such column: sealed`）

- [ ] **Step 3: migration を書く**

`migrations/0021_photo_sealed.sql`（LF で保存する。Write ツールで書けば LF になる）:

```sql
-- 1 means both r2_key and caption_r2_key were stored with SSE-C under
-- PHOTO_SSEC_KEY and must be read with it. 0 is every photo stored before.
ALTER TABLE photos ADD COLUMN sealed INTEGER NOT NULL DEFAULT 0;
```

- [ ] **Step 4: `Env` に secret を足す**

`src/index.ts` の `interface Env` で、`DESKTOP_TOKEN` の下に足す:

```ts
  /** Seals photos in R2 with SSE-C; see photo-store.ts. Absent stores them in the clear. */
  PHOTO_SSEC_KEY?: string;
```

import に足す（`import { readDesktopPhoto, photoText } from "./desktop-photo";` の下）:

```ts
import { getPhoto, photoPutOptions, PhotoKeyMissing } from "./photo-store";
```

- [ ] **Step 5: 受け取りを変える**

`src/index.ts` の `putPhoto` で、`const key = \`photo/${day}/${photoId}.jpg\`;` の直後に足す:

```ts
  const store = photoPutOptions(env);
```

原寸の `put`（分岐 A：Task 1 で sha256 併用が確かめられた場合）:

```ts
    object = await env.BUCKET.put(key, desktop ? desktop.bytes : request.body, {
      httpMetadata: { contentType: "image/jpeg" },
      customMetadata: { photoId, deviceId, facing, takenAt },
      ...(sha256 ? { sha256 } : {}),
      ...store.ssec,
    });
```

分岐 B（sha256 を R2 に任せられない場合）。`let object` の前で本文を読み、自分で照合する:

```ts
  const bytes = desktop ? desktop.bytes : new Uint8Array(await request.arrayBuffer());
  if (sha256 !== null) {
    const digest = new Uint8Array(await crypto.subtle.digest("SHA-256", bytes));
    const actual = [...digest].map(b => b.toString(16).padStart(2, "0")).join("");
    if (actual !== sha256) return json({ photo_id: photoId, error: "checksum mismatch" }, 422);
  }
```

そのうえで `put` は `bytes` を渡し、`sha256` オプションは `store.sealed` のときだけ外す:

```ts
    object = await env.BUCKET.put(key, bytes, {
      httpMetadata: { contentType: "image/jpeg" },
      customMetadata: { photoId, deviceId, facing, takenAt },
      ...(sha256 && !store.sealed ? { sha256 } : {}),
      ...store.ssec,
    });
```

縮小版の `put`:

```ts
    await env.BUCKET.put(captionKey, desktop.captionBytes, {
      httpMetadata: { contentType: "image/jpeg" },
      customMetadata: { photoId, deviceId, takenAt, role: "caption" },
      ...store.ssec,
    });
```

INSERT に `sealed` を足す:

```ts
  await env.DB.prepare(
    `INSERT INTO photos
       (photo_id, device_id, r2_key, facing, taken_at, taken_epoch, bytes,
        sha256, received_at, source, source_file, ocr_text, capture_id, monitor_id, width, height,
        caption_r2_key, sealed)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11,?12,?13,?14,?15,?16,?17,?18)
     ON CONFLICT(photo_id) DO NOTHING`,
  )
```

`.bind(...)` の最後、`captionKey,` の後に `store.sealed ? 1 : 0,` を足す。

- [ ] **Step 6: 通ることを確かめる**

Run: `npx vitest run test/photo-ssec.test.ts test/desktop-corpus.test.ts` と `npm run typecheck`
Expected: 全件 PASS。`desktop-corpus.test.ts` は鍵なしなので今までどおり通る。

- [ ] **Step 7: コミット**

```bash
git add cloudflare/lifelog/migrations/0021_photo_sealed.sql cloudflare/lifelog/src/index.ts cloudflare/lifelog/test/photo-ssec.test.ts
git commit -m "Seal new photos with SSE-C when a key is set"
```

---

### Task 4: 読み出しを `sealed` に合わせる

**Files:**
- Modify: `cloudflare/lifelog/src/index.ts`（`serveMedia` 1149-1202、`captionPhoto` 1400-1445、`debugCaption` 1458-1472）
- Modify: `cloudflare/lifelog/src/nudge.ts:24-29`（`NudgeEnv`）、`:459-475`（`lookForSomeone`）
- Test: `cloudflare/lifelog/test/photo-ssec.test.ts`（追記）

**Interfaces:**
- Consumes: `getPhoto`、`PhotoKeyMissing`（Task 2）、`photos.sealed`（Task 3）
- Produces: なし

- [ ] **Step 1: 失敗するテストを足す**

`test/photo-ssec.test.ts` の末尾に足す:

```ts
function stored(bytes: Uint8Array) {
  return {
    body: new Response(bytes).body, size: bytes.length, httpEtag: '"e"',
    writeHttpMetadata() {}, arrayBuffer: async () => bytes.buffer,
  };
}

function insertPhoto(id: string, sealed: number, captionKey: string | null = null) {
  db.prepare(`INSERT INTO photos (photo_id, device_id, r2_key, facing, taken_at, taken_epoch, received_at, sealed, caption_r2_key)
              VALUES (?, 'r1', ?, 'front', ?, 0, ?, ?, ?)`)
    .run(id, `photo/2026/09/16/${id}.jpg`, at, at, sealed, captionKey);
}

function media(id: string) {
  return new Request(`https://test/v1/media/photo/${id}`, { headers: { authorization: "Bearer admin" } });
}

function captionJob(id: string) {
  const message = { body: { segmentId: id, key: `photo/2026/09/16/${id}.jpg`, kind: "photo" }, ack: vi.fn(), retry: vi.fn() };
  return { batch: { messages: [message] } as any, message };
}

describe("reading photos", () => {
  const picture = new Uint8Array([255, 216, 1, 2, 255, 217]);

  it("serves sealed and clear photos as the same JPEG", async () => {
    env.PHOTO_SSEC_KEY = KEY;
    env.BUCKET.get = vi.fn(async () => stored(picture));
    insertPhoto("sealed0001", 1);
    insertPhoto("clear00001", 0);

    for (const id of ["sealed0001", "clear00001"]) {
      const response = await worker.fetch(media(id), env);
      expect(response.status).toBe(200);
      expect(response.headers.get("content-type")).toBe("image/jpeg");
      expect(new Uint8Array(await response.arrayBuffer())).toEqual(picture);
    }
    expect(env.BUCKET.get.mock.calls[0][1]).toMatchObject({ ssecKey: KEY });
    expect(env.BUCKET.get.mock.calls[1][1] ?? {}).not.toHaveProperty("ssecKey");
  });

  it("answers 500 for a sealed photo when the key is gone", async () => {
    insertPhoto("sealed0002", 1);
    const response = await worker.fetch(media("sealed0002"), env);
    expect(response.status).toBe(500);
    expect(await response.json()).toMatchObject({ error: "photo key missing" });
    expect(env.BUCKET.get).not.toHaveBeenCalled();
  });

  it("captions a sealed photo from its sealed caption copy", async () => {
    env.PHOTO_SSEC_KEY = KEY;
    env.BUCKET.get = vi.fn(async () => stored(picture));
    insertPhoto("sealed0003", 1, "photo/2026/09/16/sealed0003.caption.jpg");
    const { batch, message } = captionJob("sealed0003");
    await worker.queue(batch, env);
    expect(message.ack).toHaveBeenCalled();
    expect(env.BUCKET.get).toHaveBeenCalledWith("photo/2026/09/16/sealed0003.caption.jpg", { ssecKey: KEY });
    const row: any = db.prepare("SELECT status, caption FROM photos WHERE photo_id = ?").get("sealed0003");
    expect(row).toMatchObject({ status: "captioned", caption: "机の上にノートがある。" });
  });

  it("marks the caption failed, without retrying, when the key is gone", async () => {
    insertPhoto("sealed0004", 1);
    const { batch, message } = captionJob("sealed0004");
    await worker.queue(batch, env);
    expect(message.ack).toHaveBeenCalled();
    expect(message.retry).not.toHaveBeenCalled();
    const row: any = db.prepare("SELECT status, error FROM photos WHERE photo_id = ?").get("sealed0004");
    expect(row).toMatchObject({ status: "failed", error: "photo key missing" });
  });
});
```

`insertPhoto` の列は、`photos` の NOT NULL 列に合わせる。足りない列で INSERT が落ちたら、`migrations/0009_photos.sql` 以降の定義を見て足す。

- [ ] **Step 2: 失敗を確かめる**

Run: `npx vitest run test/photo-ssec.test.ts`
Expected: `reading photos` の 4 件が FAIL（鍵が渡っていない／500 にならない／`failed` にならない）

- [ ] **Step 3: 配信を変える**

`serveMedia` の写真分岐:

```ts
  let sealed = false;
  ...
  } else if (kind === "photo") {
    const row = await env.DB.prepare(`SELECT r2_key, sealed FROM photos WHERE photo_id = ?1`)
      .bind(id)
      .first<{ r2_key: string; sealed: number }>();
    if (!row) return json({ error: "not found" }, 404);
    key = row.r2_key;
    sealed = row.sealed === 1;
    contentType = "image/jpeg";
  }
```

`let sealed = false;` は `let contentType` の下に置く。取得部分:

```ts
  const range = request.headers.get("range");
  const options = range ? { range: request.headers } : undefined;
  let object: R2ObjectBody | null;
  try {
    object = kind === "photo"
      ? await getPhoto(env, key!, sealed, options)
      : await env.BUCKET.get(key!, options);
  } catch (error) {
    if (error instanceof PhotoKeyMissing) return json({ error: error.message }, 500);
    throw error;
  }
  if (!object) return json({ error: "object missing" }, 404);
```

分岐 R（Task 1 で Range と `ssecKey` を併用できなかった場合）: `getPhoto` に渡す `options` を `sealed ? undefined : options` にし、`accept-ranges` は `kind === "photo" && sealed` のとき `none` にする。

- [ ] **Step 4: キャプション生成を変える**

`captionPhoto` の行の取得と読み出し:

```ts
  const row = await env.DB.prepare(
    `SELECT source, caption_r2_key, sealed FROM photos WHERE photo_id = ?1`,
  )
    .bind(message.segmentId)
    .first<{ source: string | null; caption_r2_key: string | null; sealed: number | null }>();

  let object: R2ObjectBody | null;
  try {
    object = await getPhoto(env, row?.caption_r2_key ?? message.key, row?.sealed === 1);
  } catch (error) {
    if (!(error instanceof PhotoKeyMissing)) throw error;
    // Retrying cannot help until the secret is put back, so this is recorded
    // the same way a missing object is.
    await env.DB.prepare(
      `UPDATE photos SET status = 'failed', error = ?1 WHERE photo_id = ?2`,
    )
      .bind(error.message, message.segmentId)
      .run();
    return;
  }
  if (!object) {
```

`if (!object) {` 以降は今のまま。

- [ ] **Step 5: キャプションのデバッグを変える**

`debugCaption`:

```ts
  const row = await env.DB.prepare(
    `SELECT COALESCE(caption_r2_key, r2_key) AS key, sealed FROM photos WHERE photo_id = ?1`,
  )
    .bind(photoId)
    .first<{ key: string; sealed: number }>();
  if (!row) return json({ error: "not found" }, 404);

  let object: R2ObjectBody | null;
  try {
    object = await getPhoto(env, row.key, row.sealed === 1);
  } catch (error) {
    if (error instanceof PhotoKeyMissing) return json({ error: error.message }, 500);
    throw error;
  }
  if (!object) return json({ error: "image missing" }, 404);
```

- [ ] **Step 6: 在室判定を変える**

`src/nudge.ts` の import に足す:

```ts
import { getPhoto, PhotoKeyMissing } from "./photo-store";
```

`NudgeEnv` に足す:

```ts
  /** See photo-store.ts. */
  PHOTO_SSEC_KEY?: string;
```

`lookForSomeone` の読み出し:

```ts
  const row = await env.DB.prepare(`SELECT r2_key, sealed FROM photos WHERE photo_id = ?1`)
    .bind(photoId)
    .first<{ r2_key: string; sealed: number }>();
  if (!row) return { yes: false, why: "no_frame", note: null, neurons: null };

  let object: R2ObjectBody | null;
  try {
    object = await getPhoto(env, row.r2_key, row.sealed === 1);
  } catch (error) {
    if (!(error instanceof PhotoKeyMissing)) throw error;
    // Fails closed like every other refusal here, with a reason that says why.
    return { yes: false, why: "photo_key_missing", note: null, neurons: null };
  }
  if (!object) return { yes: false, why: "no_frame", note: null, neurons: null };
```

`why` に新しい値を足すので、`why` の型が文字列の union になっていないか確かめる。union なら `"photo_key_missing"` を足す。

在室判定は前提条件が多く、単体テストを組むと本題から外れるので、ここは型チェックと `photo-store.test.ts` で担保する。

- [ ] **Step 7: 全体を確かめる**

Run: `npm test` と `npm run typecheck`
Expected: 全件 PASS、型エラーなし

- [ ] **Step 8: コミット**

```bash
git add cloudflare/lifelog/src/index.ts cloudflare/lifelog/src/nudge.ts cloudflare/lifelog/test/photo-ssec.test.ts
git commit -m "Read sealed photos with their key"
```

---

### Task 5: 本番へ反映して確かめる（各手順とも要承認）

**Files:**
- Modify: `docs/superpowers/specs/2026-09-16-photo-ssec-design.md` と `.html`（状態とターン数）
- Modify: この計画の「調査結果」「反映記録」

**Interfaces:**
- Consumes: Task 2〜4 の成果
- Produces: 本番での動作記録

- [ ] **Step 1: アカウントを確かめる**

Run: `npx wrangler whoami`
Expected: 利用者のアカウント。結果を利用者に伝える。

- [ ] **Step 2: 鍵を作って控えてもらう**

Run（ローカルで生成するだけ）: `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`

出力は会話に出さない。ファイル `<scratchpad>/photo-ssec-key.txt` に書き、利用者にパスワード管理へ移してもらう。移したと返事をもらってから、ファイルを消す。

- [ ] **Step 3: secret を登録する（要承認）**

利用者に `! npx wrangler secret put PHOTO_SSEC_KEY`（`cloudflare/lifelog` で）を実行してもらう。値は利用者が貼る。
Expected: `Success! Uploaded secret PHOTO_SSEC_KEY`

- [ ] **Step 4: migration を適用する（要承認）**

Run: `npx wrangler d1 migrations apply r1-lifelog --remote`
Expected: `0021_photo_sealed.sql` が適用される。`incomplete input` が出たら、改行が CRLF になっていないか `git ls-files --eol migrations/0021_photo_sealed.sql` で確かめる。

- [ ] **Step 5: デプロイする（要承認）**

Run: `npx wrangler deploy`
Expected: 新しい Version ID が出る。控えておく。

- [ ] **Step 6: 動作を確かめる**

- 次に R1 か PC から写真が届いたら、D1 で `sealed = 1` を確かめる（読み取りのみ）:
  `npx wrangler d1 execute r1-lifelog --remote --command "SELECT photo_id, sealed, status, caption IS NOT NULL AS captioned FROM photos ORDER BY received_at DESC LIMIT 5"`
- 日ビューでその写真が表示されることを、利用者に確かめてもらう（Access の内側なので）。
- 鍵なしで取れないことを確かめる（読み取りのみ）:
  `npx wrangler r2 object get r1-lifelog-audio/<その写真の r2_key> --remote --file <scratchpad>/sealed.jpg`
  Expected: 失敗する。成功した場合は、中身が JPEG（先頭 `FF D8`）でないかを確かめ、利用者に報告する。
- 既存の写真（`sealed = 0`）も、日ビューで今までどおり表示されること。

- [ ] **Step 7: 記録してコミット**

設計書の「状態」を「本番反映済み」に変え、ターン数を更新する（Markdown と HTML の両方）。この計画の「反映記録」に Version ID、日時（JST）、確かめた内容を書く。

```bash
git add docs/superpowers/specs/2026-09-16-photo-ssec-design.md docs/superpowers/specs/2026-09-16-photo-ssec-design.html docs/superpowers/plans/2026-09-16-photo-ssec.md
git commit -m "Record the SSE-C rollout for photos"
```

---

## 調査結果

（Task 1 で記入）

## 反映記録

（Task 5 で記入）

## ターン数

予定 / 実際: 実装 8 / 未計測
