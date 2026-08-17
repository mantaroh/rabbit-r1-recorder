-- Where the device was, every few minutes.
--
-- Positions are not like segments and photographs: there is no object in R2
-- behind them, the row is the whole artefact. So the durability contract is
-- different too — the device may resend a batch freely and the primary key
-- absorbs it, rather than a status column tracking a separate upload.
--
-- (device_id, recorded_epoch) as the key rather than a synthetic id, because
-- a fix is identified by when and by whom it was taken. A device cannot be in
-- two places in the same second, so a repeat is by definition the same fix
-- arriving twice.
CREATE TABLE IF NOT EXISTS positions (
  device_id       TEXT    NOT NULL,
  recorded_at     TEXT    NOT NULL,
  recorded_epoch  INTEGER NOT NULL,

  lat             REAL    NOT NULL,
  lon             REAL    NOT NULL,

  -- Metres of horizontal error the provider claims. Kept because a track is
  -- unreadable without it: a 2000 m fix and a 5 m fix drawn the same way turn
  -- a walk down a street into a walk through the buildings beside it.
  accuracy_m      REAL,
  altitude_m      REAL,
  speed_mps       REAL,
  bearing_deg     REAL,

  -- Which provider answered. This device has no network location — no Play
  -- Services, and dumpsys lists only passive, fused and gps — so in practice
  -- this is 'gps' or 'fused', and a run of 'passive' means the fix came from
  -- somebody else's request and may be old.
  provider        TEXT,

  received_at     TEXT    NOT NULL,

  PRIMARY KEY (device_id, recorded_epoch)
);

CREATE INDEX IF NOT EXISTS positions_epoch ON positions(recorded_epoch);
