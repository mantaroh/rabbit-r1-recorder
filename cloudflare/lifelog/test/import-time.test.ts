import { describe, expect, it } from "vitest";
import { frameTimes } from "../src/import-time";

// 実測値。E:\DCIM\MOVI0006.mov を ffprobe と Windows のファイル情報で確認したもの。
const MOVI0006_LAST_MODIFIED = Date.parse("2026-09-14T20:19:02+09:00");

describe("frameTimes", () => {
  it("は更新日時から開始時刻を逆算する", () => {
    const times = frameTimes(MOVI0006_LAST_MODIFIED, 37, 30);
    expect(times).toHaveLength(37);
    // 20:19:02 − 36区間 × 30秒 = 20:01:02 JST = 11:01:02Z
    expect(times[0]).toBe("2026-09-14T11:01:02.000Z");
    expect(times[36]).toBe("2026-09-14T11:19:02.000Z");
  });

  it("は間隔ぶんずつ進む", () => {
    const times = frameTimes(MOVI0006_LAST_MODIFIED, 37, 30);
    for (let i = 1; i < times.length; i++) {
      expect(Date.parse(times[i]) - Date.parse(times[i - 1])).toBe(30_000);
    }
  });

  it("は1フレームなら更新日時そのものを返す", () => {
    // MOVI0004 がこれ。作成日時と更新日時が2秒差で、フレームは1枚。
    const times = frameTimes(Date.parse("2026-09-14T19:59:02+09:00"), 1, 30);
    expect(times).toEqual(["2026-09-14T10:59:02.000Z"]);
  });

  it("は日をまたいでも壊れない", () => {
    // JST の 00:05:02 に書き終わった37枚は、前日の 23:47:02 から始まる。
    const times = frameTimes(Date.parse("2026-09-15T00:05:02+09:00"), 37, 30);
    expect(times[0]).toBe("2026-09-14T14:47:02.000Z");
    expect(times[36]).toBe("2026-09-14T15:05:02.000Z");
  });

  it("は間隔を変えれば結果も変わる", () => {
    const times = frameTimes(MOVI0006_LAST_MODIFIED, 3, 60);
    expect(Date.parse(times[1]) - Date.parse(times[0])).toBe(60_000);
    expect(times[2]).toBe(new Date(MOVI0006_LAST_MODIFIED).toISOString());
  });

  it("は壊れた入力を拒む", () => {
    expect(() => frameTimes(NaN, 37, 30)).toThrow();
    expect(() => frameTimes(MOVI0006_LAST_MODIFIED, 0, 30)).toThrow();
    expect(() => frameTimes(MOVI0006_LAST_MODIFIED, 1.5, 30)).toThrow();
    expect(() => frameTimes(MOVI0006_LAST_MODIFIED, 37, 0)).toThrow();
  });

  it("は文字列化してもそのまま動く", () => {
    // import.ts はこの関数を toString() でページに埋め込む。バンドラが
    // 外側の識別子を参照する形に書き換えたら、ブラウザで落ちる。ここで止める。
    const rebuilt = new Function("return " + frameTimes.toString())() as typeof frameTimes;
    expect(rebuilt(MOVI0006_LAST_MODIFIED, 37, 30)[0]).toBe("2026-09-14T11:01:02.000Z");
  });
});
