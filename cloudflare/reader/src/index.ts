/**
 * A feed reader, as a sub-application.
 *
 *   cron ──▶ crawl 57 feeds, conditionally ──▶ D1 (feeds, items)
 *                                               │
 *   lifelog Worker ──service binding──▶ fetch ──┤
 *        │                                      │
 *        ├─ R1 standby screen                   │
 *        └─ Hermes, over MCP ───────────────────┘
 *
 * A second Worker rather than more routes on the first one, and the reason is
 * the binding list: this one holds a database of headlines and nothing else. It
 * has no R2 bucket and no handle on the lifelog's D1, so a feed parser that
 * goes wrong cannot reach the audio archive. The lifelog Worker's own cron
 * once deleted 327 recordings; not handing a new subsystem the same reach is
 * cheaper than trusting it.
 *
 * Nothing here is authenticated. It is unroutable from the internet — no
 * custom domain, no workers.dev — so the only ways in are the cron and the
 * service binding, and the Worker on the other end of that binding sits behind
 * Cloudflare Access and its own bearer.
 */

import { parseFeed, parseFeedTitle, parseOpml, type Subscription } from "./feed";

interface Env {
  DB: D1Database;
}

/** How many feeds are in flight at once during a crawl. */
const CONCURRENCY = 8;

/**
 * Per-feed budget. A dead host that accepts a connection and then says nothing
 * is the expensive case, and there are several of those in a subscription list
 * this old.
 */
const FETCH_TIMEOUT_MS = 8_000;

/** Kept per feed. Older items stay in the table; this only bounds one parse. */
const MAX_ITEMS_PER_FEED = 50;

/**
 * A feed that has failed this many times in a row drops to one attempt a day.
 * Several of these hosts have been gone for years and there is no reason to
 * ask them hourly forever.
 */
const FAILURES_BEFORE_BACKOFF = 5;
const BACKOFF_MS = 24 * 60 * 60 * 1000;

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/feeds/latest") {
      return latest(env, url);
    }
    if (request.method === "GET" && url.pathname === "/feeds/feeds") {
      return feedList(env);
    }
    if (request.method === "GET" && url.pathname === "/feeds/search") {
      return search(env, url);
    }
    if (request.method === "POST" && url.pathname === "/feeds/import") {
      return importOpml(request, env);
    }
    if (request.method === "POST" && url.pathname === "/feeds/crawl") {
      return json(await crawl(env, intParam(url, "limit") ?? 100));
    }

    return json({ error: "not found" }, 404);
  },

  async scheduled(_event: ScheduledController, env: Env, ctx: ExecutionContext): Promise<void> {
    ctx.waitUntil(
      crawl(env, 100).then((summary) => {
        console.log("crawl", JSON.stringify(summary));
      }),
    );
  },
} satisfies ExportedHandler<Env>;

// ----------------------------------------------------------------- crawl ---

interface CrawlSummary {
  attempted: number;
  changed: number;
  unchanged: number;
  failed: number;
  newItems: number;
  skipped: number;
}

/**
 * One pass over the subscription list, least-recently-fetched first.
 *
 * Ordered that way so a run cut short still makes progress on the feeds that
 * have waited longest, rather than re-reading the same prefix every hour.
 */
async function crawl(env: Env, limit: number): Promise<CrawlSummary> {
  const now = Date.now();
  const rows = await env.DB.prepare(
    `SELECT xml_url, etag, last_modified, failures, last_fetch_at
       FROM feeds
      ORDER BY COALESCE(last_fetch_at, '') ASC
      LIMIT ?1`,
  )
    .bind(limit)
    .all<FeedRow>();

  const due = (rows.results ?? []).filter((feed) => !backedOff(feed, now));
  const summary: CrawlSummary = {
    attempted: due.length,
    changed: 0,
    unchanged: 0,
    failed: 0,
    newItems: 0,
    skipped: (rows.results?.length ?? 0) - due.length,
  };

  for (let i = 0; i < due.length; i += CONCURRENCY) {
    const batch = due.slice(i, i + CONCURRENCY);
    const results = await Promise.all(batch.map((feed) => fetchOne(env, feed, now)));
    for (const result of results) {
      if (result.outcome === "changed") summary.changed += 1;
      else if (result.outcome === "unchanged") summary.unchanged += 1;
      else summary.failed += 1;
      summary.newItems += result.newItems;
    }
  }

  return summary;
}

interface FeedRow {
  xml_url: string;
  etag: string | null;
  last_modified: string | null;
  failures: number;
  last_fetch_at: string | null;
}

function backedOff(feed: FeedRow, now: number): boolean {
  if (feed.failures < FAILURES_BEFORE_BACKOFF) return false;
  const last = feed.last_fetch_at ? Date.parse(feed.last_fetch_at) : 0;
  return Number.isFinite(last) && now - last < BACKOFF_MS;
}

async function fetchOne(
  env: Env,
  feed: FeedRow,
  now: number,
): Promise<{ outcome: "changed" | "unchanged" | "failed"; newItems: number }> {
  const at = new Date(now).toISOString();
  const headers: Record<string, string> = {
    // Named honestly. A crawler that hides what it is gives the person on the
    // other end no way to complain about it.
    "user-agent": "r1-reader/0.1 (personal feed reader; 57 feeds; hourly)",
    accept: "application/rss+xml, application/atom+xml, application/xml, text/xml;q=0.9, */*;q=0.8",
  };
  if (feed.etag) headers["if-none-match"] = feed.etag;
  if (feed.last_modified) headers["if-modified-since"] = feed.last_modified;

  let response: Response;
  try {
    response = await fetch(feed.xml_url, {
      headers,
      redirect: "follow",
      signal: AbortSignal.timeout(FETCH_TIMEOUT_MS),
    });
  } catch (error) {
    await recordFailure(env, feed.xml_url, at, 0, String(error).slice(0, 200));
    return { outcome: "failed", newItems: 0 };
  }

  if (response.status === 304) {
    await env.DB.prepare(
      `UPDATE feeds SET last_fetch_at = ?2, last_success_at = ?2, last_status = 304,
              last_error = NULL, failures = 0
        WHERE xml_url = ?1`,
    )
      .bind(feed.xml_url, at)
      .run();
    return { outcome: "unchanged", newItems: 0 };
  }

  if (!response.ok) {
    await recordFailure(env, feed.xml_url, at, response.status, null);
    return { outcome: "failed", newItems: 0 };
  }

  const xml = await response.text();
  const items = parseFeed(xml).slice(0, MAX_ITEMS_PER_FEED);

  // A 200 that parses to nothing is a failure wearing a success's clothes:
  // an HTML error page, a parked domain, a feed that moved. Recorded as one so
  // the backoff eventually catches it.
  if (items.length === 0) {
    await recordFailure(env, feed.xml_url, at, response.status, "no items parsed");
    return { outcome: "failed", newItems: 0 };
  }

  const seconds = Math.floor(now / 1000);
  const statements = items.map((item) =>
    env.DB.prepare(
      `INSERT OR IGNORE INTO items
         (item_id, xml_url, title, link, published_at, published_epoch, first_seen_epoch)
       VALUES (?1,?2,?3,?4,?5,?6,?7)`,
    ).bind(
      `${feed.xml_url}|${item.guid}`.slice(0, 900),
      feed.xml_url,
      item.title,
      item.link,
      item.publishedAt,
      item.publishedAt ? Math.floor(Date.parse(item.publishedAt) / 1000) : null,
      seconds,
    ),
  );

  const written = await env.DB.batch(statements);
  const newItems = written.reduce((total, r) => total + (r.meta?.changes ?? 0), 0);

  await env.DB.prepare(
    `UPDATE feeds
        SET etag = ?2, last_modified = ?3, last_fetch_at = ?4, last_success_at = ?4,
            last_status = ?5, last_error = NULL, failures = 0,
            title = COALESCE(NULLIF(?6, ''), title)
      WHERE xml_url = ?1`,
  )
    .bind(
      feed.xml_url,
      response.headers.get("etag"),
      response.headers.get("last-modified"),
      at,
      response.status,
      parseFeedTitle(xml) ?? "",
    )
    .run();

  return { outcome: "changed", newItems };
}

async function recordFailure(
  env: Env,
  xmlUrl: string,
  at: string,
  status: number,
  error: string | null,
): Promise<void> {
  await env.DB.prepare(
    `UPDATE feeds
        SET last_fetch_at = ?2, last_status = ?3, last_error = ?4,
            failures = failures + 1
      WHERE xml_url = ?1`,
  )
    .bind(xmlUrl, at, status, error)
    .run();
}

// ------------------------------------------------------------------ read ---

/**
 * What has appeared recently, newest first.
 *
 * Ordered by when this crawler first saw an item, not by the date the feed
 * claims. Publishers get their own dates wrong constantly — absent, in the
 * future, or identical for every entry — and first-seen is the one field that
 * cannot be got wrong, which makes it the only honest answer to "what is new".
 *
 * The published date breaks ties, and that is not a detail. Everything that
 * arrives in one crawl shares a first-seen second, so without a tie-break the
 * order falls through to insertion order and the list comes out grouped by
 * feed — the first crawl of this list returned eight consecutive headlines
 * from the same site, which is not what "latest" means. A burst from one
 * publisher does the same thing on any later run.
 */
async function latest(env: Env, url: URL): Promise<Response> {
  const limit = Math.min(intParam(url, "limit") ?? 20, 200);
  const category = url.searchParams.get("category");

  const rows = await env.DB.prepare(
    `SELECT i.title, i.link, i.published_at, i.first_seen_epoch,
            f.title AS feed_title, f.category
       FROM items i
       JOIN feeds f ON f.xml_url = i.xml_url
      WHERE (?2 IS NULL OR f.category = ?2)
      ORDER BY i.first_seen_epoch DESC, i.published_epoch DESC, i.rowid DESC
      LIMIT ?1`,
  )
    .bind(limit, category)
    .all();

  return json({ count: rows.results?.length ?? 0, items: rows.results ?? [] });
}

/** The subscription list with its health, which is most of what needs looking at. */
async function feedList(env: Env): Promise<Response> {
  const rows = await env.DB.prepare(
    `SELECT f.xml_url, f.title, f.html_url, f.category, f.last_status, f.last_error,
            f.failures, f.last_success_at,
            (SELECT COUNT(*) FROM items i WHERE i.xml_url = f.xml_url) AS items
       FROM feeds f
      ORDER BY f.failures DESC, f.category, f.title`,
  ).all<any>();

  const feeds = rows.results ?? [];
  // Surfaced rather than left to be noticed: an OPML this old has feeds in it
  // whose hosts are simply gone, and a reader that silently shows fewer
  // stories looks like a quiet week. Two counts, because they mean different
  // things — one bad fetch is weather, five in a row is a funeral.
  return json({
    count: feeds.length,
    failing: feeds.filter((f) => f.failures > 0).length,
    backed_off: feeds.filter((f) => f.failures >= FAILURES_BEFORE_BACKOFF).length,
    feeds,
  });
}

async function search(env: Env, url: URL): Promise<Response> {
  const q = (url.searchParams.get("q") ?? "").trim();
  if (!q) return json({ error: "q required" }, 400);
  const limit = Math.min(intParam(url, "limit") ?? 30, 100);

  const rows = await env.DB.prepare(
    `SELECT i.title, i.link, i.published_at, i.first_seen_epoch, f.title AS feed_title
       FROM items i
       JOIN feeds f ON f.xml_url = i.xml_url
      WHERE i.title LIKE ?1 ESCAPE '\\'
      ORDER BY i.first_seen_epoch DESC
      LIMIT ?2`,
  )
    .bind(`%${q.replace(/[\\%_]/g, (c) => "\\" + c)}%`, limit)
    .all();

  return json({ q, count: rows.results?.length ?? 0, results: rows.results ?? [] });
}

/**
 * Seeds the subscription list from an OPML body.
 *
 * Additive. An existing row keeps its crawl state and its learned title — the
 * file is where this started, not what it is, and re-importing must not throw
 * away a year of knowing which feeds are dead.
 */
async function importOpml(request: Request, env: Env): Promise<Response> {
  const subscriptions = parseOpml(await request.text());
  if (!subscriptions.length) return json({ error: "no feeds found in OPML" }, 400);

  const at = new Date().toISOString();
  const statements = subscriptions.map((sub: Subscription) =>
    env.DB.prepare(
      `INSERT INTO feeds (xml_url, title, html_url, category, added_at)
       VALUES (?1,?2,?3,?4,?5)
       ON CONFLICT(xml_url) DO UPDATE SET
         category = COALESCE(excluded.category, feeds.category),
         html_url = COALESCE(excluded.html_url, feeds.html_url)`,
    ).bind(sub.xmlUrl, sub.title, sub.htmlUrl, sub.category, at),
  );

  const written = await env.DB.batch(statements);
  const added = written.reduce((total, r) => total + (r.meta?.changes ?? 0), 0);

  return json({ found: subscriptions.length, written: added });
}

// ----------------------------------------------------------------- utils ---

function intParam(url: URL, name: string): number | null {
  const raw = url.searchParams.get(name);
  if (raw === null) return null;
  const value = Number(raw);
  return Number.isFinite(value) ? Math.trunc(value) : null;
}

function json(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body, null, 2), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}
