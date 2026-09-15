import { describe, expect, it } from "vitest";
import { CAPTION_MODEL, captionInput, captionTextOf } from "../src/caption-model";

const decode = (dataUri: string) => {
  const b64 = dataUri.replace(/^data:image\/jpeg;base64,/, "");
  return Uint8Array.from(atob(b64), (c) => c.charCodeAt(0));
};

describe("captionInput", () => {
  it("は文面と画像を1つのユーザー発話にまとめる", () => {
    const input = captionInput(new Uint8Array([1, 2, 3]), "説明してください", 220);
    expect(input.max_tokens).toBe(220);
    expect(input.messages).toHaveLength(1);
    const [msg] = input.messages;
    expect(msg.role).toBe("user");
    expect(msg.content[0]).toEqual({ type: "text", text: "説明してください" });
    expect(msg.content[1].type).toBe("image_url");
    expect(msg.content[1].image_url.url.startsWith("data:image/jpeg;base64,")).toBe(true);
  });

  it("は画像のバイト列を欠けなく data URI に載せる", () => {
    const bytes = new Uint8Array([0, 1, 127, 128, 200, 255]);
    const url = captionInput(bytes, "p", 10).messages[0].content[1].image_url.url;
    expect(Array.from(decode(url))).toEqual(Array.from(bytes));
  });

  it("は分割の境目をまたぐ大きさでも壊さない", () => {
    // String.fromCharCode.apply は引数の数に上限があるので 32KiB ずつ変換している。
    // 境目の前後で1バイトでもずれると画像が壊れ、モデルは何も読めなくなる。
    const bytes = new Uint8Array(0x8000 * 2 + 17);
    for (let i = 0; i < bytes.length; i++) bytes[i] = (i * 31) & 0xff;
    const url = captionInput(bytes, "p", 10).messages[0].content[1].image_url.url;
    const back = decode(url);
    expect(back.length).toBe(bytes.length);
    expect(back[0x8000 - 1]).toBe(bytes[0x8000 - 1]);
    expect(back[0x8000]).toBe(bytes[0x8000]);
    expect(back[bytes.length - 1]).toBe(bytes[bytes.length - 1]);
  });
});

describe("captionTextOf", () => {
  it("は response を読む", () => {
    expect(captionTextOf({ response: "  画面の説明  " })).toBe("画面の説明");
  });

  it("は response が無ければ choices から読む", () => {
    expect(
      captionTextOf({ choices: [{ message: { content: "チャット形式の本文" } }] }),
    ).toBe("チャット形式の本文");
  });

  it("は何も無ければ空文字を返す", () => {
    expect(captionTextOf(null)).toBe("");
    expect(captionTextOf({})).toBe("");
  });
});

describe("CAPTION_MODEL", () => {
  it("は llama-4-scout を指す", () => {
    expect(CAPTION_MODEL).toBe("@cf/meta/llama-4-scout-17b-16e-instruct");
  });
});
