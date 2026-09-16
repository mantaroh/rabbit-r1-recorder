# Lifelog PC

Windowsの各画面を60秒ごと（設定で変更可）に記録し、日本語OCRと画像をlifelogへ送る常駐アプリです。
画面が変化しなくても毎回撮影します。

設計: [Windowsスクリーンショットのコーパス化](../../docs/superpowers/specs/2026-09-16-windows-screenshot-corpus-design.md)
実装・運用記録: [本番反映と検証結果](../../docs/superpowers/plans/2026-09-16-windows-screenshot-corpus.md)

2026-09-16に本番反映・運用開始済み。利用者の方針により、当面はこの構成で運用します。

## インストール・起動

Windows版Python 3.11以降（Tkinter込み）とWindowsの日本語OCR言語機能が必要です。
このリポジトリはWindows ARM64 / Python 3.11で検証しています。

リポジトリのルートからPowerShellで実行します。

```powershell
& ./windows/screenshot-agent/install.ps1
```

実行ポリシーでスクリプトを実行できない場合は、同じフォルダで以下を実行できます。

```powershell
python -m venv .venv
./.venv/Scripts/python.exe -m pip install -r requirements.txt
./.venv/Scripts/pythonw.exe agent.py
```

以後はデスクトップの **Lifelog PC** または `launch.vbs` を開きます。
Python単体のexe配布ではなく、このフォルダと仮想環境を使う構成です。配置場所を移した場合は再インストールしてください。

設定画面で接続先、Cloudflare Access Client ID / Secret、PC専用トークン（Workerの `DESKTOP_TOKEN`）を入力し、
「設定を保存」→「記録を開始」。既定間隔は60秒です。管理者トークンは必要ありません。
設定済みなら次回の起動から記録を開始します。

- 「一時停止」で新しい撮影と送信を停止。「記録を開始」で再開。
- ウィンドウの×はトレイへ格納。「終了」はプロセスを終了。
- 自動起動は「Windowsへのサインイン時に起動」で有効にできます。
- 「未送信を再試行」はバックオフの待ち時間を解除します。一時停止中なら再開も必要です。
- 送信先が未対応の場合は画像を送らず、状態にWorker更新待ちを表示します。

設定・未送信キュー・ログは `%LOCALAPPDATA%\LifelogScreenshot`。
資格情報は現在のWindowsユーザーでのみ復号できるDPAPIで保護します。
画面を記録できないロック中・スリープ中の過去分を、復帰後に埋めることはしません。
未送信画像は失敗や再起動でも残し、保存成功の応答を確認してからローカルから削除します。

## Web画面で見る

[lifelog](https://lifelog.mantaroh.org) の一覧で対象の日付を選びます。
PCの記録は「PC · 画面名」として各周期の画面がまとまり、OCR本文は「画面の文字」を開くと読めます。
一覧は時刻の古い順なので、最新の記録は下側です。画面は自動更新しないため、新しい記録を見るには再読み込みしてください。

PCのUTC時刻とR1の日本時間が混在して順序が崩れる問題は、2026-09-16に修正・本番反映済みです。
検索ではキャプションとOCRを対象に探せます。OCRがあれば、AIキャプションの生成待ちでも検索できます。
AIキャプションは、長辺960pxに縮小した画像から作ります（原寸の画像は保存と表示に使います）。1920×1080のままだと1枚約66 Neurons、960×540では約33 Neuronsでした。

## 環境診断・テスト

このフォルダから実行します。

```powershell
./.venv/Scripts/python.exe agent.py --doctor
./.venv/Scripts/python.exe -m unittest -v test_agent
```

`--doctor` は画面構成、日本語OCRの有無、画像寸法上限、設定の有無を表示します。
秘密や画面本文は表示しません。

ローカルだけに1回撮影する場合:

```powershell
./.venv/Scripts/python.exe agent.py --capture-once --data-dir "$env:LOCALAPPDATA/LifelogScreenshot-check"
```

## ローカルで取り込みから検索まで検証

`cloudflare/lifelog` で以下を実行します。テスト用D1/R2/Queueを使い、AIは固定応答です。
本番の資格情報やWorkers AIへの呼び出しは使いません。

```powershell
npx wrangler d1 migrations apply desktop-local-test --local --config wrangler.desktop-test.jsonc
npx wrangler dev --local --config wrangler.desktop-test.jsonc --port 8799
```

別のターミナルでこのフォルダから実行します。

```powershell
./.venv/Scripts/python.exe verify_local.py
# 実際の画面でも検証するときだけ指定。画像はローカルR2に残ります。
./.venv/Scripts/python.exe verify_local.py --real-displays
# 30秒周期（検証用に固定）と停止・再開を約37秒かけて検証（実画面をローカルへ送信）。
./.venv/Scripts/python.exe verify_schedule.py
```

OCR → multipart送信 → D1/R2 → Queue → FTS/LIKE検索 → コンテキスト → MCPを確認します。
初回は合成画像で、同じ画像の2回の撮影が別記録になることも検証します。
テスト用Workerはlocalhost以外からのHTTPリクエストを拒否します。本番デプロイには使いません。

サーバ側テストは `cloudflare/lifelog` で `npm test`。
実SQLiteによるテストのためNode.js 22.13以降が必要です。
型チェックには以前からあるqueueハンドラの型不一致が1件残っています。

## 本番への反映

2026-09-16に本番反映・実AIを含む送信検証まで完了しています。このPCには専用トークンを設定済みです。
以後の更新時は `cloudflare/lifelog` で、未適用一覧を確認してから行います。

```powershell
npx wrangler d1 migrations list r1-lifelog --remote
npx wrangler d1 migrations apply r1-lifelog --remote
npm run deploy
```

0019が先、Workerが後です。0019はOCR列追加と全文索引の再構築を行います。
適用後に `/health` が `desktop_upload: 1` を返すことを確認してから、PCアプリで記録を開始します。
古いWorkerのままでもPCアプリは画像をローカルに保持し、未対応の本番へ画像本体を送信しません。

## 制限

- 本番の受信・AI生成・OCR検索・MCPは合成画像で確認し、確認用画像は削除済みです。運用開始後は実画面の受信と日ビューへの反映も確認しています。
- コンテキストは写真200件、日ビューは古い順に2,000件まで。超過しても保存・索引されますが、最新分が日ビューに出なくなる場合があります。APIの `photos_truncated` は返しますが、Web画面には上限の警告表示がありません。
- 未送信画像を自動廃棄しないため、長期オフラインでは保存先の空き容量を確認してください。
- 日本語OCR未導入・画像が大きすぎる等の失敗は再試行待ちになり、状態画面に表示されます。
