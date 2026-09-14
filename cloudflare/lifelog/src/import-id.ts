/**
 * A frame's id, derived from the file it came out of.
 *
 * Deterministic, because that is the whole of the deduplication: putPhoto
 * answers `duplicate: true` for an id it already holds, so re-importing the
 * same .mov costs a few requests and changes nothing.
 *
 * The file's name is deliberately not part of this. The camera reuses
 * MOVI0006.mov every time the card is cleared, so two unrelated recordings
 * share a name — only the content distinguishes them.
 *
 * The frame number is zero-padded so that ordering by photo_id is ordering by
 * frame. /v1/admin/photo-retime relies on that to re-date a whole import in
 * sequence.
 */
export function photoIdFor(fileHash: string, index: number): string {
  if (!/^[0-9a-f]{16}$/.test(fileHash)) {
    throw new Error("fileHash must be 16 lowercase hex characters");
  }
  if (!Number.isInteger(index) || index < 0 || index > 9999) {
    throw new Error("index must be an integer in 0..9999");
  }
  return "g" + fileHash + "-" + String(index).padStart(4, "0");
}
