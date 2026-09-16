/** Desktop uploads carry OCR separately from the vision model's description. */
export const MAX_DESKTOP_BODY = 20 * 1024 * 1024;
export const MAX_OCR_BYTES = 128 * 1024;

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
    width: m.width as number, height: m.height as number, ocrText: m.ocr_text as string };
}

export function photoText(row: { caption?: unknown; ocr_text?: unknown }): string {
  return [row.caption, row.ocr_text].filter(v => typeof v === "string" && v.trim()).join("\n");
}
