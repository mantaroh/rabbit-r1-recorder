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

<p>
  <button id="go" disabled>取り込む</button>
  <span id="progress" class="muted"></span>
</p>

<div id="log"></div>

<script>
// 純粋関数は src/import-time.ts と src/import-id.ts の実体をそのまま埋め込む。
// 実装を二重に持つと、片方だけ直したときに黙ってずれる。
const frameTimes = ${frameTimes.toString()};
const photoIdFor = ${photoIdFor.toString()};

const $ = (id) => document.getElementById(id);
const log = (s) => { $('log').textContent += s + '\\n'; };

// 表の行と同じ順序の配列。行の change ハンドラが dataset.i で引き当てるので、
// 並び順が識別子を兼ねている。
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
  const n = Math.round(Number($('interval').value));
  const clamped = Number.isFinite(n) ? Math.min(3600, Math.max(1, n)) : 30;
  // 画面の数字と計算に使う数字を一致させる。黙って別の値で計算すると、
  // あとで photo-retime に同じ interval_sec を渡しても再現しない取り込みが
  // できてしまう。
  if (String(clamped) !== $('interval').value) $('interval').value = String(clamped);
  return clamped;
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
 * duration alone cannot give the frame count, so playback is sampled. The
 * divisor is the presentedFrames delta, not the number of callbacks: two
 * consecutive rVFC calls are NOT guaranteed to be consecutive frames, and
 * dividing by an assumed 1 makes a skipped frame read as half the true rate.
 * Half the rate means half the frames imported, with nothing on screen to say
 * so — the one failure here that looks like success.
 *
 * Averaged over ten frames rather than two, so a single irregular callback
 * cannot set the answer. Short clips end before ten: the ended handler settles
 * from whatever was gathered. If nothing usable was gathered, null, and the
 * caller assumes 25 and says so on screen.
 */
function measureFps(video) {
  return new Promise((resolve) => {
    if (!video.requestVideoFrameCallback) return resolve(null);

    let first = null;
    let last = null;

    const fromSamples = () => {
      if (!first || !last) return null;
      const frames = last.frames - first.frames;
      const seconds = last.at - first.at;
      if (!(frames > 0) || !(seconds > 0)) return null;
      const fps = frames / seconds;
      return Number.isFinite(fps) && fps > 0 ? fps : null;
    };

    const finish = (value) => {
      clearTimeout(bail);
      video.removeEventListener('ended', onEnded);
      video.pause();
      resolve(value);
    };

    const onEnded = () => finish(fromSamples());
    const bail = setTimeout(() => finish(fromSamples()), 4000);

    const tick = (_now, meta) => {
      if (first === null) {
        first = { at: meta.mediaTime, frames: meta.presentedFrames };
        video.requestVideoFrameCallback(tick);
        return;
      }
      last = { at: meta.mediaTime, frames: meta.presentedFrames };
      if (last.frames - first.frames >= 10) return finish(fromSamples());
      video.requestVideoFrameCallback(tick);
    };

    video.addEventListener('ended', onEnded);
    video.requestVideoFrameCallback(tick);
    video.play().catch(() => finish(null));
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
  // photoIdFor はフレーム番号を4桁で持つので 9999 が上限。そこで落ちるより、
  // 押す前に画面で止めたほうがいい。1万枚は 30 秒間隔なら 83 時間ぶんで、
  // タイムラプスではない何かが紛れ込んだ合図でもある。
  if (count > 10000) {
    throw new Error(count + ' フレームは多すぎます（タイムラプスではない可能性）');
  }
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

  $('go').disabled = !analysed.some((a) => !a.error);
}

/** Seeks and waits, because drawImage before the seek lands gives the old frame. */
function seekTo(video, t) {
  return new Promise((resolve, reject) => {
    // seeked が来ない経路が実在する。currentTime に現在と同じ値を代入すると
    // ブラウザがシーク自体を省くことがあり、待ち続けると取り込みが無言で
    // 固まる。ボタンは無効のまま、画面には何も出ない。止まるなら、止まったと
    // 見えるほうがよい。
    const bail = setTimeout(() => {
      cleanup();
      reject(new Error('シークが ' + t.toFixed(3) + ' 秒で応答しませんでした'));
    }, 10000);
    const ok = () => { cleanup(); resolve(); };
    const ng = () => { cleanup(); reject(new Error('シークに失敗しました')); };
    const cleanup = () => {
      clearTimeout(bail);
      video.removeEventListener('seeked', ok);
      video.removeEventListener('error', ng);
    };
    video.addEventListener('seeked', ok);
    video.addEventListener('error', ng);
    video.currentTime = t;
  });
}

function toJpeg(canvas) {
  return new Promise((resolve, reject) => {
    canvas.toBlob(
      (b) => (b ? resolve(b) : reject(new Error('JPEG に変換できませんでした'))),
      'image/jpeg',
      0.85,
    );
  });
}

async function putFrame(blob, id, takenAt, sourceFile) {
  const q = new URLSearchParams({
    taken_at: takenAt,
    device_id: 'glasses',
    facing: 'rear',
    source: 'glasses',
    source_file: sourceFile,
  });
  const r = await fetch('/v1/photos/' + id + '?' + q.toString(), {
    method: 'PUT',
    body: blob,
  });
  if (!r.ok) throw new Error('PUT ' + id + ' が ' + r.status + ' を返しました');
  return r.json();
}

/**
 * One file, frame by frame.
 *
 * Sequential rather than parallel. The video element has one playback
 * position, so two seeks at once would race for it; the upload is the fast
 * part here, not the bottleneck.
 */
async function importFile(a, onStep) {
  // videoWidth が 0 のまま描くと、drawImage は例外も投げずに何も描かず、
  // 真っ黒な JPEG が 200 で入り続ける。成功に見える失敗なので、ここで止める。
  const width = a.video.videoWidth;
  const height = a.video.videoHeight;
  if (!width || !height) {
    throw new Error('映像の大きさが取れませんでした（コーデック非対応の可能性）');
  }

  const canvas = document.createElement('canvas');
  canvas.width = width;
  canvas.height = height;
  const ctx = canvas.getContext('2d');

  let stored = 0;
  let skipped = 0;

  for (let i = 0; i < a.count; i++) {
    // フレームの境目を踏まないよう中央を狙う。
    const raw = (i + 0.5) / a.fps;
    if (raw > a.video.duration) break;
    // count は duration × fps の四捨五入なので、最後の1枚の中央が duration に
    // ちょうど重なることがある。そのまま渡すとブラウザが末尾にクランプし、
    // 現在位置と同じ値になって seeked が飛ばない。わずかに内側を狙う。
    const t = Math.min(raw, Math.max(0, a.video.duration - 0.001));

    await seekTo(a.video, t);
    // 前のフレームを消してから描く。drawImage が何も描かなかったとき、
    // 残像が次の id でアップロードされるのを防ぐ。
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    ctx.drawImage(a.video, 0, 0, canvas.width, canvas.height);
    const blob = await toJpeg(canvas);
    const answer = await putFrame(blob, photoIdFor(a.hash, i), a.times[i], a.hash);
    if (answer.duplicate) skipped += 1;
    else stored += 1;
    onStep(i + 1, a.count);
  }

  return { stored, skipped };
}

$('go').addEventListener('click', async () => {
  $('go').disabled = true;
  $('pick').disabled = true;
  let failed = 0;
  try {
    for (const a of analysed) {
      if (a.error) { log(a.file.name + ' — 飛ばしました（' + a.error + '）'); continue; }
      log(a.file.name + ' — ' + a.count + ' 枚');
      try {
        const r = await importFile(a, (n, total) => {
          $('progress').textContent = a.file.name + ' ' + n + ' / ' + total;
        });
        log('  取り込み ' + r.stored + ' 枚、既出 ' + r.skipped + ' 枚');
      } catch (err) {
        // 1本の失敗で残りを巻き添えにしない。カード1枚に記録が何本も入って
        // いて、そのうち1本だけ壊れている、というのは普通に起きる。
        failed += 1;
        log('  中断: ' + String((err && err.message) || err));
        log('  もう一度押せば、入っているぶんは飛ばして続きから入ります。');
      }
    }
    $('progress').textContent = failed
      ? failed + ' 本が途中で止まりました'
      : '完了';
  } finally {
    $('go').disabled = false;
    $('pick').disabled = false;
  }
});

function esc(s) {
  return String(s).replace(/[&<>"]/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' }[c]));
}
</script>
</body></html>`;
