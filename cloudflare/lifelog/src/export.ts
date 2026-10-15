/**
 * 時間帯を指定した書き出し。音声は ZIP、文字起こしはテキスト。
 *
 * 音声は1つにつながず、1セグメント1ファイルのまま ZIP に入れる。つなぐと
 * 録音の切れ目が詰まって時刻が分からなくなり、WAV と Opus が混ざった時間帯は
 * そもそも作れない。時刻はファイル名に残す。
 *
 * ZIP は無圧縮で、R2 から読みながら流す。WAV も Opus も縮まないので圧縮は
 * CPU の無駄になる。CRC は中身を流したあとでないと分からないので、各ファイルの
 * 後ろに data descriptor を置く（汎用フラグ bit 3）。ZIP64 は使わないので、
 * 全体を 4GB 未満に抑えるのは呼び出し側の上限（MAX_EXPORT_BYTES）。
 */
import { segmentText, type SegmentHead } from "./day-page";

/** 音声の書き出しの上限。ZIP64 なしの 4GB に余裕を残す。WAV 48kHz で6時間弱。 */
export const MAX_EXPORT_BYTES = 2 * 1024 ** 3;

const JST_OFFSET_SEC = 9 * 3600;

/** UTC 秒を JST の各部分に分ける。 */
function jstParts(epoch: number) {
  const d = new Date((epoch + JST_OFFSET_SEC) * 1000);
  const p = (n: number) => String(n).padStart(2, "0");
  return {
    date: `${d.getUTCFullYear()}-${p(d.getUTCMonth() + 1)}-${p(d.getUTCDate())}`,
    time: `${p(d.getUTCHours())}:${p(d.getUTCMinutes())}:${p(d.getUTCSeconds())}`,
    compact: `${d.getUTCFullYear()}${p(d.getUTCMonth() + 1)}${p(d.getUTCDate())}_${p(d.getUTCHours())}${p(d.getUTCMinutes())}${p(d.getUTCSeconds())}`,
    d,
  };
}

/** 書き出しの対象になる1セグメント。 */
export interface ExportSegment extends SegmentHead {
  r2_key: string;
  started_epoch: number;
  bytes: number | null;
}

/** ZIP の中のファイル名。`20261015_093000_<segment_id>.wav`。拡張子は R2 のキーから。 */
export function audioFileName(seg: Pick<ExportSegment, "segment_id" | "r2_key" | "started_epoch">): string {
  const dot = seg.r2_key.lastIndexOf(".");
  const ext = dot > seg.r2_key.lastIndexOf("/") ? seg.r2_key.slice(dot + 1) : "bin";
  return `${jstParts(seg.started_epoch).compact}_${seg.segment_id}.${ext}`;
}

/** ダウンロードのファイル名。`lifelog-20261015_0900-1000`。日をまたぐときは終わりの日付も入れる。 */
export function exportBaseName(fromEpoch: number, toEpoch: number): string {
  const f = jstParts(fromEpoch);
  const t = jstParts(toEpoch);
  const end = t.compact.slice(0, 8) === f.compact.slice(0, 8) ? t.compact.slice(9, 13) : t.compact.slice(0, 13);
  return `lifelog-${f.compact.slice(0, 13)}-${end}`;
}

/**
 * 文字起こしのテキスト。1行1セグメントで、日ビューに出る言葉だけ。
 * 定型句（Whisper の独り言）と、まだ・もう文字のないものは入れない。
 */
export function transcriptText(rows: ExportSegment[], fromEpoch: number, toEpoch: number): string {
  const f = jstParts(fromEpoch);
  const t = jstParts(toEpoch);
  const lines: string[] = [];
  for (const row of rows) {
    const text = segmentText(row).replace(/\s*\n\s*/g, " ");
    if (!text) continue;
    const at = jstParts(row.started_epoch);
    lines.push(`${at.date} ${at.time}  ${text}`);
  }
  const head = `# ${f.date} ${f.time.slice(0, 5)} 〜 ${t.date} ${t.time.slice(0, 5)}（JST）の文字起こし ${lines.length}件`;
  return [head, ...lines].join("\n") + "\n";
}

// ------------------------------------------------------------------ zip ---

/**
 * slicing-by-8 の表。1バイトずつの表引きだと 256MB に約3秒かかり、上限の 2GB では
 * Worker の CPU 上限（30秒）に近づく。8バイトずつ進めると数倍速い。
 */
const CRC_TABLES = (() => {
  const t = new Uint32Array(256 * 8);
  for (let n = 0; n < 256; n++) {
    let c = n;
    for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1;
    t[n] = c >>> 0;
  }
  for (let n = 0; n < 256; n++) {
    for (let k = 1; k < 8; k++) t[k * 256 + n] = (t[(k - 1) * 256 + n] >>> 8) ^ t[t[(k - 1) * 256 + n] & 0xff];
  }
  return t;
})();

/** CRC-32 を続きから計算する。最初は 0 を渡す。 */
export function crc32(chunk: Uint8Array, crc = 0): number {
  const t = CRC_TABLES;
  let c = ~crc >>> 0;
  let i = 0;
  const end8 = chunk.length - (chunk.length % 8);
  for (; i < end8; i += 8) {
    const a = c ^ (chunk[i] | (chunk[i + 1] << 8) | (chunk[i + 2] << 16) | (chunk[i + 3] << 24));
    c = t[1792 + (a & 0xff)] ^ t[1536 + ((a >>> 8) & 0xff)] ^ t[1280 + ((a >>> 16) & 0xff)] ^ t[1024 + (a >>> 24)]
      ^ t[768 + chunk[i + 4]] ^ t[512 + chunk[i + 5]] ^ t[256 + chunk[i + 6]] ^ t[chunk[i + 7]];
  }
  for (; i < chunk.length; i++) c = t[(c ^ chunk[i]) & 0xff] ^ (c >>> 8);
  return ~c >>> 0;
}

/** ZIP の日時（MS-DOS 形式）。ZIP にタイムゾーンはないので JST の壁時計で入れる。 */
function dosTime(epoch: number): { time: number; date: number } {
  const { d } = jstParts(epoch);
  return {
    time: (d.getUTCHours() << 11) | (d.getUTCMinutes() << 5) | (d.getUTCSeconds() >> 1),
    date: ((d.getUTCFullYear() - 1980) << 9) | ((d.getUTCMonth() + 1) << 5) | d.getUTCDate(),
  };
}

export interface ZipEntry {
  name: string;
  epoch: number;
  /** 中身。null は「読めなかった」で、そのファイルは ZIP に入れない。 */
  open: () => Promise<ReadableStream<Uint8Array> | Uint8Array | null>;
}

interface Written { name: Uint8Array; crc: number; size: number; offset: number; time: number; date: number }

/** bit 3: サイズと CRC は後ろの data descriptor にある。bit 11: 名前は UTF-8。 */
const FLAGS = 0x0008 | 0x0800;

function localHeader(name: Uint8Array, time: number, date: number): Uint8Array {
  const b = new Uint8Array(30 + name.length);
  const v = new DataView(b.buffer);
  v.setUint32(0, 0x04034b50, true);
  v.setUint16(4, 20, true);
  v.setUint16(6, FLAGS, true);
  v.setUint16(8, 0, true); // 無圧縮
  v.setUint16(10, time, true);
  v.setUint16(12, date, true);
  // CRC とサイズは 0。data descriptor に書く。
  v.setUint16(26, name.length, true);
  b.set(name, 30);
  return b;
}

function dataDescriptor(crc: number, size: number): Uint8Array {
  const b = new Uint8Array(16);
  const v = new DataView(b.buffer);
  v.setUint32(0, 0x08074b50, true);
  v.setUint32(4, crc, true);
  v.setUint32(8, size, true);
  v.setUint32(12, size, true);
  return b;
}

function centralDirectory(files: Written[], start: number): Uint8Array {
  const size = files.reduce((n, f) => n + 46 + f.name.length, 0);
  const b = new Uint8Array(size + 22);
  const v = new DataView(b.buffer);
  let p = 0;
  for (const f of files) {
    v.setUint32(p, 0x02014b50, true);
    v.setUint16(p + 4, 20, true);
    v.setUint16(p + 6, 20, true);
    v.setUint16(p + 8, FLAGS, true);
    v.setUint16(p + 10, 0, true);
    v.setUint16(p + 12, f.time, true);
    v.setUint16(p + 14, f.date, true);
    v.setUint32(p + 16, f.crc, true);
    v.setUint32(p + 20, f.size, true);
    v.setUint32(p + 24, f.size, true);
    v.setUint16(p + 28, f.name.length, true);
    v.setUint32(p + 42, f.offset, true);
    b.set(f.name, p + 46);
    p += 46 + f.name.length;
  }
  v.setUint32(p, 0x06054b50, true);
  v.setUint16(p + 8, files.length, true);
  v.setUint16(p + 10, files.length, true);
  v.setUint32(p + 12, size, true);
  v.setUint32(p + 16, start, true);
  return b;
}

async function* zipChunks(entries: ZipEntry[]): AsyncGenerator<Uint8Array> {
  const encoder = new TextEncoder();
  const files: Written[] = [];
  let offset = 0;
  for (const entry of entries) {
    const body = await entry.open();
    if (body === null) continue;
    const name = encoder.encode(entry.name);
    const { time, date } = dosTime(entry.epoch);
    const header = localHeader(name, time, date);
    const file: Written = { name, crc: 0, size: 0, offset, time, date };
    yield header;
    offset += header.length;

    if (body instanceof Uint8Array) {
      file.crc = crc32(body);
      file.size = body.length;
      yield body;
    } else {
      const reader = body.getReader();
      for (;;) {
        const { done, value } = await reader.read();
        if (done) break;
        file.crc = crc32(value, file.crc);
        file.size += value.length;
        yield value;
      }
    }
    offset += file.size;
    const tail = dataDescriptor(file.crc, file.size);
    yield tail;
    offset += tail.length;
    // ZIP64 なしで書けるのはここまで。上限で止めているので、来たら上限の計算が間違っている。
    if (offset > 0xffffffff || files.length >= 0xffff) throw new Error("zip too large without zip64");
    files.push(file);
  }
  yield centralDirectory(files, offset);
}

/** ZIP を流す。読まれた分だけ R2 から読む。 */
export function zipStream(entries: ZipEntry[]): ReadableStream<Uint8Array> {
  const chunks = zipChunks(entries);
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      const { done, value } = await chunks.next();
      if (done) controller.close();
      else controller.enqueue(value);
    },
    async cancel() {
      await chunks.return(undefined);
    },
  });
}
