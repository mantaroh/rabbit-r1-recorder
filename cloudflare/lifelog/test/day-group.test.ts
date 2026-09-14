import { describe, expect, it } from "vitest";
import { groupPhotos, type PhotoRow } from "../src/day-group";

function row(over: Partial<PhotoRow> & { photo_id: string; taken_epoch: number }): PhotoRow {
  return {
    facing: "front",
    caption: null,
    source: "r1",
    source_file: null,
    taken_at: new Date(over.taken_epoch * 1000).toISOString(),
    ...over,
  };
}

describe("groupPhotos", () => {
  it("は R1 の前後の対を1つにまとめる", () => {
    const groups = groupPhotos([
      row({ photo_id: "a", taken_epoch: 1_789_000_000, facing: "front" }),
      row({ photo_id: "b", taken_epoch: 1_789_000_003, facing: "rear" }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].shots.map((s) => s.photo_id)).toEqual(["a", "b"]);
  });

  it("は分が違う R1 の写真を分ける", () => {
    const groups = groupPhotos([
      row({ photo_id: "a", taken_epoch: 1_789_000_000 }),
      row({ photo_id: "b", taken_epoch: 1_789_000_300 }),
    ]);
    expect(groups).toHaveLength(2);
  });

  it("は同じ分に入る眼鏡の2フレームを対として扱わない", () => {
    // これが直したかった不具合。30秒間隔だと1分に2枚入るので、
    // 分でまとめると無関係な2フレームが前後の対になってしまう。

    // このテストの前提そのもの。ここが崩れると、名前だけ残って
    // 「同じ分」を検証しないテストになる。
    expect(Math.floor(1_788_999_960 / 60)).toBe(Math.floor(1_788_999_990 / 60));

    const groups = groupPhotos([
      row({ photo_id: "g-0", taken_epoch: 1_788_999_960, source: "glasses", source_file: "aaaaaaaaaaaaaaaa" }),
      row({ photo_id: "g-1", taken_epoch: 1_788_999_990, source: "glasses", source_file: "aaaaaaaaaaaaaaaa" }),
    ]);
    expect(groups).toHaveLength(1);
    expect(groups[0].source).toBe("glasses");
    expect(groups[0].shots).toHaveLength(2);
  });

  it("は眼鏡の1本ぶんを丸ごと1エントリにする", () => {
    const rows = Array.from({ length: 37 }, (_, i) =>
      row({
        photo_id: "g-" + i,
        taken_epoch: 1_789_000_000 + i * 30,
        source: "glasses",
        source_file: "aaaaaaaaaaaaaaaa",
      }),
    );
    const groups = groupPhotos(rows);
    expect(groups).toHaveLength(1);
    expect(groups[0].shots).toHaveLength(37);
  });

  it("は別の動画から来たフレームを混ぜない", () => {
    const groups = groupPhotos([
      row({ photo_id: "g-0", taken_epoch: 1_789_000_000, source: "glasses", source_file: "aaaaaaaaaaaaaaaa" }),
      row({ photo_id: "h-0", taken_epoch: 1_789_000_010, source: "glasses", source_file: "bbbbbbbbbbbbbbbb" }),
    ]);
    expect(groups).toHaveLength(2);
  });

  it("は同じ分の R1 と眼鏡を別々にする", () => {
    const groups = groupPhotos([
      row({ photo_id: "r", taken_epoch: 1_789_000_000 }),
      row({ photo_id: "g", taken_epoch: 1_789_000_005, source: "glasses", source_file: "aaaaaaaaaaaaaaaa" }),
    ]);
    expect(groups).toHaveLength(2);
    expect(groups.map((g) => g.source).sort()).toEqual(["glasses", "r1"]);
  });

  it("は時刻の昇順で返す", () => {
    const groups = groupPhotos([
      row({ photo_id: "late", taken_epoch: 1_789_000_600 }),
      row({ photo_id: "early", taken_epoch: 1_789_000_000 }),
    ]);
    expect(groups.map((g) => g.shots[0].photo_id)).toEqual(["early", "late"]);
  });

  it("は空を渡されても落ちない", () => {
    expect(groupPhotos([])).toEqual([]);
  });
});
