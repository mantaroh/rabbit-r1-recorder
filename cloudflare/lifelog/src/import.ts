/**
 * The import screen.
 *
 * Separate from the archive page on purpose. ui.ts is one template string that
 * is opened every day to read what was said; video decoding and canvas work
 * have no business loading there for the sake of something used now and then.
 * The map is lazy-loaded in ui.ts for the same reason.
 *
 * A browser cannot notice a USB drive appearing, and cannot read a file the
 * person did not hand it. So the flow is: plug the camera in, press the
 * button, pick the folder. webkitdirectory rather than showDirectoryPicker,
 * because the latter is Chromium-only and this archive is read in Firefox.
 */
import { frameTimes } from "./import-time";
import { photoIdFor } from "./import-id";

export const IMPORT_HTML = `<!doctype html>
<html lang="ja"><head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>取り込み — lifelog</title>
<style>
  :root { color-scheme: dark; }
  body { margin: 0; padding: 16px; background: #14161a; color: #e6e9ee;
         font: 15px/1.7 system-ui, sans-serif; }
  h1 { font-size: 18px; margin: 0 0 4px; }
  .sub { color: #8b95a3; font-size: 13px; margin: 0 0 20px; }
  a { color: #7fd1a0; }
  button { font: inherit; padding: 8px 16px; border-radius: 6px; border: 0;
           background: #7fd1a0; color: #14161a; font-weight: 600; cursor: pointer; }
  button:disabled { background: #3a4048; color: #8b95a3; cursor: default; }
  input[type=number], input[type=datetime-local] {
    font: inherit; background: #1c2027; color: #e6e9ee;
    border: 1px solid #3a4048; border-radius: 4px; padding: 4px 6px; }
  table { width: 100%; border-collapse: collapse; margin: 16px 0; font-size: 13px; }
  th { text-align: left; color: #8b95a3; font-weight: 500; font-size: 11px;
       letter-spacing: .08em; text-transform: uppercase;
       border-bottom: 1px solid #3a4048; padding: 0 8px 6px 0; }
  td { padding: 8px 8px 8px 0; border-bottom: 1px solid #23272e; vertical-align: top; }
  .err { color: #d2a24c; }
  .muted { color: #8b95a3; }
  #log { font: 12px/1.6 ui-monospace, monospace; color: #8b95a3;
         white-space: pre-wrap; margin-top: 16px; }
</style>
</head><body>

<h1>眼鏡カメラの取り込み</h1>
<p class="sub">
  カメラを USB でつないでから、下のボタンで <code>DCIM</code> フォルダを選んでください。
  取り込み済みのフレームは自動で飛ばします。
  <a href="/ui">アーカイブに戻る</a>
</p>

<p>
  <input type="file" id="pick" webkitdirectory directory multiple accept="video/quicktime,.mov,.MOV">
</p>

<p>
  撮影間隔
  <input type="number" id="interval" min="1" max="3600" step="1" value="30"> 秒
</p>

<div id="files"></div>
<div id="log"></div>

<script>
// 純粋関数は src/import-time.ts と src/import-id.ts の実体をそのまま埋め込む。
// 実装を二重に持つと、片方だけ直したときに黙ってずれる。
const frameTimes = ${frameTimes.toString()};
const photoIdFor = ${photoIdFor.toString()};

const $ = (id) => document.getElementById(id);
const log = (s) => { $('log').textContent += s + '\\n'; };

/** Files keyed by their content hash, as analysed. */
let analysed = [];

$('pick').addEventListener('change', async (e) => {
  const movs = [...e.target.files].filter((f) => /\\.mov$/i.test(f.name));
  $('log').textContent = '';
  if (!movs.length) {
    $('files').innerHTML = '<p class="err">.mov が見つかりませんでした。</p>';
    return;
  }
  $('files').innerHTML = '<p class="muted">読み込み中…</p>';
  analysed = [];
  for (const file of movs) {
    try {
      analysed.push(await analyse(file));
    } catch (err) {
      analysed.push({ file, error: String(err && err.message || err) });
    }
  }
  render();
});

$('interval').addEventListener('change', () => {
  // 間隔が変わると開始時刻も全部変わる。読み直さずに引き直すだけでよい。
  for (const a of analysed) if (!a.error) a.times = timesFor(a);
  render();
});

function intervalSec() {
  const n = Number($('interval').value);
  return Number.isFinite(n) && n >= 1 ? n : 30;
}

// 間隔はカメラ側の設定で、ファイルには入っていない。毎回入れ直すのは
// 取り込みのたびに間違える機会を作るだけなので、この端末に覚えさせる。
try {
  const saved = localStorage.getItem('glasses.intervalSec');
  if (saved) $('interval').value = saved;
} catch (e) { /* プライベートウィンドウなど。既定の30秒で進む */ }

$('interval').addEventListener('change', () => {
  try { localStorage.setItem('glasses.intervalSec', String(intervalSec())); } catch (e) {}
});

function timesFor(a) {
  const startMs = a.startOverrideMs ?? null;
  if (startMs !== null) {
    const step = intervalSec() * 1000;
    return Array.from({ length: a.count }, (_, i) => new Date(startMs + i * step).toISOString());
  }
  return frameTimes(a.file.lastModified, a.count, intervalSec());
}

/** SHA-256 of the whole file, first 16 hex digits. */
async function hash16(file) {
  const digest = await crypto.subtle.digest('SHA-256', await file.arrayBuffer());
  return [...new Uint8Array(digest)]
    .map((b) => b.toString(16).padStart(2, '0')).join('').slice(0, 16);
}

/**
 * How many frames a second the file plays at.
 *
 * duration alone cannot give the frame count, so two consecutive frame
 * callbacks are timed instead. Firefox has requestVideoFrameCallback from 132.
 * If it is missing or the video refuses to play, 25 is assumed and the screen
 * says so.
 */
function measureFps(video) {
  return new Promise((resolve) => {
    if (!video.requestVideoFrameCallback) return resolve(null);
    const bail = setTimeout(() => resolve(null), 4000);
    let first = null;
    const tick = (_now, meta) => {
      if (first === null) {
        first = meta.mediaTime;
        video.requestVideoFrameCallback(tick);
        return;
      }
      const delta = meta.mediaTime - first;
      clearTimeout(bail);
      video.pause();
      resolve(delta > 0 ? 1 / delta : null);
    };
    video.requestVideoFrameCallback(tick);
    video.play().catch(() => { clearTimeout(bail); resolve(null); });
  });
}

function loadVideo(file) {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video');
    video.muted = true;
    video.playsInline = true;
    video.preload = 'auto';
    video.src = URL.createObjectURL(file);
    video.onloadedmetadata = () => resolve(video);
    video.onerror = () => reject(new Error('このブラウザでは読めない形式です'));
  });
}

async function analyse(file) {
  const video = await loadVideo(file);
  const measured = await measureFps(video);
  const fps = measured ?? 25;
  const count = Math.max(1, Math.round(video.duration * fps));
  const a = {
    file, video, fps, count,
    fpsAssumed: measured === null,
    hash: await hash16(file),
    startOverrideMs: null,
  };
  a.times = timesFor(a);
  return a;
}

const jst = (iso) =>
  new Date(iso).toLocaleString('ja-JP', { timeZone: 'Asia/Tokyo', hour12: false });

/** datetime-local wants local wall-clock with no zone, to the second. */
function toLocalInput(iso) {
  const d = new Date(iso);
  const pad = (n) => String(n).padStart(2, '0');
  return d.getFullYear() + '-' + pad(d.getMonth() + 1) + '-' + pad(d.getDate()) +
    'T' + pad(d.getHours()) + ':' + pad(d.getMinutes()) + ':' + pad(d.getSeconds());
}

function render() {
  if (!analysed.length) { $('files').innerHTML = ''; return; }
  const rows = analysed.map((a, i) => {
    if (a.error) {
      return '<tr><td>' + esc(a.file.name) + '</td>' +
             '<td colspan="4" class="err">' + esc(a.error) + '</td></tr>';
    }
    return '<tr>' +
      '<td>' + esc(a.file.name) + '<br><span class="muted">' + a.hash + '</span></td>' +
      '<td>' + a.count + ' 枚<br><span class="muted">' + a.fps.toFixed(2) + ' fps' +
        (a.fpsAssumed ? '（仮定）' : '') + '</span></td>' +
      '<td><input type="datetime-local" step="1" data-i="' + i + '" class="start" value="' +
        toLocalInput(a.times[0]) + '"></td>' +
      '<td>' + esc(jst(a.times[a.times.length - 1])) + '</td>' +
      '<td>' + Math.round(a.file.size / 1024) + ' KB</td>' +
      '</tr>';
  }).join('');

  $('files').innerHTML =
    '<table><thead><tr><th>ファイル</th><th>フレーム</th><th>開始（JST・編集可）</th>' +
    '<th>終了</th><th>サイズ</th></tr></thead><tbody>' + rows + '</tbody></table>';

  for (const input of document.querySelectorAll('.start')) {
    input.addEventListener('change', (e) => {
      const a = analysed[Number(e.target.dataset.i)];
      const ms = Date.parse(e.target.value);
      a.startOverrideMs = Number.isNaN(ms) ? null : ms;
      a.times = timesFor(a);
      render();
    });
  }
}

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}
</script>
</body></html>`;
