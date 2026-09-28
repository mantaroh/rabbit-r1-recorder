import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import worker, { contextData, searchData } from "../src/index";
import { groupPhotos } from "../src/day-group";
import { captionPromptFor, CAPTION_PROMPT_DESKTOP } from "../src/caption-prompt";
import { jpegSize } from "../src/desktop-photo";

let db: DatabaseSync;
let env: any;
const at = "2026-09-16T01:00:00.000Z";
const photoId = "pc_1234567890abcdef";

function prepare(sql: string) {
  let values: unknown[] = [];
  const execute = (method: "all" | "get" | "run") => {
    const statement = db.prepare(sql);
    return values.length
      ? statement[method](Object.fromEntries(values.map((v, i) => [String(i + 1), v])) as any)
      : statement[method]();
  };
  return {
    bind(...args: unknown[]) { values = args; return this; },
    async all() { return { results: execute("all") }; },
    async first() { return execute("get") ?? null; },
    async run() { return execute("run"); },
  };
}

/** The smallest JPEG header jpegSize has to read: SOI, a baseline SOF0, EOI. */
function jpeg(width: number, height: number, sof = 0xc0) {
  return new Uint8Array([0xff, 0xd8, 0xff, 0xe0, 0x00, 0x04, 0x00, 0x00,
    0xff, sof, 0x00, 0x11, 0x08, height >> 8, height & 0xff, width >> 8, width & 0xff,
    0x03, 1, 0x22, 0, 2, 0x11, 1, 3, 0x11, 1, 0xff, 0xd9]);
}

function request(id = photoId, ocr = "スクリーン文書 特別検索語 駅", metadata: object = {}, caption?: Blob) {
  const form = new FormData();
  form.set("image", new Blob([new Uint8Array([255, 216, 255, 217])], { type: "image/jpeg" }), "screen.jpg");
  form.set("metadata", JSON.stringify({ capture_id: "a".repeat(32), monitor_id: "DISPLAY1", width: 2560, height: 1440, ocr_text: ocr, ...metadata }));
  if (caption) form.set("caption_image", caption, "caption.jpg");
  return new Request(`https://test/v1/photos/${id}?source=desktop&taken_at=${at}&device_id=pc-test`, {
    method: "PUT", headers: { authorization: "Bearer ingest" }, body: form,
  });
}

beforeEach(() => {
  db = new DatabaseSync(":memory:");
  for (const name of readdirSync("migrations").filter(n => n.endsWith(".sql")).sort()) {
    db.exec(readFileSync("migrations/" + name, "utf8"));
  }
  env = {
    INGEST_TOKEN: "ingest", ADMIN_TOKEN: "admin", DESKTOP_TOKEN: "desktop-secret", DB: { prepare },
    BUCKET: { put: vi.fn(async () => ({ size: 4 })), get: vi.fn(async () => ({ arrayBuffer: async () => new ArrayBuffer(4) })) },
    TRANSCRIBE_QUEUE: { send: vi.fn(async () => {}) },
    AI: { run: vi.fn(async () => ({ response: "PC上で文書を編集している。" })) },
  };
});
afterEach(() => db.close());

describe("desktop ingestion through real SQLite migrations", () => {
  it("day view orders UTC desktop frames and offset camera timestamps by the actual instant", async () => {
    await worker.fetch(request(), env); // 01:00 UTC = 10:00 JST
    const cameraAt = "2026-09-16T09:00:00+09:00"; // One hour earlier
    const camera = new Request(`https://test/v1/photos/camera123?taken_at=${encodeURIComponent(cameraAt)}`, {
      method: "PUT", headers: { authorization: "Bearer ingest" }, body: "jpeg",
    });
    expect((await worker.fetch(camera, env)).status).toBe(201);
    const response = await worker.fetch(new Request("https://test/v1/day?date=2026-09-16", {
      headers: { authorization: "Bearer admin" },
    }), env);
    const day: any = await response.json();
    expect(day.entries.map((entry: any) => entry.shots[0].id)).toEqual(["camera123", photoId]);
  });
  it("dedicated desktop credentials can upload screenshots but cannot read or administer the archive", async () => {
    const upload = request();
    upload.headers.set("authorization", "Bearer desktop-secret");
    expect((await worker.fetch(upload, env)).status).toBe(201);
    for (const [method, path] of [["GET", "/v1/search?q=test"], ["POST", "/v1/admin/recaption"],
      ["PUT", `/v1/photos/another123?taken_at=${at}&source=r1`], ["POST", "/mcp"]]) {
      const denied = new Request("https://test" + path, { method, headers: { authorization: "Bearer desktop-secret" } });
      expect((await worker.fetch(denied, env)).status).toBe(403);
    }
  });
  it("stores OCR and indexes it before captioning; both query lengths find it", async () => {
    const response = await worker.fetch(request(), env);
    expect(response.status).toBe(201);
    expect(await response.json()).toMatchObject({ photo_id: photoId, source: "desktop" });
    for (const q of ["特別検索語", "駅"]) {
      const result = await searchData(env, q, 20);
      expect(result.results).toHaveLength(1);
      expect(result.results[0]).toMatchObject({ kind: "photo", source: "desktop", id: photoId });
      expect(result.results[0].text).toContain(q);
    }
    const context = await contextData(env, at, 3600);
    expect(context.text).toBe("");
    expect(context.seen).toContain("特別検索語");
    expect(context.photos[0]).toMatchObject({ monitor_id: "DISPLAY1", ocr_text: "スクリーン文書 特別検索語 駅" });
  });

  it("queue captioning retains OCR and chooses the desktop prompt", async () => {
    await worker.fetch(request(), env);
    const message = env.TRANSCRIBE_QUEUE.send.mock.calls[0][0];
    const ack = vi.fn();
    await worker.queue({ messages: [{ body: message, ack, retry: vi.fn() }] } as any, env);
    expect(ack).toHaveBeenCalled();
    expect(captionPromptFor("desktop")).toBe(CAPTION_PROMPT_DESKTOP);
    expect(env.AI.run.mock.calls[0][1].messages[0].content[0].text).toBe(CAPTION_PROMPT_DESKTOP);
    const result = await searchData(env, "特別検索語", 10);
    expect(result.results[0].text).toContain("PC上で文書");
    expect(result.results[0].text).toContain("特別検索語");
    expect(db.prepare("SELECT COUNT(*) AS n FROM photos_fts").get()).toMatchObject({ n: 1 });
  });

  it("keeps the caption image beside the original and captions from it", async () => {
    const small = new Blob([jpeg(960, 540)], { type: "image/jpeg" });
    expect((await worker.fetch(request(photoId, "文字", {}, small), env)).status).toBe(201);
    const keys = env.BUCKET.put.mock.calls.map((call: any[]) => call[0]);
    expect(keys).toEqual(["photo/2026/09/16/pc_1234567890abcdef.jpg", "photo/2026/09/16/pc_1234567890abcdef.caption.jpg"]);
    expect(db.prepare("SELECT r2_key, caption_r2_key FROM photos").get()).toMatchObject({
      r2_key: keys[0], caption_r2_key: keys[1],
    });
    // The queue still names the original; the consumer reads the row.
    const message = env.TRANSCRIBE_QUEUE.send.mock.calls[0][0];
    expect(message.key).toBe(keys[0]);
    await worker.queue({ messages: [{ body: message, ack: vi.fn(), retry: vi.fn() }] } as any, env);
    expect(env.BUCKET.get.mock.calls.map((call: any[]) => call[0])).toEqual([keys[1]]);
  });

  it("captions from the original when no caption image was sent", async () => {
    await worker.fetch(request(), env);
    expect(env.BUCKET.put).toHaveBeenCalledTimes(1);
    expect(db.prepare("SELECT caption_r2_key FROM photos").get()).toMatchObject({ caption_r2_key: null });
    const message = env.TRANSCRIBE_QUEUE.send.mock.calls[0][0];
    await worker.queue({ messages: [{ body: message, ack: vi.fn(), retry: vi.fn() }] } as any, env);
    expect(env.BUCKET.get.mock.calls.map((call: any[]) => call[0])).toEqual(["photo/2026/09/16/pc_1234567890abcdef.jpg"]);
  });

  it("rejects a caption image that is not a small JPEG before storing anything", async () => {
    for (const bad of [
      new Blob([new Uint8Array([1, 2, 3, 4])], { type: "image/jpeg" }),
      new Blob([jpeg(960, 540)], { type: "image/png" }),
      new Blob([jpeg(1920, 1080)], { type: "image/jpeg" }),
      new Blob([new Uint8Array([0xff, 0xd8, 0xff, 0xd9])], { type: "image/jpeg" }),
    ]) {
      expect((await worker.fetch(request(photoId, "文字", {}, bad), env)).status).toBe(400);
    }
    expect(env.BUCKET.put).not.toHaveBeenCalled();
  });

  it("reads JPEG dimensions from baseline and progressive headers", () => {
    expect(jpegSize(jpeg(960, 540))).toEqual({ width: 960, height: 540 });
    expect(jpegSize(jpeg(540, 960, 0xc2))).toEqual({ width: 540, height: 960 });
    expect(jpegSize(new Uint8Array([0xff, 0xd8, 0xff, 0xd9]))).toBeNull();
    expect(jpegSize(new Uint8Array([0xff, 0xd8, 0xff, 0xc0, 0x00]))).toBeNull();
  });

  it("retains one row on retry and repairs failure after DB commit before enqueue", async () => {
    env.TRANSCRIBE_QUEUE.send.mockRejectedValueOnce(new Error("queue unavailable"));
    await expect(worker.fetch(request(), env)).rejects.toThrow("queue unavailable");
    expect((await worker.fetch(request(), env)).status).toBe(200);
    expect(env.TRANSCRIBE_QUEUE.send).toHaveBeenCalledTimes(2);
    expect(env.BUCKET.put).toHaveBeenCalledTimes(1);
    expect(db.prepare("SELECT COUNT(*) AS n FROM photos").get()).toMatchObject({ n: 1 });
  });

  it("clears stale OCR on empty/null update and deletes the index with the photo", async () => {
    await worker.fetch(request(), env);
    db.exec("UPDATE photos SET ocr_text=''");
    expect((await searchData(env, "特別検索語", 20)).count).toBe(0);
    db.exec("UPDATE photos SET ocr_text='復活した文字'");
    expect((await searchData(env, "復活した", 20)).count).toBe(1);
    db.exec("UPDATE photos SET ocr_text=NULL");
    expect(db.prepare("SELECT COUNT(*) AS n FROM photos_fts").get()).toMatchObject({ n: 0 });
    db.exec("UPDATE photos SET ocr_text='削除する文字'; DELETE FROM photos");
    expect(db.prepare("SELECT COUNT(*) AS n FROM photos_fts").get()).toMatchObject({ n: 0 });
  });

  it("same screen content at different times creates distinct records", async () => {
    await worker.fetch(request(), env);
    await worker.fetch(request("pc_another12345678"), env);
    expect((await searchData(env, "特別検索語", 10)).count).toBe(2);
    expect((await searchData(env, "特別検索語", 1)).count).toBe(1);
  });

  it("rejects malformed metadata and oversized OCR before storing an object", async () => {
    expect((await worker.fetch(request(photoId, "valid", { width: 0 }), env)).status).toBe(400);
    expect((await worker.fetch(request(photoId, "字".repeat(50000)), env)).status).toBe(400);
    expect(env.BUCKET.put).not.toHaveBeenCalled();
  });

  it("preserves legacy image uploads and excludes failed camera captions", async () => {
    const r = new Request(`https://test/v1/photos/legacy123?taken_at=${at}`, {
      method: "PUT", headers: { authorization: "Bearer ingest" }, body: "jpeg",
    });
    expect((await worker.fetch(r, env)).status).toBe(201);
    db.exec("UPDATE photos SET caption='既存のキャプション',status='captioned'");
    expect((await searchData(env, "キャプション", 10)).results[0].source).toBe("r1");
    db.exec("UPDATE photos SET status='failed'");
    expect((await searchData(env, "キャプション", 10)).count).toBe(0);
  });

  it("groups screens by capture rather than collapsing two captures in a minute", () => {
    const base = { taken_at: at, taken_epoch: 1, source: "desktop", source_file: null, caption: "", facing: "front" };
    const groups = groupPhotos([
      { ...base, photo_id: "a", capture_id: "first", monitor_id: "1" },
      { ...base, photo_id: "b", capture_id: "first", monitor_id: "2" },
      { ...base, photo_id: "c", capture_id: "second", monitor_id: "1", taken_epoch: 31 },
    ]);
    expect(groups.map(g => g.shots.length)).toEqual([2, 1]);
  });

  it("migration preserves existing captions in the rebuilt index", () => {
    db.close();
    db = new DatabaseSync(":memory:");
    for (const name of readdirSync("migrations").filter(n => n.endsWith(".sql") && n < "0019").sort()) db.exec(readFileSync("migrations/" + name, "utf8"));
    db.exec(`INSERT INTO photos(photo_id,device_id,r2_key,facing,taken_at,taken_epoch,received_at,caption,status)
      VALUES('oldphoto','r1','p','front','${at}',1,'${at}','移行前のキャプション','captioned')`);
    db.exec(readFileSync("migrations/0019_desktop_screenshots.sql", "utf8"));
    expect(db.prepare("SELECT photo_id FROM photos_fts WHERE photos_fts MATCH 'キャプション'").all()).toEqual([{ photo_id: "oldphoto" }]);
  });
});

describe("day view, a page at a time", () => {
  function segment(id: string, startedAt: string, transcript: string) {
    db.prepare(`INSERT INTO segments(segment_id, device_id, r2_key, started_at, started_epoch, received_at, status, transcript)
      VALUES (?, 'r1', ?, ?, ?, ?, 'transcribed', ?)`)
      .run(id, "audio/" + id, startedAt, Math.floor(Date.parse(startedAt) / 1000), startedAt, transcript);
  }

  async function day(query: string) {
    const response = await worker.fetch(new Request("https://test/v1/day?date=2026-09-16" + query, {
      headers: { authorization: "Bearer admin" },
    }), env);
    expect(response.status).toBe(200);
    return response.json() as Promise<any>;
  }

  beforeEach(async () => {
    await worker.fetch(request(), env); // PC, 10:00 JST, with OCR
    db.exec("UPDATE photos SET caption='PC の説明'");
    const camera = new Request(`https://test/v1/photos/camera123?taken_at=${encodeURIComponent("2026-09-16T09:00:00+09:00")}`, {
      method: "PUT", headers: { authorization: "Bearer ingest" }, body: "jpeg",
    });
    await worker.fetch(camera, env);
    segment("spoken", "2026-09-16T11:00:00+09:00", "こんにちは");
    segment("silent", "2026-09-16T08:00:00+09:00", "");
  });

  it("returns the newest first, with the text of the page's own photos", async () => {
    const d = await day("&page=1&per_page=20");
    expect(d).toMatchObject({ page: 1, per_page: 20, total_entries: 3, total_pages: 1 });
    expect(d.entries.map((e: any) => e.kind === "audio" ? e.id : e.shots[0].id)).toEqual(["spoken", photoId, "camera123"]);
    expect(d.entries[0].text).toBe("こんにちは");
    expect(d.entries[1].shots[0]).toMatchObject({ caption: "PC の説明", has_ocr: true, label: "DISPLAY1" });
    // The screen text is fetched when a picture is opened, not with the page.
    expect(d.entries[1].shots[0]).not.toHaveProperty("ocr_text");
    expect(JSON.stringify(d)).not.toContain("特別検索語");
    expect(d.entries[2].shots[0]).toMatchObject({ id: "camera123", has_ocr: false });
    expect(d.stats).toMatchObject({ segments: 2, with_text: 1, photos: 2, rows_truncated: false });
  });

  it("leaves silent segments out unless asked, so a page is never mostly hidden", async () => {
    expect((await day("&page=1")).total_entries).toBe(3);
    const d = await day("&page=1&quiet=1");
    expect(d.total_entries).toBe(4);
    expect(d.entries.at(-1).id).toBe("silent");
  });

  it("gives only the audio, oldest first, when asked for only=audio", async () => {
    const d = await day("&page=1&only=audio");
    expect(d).toMatchObject({ total_entries: 1, total_pages: 1 });
    expect(d.entries.map((e: any) => e.id)).toEqual(["spoken"]);
    segment("later", "2026-09-16T12:00:00+09:00", "またね");
    const q = await day("&page=1&only=audio&quiet=1");
    expect(q.entries.map((e: any) => e.id)).toEqual(["silent", "spoken", "later"]);
    // The day's totals still describe the whole day.
    expect(q.stats).toMatchObject({ segments: 3, photos: 2 });
  });

  it("keeps the whole-day answer the R1 reads when no page is asked for", async () => {
    const d = await day("");
    expect(d.page).toBeUndefined();
    expect(d.entries.map((e: any) => e.kind === "audio" ? e.id : e.shots[0].id))
      .toEqual(["silent", "camera123", photoId, "spoken"]);
    expect(d.stats).toMatchObject({ segments: 2, with_text: 1, photos: 2, photos_truncated: false });
  });

  it("reaches every photo of a day past the old 2,000 limit, without repeats", async () => {
    const insert = db.prepare(`INSERT INTO photos(photo_id, device_id, r2_key, facing, taken_at, taken_epoch, received_at,
      caption, status, source, capture_id, monitor_id) VALUES (?, 'pc', ?, 'front', ?, ?, ?, ?, 'captioned', 'desktop', ?, 'D1')`);
    const base = Date.parse("2026-09-16T00:00:00+09:00") + 12 * 3600e3; // noon JST onwards
    db.exec("BEGIN");
    for (let i = 0; i < 2050; i++) {
      const takenAt = new Date(base + i * 20_000).toISOString();
      insert.run(`bulk${i}`, `p/bulk${i}`, takenAt, Math.floor((base + i * 20_000) / 1000), takenAt, `c${i}`, i.toString(16).padStart(32, "0"));
    }
    db.exec("COMMIT");

    const first = await day("&page=1&per_page=100");
    expect(first).toMatchObject({ total_entries: 2053, total_pages: 21 });
    expect(first.entries[0].shots[0]).toMatchObject({ id: "bulk2049", caption: "c2049" });

    const seen: string[] = [];
    for (let p = 1; p <= first.total_pages; p++) {
      for (const e of (await day(`&page=${p}&per_page=100`)).entries) seen.push(e.kind === "audio" ? e.id : e.shots[0].id);
    }
    expect(seen).toHaveLength(2053);
    expect(new Set(seen).size).toBe(2053);
    expect(seen.at(-1)).toBe("camera123");
    expect((await day("&page=22&per_page=100")).entries).toEqual([]);
  });

  it("hands one picture's caption and screen text to the web page, and to nobody else", async () => {
    const ask = (id: string, token: string) => worker.fetch(new Request(`https://test/v1/photos/${id}/text`, {
      headers: { authorization: "Bearer " + token },
    }), env);

    const response = await ask(photoId, "admin");
    expect(response.status).toBe(200);
    expect(await response.json()).toEqual({
      photo_id: photoId, caption: "PC の説明", ocr_text: "スクリーン文書 特別検索語 駅",
    });
    expect(await (await ask("camera123", "admin")).json()).toEqual({ photo_id: "camera123", caption: "", ocr_text: "" });

    expect((await ask("nosuchphoto", "admin")).status).toBe(404);
    expect((await ask(photoId, "desktop-secret")).status).toBe(403);
    expect((await ask(photoId, "ingest")).status).toBe(403);
  });

  it("leaves the screen text out of the whole-day answer the R1 downloads every two minutes", async () => {
    const d = await day("");
    const shot = d.entries.find((e: any) => e.kind === "photo" && e.shots[0].id === photoId).shots[0];
    expect(shot).toMatchObject({ caption: "PC の説明", label: "DISPLAY1" });
    expect(shot).not.toHaveProperty("ocr_text");
    expect(JSON.stringify(d)).not.toContain("特別検索語");
    // What the R1's standby screen reads is all still there.
    expect(d.stats).toMatchObject({ segments: 2, with_text: 1, photos: 2 });
    expect(d.entries.at(-1)).toMatchObject({ kind: "audio", text: "こんにちは" });
  });

  it("falls back to 50 a page for a size the page does not offer", async () => {
    expect((await day("&page=1&per_page=5000")).per_page).toBe(50);
    expect((await day("&page=abc")).page).toBe(1);
  });
});
