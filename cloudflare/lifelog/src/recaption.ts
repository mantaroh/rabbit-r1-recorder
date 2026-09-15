/**
 * Putting photographs captioned by an earlier model back through the current one.
 *
 * The captions do not get rewritten here. Each photograph is only queued, and
 * the ordinary captionPhoto consumer re-captions it and the photos_fts trigger
 * refreshes the index. The old caption stays in place until the new one lands,
 * so a photograph never drops out of search or the day view while it waits.
 */

export interface RecaptionRow {
  photo_id: string;
  r2_key: string;
}

export interface RecaptionMessage {
  body: { segmentId: string; key: string; kind: "photo" };
}

/**
 * Queue messages in batches for sendBatch.
 *
 * sendBatch takes up to 100 messages at a time. captionBackfill sends one at a
 * time, which is up to 500 outbound calls in a single request; batching makes
 * that five, well clear of the per-request subrequest limit.
 */
export function recaptionBatches(rows: RecaptionRow[], size = 100): RecaptionMessage[][] {
  const out: RecaptionMessage[][] = [];
  for (let i = 0; i < rows.length; i += size) {
    out.push(
      rows.slice(i, i + size).map((r) => ({
        body: { segmentId: r.photo_id, key: r.r2_key, kind: "photo" as const },
      })),
    );
  }
  return out;
}

/**
 * The cutoff as the query will compare it, or null if it is not a time.
 *
 * caption_at is stored as text and compared as text. It is written by
 * toISOString(), so it always carries milliseconds -- "08:12:30.500Z". A
 * cutoff typed as "08:12:30Z" compares wrong at the boundary, because '.' sorts
 * before 'Z': a caption written half a second after the cutoff would read as
 * earlier than it and be captioned again. Normalising through toISOString()
 * puts both sides in the same shape.
 */
export function recaptionCutoff(raw: string | null | undefined): string | null {
  if (!raw) return null;
  const ms = Date.parse(raw);
  return Number.isNaN(ms) ? null : new Date(ms).toISOString();
}
