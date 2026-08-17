/**
 * The map, self-hosted.
 *
 * The PMTiles distributor asks that its URLs not be referenced from an
 * application — "本サイトのPMTiles URLをアプリから直接参照する利用はご遠慮
 * ください" — and offers the archive for download instead. So the archive is
 * pulled once into R2 and served from here, which is what was asked for and
 * also what this system wants anyway: no third-party host in the path of a
 * private archive, no API key, no per-request billing, and R2 charges nothing
 * for egress.
 *
 * The renderer is mirrored on the same terms. MapLibre and pmtiles.js would
 * ordinarily come from a CDN, and a page that is otherwise one file with no
 * external requests should not start making two of them to a third party every
 * time it is opened.
 *
 * PMTiles is read by HTTP range request — that is the whole point of the
 * format, one file addressed by byte offset — and the Worker already answers
 * ranges out of R2 for audio scrubbing. Same mechanism, different bytes.
 */

interface MapEnv {
  BUCKET: R2Bucket;
}

/** Where the mirrored copies live in the bucket. */
const PREFIX = "map/";

/**
 * Everything the map needs, with its source fixed here.
 *
 * A hardcoded list rather than a URL parameter. An endpoint that fetches
 * whatever it is told and stores the result is a server-side request forgery
 * with a bucket attached, and this one is reachable with the same token the
 * device carries.
 */
export const MAP_ASSETS: Array<{
  key: string;
  url: string;
  contentType: string;
  /** Pulled in parts rather than one stream; see fetchAsset. */
  large?: boolean;
}> = [
  {
    key: "japan-20260806.pmtiles",
    url: "https://armd-01.sakura.ne.jp/tiles/japan-20260806.pmtiles",
    contentType: "application/octet-stream",
    large: true,
  },
  {
    key: "style.json",
    url: "https://armd-01.sakura.ne.jp/tiles/japan-20260806.json",
    contentType: "application/json; charset=utf-8",
  },
  {
    key: "lib/maplibre-gl.js",
    url: "https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.js",
    contentType: "text/javascript; charset=utf-8",
  },
  {
    key: "lib/maplibre-gl.css",
    url: "https://unpkg.com/maplibre-gl@5.24.0/dist/maplibre-gl.css",
    contentType: "text/css; charset=utf-8",
  },
  {
    key: "lib/pmtiles.js",
    url: "https://unpkg.com/pmtiles@4.4.1/dist/pmtiles.js",
    contentType: "text/javascript; charset=utf-8",
  },

  // The icon sheet. Four small files, so they are mirrored outright rather
  // than pulled through like the fonts. MapLibre appends these suffixes to the
  // style's `sprite` value itself, which is why the keys look like this.
  {
    key: "sprite.json",
    url: "https://tile.openstreetmap.jp/styles/osm-bright/sprite.json",
    contentType: "application/json; charset=utf-8",
  },
  {
    key: "sprite.png",
    url: "https://tile.openstreetmap.jp/styles/osm-bright/sprite.png",
    contentType: "image/png",
  },
  {
    key: "sprite@2x.json",
    url: "https://tile.openstreetmap.jp/styles/osm-bright/sprite@2x.json",
    contentType: "application/json; charset=utf-8",
  },
  {
    key: "sprite@2x.png",
    url: "https://tile.openstreetmap.jp/styles/osm-bright/sprite@2x.png",
    contentType: "image/png",
  },
];

/** Where the glyph ranges come from, once each. */
const FONT_ORIGIN = "https://tile.openstreetmap.jp/fonts";

/**
 * The fontstacks this style asks for, taken from its own layers.
 *
 * An allowlist rather than a passthrough: without it, `/v1/map/fonts/<anything>`
 * would fetch and store whatever it was given, which is the same
 * server-side-request-forgery-with-a-bucket that the asset list exists to
 * avoid.
 */
const FONT_STACKS = new Set([
  "Noto Sans Regular",
  "Noto Sans Bold",
  "Noto Sans Italic",
  "Roboto Regular,Noto Sans Regular",
  "Roboto Medium,Noto Sans Regular",
  "Roboto Condensed Italic,Noto Sans Italic",
]);

/**
 * 32 MiB. R2 wants every part but the last to be the same size and at least
 * 5 MiB; a Worker has 128 MB of memory and holds one part at a time, so this
 * leaves room to be wrong about the overhead.
 */
const PART_BYTES = 32 * 1024 * 1024;

/**
 * Copies the sources into R2. One-time, and safe to re-run.
 *
 * `?only=` does a single asset, which is how a 1.5 GB archive gets retried
 * without re-fetching the four small files beside it. `?force=1` overwrites
 * something already present.
 */
export async function fetchMapAssets(env: MapEnv, url: URL): Promise<Response> {
  const only = url.searchParams.get("only");
  const force = url.searchParams.get("force") === "1";
  const wanted = only ? MAP_ASSETS.filter((a) => a.key === only) : MAP_ASSETS;

  if (!wanted.length) {
    return json({ error: "unknown asset", known: MAP_ASSETS.map((a) => a.key) }, 400);
  }

  const results: unknown[] = [];
  for (const asset of wanted) {
    const key = PREFIX + asset.key;
    if (!force) {
      const existing = await env.BUCKET.head(key);
      if (existing) {
        results.push({ key: asset.key, skipped: "already stored", bytes: existing.size });
        continue;
      }
    }
    try {
      const bytes = asset.large
        ? await fetchLarge(env, key, asset.url, asset.contentType)
        : await fetchSmall(env, key, asset.url, asset.contentType);
      results.push({ key: asset.key, stored: bytes });
    } catch (error) {
      results.push({ key: asset.key, error: String(error).slice(0, 300) });
    }
  }

  return json({ assets: results });
}

async function fetchSmall(
  env: MapEnv,
  key: string,
  source: string,
  contentType: string,
): Promise<number> {
  const response = await fetch(source);
  if (!response.ok) throw new Error(`source returned ${response.status}`);
  let body: ArrayBuffer | string = await response.arrayBuffer();

  // The distributed style points at the archive sitting beside it. Ours does
  // not sit beside it, so the one reference that would reach back to the
  // original host is rewritten on the way in — otherwise every map view would
  // hotlink the thing we went to the trouble of mirroring.
  if (key.endsWith("style.json")) {
    const text = new TextDecoder().decode(body as ArrayBuffer);
    body = text
      .replace(/pmtiles:\/\/[^"']+\.pmtiles/g, "pmtiles:///v1/map/japan-20260806.pmtiles")
      // Fonts and icons too, or the page would still call a third party on
      // every open — which is the thing mirroring the archive was for.
      .replace(/https:\/\/tile\.openstreetmap\.jp\/fonts/g, "/v1/map/fonts")
      .replace(/https:\/\/tile\.openstreetmap\.jp\/styles\/osm-bright\/sprite/g, "/v1/map/sprite");
  }

  await env.BUCKET.put(key, body, { httpMetadata: { contentType } });
  return typeof body === "string" ? body.length : body.byteLength;
}

/**
 * Ranged multipart, because 1.5 GB through one stream is a single failure that
 * loses the whole transfer, and because a part at a time is a bounded amount of
 * memory rather than a hope about how the runtime buffers.
 */
async function fetchLarge(
  env: MapEnv,
  key: string,
  source: string,
  contentType: string,
): Promise<number> {
  const head = await fetch(source, { method: "HEAD" });
  if (!head.ok) throw new Error(`source HEAD returned ${head.status}`);
  const total = Number(head.headers.get("content-length") ?? 0);
  if (!total) throw new Error("source did not report a length");
  if (head.headers.get("accept-ranges") !== "bytes") {
    throw new Error("source does not accept ranges");
  }

  const upload = await env.BUCKET.createMultipartUpload(key, {
    httpMetadata: { contentType },
  });

  try {
    const parts: R2UploadedPart[] = [];
    for (let offset = 0, n = 1; offset < total; offset += PART_BYTES, n += 1) {
      const end = Math.min(offset + PART_BYTES, total) - 1;
      const chunk = await fetch(source, { headers: { Range: `bytes=${offset}-${end}` } });
      if (chunk.status !== 206) throw new Error(`part ${n}: expected 206, got ${chunk.status}`);
      parts.push(await upload.uploadPart(n, await chunk.arrayBuffer()));
    }
    await upload.complete(parts);
    return total;
  } catch (error) {
    // Leaves no half-written object behind. A retry starts clean rather than
    // finding a truncated archive that reads as a map of a smaller country.
    await upload.abort().catch(() => {});
    throw error;
  }
}

/**
 * Serves a mirrored asset, honouring ranges.
 *
 * Ranges are not an optimisation here. PMTiles is one file that a client reads
 * by byte offset — refusing ranges would mean sending 1.5 GB to draw one
 * street.
 */
export async function serveMap(
  request: Request,
  env: MapEnv,
  url: URL,
): Promise<Response> {
  const path = url.pathname.slice("/v1/map/".length);

  if (path.startsWith("fonts/")) return serveGlyphs(env, path);

  // No traversal, and no browsing: only the keys this module put there.
  if (!MAP_ASSETS.some((a) => a.key === path)) {
    return json({ error: "not a map asset", known: MAP_ASSETS.map((a) => a.key) }, 404);
  }

  const range = request.headers.get("range");
  const object = await env.BUCKET.get(PREFIX + path, range ? { range: request.headers } : undefined);
  if (!object) {
    return json({ error: "not mirrored yet", hint: "POST /v1/admin/map-fetch" }, 404);
  }

  const headers = new Headers();
  object.writeHttpMetadata(headers);
  headers.set("etag", object.httpEtag);
  headers.set("accept-ranges", "bytes");
  // Immutable: the key carries the archive's date, so a new map is a new key.
  headers.set("cache-control", "private, max-age=31536000, immutable");

  if (range && object.range && "offset" in object.range) {
    const offset = object.range.offset ?? 0;
    const length = object.range.length ?? object.size - offset;
    headers.set("content-range", `bytes ${offset}-${offset + length - 1}/${object.size}`);
    return new Response(object.body, { status: 206, headers });
  }

  headers.set("content-length", String(object.size));
  return new Response(object.body, { headers });
}

/**
 * A glyph range, cached on first use.
 *
 * Pulled through rather than mirrored in bulk. There are 256 ranges per
 * fontstack and six stacks in this style — 1536 files, nearly all of them for
 * scripts this map will never draw — and fetching them all would be a
 * considerable favour to ask of a volunteer-run server for the sake of a
 * private log. A viewport asks for maybe a dozen, once, and after that they
 * are local.
 *
 * The browser never reaches the origin either way; only the first request for
 * a range does, from here.
 */
async function serveGlyphs(env: MapEnv, path: string): Promise<Response> {
  const match = /^fonts\/([^/]+)\/(\d{1,5}-\d{1,5})\.pbf$/.exec(path);
  if (!match) return json({ error: "bad glyph path" }, 400);

  const stack = decodeURIComponent(match[1]);
  const range = match[2];
  if (!FONT_STACKS.has(stack)) {
    return json({ error: "unknown fontstack", known: [...FONT_STACKS] }, 404);
  }

  const key = `${PREFIX}fonts/${stack}/${range}.pbf`;
  const headers = {
    "content-type": "application/x-protobuf",
    // A glyph range for a given font never changes.
    "cache-control": "private, max-age=31536000, immutable",
  };

  const cached = await env.BUCKET.get(key);
  if (cached) return new Response(cached.body, { headers });

  const source = `${FONT_ORIGIN}/${encodeURIComponent(stack)}/${range}.pbf`;
  const response = await fetch(source);
  if (!response.ok) {
    return json({ error: "origin refused", status: response.status }, 502);
  }

  // Buffered rather than teed: a range is tens of kilobytes, and storing a
  // half-written one because the client went away would poison the cache
  // permanently — these are never revalidated.
  const bytes = await response.arrayBuffer();
  await env.BUCKET.put(key, bytes, {
    httpMetadata: { contentType: "application/x-protobuf" },
  });
  return new Response(bytes, { headers });
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
