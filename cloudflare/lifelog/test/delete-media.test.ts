import { describe, expect, it } from "vitest";
import {
  MAX_RANGE_SEC,
  chunk,
  deleteTargets,
  keysOf,
  parseKinds,
  parseRange,
  photoKind,
} from "../src/delete-media";

describe("parseRange", () => {
  it("は JST の時刻を UTC 秒にする", () => {
    expect(parseRange("2026-10-07T10:00:00+09:00", "2026-10-07T10:30:00+09:00")).toEqual({
      fromEpoch: Date.parse("2026-10-07T01:00:00Z") / 1000,
      toEpoch: Date.parse("2026-10-07T01:30:00Z") / 1000,
    });
  });

  it("は1日分（日付の変わり目まで）を許す", () => {
    expect(parseRange("2026-10-07T00:00:00+09:00", "2026-10-08T00:00:00+09:00")).not.toHaveProperty("error");
  });

  it("は24時間を超える指定を断る", () => {
    const from = Date.parse("2026-10-07T00:00:00Z");
    const to = new Date(from + (MAX_RANGE_SEC + 1) * 1000).toISOString();
    expect(parseRange("2026-10-07T00:00:00Z", to)).toHaveProperty("error");
  });

  it("は逆向き・同時刻・読めない時刻を断る", () => {
    expect(parseRange("2026-10-07T10:00:00Z", "2026-10-07T09:00:00Z")).toHaveProperty("error");
    expect(parseRange("2026-10-07T10:00:00Z", "2026-10-07T10:00:00Z")).toHaveProperty("error");
    expect(parseRange("nonsense", "2026-10-07T10:00:00Z")).toHaveProperty("error");
    expect(parseRange(null, null)).toHaveProperty("error");
  });
});

describe("parseKinds", () => {
  it("は知っている種類だけを重複なく返す", () => {
    expect(parseKinds("audio,camera,audio,unknown")).toEqual(["audio", "camera"]);
  });

  it("は何も選ばれていなければ null", () => {
    expect(parseKinds("")).toBeNull();
    expect(parseKinds(null)).toBeNull();
    expect(parseKinds("photo")).toBeNull();
  });
});

describe("photoKind", () => {
  it("は PC だけを desktop にし、R1・眼鏡・古い NULL はカメラにする", () => {
    expect(photoKind("desktop")).toBe("desktop");
    expect(photoKind("r1")).toBe("camera");
    expect(photoKind("glasses")).toBe("camera");
    expect(photoKind(null)).toBe("camera");
  });
});

describe("keysOf", () => {
  it("はキャプション用の縮小版も消す対象に入れる", () => {
    expect(keysOf({
      photos: [
        { photo_id: "p1", r2_key: "photo/a.jpg", caption_r2_key: "photo/a.caption.jpg", source: "desktop" },
        { photo_id: "p2", r2_key: "photo/b.jpg", caption_r2_key: null, source: "r1" },
      ],
      segments: [{ segment_id: "s1", r2_key: "audio/c.wav" }],
    })).toEqual(["photo/a.jpg", "photo/a.caption.jpg", "photo/b.jpg", "audio/c.wav"]);
  });
});

describe("chunk", () => {
  it("は端数も1つの束にする", () => {
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]]);
    expect(chunk([], 2)).toEqual([]);
  });
});

/** 呼ばれた順番だけを記録する偽の DB と R2。 */
function fakeEnv() {
  const log: string[] = [];
  const statement = (sql: string) => ({
    sql,
    args: [] as unknown[],
    bind(...args: unknown[]) { return { ...this, args }; },
  });
  const env = {
    DB: {
      prepare: (sql: string) => statement(sql),
      batch: async (stmts: { sql: string; args: unknown[] }[]) => {
        for (const s of stmts) log.push("d1: " + s.sql.split(/\s+/).slice(0, 3).join(" ") + " " + JSON.stringify(s.args));
        return [];
      },
    },
    BUCKET: {
      delete: async (keys: string[]) => { log.push("r2: " + keys.join(",")); },
    },
  };
  return { env: env as any, log };
}

describe("deleteTargets", () => {
  it("は R2 を先に消してから D1 の行を消し、ID を削除済みに残す", async () => {
    const { env, log } = fakeEnv();
    const result = await deleteTargets(env, {
      photos: [{ photo_id: "p1", r2_key: "photo/a.jpg", caption_r2_key: null, source: "desktop" }],
      segments: [{ segment_id: "s1", r2_key: "audio/c.wav" }],
    }, new Date("2026-10-07T00:00:00Z"));

    expect(result).toEqual({ photos: 1, segments: 1 });
    expect(log[0]).toBe("r2: photo/a.jpg,audio/c.wav");
    expect(log.slice(1)).toEqual([
      'd1: DELETE FROM photos ["p1"]',
      'd1: DELETE FROM segments ["s1"]',
      'd1: INSERT OR IGNORE ["p1","desktop","2026-10-07T00:00:00.000Z"]',
      'd1: INSERT OR IGNORE ["s1","audio","2026-10-07T00:00:00.000Z"]',
    ]);
  });

  it("は対象が無ければ何も呼ばない", async () => {
    const { env, log } = fakeEnv();
    expect(await deleteTargets(env, { photos: [], segments: [] })).toEqual({ photos: 0, segments: 0 });
    expect(log).toEqual([]);
  });
});
