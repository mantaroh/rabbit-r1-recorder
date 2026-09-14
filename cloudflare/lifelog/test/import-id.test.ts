import { describe, expect, it } from "vitest";
import { photoIdFor } from "../src/import-id";

describe("photoIdFor", () => {
  it("は22文字の id を作る", () => {
    expect(photoIdFor("3f8a1c9e04b27d6f", 12)).toBe("g3f8a1c9e04b27d6f-0012");
    expect(photoIdFor("3f8a1c9e04b27d6f", 12)).toHaveLength(22);
  });

  it("はフレーム番号を4桁に0詰めする", () => {
    // 0詰めは飾りではない。photo-retime がこの順序で並べ替えるので、
    // 0詰めを外すと 10 が 2 より前に来る。
    expect(photoIdFor("0000000000000000", 0)).toBe("g0000000000000000-0000");
    expect(photoIdFor("0000000000000000", 9999)).toBe("g0000000000000000-9999");
  });

  it("は既存の photo_id 検証を通る", () => {
    expect(photoIdFor("abcdef0123456789", 7)).toMatch(/^[A-Za-z0-9_-]{8,64}$/);
  });

  it("は壊れた入力を拒む", () => {
    expect(() => photoIdFor("XYZ", 0)).toThrow();
    expect(() => photoIdFor("3F8A1C9E04B27D6F", 0)).toThrow(); // 大文字は不可
    expect(() => photoIdFor("3f8a1c9e04b27d6f", -1)).toThrow();
    expect(() => photoIdFor("3f8a1c9e04b27d6f", 10000)).toThrow();
    expect(() => photoIdFor("3f8a1c9e04b27d6f", 1.5)).toThrow();
  });
});
