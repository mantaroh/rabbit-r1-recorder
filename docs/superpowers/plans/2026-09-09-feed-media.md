# 新着画面に画像と要約を出す — 実装計画

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 待受の「新着」画面に、記事の見出しに加えて要約と画像を出す。

**Architecture:** `reader` Worker のパーサが要約と画像URLを拾って D1 に足し、英語の要約はクロール時に Workers AI で一度だけ日本語にする。端末は `/feeds/latest` から受けた画像URLを publisher から直接取り、この画面にいる間だけキャラクターを隠して 240×320dp を全面使う。長い要約は縦スクロール、記事の送りは横スワイプ。

**Tech Stack:** Cloudflare Workers / D1 / Workers AI (`@cf/meta/llama-3.1-8b-instruct`) / vitest、Android (Kotlin, AndroidX なし, minSdk 34)

**Spec:** `docs/superpowers/specs/2026-09-09-feed-media-design.md`

## Global Constraints

- **ブランチ**: `feature/feed-media`。`main` / `develop` には触らない
- **push は承認制**。ローカルの commit は都度行ってよい
- **コミットメッセージに AI の固有名詞・署名を書かない**。末尾に `Claude-Session: https://claude.ai/code/session_019eYyuJgyYGrx7zGoGBFRhs` を付ける
- **`reader` Worker のバインディングは D1 と AI のみ**。R2 や lifelog の D1 を足さない
- **`audioprobe` に AndroidX を足さない**。`android.useAndroidX=false` が立っている
- **端末の新規依存はゼロ**。HTTP は `java.net.HttpURLConnection`（`Headlines.kt` と同じ）
- **要約の上限 400字**、**画像URLの上限 500字**、**画像の足切り 200px**（フィード側の属性）／**240px**（端末側の実寸）
- **翻訳は1クロールあたり 30件**まで
- コード中のコメントと識別子は英語（既存ファイルに合わせる）。UI 文言は日本語
- Kotlin の単体テストはこのリポジトリに存在しない。端末側は `./gradlew :audioprobe:assembleDebug` の成功と実機確認で見る

---

## ファイル構成

| ファイル | 責務 | 状態 |
| --- | --- | --- |
| `cloudflare/reader/src/feed.ts` | XML から要約と画像URLを取り出す純粋関数 | 変更 |
| `cloudflare/reader/src/feed.test.ts` | 上の単体テスト | 新規 |
| `cloudflare/reader/package.json` | vitest の devDependency と `test` スクリプト | 変更 |
| `cloudflare/reader/migrations/0002_item_media.sql` | `items` にカラム3つ | 新規 |
| `cloudflare/reader/src/index.ts` | 保存・翻訳・`/feeds/latest` の応答 | 変更 |
| `cloudflare/reader/wrangler.jsonc` | AI バインディング | 変更 |
| `audioprobe/.../Headlines.kt` | 要約と画像URLを受け取る | 変更 |
| `audioprobe/.../Thumbnails.kt` | 画像の取得・デコード・LRU | 新規 |
| `audioprobe/.../Signage.kt` | 新着画面のレイアウトと操作、`fullBleed` | 変更 |
| `audioprobe/.../SignageActivity.kt` | 全面表示とキャラの排他、タップの受け渡し | 変更 |

---

## Task 1: パーサが要約を返す（vitest 導入込み）

**Files:**
- Modify: `cloudflare/reader/package.json`
- Create: `cloudflare/reader/vitest.config.ts`
- Create: `cloudflare/reader/src/feed.test.ts`
- Modify: `cloudflare/reader/src/feed.ts`

**Interfaces:**
- Consumes: なし
- Produces: `FeedItem.summary: string | null`、モジュール内部の `rawText(xml, tag)` / `plainText(html)` / `strip(html)`

`feed.ts` は Workers API を一切使わない純粋な TypeScript なので、`@cloudflare/vitest-pool-workers` は要らない。素の vitest で足りる。

- [ ] **Step 1: vitest を入れる**

```bash
cd cloudflare/reader && npm install --save-dev vitest@^3
```

`package.json` の `scripts` に足す。

```json
"test": "vitest run",
"test:watch": "vitest"
```

`vitest.config.ts` を作る。

```typescript
import { defineConfig } from "vitest/config";

export default defineConfig({
  test: {
    // feed.ts は Workers の API を使わない純粋な関数の集まりなので、
    // workerd を起こす必要がない。node で足りる。
    environment: "node",
    include: ["src/**/*.test.ts"],
  },
});
```

- [ ] **Step 2: 落ちるテストを書く**

`cloudflare/reader/src/feed.test.ts` を作る。

```typescript
import { describe, expect, it } from "vitest";
import { parseFeed } from "./feed";

/** 一件だけのRSSに仕立てる。テストが見たいのは item の中身だけ。 */
function rss(item: string): string {
  return `<rss version="2.0"><channel><title>Test</title>${item}</channel></rss>`;
}

describe("summary", () => {
  it("takes description and strips its tags", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title><link>https://example.com/a</link>
        <description><![CDATA[<p>Hello <b>world</b> and then some more text.</p>]]></description>
      </item>`),
    );
    expect(item.summary).toBe("Hello world and then some more text.");
  });

  it("decodes HTML that was escaped twice", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <description>&amp;lt;p&amp;gt;Alpha &amp;amp;amp; Beta, and a good deal more text here.&amp;lt;/p&amp;gt;</description>
      </item>`),
    );
    expect(item.summary).toBe("Alpha & Beta, and a good deal more text here.");
  });

  it("cuts a full article down to 400 characters", () => {
    const long = "word ".repeat(500);
    const [item] = parseFeed(rss(`<item><title>T</title><description>${long}</description></item>`));
    expect(item.summary).toHaveLength(401); // 400 + 省略記号
    expect(item.summary?.endsWith("…")).toBe(true);
  });

  it("skips a description that is only a read-more stub", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title><description>Read more</description>
        <content:encoded><![CDATA[The actual body of the entry lives here.]]></content:encoded>
      </item>`),
    );
    expect(item.summary).toBe("The actual body of the entry lives here.");
  });

  it("is null when the entry carries no body at all", () => {
    const [item] = parseFeed(rss(`<item><title>T</title><link>https://example.com/a</link></item>`));
    expect(item.summary).toBeNull();
  });
});
```

- [ ] **Step 3: 落ちることを確認する**

Run: `cd cloudflare/reader && npm test`
Expected: FAIL。`item.summary` が `undefined` で、`toBe` が全部落ちる。

- [ ] **Step 4: `feed.ts` に要約を実装する**

`FeedItem` にフィールドを足す。

```typescript
export interface FeedItem {
  /** The feed's own identifier for this entry, or its link as a fallback. */
  guid: string;
  title: string;
  link: string | null;
  /** ISO 8601 if the feed gave anything parseable, else null. */
  publishedAt: string | null;
  /** The entry's body as plain text, cut short. Null when it has none. */
  summary: string | null;
}
```

ファイル先頭の定数。

```typescript
/**
 * How much of an entry's body is kept.
 *
 * Bodies are not a fixed size and are not close to one: measured across the
 * subscription list on 2026-08-09, the median entry ran 88 characters at The
 * Register and 15,475 at android-developers, which ships the whole article.
 * The panel shows a few dozen characters at a time and scrolls, so this is
 * about what a person might read before moving on rather than about the page.
 */
const SUMMARY_MAX = 400;

/** Shorter than this is a "Read more" stub rather than a summary. */
const SUMMARY_MIN = 20;
```

`parseFeed` のループに一行足す。

```typescript
    items.push({
      guid: guid.slice(0, 500),
      title: title.slice(0, 500),
      link,
      publishedAt: dateOf(block),
      summary: summaryOf(block),
    });
```

既存の `text()` を分解して、生の中身を返す関数を切り出す。**`text()` の振る舞いは変えない。**

```typescript
/**
 * The inner text of the first matching element, with CDATA unwrapped and
 * nothing else done to it.
 *
 * Split out from [text] because a summary needs the markup that is in there —
 * to throw it away deliberately — while a title needs it already gone.
 */
function rawText(xml: string, tag: string): string | null {
  const name = tag.includes(":") ? tag : `(?:[a-zA-Z0-9]+:)?${tag}`;
  const match = new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`).exec(xml);
  if (!match) return null;

  const cdata = /<!\[CDATA\[([\s\S]*?)\]\]>/.exec(match[1]);
  return cdata ? cdata[1] : match[1];
}

function text(xml: string, tag: string): string | null {
  const raw = rawText(xml, tag);
  if (raw === null) return null;
  return decode(raw).replace(/\s+/g, " ").trim() || null;
}
```

要約の抽出。

```typescript
/**
 * The entry's body, as something that can be put on a screen.
 *
 * Four elements in order of how likely a publisher is to have meant the short
 * one. `content` is last because Atom's is the whole article, and a feed that
 * offers both has already said which it thinks is the summary.
 */
function summaryOf(block: string): string | null {
  for (const tag of ["description", "summary", "content:encoded", "content"]) {
    const raw = rawText(block, tag);
    if (!raw) continue;

    const plain = plainText(raw);
    // "Read more", "Continue reading", and the empty wrappers left behind by a
    // template. Falling through to the next element finds the real body.
    if (plain.length < SUMMARY_MIN) continue;

    return plain.length > SUMMARY_MAX ? plain.slice(0, SUMMARY_MAX).trimEnd() + "…" : plain;
  }
  return null;
}

/**
 * Markup out, entities in.
 *
 * Two passes, because escaping is not consistently applied once. A feed that
 * put its HTML in CDATA presents tags immediately; one that escaped it
 * presents them only after a decode; and one that escaped it twice — which
 * happens, and is the same class of mistake [decode] already handles for
 * titles — presents them after the second.
 */
function plainText(html: string): string {
  let value = html;
  for (let pass = 0; pass < 2; pass += 1) value = decode(strip(value));
  return value.replace(/\s+/g, " ").trim();
}

function strip(html: string): string {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<[^>]+>/g, " ");
}
```

- [ ] **Step 5: 通ることを確認する**

Run: `cd cloudflare/reader && npm test`
Expected: PASS。5件とも緑。

- [ ] **Step 6: コミット**

```bash
git add cloudflare/reader/package.json cloudflare/reader/package-lock.json \
        cloudflare/reader/vitest.config.ts cloudflare/reader/src/feed.ts \
        cloudflare/reader/src/feed.test.ts
git commit -F - <<'MSG'
Read the body of a feed entry as well as its title

The standby screen shows a headline and nothing else, which is not
enough to be read. This takes the entry's body too, as plain text, cut
at four hundred characters.

Escaping is decoded twice. A feed that escaped its HTML once presents
tags only after a decode and one that escaped it twice only after the
second, and both are in the subscription list.

Brings vitest with it. Seven filtering rules are coming and checking
them by eye is not a plan.

Claude-Session: https://claude.ai/code/session_019eYyuJgyYGrx7zGoGBFRhs
MSG
```

---

## Task 2: パーサが画像URLを返す

**Files:**
- Modify: `cloudflare/reader/src/feed.ts`
- Modify: `cloudflare/reader/src/feed.test.ts`

**Interfaces:**
- Consumes: Task 1 の `rawText`、既存の `decode`
- Produces: `FeedItem.imageUrl: string | null`

- [ ] **Step 1: 落ちるテストを書く**

`feed.test.ts` の末尾に足す。

```typescript
describe("imageUrl", () => {
  it("prefers an image enclosure over a thumbnail", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <media:thumbnail url="https://example.com/small.jpg" />
        <enclosure url="https://example.com/big.jpg" type="image/jpeg" />
      </item>`),
    );
    expect(item.imageUrl).toBe("https://example.com/big.jpg");
  });

  it("asks Blogger for a size that is worth showing", () => {
    // Measured 2026-09-09: the s72-c URL is 72x72 and 7,251 bytes; the s640
    // rewrite of the same URL is 135,810 bytes of the same picture.
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <media:thumbnail url="https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEg/s72-c/Meta.png" height="72" width="72"/>
      </item>`),
    );
    expect(item.imageUrl).toBe(
      "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEg/s640/Meta.png",
    );
  });

  it("drops a gravatar and falls through to the body", () => {
    // mozillagfx offers media:content, and what it offers is the author's face.
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <media:content url="https://0.gravatar.com/avatar/96d7?s=96" medium="image" />
        <description><![CDATA[<img src="https://example.com/article.jpg"> Body text goes here.]]></description>
      </item>`),
    );
    expect(item.imageUrl).toBe("https://example.com/article.jpg");
  });

  it("drops a tracking pixel", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <description><![CDATA[Body text.<img src="https://feeds.example.com/~r/x/~4/abc" height="1" width="1">]]></description>
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("drops a thumbnail that declares itself too small", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <media:thumbnail url="https://example.com/icon.png" width="72" height="72" />
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("refuses anything that is not http", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <enclosure url="data:image/png;base64,iVBORw0KGgo=" type="image/png" />
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("ignores a non-image enclosure", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <enclosure url="https://example.com/episode.mp3" type="audio/mpeg" />
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("is null when the entry offers nothing", () => {
    const [item] = parseFeed(rss(`<item><title>T</title><description>Body text.</description></item>`));
    expect(item.imageUrl).toBeNull();
  });
});
```

- [ ] **Step 2: 落ちることを確認する**

Run: `cd cloudflare/reader && npm test`
Expected: FAIL。`item.imageUrl` が `undefined`。

- [ ] **Step 3: `feed.ts` に画像を実装する**

`FeedItem` に足す。

```typescript
  /** An absolute http(s) picture of the article, big enough to be worth showing. */
  imageUrl: string | null;
```

`parseFeed` のループに足す。

```typescript
      summary: summaryOf(block),
      imageUrl: imageOf(block),
```

定数。

```typescript
/**
 * The smallest a picture may declare itself and still be shown.
 *
 * Two things sit under this and neither is the article: Blogger's
 * media:thumbnail is 72 pixels square, and the pixel FeedBurner puts at the
 * end of a body is one.
 */
const MIN_IMAGE_PX = 200;

/**
 * Hosts that serve something other than the article, whatever the element
 * says it is. Gravatar is the author's face; mozillagfx offers it as
 * media:content on every entry.
 */
const IMAGE_DENYLIST = ["gravatar.com"];
```

既存の `linkOf` の中にある `attrOf` をモジュール直下に引き上げる。`linkOf` からローカル定義を消し、この共通版を使う。

```typescript
/**
 * One attribute out of a tag's attribute string.
 *
 * Anchored on a boundary that is whitespace or the start, so `width` does not
 * also match `data-width`.
 */
function attrOf(attrs: string, name: string): string | null {
  return new RegExp(`(?:^|\\s)${name}\\s*=\\s*["']([^"']*)["']`, "i").exec(attrs)?.[1] ?? null;
}

/** The attribute strings of every occurrence of a tag, in document order. */
function tagsOf(xml: string, name: string): string[] {
  return [...xml.matchAll(new RegExp(`<${name}\\b([^>]*?)/?>`, "gi"))].map((m) => m[1]);
}
```

抽出本体。

```typescript
/**
 * The article's picture, or null when the entry only offers something that is
 * not one.
 *
 * Four places, in descending order of how likely the publisher was to mean the
 * article by it, and every candidate has to survive [usableImage] — so an
 * entry whose media:content is an avatar falls through to the body rather than
 * ending up with a face on the screen.
 */
function imageOf(block: string): string | null {
  for (const attrs of tagsOf(block, "enclosure")) {
    const type = attrOf(attrs, "type");
    if (type && !type.toLowerCase().startsWith("image/")) continue;
    const url = usableImage(attrOf(attrs, "url"), attrs);
    if (url) return url;
  }

  for (const attrs of tagsOf(block, "media:content")) {
    const type = attrOf(attrs, "type")?.toLowerCase() ?? "";
    const medium = attrOf(attrs, "medium")?.toLowerCase() ?? "";
    if (medium !== "image" && !type.startsWith("image/")) continue;
    const url = usableImage(attrOf(attrs, "url"), attrs);
    if (url) return url;
  }

  for (const attrs of tagsOf(block, "media:thumbnail")) {
    const url = usableImage(attrOf(attrs, "url"), attrs);
    if (url) return url;
  }

  // The body, which is where a publisher that uses none of the above puts it.
  // Decoded first: a feed that escaped its HTML has no <img> until it is.
  const body = ["description", "content:encoded", "summary", "content"]
    .map((tag) => rawText(block, tag) ?? "")
    .join(" ");
  for (const attrs of tagsOf(decode(body), "img")) {
    const url = usableImage(attrOf(attrs, "src"), attrs);
    if (url) return url;
  }

  return null;
}

/** The URL if it is worth fetching from a device, else null. */
function usableImage(url: string | null, attrs: string): string | null {
  if (!url) return null;

  const cleaned = decode(url).trim();
  if (!cleaned || cleaned.length > 500) return null;
  // The device fetches this itself, so nothing but an ordinary URL is any use
  // to it — no data: payloads, no protocol-relative addresses.
  if (!/^https?:\/\//i.test(cleaned)) return null;
  if (IMAGE_DENYLIST.some((host) => cleaned.toLowerCase().includes(host))) return null;

  // A rewritten URL is a different picture from the one the attributes
  // describe, so the size the feed declared no longer applies to it.
  const bigger = enlarged(cleaned);
  if (bigger) return bigger;

  for (const name of ["width", "height"]) {
    const value = Number(attrOf(attrs, name));
    if (Number.isFinite(value) && value > 0 && value < MIN_IMAGE_PX) return null;
  }

  return cleaned;
}

/**
 * Blogger states a thumbnail's size in its path, and the size it states is 72
 * pixels — 7,251 bytes of a picture that has to fill 480. The same URL asked
 * for s640 returned 135,810 bytes of the same image, measured against
 * android-developers on 2026-09-09.
 *
 * Narrow on purpose. This is one publisher's URL scheme, not a general
 * ability to resize other people's images by guessing at their addresses.
 */
function enlarged(url: string): string | null {
  if (!url.includes("googleusercontent.com")) return null;
  const rewritten = url.replace(/\/s\d+(-c)?\//, "/s640/").replace(/=s\d+(-c)?$/, "=s640");
  return rewritten === url ? null : rewritten;
}
```

- [ ] **Step 4: 通ることを確認する**

Run: `cd cloudflare/reader && npm test`
Expected: PASS。Task 1 の5件と合わせて13件が緑。

- [ ] **Step 5: 実物のフィードで一度確かめる**

このリポジトリの購読リストで実際に動くことを見る。ネットワークに出る読み取りだけの確認。

```bash
cd cloudflare/reader && node --input-type=module -e '
import { parseFeed } from "./src/feed.ts";
for (const url of ["https://www.theregister.co.uk/excerpts.rss",
                   "https://android-developers.blogspot.com/feeds/posts/default",
                   "https://feeds.mashable.com/Mashable",
                   "https://b.hatena.ne.jp/hotentry.rss"]) {
  const xml = await (await fetch(url, { redirect: "follow" })).text();
  const items = parseFeed(xml).slice(0, 3);
  console.log(url);
  for (const i of items) console.log("  ", i.imageUrl ?? "(no image)", "|", (i.summary ?? "").slice(0, 50));
}'
```

Expected: theregister は `image.theregister.com`、android-developers は `/s640/` に書き換わった URL、mashable は `helios-i.mashable.com`、はてブは全部 `(no image)` で要約だけ。**s72-c が残っていたら書き換えが効いていない。**

- [ ] **Step 6: コミット**

```bash
git add cloudflare/reader/src/feed.ts cloudflare/reader/src/feed.test.ts
git commit -F - <<'MSG'
Find the picture that belongs to a feed entry

Four places carry one and the first that has anything is not always
right, so every candidate has to survive a size check before it is
taken. Two measurements are the reason there is a check at all: Blogger
offers a 72 pixel thumbnail, and mozillagfx offers the author's gravatar
as media:content on every entry.

Blogger's URL states its own size, so that one is asked for s640
instead. Narrow to that host on purpose; guessing at other people's
addresses is not a resizing service.

Claude-Session: https://claude.ai/code/session_019eYyuJgyYGrx7zGoGBFRhs
MSG
```

---

## Task 3: 要約と画像を D1 に保存し、`/feeds/latest` で返す

**Files:**
- Create: `cloudflare/reader/migrations/0002_item_media.sql`
- Modify: `cloudflare/reader/src/index.ts`

**Interfaces:**
- Consumes: Task 2 の `FeedItem.summary` / `FeedItem.imageUrl`
- Produces: `/feeds/latest` の各要素に `summary: string | null` と `image_url: string | null`

- [ ] **Step 1: マイグレーションを書く**

`cloudflare/reader/migrations/0002_item_media.sql`

```sql
-- What an entry looks like, as well as what it says.
--
-- A headline on its own does not get read on a 240 dp panel, and the feeds
-- already carry both of these; the crawler was simply throwing them away.

ALTER TABLE items ADD COLUMN summary TEXT;

-- The Japanese of the above, filled in after the fact for entries that arrive
-- in another language. Kept apart from summary rather than replacing it: a
-- translation is a guess about a text, and losing the text to store the guess
-- is not a trade worth making.
ALTER TABLE items ADD COLUMN summary_ja TEXT;

ALTER TABLE items ADD COLUMN image_url TEXT;
```

- [ ] **Step 2: ローカルの D1 に当てる**

```bash
cd cloudflare/reader && npx wrangler d1 migrations apply r1-reader --local
```

Expected: `0002_item_media.sql` が適用された旨。

- [ ] **Step 3: `fetchOne` の書き込みを upsert にする**

`index.ts` の `fetchOne` の中、`const statements = items.map(...)` を差し替える。

```typescript
  const seconds = Math.floor(now / 1000);
  const statements = items.map((item) =>
    env.DB.prepare(
      `INSERT INTO items
         (item_id, xml_url, title, link, published_at, published_epoch, first_seen_epoch,
          summary, image_url)
       VALUES (?1,?2,?3,?4,?5,?6,?7,?8,?9)
       ON CONFLICT(item_id) DO UPDATE SET
         summary   = excluded.summary,
         image_url = excluded.image_url
       WHERE items.summary IS NULL AND items.image_url IS NULL`,
    ).bind(
      `${feed.xml_url}|${item.guid}`.slice(0, 900),
      feed.xml_url,
      item.title,
      item.link,
      item.publishedAt,
      item.publishedAt ? Math.floor(Date.parse(item.publishedAt) / 1000) : null,
      seconds,
      item.summary,
      item.imageUrl,
    ),
  );
```

`newItems` の集計のすぐ上に、なぜ数字が一度だけ狂うのかを書く。

```typescript
  const written = await env.DB.batch(statements);
  // Counts the entries filled in as well as the ones that arrived, for the one
  // pass after this ships: everything already in the table predates these two
  // columns and gets them the next time its feed is read. It settles to zero
  // on its own, and the number only ever reaches a log line.
  const newItems = written.reduce((total, r) => total + (r.meta?.changes ?? 0), 0);
```

`ON CONFLICT ... WHERE` の条件は、**両方が NULL のときだけ**書く。片方だけ埋まっている行を上書きしないため。

- [ ] **Step 4: `latest` が返す列を増やす**

`latest()` の SQL を差し替える。

```typescript
  const rows = await env.DB.prepare(
    `SELECT i.title, i.link, i.published_at, i.first_seen_epoch,
            COALESCE(i.summary_ja, i.summary) AS summary, i.image_url,
            f.title AS feed_title, f.category
       FROM items i
       JOIN feeds f ON f.xml_url = i.xml_url
      WHERE (?2 IS NULL OR f.category = ?2)
      ORDER BY i.first_seen_epoch DESC, i.published_epoch DESC, i.rowid DESC
      LIMIT ?1`,
  )
    .bind(limit, category)
    .all();
```

`COALESCE` をここに置く理由を関数の doc に一段落足す。

```
 * The translation is chosen here rather than on the device. There is one
 * summary as far as anything downstream is concerned, and whether it was
 * translated is this Worker's business — a device that had to know would be a
 * device that has to be redeployed when the answer changes.
```

`/feeds/search` は触らない。

- [ ] **Step 5: ローカルで動かして確かめる**

```bash
cd cloudflare/reader && npx wrangler dev --local --port 8788 &
curl -s -X POST "http://127.0.0.1:8788/feeds/crawl?limit=5" | head -20
curl -s "http://127.0.0.1:8788/feeds/latest?limit=3"
```

Expected: `latest` の各要素に `summary` と `image_url` が出ている。少なくとも1件は `image_url` が非 null。確認できたら `wrangler dev` を止める。

> ⚠️ **ローカルの D1 が空なら** 先に OPML を入れる:
> `curl -s -X POST --data-binary @../../feedly-opal-sample.opml http://127.0.0.1:8788/feeds/import`

- [ ] **Step 6: コミット**

```bash
git add cloudflare/reader/migrations/0002_item_media.sql cloudflare/reader/src/index.ts
git commit -F - <<'MSG'
Keep the summary and the picture, and hand them to the reader

Three columns. summary_ja is separate from summary rather than
replacing it, because a translation is a guess about a text and losing
the text to store the guess is not a trade worth making.

The insert becomes an upsert so that entries already in the table get
the two new columns the next time their feed is read, rather than
staying blank until they fall out of it. It only writes where both are
still null, so nothing that has been filled in gets overwritten.

Claude-Session: https://claude.ai/code/session_019eYyuJgyYGrx7zGoGBFRhs
MSG
```

---

## Task 4: 英語の要約を日本語にする

**Files:**
- Modify: `cloudflare/reader/wrangler.jsonc`
- Modify: `cloudflare/reader/src/index.ts`

**Interfaces:**
- Consumes: Task 3 の `summary` / `summary_ja` カラム
- Produces: `CrawlSummary.translated: number`、`translatePending(env, limit): Promise<number>`

- [ ] **Step 1: AI バインディングを足し、コメントを事実に合わせる**

`wrangler.jsonc` の `d1_databases` の上のコメントを書き換え、AI を足す。**いまのコメントは「D1 だけ」と断言しているので、そのままにできない。**

```jsonc
  // Two bindings, and the list is the point. This Worker has no R2 bucket and
  // no handle on the lifelog database, so a bug in a feed parser cannot reach
  // the audio archive however badly it goes wrong. That separation is the
  // reason this is a second Worker rather than another route on the first one
  // — the lifelog Worker's own cron once deleted 327 recordings, and the
  // cheapest guarantee that it cannot happen again is to not hand the binding
  // over. Workers AI holds nothing and reaches nothing, so it does not spend
  // any of that.
  "d1_databases": [
    {
      "binding": "DB",
      "database_name": "r1-reader",
      "database_id": "5f717644-9965-4b65-b05c-ec6b6de378ea",
      "migrations_dir": "./migrations"
    }
  ],

  // Translating the summaries of feeds that publish in English. Runs against
  // the real service in dev as well; there is no local Workers AI.
  "ai": { "binding": "AI" },
```

`Env` に足す。

```typescript
interface Env {
  DB: D1Database;
  AI: Ai;
}
```

- [ ] **Step 2: 翻訳を書く**

`index.ts` の crawl のセクションに足す。定数はファイル上部の他の定数の隣に置く。

```typescript
/**
 * The model that turns an English summary into a Japanese one.
 *
 * An instruction model rather than a translation model. m2m100 is cheaper and
 * faster and reads like it: the subscription list is technical writing, full
 * of product names that a translation model declines and word order it gets
 * backwards. This is a screen that exists to be read at a glance, and a
 * sentence that has to be decoded is worse than the English was.
 */
const TRANSLATE_MODEL = "@cf/meta/llama-3.1-8b-instruct";

/**
 * How many summaries are translated in one crawl.
 *
 * A bound rather than a budget. Most hours bring nothing like this many, but
 * feeds do update in unison, and an hour where forty sites all publish at once
 * should not be the hour that spends the day's neurons. What is left waits for
 * the next run, which is at most an hour away.
 */
const TRANSLATE_PER_CRAWL = 30;

const TRANSLATE_PROMPT =
  "あなたは技術ニュースの翻訳者です。" +
  "渡された英語の要約を、日本語の技術記事として自然な文体に訳してください。" +
  "製品名・企業名・技術用語は原語のまま残します。" +
  "前置きも注釈も引用符も付けず、訳文だけを返してください。";

/** Enough of a test: Japanese cannot be written without one of these. */
function foreign(text: string): boolean {
  return !/[぀-ゟ゠-ヿ一-鿿]/.test(text);
}

/**
 * Fills in summary_ja for entries that do not have one yet.
 *
 * Newest first, because the screen shows the ten most recent and an entry that
 * has already scrolled off the bottom of that is not worth a model call. An
 * entry whose translation keeps failing is retried on every crawl, which costs
 * one call an hour and stops mattering as soon as anything newer arrives.
 */
async function translatePending(env: Env, limit: number): Promise<number> {
  const rows = await env.DB.prepare(
    `SELECT item_id, summary FROM items
      WHERE summary IS NOT NULL AND summary_ja IS NULL
      ORDER BY first_seen_epoch DESC
      LIMIT ?1`,
  )
    .bind(limit)
    .all<{ item_id: string; summary: string }>();

  let done = 0;
  for (const row of rows.results ?? []) {
    // Already Japanese. Copied across rather than left null, or it would be
    // reconsidered on every crawl for the rest of the entry's life.
    const translated = foreign(row.summary) ? await translate(env, row.summary) : row.summary;
    if (!translated) continue;

    await env.DB.prepare(`UPDATE items SET summary_ja = ?2 WHERE item_id = ?1`)
      .bind(row.item_id, translated)
      .run();
    done += 1;
  }
  return done;
}

async function translate(env: Env, summary: string): Promise<string | null> {
  try {
    const result: any = await env.AI.run(TRANSLATE_MODEL as any, {
      messages: [
        { role: "system", content: TRANSLATE_PROMPT },
        { role: "user", content: summary },
      ],
      max_tokens: 600,
    });

    const answer = String(result?.response ?? "").replace(/\s+/g, " ").trim();
    if (!answer) return null;
    // A model that answered in English has translated nothing, and storing
    // that would mean never asking again.
    if (foreign(answer)) return null;

    return answer.slice(0, 600);
  } catch (error) {
    console.log("translate failed", String(error).slice(0, 200));
    return null;
  }
}
```

- [ ] **Step 3: crawl の最後に呼ぶ**

`CrawlSummary` にフィールドを足す。

```typescript
interface CrawlSummary {
  attempted: number;
  changed: number;
  unchanged: number;
  failed: number;
  newItems: number;
  skipped: number;
  translated: number;
}
```

`crawl()` の初期化に `translated: 0` を足し、`return summary` の直前で呼ぶ。

```typescript
  // After the fetching, not during it. A crawl that spent its time waiting on
  // a model would leave feeds unread, and the summaries are still there next
  // time whereas a feed's window is not.
  summary.translated = await translatePending(env, TRANSLATE_PER_CRAWL);

  return summary;
```

- [ ] **Step 4: 実際に訳させて読む**

```bash
cd cloudflare/reader && npx wrangler dev --port 8788 &
curl -s -X POST "http://127.0.0.1:8788/feeds/crawl?limit=5"
curl -s "http://127.0.0.1:8788/feeds/latest?limit=5"
```

> ⚠️ **`--local` を付けない。** Workers AI にローカル実装はなく、dev でも実サービスに出る。
> ニューロンを消費するので、`limit=5` より大きくしない。

Expected: crawl の応答に `translated` が出る。`latest` の英語フィードの `summary` が日本語になっている。**製品名が訳されていたら `TRANSLATE_PROMPT` を直す。** 確認できたら `wrangler dev` を止める。

- [ ] **Step 5: コミット**

```bash
git add cloudflare/reader/wrangler.jsonc cloudflare/reader/src/index.ts
git commit -F - <<'MSG'
Translate the summaries that arrive in English

Most of this subscription list publishes in English and the screen is
read in Japanese, so a summary in the original is a summary that gets
skipped. Translated once when it is crawled and kept, rather than on
every read.

An instruction model rather than a translation model. The list is
technical writing, full of product names a translation model declines
and word order it gets backwards, and a sentence that has to be decoded
is worse than the English was.

Thirty per crawl. Feeds do update in unison and an hour where forty
sites publish at once should not be the hour that spends the day.

Claude-Session: https://claude.ai/code/session_019eYyuJgyYGrx7zGoGBFRhs
MSG
```

---

## Task 5: 端末が要約と画像URLを受け取る

**Files:**
- Modify: `audioprobe/src/main/java/com/r1/audioprobe/Headlines.kt`

**Interfaces:**
- Consumes: Task 3 の `/feeds/latest` の `summary` / `image_url`
- Produces: `Headlines.Item(title: String, source: String, summary: String, imageUrl: String?)`

- [ ] **Step 1: `Item` を広げる**

```kotlin
    /**
     * [summary] is already in Japanese where the Worker could manage it, and in
     * the publisher's own language where it could not. Which of the two it is
     * is not this end's business.
     */
    data class Item(
        val title: String,
        val source: String,
        val summary: String,
        val imageUrl: String?,
    )
```

- [ ] **Step 2: パースを広げる**

```kotlin
    private fun parse(body: String): List<Item> {
        val array = JSONObject(body).optJSONArray("items") ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            val row = array.optJSONObject(index) ?: return@mapNotNull null
            val title = row.optString("title").trim()
            if (title.isEmpty()) return@mapNotNull null
            Item(
                title = title,
                source = row.optString("feed_title").trim(),
                summary = row.optString("summary").trim(),
                // optString gives "" for a JSON null as well as for a missing
                // key, and both mean the same thing here: nothing to fetch.
                imageUrl = row.optString("image_url").trim().ifEmpty { null },
            )
        }
    }
```

- [ ] **Step 3: ビルドが通ることを確認する**

Run: `./gradlew :audioprobe:assembleDebug`
Expected: BUILD SUCCESSFUL。`Signage.kt` は `Item` を名前で作っていないので、この時点では壊れない。

- [ ] **Step 4: コミット**

```bash
git add audioprobe/src/main/java/com/r1/audioprobe/Headlines.kt
git commit -F - <<'MSG'
Take the summary and the picture off the wire

Whether the summary was translated is decided on the server. There is
one summary as far as this end is concerned, which is what stops the
device needing a new build when that answer changes.

Claude-Session: https://claude.ai/code/session_019eYyuJgyYGrx7zGoGBFRhs
MSG
```

---

## Task 6: 端末が画像を取ってくる

**Files:**
- Create: `audioprobe/src/main/java/com/r1/audioprobe/Thumbnails.kt`

**Interfaces:**
- Consumes: `UploadSettings.unmeteredOnly`（既存）
- Produces: `Thumbnails.get(url: String?): Bitmap?`、`Thumbnails.prefetch(context: Context, url: String?)`

`Headlines.kt` と同じ形にする。単一スレッドの executor、`HttpURLConnection`、失敗は黙って諦める。

- [ ] **Step 1: `Thumbnails.kt` を書く**

```kotlin
package com.r1.audioprobe

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

/**
 * The pictures that go with the headlines.
 *
 * Fetched from the publisher directly rather than through the Worker. There is
 * nothing to gain by relaying them — Workers cannot resize an image without a
 * paid Images plan, so a relay would move the same bytes for an extra hop —
 * and the alternative is a Worker with an open fetch of arbitrary URLs in it.
 *
 * Everything here fails quietly. A screen that says it could not get a
 * thumbnail is answering a question nobody asked; the article is what the
 * screen is for, and it is still there without one.
 */
object Thumbnails {

    private const val TAG = "R1AudioProbe"
    private const val TIMEOUT_MS = 8_000

    /** The panel is 480 px across, so this is what a picture has to fill. */
    private const val TARGET_PX = 480

    /**
     * Below this the source is an icon rather than an article.
     *
     * The Worker drops what it can see — a thumbnail that declares 72 pixels,
     * a gravatar — but a great deal of what a feed offers declares no size at
     * all, and the first honest measurement of those is here, after the
     * header has been read.
     */
    private const val MIN_SOURCE_W = 240
    private const val MIN_SOURCE_H = 120

    /** One picture. Anything past this is not a thumbnail. */
    private const val MAX_BYTES = 2 * 1024 * 1024

    /**
     * Four.
     *
     * One is on screen, one is being read towards, and the two behind cover
     * going back. At 480x288 in ARGB_8888 that is about 2.2 MB, against the
     * ten items the list holds — which would be five and a half, kept for a
     * screen that has already moved past most of them.
     */
    private const val CACHE_SIZE = 4

    /** Bounded for the same reason as the cache: this runs for days. */
    private const val FAILED_SIZE = 64

    private val cache = object : LinkedHashMap<String, Bitmap>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Bitmap>) =
            size > CACHE_SIZE
    }

    private val failed = object : LinkedHashMap<String, Boolean>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Boolean>) =
            size > FAILED_SIZE
    }

    private val worker = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "thumbnails").apply { isDaemon = true }
    }

    private val inFlight = HashSet<String>()

    /** What is already decoded, or null. Cheap enough to call every tick. */
    @Synchronized
    fun get(url: String?): Bitmap? = url?.let { cache[it] }

    /**
     * Starts a fetch if there is not one already and the link is free.
     *
     * Called for the item on screen and the one after it, so arriving at a
     * headline usually finds its picture already there.
     */
    @Synchronized
    fun prefetch(context: Context, url: String?) {
        if (url.isNullOrEmpty()) return
        if (cache.containsKey(url) || failed.containsKey(url) || url in inFlight) return

        // The same rule the recordings follow. A standby screen quietly
        // spending a data plan on decoration is the thing to avoid, and the
        // switch for it already exists.
        if (UploadSettings(context).unmeteredOnly && !unmetered(context)) return

        inFlight.add(url)
        worker.execute {
            val bitmap = runCatching { load(url) }.getOrElse {
                Log.w(TAG, "thumbnail fetch failed", it)
                null
            }
            synchronized(this) {
                inFlight.remove(url)
                if (bitmap != null) cache[url] = bitmap else failed[url] = true
            }
        }
    }

    private fun unmetered(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) ?: false
    }.getOrDefault(false)

    private fun load(url: String): Bitmap? {
        val bytes = fetch(url) ?: return null
        return decode(bytes)
    }

    private fun fetch(url: String): ByteArray? {
        var connection: HttpURLConnection? = null
        return try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true
                // Named for the same reason the crawler is. A request that
                // hides what it is gives the person on the other end no way to
                // complain about it.
                setRequestProperty("user-agent", "r1-reader/0.1 (personal feed reader)")
                setRequestProperty("accept", "image/*")
            }
            if (connection.responseCode !in 200..299) return null

            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(16 * 1024)
            connection.inputStream.use { stream ->
                while (true) {
                    val read = stream.read(chunk)
                    if (read <= 0) break
                    buffer.write(chunk, 0, read)
                    // Checked while reading rather than from the header:
                    // Content-Length is optional and is occasionally a lie.
                    if (buffer.size() > MAX_BYTES) return null
                }
            }
            buffer.toByteArray()
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * Two passes: the header for the size, then the pixels at a size worth
     * keeping.
     *
     * Article pictures run 1200 across and the panel is 480, so decoding what
     * arrives would spend four times the memory to throw three quarters of it
     * away on the next line.
     */
    private fun decode(bytes: ByteArray): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)

        val width = bounds.outWidth
        val height = bounds.outHeight
        if (width <= 0 || height <= 0) return null
        // The last stop for an avatar the Worker could not see the size of.
        // Width alone would refuse a wide banner, which is a real article
        // picture, so the two are checked against different limits.
        if (width < MIN_SOURCE_W || height < MIN_SOURCE_H) return null

        var sample = 1
        while (width / (sample * 2) >= TARGET_PX) sample *= 2

        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }
}
```

- [ ] **Step 2: ビルドが通ることを確認する**

Run: `./gradlew :audioprobe:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 3: コミット**

```bash
git add audioprobe/src/main/java/com/r1/audioprobe/Thumbnails.kt
git commit -F - <<'MSG'
Fetch the pictures that go with the headlines

Straight from the publisher rather than through the Worker. There is
nothing to gain by relaying them — Workers cannot resize an image
without a paid Images plan, so a relay moves the same bytes for an extra
hop — and the alternative is a Worker with an open fetch of arbitrary
URLs in it.

Decoded in two passes so that a 1200 pixel article picture does not
spend four times the memory to have three quarters of it thrown away,
and refused outright when the source turns out to be an icon. That last
check is here rather than on the server because most of what a feed
offers declares no size at all.

Held four at a time and never written to disk. Ten headlines turn over
in a couple of minutes; there is nothing here worth surviving a reboot.

Claude-Session: https://claude.ai/code/session_019eYyuJgyYGrx7zGoGBFRhs
MSG
```

---

## Task 7: 新着画面のレイアウトと操作

**Files:**
- Modify: `audioprobe/src/main/java/com/r1/audioprobe/Signage.kt`

**Interfaces:**
- Consumes: `Headlines.Item`（Task 5）、`Thumbnails.get` / `Thumbnails.prefetch`（Task 6）
- Produces: `SignageScreen.fullBleed: Boolean`、`SignageScreen.onTap(action: () -> Unit)`

- [ ] **Step 1: `SignageScreen` に2つ足す**

`interface SignageScreen` に、既定の実装付きで足す。他の5画面は何も書かなくてよい。

```kotlin
    /**
     * Whether this screen wants the whole panel rather than the top of it.
     *
     * The character is the standby display's frame and every other screen sits
     * over it, in the half it does not occupy. A screen with a picture in it
     * has no room to do that, so it asks for the panel and the host puts the
     * character away while it is showing.
     */
    val fullBleed: Boolean get() = false

    /**
     * Handed to the screen before it is shown, so that one whose own views
     * consume touches can still act on a tap.
     *
     * Anything with a scroller in it takes the gesture before the Activity
     * sees it, and the Activity's tap is the only way off the standby display
     * when there is no character to ask for a motion.
     */
    fun onTap(action: () -> Unit) {}
```

- [ ] **Step 2: 横スワイプを取るコンテナを書く**

`Signage.kt` の `HeadlinesScreen` の直前に置く。

```kotlin
/**
 * Takes horizontal drags for itself and lets everything else through.
 *
 * The summary scrolls and the articles step sideways, and both are the same
 * finger on the same panel — there is no other input left. The wheel steps
 * between screens and the side button leaves, which were spoken for before
 * this screen existed.
 *
 * Only a drag is claimed. A tap goes where it would have gone, so the way off
 * the display does not quietly disappear on this screen.
 */
private class SwipeFrame(context: Context) : LinearLayout(context) {

    var onSwipe: ((Int) -> Unit)? = null
    var onTouched: (() -> Unit)? = null

    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var claimed = false

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
                claimed = false
                onTouched?.invoke()
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                // Sideways has to beat downwards as well as beat the slop.
                // A diagonal on a scroller is somebody scrolling.
                if (abs(dx) > slop && abs(dx) > abs(dy)) claimed = true
            }
        }
        return claimed
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_UP) {
            val dx = event.x - downX
            if (abs(dx) > slop * 4) onSwipe?.invoke(if (dx < 0) 1 else -1)
        }
        return true
    }
}
```

import を足す。

```kotlin
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.ScrollView
import kotlin.math.abs
```

- [ ] **Step 3: `HeadlinesScreen` を書き直す**

既存の `HeadlinesScreen` クラスを丸ごと差し替える。クラスの doc は書き足す形にする。

```kotlin
/**
 * Headlines, one at a time, with the article's picture and as much of its
 * summary as it has.
 *
 * This was a list first, four titles cut to two lines each, and it did not
 * work: at the size that fits four Japanese headlines on a 240 dp panel none of
 * them can be read from across a desk, and every one is truncated mid-sentence.
 * A list of four things you cannot read is worse than one thing you can.
 *
 * Then it was one headline and nothing else, which was readable and still went
 * unread — a line of text on a black panel is not something the eye stops at.
 * So the picture, which is why this screen asks for the whole panel and why
 * the character stands down while it is showing.
 *
 * The summary scrolls, and that changes what this screen is. It was built to
 * be glanced at, which is what the fifteen seconds are for; a screen you can
 * scroll is one somebody is reading. Both are true now, so the rotation stops
 * for a minute whenever the panel is touched and resumes when it is left
 * alone.
 */
private class HeadlinesScreen : SignageScreen {
    override val id = "headlines"
    override val title = "新着"

    override val fullBleed = true

    private lateinit var context: Context
    private lateinit var frame: SwipeFrame
    private lateinit var picture: ImageView
    private lateinit var meta: TextView
    private lateinit var scroller: ScrollView
    private lateinit var heading: TextView
    private lateinit var body: TextView

    private var tapAction: (() -> Unit)? = null

    private var index = 0
    private var advancedAt = 0L
    private var touchedAt = 0L
    private var showing: String? = null

    private companion object {
        /**
         * Fifteen seconds. Six was tried and was too quick, and the reason is
         * that this is a glanced-at screen rather than a read one: the clock
         * that matters is not how long a headline takes to read, it is how
         * long after looking up you start. A headline that changes while you
         * are halfway through it is worse than one you have already finished.
         *
         * Ten items makes the loop two and a half minutes, which is about how
         * often the list is refetched anyway.
         */
        const val DWELL_MS = 15_000L

        /**
         * How long a touch stops the rotation for.
         *
         * Long enough to finish reading something that scrolls, short enough
         * that a screen brushed in passing is back to normal before anybody
         * comes back to it.
         */
        const val HOLD_MS = 60_000L

        /** Slow enough not to snap, short enough not to be a thing happening. */
        const val FADE_MS = 400L

        /** How much of the panel the picture takes. */
        const val PICTURE_WEIGHT = 0.45f
    }

    override fun onTap(action: () -> Unit) {
        tapAction = action
    }

    override fun createView(context: Context): View {
        this.context = context.applicationContext
        val density = context.resources.displayMetrics.density
        val pad = (16 * density).toInt()

        frame = SwipeFrame(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            onSwipe = { by -> stepBy(by) }
            onTouched = { touchedAt = System.currentTimeMillis() }
        }

        picture = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            // Blank until there is something. An empty box is not a failure
            // worth drawing a frame around.
            setBackgroundColor(Color.BLACK)
        }
        frame.addView(
            picture,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, PICTURE_WEIGHT),
        )

        meta = SignageStyle.text(context, 12f, SignageStyle.ORANGE, bold = true).apply {
            // One line, and the position comes first so it is the part that
            // survives. Feed titles run to "ITmedia PC・AV・スマートフォン 最新
            // 記事一覧", which wrapped onto a second line and pushed the
            // counter down with it.
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            setPadding(pad, pad, pad, 0)
        }
        frame.addView(meta, SignageStyle.wide())

        heading = SignageStyle.text(context, 17f, SignageStyle.INK, bold = true).apply {
            setLineSpacing(6f, 1f)
            setPadding(pad, (6 * density).toInt(), pad, 0)
        }
        body = SignageStyle.text(context, 13f, SignageStyle.DIM).apply {
            setLineSpacing(6f, 1f)
            setPadding(pad, (10 * density).toInt(), pad, pad)
        }

        // Nothing is cut. There is no line count at which a summary stops
        // being worth reading, and the panel scrolls now.
        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            addView(heading, SignageStyle.wide())
            addView(body, SignageStyle.wide())
        }
        scroller = ScrollView(context).apply {
            isFillViewport = true
            addView(column)
            setOnTouchListener { view, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    touchedAt = System.currentTimeMillis()
                }
                if (event.actionMasked == MotionEvent.ACTION_UP) view.performClick()
                false
            }
            // Restores the way off the display, which the scroller would
            // otherwise swallow along with the rest of the gesture.
            setOnClickListener { tapAction?.invoke() }
        }
        frame.addView(
            scroller,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f - PICTURE_WEIGHT),
        )

        // The view is rebuilt every time the wheel lands here, while this
        // object and its position survive. Forgetting what is on screen makes
        // the first frame after arriving get painted rather than faded into.
        showing = null
        return frame
    }

    /** A swipe. Moves now and puts the timer back to the start. */
    private fun stepBy(by: Int) {
        val size = Headlines.items.size
        if (size == 0) return
        index = (index + by + size) % size
        advancedAt = System.currentTimeMillis()
        touchedAt = advancedAt
    }

    /**
     * [data] is the lifelog snapshot and has nothing to do with feeds; the
     * headlines arrive on their own schedule from [Headlines]. The parameter is
     * the interface's, and ignoring it is the honest thing to do rather than
     * inventing a use.
     */
    override fun refresh(data: LifelogSummary.Snapshot?) {
        val items = Headlines.items
        if (items.isEmpty()) {
            // Never "nothing new". The endpoint returns the latest ten whether
            // they arrived a minute ago or on Tuesday, so an empty list here
            // means the crawler has not run or cannot be reached — and those
            // two are worth telling apart.
            picture.visibility = View.GONE
            meta.text = ""
            heading.text = when (Headlines.reachable) {
                null -> "読み込み中…"
                false -> "取得できません"
                true -> "記事がありません"
            }
            body.visibility = View.GONE
            return
        }

        val now = System.currentTimeMillis()
        val held = now - touchedAt < HOLD_MS
        if (advancedAt == 0L) {
            advancedAt = now
        } else if (!held && now - advancedAt >= DWELL_MS) {
            advancedAt = now
            index += 1
        }
        // Modulo at use rather than at increment: the list is replaced under
        // this screen every couple of minutes and can come back shorter.
        val position = index % items.size
        val item = items[position]

        // Asked for on every tick rather than once on arrival, because the
        // fetch finishes on another thread and there is no callback to wait
        // for. Both calls are a map lookup when there is nothing to do.
        Thumbnails.prefetch(context, item.imageUrl)
        Thumbnails.prefetch(context, items[(position + 1) % items.size].imageUrl)
        val bitmap = Thumbnails.get(item.imageUrl)

        if (item.title == showing) {
            // The picture can land seconds after the text it belongs to, so
            // this one thing is kept up to date without a repaint.
            paintPicture(bitmap)
            return
        }
        val first = showing == null
        showing = item.title

        val paint = {
            // The counter and the feed name. A headline whose source is
            // nowhere is worse than a crowded line.
            meta.text = buildString {
                append(position + 1).append(" / ").append(items.size)
                append("   ").append(item.source.ifEmpty { "新着" })
            }
            heading.text = item.title
            body.visibility = if (item.summary.isEmpty()) View.GONE else View.VISIBLE
            body.text = item.summary
            paintPicture(bitmap)
            // A new article starts at its own beginning.
            scroller.scrollTo(0, 0)
        }

        if (first) {
            paint()
            frame.alpha = 1f
        } else {
            frame.animate().alpha(0f).setDuration(FADE_MS).withEndAction {
                paint()
                frame.animate().alpha(1f).setDuration(FADE_MS).start()
            }.start()
        }
    }

    /**
     * The picture, or the room it would have taken.
     *
     * Gone rather than blank: a third of what this list carries has no picture
     * at all — Hatena and Publickey have none between them — and a black
     * rectangle above every one of those reads as a screen that is failing to
     * load something.
     */
    private fun paintPicture(bitmap: android.graphics.Bitmap?) {
        if (bitmap == null) {
            picture.setImageDrawable(null)
            picture.visibility = View.GONE
        } else {
            picture.setImageBitmap(bitmap)
            picture.visibility = View.VISIBLE
        }
    }
}
```

- [ ] **Step 4: ビルドが通ることを確認する**

Run: `./gradlew :audioprobe:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 5: コミット**

```bash
git add audioprobe/src/main/java/com/r1/audioprobe/Signage.kt
git commit -F - <<'MSG'
Put the picture and the summary on the news screen

One headline on a black panel was readable and still went unread. A
line of text is not something the eye stops at, so the article's picture
goes above it and its summary below, and the screen asks for the whole
panel to have room for them.

The summary scrolls, which changes what this screen is. It was built to
be glanced at — that is what the fifteen seconds are for — and a screen
you can scroll is one somebody is reading. So the rotation stops for a
minute whenever the panel is touched, and resumes when it is left alone.

Sideways drags step between articles. It is the same finger on the same
panel as the scrolling, and there is no other input left: the wheel
steps between screens and the side button leaves. A tap is deliberately
not claimed, so the way off the display does not disappear here.

An article with no picture loses the frame rather than showing an empty
one. A third of this list has none, and Hatena and Publickey have none
between them.

Claude-Session: https://claude.ai/code/session_019eYyuJgyYGrx7zGoGBFRhs
MSG
```

---

## Task 8: キャラクターとの排他

**Files:**
- Modify: `audioprobe/src/main/java/com/r1/audioprobe/SignageActivity.kt`

**Interfaces:**
- Consumes: Task 7 の `SignageScreen.fullBleed` / `SignageScreen.onTap`
- Produces: なし（最後のタスク）

- [ ] **Step 1: タップの中身を関数に切り出す**

`onTouchEvent` の中身を `handleTap()` に移す。振る舞いは変えない。

```kotlin
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            handleTap()
            return true
        }
        return super.onTouchEvent(event)
    }

    /**
     * What a tap on the standby display does.
     *
     * Pulled out of [onTouchEvent] because a screen with a scroller in it
     * takes the gesture first, and hands it back here through
     * [SignageScreen.onTap] rather than losing it.
     */
    private fun handleTap() {
        val page = stage
        if (stageReady && page != null && !fullBleedShowing()) {
            // The clock starts again from the tap. Otherwise a motion
            // asked for by hand could be followed a second later by one
            // nobody asked for.
            scheduleMove(System.currentTimeMillis())
            StagePage.call(page, "window.stagePlay()", "play")
        } else {
            finish()
        }
    }

    /** Whether the screen on show has asked for the whole panel. */
    private fun fullBleedShowing(): Boolean = screens.getOrNull(index)?.fullBleed == true
```

`fullBleedShowing()` の条件が要るのは、**キャラが隠れている画面でタップしても動くものが無いから**。隠れているキャラに動けと言うより、離脱にしたほうが正しい。

- [ ] **Step 2: `contentLayout` を画面ごとに決める**

```kotlin
    /**
     * How much of the panel the screens get, and what backs them.
     *
     * A screen that asked for the whole panel gets it, and the character is
     * put away for as long as it is showing; see [show]. Everything else sits
     * in the top half over the character, which is what [CONTENT_FRACTION] is.
     */
    private fun contentLayout(screen: SignageScreen? = null): FrameLayout.LayoutParams {
        if (!stageReady || screen?.fullBleed == true) {
            return FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            )
        }
        val height = (resources.displayMetrics.heightPixels * CONTENT_FRACTION).toInt()
        return FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, height).apply {
            gravity = Gravity.TOP
        }
    }
```

`onCreate` の `frame.addView(content, contentLayout())` はそのままでよい（この時点で `stageReady` は false）。

- [ ] **Step 3: `show()` でキャラを出し入れする**

```kotlin
    private fun show(next: Int) {
        index = next
        val screen = screens.getOrNull(index) ?: return

        // The character is the frame around every other screen and is in the
        // way of this one. Hidden rather than destroyed: the page takes
        // seconds and nineteen megabytes to have a model on screen again, and
        // the wheel can come back here in a moment.
        val whole = screen.fullBleed
        stage?.visibility = if (whole || !stageReady) View.GONE else View.VISIBLE

        content.layoutParams = contentLayout(screen)
        // Screens paint themselves black — see SignageStyle.column — which is
        // right when they are the whole panel and wrong when there is a
        // character behind them. The scrim on [content] is what backs the text
        // instead, and it stops before the character does.
        content.background = if (stageReady && !whole) scrim() else null

        content.removeAllViews()
        val view = screen.createView(this)
        if (stageReady && !whole) view.setBackgroundColor(Color.TRANSPARENT)
        screen.onTap { handleTap() }
        content.addView(
            view,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )
        screen.refresh(LifelogSummary.current)
    }
```

- [ ] **Step 4: `onPageState` が全面表示を踏み潰さないようにする**

`stageReady = true` になった直後の3行は、いま無条件にキャラを出して scrim を敷く。新着画面を見ている最中にモデルの読み込みが終わると、キャラが被さる。

```kotlin
        stageReady = true
        val whole = fullBleedShowing()
        stage?.let { page ->
            page.visibility = if (whole) View.GONE else View.VISIBLE
            StagePage.call(page, "window.stageStandby()", "standby")
        }

        // The screens were laid out for a black panel a moment ago. Not the
        // one asking for the whole panel, which wanted exactly that.
        if (!whole) {
            content.background = scrim()
            content.layoutParams = contentLayout(screens.getOrNull(index))
            content.getChildAt(0)?.setBackgroundColor(Color.TRANSPARENT)
        }
```

> ⚠️ **既存の `content.background = scrim()` 以降の行と置き換える。** 元の実装に何が続いているかを読んでから当てること。

- [ ] **Step 5: ビルドが通ることを確認する**

Run: `./gradlew :audioprobe:assembleDebug`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: 実機で確かめる**

R1 に入れて、待受の「新着」まで回す。

```bash
./gradlew :audioprobe:installDebug
adb shell input keyevent KEYCODE_DPAD_DOWN   # ホイールを回して新着まで
adb exec-out screencap -p > /tmp/news.png
```

見るべきもの。

| 確認 | 期待 |
| --- | --- |
| 画像 | 上 45% に出る。粒になっていない |
| キャラ | 新着画面にいる間は消えている |
| 他の画面 | ホイールで移ると、キャラが戻り上半分表示になる |
| 要約 | 日本語で出ている |
| 縦ドラッグ | 要約がスクロールする |
| 横スワイプ | 前後の記事に移る |
| スワイプ後 | 15秒で勝手に送られない |
| 60秒放置 | 自動送りが戻る |
| 画像のない記事 | 画像枠が消えて文字だけになる |

スクリーンショットは `ACTIVITY.ja.md` に貼る。

- [ ] **Step 7: コミット**

```bash
git add audioprobe/src/main/java/com/r1/audioprobe/SignageActivity.kt ACTIVITY.ja.md
git commit -F - <<'MSG'
Stand the character down while the news is showing

Every other screen sits in the top half, over the character, which is
what CONTENT_FRACTION is for. A screen with a picture in it has no room
to do that, so it asks for the panel and the character goes away for as
long as it is showing — hidden rather than destroyed, because the page
takes seconds and nineteen megabytes to have a model on screen again and
the wheel can come back in a moment.

A tap on that screen leaves rather than asking for a motion. There is
nothing there to ask.

Claude-Session: https://claude.ai/code/session_019eYyuJgyYGrx7zGoGBFRhs
MSG
```

---

## デプロイ（承認が要る）

以下は**ユーザーの明示的な承認を得てから**実行する。ルール1（外部への反映）とルール4（環境変更）に該当する。

```bash
cd cloudflare/reader
npx wrangler d1 migrations apply r1-reader --remote   # 本番 D1 のスキーマ変更
npx wrangler deploy                                    # Worker のデプロイ
```

デプロイ後、最初のクロールを待たずに確認したい場合は `POST /feeds/crawl` を lifelog 経由で叩く。

---

## 自己レビュー

**1. 設計書の網羅**

| 設計書の項目 | タスク |
| --- | --- |
| 要約の抽出と400字 | Task 1 |
| 画像の抽出と足切り4種 | Task 2 |
| マイグレーションと upsert | Task 3 |
| `/feeds/latest` の列追加 | Task 3 |
| 翻訳、AI バインディング、30件上限 | Task 4 |
| `Headlines.kt` | Task 5 |
| `Thumbnails.kt` | Task 6 |
| レイアウト、スクロール、横スワイプ、60秒 | Task 7 |
| `fullBleed` とキャラの排他 | Task 8 |
| 実機確認とスクショ | Task 8 Step 6 |

**2. 設計書から変えたところ**

| 変更 | 理由 |
| --- | --- |
| 要約の日本語選択を端末の `summary_ja ?: summary` から SQL の `COALESCE` へ | 翻訳したかどうかは Worker の事情で、端末が知る必要がない |
| `Thumbnails` の HTTP を OkHttp から `HttpURLConnection` へ | 隣の `Headlines.kt` と同じ形になる。依存はどちらでも増えない |
| 従量制の判定を無条件から `UploadSettings.unmeteredOnly` の尊重へ | 同じ判断をする設定が既にあり、既定は ON |
| 端末側の実寸足切りを「240px 未満」から「幅240px / 高さ120px 未満」へ | 幅だけだと 1200×200 の横長バナーを落とす |
| `plainText` のタグ除去・復号を2巡へ | 1巡だと二重エスケープされた HTML からタグが消えない |
| タップの受け渡し（`SignageScreen.onTap`）を追加 | `ScrollView` がタップを飲むので、キャラ無し時の離脱手段が消える |

これらは設計書にも反映する。

**3. 型の一致**

- `FeedItem.summary` / `FeedItem.imageUrl` — Task 1 で定義、Task 2 で追加、Task 3 で使用。一致
- `Headlines.Item(title, source, summary, imageUrl)` — Task 5 で定義、Task 7 で使用。一致
- `Thumbnails.get(url: String?)` / `prefetch(context, url: String?)` — Task 6 で定義、Task 7 で使用。一致
- `SignageScreen.fullBleed` / `onTap` — Task 7 で定義、Task 8 で使用。一致
- `contentLayout(screen: SignageScreen? = null)` — Task 8 内で定義と使用。既存の引数なし呼び出しは既定値で通る

## ターン数

- 設計: 予定 4 / 実際 7
- 実装: 予定 8 タスク / 実際 未計測
