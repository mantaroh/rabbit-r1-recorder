import { describe, expect, it } from "vitest";
import {
  CAPTION_PROMPT_GLASSES,
  CAPTION_PROMPT_R1,
  captionPromptFor,
} from "../src/caption-prompt";

describe("captionPromptFor", () => {
  it("は眼鏡カメラに専用のプロンプトを返す", () => {
    expect(captionPromptFor("glasses")).toBe(CAPTION_PROMPT_GLASSES);
  });

  it("はそれ以外を R1 のプロンプトに落とす", () => {
    expect(captionPromptFor("r1")).toBe(CAPTION_PROMPT_R1);
    expect(captionPromptFor(null)).toBe(CAPTION_PROMPT_R1);
    expect(captionPromptFor(undefined)).toBe(CAPTION_PROMPT_R1);
    expect(captionPromptFor("なにか知らないもの")).toBe(CAPTION_PROMPT_R1);
  });

  it("の眼鏡用は文字を読むよう指示している", () => {
    // 1920x1080 のまま上げる判断と対になっている。解像度を保っても
    // プロンプトが文字に触れなければ、その画素は無駄になる。
    expect(CAPTION_PROMPT_GLASSES).toContain("文字");
  });

  it("の2つは別物である", () => {
    expect(CAPTION_PROMPT_GLASSES).not.toBe(CAPTION_PROMPT_R1);
  });
});
