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
