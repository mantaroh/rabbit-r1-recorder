/**
 * Where each frame of one import belongs on the clock.
 *
 * The frame's number comes out of its id, never out of its position in the
 * list. A failed upload leaves a gap — the design's failure table says so and
 * tells you to re-run — and numbering by position would date every frame after
 * that gap one interval early. This is the code that exists to correct a
 * mistake in the timestamps; it has no business introducing a subtler one.
 */
export interface RetimeTarget {
  photoId: string;
  takenAt: string;
  takenEpoch: number;
}

export function retimeTargets(
  photoIds: string[],
  startMs: number,
  intervalSec: number,
): RetimeTarget[] {
  if (!Number.isFinite(startMs)) {
    throw new Error("startMs must be a finite number");
  }
  if (!Number.isFinite(intervalSec) || intervalSec <= 0) {
    throw new Error("intervalSec must be a positive number");
  }

  return photoIds.map((photoId) => {
    const match = /-(\d{4})$/.exec(photoId);
    if (!match) {
      throw new Error("frame number not readable from photo_id: " + photoId);
    }
    const at = new Date(startMs + Number(match[1]) * intervalSec * 1000);
    return {
      photoId,
      takenAt: at.toISOString(),
      takenEpoch: Math.floor(at.getTime() / 1000),
    };
  });
}
