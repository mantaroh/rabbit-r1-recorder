# 新着画面に画像と要約を出す

作成日: 2026-09-09 (JST)
対象: `cloudflare/reader` / `audioprobe`
ブランチ: `feature/nudge` から分岐予定

## なぜやるか

いまの待受の「新着」画面は見出しと出典しか出しておらず、文字だけなので読まれない。
記事のタイトル・要約・画像を出して、目で止まる画面にする。

## 決めたこと

| 項目 | 決定 |
| --- | --- |
| レイアウト | この画面のときだけキャラクターを隠し、240×320dp を全面使う |
| 画像の取得 | 端末が publisher から直接 GET する。Worker は中継しない |
| 画像が無い記事 | 文字だけのレイアウトに切り替える。記事は落とさない |
| 要約の翻訳 | 英語の要約だけ日本語にする。見出しは原文のまま |
| 翻訳の場所 | `reader` Worker のクロール時に一度だけ。D1 に保存する |
| 長い要約 | 縦ドラッグでスクロールできる。画像は上に固定 |
| 記事の送り | 横スワイプで前後。触ったら自動送りが60秒止まる |
| テスト | `reader` に vitest を入れ、パーサの単体テストを書く |

## 実測データ

購読リスト57本のうち14本をサンプルし、2026-09-09 に取得した結果。
5本は 404 / 接続失敗 / item なしで、死んだフィードが混ざっているのは既知のとおり。

| フィード | 件数 | 画像あり | 画像の出どころ | 要約の中央値 |
| --- | --- | --- | --- | --- |
| android-developers | 20 | 20/20 | `media:thumbnail` | 15,475字 |
| feedburner/hsDu | 20 | 20/20 | `media:thumbnail` | 15,475字 |
| theregister | 20 | 20/20 | `enclosure` | 88字 |
| mashable | 20 | 20/20 | 本文中の `<img>` | 122字 |
| mozillagfx | 10 | 10/10 | `media:content` | 378字 |
| y-anz-m | 20 | 9/20 | `media:thumbnail` | 2,028字 |
| publickey1 | 15 | 0/15 | — | 123字 |
| はてブ hotentry | 20 | 0/20 | — | 158字 |
| guykawasaki | 10 | 0/10 | — | 465字 |

155件中99件（64%）に画像がある。ただし偏っていて、9本中3本は1件も持たない。

この2つが設計を決めた。

- **android-developers の `media:thumbnail` は 72×72 px / 7KB。**
  URL の `/s72-c/` を `/s640/` に書き換えると 200 が返り、136KB の実画像になる。
  書き換えないと画像枠が72pxの粒になる。
- **mozillagfx の `media:content` は gravatar の著者アイコン 96px** で、記事の画像ではない。
  タグがあれば画像、という扱いはできない。

## サーバ側 — `cloudflare/reader`

### データの流れ

既存の経路は変えない。増えるのは端末が画像バイトを直接取る線が1本だけ。

```txt
cron ──▶ crawl 57 feeds ──▶ D1 (items: +summary +summary_ja +image_url)
                              │
端末 ──▶ lifelog ──service binding──▶ /feeds/latest ──┘
  │
  └──▶ publisher の画像URLへ直接 GET   ← 新設。Worker を経由しない
```

### `src/feed.ts` — `FeedItem` に2つ足す

```typescript
summary: string | null;    // タグを落とした平文、400字で切る
imageUrl: string | null;
```

`summary_ja` はパーサの守備範囲外で、`index.ts` が翻訳して足す。

**要約の抽出**は `description` → `summary` → `content:encoded` → `content` の順。
CDATA解除のあと、**タグ除去とエンティティ復号を2巡**して空白圧縮、400字で切る。

2巡なのは、エスケープの回数がフィードによって違うため。
CDATA に生の HTML を入れたフィードは1回目のタグ除去で消えるが、
`&lt;p&gt;` と1回エスケープしたフィードは復号するまでタグが現れず、
`&amp;lt;p&amp;gt;` と2回エスケープしたフィードは2回目の復号まで現れない。
既存の `decode()` が数値実体を先に処理しているのと同じ話で、順番と回数に意味がある。

20字未満の結果は「続きを読む」の類とみなして次の要素に落とす。

**画像の抽出**は候補を上から順に見て、足切りに落ちたら次の候補へ落とす。

| 順 | 候補 |
| --- | --- |
| 1 | `<enclosure type="image/*">` |
| 2 | `<media:content medium="image">` または `type="image/*"` |
| 3 | `<media:thumbnail>` |
| 4 | 本文中の最初の `<img>` |

足切りは4つ。

| 規則 | 理由 |
| --- | --- |
| `width` / `height` 属性が 200px 未満なら捨てる | Blogger は 72×72、FeedBurner の追跡ピクセルは 1×1 |
| ホストが `gravatar.com` なら捨てる | mozillagfx の `media:content` は著者アイコン |
| `http` / `https` 以外、500字超は捨てる | `data:` URI を端末に渡さない |
| `googleusercontent` の `/sNN-c/` を `/s640/` に書き換える | 72×72 7KB が 640px 136KB になる |

書き換えた URL は 200px 足切りの対象外にする。書き換え後の実寸は属性が語らない。

### マイグレーション `migrations/0002_item_media.sql`

```sql
ALTER TABLE items ADD COLUMN summary TEXT;
ALTER TABLE items ADD COLUMN summary_ja TEXT;
ALTER TABLE items ADD COLUMN image_url TEXT;
```

既存行を埋めるために、`fetchOne` の `INSERT OR IGNORE` を upsert に変える。

```sql
INSERT INTO items (...) VALUES (...)
ON CONFLICT(item_id) DO UPDATE SET
  summary   = excluded.summary,
  image_url = excluded.image_url
WHERE items.summary IS NULL AND items.image_url IS NULL
```

まだ配信中の記事は次のクロールで埋まる。配信から落ちた古い記事は NULL のまま残るが、
`/feeds/latest` が返すのは新着10件なので実害がない。

`newItems` の集計がデプロイ直後の1周だけ水増しされる。ログとクロール応答にしか出ない数字なので許容する。

### 翻訳

`wrangler.jsonc` に `"ai": { "binding": "AI" }` を足す。

このWorkerには「Deliberately the only binding」というコメントがあり、
D1 以外を持たないことが lifelog と分けた理由そのものになっている。
AI バインディングは音声アーカイブに手が届く類のものではないので分離は保たれるが、
あのコメントは事実に合わせて書き換える。

- **判定**: 要約に漢字・ひらがな・カタカナが1文字も無ければ外国語とみなす
- **モデル**: `@cf/meta/llama-3.1-8b-instruct`
- **指示**: 技術ニュースとして自然な日本語にする、固有名詞・製品名は原語のまま残す、訳文だけを返す
- **対象**: 要約のみ。見出しは訳さない
- **タイミング**: クロール1周の終わりに、`summary IS NOT NULL AND summary_ja IS NULL`
  の行を新しい順に拾って訳す。「その回の新着だけ」ではないので、
  upsert で後から `summary` が埋まった記事も次の回で拾われる
- **失敗時**: `summary_ja` は NULL のまま。端末が原文にフォールバックする
- **上限**: 1クロールあたり翻訳 30件まで。溢れた分は次の回にまわす

30件の上限は、フィードが一斉に更新された回にニューロンを使い切らないためのもの。
実際の毎時新着数を測っていないので、最初の数日はログの `translated` を見て見直す。

### `/feeds/latest`

`SELECT` に `COALESCE(i.summary_ja, i.summary) AS summary` と `i.image_url` を足す。

**日本語を選ぶのはここ**で、端末ではない。
下流にとって要約は1つで、それが訳されたものかどうかは Worker の事情でしかない。
端末が知る形にすると、その判断を変えるたびに端末を焼き直すことになる。

`/feeds/search` は触らない。あれは Hermes が本文検索に使う口で、画像は要らない。

`limit=10` で応答が 4KB 前後になる。いまは 1KB 弱。

## 端末側 — `audioprobe`

### 変更するファイル

| ファイル | 変更 |
| --- | --- |
| `Headlines.kt` | `Item` に `summary` / `imageUrl` を足し、パースを広げる |
| `Thumbnails.kt` | 新規。画像の取得・デコード・LRU |
| `Signage.kt` | `HeadlinesScreen` を作り替え、`SignageScreen` に `fullBleed` と `onTap` を足す |
| `SignageActivity.kt` | `fullBleed` な画面のときキャラを隠して全面を渡し、タップ処理を画面に渡す |

### `Thumbnails.kt`（新規）

HTTP は `java.net.HttpURLConnection`。隣の `Headlines.kt` と同じ形になる。
OkHttp も既存依存なのでどちらでも依存は増えないが、同じディレクトリで2つの流儀を使う理由がない。

- **デコードは2段**。`inJustDecodeBounds` で寸法を見て `inSampleSize` を決め、
  幅 480px を下回らない最小の間引きで読む
- **実寸が幅 240px 未満または高さ 120px 未満なら捨てて画像なし扱い**。
  サーバ側の足切りをすり抜けたアバターに対する最後の防波堤。
  幅だけで判定すると 1200×200 の横長バナーを落とすので、2つの閾値を分ける
- **メモリ LRU は4枚**。480×288 ARGB_8888 で約 553KB、合計 2.2MB。
  1件ずつ表示で先読みは次の1枚だけなので4枚で足りる
- **ディスクキャッシュは作らない**。10件が数分で回る画面で、再起動をまたいで残す価値がない
- **`UploadSettings.unmeteredOnly` を尊重する**。既定は ON で、録音のアップロードと同じ switch。
  同じ判断をする設定を2つ持たない
- 1枚 2MB 上限、タイムアウト 8秒（`Headlines` と同じ）。実測の1枚は 100〜200KB

### `HeadlinesScreen` のレイアウト

ビュー階層は1つだけ持ち、画像の有無で `ImageView` を `GONE` にして行数を振り替える。

```txt
LinearLayout (vertical, black)
├─ ImageView   240×144dp / CENTER_CROP          ← 画像なしのとき GONE
├─ meta   12sp orange   "3 / 10   ITmedia"
└─ ScrollView
   └─ LinearLayout (vertical)
      ├─ title  17sp bold
      └─ body   13sp dim                        ← 要約が空なら GONE
```

画像は上に固定し、スクロールするのはテキストだけ。
画像がこの画面の視覚的な錨で、スクロールで消えると何の記事を読んでいるか分からなくなる。

`maxLines` は置かない。スクロールできるので切る必要がない。

### 操作

R1 のキーはホイール（上下＝画面切替）と横のボタン（＝離脱）で埋まっているので、
記事の送りとスクロールはタッチしかない。

| 操作 | 割り当て |
| --- | --- |
| 横スワイプ | 前の記事 / 次の記事 |
| 縦ドラッグ | 要約のスクロール |
| ホイール上下 | 画面の切替（変更なし） |
| 横ボタン | 離脱（変更なし） |

タッチは `HeadlinesScreen` が自分のビューで拾う。
`SignageActivity.onTouchEvent` には子が消費しなかった分しか来ないので、
パネルを叩いてキャラを動かす既存の実装とは衝突しない。
この画面ではキャラが隠れているので、そもそも触る相手がいない。

横スワイプは `ScrollView` を包む親のコンテナが `onInterceptTouchEvent` で横取りする。
横方向の移動量が縦を上回り、かつ `scaledTouchSlop` を超えたときだけ奪う。

> ⚠️ **タップは奪わない。** `ScrollView` はタップも飲むので、そのままだと
> キャラが居ない設定のときの離脱手段（画面を叩くと `finish()`）が、この画面だけ消える。
> `SignageScreen` に `fun onTap(action: () -> Unit)` を足し、
> `SignageActivity` が自分のタップ処理を渡す。既定は何もしない実装なので他の画面は無変更。

**自動送りは触ったら60秒止まる。** 普段は今までどおり15秒で送る。
スワイプかスクロールがあった時刻を記録し、そこから60秒は `index` を進めない。
記事が変わるときはスクロール位置を先頭に戻す。

いまの `HeadlinesScreen` は「見る画面であって読む画面ではない」という前提で15秒を選んでいる。
スクロールできる画面は読む画面なので、その前提が半分崩れる。
60秒はその折り合いで、放置すれば今までどおり回り、手を出せば止まる。

### キャラとの排他

`SignageScreen` に `val fullBleed: Boolean get() = false` を足し、`HeadlinesScreen` だけ `true` にする。

`show()` が `fullBleed` かつ `stageReady` のとき、

- `stage` を `View.GONE` にする
- `content` の `LayoutParams` を `MATCH_PARENT` に付け替える
- `content.background`（scrim）を外し、画面自身の黒背景を使う

離れるときに戻す。既存の `contentLayout()` と `scrim()` の分岐に条件が1つ増えるだけで、
キャラ側のコードもモデルの読み込みも触らない。

`stage` は `GONE` にするだけで破棄しない。19MB のモデルを読み直すのは論外。

## 失敗の見え方

| 起きたこと | 画面 |
| --- | --- |
| 画像の取得・デコード失敗 | 黙って文字レイアウト。エラーは出さない |
| 画像が 240px 未満 | 同上 |
| 従量制回線 | 同上 |
| 翻訳が失敗した / まだ済んでいない | 英語の原文を出す |
| 要約が空 | 要約行を消して見出しだけ |
| フィード自体が引けない | 現状の「取得できません」のまま |

画像が出ないことをエラーとして見せない。この画面が答えるべきなのは記事の内容であって、
サムネイルの調達状況ではない。

## テスト

`reader` に vitest を入れる。`devDependencies` が1つ増える。

パーサの足切り規則が7個あり、全部を目で確かめるのは現実的でない。
実測した4本のフィードの XML 断片を fixture にして、次を確かめる。

- Blogger の `/s72-c/` が `/s640/` に書き換わる
- gravatar の `media:content` が落ちて、次の候補に進む
- 1×1 の追跡ピクセルが落ちる
- `enclosure` が `media:thumbnail` より優先される
- 二重エスケープされた HTML からタグが消える
- 15,475字の本文が400字で切れる
- 画像が1つも無い記事で `imageUrl` が null になる

端末側は実機で確認する。スクリーンショットを撮って `ACTIVITY.ja.md` に残す。

## やらないこと

| やらないこと | 理由 |
| --- | --- |
| 記事ページを取って `og:image` を拾う | クロールのサブリクエストが増える。文字レイアウトで足りるか実機で見てから決める |
| Worker で画像を中継・縮小する | `cf.image` は有料の Images 契約が要る。中継だけなら通信量は変わらない |
| 画像のディスクキャッシュ | 数分で回る画面で再起動をまたぐ価値がない |
| 見出しの翻訳 | 固有名詞や製品名が壊れる事故が見出しで起きる |
| 古い記事の遡り翻訳 | 出るのは新着10件だけ |
| `/feeds/search` に画像を足す | Hermes の本文検索に画像は要らない |

## 影響範囲

| 対象 | 影響 |
| --- | --- |
| `reader` の D1 | カラム3つ追加。既存データは消えない |
| `reader` のバインディング | AI が増える。D1 単独という設計原則のコメントを書き換える |
| `lifelog` | 変更なし。`/v1/feeds/*` は素通しのまま |
| 他の待受画面 | 変更なし。`fullBleed` は既定 false |
| キャラクター表示 | 新着画面にいる間だけ `GONE`。他の画面では従来どおり |
| 端末の通信量 | 画像で1枚 100〜200KB 増える。従量制では取らない |
| 端末の依存 | 増えない。OkHttp は既にある |

## ターン数

- 設計: 予定 4 / 実際 7
- 実装: 未計測
