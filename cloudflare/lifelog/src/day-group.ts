/**
 * How a day's frames are gathered into entries.
 *
 * The R1 takes a front/rear pair at the same instant, so grouping by the
 * minute is how the two halves of one moment find each other.
 *
 * The glasses camera breaks that. Its frames are 30 seconds apart, which puts
 * two of them in the same minute, and the minute rule would present two
 * unrelated views as if they were the two sides of one. They are grouped by
 * the file they were imported from instead: one recording, one entry, however
 * many frames it holds.
 */

export interface PhotoRow {
  photo_id: string;
  taken_at: string;
  taken_epoch: number;
  facing: string;
  caption: string | null;
  source: string;
  source_file: string | null;
  capture_id?: string | null;
  monitor_id?: string | null;
  ocr_text?: string | null;
}

export interface PhotoGroup {
  /** The first shot's timestamp; what the day view sorts and labels by. */
  at: string;
  source: string;
  shots: PhotoRow[];
}

export function groupPhotos(rows: PhotoRow[]): PhotoGroup[] {
  const buckets = new Map<string, PhotoRow[]>();

  for (const row of rows) {
    // A glasses frame with no source_file should not exist — putPhoto refuses
    // it — but if one ever does, falling back to the minute keeps it visible
    // rather than collapsing every stray into a single bucket.
    const key =
      row.source === "desktop"
        ? "desktop:" + (row.capture_id ?? row.photo_id)
        : row.source === "glasses" && row.source_file
        ? "glasses:" + row.source_file
        : row.source + ":" + Math.floor(row.taken_epoch / 60);

    const bucket = buckets.get(key);
    if (bucket) bucket.push(row);
    else buckets.set(key, [row]);
  }

  const groups: PhotoGroup[] = [];
  for (const shots of buckets.values()) {
    shots.sort((a, b) => a.taken_epoch - b.taken_epoch);
    groups.push({ at: shots[0].taken_at, source: shots[0].source, shots });
  }

  groups.sort((a, b) => a.shots[0].taken_epoch - b.shots[0].taken_epoch);
  return groups;
}
