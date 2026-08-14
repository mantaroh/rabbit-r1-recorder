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
    width: 100%; border-radius: 6px; border: 1px solid var(--line);
    display: block; background: #000; cursor: zoom-in;
  }
  .shot .cap { font-size: 12px; color: var(--dim); margin-top: 4px; }
  .facing { font-size: 11px; color: var(--warn); }
  audio { width: 100%; max-width: 340px; height: 32px; margin-top: 6px; }
  .empty { color: var(--dim); padding: 30px 0; text-align: center; }
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
</header>
<main>
  <div class="stats" id="stats">読み込み中…</div>
  <div id="list"></div>
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
    const shots = e.shots.map((s) => \`
      <div class="shot">
        <img loading="lazy" src="/v1/media/photo/\${encodeURIComponent(s.id)}"
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
$('date').addEventListener('change', () => loadDay($('date').value));
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
  loadDay($('date').value);
}

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
