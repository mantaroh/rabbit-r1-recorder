/**
 * The browsing surface for the archive.
 *
 * One file, no build step, no dependencies. The Worker is the whole backend
 * and this is the whole frontend; a bundler between them would be more moving
 * parts than the thing itself has.
 *
 * Everything is a day. The archive is a chronology, and the question it gets
 * asked is nearly always "what happened on this day, around this time" —
 * search exists for the times it is not.
 */
export const UI_HTML = `<!doctype html>
<html lang="ja">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>R1 lifelog</title>
<meta name="theme-color" content="#14161a">
<!--
  Five bars, tall in the middle and short at the ends: the loudness of one
  utterance from the first word to the last. It is the signal this system
  actually stores — one byte per second, the thing the VAD reads and the thing
  rejudge re-reads — rather than a picture of a microphone.

  Drawn as a filled orange tile with the bars knocked out of it, because a
  16 px favicon is read by its silhouette and thin strokes on a transparent
  ground disappear into a dark tab strip. The orange is the R1's own, which is
  what ties this page to the object that filled it.

  Inline as a data URI: the page is one file with no build step and no external
  requests, and a favicon is not a good enough reason to break either. The
  colour hashes have to be %23 or the URI ends at the first one.
-->
<link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'><rect width='16' height='16' rx='3' fill='%23fe5000'/><g fill='%2314161a'><rect x='1' y='5' width='2' height='6'/><rect x='4' y='3' width='2' height='11'/><rect x='7' y='1' width='2' height='14'/><rect x='10' y='4' width='2' height='9'/><rect x='13' y='6' width='2' height='5'/></g></svg>">
<style>
  :root {
    color-scheme: dark;
    --bg: #14161a;
    --panel: #1c1f25;
    --line: #2a2f37;
    --text: #e6e8ec;
    --dim: #8b93a1;
    --accent: #7fd1a0;
    --warn: #d2a24c;
  }
  * { box-sizing: border-box; }
  body {
    margin: 0;
    background: var(--bg);
    color: var(--text);
    font: 15px/1.6 system-ui, -apple-system, "Hiragino Sans", "Noto Sans JP", sans-serif;
  }
  header {
    position: sticky; top: 0; z-index: 10;
    background: var(--panel);
    border-bottom: 1px solid var(--line);
    padding: 10px 14px;
    display: flex; flex-wrap: wrap; gap: 8px; align-items: center;
  }
  h1 { font-size: 15px; margin: 0 8px 0 0; color: var(--accent); font-weight: 600; }
  input, button, select {
    background: #23272f; color: var(--text);
    border: 1px solid var(--line); border-radius: 6px;
    padding: 6px 10px; font: inherit;
  }
  button { cursor: pointer; }
  button:hover { border-color: var(--accent); }
  #search { flex: 1; min-width: 160px; }
  main { padding: 14px; max-width: 900px; margin: 0 auto; }
  .stats { color: var(--dim); font-size: 13px; margin-bottom: 12px; }
  .entry {
    display: grid; grid-template-columns: 62px 1fr; gap: 12px;
    padding: 10px 0; border-bottom: 1px solid var(--line);
  }
  .time { color: var(--dim); font-variant-numeric: tabular-nums; font-size: 13px; }
  .text { white-space: pre-wrap; word-break: break-word; }
  .muted { color: var(--dim); font-style: italic; }
  .shots { display: flex; flex-wrap: wrap; gap: 10px; }
  .shot { width: 210px; }
  .shot img {
    width: 100%; aspect-ratio: 3 / 4;
    border-radius: 6px; border: 1px solid var(--line);
    display: block; background: #000; cursor: zoom-in; object-fit: cover;
  }
  .shot .cap { font-size: 12px; color: var(--dim); margin-top: 4px; }
  .facing { font-size: 11px; color: var(--warn); }
  audio { width: 100%; max-width: 340px; height: 32px; margin-top: 6px; }
  .empty { color: var(--dim); padding: 30px 0; text-align: center; }
  .tab.on { border-color: var(--accent); color: var(--accent); }
  /* Sized in viewport units: a map that has to be scrolled to is a map you
     navigate twice. */
  #map { height: calc(100vh - 150px); min-height: 320px; border-radius: 8px; overflow: hidden; }
  #map .maplibregl-ctrl-attrib { font-size: 11px; }
  dialog {
    border: none; background: transparent; max-width: 96vw; max-height: 96vh; padding: 0;
  }
  dialog img { max-width: 96vw; max-height: 96vh; border-radius: 8px; }
  dialog::backdrop { background: rgba(0,0,0,.85); }
</style>
</head>
<body>
<header>
  <h1>R1 lifelog</h1>
  <button id="prev" title="前の日">‹</button>
  <input type="date" id="date">
  <button id="next" title="次の日">›</button>
  <input id="search" placeholder="全期間を検索（音声・写真）">
  <label style="color:var(--dim);font-size:13px">
    <input type="checkbox" id="showQuiet"> 無音も表示
  </label>
  <button class="tab on" id="tabList">記録</button>
  <button class="tab" id="tabMap">地図</button>
</header>
<main>
  <div class="stats" id="stats">読み込み中…</div>
  <div id="list"></div>
  <div id="map" hidden></div>
</main>
<dialog id="viewer"><img id="viewerImg" alt=""></dialog>

<script>
const $ = (id) => document.getElementById(id);
const pad = (n) => String(n).padStart(2, '0');
const todayJst = () => {
  // The device stamps everything +09:00; the browser may be anywhere.
  const now = new Date(Date.now() + 9 * 3600e3);
  return now.toISOString().slice(0, 10);
};

let showQuiet = false;

function timeOf(iso) {
  const m = /T(\\d{2}):(\\d{2})/.exec(iso || '');
  return m ? m[1] + ':' + m[2] : '';
}

function escapeHtml(s) {
  return (s || '').replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}

function renderEntry(e) {
  if (e.kind === 'photo') {
    // width/height and the matching aspect-ratio in CSS are what make
    // loading="lazy" work: without them the browser lays every image out at
    // zero height, decides the whole day is above the fold, and fetches all
    // 160 frames at once. Measured doing exactly that.
    const shots = e.shots.map((s) => \`
      <div class="shot">
        <img loading="lazy" decoding="async" width="480" height="640"
             src="/v1/media/photo/\${encodeURIComponent(s.id)}"
             data-full="/v1/media/photo/\${encodeURIComponent(s.id)}" alt="">
        <div class="facing">\${s.facing === 'front' ? '前' : '後'}</div>
        <div class="cap">\${escapeHtml(s.caption) || '<span class="muted">キャプションなし</span>'}</div>
      </div>\`).join('');
    return \`<div class="entry"><div class="time">\${timeOf(e.at)}</div>
      <div><div class="shots">\${shots}</div></div></div>\`;
  }

  const body = e.text
    ? \`<div class="text">\${escapeHtml(e.text)}</div>\`
    : \`<div class="muted">\${e.status === 'silent' ? '無音（文字起こしなし）' : '発話なし'}</div>\`;
  return \`<div class="entry"><div class="time">\${timeOf(e.at)}</div>
    <div>\${body}
      <audio preload="none" controls src="/v1/media/audio/\${encodeURIComponent(e.id)}"></audio>
    </div></div>\`;
}

function draw(entries, statsText) {
  $('stats').textContent = statsText;
  const visible = entries.filter((e) => e.kind === 'photo' || showQuiet || e.text);
  $('list').innerHTML = visible.length
    ? visible.map(renderEntry).join('')
    : '<div class="empty">この日の記録はありません</div>';
}

async function loadDay(date) {
  $('stats').textContent = '読み込み中…';
  const r = await fetch('/v1/day?date=' + encodeURIComponent(date));
  if (!r.ok) { $('stats').textContent = '読み込み失敗 (' + r.status + ')'; return; }
  const d = await r.json();
  window.__entries = d.entries;
  draw(d.entries,
    \`\${d.stats.segments} 分の音声 · 発話 \${d.stats.with_text} · 写真 \${d.stats.photos}\`);
}

async function runSearch(q) {
  $('stats').textContent = '検索中…';
  const r = await fetch('/v1/search?q=' + encodeURIComponent(q) + '&limit=100');
  if (!r.ok) { $('stats').textContent = '検索失敗 (' + r.status + ')'; return; }
  const d = await r.json();
  const entries = (d.results || []).map((x) => ({
    kind: 'audio', at: x.started_at, id: x.segment_id, text: x.transcript, status: 'transcribed',
  }));
  window.__entries = entries;
  draw(entries, \`「\${q}」: \${entries.length} 件\`);
}

$('date').value = todayJst();
// Whichever view is showing follows the date; changing the day should not also
// change what you were looking at.
const reload = () => showTab($('map').hidden ? 'list' : 'map');
$('date').addEventListener('change', reload);
$('prev').addEventListener('click', () => shiftDay(-1));
$('next').addEventListener('click', () => shiftDay(1));
$('showQuiet').addEventListener('change', (e) => {
  showQuiet = e.target.checked;
  if (window.__entries) draw(window.__entries, $('stats').textContent);
});
$('search').addEventListener('keydown', (e) => {
  if (e.key !== 'Enter') return;
  const q = e.target.value.trim();
  if (q) runSearch(q); else loadDay($('date').value);
});

function shiftDay(delta) {
  const d = new Date($('date').value + 'T00:00:00Z');
  d.setUTCDate(d.getUTCDate() + delta);
  $('date').value = d.toISOString().slice(0, 10);
  reload();
}

// ------------------------------------------------------------------ map ---
// Everything the map needs is served from this origin: the PMTiles archive,
// the style, MapLibre and pmtiles.js all live in R2. The distributor of the
// archive asks that its URLs not be referenced from an application, and a
// private log has no business announcing itself to a CDN either.
//
// Loaded on first use, not on page load. The renderer is about a megabyte and
// most visits to this page are to read what was said, not to see where.

let mapPromise = null;
let mapView = null;

function loadOnce(tag, attrs) {
  return new Promise((resolve, reject) => {
    const el = document.createElement(tag);
    Object.assign(el, attrs);
    el.onload = resolve;
    el.onerror = () => reject(new Error('could not load ' + (attrs.src || attrs.href)));
    document.head.appendChild(el);
  });
}

function ensureMap() {
  if (mapPromise) return mapPromise;
  mapPromise = (async () => {
    await loadOnce('link', { rel: 'stylesheet', href: '/v1/map/lib/maplibre-gl.css' });
    await loadOnce('script', { src: '/v1/map/lib/maplibre-gl.js' });
    await loadOnce('script', { src: '/v1/map/lib/pmtiles.js' });

    // PMTiles is one file read by byte offset; this teaches MapLibre to ask
    // for ranges of it instead of for tile URLs that do not exist.
    maplibregl.addProtocol('pmtiles', new pmtiles.Protocol().tile);

    mapView = new maplibregl.Map({
      container: 'map',
      style: '/v1/map/style.json',
      center: [139.767, 35.681],
      zoom: 9,
    });
    mapView.addControl(new maplibregl.NavigationControl({ showCompass: false }));
    await new Promise((done) => mapView.on('load', done));

    mapView.addSource('track', { type: 'geojson', data: emptyTrack() });
    // The line first so the points sit on top of it.
    mapView.addLayer({
      id: 'track-line', type: 'line', source: 'track',
      filter: ['==', '$type', 'LineString'],
      paint: { 'line-color': '#7fd1a0', 'line-width': 3, 'line-opacity': 0.85 },
    });
    mapView.addLayer({
      id: 'track-points', type: 'circle', source: 'track',
      filter: ['==', '$type', 'Point'],
      paint: {
        // Radius from reported accuracy, not a constant. A 2 km fix and a 5 m
        // fix drawn the same size turn a walk down a street into a walk
        // through the buildings beside it.
        'circle-radius': ['interpolate', ['linear'], ['get', 'accuracy'], 5, 4, 100, 8, 2000, 16],
        'circle-color': ['case', ['>', ['get', 'accuracy'], 200], '#d2a24c', '#7fd1a0'],
        'circle-opacity': 0.5,
        'circle-stroke-color': '#14161a',
        'circle-stroke-width': 1,
      },
    });
    return mapView;
  })();
  return mapPromise;
}

const emptyTrack = () => ({ type: 'FeatureCollection', features: [] });

async function loadTrack(date) {
  const map = await ensureMap();
  const r = await fetch('/v1/positions?date=' + encodeURIComponent(date));
  if (!r.ok) { $('stats').textContent = '位置の読み込み失敗 (' + r.status + ')'; return; }
  const d = await r.json();

  const fixes = d.positions || [];
  const features = fixes.map((p) => ({
    type: 'Feature',
    geometry: { type: 'Point', coordinates: [p.lon, p.lat] },
    properties: { accuracy: p.accuracy_m == null ? 50 : p.accuracy_m, at: p.recorded_at },
  }));

  // One line through the fixes, in time order. Straight segments between
  // points five minutes apart are a lie about the route taken, so this is
  // drawn as what it is — a sequence of sightings, with the points visible.
  if (fixes.length > 1) {
    features.push({
      type: 'Feature',
      geometry: { type: 'LineString', coordinates: fixes.map((p) => [p.lon, p.lat]) },
      properties: {},
    });
  }

  map.getSource('track').setData({ type: 'FeatureCollection', features });
  $('stats').textContent = fixes.length
    ? d.date + ' の測位 ' + fixes.length + ' 件'
    : d.date + ' の測位はありません（屋内では GPS が入りません）';

  if (fixes.length) {
    const bounds = fixes.reduce(
      (b, p) => b.extend([p.lon, p.lat]),
      new maplibregl.LngLatBounds([fixes[0].lon, fixes[0].lat], [fixes[0].lon, fixes[0].lat]),
    );
    map.fitBounds(bounds, { padding: 40, maxZoom: 16, duration: 0 });
  }
}

function showTab(which) {
  const map = which === 'map';
  $('tabMap').classList.toggle('on', map);
  $('tabList').classList.toggle('on', !map);
  $('list').hidden = map;
  $('map').hidden = !map;
  if (map) {
    loadTrack($('date').value).catch((e) => {
      $('stats').textContent = '地図を読み込めません: ' + e.message;
    });
  } else {
    loadDay($('date').value);
  }
}

$('tabMap').addEventListener('click', () => showTab('map'));
$('tabList').addEventListener('click', () => showTab('list'));

document.addEventListener('click', (e) => {
  const img = e.target.closest('.shot img');
  if (!img) return;
  $('viewerImg').src = img.dataset.full;
  $('viewer').showModal();
});
$('viewer').addEventListener('click', () => $('viewer').close());

loadDay($('date').value);
</script>
</body>
</html>`;
