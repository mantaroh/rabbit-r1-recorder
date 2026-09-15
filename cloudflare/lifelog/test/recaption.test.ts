import { describe, expect, it } from "vitest";
import { recaptionBatches, recaptionCutoff } from "../src/recaption";

const rows = (n: number) =>
  Array.from({ length: n }, (_, i) => ({ photo_id: `p${i}`, r2_key: `photo/2026/09/14/p${i}.jpg` }));

describe("recaptionBatches", () => {
  it("は100件ずつに束ねる", () => {
    const batches = recaptionBatches(rows(250));
    expect(batches.map((b) => b.length)).toEqual([100, 100, 50]);
  });

  it("はちょうど割り切れても空の束を作らない", () => {
    expect(recaptionBatches(rows(200)).map((b) => b.length)).toEqual([100, 100]);
  });

  it("は captionPhoto が読む形のメッセージにする", () => {
    const [[first]] = recaptionBatches(rows(1));
    expect(first).toEqual({
      body: { segmentId: "p0", key: "photo/2026/09/14/p0.jpg", kind: "photo" },
    });
  });

  it("は並び順を保つ", () => {
    // 古い順に選んでいるので、束の中でもその順のまま積む。
    const flat = recaptionBatches(rows(205)).flat();
    expect(flat.map((m) => m.body.segmentId)).toEqual(rows(205).map((r) => r.photo_id));
  });

  it("は空なら何も返さない", () => {
    expect(recaptionBatches([])).toEqual([]);
  });

  it("は束の大きさを指定できる", () => {
    expect(recaptionBatches(rows(7), 3).map((b) => b.length)).toEqual([3, 3, 1]);
  });
});

describe("recaptionCutoff", () => {
  it("は時刻をミリ秒付きの ISO に揃える", () => {
    expect(recaptionCutoff("2026-09-15T08:12:30Z")).toBe("2026-09-15T08:12:30.000Z");
  });

  it("は揃えることで同じ秒の境目を正しく比べられるようにする", () => {
    // caption_at は toISOString() で書かれるのでミリ秒が付く。ミリ秒なしの
    // "…30Z" と文字列で比べると '.' < 'Z' なので "…30.500Z" が小さく出て、
    // 30.5秒に付いたキャプションを「30秒より前」と誤判定する。
    const cutoff = recaptionCutoff("2026-09-15T08:12:30Z")!;
    expect("2026-09-15T08:12:30.500Z" < cutoff).toBe(false);
    expect("2026-09-15T08:12:29.999Z" < cutoff).toBe(true);
    // 揃えないとこうなる、という対照。
    expect("2026-09-15T08:12:30.500Z" < "2026-09-15T08:12:30Z").toBe(true);
  });

  it("はタイムゾーン付きの入力も UTC に揃える", () => {
    expect(recaptionCutoff("2026-09-15T17:12:30+09:00")).toBe("2026-09-15T08:12:30.000Z");
  });

  it("は読めない時刻に null を返す", () => {
    expect(recaptionCutoff("nonsense")).toBeNull();
    expect(recaptionCutoff("")).toBeNull();
    expect(recaptionCutoff(null)).toBeNull();
  });
});
