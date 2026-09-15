/**
 * Which vision model writes the captions, and how it is spoken to.
 *
 * llama-4-scout replaced llama-3.2-11b-vision on 2026-09-15. Compared on the
 * same synthetic image, whose text was known, the old model misread 週次 as
 * 一覧次 and 眼鏡 as 着鏡, and at 1920x1080 stopped listing what was on screen
 * and padded the answer with a general essay on what project management is --
 * the failure behind the glasses captions that ran to the token ceiling and
 * stopped mid-sentence. scout summarised the same image correctly in half the
 * words, and in less than half the time.
 *
 * It is not cheaper. Measured: 0.025 neurons per input token against 0.0044
 * for the old model, so at the R1's 640x480 the two cost the same (21.8 vs
 * 22.4 neurons) and at the glasses camera's 1920x1080 scout costs about 1.7x
 * (64.2 vs 38.5). The R1 is most of the archive, so the total barely moves.
 */
export const CAPTION_MODEL = "@cf/meta/llama-4-scout-17b-16e-instruct";

/**
 * Binary to base64 in slices.
 *
 * String.fromCharCode.apply takes the bytes as arguments, and engines cap how
 * many arguments a call may have; a 1920x1080 JPEG is well past that. 32 KiB at
 * a time stays under every limit.
 */
function toBase64(bytes: Uint8Array): string {
  let bin = "";
  const step = 0x8000;
  for (let i = 0; i < bytes.length; i += step) {
    bin += String.fromCharCode.apply(null, Array.from(bytes.subarray(i, i + step)));
  }
  return btoa(bin);
}

/**
 * The request body for one caption.
 *
 * scout takes an OpenAI-style message with the image inline as a data URI.
 * The old model took the bytes as a plain array beside a prompt; scout rejects
 * nothing in that shape but reads the chat form, which is why the shape changed
 * along with the model name.
 */
export interface CaptionInput {
  // Tuples, not arrays: there is always exactly one message, holding exactly
  // the text and then the image. Typed that way, a caller reading the image
  // back does not have to argue with the compiler about whether it exists.
  messages: [
    {
      role: "user";
      content: [
        { type: "text"; text: string },
        { type: "image_url"; image_url: { url: string } },
      ];
    },
  ];
  max_tokens: number;
}

export function captionInput(bytes: Uint8Array, prompt: string, maxTokens: number): CaptionInput {
  return {
    messages: [
      {
        role: "user",
        content: [
          { type: "text", text: prompt },
          { type: "image_url", image_url: { url: "data:image/jpeg;base64," + toBase64(bytes) } },
        ],
      },
    ],
    max_tokens: maxTokens,
  };
}

/**
 * The caption text out of a result.
 *
 * scout fills both `response` and `choices[0].message.content` with the same
 * string -- checked against a live call -- so `response` alone would do today.
 * The fallback is there because the two are an API courtesy rather than a
 * contract, and a caption that silently comes back empty is stored as
 * 'captioned' and then never matches a search.
 */
export function captionTextOf(result: any): string {
  return String(result?.response ?? result?.choices?.[0]?.message?.content ?? "").trim();
}
