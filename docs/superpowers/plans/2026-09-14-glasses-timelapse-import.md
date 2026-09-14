# 眼鏡カメラのタイムラプス取り込み 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 眼鏡カメラの `.mov` を Web 画面から選び、ブラウザでフレームに分解して既存の `photos` パイプラインに流し込めるようにする。

**Architecture:** 動画はサーバに送らない。ブラウザが `<video>` でデコードし、canvas で1フレームずつ JPEG にして、既存の `PUT /v1/photos/<id>` に上げる。`photo_id` を元ファイルの SHA-256 から決めることで、再取り込みはサーバ側の既存の重複排除にそのまま吸収される。時刻はファイルの更新日時から逆算する。

**Tech Stack:** Cloudflare Workers / D1 / R2 / Queues、TypeScript、vitest（新規）、ブラウザ側は素の DOM API（ライブラリなし）

**Spec:** [docs/superpowers/specs/2026-09-14-glasses-timelapse-import-design.md](../specs/2026-09-14-glasses-timelapse-import-design.md)

**対になる設計:** [docs/superpowers/specs/2026-09-14-caption-corpus-design.md](../specs/2026-09-14-caption-corpus-design.md) — **この計画だけでは目的を達成しない。** キャプションは書かれるが検索にもエージェントにも届かない。第2弾を必ず後続させること。

## Global Constraints

- 作業ディレクトリは `cloudflare/lifelog`。パッケージマネージャは **npm**（`package-lock.json` がある。pnpm ではない）
- ブランチは `feature/glasses-import`。`main` / `develop` に直接コミットしない
- `export const runtime = "edge"` は書かない
- コミットメッセージに AI の固有名詞・署名・生成ツールへの言及を入れない
- タイムゾーンは Asia/Tokyo。ISO 8601 で保存し、表示は JST
- 認証の追加配線は不要。ブラウザの利用者は Cloudflare Access のアサーションで `principalFor` が `admin` を返し、全ルートを通る（`src/index.ts` の `authorise`）
- `photo_id` は既存の検証 `/^[A-Za-z0-9_-]{8,64}$/` を満たすこと
- 撮影間隔の既定は 30 秒
- 実測の基準値（テストに使う）: `MOVI0006.mov` は 37 フレーム、更新日時 `2026-09-14T20:19:02+09:00`、逆算した開始時刻 `2026-09-14T20:01:02+09:00`

---

## ファイル構成

| ファイル | 責務 |
| --- | --- |
| `src/import-id.ts` | 新規。`photoIdFor(fileHash, index)` — 純粋関数 |
| `src/import-time.ts` | 新規。`frameTimes(lastModifiedMs, count, intervalSec)` — 純粋関数 |
| `src/caption-prompt.ts` | 新規。出所ごとのキャプション用プロンプト選択 — 純粋関数 |
| `src/day-group.ts` | 新規。`groupPhotos(rows)` — 日ビューのグループ化、純粋関数 |
| `src/import.ts` | 新規。`/import` が返す HTML ページ（ブラウザ側の全処理） |
| `src/index.ts` | 変更。`putPhoto` / `captionPhoto` / `dayView` / ルート / `photoRetime` |
| `src/ui.ts` | 変更。`/import` へのリンク1つ |
| `migrations/0017_photo_source.sql` | 新規。`source` と `source_file` |
| `test/*.test.ts` | 新規。純粋関数の単体テスト |

ブラウザで動く必要のある純粋関数（`photoIdFor` / `frameTimes`）は `.ts` に置いたうえで、
`import.ts` が `Function.prototype.toString()` でページに埋め込む。
実装を二重に持たないための措置で、Task 2 でその前提をテストごと固定する。

---

### Task 1: vitest を入れて `photoIdFor` を書く

**Files:**
- Modify: `cloudflare/lifelog/package.json`
- Create: `cloudflare/lifelog/src/import-id.ts`
- Test: `cloudflare/lifelog/test/import-id.test.ts`

**Interfaces:**
- Consumes: なし
- Produces: `photoIdFor(fileHash: string, index: number): string` — `"g" + 16桁hex + "-" + 4桁0詰め` の22文字を返す。範囲外は `Error` を投げる

- [ ] **Step 1: vitest を入れる**

```bash
cd cloudflare/lifelog
npm add -D vitest
```

`package.json` の `scripts` に2行足す。

```json
"scripts": {
  "dev": "wrangler dev",
  "deploy": "wrangler deploy",
  "types": "wrangler types",
  "test": "vitest run",
  "test:watch": "vitest"
}
```

- [ ] **Step 2: 失敗するテストを書く**

`cloudflare/lifelog/test/import-id.test.ts`:

```typescript
import { describe, expect, it } from "vitest";
import { photoIdFor } from "../src/import-id";

describe("photoIdFor", () => {
  it("は22文字の id を作る", () => {
    expect(photoIdFor("3f8a1c9e04b27d6f", 12)).toBe("g3f8a1c9e04b27d6f-0012");
    expect(photoIdFor("3f8a1c9e04b27d6f", 12)).toHaveLength(22);
  });

  it("はフレーム番号を4桁に0詰めする", () => {
    // 0詰めは飾りではない。photo-retime がこの順序で並べ替えるので、
    // 0詰めを外すと 10 が 2 より前に来る。
    expect(photoIdFor("0000000000000000", 0)).toBe("g0000000000000000-0000");
    expect(photoIdFor("0000000000000000", 9999)).toBe("g0000000000000000-9999");
  });

  it("は既存の photo_id 検証を通る", () => {
    expect(photoIdFor("abcdef0123456789", 7)).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
  });

  it("は壊れた入力を拒む", () => {
    expect(() => photoIdFor("XYZ", 0)).toThrow();
    expect(() => photoIdFor("3F8A1C9E04B27D6F", 0)).toThrow(); // 大文字は不可
    expect(() => photoIdFor("3f8a1c9e04b27d6f", -1)).toThrow();
    expect(() => photoIdFor("3f8a1c9e04b27d6f", 10000)).toThrow();
    expect(() => photoIdFor("3f8a1c9e04b27d6f", 1.5)).toThrow();
  });
});
```

- [ ] **Step 3: テストを走らせて落ちることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: FAIL — `Failed to resolve import "../src/import-id"`

- [ ] **Step 4: 最小の実装を書く**

`cloudflare/lifelog/src/import-id.ts`:

```typescript
/**
 * A frame's id, derived from the file it came out of.
 *
 * Deterministic, because that is the whole of the deduplication: putPhoto
 * answers `duplicate: true` for an id it already holds, so re-importing the
 * same .mov costs a few requests and changes nothing.
 *
 * The file's name is deliberately not part of this. The camera reuses
 * MOVI0006.mov every time the card is cleared, so two unrelated recordings
 * share a name — only the content distinguishes them.
 *
 * The frame number is zero-padded so that ordering by photo_id is ordering by
 * frame. /v1/admin/photo-retime relies on that to re-date a whole import in
 * sequence.
 */
export function photoIdFor(fileHash: string, index: number): string {
  if (!/^[0-9a-f]{16}$/.test(fileHash)) {
    throw new Error("fileHash must be 16 lowercase hex characters");
  }
  if (!Number.isInteger(index) || index < 0 || index > 9999) {
    throw new Error("index must be an integer in 0..9999");
  }
  return "g" + fileHash + "-" + String(index).padStart(4, "0");
}
```

- [ ] **Step 5: テストが通ることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: PASS — 4 tests

- [ ] **Step 6: コミット**

```bash
git add cloudflare/lifelog/package.json cloudflare/lifelog/package-lock.json \
        cloudflare/lifelog/src/import-id.ts cloudflare/lifelog/test/import-id.test.ts
git commit -m "Name a frame after the file it came out of"
```

---

### Task 2: `frameTimes` を書く

**Files:**
- Create: `cloudflare/lifelog/src/import-time.ts`
- Test: `cloudflare/lifelog/test/import-time.test.ts`

**Interfaces:**
- Consumes: なし
- Produces: `frameTimes(lastModifiedMs: number, count: number, intervalSec: number): string[]` — ISO 8601 文字列の配列を時刻昇順で返す

- [ ] **Step 1: 失敗するテストを書く**

`cloudflare/lifelog/test/import-time.test.ts`:

```typescript
import { describe, expect, it } from "vitest";
import { frameTimes } from "../src/import-time";

// 実測値。E:\DCIM\MOVI0006.mov を ffprobe と Windows のファイル情報で確認したもの。
const MOVI0006_LAST_MODIFIED = Date.parse("2026-09-14T20:19:02+09:00");

describe("frameTimes", () => {
  it("は更新日時から開始時刻を逆算する", () => {
    const times = frameTimes(MOVI0006_LAST_MODIFIED, 37, 30);
    expect(times).toHaveLength(37);
    // 20:19:02 − 36区間 × 30秒 = 20:01:02 JST = 11:01:02Z
    expect(times[0]).toBe("2026-09-14T11:01:02.000Z");
    expect(times[36]).toBe("2026-09-14T11:19:02.000Z");
  });

  it("は間隔ぶんずつ進む", () => {
    const times = frameTimes(MOVI0006_LAST_MODIFIED, 37, 30);
    for (let i = 1; i < times.length; i++) {
      expect(Date.parse(times[i]) - Date.parse(times[i - 1])).toBe(30_000);
    }
  });

  it("は1フレームなら更新日時そのものを返す", () => {
    // MOVI0004 がこれ。作成日時と更新日時が2秒差で、フレームは1枚。
    const times = frameTimes(Date.parse("2026-09-14T19:59:02+09:00"), 1, 30);
    expect(times).toEqual(["2026-09-14T10:59:02.000Z"]);
  });

  it("は日をまたいでも壊れない", () => {
    // JST の 00:05:02 に書き終わった37枚は、前日の 23:47:02 から始まる。
    const times = frameTimes(Date.parse("2026-09-15T00:05:02+09:00"), 37, 30);
    expect(times[0]).toBe("2026-09-14T14:47:02.000Z");
    expect(times[36]).toBe("2026-09-14T15:05:02.000Z");
  });

  it("は間隔を変えれば結果も変わる", () => {
    const times = frameTimes(MOVI0006_LAST_MODIFIED, 3, 60);
    expect(Date.parse(times[1]) - Date.parse(times[0])).toBe(60_000);
    expect(times[2]).toBe(new Date(MOVI0006_LAST_MODIFIED).toISOString());
  });

  it("は壊れた入力を拒む", () => {
    expect(() => frameTimes(NaN, 37, 30)).toThrow();
    expect(() => frameTimes(MOVI0006_LAST_MODIFIED, 0, 30)).toThrow();
    expect(() => frameTimes(MOVI0006_LAST_MODIFIED, 1.5, 30)).toThrow();
    expect(() => frameTimes(MOVI0006_LAST_MODIFIED, 37, 0)).toThrow();
  });

  it("は文字列化してもそのまま動く", () => {
    // import.ts はこの関数を toString() でページに埋め込む。バンドラが
    // 外側の識別子を参照する形に書き換えたら、ブラウザで落ちる。ここで止める。
    const rebuilt = new Function("return " + frameTimes.toString())() as typeof frameTimes;
    expect(rebuilt(MOVI0006_LAST_MODIFIED, 37, 30)[0]).toBe("2026-09-14T11:01:02.000Z");
  });
});
```

- [ ] **Step 2: テストを走らせて落ちることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: FAIL — `Failed to resolve import "../src/import-time"`

- [ ] **Step 3: 最小の実装を書く**

`cloudflare/lifelog/src/import-time.ts`:

```typescript
/**
 * When each frame was actually taken.
 *
 * The browser cannot read a file's creation time. File exposes only
 * lastModified, which the camera sets when it finishes writing — the END of
 * the recording. So the start is derived rather than read: walk back over the
 * span the frames cover.
 *
 * The MOV's own creation_time does hold the true start, but on this camera it
 * is offset by exactly -8 hours (it writes JST to the filesystem and converts
 * the container's timestamp as if the clock were UTC+8). Correcting for that
 * would bake in a constant that stops being true the day the camera's timezone
 * is fixed, and would do it silently. Measured on MOVI0006, the arithmetic
 * below lands 10 seconds late — inside a 30-second interval, and visible in
 * the import screen where it can be corrected by hand.
 *
 * Written so that Function.prototype.toString() gives valid standalone JS:
 * it must not reference anything outside itself, because import.ts embeds it
 * in the page that way rather than keeping a second copy.
 */
export function frameTimes(
  lastModifiedMs: number,
  count: number,
  intervalSec: number,
): string[] {
  if (!Number.isFinite(lastModifiedMs)) {
    throw new Error("lastModifiedMs must be a finite number");
  }
  if (!Number.isInteger(count) || count < 1) {
    throw new Error("count must be a positive integer");
  }
  if (!Number.isFinite(intervalSec) || intervalSec <= 0) {
    throw new Error("intervalSec must be a positive number");
  }

  const stepMs = intervalSec * 1000;
  const startMs = lastModifiedMs - (count - 1) * stepMs;
  const out: string[] = [];
  for (let i = 0; i < count; i++) {
    out.push(new Date(startMs + i * stepMs).toISOString());
  }
  return out;
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: PASS — 7 tests in import-time, 4 in import-id

- [ ] **Step 5: コミット**

```bash
git add cloudflare/lifelog/src/import-time.ts cloudflare/lifelog/test/import-time.test.ts
git commit -m "Work out when each frame was taken from when the file was written"
```

---

### Task 3: マイグレーション 0017 — 出所の2列

**Files:**
- Create: `cloudflare/lifelog/migrations/0017_photo_source.sql`

**Interfaces:**
- Consumes: なし
- Produces: `photos.source TEXT NOT NULL DEFAULT 'r1'`、`photos.source_file TEXT`、索引 `photos_source_file`

- [ ] **Step 1: マイグレーションを書く**

`cloudflare/lifelog/migrations/0017_photo_source.sql`:

```sql
-- どの機材から来たフレームか。
--
-- 眼鏡カメラのタイムラプスは30秒間隔で、R1 の5分間隔の10倍の密度がある。
-- 同じテーブルに入れる(写真であることに変わりはなく、キャプションも日ビューも
-- 配信も共通)が、日ビューの見せ方だけは分ける必要があるため出所を持つ。
--
-- 既存の行はすべて R1 のもの。DEFAULT がそれを引き受ける。
ALTER TABLE photos ADD COLUMN source TEXT NOT NULL DEFAULT 'r1';

-- 取り込み元の動画1本を指す識別子。眼鏡カメラのフレームにだけ入る。
--
-- 日ビューで「この18分ぶん」を1エントリにまとめるため、および時刻を後から
-- 引き直すときの単位として使う。R1 の写真は NULL。
ALTER TABLE photos ADD COLUMN source_file TEXT;

CREATE INDEX IF NOT EXISTS photos_source_file ON photos(source_file);
```

- [ ] **Step 2: ローカルに当てる**

Run:
```bash
cd cloudflare/lifelog
npx wrangler d1 migrations apply r1-lifelog --local
```
Expected: `0017_photo_source.sql` が適用されたと出る

- [ ] **Step 3: 列ができたことを確認する**

Run:
```bash
npx wrangler d1 execute r1-lifelog --local --command "PRAGMA table_info(photos)"
```
Expected: `source`（`notnull=1`, `dflt_value='r1'`）と `source_file`（nullable）が並ぶ

- [ ] **Step 4: 既存行に既定値が入ることを確認する**

Run:
```bash
npx wrangler d1 execute r1-lifelog --local --command \
  "INSERT INTO photos (photo_id, device_id, r2_key, facing, taken_at, taken_epoch, received_at) VALUES ('legacy00', 'r1', 'photo/x.jpg', 'front', '2026-09-14T00:00:00Z', 1789344000, '2026-09-14T00:00:00Z'); SELECT photo_id, source, source_file FROM photos WHERE photo_id = 'legacy00'"
```
Expected: `source = r1`、`source_file = NULL`

後始末:
```bash
npx wrangler d1 execute r1-lifelog --local --command "DELETE FROM photos WHERE photo_id = 'legacy00'"
```

- [ ] **Step 5: コミット**

```bash
git add cloudflare/lifelog/migrations/0017_photo_source.sql
git commit -m "Record which camera a frame came from"
```

---

### Task 4: `putPhoto` が出所を受け取る

**Files:**
- Modify: `cloudflare/lifelog/src/index.ts`（`putPhoto`、`async function putPhoto` から `return json({ photo_id: photoId, key, bytes: ... }, 201);` まで）

**Interfaces:**
- Consumes: Task 3 の `photos.source` / `photos.source_file`
- Produces: `PUT /v1/photos/<id>?source=glasses&source_file=<16hex>` を受け付ける。`source` 省略時は `r1`

- [ ] **Step 1: パラメータの読み取りを足す**

`putPhoto` 内、`const deviceId = url.searchParams.get("device_id") ?? "unknown";` の直後に足す。

```typescript
  // 出所。既定が r1 なのは、R1 端末を変更せずに済ませるため。
  const source = url.searchParams.get("source") === "glasses" ? "glasses" : "r1";

  // 取り込み元の動画。眼鏡カメラのフレームには必須で、これが日ビューの
  // グループ化と photo-retime の単位になる。
  const sourceFile = url.searchParams.get("source_file");
  if (source === "glasses") {
    if (!sourceFile || !/^[0-9a-f]{16}$/.test(sourceFile)) {
      return json({ error: "source_file must be 16 lowercase hex characters" }, 400);
    }
  }
```

- [ ] **Step 2: INSERT に2列足す**

同じ関数内の `INSERT INTO photos` を差し替える。

```typescript
  await env.DB.prepare(
    `INSERT INTO photos
       (photo_id, device_id, r2_key, facing, taken_at, taken_epoch, bytes,
        sha256, received_at, source, source_file)
     VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9,?10,?11)
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
      source,
      source === "glasses" ? sourceFile : null,
    )
    .run();
```

- [ ] **Step 3: 返り値にも出所を載せる**

同じ関数の最後の `return` を差し替える。

```typescript
  return json({ photo_id: photoId, key, bytes: object?.size ?? null, source }, 201);
```

- [ ] **Step 4: ローカルで動かして確認する**

別の端末で Worker を起動したまま:
```bash
cd cloudflare/lifelog && npx wrangler dev
```

小さな JPEG を1枚作って投げる。
```bash
printf '\xff\xd8\xff\xdb\x00\x43\x00\xff\xd9' > /tmp/tiny.jpg

curl -i -X PUT --data-binary @/tmp/tiny.jpg \
  "http://127.0.0.1:8787/v1/photos/g0123456789abcdef-0000?taken_at=2026-09-14T11:01:02.000Z&device_id=glasses&facing=rear&source=glasses&source_file=0123456789abcdef"
```
Expected: `201`、本文に `"source":"glasses"`

- [ ] **Step 5: 行の中身を確認する**

Run:
```bash
npx wrangler d1 execute r1-lifelog --local --command \
  "SELECT photo_id, source, source_file, facing FROM photos WHERE photo_id = 'g0123456789abcdef-0000'"
```
Expected: `source = glasses`、`source_file = 0123456789abcdef`、`facing = rear`

- [ ] **Step 6: source_file 無しの glasses が弾かれることを確認する**

Run:
```bash
curl -i -X PUT --data-binary @/tmp/tiny.jpg \
  "http://127.0.0.1:8787/v1/photos/g0123456789abcdef-0001?taken_at=2026-09-14T11:01:32.000Z&source=glasses"
```
Expected: `400`、`"source_file must be 16 lowercase hex characters"`

- [ ] **Step 7: R1 の呼び方が変わっていないことを確認する**

Run:
```bash
curl -i -X PUT --data-binary @/tmp/tiny.jpg \
  "http://127.0.0.1:8787/v1/photos/r1test000000?taken_at=2026-09-14T11:05:00.000Z&facing=front"
npx wrangler d1 execute r1-lifelog --local --command \
  "SELECT source, source_file FROM photos WHERE photo_id = 'r1test000000'"
```
Expected: `201`、`source = r1`、`source_file = NULL`

- [ ] **Step 8: コミット**

```bash
git add cloudflare/lifelog/src/index.ts
git commit -m "Take the camera's name along with the frame"
```

---

### Task 5: 出所ごとにキャプションのプロンプトを変える

**Files:**
- Create: `cloudflare/lifelog/src/caption-prompt.ts`
- Modify: `cloudflare/lifelog/src/index.ts`（`CAPTION_PROMPT` の定義を削り、`captionPhoto` を差し替える）
- Test: `cloudflare/lifelog/test/caption-prompt.test.ts`

**Interfaces:**
- Consumes: Task 3 の `photos.source`
- Produces: `captionPromptFor(source: string | null | undefined): string`、`CAPTION_PROMPT_R1`、`CAPTION_PROMPT_GLASSES`

- [ ] **Step 1: 失敗するテストを書く**

`cloudflare/lifelog/test/caption-prompt.test.ts`:

```typescript
import { describe, expect, it } from "vitest";
import {
  CAPTION_PROMPT_GLASSES,
  CAPTION_PROMPT_R1,
  captionPromptFor,
} from "../src/caption-prompt";

describe("captionPromptFor", () => {
  it("は眼鏡カメラに専用のプロンプトを返す", () => {
    expect(captionPromptFor("glasses")).toBe(CAPTION_PROMPT_GLASSES);
  });

  it("はそれ以外を R1 のプロンプトに落とす", () => {
    expect(captionPromptFor("r1")).toBe(CAPTION_PROMPT_R1);
    expect(captionPromptFor(null)).toBe(CAPTION_PROMPT_R1);
    expect(captionPromptFor(undefined)).toBe(CAPTION_PROMPT_R1);
    expect(captionPromptFor("なにか知らないもの")).toBe(CAPTION_PROMPT_R1);
  });

  it("の眼鏡用は文字を読むよう指示している", () => {
    // 1920x1080 のまま上げる判断と対になっている。解像度を保っても
    // プロンプトが文字に触れなければ、その画素は無駄になる。
    expect(CAPTION_PROMPT_GLASSES).toContain("文字");
  });

  it("の2つは別物である", () => {
    expect(CAPTION_PROMPT_GLASSES).not.toBe(CAPTION_PROMPT_R1);
  });
});
```

- [ ] **Step 2: テストを走らせて落ちることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: FAIL — `Failed to resolve import "../src/caption-prompt"`

- [ ] **Step 3: 実装を書く**

`cloudflare/lifelog/src/caption-prompt.ts`:

```typescript
/**
 * What the vision model is asked for, and why it differs by camera.
 *
 * Japanese, because the transcripts are and a search that has to work across
 * two languages works well in neither. Concrete and short: the caption exists
 * so a frame can be found later, not so it can be read for pleasure, and every
 * token is billed.
 */

/**
 * The R1's timelapse: a device on a desk or in a pocket, photographing the
 * room. 640x480, front and rear, one pair every five minutes.
 */
export const CAPTION_PROMPT_R1 =
  "この写真に写っているものを日本語で簡潔に説明してください。" +
  "人物の有無、場所の様子、目立つ物を2〜3文で。推測は避け、見えるものだけを述べてください。";

/**
 * The glasses camera: 1920x1080 of whatever the wearer was looking at.
 *
 * A different question, not a rephrasing of the same one. Asked for "the state
 * of the place", a head-mounted frame answers about the room and loses the
 * thing the wearer was actually attending to — which is the entire reason this
 * camera is worn.
 *
 * The instruction to read text is what the resolution is for. Keeping
 * 1920x1080 and then not asking about the writing on the screen in front of
 * the wearer spends the pixels for nothing.
 */
export const CAPTION_PROMPT_GLASSES =
  "これは頭に装着したカメラの映像で、撮影者が見ていたものです。" +
  "何を見ていたかを日本語で2〜3文で説明してください。" +
  "画面・書類・看板などの文字が読める場合はその内容を含めてください。" +
  "推測は避け、見えるものだけを述べてください。";

/** Unknown sources fall back to the R1 wording rather than failing. */
export function captionPromptFor(source: string | null | undefined): string {
  return source === "glasses" ? CAPTION_PROMPT_GLASSES : CAPTION_PROMPT_R1;
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: PASS — 4 tests in caption-prompt

- [ ] **Step 5: `index.ts` から古い定数を消して差し替える**

`src/index.ts` の `const CAPTION_PROMPT = ...`（`CAPTION_MAX_TOKENS` の直前にある定義と、その上の JSDoc）を丸ごと削除し、ファイル先頭の import 群に足す。

```typescript
import { captionPromptFor } from "./caption-prompt";
```

`CAPTION_MAX_TOKENS` とその JSDoc はそのまま残す。上限の理由は変わっていない。

- [ ] **Step 6: `captionPhoto` が出所を読むようにする**

`src/index.ts` の `captionPhoto`（`/** Captions one photograph and records what it cost. */` の下）。
R2 の object が取れたあと、`const bytes = new Uint8Array(...)` から `env.AI.run(...)` までを
丸ごと次に置き換える。

```typescript
  // どのカメラのフレームかで訊くことが変わる。行が無いのはキューが D1 の
  // 書き込みを追い越した場合だけで、そのとき R1 の文面に落ちるのは安全側 --
  // キャプションが付かないよりはよい。
  const row = await env.DB.prepare(
    `SELECT source FROM photos WHERE photo_id = ?1`,
  )
    .bind(message.segmentId)
    .first<{ source: string | null }>();

  const bytes = new Uint8Array(await object.arrayBuffer());
  const result: any = await env.AI.run(
    "@cf/meta/llama-3.2-11b-vision-instruct" as any,
    {
      image: [...bytes],
      prompt: captionPromptFor(row?.source),
      max_tokens: CAPTION_MAX_TOKENS,
    } as any,
  );
```

その下の `const text = String(result?.response ?? "").trim();` 以降は変更しない。

> このコードベースに `askAboutImage` のようなヘルパーは無い。`captionPhoto` は
> `env.AI.run` を直接呼んでいる（`src/index.ts:1209`）。モデル名と `as any` の
> 形はそのまま残すこと。

- [ ] **Step 7: 型が通ることを確認する**

Run: `cd cloudflare/lifelog && npx tsc --noEmit`
Expected: エラーなし。`CAPTION_PROMPT` の未定義参照が残っていれば、ここで出る

- [ ] **Step 8: コミット**

```bash
git add cloudflare/lifelog/src/caption-prompt.ts cloudflare/lifelog/src/index.ts \
        cloudflare/lifelog/test/caption-prompt.test.ts
git commit -m "Ask the head-mounted camera a different question"
```

---

### Task 6: 日ビューのグループ化を出所で分ける

**Files:**
- Create: `cloudflare/lifelog/src/day-group.ts`
- Modify: `cloudflare/lifelog/src/index.ts`（`dayView` の写真 SELECT と、`// Group the pair that was taken together.` から `entries.sort(` の直前まで）
- Test: `cloudflare/lifelog/test/day-group.test.ts`

**Interfaces:**
- Consumes: Task 3 の `photos.source` / `photos.source_file`
- Produces: `groupPhotos(rows: PhotoRow[]): PhotoGroup[]`、および型 `PhotoRow` / `PhotoGroup`

- [ ] **Step 1: 失敗するテストを書く**

`cloudflare/lifelog/test/day-group.test.ts`:

```typescript
import { describe, expect, it } from "vitest";
import { groupPhotos, type PhotoRow } from "../src/day-group";

function row(over: Partial<PhotoRow> & { photo_id: string; taken_epoch: number }): PhotoRow {
  return {
    facing: "front",
    caption: null,
    source: "r1",
    source_file: null,
    taken_at: new Date(over.taken_epoch * 1000).toISOString(),
    ...over,
  };
}

describe("groupPhotos", () => {
  it("は R1 の前後の対を1つにまとめる", () => {
    const groups = groupPhotos([
      row({ photo_id: "a", taken_epoch: 1_789_000_000, facing: "front" }),
      row({ photo_id: "b", taken_epoch: 1_789_000_003, facing: "rear" }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].shots.map((s) => s.photo_id)).toEqual(["a", "b"]);
  });

  it("は分が違う R1 の写真を分ける", () => {
    const groups = groupPhotos([
      row({ photo_id: "a", taken_epoch: 1_789_000_000 }),
      row({ photo_id: "b", taken_epoch: 1_789_000_300 }),
    ]);
    expect(groups).toHaveLength(2);
  });

  it("は同じ分に入る眼鏡の2フレームを対として扱わない", () => {
    // これが直したかった不具合。30秒間隔だと1分に2枚入るので、
    // 分でまとめると無関係な2フレームが前後の対になってしまう。
    const groups = groupPhotos([
      row({ photo_id: "g-0", taken_epoch: 1_789_000_000, source: "glasses", source_file: "aaaaaaaaaaaaaaaa" }),
      row({ photo_id: "g-1", taken_epoch: 1_789_000_030, source: "glasses", source_file: "aaaaaaaaaaaaaaaa" }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].source).toBe("glasses");
    expect(groups[0].shots).toHaveLength(2);
  });

  it("は眼鏡の1本ぶんを丸ごと1エントリにする", () => {
    const rows = Array.from({ length: 37 }, (_, i) =>
      row({
        photo_id: "g-" + i,
        taken_epoch: 1_789_000_000 + i * 30,
        source: "glasses",
        source_file: "aaaaaaaaaaaaaaaa",
      }),
    );
    const groups = groupPhotos(rows);
    expect(groups).toHaveLength(1);
    expect(groups[0].shots).toHaveLength(37);
  });

  it("は別の動画から来たフレームを混ぜない", () => {
    const groups = groupPhotos([
      row({ photo_id: "g-0", taken_epoch: 1_789_000_000, source: "glasses", source_file: "aaaaaaaaaaaaaaaa" }),
      row({ photo_id: "h-0", taken_epoch: 1_789_000_010, source: "glasses", source_file: "bbbbbbbbbbbbbbbb" }),
    ]);
    expect(groups).toHaveLength(2);
  });

  it("は同じ分の R1 と眼鏡を別々にする", () => {
    const groups = groupPhotos([
      row({ photo_id: "r", taken_epoch: 1_789_000_000 }),
      row({ photo_id: "g", taken_epoch: 1_789_000_005, source: "glasses", source_file: "aaaaaaaaaaaaaaaa" }),
    ]);
    expect(groups).toHaveLength(2);
    expect(groups.map((g) => g.source).sort()).toEqual(["glasses", "r1"]);
  });

  it("は時刻の昇順で返す", () => {
    const groups = groupPhotos([
      row({ photo_id: "late", taken_epoch: 1_789_000_600 }),
      row({ photo_id: "early", taken_epoch: 1_789_000_000 }),
    ]);
    expect(groups.map((g) => g.shots[0].photo_id)).toEqual(["early", "late"]);
  });

  it("は空を渡されても落ちない", () => {
    expect(groupPhotos([])).toEqual([]);
  });
});
```

- [ ] **Step 2: テストを走らせて落ちることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: FAIL — `Failed to resolve import "../src/day-group"`

- [ ] **Step 3: 実装を書く**

`cloudflare/lifelog/src/day-group.ts`:

```typescript
/**
 * How a day's frames are gathered into entries.
 *
 * The R1 takes a front/rear pair at the same instant, so grouping by the
 * minute is how the two halves of one moment find each other.
 *
 * The glasses camera breaks that. Its frames are 30 seconds apart, which puts
 * two of them in the same minute, and the minute rule would present two
 * unrelated views as if they were the two sides of one. They are grouped by
 * the file they were imported from instead: one recording, one entry, however
 * many frames it holds.
 */

export interface PhotoRow {
  photo_id: string;
  taken_at: string;
  taken_epoch: number;
  facing: string;
  caption: string | null;
  source: string;
  source_file: string | null;
}

export interface PhotoGroup {
  /** The first shot's timestamp; what the day view sorts and labels by. */
  at: string;
  source: string;
  shots: PhotoRow[];
}

export function groupPhotos(rows: PhotoRow[]): PhotoGroup[] {
  const buckets = new Map<string, PhotoRow[]>();

  for (const row of rows) {
    // A glasses frame with no source_file should not exist — putPhoto refuses
    // it — but if one ever does, falling back to the minute keeps it visible
    // rather than collapsing every stray into a single bucket.
    const key =
      row.source === "glasses" && row.source_file
        ? "glasses:" + row.source_file
        : row.source + ":" + Math.floor(row.taken_epoch / 60);

    const bucket = buckets.get(key);
    if (bucket) bucket.push(row);
    else buckets.set(key, [row]);
  }

  const groups: PhotoGroup[] = [];
  for (const shots of buckets.values()) {
    shots.sort((a, b) => a.taken_epoch - b.taken_epoch);
    groups.push({ at: shots[0].taken_at, source: shots[0].source, shots });
  }

  groups.sort((a, b) => a.shots[0].taken_epoch - b.shots[0].taken_epoch);
  return groups;
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: PASS — 8 tests in day-group

- [ ] **Step 5: `dayView` の SELECT に2列足して上限を上げる**

`src/index.ts` の `dayView` 内、写真を引く `env.DB.prepare` を差し替える。

```typescript
  const photos = await env.DB.prepare(
    `SELECT photo_id, taken_at, taken_epoch, facing, caption, source, source_file
       FROM photos
      WHERE taken_epoch >= ?1 AND taken_epoch < ?2
      ORDER BY taken_epoch ASC
      LIMIT 2000`,
  )
    .bind(from, to)
    .all<any>();
```

上限が 1000 から 2000 に上がっている。R1 だけなら1日最大576枚で余裕があったが、
3時間装着すると眼鏡側が360枚足されて上限に近づくため。

- [ ] **Step 6: グループ化を差し替える**

`// Group the pair that was taken together.` から、その下の `for (const [, shots] of byMinute) { ... }` の閉じ括弧までを丸ごと次に置き換える。

```typescript
  // Grouping lives in day-group.ts because the rule is no longer one line:
  // the R1's pair-by-minute and the glasses camera's one-entry-per-import are
  // different rules that happen to produce the same kind of entry.
  for (const group of groupPhotos(photos.results ?? [])) {
    entries.push({
      kind: "photo",
      at: group.at,
      source: group.source,
      shots: group.shots.map((s) => ({
        id: s.photo_id,
        facing: s.facing,
        caption: s.caption ?? "",
      })),
    });
  }
```

ファイル先頭の import 群に足す。

```typescript
import { groupPhotos } from "./day-group";
```

- [ ] **Step 7: 型が通ることを確認する**

Run: `cd cloudflare/lifelog && npx tsc --noEmit`
Expected: エラーなし

- [ ] **Step 8: 実データで日ビューを確認する**

Task 4 で入れた行がローカルに残っているはず。Worker を起動して引く。
```bash
npx wrangler dev
curl -s "http://127.0.0.1:8787/v1/day?date=2026-09-14" | head -40
```
Expected: `entries` に `"kind":"photo"` が出て、`source` が `glasses` / `r1` に分かれている

- [ ] **Step 9: コミット**

```bash
git add cloudflare/lifelog/src/day-group.ts cloudflare/lifelog/src/index.ts \
        cloudflare/lifelog/test/day-group.test.ts
git commit -m "Stop two glasses frames pretending to be a front and a rear"
```

---

### Task 7: `/v1/admin/photo-retime` — 時刻の引き直し

**Files:**
- Modify: `cloudflare/lifelog/src/index.ts`（ルート表と、新しい関数 `photoRetime`）

**Interfaces:**
- Consumes: Task 1 の0詰め規則（`photo_id` 昇順 = フレーム順）、Task 3 の `photos.source_file`
- Produces: `POST /v1/admin/photo-retime?source_file=<16hex>&start=<ISO8601>&interval_sec=30`

- [ ] **Step 1: ルートを足す**

`src/index.ts` のルート表、`if (request.method === "POST" && url.pathname === "/v1/admin/caption-backfill") {` のブロックの直後に足す。

```typescript
    if (request.method === "POST" && url.pathname === "/v1/admin/photo-retime") {
      return photoRetime(env, url);
    }
```

- [ ] **Step 2: ハンドラを書く**

`src/index.ts` の `captionBackfill` 関数の直後に足す。

```typescript
/**
 * Re-dates a whole import.
 *
 * The import screen derives the start time from the file's modification time,
 * which is when the camera finished writing rather than when it started. That
 * is right to within a few seconds, but the interval is a camera setting the
 * file does not carry, so getting it wrong shifts every frame.
 *
 * Without this endpoint that mistake is permanent. photo_id is derived from
 * the file's content and not from the time, so re-importing with a corrected
 * start hits `duplicate: true` and leaves the old timestamps in place. Putting
 * the time into the id instead would mean a fresh set of rows on every
 * correction. The correction belongs in its own door.
 *
 * The R2 object keeps its key, which still carries the old date. The key is a
 * name, not a fact — serveMedia reads r2_key off the row — so moving the bytes
 * would buy nothing and could lose them.
 */
async function photoRetime(env: Env, url: URL): Promise<Response> {
  const sourceFile = url.searchParams.get("source_file");
  if (!sourceFile || !/^[0-9a-f]{16}$/.test(sourceFile)) {
    return json({ error: "source_file must be 16 lowercase hex characters" }, 400);
  }

  const start = url.searchParams.get("start");
  if (!start || Number.isNaN(Date.parse(start))) {
    return json({ error: "start must be an ISO 8601 timestamp" }, 400);
  }

  const intervalSec = intParam(url, "interval_sec") ?? 30;
  if (intervalSec < 1 || intervalSec > 3600) {
    return json({ error: "interval_sec must be 1..3600" }, 400);
  }

  // Ordering by photo_id is ordering by frame, because photoIdFor pads the
  // index to four digits. That is what the padding is for.
  const rows = await env.DB.prepare(
    `SELECT photo_id FROM photos WHERE source_file = ?1 ORDER BY photo_id ASC`,
  )
    .bind(sourceFile)
    .all<{ photo_id: string }>();

  const ids = (rows.results ?? []).map((r) => r.photo_id);
  if (!ids.length) return json({ error: "no frames for that source_file" }, 404);

  const startMs = Date.parse(start);
  const statements = ids.map((id, i) => {
    const at = new Date(startMs + i * intervalSec * 1000);
    return env.DB.prepare(
      `UPDATE photos SET taken_at = ?1, taken_epoch = ?2 WHERE photo_id = ?3`,
    ).bind(at.toISOString(), Math.floor(at.getTime() / 1000), id);
  });
  await env.DB.batch(statements);

  return json({
    source_file: sourceFile,
    frames: ids.length,
    interval_sec: intervalSec,
    first: new Date(startMs).toISOString(),
    last: new Date(startMs + (ids.length - 1) * intervalSec * 1000).toISOString(),
  });
}
```

- [ ] **Step 3: テスト用の行を3つ入れる**

Run:
```bash
cd cloudflare/lifelog
npx wrangler d1 execute r1-lifelog --local --command \
  "INSERT INTO photos (photo_id, device_id, r2_key, facing, taken_at, taken_epoch, received_at, source, source_file) VALUES
   ('gffffffffffffffff-0000','glasses','photo/a.jpg','rear','2026-01-01T00:00:00.000Z',1767225600,'2026-01-01T00:00:00Z','glasses','ffffffffffffffff'),
   ('gffffffffffffffff-0001','glasses','photo/b.jpg','rear','2026-01-01T00:00:30.000Z',1767225630,'2026-01-01T00:00:00Z','glasses','ffffffffffffffff'),
   ('gffffffffffffffff-0002','glasses','photo/c.jpg','rear','2026-01-01T00:01:00.000Z',1767225660,'2026-01-01T00:00:00Z','glasses','ffffffffffffffff')"
```

- [ ] **Step 4: 引き直して確認する**

Worker を起動したまま:
```bash
curl -s -X POST "http://127.0.0.1:8787/v1/admin/photo-retime?source_file=ffffffffffffffff&start=2026-09-14T11:01:02.000Z&interval_sec=30"
```
Expected:
```json
{ "source_file": "ffffffffffffffff", "frames": 3, "interval_sec": 30,
  "first": "2026-09-14T11:01:02.000Z", "last": "2026-09-14T11:02:02.000Z" }
```

Run:
```bash
npx wrangler d1 execute r1-lifelog --local --command \
  "SELECT photo_id, taken_at, taken_epoch FROM photos WHERE source_file = 'ffffffffffffffff' ORDER BY photo_id"
```
Expected: `11:01:02` / `11:01:32` / `11:02:02`、`taken_epoch` もそれに対応して増えている

- [ ] **Step 5: 壊れた入力が弾かれることを確認する**

Run:
```bash
curl -s -X POST "http://127.0.0.1:8787/v1/admin/photo-retime?source_file=zz&start=2026-09-14T11:01:02.000Z"
curl -s -X POST "http://127.0.0.1:8787/v1/admin/photo-retime?source_file=ffffffffffffffff&start=nonsense"
curl -s -X POST "http://127.0.0.1:8787/v1/admin/photo-retime?source_file=0000000000000000&start=2026-09-14T11:01:02.000Z"
```
Expected: 順に `400`（source_file）、`400`（start）、`404`（no frames）

後始末:
```bash
npx wrangler d1 execute r1-lifelog --local --command "DELETE FROM photos WHERE source_file = 'ffffffffffffffff'"
```

- [ ] **Step 6: コミット**

```bash
git add cloudflare/lifelog/src/index.ts
git commit -m "Let a whole import be re-dated after the fact"
```

---

### Task 8: `/import` の画面 — ファイルを選んで中身を読むところまで

**Files:**
- Create: `cloudflare/lifelog/src/import.ts`
- Modify: `cloudflare/lifelog/src/index.ts`（ルート表）
- Modify: `cloudflare/lifelog/src/ui.ts`（リンク1つ）

**Interfaces:**
- Consumes: Task 1 の `photoIdFor`、Task 2 の `frameTimes`
- Produces: `GET /import` が HTML を返す。この時点ではアップロードしない。選んだ `.mov` ごとに「ファイル名 / フレーム数 / fps / 開始時刻（編集可） / 間隔」を表に出す

- [ ] **Step 1: ページを作る**

`cloudflare/lifelog/src/import.ts`:

```typescript
/**
 * The import screen.
 *
 * Separate from the archive page on purpose. ui.ts is one template string that
 * is opened every day to read what was said; video decoding and canvas work
 * have no business loading there for the sake of something used now and then.
 * The map is lazy-loaded in ui.ts for the same reason.
 *
 * A browser cannot notice a USB drive appearing, and cannot read a file the
 * person did not hand it. So the flow is: plug the camera in, press the
 * button, pick the folder. webkitdirectory rather than showDirectoryPicker,
 * because the latter is Chromium-only and this archive is read in Firefox.
 */
import { frameTimes } from "./import-time";
import { photoIdFor } from "./import-id";

export const IMPORT_HTML = `<!doctype html>
<html lang="ja"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>取り込み — lifelog</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; padding: 16px; background: #14161a; color: #e6e9ee;
         font: 15px/1.7 system-ui, sans-serif; }
  h1 { font-size: 18px; margin: 0 0 4px; }
  .sub { color: #8b95a3; font-size: 13px; margin: 0 0 20px; }
  a { color: #7fd1a0; }
  button { font: inherit; padding: 8px 16px; border-radius: 6px; border: 0;
           background: #7fd1a0; color: #14161a; font-weight: 600; cursor: pointer; }
  button:disabled { background: #3a4048; color: #8b95a3; cursor: default; }
  input[type=number], input[type=datetime-local] {
    font: inherit; background: #1c2027; color: #e6e9ee;
    border: 1px solid #3a4048; border-radius: 4px; padding: 4px 6px; }
  table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 13px; }
  th { text-align: left; color: #8b95a3; font-weight: 500; font-size: 11px;
       letter-spacing: .08em; text-transform: uppercase;
       border-bottom: 1px solid #3a4048; padding: 0 8px 6px 0; }
  td { padding: 8px 8px 8px 0; border-bottom: 1px solid #23272e; vertical-align: top; }
  .err { color: #d2a24c; }
  .muted { color: #8b95a3; }
  #log { font: 12px/1.6 ui-monospace, monospace; color: #8b95a3;
         white-space: pre-wrap; margin-top: 16px; }
</style>
</head><body>

<h1>眼鏡カメラの取り込み</h1>
<p class="sub">
  カメラを USB でつないでから、下のボタンで <code>DCIM</code> フォルダを選んでください。
  取り込み済みのフレームは自動で飛ばします。
  <a href="/ui">アーカイブに戻る</a>
</p>

<p>
  <input type="file" id="pick" webkitdirectory directory multiple accept="video/quicktime,.mov,.MOV">
</p>

<p>
  撮影間隔
  <input type="number" id="interval" min="1" max="3600" step="1" value="30"> 秒
</p>

<div id="files"></div>
<div id="log"></div>

<script>
// 純粋関数は src/import-time.ts と src/import-id.ts の実体をそのまま埋め込む。
// 実装を二重に持つと、片方だけ直したときに黙ってずれる。
const frameTimes = ${frameTimes.toString()};
const photoIdFor = ${photoIdFor.toString()};

const $ = (id) => document.getElementById(id);
const log = (s) => { $('log').textContent += s + '\\n'; };

/** Files keyed by their content hash, as analysed. */
let analysed = [];

$('pick').addEventListener('change', async (e) => {
  const movs = [...e.target.files].filter((f) => /\\.mov$/i.test(f.name));
  $('log').textContent = '';
  if (!movs.length) {
    $('files').innerHTML = '<p class="err">.mov が見つかりませんでした。</p>';
    return;
  }
  $('files').innerHTML = '<p class="muted">読み込み中…</p>';
  analysed = [];
  for (const file of movs) {
    try {
      analysed.push(await analyse(file));
    } catch (err) {
      analysed.push({ file, error: String(err && err.message || err) });
    }
  }
  render();
});

$('interval').addEventListener('change', () => {
  // 間隔が変わると開始時刻も全部変わる。読み直さずに引き直すだけでよい。
  for (const a of analysed) if (!a.error) a.times = timesFor(a);
  render();
});

function intervalSec() {
  const n = Number($('interval').value);
  return Number.isFinite(n) && n >= 1 ? n : 30;
}

// 間隔はカメラ側の設定で、ファイルには入っていない。毎回入れ直すのは
// 取り込みのたびに間違える機会を作るだけなので、この端末に覚えさせる。
try {
  const saved = localStorage.getItem('glasses.intervalSec');
  if (saved) $('interval').value = saved;
} catch (e) { /* プライベートウィンドウなど。既定の30秒で進む */ }

$('interval').addEventListener('change', () => {
  try { localStorage.setItem('glasses.intervalSec', String(intervalSec())); } catch (e) {}
});

function timesFor(a) {
  const startMs = a.startOverrideMs ?? null;
  if (startMs !== null) {
    const step = intervalSec() * 1000;
    return Array.from({ length: a.count }, (_, i) => new Date(startMs + i * step).toISOString());
  }
  return frameTimes(a.file.lastModified, a.count, intervalSec());
}

/** SHA-256 of the whole file, first 16 hex digits. */
async function hash16(file) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
}

/**
 * How many frames a second the file plays at.
 *
 * duration alone cannot give the frame count, so two consecutive frame
 * callbacks are timed instead. Firefox has requestVideoFrameCallback from 132.
 * If it is missing or the video refuses to play, 25 is assumed and the screen
 * says so.
 */
function measureFps(video) {
  return new Promise((resolve) => {
    if (!video.requestVideoFrameCallback) return resolve(null);
    const bail = setTimeout(() => resolve(null), 4000);
    let first = null;
    const tick = (_now, meta) => {
      if (first === null) {
        first = meta.mediaTime;
        video.requestVideoFrameCallback(tick);
        return;
      }
      const delta = meta.mediaTime - first;
      clearTimeout(bail);
      video.pause();
      resolve(delta > 0 ? 1 / delta : null);
    };
    video.requestVideoFrameCallback(tick);
    video.play().catch(() => { clearTimeout(bail); resolve(null); });
  });
}

function loadVideo(file) {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.preload = 'auto';
    video.src = URL.createObjectURL(file);
    video.onloadedmetadata = () => resolve(video);
    video.onerror = () => reject(new Error('このブラウザでは読めない形式です'));
  });
}

async function analyse(file) {
  const video = await loadVideo(file);
  const measured = await measureFps(video);
  const fps = measured ?? 25;
  const count = Math.max(1, Math.round(video.duration * fps));
  const a = {
    file, video, fps, count,
    fpsAssumed: measured === null,
    hash: await hash16(file),
    startOverrideMs: null,
  };
  a.times = timesFor(a);
  return a;
}

const jst = (iso) =>
  new Date(iso).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo', hour12: false });

/** datetime-local wants local wall-clock with no zone, to the second. */
function toLocalInput(iso) {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
    'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

function render() {
  if (!analysed.length) { $('files').innerHTML = ''; return; }
  const rows = analysed.map((a, i) => {
    if (a.error) {
      return '<tr><td>' + esc(a.file.name) + '</td>' +
             '<td colspan="4" class="err">' + esc(a.error) + '</td></tr>';
    }
    return '<tr>' +
      '<td>' + esc(a.file.name) + '<br><span class="muted">' + a.hash + '</span></td>' +
      '<td>' + a.count + ' 枚<br><span class="muted">' + a.fps.toFixed(2) + ' fps' +
        (a.fpsAssumed ? '（仮定）' : '') + '</span></td>' +
      '<td><input type="datetime-local" step="1" data-i="' + i + '" class="start" value="' +
        toLocalInput(a.times[0]) + '"></td>' +
      '<td>' + esc(jst(a.times[a.times.length - 1])) + '</td>' +
      '<td>' + Math.round(a.file.size / 1024) + ' KB</td>' +
      '</tr>';
  }).join('');

  $('files').innerHTML =
    '<table><thead><tr><th>ファイル</th><th>フレーム</th><th>開始（JST・編集可）</th>' +
    '<th>終了</th><th>サイズ</th></tr></thead><tbody>' + rows + '</tbody></table>';

  for (const input of document.querySelectorAll('.start')) {
    input.addEventListener('change', (e) => {
      const a = analysed[Number(e.target.dataset.i)];
      const ms = Date.parse(e.target.value);
      a.startOverrideMs = Number.isNaN(ms) ? null : ms;
      a.times = timesFor(a);
      render();
    });
  }
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}
</script>
</body></html>`;
```

- [ ] **Step 2: ルートを足す**

`src/index.ts` のルート表、`/` と `/ui` を返すブロックの直後に足す。

```typescript
    if (request.method === "GET" && url.pathname === "/import") {
      return new Response(IMPORT_HTML, {
        headers: { "content-type": "text/html; charset=utf-8" },
      });
    }
```

ファイル先頭の import 群に足す。

```typescript
import { IMPORT_HTML } from "./import";
```

- [ ] **Step 3: アーカイブ画面からリンクする**

`src/ui.ts` のタブが並んでいる箇所（`<button class="tab" id="tabMap">地図</button>` のある行）の直後に足す。

```html
  <a class="tab" href="/import" style="text-decoration:none">取り込み</a>
```

- [ ] **Step 4: 型が通ることを確認する**

Run: `cd cloudflare/lifelog && npx tsc --noEmit`
Expected: エラーなし

- [ ] **Step 5: Firefox で実ファイルを読ませる**

```bash
cd cloudflare/lifelog && npx wrangler dev
```

Firefox で `http://127.0.0.1:8787/import` を開き、`E:\DCIM` を選ぶ。

Expected:
- `MOVI0006.mov` の行に **37 枚**、**25.12 fps**（「仮定」の表示なし）
- 開始が **2026-09-14 20:01:02**、終了が **2026-09-14 20:19:02**
- `MOVI0004.mov` の行は 1 枚
- ハッシュが16桁の hex で出ている

開始時刻を `20:00:52` に手で直すと、終了も `20:18:52` に追随すること。
間隔を 60 に変えると、開始が早い方向にずれること。

- [ ] **Step 6: スクリーンショットを撮る**

`docs/superpowers/plans/screenshots/2026-09-14-import-analyse.png` として保存する。

- [ ] **Step 7: コミット**

```bash
git add cloudflare/lifelog/src/import.ts cloudflare/lifelog/src/index.ts \
        cloudflare/lifelog/src/ui.ts docs/superpowers/plans/screenshots/
git commit -m "Read what is on the card without sending anything yet"
```

---

### Task 9: `/import` の画面 — フレームを切り出して上げる

**Files:**
- Modify: `cloudflare/lifelog/src/import.ts`

**Interfaces:**
- Consumes: Task 8 の `analysed` 配列、Task 4 の `PUT /v1/photos/<id>?source=glasses&source_file=...`
- Produces: 取り込みボタンと進捗表示。`duplicate` は飛ばした件数として数える

- [ ] **Step 1: ボタンと進捗の置き場所を作る**

`import.ts` の HTML、`<div id="files"></div>` の直後に足す。

```html
<p>
  <button id="go" disabled>取り込む</button>
  <span id="progress" class="muted"></span>
</p>
```

- [ ] **Step 2: 切り出しと送信を書く**

`import.ts` の `<script>` 内、`function esc(...)` の直前に足す。

```javascript
/** Seeks and waits, because drawImage before the seek lands gives the old frame. */
function seekTo(video, t) {
  return new Promise((resolve, reject) => {
    const ok = () => { cleanup(); resolve(); };
    const ng = () => { cleanup(); reject(new Error('シークに失敗しました')); };
    const cleanup = () => {
      video.removeEventListener('seeked', ok);
      video.removeEventListener('error', ng);
    };
    video.addEventListener('seeked', ok);
    video.addEventListener('error', ng);
    video.currentTime = t;
  });
}

function toJpeg(canvas) {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('JPEG に変換できませんでした'))),
      'image/jpeg',
      0.85,
    );
  });
}

async function putFrame(blob, id, takenAt, sourceFile) {
  const q = new URLSearchParams({
    taken_at: takenAt,
    device_id: 'glasses',
    facing: 'rear',
    source: 'glasses',
    source_file: sourceFile,
  });
  const r = await fetch('/v1/photos/' + id + '?' + q.toString(), {
    method: 'PUT',
    body: blob,
  });
  if (!r.ok) throw new Error('PUT ' + id + ' が ' + r.status + ' を返しました');
  return r.json();
}

/**
 * One file, frame by frame.
 *
 * Sequential rather than parallel. The video element has one playback
 * position, so two seeks at once would race for it; the upload is the fast
 * part here, not the bottleneck.
 */
async function importFile(a, onStep) {
  const canvas = document.createElement('canvas');
  canvas.width = a.video.videoWidth || 1920;
  canvas.height = a.video.videoHeight || 1080;
  const ctx = canvas.getContext('2d');

  let stored = 0;
  let skipped = 0;

  for (let i = 0; i < a.count; i++) {
    // Aim at the middle of the frame so a rounding error in fps does not land
    // on the boundary and give the neighbour.
    const t = (i + 0.5) / a.fps;
    if (t >= a.video.duration) break;

    await seekTo(a.video, t);
    ctx.drawImage(a.video, 0, 0, canvas.width, canvas.height);
    const blob = await toJpeg(canvas);
    const answer = await putFrame(blob, photoIdFor(a.hash, i), a.times[i], a.hash);
    if (answer.duplicate) skipped += 1;
    else stored += 1;
    onStep(i + 1, a.count);
  }

  return { stored, skipped };
}

$('go').addEventListener('click', async () => {
  $('go').disabled = true;
  $('pick').disabled = true;
  try {
    for (const a of analysed) {
      if (a.error) { log(a.file.name + ' — 飛ばしました（' + a.error + '）'); continue; }
      log(a.file.name + ' — ' + a.count + ' 枚');
      const r = await importFile(a, (n, total) => {
        $('progress').textContent = a.file.name + ' ' + n + ' / ' + total;
      });
      log('  取り込み ' + r.stored + ' 枚、既出 ' + r.skipped + ' 枚');
    }
    $('progress').textContent = '完了';
  } catch (err) {
    log('中断: ' + String(err && err.message || err));
    log('もう一度押せば、入っているぶんは飛ばして続きから入ります。');
    $('progress').textContent = '';
  } finally {
    $('go').disabled = false;
    $('pick').disabled = false;
  }
});
```

- [ ] **Step 3: 解析が済んだらボタンを押せるようにする**

`render()` 関数の末尾（`for (const input of document.querySelectorAll('.start'))` のループの後）に足す。

```javascript
  $('go').disabled = !analysed.some((a) => !a.error);
```

- [ ] **Step 4: 型が通ることを確認する**

Run: `cd cloudflare/lifelog && npx tsc --noEmit`
Expected: エラーなし

- [ ] **Step 5: ローカルで1本取り込む**

```bash
cd cloudflare/lifelog && npx wrangler dev
```

Firefox で `/import` を開き、`E:\DCIM` を選び、取り込むを押す。

Expected:
- 進捗が `MOVI0006.mov 1 / 37` から `37 / 37` まで進む
- ログに `取り込み 37 枚、既出 0 枚`
- `MOVI0004.mov` は `取り込み 1 枚`

- [ ] **Step 6: 重複排除が効くことを確認する**

もう一度同じフォルダを選んで取り込む。

Expected: ログが `取り込み 0 枚、既出 37 枚`

- [ ] **Step 7: 入った行を確認する**

Run:
```bash
npx wrangler d1 execute r1-lifelog --local --command \
  "SELECT COUNT(*) AS n, MIN(taken_at) AS first, MAX(taken_at) AS last, source FROM photos WHERE source = 'glasses' GROUP BY source"
```
Expected: `n = 38`（37 + MOVI0004 の 1）、`first` が `2026-09-14T10:59:02.000Z` 前後、`last` が `2026-09-14T11:19:02.000Z`

- [ ] **Step 8: コミット**

```bash
git add cloudflare/lifelog/src/import.ts
git commit -m "Cut the frames out and send them up"
```

---

### Task 10: 通しで確認して記録を残す

**Files:**
- Create: `docs/superpowers/plans/screenshots/2026-09-14-import-dayview.png`
- Modify: `docs/superpowers/plans/2026-09-14-glasses-timelapse-import.md`（この計画の末尾に結果を書く）

**Interfaces:**
- Consumes: Task 1〜9 のすべて
- Produces: 動いた記録

- [ ] **Step 1: ローカルの D1 をきれいにして、一度だけ通す**

```bash
cd cloudflare/lifelog
npx wrangler d1 execute r1-lifelog --local --command "DELETE FROM photos WHERE source = 'glasses'"
npx wrangler dev
```

Firefox で `/import` → `E:\DCIM` → 取り込む。

- [ ] **Step 2: キャプションが付くのを待って確認する**

Run:
```bash
npx wrangler d1 execute r1-lifelog --local --command \
  "SELECT status, COUNT(*) AS n FROM photos WHERE source = 'glasses' GROUP BY status"
```
Expected: しばらくすると `captioned` が増える。`failed` が出たら `error` 列を読む

Run:
```bash
npx wrangler d1 execute r1-lifelog --local --command \
  "SELECT photo_id, taken_at, caption FROM photos WHERE source = 'glasses' ORDER BY photo_id LIMIT 3"
```
Expected: キャプションが「何を見ていたか」を述べている。R1 用の「場所の様子」調ではない

- [ ] **Step 3: 日ビューを開いて見え方を確認する**

Firefox で `http://127.0.0.1:8787/ui` を開き、日付を `2026-09-14` にする。

Expected:
- 眼鏡カメラの37枚が **1つのエントリ**にまとまっている
- R1 の写真と混ざって前後の対にはなっていない
- 時刻が 20:01 から 20:19 の範囲に並んでいる

- [ ] **Step 4: スクリーンショットを撮る**

`docs/superpowers/plans/screenshots/2026-09-14-import-dayview.png` として保存する。

- [ ] **Step 5: 時刻の引き直しを通しで試す**

Run:
```bash
# 取り込んだ動画のハッシュを D1 から取る。画面を見返さなくてよい。
npx wrangler d1 execute r1-lifelog --local --command \
  "SELECT source_file, COUNT(*) AS n FROM photos WHERE source = 'glasses' GROUP BY source_file"

# 上で 37 枚と出たほうの source_file を、以下の SOURCE_FILE に入れる
curl -s -X POST "http://127.0.0.1:8787/v1/admin/photo-retime?source_file=SOURCE_FILE&start=2026-09-14T11:00:52.000Z&interval_sec=30"

npx wrangler d1 execute r1-lifelog --local --command \
  "SELECT MIN(taken_at), MAX(taken_at) FROM photos WHERE source_file = 'SOURCE_FILE'"
```
Expected: 10秒ぶん前にずれる

- [ ] **Step 6: テストを全部走らせる**

Run: `cd cloudflare/lifelog && npm test && npx tsc --noEmit`
Expected: 全部 PASS、型エラーなし

- [ ] **Step 7: 結果をこの計画の末尾に書く**

「実行結果」の節を足し、ターン数（予定10タスク / 実際）、詰まったところ、
設計側の見落としだったか実装側だったかを書く。
書式は `docs/superpowers/specs/2026-09-09-feed-media-design.md` の「ターン数」に合わせる。

- [ ] **Step 8: コミット**

```bash
git add docs/superpowers/plans/
git commit -m "Show the glasses timelapse landing in the day view"
```

---

## 実行結果

Task 1〜9 を実装済み。ブランチ `feature/glasses-import` に14コミット。
テストは23/23 PASS、型チェックは既存の1件を除いて新規エラーなし。
ただし **ブラウザでの動作は一度も確認していない**（理由は後述）。

### 作ったもの

| Task | 内容 | コミット |
| --- | --- | --- |
| 1 | `photoIdFor` 純粋関数、vitest導入 | `753f2fd` |
| 2 | `frameTimes` 純粋関数 | `c9c9624` |
| 3 | migration 0017（`photos.source` / `source_file`） | `483dcba` |
| 4 | `putPhoto` が出所を受け取る | `b5c5d4c` |
| 5 | 出所別のキャプションプロンプト、typescript導入 | `ed6436b`, `46b2166`（修正: `adc9791`） |
| 6 | `dayView` のグループ化を出所で分岐 | `3fb1eb8`（修正: `f4efccf`） |
| 7 | `/v1/admin/photo-retime` | `65a6f74` |
| 8 | `/import` 画面（読み取りのみ） | `a8c368b`（修正: `a06b167`） |
| 9 | `/import` アップロード側 | `5f480e1`（修正: `4d92506`） |

### テスト・型チェックの状態

`cd cloudflare/lifelog && npm test`

```
Test Files  4 passed (4)
     Tests  23 passed (23)
```

`cd cloudflare/lifelog && npm run typecheck`

```
src/index.ts(243,9): error TS2322: Type '(batch: MessageBatch<TranscribeMessage>, env: Env) => Promise<void>'
  is not assignable to type 'ExportedHandlerQueueHandler<Env, unknown, unknown>'.
```

このエラーはキューハンドラの型で、この計画より前から存在する（Task 5 の時点で `src/index.ts:233`
として確認済み。以降タスクが `import` を1行足すたびに行番号だけ動いている）。直していない。

### 検証していないこと

Task 8 Step 5、Task 9 Step 5〜7、Task 10 Step 1〜5 ―― ブラウザでの通し確認は**一度も実行していない**。

ローカルの `wrangler dev` には Cloudflare Access が無く、`GET /import` はブラウザから 401 になって
画面自体が開けない。本番は `authorise` が Access のアサーションで `admin` を返す前提（`src/index.ts:378`）
なので、ローカルで再現するには開発用バイパスを足すかデプロイして実機確認するかの二択で、
どちらもスキル規定の停止確認に該当する（Ruling 3）。利用者が「実装だけ進め、確認は自分でやる」を
選んだため、Task 8・9 はコード＋型チェックまでで完了扱いとし、この Task 10 はデプロイ後に
利用者が自分で回すチェックリストを書く作業に変わった。

純粋関数（`photoIdFor` / `frameTimes` / `captionPromptFor` / `groupPhotos`）は単体テスト済みで、
そこは自信がある。動いていないのは `import.ts` のブラウザ側コード（動画デコード・fps測定・
canvas描画・アップロード）で、`node --check` による構文チェックと手計算でのトレースはしたが、
実際の `.mov` を読ませたことは一度もない。

### 実装を変えたルーリング

| # | 何があったか | 実装への影響 |
| --- | --- | --- |
| 1 | ブリーフが `askAboutImage` という存在しないヘルパーへの編集を指示していた | Task 5 のブリーフを実在コード（`env.AI.run` 直呼び、`src/index.ts:1209`）に書き換えてから実装させた |
| 3 | ブラウザでの取り込みはローカルで検証不能と判明 | Task 8/9 はコード＋型チェックまでで完了、Task 10 をチェックリスト作成に変更（このタスクの成立理由） |
| 4 | `typescript` が devDependency に無く `tsc --noEmit` が毎回オフラインで失敗する | Task 5 の先頭に typescript 追加＋`typecheck` スクリプト作成を追加。以降のタスクは `npm run typecheck` を使用 |
| 5 | レビュアーが「`captionPhoto` の D1 読み取りは不要」と指摘 | 却下し、読み取りを残した（理由3つ：source を持たない旧キューメッセージがある／モデル呼び出しの隣でコストにならない／source の権威は行にある） |
| 6 | 「同じ分に入る眼鏡の2フレーム」テストの epoch が実際には同じ分をまたいでいた | テストデータのみ修正。実装コード（`day-group.ts`）は無罪 |
| 7 | `measureFps` が rVFC の2回の呼び出しを常に連続フレームと仮定していた（1枚飛ばされると fps・枚数とも半分に出る） | `presentedFrames` の差で10フレーム分を平均する形に修正。あわせて失敗経路での `video.pause()` 漏れも修正 |
| 8 | `importFile` に Important 3件（打ち切り境界の off-by-one、seek が settle しない経路、`videoWidth=0` の黒フレーム） | 全部修正。境界を `>` に、seek に10秒タイムアウト、ゼロ次元は例外＋canvas clear |
| 9 | 1本の失敗が残り全部を巻き添えにするのは、設計書の「失敗の見え方」表（そのファイルだけ失敗、他は続行）への違反 | per-file isolation を追加。あわせて失敗時に `#progress` が空になる挙動も修正 |

### 先送りにした minor（着手は任意）

| Task | 内容 |
| --- | --- |
| 1 | `import-id.ts` の throw が素の `Error` のまま（レビュアー自身が「対応不要」と明記） |
| 3 | migration 0017 の `ALTER TABLE` に `IF NOT EXISTS` が無い（SQLite 非対応。0011 と同じ既存の露出） |
| 4 | `source_file` 正規表現の拒否パスに自動テストが無い（ブリーフは手動 curl のみ要求） |
| 5 | レビュー報告の行番号ずれの説明が誤っていた（結論「既存エラー」自体は正しい） |
| 5 | `tsconfig.json` の `"types"` が非標準の使い方（ambient型は実際には読めている） |
| 6 | `day-group.ts` のフォールバックキー（`"glasses:" + 分`）が実在の `source_file` と名前空間を共有しうる |
| 8 | 開始列のラベルは JST 表記だが値はローカル壁時計（非 JST 機では終了列と食い違う） |
| 8 | `render()` が編集中の `input` を作り直すためフォーカスが落ちる |
| 8 | `intervalSec()` が `max=3600` を無視し、不正値を黙って30に丸める |
| 8 | 「取り込み済みは自動で飛ばす」という画面冒頭の文言は Task 8 時点では嘘だったが、Task 9 の完了で真になった（解消済み） |
| 8 | `analysed` の JSDoc が「content hash でキー付け」と書いているが実体は配列 |
| 8 | `loadVideo` の reject 経路で object URL が revoke されない |
| 8 | 開始時刻を編集したあとに間隔を変えると override が残る（レビュアーも妥当と判断、未文書） |
| 9 | 外側の `try` が `catch` を失い `try/finally` になった。内側の per-file `try` の外で投げられた例外は unhandled rejection になりログに出ない（`finally` は効くのでボタンは戻る） |

## デプロイ後に確認すること

`npm run deploy` のあと、`https://lifelog.mantaroh.org` に対して実機（Firefox、実際の眼鏡カメラの
`.mov`）で確認する。以下は `MOVI0006.mov`（37フレーム、更新日時 `2026-09-14T20:19:02+09:00`、
逆算した開始時刻 `2026-09-14T20:01:02+09:00`、1920×1080、2.5 MB）を基準にした期待値。
実際のファイルはこれと違う可能性があるので、数値は目安として読む。

**1. `/import` が開けることの確認**

やること: Firefox で `https://lifelog.mantaroh.org/import` を開く。

正しい結果: Cloudflare Access のログインを経て画面が開く。ローカルでは401になっていた経路なので、
これが通ること自体がこのタスクで初めて確認される部分。

**2. フォルダを選ぶ**

やること: カメラを USB でつなぎ、「取り込み」画面の入力から `DCIM` フォルダを選ぶ。

正しい結果: `MOVI0006.mov` の行に **37 枚**、fps は **25.12 前後**（「（仮定）」の表示が出ないのが理想）。
開始時刻の編集欄が **2026-09-14 20:01:02** 前後、終了が **20:19:02**。サイズは **2.5 MB 前後**
（2,618,880 バイト）。ハッシュが16桁の hex で出ている。

**3. 取り込む**

やること: 「取り込む」ボタンを押す。

正しい結果: 進捗が `1 / 37` から `37 / 37` まで進み、ログに「取り込み 37 枚、既出 0 枚」と出る。

> ⚠️ **ここで、レビューで直したが一度もブラウザで動いていない3箇所が関わる。実際の `.mov` が
> 一番驚きをもたらしやすい場所。**
>
> - **フレームレート測定**（`measureFps`, Ruling 7）: 実機の `requestVideoFrameCallback` で正しく
>   測れれば 25.12 fps 前後・37枚が出る。「（仮定）」と出て 25 fps にフォールバックした場合、
>   枚数が `round(duration × 25)` になるため 37 から ±1 ずれることがある。ずれても記録は壊れない
>   （手順6の `photo-retime` で直せる）が、まず「ずれたかどうか」を見る。
> - **seek のタイムアウト**（`seekTo`, Ruling 8）: 1フレームのシークが10秒応答しなければ
>   「シークに失敗しました」がログに出て中断する。実機でこの経路を一度も通っていないので、
>   正常系で誤発火しないか（＝取り込みが途中で止まらないか）を確認する。
> - **ゼロ次元ガード**（`importFile`, Ruling 8）: 動画のメタデータが読めず `videoWidth`/`videoHeight`
>   が0のままだと例外で止まる（直す前は黒い JPEG がそのまま200で入っていた）。1920×1080が
>   正しく取れていれば発火しないはずだが、これも実機初検証。

**4. キャプションが付くのを待つ**

やること:
```bash
npx wrangler d1 execute r1-lifelog --remote --command \
  "SELECT status, COUNT(*) AS n FROM photos WHERE source = 'glasses' GROUP BY status"
```

正しい結果: `captioned` が増えていく。眼鏡カメラは1時間あたり120枚・キュー処理約10分の密度
（設計書の実測値）なので、37枚なら数分で揃うはず。`failed` が出たら `error` 列を読む。

続けて:
```bash
npx wrangler d1 execute r1-lifelog --remote --command \
  "SELECT photo_id, taken_at, caption FROM photos WHERE source = 'glasses' ORDER BY photo_id LIMIT 3"
```

正しい結果: キャプションが「何を見ていたか」を述べている。R1 用の「場所の様子」調ではない。
プロンプトは文字が読める場合はその内容を含めるよう指示しているので、画面や看板が写っていれば
文字起こしが混じっているかも見る。

**5. 日ビューで見え方を確認する**

やること: `https://lifelog.mantaroh.org/ui` を開き、日付を取り込んだ日（`MOVI0006.mov` なら
`2026-09-14`）にする。

正しい結果:
- 眼鏡カメラの37枚が **1つのエントリ** にまとまっている
- R1 の写真と混ざって前後の対になっていない
- 時刻が **20:01 から 20:19** の範囲に並んでいる

**6. 開始時刻がずれていた場合の引き直し**

`source_file` を調べる:
```bash
npx wrangler d1 execute r1-lifelog --remote --command \
  "SELECT source_file, COUNT(*) AS n FROM photos WHERE source = 'glasses' GROUP BY source_file"
```

引き直す:
```bash
curl -s -X POST "https://lifelog.mantaroh.org/v1/admin/photo-retime?source_file=<16桁hex>&start=<正しい開始ISO8601>&interval_sec=30" \
  -H "Authorization: Bearer <ADMIN_TOKEN>"
```

正しい結果: `{"source_file":"...","frames":37,"interval_sec":30,"first":"...","last":"..."}` が
返り、D1 の `taken_at` / `taken_epoch` が引き直される。

> ⚠️ **このエンドポイントは admin 権限がいる。** ブラウザの Cloudflare Access セッションは
> navigation にしか効かないため、curl から叩くには `ADMIN_TOKEN` を `Bearer` で渡す必要がある
> （`src/index.ts` の `principalFor`）。本番 Worker にこのシークレットが設定済みか
> `npx wrangler secret list` で確認し（値は出ない）、無ければ `npx wrangler secret put ADMIN_TOKEN`
> で用意してから叩くこと。

## 次にやること

**第2弾（`docs/superpowers/specs/2026-09-14-caption-corpus-design.md`）に必ず進むこと。**

この計画を完走した時点で、フレームは溜まりキャプションも書かれているが、
`searchData` も `contextData` も `segments` しか見ていないので、
検索にもエージェントにも一切届かない。目的は半分しか果たされていない。

`BACKLOG.md` の `## Agreed: what I was looking at, as a corpus` に同じことが書いてある。

## ターン数

- 実装: 予定 10タスク / 実際 10タスク、修正ラウンド 4回（Task 5, 6, 8, 9 各1回）

修正ラウンドの内訳と、その原因が設計側にあったか実装側にあったか。

| どこで | 何が | 原因 |
| --- | --- | --- |
| Task 5 | `npm add -D typescript` が `^7.0.2` の未固定バージョンで入った | 実装（バージョンを固定していなかった。レビュー指摘で `^5` に固定） |
| Task 6 | 「同じ分に入る眼鏡の2フレーム」テストの epoch が実際には同じ分をまたいでいた | 設計（ブリーフのテストデータの誤り。実装コードは無罪） |
| Task 8 | `measureFps` が rVFC の2回の呼び出しを常に連続フレームと仮定していた | 設計（ブリーフのコード） |
| Task 9 | 打ち切り境界の off-by-one、seek が settle しない経路、`videoWidth=0` の黒フレーム、1本の失敗が他ファイルを巻き添えにする仕様違反 | 設計（ブリーフのコード3件）＋仕様（失敗の見え方表との不一致1件） |
