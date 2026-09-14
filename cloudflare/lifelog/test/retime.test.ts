import { describe, expect, it } from "vitest";
import { retimeTargets } from "../src/retime";

const START = Date.parse("2026-09-14T11:01:02.000Z");

describe("retimeTargets", () => {
  it("は連番を間隔ぶんずつ進める", () => {
    const out = retimeTargets(["g0-0000", "g0-0001", "g0-0002"], START, 30);
    expect(out.map((t) => t.takenAt)).toEqual([
      "2026-09-14T11:01:02.000Z",
      "2026-09-14T11:01:32.000Z",
      "2026-09-14T11:02:02.000Z",
    ]);
  });

  it("は欠番があっても残りをずらさない", () => {
    // これが直したかった不具合。アップロードが途中で落ちると欠番が出るのは
    // 想定内で、そのあと時刻を直すのがこの関数の仕事。位置で数えると欠番の
    // 後ろが全部1間隔ぶん早くなる。
    const out = retimeTargets(["g0-0000", "g0-0001", "g0-0002", "g0-0003", "g0-0005"], START, 30);
    expect(out[4].photoId).toBe("g0-0005");
    expect(out[4].takenAt).toBe("2026-09-14T11:03:32.000Z");
    expect(out[4].takenEpoch).toBe(Math.floor(Date.parse("2026-09-14T11:03:32.000Z") / 1000));
  });

  it("は takenEpoch を takenAt と一致させる", () => {
    for (const t of retimeTargets(["g0-0000", "g0-0007"], START, 30)) {
      expect(t.takenEpoch).toBe(Math.floor(Date.parse(t.takenAt) / 1000));
    }
  });

  it("は1枚でも動く", () => {
    expect(retimeTargets(["g0-0042"], START, 30)[0].takenAt).toBe("2026-09-14T11:22:02.000Z");
  });

  it("は空リストで空を返す", () => {
    expect(retimeTargets([], START, 30)).toEqual([]);
  });

  it("はフレーム番号が読めない id を拒む", () => {
    expect(() => retimeTargets(["nonsense"], START, 30)).toThrow();
    expect(() => retimeTargets(["g0-12"], START, 30)).toThrow();
  });

  it("は壊れた引数を拒む", () => {
    expect(() => retimeTargets(["g0-0000"], NaN, 30)).toThrow();
    expect(() => retimeTargets(["g0-0000"], START, 0)).toThrow();
  });
});
