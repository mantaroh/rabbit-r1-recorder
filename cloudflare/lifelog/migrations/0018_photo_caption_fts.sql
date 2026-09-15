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

-- 二度目の適用に耐えるようにする。テーブルもトリガも IF NOT EXISTS で守って
-- あるのに、この INSERT だけ無条件だった。本番の 4,300 行を1文で入れる適用が
-- 途中で落ちて migration 記録が残らなかった場合、再実行で全キャプションが
-- 二重に索引され、検索結果が二重に出る。空の状態から作り直すほうが安い。
DELETE FROM photos_fts;

-- 溜まっているぶんを全部入れる。この計画の主目的のひとつ。
INSERT INTO photos_fts(caption, photo_id, taken_at)
SELECT caption, photo_id, taken_at
  FROM photos
 WHERE caption IS NOT NULL AND caption <> '';
