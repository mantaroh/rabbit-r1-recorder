-- What an entry looks like, as well as what it says.
--
-- A headline on its own does not get read on a 240 dp panel, and the feeds
-- already carry both of these; the crawler was simply throwing them away.

ALTER TABLE items ADD COLUMN summary TEXT;

-- The Japanese of the above, filled in after the fact for entries that arrive
-- in another language. Kept apart from summary rather than replacing it: a
-- translation is a guess about a text, and losing the text to store the guess
-- is not a trade worth making.
ALTER TABLE items ADD COLUMN summary_ja TEXT;

ALTER TABLE items ADD COLUMN image_url TEXT;
