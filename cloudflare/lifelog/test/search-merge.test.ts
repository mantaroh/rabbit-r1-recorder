import { describe, expect, it } from "vitest";
import { mergeResults, type SearchHit } from "../src/search-merge";

const audio = (at: string, id = "seg-" + at): SearchHit => ({
  kind: "audio", id, at, text: "音声 " + at, source: null,
});
const photo = (at: string, id = "pic-" + at): SearchHit => ({
  kind: "photo", id, at, text: "写真 " + at, source: "glasses",
});

describe("mergeResults", () => {
  it("は時刻の降順で混ぜる", () => {
    const out = mergeResults(
      [audio("2026-09-14T10:00:00.000Z"), audio("2026-09-14T08:00:00.000Z")],
      [photo("2026-09-14T09:00:00.000Z"), photo("2026-09-14T07:00:00.000Z")],
      10,
    );
    expect(out.map((h) => h.at)).toEqual([
      "2026-09-14T10:00:00.000Z",
      "2026-09-14T09:00:00.000Z",
      "2026-09-14T08:00:00.000Z",
      "2026-09-14T07:00:00.000Z",
    ]);
    expect(out.map((h) => h.kind)).toEqual(["audio", "photo", "audio", "photo"]);
  });

  it("は limit で切る", () => {
    const out = mergeResults(
      [audio("2026-09-14T10:00:00.000Z"), audio("2026-09-14T08:00:00.000Z")],
      [photo("2026-09-14T09:00:00.000Z")],
      2,
    );
    expect(out).toHaveLength(2);
    // 新しいほうから残る。片方に件数を割り当てないので、
    // その時間帯にどちらが多かったかは検索語のほうが決める。
    expect(out.map((h) => h.kind)).toEqual(["audio", "photo"]);
  });

  it("は片方が空でも動く", () => {
    expect(mergeResults([], [photo("2026-09-14T09:00:00.000Z")], 10)).toHaveLength(1);
    expect(mergeResults([audio("2026-09-14T09:00:00.000Z")], [], 10)).toHaveLength(1);
    expect(mergeResults([], [], 10)).toEqual([]);
  });

  it("は同時刻でも両方を残す", () => {
    const at = "2026-09-14T09:00:00.000Z";
    const out = mergeResults([audio(at)], [photo(at)], 10);
    expect(out).toHaveLength(2);
    expect(out.map((h) => h.id).sort()).toEqual(["pic-" + at, "seg-" + at].sort());
  });

  it("は limit が 0 以下なら空を返す", () => {
    expect(mergeResults([audio("2026-09-14T09:00:00.000Z")], [], 0)).toEqual([]);
    expect(mergeResults([audio("2026-09-14T09:00:00.000Z")], [], -1)).toEqual([]);
  });

  it("は入力を書き換えない", () => {
    const a = [audio("2026-09-14T08:00:00.000Z"), audio("2026-09-14T10:00:00.000Z")];
    const before = a.map((h) => h.at);
    mergeResults(a, [], 10);
    expect(a.map((h) => h.at)).toEqual(before);
  });
});
