import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { DatabaseSync } from "node:sqlite";
import { readFileSync, readdirSync } from "node:fs";
import worker, { contextData, searchData } from "../src/index";
import { groupPhotos } from "../src/day-group";
import { captionPromptFor, CAPTION_PROMPT_DESKTOP } from "../src/caption-prompt";

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

function request(id = photoId, ocr = "スクリーン文書 特別検索語 駅", metadata: object = {}) {
  const form = new FormData();
  form.set("image", new Blob([new Uint8Array([255, 216, 255, 217])], { type: "image/jpeg" }), "screen.jpg");
  form.set("metadata", JSON.stringify({ capture_id: "a".repeat(32), monitor_id: "DISPLAY1", width: 2560, height: 1440, ocr_text: ocr, ...metadata }));
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
