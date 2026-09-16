import { describe, expect, it } from "vitest";
import { dayEntries, pageOf, perPageOf, segmentText, type SegmentHead } from "../src/day-page";
import type { PhotoRow } from "../src/day-group";

const segment = (id: string, at: string, transcript: string | null = "話した", stock = 0): SegmentHead => ({
  segment_id: id, started_at: at, status: "transcribed", voiced_ratio: 0.5,
  transcript, stock_phrase: stock,
});

const photo = (id: string, at: string, extra: Partial<PhotoRow> = {}): PhotoRow => ({
  photo_id: id, taken_at: at, taken_epoch: Math.floor(Date.parse(at) / 1000),
  facing: "front", caption: null, source: "r1", source_file: null, ...extra,
});

describe("segmentText", () => {
  it("は定型句と空白だけの文字起こしを空として扱う", () => {
    expect(segmentText(segment("a", "2026-09-16T10:00:00+09:00", " こんにちは "))).toBe("こんにちは");
    expect(segmentText(segment("a", "2026-09-16T10:00:00+09:00", "ご視聴ありがとうございました", 1))).toBe("");
    expect(segmentText(segment("a", "2026-09-16T10:00:00+09:00", "　\n"))).toBe("");
    expect(segmentText(segment("a", "2026-09-16T10:00:00+09:00", null))).toBe("");
  });
});

describe("dayEntries", () => {
  it("は音声と写真を新しい順に並べる", () => {
    const entries = dayEntries(
      [segment("s1", "2026-09-16T09:00:00+09:00"), segment("s2", "2026-09-16T11:00:00+09:00")],
      [photo("p1", "2026-09-16T01:00:00.000Z")], // 10:00 JST
      false,
    );
    expect(entries.map((e) => e.kind === "audio" ? e.segment.segment_id : e.group.shots[0].photo_id))
      .toEqual(["s2", "p1", "s1"]);
  });

  it("は同じ秒の中でもミリ秒の順を守る", () => {
    const entries = dayEntries([], [
      photo("early", "2026-09-16T01:00:00.100Z", { source: "desktop", capture_id: "a" }),
      photo("late", "2026-09-16T01:00:00.900Z", { source: "desktop", capture_id: "b" }),
    ], false);
    expect(entries.map((e) => e.kind === "photo" && e.group.shots[0].photo_id)).toEqual(["late", "early"]);
  });

  it("は無音と定型句の音声を quiet なしでは除く", () => {
    const segments = [
      segment("voiced", "2026-09-16T09:00:00+09:00"),
      segment("silent", "2026-09-16T09:01:00+09:00", ""),
      segment("stock", "2026-09-16T09:02:00+09:00", "ご視聴", 1),
    ];
    expect(dayEntries(segments, [], false)).toHaveLength(1);
    expect(dayEntries(segments, [], true)).toHaveLength(3);
  });

  it("は PC の1回の撮影を1件にまとめ、写真は quiet に関係なく残す", () => {
    const at = "2026-09-16T01:00:00.000Z";
    const entries = dayEntries([], [
      photo("left", at, { source: "desktop", capture_id: "c1", monitor_id: "1" }),
      photo("right", at, { source: "desktop", capture_id: "c1", monitor_id: "2" }),
    ], false);
    expect(entries).toHaveLength(1);
    expect(entries[0].kind === "photo" && entries[0].group.shots.map((s) => s.photo_id)).toEqual(["left", "right"]);
  });
});

describe("pageOf", () => {
  const items = Array.from({ length: 105 }, (_, i) => i);

  it("は指定のページを切り出し、総数を返す", () => {
    expect(pageOf(items, 1, 50)).toMatchObject({ page: 1, perPage: 50, totalEntries: 105, totalPages: 3 });
    expect(pageOf(items, 1, 50).items).toEqual(items.slice(0, 50));
    expect(pageOf(items, 3, 50).items).toEqual([100, 101, 102, 103, 104]);
  });

  it("はページの境目で抜けも重複も作らない", () => {
    const pages = [1, 2, 3].flatMap((p) => pageOf(items, p, 50).items);
    expect(pages).toEqual(items);
  });

  it("は範囲外のページで空を返し、総数は保つ", () => {
    expect(pageOf(items, 4, 50)).toMatchObject({ page: 4, totalPages: 3, items: [] });
  });

  it("は 1 未満や数字でないページを 1 ページ目にする", () => {
    expect(pageOf(items, 0, 50).page).toBe(1);
    expect(pageOf(items, -3, 50).page).toBe(1);
    expect(pageOf(items, Number.NaN, 50).page).toBe(1);
  });

  it("は空の日を 0 ページとして返す", () => {
    expect(pageOf([], 1, 50)).toMatchObject({ page: 1, totalEntries: 0, totalPages: 0, items: [] });
  });
});

describe("perPageOf", () => {
  it("は 20 / 50 / 100 だけを受け付け、それ以外は 50 にする", () => {
    expect(perPageOf(20)).toBe(20);
    expect(perPageOf(100)).toBe(100);
    expect(perPageOf(null)).toBe(50);
    expect(perPageOf(30)).toBe(50);
    expect(perPageOf(100000)).toBe(50);
  });
});
