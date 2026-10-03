/**
 * PC のスクショにキャプションを付けるかどうかの判定。
 *
 * キャプション費用の9割以上が PC のスクショで、そのほとんどは直前と同じ
 * 画面の説明だった。OCR は全件取っているので、前回キャプションした画面と
 * OCR がほぼ同じなら、新しく付けない。
 *
 * しきい値は本番の3日分（418枚）で決めた。0.6 と30分で、キャプションを
 * 付けるのは23%。0.5〜0.6 でスキップされた組のキャプションを読むと、
 * ほとんどが同じ説明の言い換えだった。画面の片側だけ切り替わった組も
 * 1つあり、30分の上限はその取りこぼしを長引かせないためのもの。
 */
export const CAPTION_SKIP_SIMILARITY = 0.6;
export const CAPTION_SKIP_MAX_GAP_SEC = 30 * 60;

/** 空白を除いた文字2-gram。日本語は単語に区切れないので文字で見る。 */
function bigrams(text: string): Set<string> {
  const t = text.replace(/\s+/g, "");
  const grams = new Set<string>();
  for (let i = 0; i + 1 < t.length; i++) grams.add(t.slice(i, i + 2));
  return grams;
}

/** 2つの OCR の Jaccard 係数、0〜1。 */
export function ocrSimilarity(a: string, b: string): number {
  const x = bigrams(a);
  const y = bigrams(b);
  if (x.size === 0 && y.size === 0) return 1;
  let shared = 0;
  for (const g of x) if (y.has(g)) shared++;
  return shared / (x.size + y.size - shared);
}

/**
 * 前回キャプションした画面と比べて、今回はキャプションを省けるか。
 *
 * OCR が空の画面は比べようがないので省かない。文字のない画面の変化は
 * OCR からは分からず、同じと見なすと取りこぼす側に倒れる。
 */
export function canSkipCaption(
  current: { ocrText: string | null; takenEpoch: number },
  reference: { ocrText: string | null; takenEpoch: number } | null,
): boolean {
  if (!reference) return false;
  if (!current.ocrText?.trim() || !reference.ocrText?.trim()) return false;
  if (current.takenEpoch - reference.takenEpoch >= CAPTION_SKIP_MAX_GAP_SEC) return false;
  return ocrSimilarity(current.ocrText, reference.ocrText) >= CAPTION_SKIP_SIMILARITY;
}
