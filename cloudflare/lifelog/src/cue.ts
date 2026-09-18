/**
 * 一言 — the device saying one prepared line, at a moment a person chooses.
 *
 * Built for a talk. At the end of it the speaker says "Hello, World." out loud
 * and the R1 on the lectern answers. That is the whole feature, and its shape
 * comes from one fact: it gets a single attempt, in front of a room, on a
 * network nobody here controls.
 *
 * So neither the audio nor the text crosses the wire. The line is synthesised
 * in advance and compiled into the app; what travels is a number. See
 * `migrations/0016_cue.sql` for why it is a counter rather than a flag.
 *
 * This is a prop for one talk, not a feature the archive is gaining. It is
 * meant to be deleted afterwards, along with the table, the routes and the
 * device screen that reads them; nothing else in the Worker refers to any of
 * it.
 *
 * The page is written for a phone rather than for the machine driving the
 * slides. A deck in full screen owns the keyboard, so a button that needs the
 * browser focused is a button that cannot be pressed at the moment it is
 * wanted.
 */

export interface CueEnv {
  DB: D1Database;
}

/**
 * How stale the device's last poll may be before the page stops calling it
 * ready.
 *
 * The device polls every two seconds while the stage screen is up, so eight is
 * three missed polls: long enough that one dropped request does not turn the
 * indicator red thirty seconds before the talk, short enough that closing the
 * screen shows up while there is still time to reopen it.
 */
const READY_WINDOW_SECONDS = 8;

/**
 * The device asking whether the moment has come, and saying it is still there.
 *
 * One statement rather than an update and a select. This runs every two
 * seconds, and the sequence returned has to be the one that was true at the
 * instant the heartbeat was written rather than a moment either side of it.
 */
export async function cuePoll(env: CueEnv): Promise<Response> {
  const now = new Date();
  const row = await env.DB.prepare(
    `UPDATE cue SET polled_at = ?1, polled_epoch = ?2 WHERE id = 1 RETURNING seq`,
  )
    .bind(now.toISOString(), Math.floor(now.getTime() / 1000))
    .first<{ seq: number }>();

  return json({ seq: row?.seq ?? 0 });
}

/** The button. Advances the number the device is watching. */
export async function cueFire(env: CueEnv): Promise<Response> {
  const now = new Date();
  const row = await env.DB.prepare(
    `UPDATE cue SET seq = seq + 1, fired_at = ?1 WHERE id = 1 RETURNING seq`,
  )
    .bind(now.toISOString())
    .first<{ seq: number }>();

  return json({ seq: row?.seq ?? 0, fired_at: now.toISOString() });
}

/**
 * What the page shows.
 *
 * Deliberately a different route from the device's poll: a browser left
 * refreshing this must not be able to write the heartbeat and so look like a
 * device that is ready when the device is in a bag.
 */
export async function cueState(env: CueEnv): Promise<Response> {
  const row = await env.DB.prepare(
    `SELECT seq, fired_at, polled_at, polled_epoch FROM cue WHERE id = 1`,
  ).first<{
    seq: number;
    fired_at: string | null;
    polled_at: string | null;
    polled_epoch: number | null;
  }>();

  const age = row?.polled_epoch
    ? Math.max(0, Math.floor(Date.now() / 1000) - row.polled_epoch)
    : null;

  return json({
    seq: row?.seq ?? 0,
    fired_at: row?.fired_at ?? null,
    polled_at: row?.polled_at ?? null,
    age_seconds: age,
    ready: age !== null && age <= READY_WINDOW_SECONDS,
  });
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

/**
 * One button, sized for a thumb, on a page that says whether pressing it will
 * reach anything.
 *
 * No confirmation step. The press happens at a rehearsed instant in a sentence
 * and a dialog would spend it — the risk of firing early is smaller than the
 * risk of firing late, and firing early is recoverable by simply pressing
 * again once the device has finished.
 */
export const CUE_HTML = `<!doctype html>
<html lang="ja">
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>一言</title>
<style>
  :root { color-scheme: dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  body {
    margin: 0; min-height: 100dvh;
    display: flex; flex-direction: column; gap: 1.5rem;
    padding: 2rem 1.25rem calc(2rem + env(safe-area-inset-bottom));
    background: #101010; color: #f2f2f2;
    font: 16px/1.7 system-ui, -apple-system, "Hiragino Sans", sans-serif;
  }
  header { display: flex; flex-direction: column; gap: .35rem; }
  h1 { margin: 0; font-size: 1.15rem; font-weight: 600; letter-spacing: .04em; }
  .line { color: #8a8a8a; font-size: .9rem; }
  #state {
    display: flex; align-items: center; gap: .6rem;
    font-size: .95rem; font-variant-numeric: tabular-nums;
  }
  #dot { width: 11px; height: 11px; border-radius: 50%; background: #555; flex: none; }
  #dot.ready { background: #35c46a; }
  #dot.stale { background: #d24a2a; }
  button {
    flex: 1 1 auto; min-height: 45dvh;
    border: none; border-radius: 18px;
    background: #fe5000; color: #fff;
    font: 600 2.1rem/1.2 system-ui, sans-serif; letter-spacing: .06em;
    cursor: pointer; transition: transform .08s ease, background .15s ease;
  }
  button:active { transform: scale(.985); background: #d64400; }
  button:disabled { background: #3a3a3a; color: #8a8a8a; }
  button:focus-visible { outline: 3px solid #fff; outline-offset: 3px; }
  #log { color: #8a8a8a; font-size: .85rem; min-height: 1.7em; font-variant-numeric: tabular-nums; }
  @media (prefers-reduced-motion: reduce) { button { transition: none; } }
</style>

<header>
  <h1>一言</h1>
  <div class="line">「Hello, World. 素晴らしい世界が見えるようになりました。」</div>
  <div id="state"><span id="dot"></span><span id="text">確認中…</span></div>
</header>

<button id="fire" disabled>鳴らす</button>
<div id="log"></div>

<script>
  const dot = document.getElementById('dot');
  const text = document.getElementById('text');
  const fire = document.getElementById('fire');
  const log = document.getElementById('log');
  let seq = null;

  async function refresh() {
    try {
      const s = await (await fetch('/v1/cue/state', { cache: 'no-store' })).json();
      seq = s.seq;
      dot.className = s.ready ? 'ready' : 'stale';
      text.textContent = s.ready
        ? '端末は待機中（' + s.age_seconds + '秒前）'
        : (s.age_seconds === null ? '端末から応答がありません' : '応答が古い（' + s.age_seconds + '秒前）');
      // Never disabled on a stale heartbeat: the side button on the device is
      // the fallback for exactly that case, and a page that locks itself is
      // one more thing to fight on stage.
      fire.disabled = false;
    } catch (e) {
      dot.className = 'stale';
      text.textContent = '状態を取得できません';
      fire.disabled = false;
    }
  }

  fire.addEventListener('click', async () => {
    log.textContent = '送信中…';
    try {
      const r = await (await fetch('/v1/cue', { method: 'POST' })).json();
      log.textContent = '送信しました（seq ' + r.seq + '）';
    } catch (e) {
      log.textContent = '送信に失敗しました。端末のサイドボタンを押してください';
    }
    refresh();
  });

  refresh();
  setInterval(refresh, 2000);
</script>
</html>`;
