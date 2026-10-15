import { describe, expect, it } from "vitest";
import {
  audioFileName,
  crc32,
  exportBaseName,
  transcriptText,
  zipStream,
  type ExportSegment,
} from "../src/export";

const epoch = (iso: string) => Date.parse(iso) / 1000;

function seg(over: Partial<ExportSegment>): ExportSegment {
  return {
    segment_id: "seg00001",
    started_at: "2026-10-15T09:30:00+09:00",
    started_epoch: epoch("2026-10-15T09:30:00+09:00"),
    status: "transcribed",
    voiced_ratio: 0.5,
    transcript: "こんにちは",
    stock_phrase: 0,
    r2_key: "audio/2026/10/15/seg00001.wav",
    bytes: 100,
    ...over,
  };
}

async function readAll(stream: ReadableStream<Uint8Array>): Promise<Uint8Array> {
  const parts: Uint8Array[] = [];
  const reader = stream.getReader();
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    parts.push(value);
  }
  const out = new Uint8Array(parts.reduce((n, p) => n + p.length, 0));
  let p = 0;
  for (const part of parts) { out.set(part, p); p += part.length; }
  return out;
}

/** 中央ディレクトリから読み戻す。ローカルヘッダの位置と中身の CRC も照合する。 */
function unzip(zip: Uint8Array): { name: string; data: Uint8Array; time: number; date: number }[] {
  const v = new DataView(zip.buffer, zip.byteOffset, zip.byteLength);
  const end = zip.length - 22;
  expect(v.getUint32(end, true)).toBe(0x06054b50);
  const count = v.getUint16(end + 10, true);
  let p = v.getUint32(end + 16, true);
  const out = [];
  for (let i = 0; i < count; i++) {
    expect(v.getUint32(p, true)).toBe(0x02014b50);
    const crc = v.getUint32(p + 16, true);
    const size = v.getUint32(p + 24, true);
    const nameLen = v.getUint16(p + 28, true);
    const offset = v.getUint32(p + 42, true);
    const name = new TextDecoder().decode(zip.subarray(p + 46, p + 46 + nameLen));
    expect(v.getUint32(offset, true)).toBe(0x04034b50);
    const start = offset + 30 + v.getUint16(offset + 26, true);
    const data = zip.subarray(start, start + size);
    expect(crc32(data)).toBe(crc);
    // data descriptor
    expect(v.getUint32(start + size, true)).toBe(0x08074b50);
    expect(v.getUint32(start + size + 4, true)).toBe(crc);
    out.push({ name, data, time: v.getUint16(p + 12, true), date: v.getUint16(p + 14, true) });
    p += 46 + nameLen;
  }
  return out;
}

describe("crc32", () => {
  it("は標準の検査値に一致する", () => {
    expect(crc32(new TextEncoder().encode("123456789"))).toBe(0xcbf43926);
  });

  it("は分けて計算しても同じ値になる", () => {
    const all = new TextEncoder().encode("123456789");
    expect(crc32(all.subarray(4), crc32(all.subarray(0, 4)))).toBe(0xcbf43926);
  });
});

describe("audioFileName", () => {
  it("は JST の時刻と ID、R2 のキーの拡張子にする", () => {
    expect(audioFileName(seg({}))).toBe("20261015_093000_seg00001.wav");
    expect(audioFileName(seg({ r2_key: "audio/x/seg00001.opus" }))).toBe("20261015_093000_seg00001.opus");
  });

  it("は拡張子のないキーを bin にする", () => {
    expect(audioFileName(seg({ r2_key: "audio/v1.2/seg00001" }))).toBe("20261015_093000_seg00001.bin");
  });
});

describe("exportBaseName", () => {
  it("は同じ日なら終わりの時刻だけ付ける", () => {
    expect(exportBaseName(epoch("2026-10-15T09:00:00+09:00"), epoch("2026-10-15T10:30:00+09:00")))
      .toBe("lifelog-20261015_0900-1030");
  });

  it("は日をまたぐと終わりの日付も付ける", () => {
    expect(exportBaseName(epoch("2026-10-15T00:00:00+09:00"), epoch("2026-10-16T00:00:00+09:00")))
      .toBe("lifelog-20261015_0000-20261016_0000");
  });
});

describe("transcriptText", () => {
  const from = epoch("2026-10-15T09:00:00+09:00");
  const to = epoch("2026-10-15T10:00:00+09:00");

  it("は1行1セグメントで JST の時刻を付ける", () => {
    const text = transcriptText([
      seg({}),
      seg({ segment_id: "seg00002", started_epoch: epoch("2026-10-15T09:31:00+09:00"), transcript: "一行目\n二行目" }),
    ], from, to);
    expect(text.split("\n")).toEqual([
      "# 2026-10-15 09:00 〜 2026-10-15 10:00（JST）の文字起こし 2件",
      "2026-10-15 09:30:00  こんにちは",
      "2026-10-15 09:31:00  一行目 二行目",
      "",
    ]);
  });

  it("は定型句・空・未処理を入れない", () => {
    const text = transcriptText([
      seg({ stock_phrase: 1, transcript: "ご視聴ありがとうございました" }),
      seg({ transcript: "  " }),
      seg({ status: "pending", transcript: null }),
    ], from, to);
    expect(text).toBe("# 2026-10-15 09:00 〜 2026-10-15 10:00（JST）の文字起こし 0件\n");
  });
});

describe("zipStream", () => {
  it("は中身を無圧縮で入れ、読み戻せる", async () => {
    const big = new Uint8Array(200_000).map((_, i) => i % 251);
    const zip = await readAll(zipStream([
      { name: "transcript.txt", epoch: epoch("2026-10-15T09:00:00+09:00"), open: async () => new TextEncoder().encode("あ\n") },
      {
        name: "20261015_093000_seg00001.wav",
        epoch: epoch("2026-10-15T09:30:10+09:00"),
        open: async () => new ReadableStream({
          start(c) { c.enqueue(big.subarray(0, 70_000)); c.enqueue(big.subarray(70_000)); c.close(); },
        }),
      },
    ]));
    const files = unzip(zip);
    expect(files.map((f) => f.name)).toEqual(["transcript.txt", "20261015_093000_seg00001.wav"]);
    expect(new TextDecoder().decode(files[0].data)).toBe("あ\n");
    expect(files[1].data).toEqual(big);
    // JST の壁時計: 09:30:10 → 時 9、分 30、秒/2 = 5。日付 2026-10-15。
    expect(files[1].time).toBe((9 << 11) | (30 << 5) | 5);
    expect(files[1].date).toBe(((2026 - 1980) << 9) | (10 << 5) | 15);
  });

  it("は読めなかったファイルを飛ばす", async () => {
    const files = unzip(await readAll(zipStream([
      { name: "a.wav", epoch: 0, open: async () => null },
      { name: "b.wav", epoch: epoch("2026-10-15T00:00:00Z"), open: async () => new Uint8Array([1, 2, 3]) },
    ])));
    expect(files.map((f) => f.name)).toEqual(["b.wav"]);
  });

  it("は空でも正しい ZIP にする", async () => {
    expect(unzip(await readAll(zipStream([])))).toEqual([]);
  });
});
