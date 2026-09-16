/**
 * One page of a day, newest first.
 *
 * A day used to come back whole. With the PC recording both screens every
 * minute on top of the R1, that is thousands of pictures, and the page drew
 * every one of them. Now the day is laid out in full from the cheap columns --
 * ids, times, where a picture came from -- and only the page being shown is
 * filled in with captions, transcripts and OCR.
 *
 * The layout has to be done in full, not with LIMIT/OFFSET, because an entry
 * is not a row: a PC capture is two rows and a glasses import is hundreds, and
 * a page boundary must never cut one in half.
 */
import { groupPhotos, type PhotoGroup, type PhotoRow } from "./day-group";

export const PAGE_SIZES = [20, 50, 100];
export const DEFAULT_PAGE_SIZE = 50;

export interface SegmentHead {
  segment_id: string;
  started_at: string;
  status: string;
  voiced_ratio: number | null;
  transcript: string | null;
  /** 0 or 1: whether the transcript is one of Whisper's stock phrases. */
  stock_phrase: number | null;
}

export type EntryHead =
  | { kind: "audio"; at: string; segment: SegmentHead }
  | { kind: "photo"; at: string; group: PhotoGroup };

/**
 * What the day view shows as a segment's words.
 *
 * A stock phrase is Whisper talking to itself; the recording stays, the text
 * does not get shown.
 */
export function segmentText(row: SegmentHead): string {
  return row.stock_phrase ? "" : String(row.transcript ?? "").trim();
}

/**
 * Every entry of the day, newest first.
 *
 * Silent segments are left out unless `quiet` is set. The page used to hide
 * them after the fact, which is fine for a whole day and wrong for a page of
 * one: fifty entries with forty hidden would show ten.
 */
export function dayEntries(segments: SegmentHead[], photos: PhotoRow[], quiet: boolean): EntryHead[] {
  const entries: EntryHead[] = [];
  for (const segment of segments) {
    if (!quiet && !segmentText(segment)) continue;
    entries.push({ kind: "audio", at: segment.started_at, segment });
  }
  for (const group of groupPhotos(photos)) {
    entries.push({ kind: "photo", at: group.at, group });
  }
  // Parsed, not compared as text: the R1 stamps +09:00 and the PC stamps Z,
  // and the PC's milliseconds are what order two captures in one second.
  // Array.prototype.sort is stable, so equal instants keep their input order.
  entries.sort((a, b) => Date.parse(b.at) - Date.parse(a.at));
  return entries;
}

export function perPageOf(raw: number | null): number {
  return raw !== null && PAGE_SIZES.includes(raw) ? raw : DEFAULT_PAGE_SIZE;
}

/** A page past the end comes back empty with the totals, so the caller can step back. */
export function pageOf<T>(items: T[], rawPage: number, perPage: number) {
  const page = Number.isFinite(rawPage) && rawPage >= 1 ? Math.trunc(rawPage) : 1;
  const start = (page - 1) * perPage;
  return {
    page,
    perPage,
    totalEntries: items.length,
    totalPages: Math.ceil(items.length / perPage),
    items: items.slice(start, start + perPage),
  };
}
