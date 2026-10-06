-- 削除した記録の ID と時間帯。中身は残さない。
--
-- R1 と PC は送れたか分からないときに同じファイルを再送し、眼鏡カメラの
-- 取り込みは同じ動画から同じ ID を作る。何も残さないと、消したものが
-- 再送で戻ってくる。時間帯も残すのは、R1 がオフラインで溜めていた録音が
-- 消したあとに届く場合があるから。ID はまだ知らないので時刻で断る。
CREATE TABLE IF NOT EXISTS deleted_media (
  id          TEXT PRIMARY KEY,
  -- 'desktop' | 'camera' | 'audio'
  kind        TEXT NOT NULL,
  deleted_at  TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS deleted_ranges (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  kind        TEXT NOT NULL,
  -- [from_epoch, to_epoch) の UTC 秒
  from_epoch  INTEGER NOT NULL,
  to_epoch    INTEGER NOT NULL,
  deleted_at  TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS deleted_ranges_kind ON deleted_ranges(kind, from_epoch);
