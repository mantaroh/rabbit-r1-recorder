/**
 * What the vision model is asked for, and why it differs by camera.
 *
 * Japanese, because the transcripts are and a search that has to work across
 * two languages works well in neither. Concrete and short: the caption exists
 * so a frame can be found later, not so it can be read for pleasure, and every
 * token is billed.
 */

/**
 * The R1's timelapse: a device on a desk or in a pocket, photographing the
 * room. 640x480, front and rear, one pair every five minutes.
 */
export const CAPTION_PROMPT_R1 =
  "この写真に写っているものを日本語で簡潔に説明してください。" +
  "人物の有無、場所の様子、目立つ物を2〜3文で。推測は避け、見えるものだけを述べてください。";

/**
 * The glasses camera: 1920x1080 of whatever the wearer was looking at.
 *
 * A different question, not a rephrasing of the same one. Asked for "the state
 * of the place", a head-mounted frame answers about the room and loses the
 * thing the wearer was actually attending to — which is the entire reason this
 * camera is worn.
 *
 * The instruction to read text is what the resolution is for. Keeping
 * 1920x1080 and then not asking about the writing on the screen in front of
 * the wearer spends the pixels for nothing.
 */
export const CAPTION_PROMPT_GLASSES =
  "これは頭に装着したカメラの映像で、撮影者が見ていたものです。" +
  "何を見ていたかを日本語で2〜3文で説明してください。" +
  "画面・書類・看板などの文字が読める場合はその内容を含めてください。" +
  "推測は避け、見えるものだけを述べてください。";

/** Unknown sources fall back to the R1 wording rather than failing. */
export function captionPromptFor(source: string | null | undefined): string {
  return source === "glasses" ? CAPTION_PROMPT_GLASSES : CAPTION_PROMPT_R1;
}
