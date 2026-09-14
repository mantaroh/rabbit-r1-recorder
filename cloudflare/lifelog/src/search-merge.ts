/**
 * One hit, whichever kind of recording it came out of.
 *
 * Audio and photographs answer the same question — "when did I come across
 * this" — so they arrive in one list rather than two. The caller needs to know
 * which it is holding to render it, and, for a photograph, which camera took
 * it: a hit from the R1 is the room the user was in, a hit from the glasses is
 * what they were looking at, and those are not the same claim.
 */
export interface SearchHit {
  kind: "audio" | "photo";
  id: string;
  at: string;
  text: string;
  /** `r1` | `glasses` for photographs; null for audio, which has no camera. */
  source: string | null;
}

/**
 * Newest first, both kinds in one list, cut at `limit`.
 *
 * No quota per kind. How much of a given hour was talking and how much was
 * looking is the search term's business, not this function's — reserving half
 * the page for audio would hide a day spent reading.
 */
export function mergeResults(
  audio: SearchHit[],
  photos: SearchHit[],
  limit: number,
): SearchHit[] {
  if (!Number.isFinite(limit) || limit <= 0) return [];
  return [...audio, ...photos]
    .sort((a, b) => Date.parse(b.at) - Date.parse(a.at))
    .slice(0, limit);
}
