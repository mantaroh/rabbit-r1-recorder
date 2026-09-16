/** Desktop uploads carry OCR separately from the vision model's description. */
export const MAX_DESKTOP_BODY = 20 * 1024 * 1024;
export const MAX_OCR_BYTES = 128 * 1024;

/**
 * The copy the vision model reads.
 *
 * Neurons follow the image, not the answer: measured on five desktop frames,
 * 1920x1080 cost 65.9 and 960x540 cost 32.5. The PC sends 960 on the long
 * edge. 1280 is the ceiling here so a larger copy is refused rather than paid
 * for without anyone noticing.
 */
export const MAX_CAPTION_EDGE = 1280;
export const MAX_CAPTION_BYTES = 2 * 1024 * 1024;

/**
 * Width and height from a JPEG's frame header, or null if there is none.
 *
 * Only the markers are walked; nothing is decoded. Enough to tell a 960x540
 * copy from a full-size one without an image library in the Worker.
 */
export function jpegSize(bytes: Uint8Array): { width: number; height: number } | null {
  if (bytes.length < 4 || bytes[0] !== 0xff || bytes[1] !== 0xd8) return null;
  let p = 2;
  while (p + 3 < bytes.length) {
    if (bytes[p] !== 0xff) return null;
    const marker = bytes[p + 1];
    if (marker === 0xff) { p += 1; continue; }
    if (marker === 0xd9 || marker === 0xda) return null;
    if (marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) { p += 2; continue; }
    const length = (bytes[p + 2] << 8) | bytes[p + 3];
    if (length < 2) return null;
    // SOF0-SOF15, except DHT (C4), JPG (C8) and DAC (CC), which share the range.
    if (marker >= 0xc0 && marker <= 0xcf && marker !== 0xc4 && marker !== 0xc8 && marker !== 0xcc) {
      if (p + 8 >= bytes.length) return null;
      return { height: (bytes[p + 5] << 8) | bytes[p + 6], width: (bytes[p + 7] << 8) | bytes[p + 8] };
    }
    p += 2 + length;
  }
  return null;
}

export async function readDesktopPhoto(request: Request) {
  if (!request.headers.get("content-type")?.startsWith("multipart/form-data")) {
    throw new Error("desktop upload must be multipart/form-data");
  }
  const declared = Number(request.headers.get("content-length"));
  if (declared > MAX_DESKTOP_BODY) throw new Error("desktop upload too large");
  // Bound chunked requests as well; formData alone would read an unlimited body.
  const reader = request.body?.getReader();
  if (!reader) throw new Error("empty body");
  const chunks: Uint8Array[] = [];
  let size = 0;
  try {
    for (;;) {
      const { done, value } = await reader.read();
      if (done) break;
      size += value.byteLength;
      if (size > MAX_DESKTOP_BODY) {
        await reader.cancel();
        throw new Error("desktop upload too large");
      }
      chunks.push(value);
    }
  } finally {
    reader.releaseLock();
  }
  const body = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) { body.set(chunk, offset); offset += chunk.length; }
  const form = await new Response(body, { headers: { "content-type": request.headers.get("content-type")! } }).formData();
  const image = form.get("image");
  if (!image || typeof image === "string" || image.type !== "image/jpeg" || image.size < 4) {
    throw new Error("image must be a JPEG file");
  }
  const bytes = new Uint8Array(await image.arrayBuffer());
  if (bytes[0] !== 0xff || bytes[1] !== 0xd8 || bytes[2] !== 0xff) throw new Error("invalid JPEG");
  const captionBytes = await readCaptionImage(form.get("caption_image"));
  const raw = form.get("metadata");
  if (typeof raw !== "string") throw new Error("metadata must be JSON text");
  const m = JSON.parse(raw);
  if (!m || typeof m !== "object" || typeof m.capture_id !== "string" || !/^[a-f0-9]{32}$/.test(m.capture_id)) {
    throw new Error("capture_id must be 32 lowercase hex characters");
  }
  if (typeof m.monitor_id !== "string" || !m.monitor_id.length || m.monitor_id.length > 80) throw new Error("invalid monitor_id");
  if (![m.width, m.height].every(n => Number.isInteger(n) && n > 0 && n <= 16384)) throw new Error("invalid dimensions");
  if (typeof m.ocr_text !== "string" || new TextEncoder().encode(m.ocr_text).length > MAX_OCR_BYTES) throw new Error("OCR text too large or missing");
  return { bytes, captureId: m.capture_id as string, monitorId: m.monitor_id as string,
    width: m.width as number, height: m.height as number, ocrText: m.ocr_text as string,
    captionBytes };
}

/** Optional: a PC from before the caption copy sends only the original. */
async function readCaptionImage(part: File | string | null): Promise<Uint8Array | null> {
  if (part === null) return null;
  if (typeof part === "string" || part.type !== "image/jpeg" || part.size > MAX_CAPTION_BYTES) {
    throw new Error("caption_image must be a JPEG file of at most 2 MiB");
  }
  const bytes = new Uint8Array(await part.arrayBuffer());
  const size = jpegSize(bytes);
  if (!size || size.width < 1 || size.height < 1) throw new Error("caption_image is not a readable JPEG");
  if (Math.max(size.width, size.height) > MAX_CAPTION_EDGE) {
    throw new Error("caption_image must be at most 1280 px on the long edge");
  }
  return bytes;
}

export function photoText(row: { caption?: unknown; ocr_text?: unknown }): string {
  return [row.caption, row.ocr_text].filter(v => typeof v === "string" && v.trim()).join("\n");
}
