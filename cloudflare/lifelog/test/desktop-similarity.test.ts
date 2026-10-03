import { describe, expect, it } from "vitest";
import {
  CAPTION_SKIP_MAX_GAP_SEC,
  canSkipCaption,
  ocrSimilarity,
} from "../src/desktop-similarity";

const EDITOR = "src/index.ts export async function captionPhoto(message, env) { const row = await env.DB.prepare";
const DISCORD = "times_mantaroh チャンネル 今日はここまで おつかれさまでした メンバー オンライン";

describe("ocrSimilarity", () => {
  it("は同じ文字列で1を返す", () => {
    expect(ocrSimilarity(EDITOR, EDITOR)).toBe(1);
  });

  it("は空白の違いを無視する", () => {
    expect(ocrSimilarity("今日 は 晴れ", "今日は晴れ")).toBe(1);
  });

  it("は別のアプリの画面で低くなる", () => {
    expect(ocrSimilarity(EDITOR, DISCORD)).toBeLessThan(0.1);
  });

  it("は両方空なら1、片方だけ空なら0", () => {
    expect(ocrSimilarity("", "  ")).toBe(1);
    expect(ocrSimilarity("", EDITOR)).toBe(0);
  });
});

describe("canSkipCaption", () => {
  const ref = { ocrText: EDITOR, takenEpoch: 1_000_000 };

  it("は前回とほぼ同じ画面なら省く", () => {
    expect(canSkipCaption({ ocrText: EDITOR + " = 1;", takenEpoch: 1_000_060 }, ref)).toBe(true);
  });

  it("は画面が変わったら省かない", () => {
    expect(canSkipCaption({ ocrText: DISCORD, takenEpoch: 1_000_060 }, ref)).toBe(false);
  });

  it("は前回が無ければ省かない", () => {
    expect(canSkipCaption({ ocrText: EDITOR, takenEpoch: 1_000_060 }, null)).toBe(false);
  });

  it("は30分経ったら同じ画面でも省かない（直前までは省く）", () => {
    const just = ref.takenEpoch + CAPTION_SKIP_MAX_GAP_SEC;
    expect(canSkipCaption({ ocrText: EDITOR, takenEpoch: just - 1 }, ref)).toBe(true);
    expect(canSkipCaption({ ocrText: EDITOR, takenEpoch: just }, ref)).toBe(false);
  });

  it("は OCR が空の画面を省かない", () => {
    expect(canSkipCaption({ ocrText: "", takenEpoch: 1_000_060 }, { ocrText: "", takenEpoch: 1_000_000 })).toBe(false);
    expect(canSkipCaption({ ocrText: null, takenEpoch: 1_000_060 }, ref)).toBe(false);
  });
});
