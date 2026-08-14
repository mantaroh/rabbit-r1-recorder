-- Channel layout of the stored audio.
--
-- Sample rate was already recorded; the channel count was not, because until
-- now there was only ever one. A file whose layout is unknown is a file a
-- future decoder has to guess at, and guessing wrong on an interleaved stream
-- swaps the channels for its entire length.
--
-- NULL means mono, which is what everything uploaded before this was.
ALTER TABLE segments ADD COLUMN channels INTEGER;
