-- Every time the device asked whether it may speak first, and what came of it.
--
-- One row per decision that was worth keeping, not one per poll: the device
-- asks every two minutes and almost every answer is "nothing is due", which is
-- not a fact about the day. What is recorded is a slot that came due — whether
-- it was spoken, deferred because the room was busy, or refused because nobody
-- was in front of the camera.
--
-- The table exists because three numbers in this feature are guesses. The slot
-- times are a guess about a working day. The presence check has never been run
-- against this lens. And whether being asked at all is welcome is not something
-- a design document can answer. All three are settled by reading this back
-- after a fortnight, the same way the shake threshold and the VAD floor were.
CREATE TABLE IF NOT EXISTS nudge_log (
  id             INTEGER PRIMARY KEY AUTOINCREMENT,

  -- 'morning', 'midday', 'evening', or 'idle'.
  slot           TEXT    NOT NULL,

  decided_at     TEXT    NOT NULL,
  decided_epoch  INTEGER NOT NULL,

  -- 'minted'   — a credential was issued and the device went on to speak
  -- 'deferred' — due, but the room was busy; it will be tried again
  -- 'refused'  — due, and dropped: nobody there, or out of deferrals
  verdict        TEXT    NOT NULL,

  -- Which rule decided it, in the same words the device is told: quiet_hours,
  -- in_conversation, no_one_there, daily_cap, and so on. The verdict says what
  -- happened; this says why, and it is the column the fortnight is read from.
  why            TEXT,

  -- The frame the presence check was made from, and the line the model gave
  -- for its answer. Kept so the check can be judged against the pictures it
  -- was actually looking at rather than against a memory of them — the photo
  -- itself is in R2 under the ordinary photo path, because it was uploaded
  -- through the ordinary photo route.
  photo_id       TEXT,
  presence_note  TEXT,
  presence_yes   INTEGER,
  neurons        REAL,

  -- Filled in afterwards by the device, and null until then: whether anybody
  -- actually said anything back, and how long the exchange lasted. A nudge
  -- that nobody answers costs the same as one that works and is worth less,
  -- so the idle ones stop for the day after two of them in a row.
  answered       INTEGER,
  session_secs   INTEGER
);

CREATE INDEX IF NOT EXISTS nudge_log_epoch ON nudge_log(decided_epoch);
CREATE INDEX IF NOT EXISTS nudge_log_slot ON nudge_log(slot, decided_epoch);
