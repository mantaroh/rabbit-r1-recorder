# R2 に置く写真を SSE-C で暗号化する

作成日: 2026-09-16 (JST)
状態: 設計承認済み。実装前。

## 目的

R2 のダッシュボード、R2 の API トークン、バケットの設定ミス。
このどれかで R2 を直接見られても、写真を画像として開けないようにする。

Web で見るときは、今までどおり Worker が復号した JPEG を返す。
Web 画面の側は何も変えない。

守る相手は「R2 を直接見る人」だけにする。
Worker にも鍵を持たせない形（ブラウザ側で復号する）は検討したが、採らない。
キャプション生成と声かけの在室判定は Worker が画像を読むので、どちらも動かなくなる。

## 方式

R2 の SSE-C（Server-Side Encryption with Customer-Provided Keys）を使う。
Worker バインディングの `put` / `get` に `ssecKey` を渡すだけで済む。
鍵は 32 バイトで、hex 文字列でも渡せる。

参照: [Use SSE-C](https://developers.cloudflare.com/r2/examples/ssec/)、
[Workers API reference](https://developers.cloudflare.com/r2/api/workers/workers-api-reference/)

| 案 | 採否 | 理由 |
| --- | --- | --- |
| R2 の SSE-C | 採用 | 変更が小さい。Range 付きの取得とストリーミングがそのまま残る |
| Worker で AES-GCM を自前でかける | 不採用 | アップロードを全部メモリに読むことになる。写真の Range 取得も捨てることになる。暗号文の形式も自分で持つことになる。今回の目的に対して、抱えるものが多い |
| ブラウザで復号する | 不採用 | 上に書いたとおり、Worker が画像を読む処理が動かなくなる |

## 決めたこと

| 項目 | 内容 |
| --- | --- |
| 鍵 | Worker の secret `PHOTO_SSEC_KEY`。64 桁の hex |
| 鍵の控え | 利用者が手元のパスワード管理に置く。**なくすと、それ以降の写真は Cloudflare でも戻せない** |
| 対象 | 写真の原寸（`photos.r2_key`）と、PC が送る縮小版（`photos.caption_r2_key`）。R1、眼鏡、PC のどれから来た写真も同じ扱い |
| 対象外 | 音声、地図タイル |
| 既存の写真 | 平文のまま残す。今回暗号化するのは、反映後に入る写真だけ |
| 判定 | `photos` に `sealed INTEGER NOT NULL DEFAULT 0` を足す。1 の行は、原寸も縮小版も SSE-C で置いてある |

判定を D1 に持つのは、S3 と同じなら、平文のオブジェクトに鍵を渡した取得はエラーになるからだ。
「鍵を付けて取ってみて、だめなら鍵なしで取り直す」形は、1 回の取得が 2 回になるのでやめた。

## 変更箇所

| ファイル | 変更 |
| --- | --- |
| `cloudflare/lifelog/migrations/0021_photo_sealed.sql` | `photos.sealed` を追加。改行は LF |
| `cloudflare/lifelog/src/photo-store.ts`（新規） | `photoPutOptions(env)` と `getPhoto(env, key, sealed, options)` を置く。鍵の有無と `sealed` の見分けはここに閉じ込める |
| `cloudflare/lifelog/src/index.ts` 写真の受け取り（1329 行目付近） | 原寸と縮小版を `ssecKey` 付きで置き、行に `sealed = 1` を書く |
| `cloudflare/lifelog/src/index.ts` 写真の配信（1179 行目付近） | `photos.sealed` を読み、`getPhoto` 経由で取る。音声の配信は変えない |
| `cloudflare/lifelog/src/index.ts` `captionPhoto`（1414 行目付近） | 同上 |
| `cloudflare/lifelog/src/index.ts` キャプションのデバッグ（1470 行目付近） | 同上 |
| `cloudflare/lifelog/src/nudge.ts` 在室判定（472 行目付近） | 同上 |
| `cloudflare/lifelog/src/index.ts` の `interface Env`、`nudge.ts` の `NudgeEnv` | `PHOTO_SSEC_KEY?: string` を足す。ほかの secret（`INGEST_TOKEN` など）と同じ書き方にする |

## 鍵がないとき

`PHOTO_SSEC_KEY` が設定されていなければ、今までどおり平文で置き、`sealed = 0` にする。
ローカル開発と、secret を登録する前のデプロイで、受け取りが止まらないようにするためだ。

逆に、`sealed = 1` の行を読むときに鍵がなければ、エラーにする。
配信は 500、キャプション生成は `failed` にして理由を残す。
鍵なしで取りにいっても、失敗するだけだからだ。

## 実装前に確かめること

| 項目 | 確かめ方 | だめだったとき |
| --- | --- | --- |
| `put` で `sha256` と `ssecKey` を同時に渡せるか | preview で実際に置く | Worker で平文のハッシュを計算して照合してから置く |
| 鍵なしの `get` が失敗するか | preview で鍵を付けずに取る | 目的を満たさないので、方式から見直す |
| Range 付きの `get` が `ssecKey` と一緒に使えるか | preview で Range 付きで取る | 写真だけ Range を無視して全体を返す |
| miniflare が `ssecKey` を扱うか | ローカルテストで確認 | 単体テストでは「`ssecKey` が渡っていること」だけを見て、暗号化そのものは preview で確認する |

## テスト

- **`photo-store.ts`**：鍵あり・鍵なし・`sealed` の組み合わせで、渡すオプションとエラーを確かめる。
- **受け取り**：鍵ありなら `sealed = 1` になり、鍵なしなら `sealed = 0` になる。
- **配信**：`sealed = 1` と `sealed = 0` の写真が、どちらも同じ JPEG で返る。
- **本番反映後**：新しい写真を日ビューで開けること。同じオブジェクトを鍵なしで取ると失敗すること。

## 反映の手順

どれも実行前に、対象アカウントを確かめたうえで承認をもらう。

1. 鍵を作り、利用者が控える。`wrangler secret put PHOTO_SSEC_KEY` で登録する。
2. 本番 D1 に migration 0021 を適用する。
3. Worker をデプロイする。
4. 新しく入った写真が Web で表示でき、キャプションも付くことを確かめる。
5. 同じ写真を鍵なしで R2 から取ろうとして、失敗することを確かめる。

## 今回やらないこと

- 既存の写真の暗号化（置き直し）。やるなら別の作業にする。
- 鍵の入れ替え。SSE-C は鍵を変えるとオブジェクトの置き直しが要る。必要になったら、`sealed` を鍵の世代番号として使う形で広げる。
- 音声の暗号化。

## ターン数

予定 / 実際: 設計 4 / 4（実装は未着手）
