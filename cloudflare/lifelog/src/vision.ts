/**
 * The vision model, and the one place that knows how to hand it a photograph.
 *
 * Three callers ask a picture a question — the queue consumer that captions
 * every frame, the debug endpoint that prices the captioning, and the presence
 * check that decides whether the device may speak. They had three copies of the
 * same call, which meant a change of model was three edits and a change of
 * request shape was three chances to get it wrong. It is one edit now.
 *
 * The request shape is the reason this file exists at all. Workers AI has two
 * of them, and which one a model takes is a property of the model rather than
 * of the account:
 *
 *   llama-3.2-11b-vision   { image: number[], prompt, max_tokens }
 *   everything newer       { messages: [{ content: [text, image_url] }] }
 *
 * The second wants a data URI and will not take an HTTP URL — the generated
 * types say so in as many words — so the bytes have to be base64 on the way
 * past. That is the whole of the migration, and it is here.
 */

/**
 * What the archive is captioned with.
 *
 * Chosen against the whole vision-capable catalogue on price, because the
 * quality argument was a tie and the price was not: 26B of parameters with 4B
 * active, at $0.100/M in and $0.300/M out. The nearest alternative that is also
 * current — glm-5.3-flash — is dearer on both axes for the same capabilities,
 * and qwen3.8-27b is 4.5x the input and 10.7x the output.
 */
export const VISION_MODEL = "@cf/google/gemma-4-26b-a4b-it";

/**
 * What it was captioned with before, kept only so the two can be shown the same
 * frame and compared.
 *
 * Delete this, and the branch in `askAboutImage` that serves it, once the
 * presence check has moved too. Until then the archive contains captions from
 * both and the debug endpoint has to be able to reach either.
 */
export const LEGACY_VISION_MODEL = "@cf/meta/llama-3.2-11b-vision-instruct";

/** Whether a model wants the old top-level `image`, or messages with a data URI. */
function takesBytesDirectly(model: string): boolean {
  return model === LEGACY_VISION_MODEL;
}

export interface VisionAnswer {
  /** The answer, trimmed. Empty means the model said nothing usable. */
  text: string;
  /** What it cost, when the response carries a usage block. */
  neurons: number | null;
  /** Unedited, for the debug endpoint. Nothing else should read this. */
  raw: unknown;
}

/**
 * Base64 without unpacking the whole frame into one argument list.
 *
 * A 640x480 JPEG off this device is tens of kilobytes, and
 * `String.fromCharCode(...bytes)` on that many arguments is a stack overflow
 * waiting for a slightly larger picture. Chunked at 32 KB, which is the usual
 * safe width for a spread call.
 */
function dataUrl(bytes: Uint8Array, mime: string): string {
  const CHUNK = 0x8000;
  let binary = "";
  for (let i = 0; i < bytes.length; i += CHUNK) {
    binary += String.fromCharCode(...bytes.subarray(i, i + CHUNK));
  }
  return `data:${mime};base64,${btoa(binary)}`;
}

export interface AskOptions {
  /** Defaults to VISION_MODEL. The debug endpoint overrides it to compare. */
  model?: string;
  /** JPEG unless the stored object says otherwise. */
  mime?: string;
  /**
   * How hard a reasoning model is allowed to think before it answers.
   *
   * It matters here for a reason that is not obvious: thinking is billed as
   * output, and output is 3x the price of input. It also eats the token
   * ceiling, so a low ceiling and a thoughtful model produce an empty answer
   * rather than a short one — see the note on the presence check in nudge.ts.
   */
  reasoning?: "low" | "medium" | "high";
}

/**
 * Asks one picture one question.
 *
 * Throws whatever the model layer throws; the presence check needs to catch it
 * and answer no, and the queue consumer needs it to retry, and those are
 * different enough that this does not decide for them.
 */
export async function askAboutImage(
  ai: Ai,
  bytes: Uint8Array,
  prompt: string,
  maxTokens: number,
  options: AskOptions = {},
): Promise<VisionAnswer> {
  const model = options.model ?? VISION_MODEL;

  const input: Record<string, unknown> = takesBytesDirectly(model)
    ? { image: [...bytes], prompt, max_tokens: maxTokens }
    : {
        messages: [
          {
            role: "user",
            content: [
              { type: "text", text: prompt },
              {
                type: "image_url",
                image_url: { url: dataUrl(bytes, options.mime ?? "image/jpeg") },
              },
            ],
          },
        ],
        max_completion_tokens: maxTokens,
      };

  if (options.reasoning && !takesBytesDirectly(model)) {
    input.reasoning_effort = options.reasoning;
  }

  const result: any = await ai.run(model as any, input as any);

  return {
    text: String(result?.response ?? "").trim(),
    neurons: Number(result?.usage?.neurons) || null,
    raw: result,
  };
}
