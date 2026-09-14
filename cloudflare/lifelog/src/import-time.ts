/**
 * When each frame was actually taken.
 *
 * The browser cannot read a file's creation time. File exposes only
 * lastModified, which the camera sets when it finishes writing — the END of
 * the recording. So the start is derived rather than read: walk back over the
 * span the frames cover.
 *
 * The MOV's own creation_time does hold the true start, but on this camera it
 * is offset by exactly -8 hours (it writes JST to the filesystem and converts
 * the container's timestamp as if the clock were UTC+8). Correcting for that
 * would bake in a constant that stops being true the day the camera's timezone
 * is fixed, and would do it silently. Measured on MOVI0006, the arithmetic
 * below lands 10 seconds late — inside a 30-second interval, and visible in
 * the import screen where it can be corrected by hand.
 *
 * Written so that Function.prototype.toString() gives valid standalone JS:
 * it must not reference anything outside itself, because import.ts embeds it
 * in the page that way rather than keeping a second copy.
 */
export function frameTimes(
  lastModifiedMs: number,
  count: number,
  intervalSec: number,
): string[] {
  if (!Number.isFinite(lastModifiedMs)) {
    throw new Error("lastModifiedMs must be a finite number");
  }
  if (!Number.isInteger(count) || count < 1) {
    throw new Error("count must be a positive integer");
  }
  if (!Number.isFinite(intervalSec) || intervalSec <= 0) {
    throw new Error("intervalSec must be a positive number");
  }

  const stepMs = intervalSec * 1000;
  const startMs = lastModifiedMs - (count - 1) * stepMs;
  const out: string[] = [];
  for (let i = 0; i < count; i++) {
    out.push(new Date(startMs + i * stepMs).toISOString());
  }
  return out;
}
