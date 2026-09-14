# 写真のキャプションを検索とエージェントから引けるようにする

作成日: 2026-09-14 (JST)
対象: `cloudflare/lifelog`
ブランチ: `feature/glasses-import` の後続、または並行

第1弾: [眼鏡カメラのタイムラプス取り込み](2026-09-14-glasses-timelapse-import-design.md)

## なぜやるか

`photos.caption` は書き込み専用になっている。
読んでいる箇所を全部洗ったところ3つしかなく、いずれも表示か内部用だった。

| 読んでいる場所 | 用途 |
| --- | --- |
| `src/index.ts:701,742` `dayView` | 日ビューに表示する |
| `src/ui.ts:158` | 同上 |
| `src/index.ts:1307` `captionBackfill` | 未キャプションの行を探す |

`searchData`（`/v1/search`）と `contextData`（`/v1/context`）はどちらも
`segments` しか見ていない。MCP の `lifelog_search` / `lifelog_recent` は
その2つを呼ぶだけなので、**写真のキャプションはエージェントに届かない。**

R1 のタイムラプスはずっとキャプションを書き続けてきたが、
日ビューを開いた人間の目にしか触れていない。
眼鏡カメラを足しても、この状態のままでは読まれないデータが増えるだけになる。

第1弾が「見ていたものを溜める」なら、これは「溜めたものを引けるようにする」。
片方だけでは目的を果たさない。

## 決めたこと

| 項目 | 決定 |
| --- | --- |
| 全文検索 | `photos_fts` を作る。`segments_fts` と同じ trigram トークナイザ |
| トリガの形 | **0006 の教訓を最初から入れる。**常に DELETE、非空のときだけ INSERT |
| 既存データ | マイグレーションで既存のキャプションを一括投入する |
| 検索の返し方 | `searchData` が音声と写真を引いて時刻順にマージ。`kind` と `source` を付ける |
| コンテキスト | `contextData` に `seen` を**追加**する。既存の `text` は触らない |
| MCP | 新しいツールは足さない。既存2つの返す中身が増える |
| 品質の足切り | `status = 'captioned'` かつ キャプションが非空。音声の `speech_ratio` に相当 |
| 1日のサマリー | やらない。第3弾 |

## マイグレーション `migrations/0018_photo_caption_fts.sql`

```sql
-- 写真キャプションの全文検索。
--
-- トークナイザは segments_fts と同じ trigram。理由も同じで、unicode61 は
-- CJK を空白までひとかたまりに扱うため、日本語の部分一致が効かない
-- (0002_trigram_fts.sql に実測が残っている)。
CREATE VIRTUAL TABLE IF NOT EXISTS photos_fts USING fts5(
  caption,
  photo_id UNINDEXED,
  taken_at UNINDEXED,
  tokenize = 'trigram'
);

-- 0006 で segments 側が踏んだ穴を、こちらは最初から避ける。
--
-- 「非空のときだけ発火」にすると、キャプションが空に訂正されたときに
-- 古いテキストが索引に残り続ける。DELETE は無条件、INSERT だけ条件付きにする。
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

-- 溜まっているぶんを全部入れる。ここが今回の主目的のひとつ。
INSERT INTO photos_fts(caption, photo_id, taken_at)
SELECT caption, photo_id, taken_at
  FROM photos
 WHERE caption IS NOT NULL AND caption <> '';
```

キャプションは `captionPhoto` が `UPDATE` で書く（`INSERT` 時は NULL）ので、
`AFTER UPDATE OF caption` で拾える。`AFTER INSERT` は要らない。

## `searchData` — 2つの索引を1つの結果に

いまは `segments_fts`（3文字以上）と `segments` の LIKE 走査（2文字以下）を
出し分けている。写真側にも同じ出し分けをそのまま適用する。
trigram が3文字窓である以上、条件は同じだから。

### 返す形

いまの `results` は `segments` の列がそのまま並んでいる。
写真が混ざると列が揃わないので、共通部分を決める。

| フィールド | 音声 | 写真 |
| --- | --- | --- |
| `kind` | `"audio"` | `"photo"` |
| `id` | `segment_id` | `photo_id` |
| `at` | `started_at` | `taken_at` |
| `text` | `transcript` | `caption` |
| `source` | `null` | `r1` \| `glasses` |

`source` を返すのは、ヒットが「周囲の様子」なのか「自分が見ていたもの」なのかで
意味が違うため。第1弾で `photos.source` を持たせたのはここで効く。

マージは時刻の降順で、両方合わせて `limit` 件。
音声と写真で件数を割り当てない。その時間帯にどちらが多かったかは、
検索語のほうが決めることで、こちらが決めることではない。

> 破壊的変更になる。`/v1/search` と MCP の `lifelog_search` を読む側は、
> `segment_id` ではなく `id` と `kind` を見るように直す必要がある。
> 利用者は本人1人なので、互換フィールドは残さない。

## `contextData` — `seen` を足す

`text` は触らない。`nudge.ts` と `interpret.ts` がこの blob を読んでおり、
中身の意味を変えると、あの2つの判断が黙って変わる。

追加するフィールド。

| フィールド | 中身 |
| --- | --- |
| `seen` | その時間帯のキャプションを時刻順に連結したもの |
| `photos` | 個々の行（`photo_id` / `taken_at` / `caption` / `source`） |

エージェントには「話していたこと（`text`）」と「見ていたもの（`seen`）」が
別々に届く。混ぜると、聞いた話と見た物の区別がつかなくなる。

足切りは `status = 'captioned'` かつキャプションが非空。
`failed`（モデルが画像を拒否した行）は入れない。

`LIMIT` は音声と同じく 200。30秒間隔だと100分ぶんで埋まるので、
既定の `before_sec = 3600`（1時間 = 120枚）なら収まる。

## MCP

`src/mcp.ts` にツールは足さない。`lifelog_recent` と `lifelog_search` が
呼んでいる先が増えるだけなので、変えるのは `description` の文面。

| ツール | 直すこと |
| --- | --- |
| `lifelog_recent` | 「見ていたもの」も返ることを説明に足す |
| `lifelog_search` | 検索対象に写真キャプションが入ることと、`kind` / `source` の意味を書く |

説明文を直さないとエージェントが新しいフィールドを使わない。
ツールの説明は仕様書であって飾りではない。

## 失敗の見え方

| 何が | どうなる | どうする |
| --- | --- | --- |
| 既存キャプションの一括投入が途中で落ちる | 索引が部分的になる | マイグレーションは1トランザクション。落ちたら何も入らない |
| キャプションが空に訂正された | 索引に古い文が残る…はず**だが残らない** | 0006 の形を最初から入れてあるため |
| 似たキャプションが大量にヒットする | 検索結果が同じ絵で埋まる | 実データを見てから判断。下の「残っていること」 |
| `failed` の行 | 検索に出ない | 足切りで除外済み |
| 古い `/v1/search` 利用者 | `segment_id` が無くなって壊れる | 利用者は本人のみ。`ui.ts` を同時に直す |

## テスト

第1弾で入れる `vitest` をそのまま使う。

| 対象 | 置き場所 | テストすること |
| --- | --- | --- |
| `mergeResults(audio, photos, limit)` | `src/search-merge.ts` | 時刻降順、limit で切る、`kind` が付く、片方が空でも動く |

SQL トリガは純粋関数にできないので、ローカル D1 に対して手で確認する。

```bash
pnpm db:migrate --local
# キャプションを入れる → 検索に出る
# キャプションを '' に更新する → 検索から消える  ← 0006 の穴の再発確認
# 行を削除する → 索引からも消える
```

この3手順は必ず実行する。0006 は「トリガが正しいはず」で通してから
本番で見つかった穴なので、同じ確認を省かない。

## やらないこと

| やらないこと | 理由 |
| --- | --- |
| 1日のサマリー | 第3弾。コーパスが引けるようになってから設計する |
| ベクトル検索 / 埋め込み | trigram で足りるか見てから。Vectorize は後から足せる |
| 画像そのものの類似検索 | キャプションが成果物で、画像は原本。いま要らない |
| 重複キャプションの圧縮 | 実データで問題になってから |
| `text` に `seen` を混ぜる | `nudge.ts` と `interpret.ts` の判断が黙って変わる |
| 互換フィールドの維持 | 利用者は本人1人 |

## 影響範囲

| 対象 | 変更 | 既存への影響 |
| --- | --- | --- |
| `migrations/0018_photo_caption_fts.sql` | 新規。仮想テーブルとトリガ2つ、既存ぶん投入 | 既存の `photos` 行は変わらない |
| `src/index.ts` `searchData` | 写真を引いてマージ、返す形を変更 | **破壊的。** `ui.ts` を同時に直す |
| `src/index.ts` `contextData` | `seen` と `photos` を追加 | `text` は不変。`nudge` / `interpret` に影響なし |
| `src/mcp.ts` | 2ツールの `description` | ツール名と引数は不変 |
| `src/ui.ts` | 検索結果の描画を `kind` 対応に | 検索画面の見た目が変わる |
| `src/search-merge.ts` | 新規（純粋関数） | なし |

R1 端末（`app/`, `core/`）は変更しない。書き込み側は一切触らない。

## 残っていること

**1日のサマリーは第3弾。** 材料（音声の transcript と写真のキャプション）が
揃って引けるようになってから設計する。いま作ると、
読み出し口が無い状態でサマリー生成を書くことになる。

30秒間隔のキャプションが検索を薄めるかどうかは、実データを見るまで分からない。
連続するフレームはほとんど同じ絵なので、似た文が並ぶ可能性はある。
眼鏡カメラのフレームが数百枚溜まった時点で、
同じ語で検索して結果が同じ絵で埋まるかを確認する。
埋まるようなら、`source_file` 単位で代表を選ぶか、
連続する同一キャプションをまとめる方向で直す。

## ターン数

- 設計: 予定 2 / 実際 1

第1弾のレビューで「コーパスにしたい」という目的が出たことから派生した。
最初の聞き取りで「何のために取り込むのか」を押さえていれば、
第1弾の設計時点で2本に割ることが見えていた。
