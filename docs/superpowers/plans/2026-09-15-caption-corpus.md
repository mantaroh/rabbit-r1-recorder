# 写真のキャプションをコーパスにする 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** `photos.caption` を全文検索とエージェントから引けるようにし、書き込み専用だった4,300件のキャプションを読み出せる状態にする。

**Architecture:** `segments_fts` と同じ trigram トークナイザで `photos_fts` を作り、トリガで同期する。`searchData` が音声と写真を別々に引いて時刻順にマージし、`contextData` は既存の `text` を触らずに `seen` を追加する。MCP はツールを足さず説明文だけ直す。

**Tech Stack:** Cloudflare Workers / D1 / SQLite FTS5、TypeScript、vitest

**Spec:** [docs/superpowers/specs/2026-09-14-caption-corpus-design.md](../specs/2026-09-14-caption-corpus-design.md)

**第1弾:** [眼鏡カメラのタイムラプス取り込み](2026-09-14-glasses-timelapse-import.md) — 完了・本番反映済み。この計画はその対になる読み出し側。

## Global Constraints

- 作業ディレクトリは `cloudflare/lifelog`。パッケージマネージャは **npm**（`package-lock.json` がある）
- ブランチは `feature/caption-corpus`。`main` に直接コミットしない。push もしない
- **`--remote` を付けた D1 / wrangler コマンドを一切実行しない。** 本番反映は人間が別途判断する
- デプロイしない（`npm run deploy` を実行しない）
- コミットメッセージに AI の製品名・モデル名・`Co-Authored-By`・「generated with」/「Claude-Session」trailer・絵文字署名を入れない
- `principalFor` / `authorise` / `DEVICE_ROUTES` を変更しない
- **`npm run typecheck` は clean にならない。** `src/index.ts` の queue ハンドラに `MessageBatch<TranscribeMessage>` の型不一致が1件あり、この計画より前から存在する。基準は「**新しいエラーが増えていないこと**」。直さない
- 型チェックは `npm run typecheck`、テストは `npm test`（現在 30 件）
- Git Bash では `wrangler d1 execute --command` に複数行 SQL を渡すと `SQLITE_ERROR: incomplete input` になる。複数行は `--file` を使う
- ローカルの認証トークンは `cloudflare/lifelog/.dev.vars`（git 管理外）に `INGEST_TOKEN` と `ADMIN_TOKEN`。`/health` 以外の全ルートに認証がかかる。**トークンの値を報告書に書かない**

## 実データ（2026-09-15 時点、本番から読み取り）

この計画が索引に入れる対象。

| 出所 | 件数 | キャプション済み |
| --- | --- | --- |
| R1 | 4,241 | 4,206 |
| 眼鏡 | 118 | 104（残りはキュー処理中） |

眼鏡のキャプションは中央値300文字で、**76% が文末で途切れている**（`CAPTION_MAX_TOKENS = 220` の上限に当たっている）。
15% に同一文の繰り返しがある。R1 は中央値90文字、途切れ11%、繰り返し3%。

**この品質問題はこの計画では直さない。** 索引の作り方は中身の品質と独立しており、
キャプションを付け直せばトリガが索引を更新する。プロンプトと上限の見直しは別途判断する。

## ファイル構成

| ファイル | 責務 |
| --- | --- |
| `src/search-merge.ts` | 新規。`mergeResults(audio, photos, limit)` — 純粋関数 |
| `migrations/0018_photo_caption_fts.sql` | 新規。`photos_fts` とトリガ2つ、既存ぶんの投入 |
| `src/index.ts` | 変更。`searchData` / `contextData` |
| `src/mcp.ts` | 変更。2ツールの `description` |
| `src/ui.ts` | 変更。検索結果の描画 |
| `test/search-merge.test.ts` | 新規 |

---

### Task 1: `mergeResults` を書く

**Files:**
- Create: `cloudflare/lifelog/src/search-merge.ts`
- Test: `cloudflare/lifelog/test/search-merge.test.ts`

**Interfaces:**
- Consumes: なし
- Produces: 型 `SearchHit`、関数 `mergeResults(audio: SearchHit[], photos: SearchHit[], limit: number): SearchHit[]`

- [ ] **Step 1: 失敗するテストを書く**

`cloudflare/lifelog/test/search-merge.test.ts`:

```typescript
import { describe, expect, it } from "vitest";
import { mergeResults, type SearchHit } from "../src/search-merge";

const audio = (at: string, id = "seg-" + at): SearchHit => ({
  kind: "audio", id, at, text: "音声 " + at, source: null,
});
const photo = (at: string, id = "pic-" + at): SearchHit => ({
  kind: "photo", id, at, text: "写真 " + at, source: "glasses",
});

describe("mergeResults", () => {
  it("は時刻の降順で混ぜる", () => {
    const out = mergeResults(
      [audio("2026-09-14T10:00:00.000Z"), audio("2026-09-14T08:00:00.000Z")],
      [photo("2026-09-14T09:00:00.000Z"), photo("2026-09-14T07:00:00.000Z")],
      10,
    );
    expect(out.map((h) => h.at)).toEqual([
      "2026-09-14T10:00:00.000Z",
      "2026-09-14T09:00:00.000Z",
      "2026-09-14T08:00:00.000Z",
      "2026-09-14T07:00:00.000Z",
    ]);
    expect(out.map((h) => h.kind)).toEqual(["audio", "photo", "audio", "photo"]);
  });

  it("は limit で切る", () => {
    const out = mergeResults(
      [audio("2026-09-14T10:00:00.000Z"), audio("2026-09-14T08:00:00.000Z")],
      [photo("2026-09-14T09:00:00.000Z")],
      2,
    );
    expect(out).toHaveLength(2);
    // 新しいほうから残る。片方に件数を割り当てないので、
    // その時間帯にどちらが多かったかは検索語のほうが決める。
    expect(out.map((h) => h.kind)).toEqual(["audio", "photo"]);
  });

  it("は片方が空でも動く", () => {
    expect(mergeResults([], [photo("2026-09-14T09:00:00.000Z")], 10)).toHaveLength(1);
    expect(mergeResults([audio("2026-09-14T09:00:00.000Z")], [], 10)).toHaveLength(1);
    expect(mergeResults([], [], 10)).toEqual([]);
  });

  it("は同時刻でも両方を残す", () => {
    const at = "2026-09-14T09:00:00.000Z";
    const out = mergeResults([audio(at)], [photo(at)], 10);
    expect(out).toHaveLength(2);
    expect(out.map((h) => h.id).sort()).toEqual(["pic-" + at, "seg-" + at].sort());
  });

  it("は limit が 0 以下なら空を返す", () => {
    expect(mergeResults([audio("2026-09-14T09:00:00.000Z")], [], 0)).toEqual([]);
    expect(mergeResults([audio("2026-09-14T09:00:00.000Z")], [], -1)).toEqual([]);
  });

  it("は入力を書き換えない", () => {
    const a = [audio("2026-09-14T08:00:00.000Z"), audio("2026-09-14T10:00:00.000Z")];
    const before = a.map((h) => h.at);
    mergeResults(a, [], 10);
    expect(a.map((h) => h.at)).toEqual(before);
  });
});
```

- [ ] **Step 2: テストを走らせて落ちることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: FAIL — `Failed to resolve import "../src/search-merge"`

- [ ] **Step 3: 実装を書く**

`cloudflare/lifelog/src/search-merge.ts`:

```typescript
/**
 * One hit, whichever kind of recording it came out of.
 *
 * Audio and photographs answer the same question — "when did I come across
 * this" — so they arrive in one list rather than two. The caller needs to know
 * which it is holding to render it, and, for a photograph, which camera took
 * it: a hit from the R1 is the room the user was in, a hit from the glasses is
 * what they were looking at, and those are not the same claim.
 */
export interface SearchHit {
  kind: "audio" | "photo";
  id: string;
  at: string;
  text: string;
  /** `r1` | `glasses` for photographs; null for audio, which has no camera. */
  source: string | null;
}

/**
 * Newest first, both kinds in one list, cut at `limit`.
 *
 * No quota per kind. How much of a given hour was talking and how much was
 * looking is the search term's business, not this function's — reserving half
 * the page for audio would hide a day spent reading.
 */
export function mergeResults(
  audio: SearchHit[],
  photos: SearchHit[],
  limit: number,
): SearchHit[] {
  if (!Number.isFinite(limit) || limit <= 0) return [];
  return [...audio, ...photos]
    .sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
    .slice(0, limit);
}
```

- [ ] **Step 4: テストが通ることを確認する**

Run: `cd cloudflare/lifelog && npm test`
Expected: PASS — 30 既存 + 6 新規 = 36

- [ ] **Step 5: コミット**

```bash
git add cloudflare/lifelog/src/search-merge.ts cloudflare/lifelog/test/search-merge.test.ts
git commit -m "Put both kinds of recording in one result list"
```

---

### Task 2: マイグレーション 0018 — キャプションの全文検索

**Files:**
- Create: `cloudflare/lifelog/migrations/0018_photo_caption_fts.sql`

**Interfaces:**
- Consumes: `photos.caption` / `photos.photo_id` / `photos.taken_at`
- Produces: 仮想テーブル `photos_fts(caption, photo_id UNINDEXED, taken_at UNINDEXED)`、トリガ `photos_fts_update` と `photos_fts_delete`

- [ ] **Step 1: マイグレーションを書く**

`cloudflare/lifelog/migrations/0018_photo_caption_fts.sql`:

```sql
-- 写真キャプションの全文検索。
--
-- ここまで photos.caption は書き込み専用だった。dayView と captionBackfill の
-- 2箇所しか読んでおらず、searchData も contextData も segments しか見ていない
-- ため、R1 が 0011 以降ずっと書き溜めてきた4千件は誰にも引かれていない。
--
-- トークナイザは segments_fts と同じ trigram。理由も同じで、unicode61 は CJK を
-- 空白までひとかたまりに扱うため日本語の部分一致が効かない
-- （0002_trigram_fts.sql に「負け犬 で5件中0ヒット」という実測が残っている）。
CREATE VIRTUAL TABLE IF NOT EXISTS photos_fts USING fts5(
  caption,
  photo_id UNINDEXED,
  taken_at UNINDEXED,
  tokenize = 'trigram'
);

-- 0006 で segments 側が本番で踏んだ穴を、こちらは最初から避ける。
--
-- 「非空のときだけ発火」にすると、キャプションが空に訂正されたときに古い
-- テキストが索引に残り続ける。DELETE は無条件、INSERT だけ条件付きにする。
CREATE TRIGGER IF NOT EXISTS photos_fts_update
AFTER UPDATE OF caption ON photos
BEGIN
  DELETE FROM photos_fts WHERE photo_id = new.photo_id;
  INSERT INTO photos_fts(caption, photo_id, taken_at)
  SELECT new.caption, new.photo_id, new.taken_at
   WHERE new.caption IS NOT NULL AND new.caption <> '';
END;

CREATE TRIGGER IF NOT EXISTS photos_fts_delete
AFTER DELETE ON photos
BEGIN
  DELETE FROM photos_fts WHERE photo_id = old.photo_id;
END;

-- 溜まっているぶんを全部入れる。この計画の主目的のひとつ。
INSERT INTO photos_fts(caption, photo_id, taken_at)
SELECT caption, photo_id, taken_at
  FROM photos
 WHERE caption IS NOT NULL AND caption <> '';
```

キャプションは `captionPhoto` が `UPDATE` で書く（`INSERT` 時は NULL）ので、
`AFTER UPDATE OF caption` で拾える。`AFTER INSERT` は要らない。

- [ ] **Step 2: ローカルに当てる**

Run:
```bash
cd cloudflare/lifelog
npx wrangler d1 migrations apply r1-lifelog --local
```
Expected: `0018_photo_caption_fts.sql` が適用されたと出る

- [ ] **Step 3: 索引ができたことを確認する**

Run:
```bash
npx wrangler d1 execute r1-lifelog --local --command "SELECT COUNT(*) AS n FROM photos_fts"
```
Expected: ローカルにキャプション付きの行があればその件数。0 でも次で確認できる

- [ ] **Step 4: トリガの3手順を必ず実行する**

0006 は「トリガが正しいはず」で通してから本番で穴が見つかっている。同じ確認を省かない。
複数行 SQL は Git Bash で壊れるので、1文ずつ実行するか `--file` を使う。

```bash
# (a) 行を入れてキャプションを付ける → 索引に出る
npx wrangler d1 execute r1-lifelog --local --command "INSERT INTO photos (photo_id, device_id, r2_key, facing, taken_at, taken_epoch, received_at, source) VALUES ('ftstest0','r1','photo/x.jpg','front','2026-09-14T00:00:00Z',1789344000,'2026-09-14T00:00:00Z','r1')"
npx wrangler d1 execute r1-lifelog --local --command "UPDATE photos SET caption = 'テスト用のキャプション', status = 'captioned' WHERE photo_id = 'ftstest0'"
npx wrangler d1 execute r1-lifelog --local --command "SELECT photo_id FROM photos_fts WHERE photos_fts MATCH '\"キャプション\"'"
```
Expected: `ftstest0` が返る

```bash
# (b) キャプションを空に訂正する → 索引から消える  ← 0006 の穴の再発確認
npx wrangler d1 execute r1-lifelog --local --command "UPDATE photos SET caption = '' WHERE photo_id = 'ftstest0'"
npx wrangler d1 execute r1-lifelog --local --command "SELECT COUNT(*) AS n FROM photos_fts WHERE photo_id = 'ftstest0'"
```
Expected: `n = 0`

```bash
# (c) 行を削除する → 索引からも消える
npx wrangler d1 execute r1-lifelog --local --command "UPDATE photos SET caption = 'もう一度' WHERE photo_id = 'ftstest0'"
npx wrangler d1 execute r1-lifelog --local --command "DELETE FROM photos WHERE photo_id = 'ftstest0'"
npx wrangler d1 execute r1-lifelog --local --command "SELECT COUNT(*) AS n FROM photos_fts WHERE photo_id = 'ftstest0'"
```
Expected: `n = 0`

この3手順の出力を報告書に全部貼ること。

- [ ] **Step 5: コミット**

```bash
git add cloudflare/lifelog/migrations/0018_photo_caption_fts.sql
git commit -m "Index the captions nobody could search"
```

---

### Task 3: `searchData` が写真も引く

**Files:**
- Modify: `cloudflare/lifelog/src/index.ts`（`export async function searchData`）

**Interfaces:**
- Consumes: Task 1 の `mergeResults` / `SearchHit`、Task 2 の `photos_fts`
- Produces: `/v1/search` の `results` が `{kind, id, at, text, source}` の配列になる

- [ ] **Step 1: import を足す**

`src/index.ts` の import 群に足す。

```typescript
import { mergeResults, type SearchHit } from "./search-merge";
```

- [ ] **Step 2: `searchData` を書き換える**

`export async function searchData` の本体を丸ごと次に置き換える。
3文字未満で LIKE 走査に落とす既存の出し分けは、trigram が3文字窓である以上
写真側にもそのまま当てはまるので、同じ条件を使う。

```typescript
export async function searchData(env: Env, q: string, rawLimit: number) {
  const limit = Math.min(rawLimit, 100);
  const term = q.trim();

  // The trigram tokenizer indexes 3-character windows, so it cannot match a
  // query shorter than that — and in Japanese one- and two-character terms
  // (敵, 駅, 予定) are ordinary things to search for. Measured: 負け犬 hits,
  // トモ does not. Scan for those instead of returning a confident zero.
  const useFts = [...term].length >= 3;
  const quoted = `"${term.replace(/"/g, '""')}"`;
  const liked = `%${term.replace(/[\\%_]/g, (c) => "\\" + c)}%`;

  const audioRows = useFts
    ? await env.DB.prepare(
        `SELECT f.segment_id, f.started_at, s.transcript
           FROM segments_fts f
           JOIN segments s ON s.segment_id = f.segment_id
          WHERE segments_fts MATCH ?1
            AND s.stock_phrase = 0
            AND (s.speech_ratio IS NULL OR s.speech_ratio >= ?3)
          ORDER BY s.started_epoch DESC
          LIMIT ?2`,
      )
        .bind(quoted, limit, MIN_SPEECH_RATIO)
        .all<any>()
    : await env.DB.prepare(
        `SELECT segment_id, started_at, transcript
           FROM segments
          WHERE transcript LIKE ?1 ESCAPE '\\'
            AND stock_phrase = 0
            AND (speech_ratio IS NULL OR speech_ratio >= ?3)
          ORDER BY started_epoch DESC
          LIMIT ?2`,
      )
        .bind(liked, limit, MIN_SPEECH_RATIO)
        .all<any>();

  // 'captioned' and a non-empty caption both matter: a row can be 'failed'
  // with an error and no caption, and one can be captioned with an empty
  // string when the model returned nothing. Neither is a search result.
  const photoRows = useFts
    ? await env.DB.prepare(
        `SELECT f.photo_id, f.taken_at, p.caption, p.source
           FROM photos_fts f
           JOIN photos p ON p.photo_id = f.photo_id
          WHERE photos_fts MATCH ?1
            AND p.status = 'captioned'
            AND p.caption IS NOT NULL AND p.caption <> ''
          ORDER BY p.taken_epoch DESC
          LIMIT ?2`,
      )
        .bind(quoted, limit)
        .all<any>()
    : await env.DB.prepare(
        `SELECT photo_id, taken_at, caption, source
           FROM photos
          WHERE caption LIKE ?1 ESCAPE '\\'
            AND status = 'captioned'
            AND caption IS NOT NULL AND caption <> ''
          ORDER BY taken_epoch DESC
          LIMIT ?2`,
      )
        .bind(liked, limit)
        .all<any>();

  const audio: SearchHit[] = (audioRows.results ?? []).map((r: any) => ({
    kind: "audio",
    id: r.segment_id,
    at: r.started_at,
    text: String(r.transcript ?? ""),
    source: null,
  }));

  const photos: SearchHit[] = (photoRows.results ?? []).map((r: any) => ({
    kind: "photo",
    id: r.photo_id,
    at: r.taken_at,
    text: String(r.caption ?? ""),
    source: r.source ?? "r1",
  }));

  const results = mergeResults(audio, photos, limit);

  return {
    q: term,
    mode: useFts ? "fts" : "scan",
    count: results.length,
    audio_matched: audio.length,
    photo_matched: photos.length,
    results,
  };
}
```

> 破壊的変更。`results` の要素は `segment_id` / `started_at` / `transcript` を
> 持たなくなり、`id` / `at` / `text` / `kind` / `source` になる。
> 読む側は `src/ui.ts`（Task 5）と MCP（Task 6 は説明文のみ）。
> 利用者は本人1人なので互換フィールドは残さない。

- [ ] **Step 3: 型が通ることを確認する**

Run: `cd cloudflare/lifelog && npm run typecheck`
Expected: `MessageBatch<TranscribeMessage>` の既存エラー1件のみ。新規なし

- [ ] **Step 4: ローカルで引けることを確認する**

別の端末で Worker を起動したまま:
```bash
cd cloudflare/lifelog && npx wrangler dev
```

トークンは `.dev.vars` から読む。**値は報告書に書かない。**
```bash
# 写真だけがヒットする語で引く
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8787/v1/search?q=キャプション&limit=10"
```
Expected: `results` の要素が `{"kind":"photo","id":...,"at":...,"text":...,"source":...}` の形。
`photo_matched` が 1 以上

ローカル D1 にキャプション付きの写真が無ければ、Task 2 Step 4 (a) と同じ手順で1件入れてから引くこと。

- [ ] **Step 5: コミット**

```bash
git add cloudflare/lifelog/src/index.ts
git commit -m "Let the search see what the cameras saw"
```

---

### Task 4: `contextData` に `seen` を足す

**Files:**
- Modify: `cloudflare/lifelog/src/index.ts`（`export async function contextData`）

**Interfaces:**
- Consumes: `photos` テーブル
- Produces: `/v1/context` の応答に `seen`（文字列）と `photos`（配列）が増える。`text` は不変

- [ ] **Step 1: 写真の取得を足す**

`contextData` の中、`const segments = rows.results ?? [];` の直前に足す。

```typescript
  // status と非空を両方見る。'failed' の行はエラーだけ持っていてキャプションが
  // 無く、'captioned' でも空文字のことがある。どちらもエージェントに渡す中身ではない。
  const photoRows = await env.DB.prepare(
    `SELECT photo_id, taken_at, caption, source
       FROM photos
      WHERE status = 'captioned'
        AND caption IS NOT NULL AND caption <> ''
        AND taken_epoch >= ?1 AND taken_epoch <= ?2
      ORDER BY taken_epoch ASC
      LIMIT 200`,
  )
    .bind(fromEpoch, atEpoch)
    .all<any>();
```

`LIMIT` は音声と同じ 200。30秒間隔だと100分ぶんで埋まるので、
既定の `before_sec = 3600`（1時間 = 120枚）なら収まる。

- [ ] **Step 2: 返り値に2つ足す**

`contextData` の `return` を次に置き換える。**`text` は触らない。**

```typescript
  const segments = rows.results ?? [];
  const photos = photoRows.results ?? [];
  return {
    at,
    before_sec: beforeSec,
    from,
    min_speech_ratio: floor,
    count: segments.length,
    // A single blob is what an agent actually wants to read.
    text: segments.map((r: any) => r.transcript).join(" ").trim(),
    segments,
    // Kept apart from `text` on purpose. nudge.ts and interpret.ts read that
    // blob to decide whether to speak; folding captions into it would change
    // those judgements silently. And an agent that cannot tell what was said
    // from what was seen will attribute one to the other.
    seen: photos.map((r: any) => r.caption).join(" ").trim(),
    photos,
  };
```

- [ ] **Step 3: `text` が変わっていないことを確認する**

`nudge.ts` と `interpret.ts` が `text` を読んでいる。差分に `text:` の行が
現れていないこと、`segments` の中身が変わっていないことを目視で確認し、報告書に書く。

Run: `cd cloudflare/lifelog && git diff src/index.ts | grep -n "text:\|segments," || echo "text/segments に変更なし"`

- [ ] **Step 4: 型が通ることを確認する**

Run: `cd cloudflare/lifelog && npm run typecheck`
Expected: 既存エラー1件のみ

- [ ] **Step 5: ローカルで確認する**

```bash
curl -s -H "Authorization: Bearer $TOKEN" \
  "http://127.0.0.1:8787/v1/context?at=2026-09-14T12:30:00Z&before_sec=7200" | head -40
```
Expected: `seen` と `photos` が増えている。`text` は従来どおり

- [ ] **Step 6: コミット**

```bash
git add cloudflare/lifelog/src/index.ts
git commit -m "Tell the agent what was seen, not only what was said"
```

---

### Task 5: 検索結果の描画を `kind` に対応させる

**Files:**
- Modify: `cloudflare/lifelog/src/ui.ts`（`runSearch` と `renderEntry`）

**Interfaces:**
- Consumes: Task 3 の新しい `results` の形
- Produces: 検索結果に写真が混ざって出る

- [ ] **Step 1: `runSearch` の詰め替えを書き換える**

`src/ui.ts` の `runSearch` 内、`const entries = (d.results || []).map(...)` を置き換える。

```javascript
  const entries = (d.results || []).map((x) =>
    x.kind === 'photo'
      ? {
          kind: 'photo', at: x.at,
          // 検索結果では前後の別より、どちらのカメラかのほうが意味がある。
          // R1 は周囲の様子、眼鏡は見ていたもので、同じ主張ではない。
          shots: [{ id: x.id, label: x.source === 'glasses' ? '眼鏡' : 'R1', caption: x.text }],
        }
      : { kind: 'audio', at: x.at, id: x.id, text: x.text, status: 'transcribed' });
```

- [ ] **Step 2: 件数の表示に内訳を出す**

同じ関数の `draw(...)` 呼び出しを置き換える。

```javascript
  draw(entries, \`「\${q}」: \${entries.length} 件（音声 \${d.audio_matched} · 写真 \${d.photo_matched}）\`);
```

- [ ] **Step 3: `renderEntry` がラベルを使えるようにする**

`renderEntry` の写真側、`<div class="facing">` の行を置き換える。

```javascript
        <div class="facing">\${s.label ?? (s.facing === 'front' ? '前' : '後')}</div>
```

日ビューは `label` を渡さないので、これまでどおり 前/後 が出る。追加のみで既存の見え方は変わらない。

- [ ] **Step 4: 型が通ることを確認する**

Run: `cd cloudflare/lifelog && npm run typecheck`
Expected: 既存エラー1件のみ

- [ ] **Step 5: 検索ボックスの文言が真になったことを確認する**

`src/ui.ts` の検索入力は以前から `placeholder="全期間を検索（音声・写真）"` と書いてあり、
写真は検索できなかった。この計画でその文言が実際に正しくなる。
文言を変える必要はない。報告書に「既存の文言が真になった」と1行書くこと。

- [ ] **Step 6: コミット**

```bash
git add cloudflare/lifelog/src/ui.ts
git commit -m "Show the pictures the search now finds"
```

---

### Task 6: MCP の説明文を直す

**Files:**
- Modify: `cloudflare/lifelog/src/mcp.ts`（`TOOLS` の `lifelog_recent` と `lifelog_search` の `description`）

**Interfaces:**
- Consumes: Task 3 と Task 4 の応答の変化
- Produces: ツール名と引数は不変。説明文のみ

- [ ] **Step 1: `lifelog_recent` の説明を直す**

`description` を置き換える。

```typescript
    description:
      "Read what the user was doing near a moment in time, from the always-on " +
      "recording on their Rabbit R1 and from the cameras. Use this to resolve " +
      "references to recent moments — 'さっきの話', 'これ', 'the thing we just " +
      "discussed' — before answering. Returns `text` (what was said out loud) " +
      "and `seen` (captions of what the cameras recorded) as separate fields: " +
      "do not treat one as the other. Defaults to the two minutes before now.",
```

- [ ] **Step 2: `lifelog_search` の説明を直す**

```typescript
    description:
      "Full-text search over everything the user has been recorded saying and " +
      "everything the cameras recorded seeing. Use for 'when did I talk about " +
      "X', 'when did I last see X', or to recover a detail from an earlier " +
      "moment. Each result carries `kind`: 'audio' is a passage of speech, " +
      "'photo' is a caption of a frame. A photo result also carries `source`: " +
      "'r1' is the room the user was in, 'glasses' is what they were looking " +
      "at. Those are different claims — do not conflate them.",
```

説明文を直さないとエージェントが新しいフィールドを使わない。ツールの説明は仕様書であって飾りではない。

- [ ] **Step 3: 型が通ることを確認する**

Run: `cd cloudflare/lifelog && npm run typecheck`
Expected: 既存エラー1件のみ

- [ ] **Step 4: ツール定義が壊れていないことを確認する**

```bash
curl -s -X POST -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"jsonrpc":"2.0","id":1,"method":"tools/list"}' \
  "http://127.0.0.1:8787/mcp" | head -30
```
Expected: 5つのツールが返り、`lifelog_recent` と `lifelog_search` の `description` が新しい文面になっている

> `/mcp` は `AGENT_TOKEN` か `ADMIN_TOKEN` で通る。`.dev.vars` に `AGENT_TOKEN` が
> 無ければ `ADMIN_TOKEN` を使うこと。

- [ ] **Step 5: コミット**

```bash
git add cloudflare/lifelog/src/mcp.ts
git commit -m "Say in the tool descriptions that the cameras are searchable now"
```

---

### Task 7: 通しで確認して記録を残す

**Files:**
- Modify: `docs/superpowers/plans/2026-09-15-caption-corpus.md`（この計画の末尾）

**Interfaces:**
- Consumes: Task 1〜6 のすべて
- Produces: 実行結果と、本番反映のための手順

- [ ] **Step 1: 全部のテストと型チェックを回す**

Run: `cd cloudflare/lifelog && npm test && npm run typecheck`
Expected: 36 件通過。型チェックは既存エラー1件のみ

- [ ] **Step 2: ローカルで通しの検索を確認する**

`wrangler dev` を起動し、次の3つを引いて結果を報告書に貼る。

```bash
# (a) 3文字以上 → FTS 経路
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8787/v1/search?q=キャプション&limit=20"
# (b) 2文字 → LIKE 走査の経路
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8787/v1/search?q=テス&limit=20"
# (c) 何にも当たらない語
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8787/v1/search?q=ありえない語句xyz&limit=20"
```
Expected: (a)(b) は `mode` がそれぞれ `fts` / `scan` で `results` が返る。(c) は `count: 0`

- [ ] **Step 3: `/v1/context` を確認する**

```bash
curl -s -H "Authorization: Bearer $TOKEN" "http://127.0.0.1:8787/v1/context?at=2026-09-14T12:30:00Z&before_sec=7200"
```
Expected: `text` と `seen` が別々に返る

- [ ] **Step 4: この計画の末尾に結果を書く**

`## 実行結果`、`## 本番に反映する手順`、`## ターン数` の3節を足す。

`## 本番に反映する手順` には次を書く。**実行はしない。**

```bash
cd cloudflare/lifelog
npx wrangler d1 migrations apply r1-lifelog --remote   # 0018。既存4,300件が索引に入る
npm run deploy
```

マイグレーションが先。索引が無い状態で新コードを出すと `searchData` が
`no such table: photos_fts` で落ちる。第1弾と同じ順序の理由。

`## ターン数` は `docs/superpowers/specs/2026-09-09-feed-media-design.md` の
同名の節の形に合わせる。

- [ ] **Step 5: コミット**

```bash
git add docs/superpowers/plans/2026-09-15-caption-corpus.md
git commit -m "Write down what the corpus work needs to go live"
```

---

## 実行結果

Task 1〜7 を実装済み。ブランチ `feature/caption-corpus` に7コミット。
テストは36/36 PASS、型チェックは既存の1件を除いて新規エラーなし。
7タスクとも実装後のレビューが1回で clean（修正ラウンドなし）。

### 作ったもの

| Task | 内容 | コミット |
| --- | --- | --- |
| 1 | `mergeResults` 純粋関数（音声と写真を時刻でマージ） | `fa2b9dc` |
| 2 | migration 0018（`photos_fts` とトリガ2つ、既存4,206件ぶんの投入） | `f92241a` |
| 3 | `searchData` が写真も引くようになる | `600d268` |
| 4 | `contextData` に `seen` / `photos` を追加 | `ffc5875` |
| 5 | 検索結果の描画が `kind` に対応 | `0a471f4` |
| 6 | MCP ツール説明文の更新 | `813f60d` |
| 7 | 通し確認と本番手順の記録（このコミット） | — |

### テスト・型チェックの状態

`cd cloudflare/lifelog && npm test`

```
Test Files  6 passed (6)
     Tests  36 passed (36)
```

`cd cloudflare/lifelog && npm run typecheck`

```
src/index.ts(245,9): error TS2322: Type '(batch: MessageBatch<TranscribeMessage>, env: Env) => Promise<void>'
  is not assignable to type 'ExportedHandlerQueueHandler<Env, unknown, unknown>'.
```

このエラーはキューハンドラの型で、この計画より前から存在する。直していない。

### ローカルでの通し確認

`wrangler dev` をローカルで起動し確認した。ローカル D1 はほぼ空（キャプション無しの写真2件、
segments 0件）だったため、まず素の状態で3経路を引いて `mode` の出し分けと0件時の挙動を確認し、
そのあと Task 4 で懸念が残っていた `/v1/context` の実検証のために音声・写真を1件ずつ入れ、
そのデータで検索3経路も引き直して実際にヒットすることまで確認した。詳細な全コマンド・全出力は
[`task-7-report.md`](../../../.superpowers/sdd/2026-09-15-caption-corpus/task-7-report.md) に貼ってある。ここでは結論だけ書く。

**検索3経路**（`/v1/search`）

| 語 | 文字数 | `mode` | 結果 |
| --- | --- | --- | --- |
| キャプション | 6 | `fts` | 期待どおり。データを入れた後は写真1件ヒット |
| テス | 2 | `scan` | 期待どおり。データを入れた後は音声1件・写真1件ヒット |
| ありえない語句xyz | — | `fts` | `count: 0`（データの有無によらず） |

**`/v1/context`（Task 4 の懸念を埋める確認）**

Task 4 のレビューで「実行時確認は音声ゼロの窓だったので `text` に写真のキャプションが
混ざっていないことを証明していない」という minor が繰り越されていた。今回、音声と写真が
両方ある窓を自分でローカル D1 に作って確認した。

- 挿入: `photos` に1行（`task7ctx-photo1`、`caption = "キャプションのテスト写真です"`、
  `taken_at = 2026-09-14T11:10:00Z`）、`segments` に1行（`task7ctx-seg1`、
  `transcript = "テスト用の音声記録です"`、`status = 'transcribed'`、
  `started_at = 2026-09-14T11:15:00Z`）。どちらも `INSERT` してから対象カラムを
  `UPDATE` してトリガを発火させた（写真は caption、音声は transcript）。
  両方とも問い合わせ窓 `at=2026-09-14T12:30:00Z&before_sec=7200`（10:30〜12:30 UTC）の中。
- 確認: `photos_fts` / `segments_fts` に索引されたことを先に確認してから `/v1/context` を叩いた。
  `text` が `"テスト用の音声記録です"`（transcriptの語のみ）、`seen` が
  `"キャプションのテスト写真です"`（captionの語のみ）と、一切混ざらず別々に返った。
- 削除: 確認後に2行とも `DELETE` し、`segments` / `photos` / `segments_fts` / `photos_fts`
  すべてから消えたことを `COUNT(*)` で確認、検索・`/v1/context` を再度引いて空に戻ったことも確認した。

これで Task 4 の minor（実行時証拠が弱い）は解消。

### `wrangler dev` の停止

Task 6 の申し送りどおり、`workerd.exe` を直接殺さず、プロセスツリーを遡って見つけた
トップレベルの `npx wrangler dev`（node.exe、`wrangler dev` 起動コマンド）を
`taskkill /F /T /PID` で止めた。`netstat` で `127.0.0.1:8787` の `LISTENING` が
消えたことを確認済み（`TIME_WAIT` の残骸のみ）。

### 先送りのまま残っているもの（この計画では触らない）

| Task | 内容 |
| --- | --- |
| 1 | `at` が不正な文字列だと comparator が NaN を返す（D1 の値は自前の ISO 文字列なので実害は薄いと Task 3 で確認済み） |
| 1 | `limit=Infinity` が `[]` を返す（現状そういう呼び出しは無い） |
| 2 | ~~backfill の `INSERT` に重複ガードが無い~~ → **解消済み。**最終レビューで「4,300行の適用が途中で落ちて migration 記録が残らなければ再実行で二重索引になる」と格上げされ、backfill の直前に `DELETE FROM photos_fts;` を追加した（`b7f66ce`） |
| 3 | `searchData` 自体の単体テストが無い（変更前から無い。統合確認は curl で実施済み） |
| 4 | `photoRows.all<any>()` だけ型引数付き（兄弟の音声クエリは `.all()`）。機能差なし |
| 6 | 音声結果の `source` が null であることが MCP の説明文に書かれていない（誤りではないが沈黙） |

キャプション品質（眼鏡カメラの76%が文末で途切れる件）は索引の作りとは独立した別問題として、
`## 次にやること` に書いたとおり完了後にユーザーへ提起する。

## 本番に反映する手順

**実行はしない。** 人間がこの通りに、本人の判断で叩く。

```bash
cd cloudflare/lifelog
npx wrangler d1 migrations list r1-lifelog --remote     # まず未適用を確認する
npx wrangler d1 migrations apply r1-lifelog --remote   # 0018。既存4,300件が索引に入る
npx wrangler d1 execute r1-lifelog --remote --command "SELECT COUNT(*) FROM photos_fts"
npx wrangler d1 execute r1-lifelog --remote --command "SELECT COUNT(*) FROM photos WHERE caption IS NOT NULL AND caption <> ''"
npm run deploy
```

- **先に `migrations list` で未適用を確認する。** `migrations apply` は指定した1件だけ
  でなく未適用のマイグレーションを全部まとめて当てる。今回のつもりが0018だけでも、
  ほかに未適用のものが残っていれば一緒に流れる。何が当たるかを先に見てから叩く。
- **マイグレーションが先、デプロイが後。** 新しい `searchData` は `photos_fts` を
  クエリする。索引テーブルが無い状態で新しいコードだけ先に出すと、検索を叩くたびに
  `no such table: photos_fts` で落ちる。第1弾（眼鏡タイムラプス取り込み）と同じ順序の理由。
- **backfill の所要時間は本番では未測定。** migration 0018 の最後の `INSERT` は
  「キャプション済みの写真ぜんぶ」を1文で `photos_fts` に入れる。Task 2 で検証したのは
  ローカルのほぼ空の D1（対象行はテスト用の1〜数件）に対してで、本番の約4,300件に対して
  同じ文がどれくらいの時間で終わるかは計測していない。一瞬で終わる想定で話を進めない。
  必要なら本番反映のタイミングで実測し、長くかかるようなら分割 INSERT への変更を別途検討する。
- **適用後に件数を突き合わせる。** `photos_fts` の行数と、`photos` のうちキャプションが
  非空の行数を数えて、一致することを確認する。一致しなければ backfill が途中で
  止まっている。
- **backfill 中も R1 とキューは止まらない。** R1 は5分おきに写真をアップロードし、
  キャプションを付けるキューは `UPDATE photos SET caption = ...` を継続的に書き続けている。
  backfill と重なるぶん、途中のキャプション `UPDATE` が失敗してキューに再試行される
  ことがありうる。キューはリトライするので実害は無い想定だが、気づかず驚くより先に
  書いておく。

## 次にやること

**第3弾は1日のサマリー。** この計画が終われば材料（音声の transcript と写真のキャプション）が
両方引けるようになるので、そこから設計する。

**キャプションの品質は別問題として残る。** 本番の実測で、眼鏡カメラのキャプションは
76% が文末で途切れ、15% に同一文の繰り返しがある（`CAPTION_MAX_TOKENS = 220` に対して
中央値300文字）。索引の作りとは独立しているので、この計画では触らない。
画像は残っているので付け直しはいつでもできる。

## ターン数

- 実装: 予定 7タスク / 実際 7タスク、修正ラウンド 0回

7タスクとも実装 → レビューが1回で clean。コード上の修正ラウンドは無かった。
ただし Task 4 のレビューで「実行時確認が音声ゼロの窓だった」という minor が
Task 7 に繰り越されており、Task 7 の Step 3 でその検証をやり直して埋めている
（コードの修正ではなく、検証のやり直し）。

| どこで | 何が | 原因 |
| --- | --- | --- |
| Task 4 → Task 7 | `/v1/context` の実行時確認が音声ゼロの窓で行われており、`text` に写真のキャプションが混ざらないことを証明していなかった | 検証（コードの diff 自体は静的証拠として clean と Task 4 レビューで確認済み。Task 7 で音声・写真が両方ある窓を作って実測し、混ざっていないことを確認して解消） |
