-- The subscription list, and what has appeared in it.
--
-- Seeded from an OPML export and then owned here: the file is a starting
-- point, not the source of truth, because the crawler learns things the file
-- cannot carry — which feeds have moved, which have been dead for a year, when
-- each was last successfully read.

CREATE TABLE IF NOT EXISTS feeds (
  -- The feed's own URL, which is the only identifier a feed reliably has.
  xml_url          TEXT PRIMARY KEY,

  title            TEXT NOT NULL,
  html_url         TEXT,

  -- OPML nests feeds under one level of folders; that folder is the category.
  category         TEXT,

  -- Conditional-request state. Sending these back is the difference between
  -- asking 57 sites for their whole feed every hour and asking them whether
  -- anything changed.
  etag             TEXT,
  last_modified    TEXT,

  last_fetch_at    TEXT,
  last_success_at  TEXT,

  -- HTTP status, or 0 when the request never got one.
  last_status      INTEGER,
  last_error       TEXT,

  -- Drives the backoff. This OPML is old enough that some of these hosts no
  -- longer exist, and a dead feed should cost one request a day rather than
  -- one an hour forever.
  failures         INTEGER NOT NULL DEFAULT 0,

  added_at         TEXT NOT NULL
);

CREATE TABLE IF NOT EXISTS items (
  -- Feed URL plus the item's own id. Feeds reuse guids across feeds far more
  -- often than they collide within one, so the pair is what is unique.
  item_id          TEXT PRIMARY KEY,
  xml_url          TEXT NOT NULL,

  title            TEXT NOT NULL,
  link             TEXT,

  -- What the feed claims. Frequently absent, occasionally in the future, and
  -- in one memorable class of feed identical for every item.
  published_at     TEXT,
  published_epoch  INTEGER,

  -- When this crawler first saw it. This is the honest ordering for "what is
  -- new", because it is the one field a publisher cannot get wrong, and it is
  -- what the device's standby screen sorts by.
  first_seen_epoch INTEGER NOT NULL
);

CREATE INDEX IF NOT EXISTS items_first_seen ON items(first_seen_epoch DESC);
CREATE INDEX IF NOT EXISTS items_published ON items(published_epoch DESC);
CREATE INDEX IF NOT EXISTS items_feed ON items(xml_url);
