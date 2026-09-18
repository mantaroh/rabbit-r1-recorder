/**
 * Reading RSS 2.0, RSS 1.0/RDF and Atom, without a dependency.
 *
 * These are three formats that agree on almost nothing except that an item has
 * a title and a link somewhere. The subscription list this was written for
 * contains all three, so all three are handled rather than the one that happens
 * to be commonest.
 *
 * Parsed by regular expression, which is normally the wrong answer for XML and
 * is the right one here. Workers have no DOM parser; the alternative is a
 * dependency in a Worker whose sibling has none; and the failure mode is
 * bounded — a mangled title or a missed item, in a feed that will be read again
 * in an hour. Nothing here is authoritative and nothing is durable enough to
 * be corrupted.
 */

export interface FeedItem {
  /** The feed's own identifier for this entry, or its link as a fallback. */
  guid: string;
  title: string;
  link: string | null;
  /** ISO 8601 if the feed gave anything parseable, else null. */
  publishedAt: string | null;
  /** The entry's body as plain text, cut short. Null when it has none. */
  summary: string | null;
  /** An absolute http(s) picture of the article, big enough to be worth showing. */
  imageUrl: string | null;
}

/**
 * How much of an entry's body is kept, in Unicode code points rather than
 * UTF-16 units — see the cut in [summaryOf], which counts by code point so a
 * cut cannot land inside a surrogate pair.
 *
 * Bodies are not a fixed size and are not close to one: measured across the
 * subscription list on 2026-09-09, the median entry ran 88 characters at The
 * Register and 15,475 at android-developers, which ships the whole article.
 * The panel shows a few dozen characters at a time and scrolls, so this is
 * about what a person might read before moving on rather than about the page.
 */
const SUMMARY_MAX = 400;

/** Shorter than this is a "Read more" stub rather than a summary. */
const SUMMARY_MIN = 20;

/** Items in document order, which for nearly every feed means newest first. */
export function parseFeed(xml: string): FeedItem[] {
  const blocks = xml.match(/<(item|entry)(?:\s[^>]*)?>[\s\S]*?<\/\1>/g) ?? [];
  const items: FeedItem[] = [];

  for (const block of blocks) {
    const title = text(block, "title") ?? "(untitled)";
    const link = linkOf(block);
    const guid =
      text(block, "guid") ??
      text(block, "id") ??
      link ??
      // Nothing stable to key on. The title is a poor identifier, but it is
      // better than treating every crawl as a fresh arrival.
      title;

    items.push({
      guid: guid.slice(0, 500),
      title: title.slice(0, 500),
      link,
      publishedAt: dateOf(block),
      summary: summaryOf(block),
      imageUrl: imageOf(block),
    });
  }

  return items;
}

/** The channel or feed title, for a subscription whose OPML entry lied. */
export function parseFeedTitle(xml: string): string | null {
  // Strip the entries first: their titles are the ones that would otherwise
  // match, since they appear before the closing tag of the channel.
  const head = xml.replace(/<(item|entry)(?:\s[^>]*)?>[\s\S]*?<\/\1>/g, "");
  return text(head, "title");
}

/**
 * Atom puts the address in an attribute and RSS puts it in the element body,
 * and Atom feeds carry several — the self link, alternates, replies, and
 * sometimes an enclosure.
 */
function linkOf(block: string): string | null {
  // Atom: prefer an explicit alternate, then any link with no rel, and never
  // rel="self" or rel="replies" if something else is on offer.
  const hrefs = [...block.matchAll(/<link\b([^>]*)\/?>/g)].map((m) => m[1]);

  let fallback: string | null = null;
  for (const attrs of hrefs) {
    const href = attrOf(attrs, "href");
    if (!href) continue;
    const rel = attrOf(attrs, "rel");
    if (rel === "alternate" || rel === null) return decode(href);
    if (!fallback) fallback = decode(href);
  }
  if (fallback) return fallback;

  // RSS 2.0 and RDF: the URL is the element's text.
  const inline = text(block, "link");
  return inline || null;
}

/**
 * The smallest a picture may declare itself and still be shown.
 *
 * Two things sit under this and neither is the article: Blogger's
 * media:thumbnail is 72 pixels square, and the pixel FeedBurner puts at the
 * end of a body is one.
 */
const MIN_IMAGE_PX = 200;

/**
 * Hosts that serve something other than the article, whatever the element
 * says it is. Gravatar is the author's face; mozillagfx offers it as
 * media:content on every entry.
 *
 * "favicon." rather than one CDN hostname: there is no host parsing here, the
 * check below is `cleaned.toLowerCase().includes(host)` over the whole URL, so
 * this matches the word anywhere in it — path included — and the next favicon
 * CDN does not have to be discovered the same way. That looseness is also the
 * only reason `b.hatena.ne.jp/entry/image` below works at all: it names a path,
 * not a host, and there is no hostname-anchored form of it. No article
 * picture's URL has happened to contain "favicon." anywhere in it, so the
 * substring match has not cost a false positive yet.
 *
 * `b.st-hatena.com` and `b.hatena.ne.jp/entry/image` are Hatena Bookmark, an
 * aggregator whose entry body is a quote card built out of interface images —
 * a favicon, an OGP thumbnail proxy, a "add this bookmark" badge — and never
 * the linked article's picture. That is a property of the source, not of any
 * one image's size, so no size check was ever going to find all of it;
 * measured 2026-09-09 by watching a size-based rule clear one of these and
 * immediately uncover the next. `f.st-hatena.com` is deliberately left off:
 * that is Hatena Blog, a different product on a different subdomain, and
 * cdn-ak.f.st-hatena.com serves a real article's picture.
 */
const IMAGE_DENYLIST = ["gravatar.com", "favicon.", "b.st-hatena.com", "b.hatena.ne.jp/entry/image"];

/**
 * One attribute out of a tag's attribute string.
 *
 * Anchored on a boundary that is whitespace or the start, so `width` does not
 * also match `data-width`.
 */
function attrOf(attrs: string, name: string): string | null {
  return new RegExp(`(?:^|\\s)${name}\\s*=\\s*["']([^"']*)["']`, "i").exec(attrs)?.[1] ?? null;
}

/** The attribute strings of every occurrence of a tag, in document order. */
function tagsOf(xml: string, name: string): string[] {
  return [...xml.matchAll(new RegExp(`<${name}\\b([^>]*?)/?>`, "gi"))].map((m) => m[1]);
}

/**
 * The article's picture, or null when the entry only offers something that is
 * not one.
 *
 * Four places, in descending order of how likely the publisher was to mean the
 * article by it, and every candidate has to survive [usableImage] — so an
 * entry whose media:content is an avatar falls through to the body rather than
 * ending up with a face on the screen.
 */
function imageOf(block: string): string | null {
  for (const attrs of tagsOf(block, "enclosure")) {
    const type = attrOf(attrs, "type");
    if (type && !type.toLowerCase().startsWith("image/")) continue;
    const url = usableImage(attrOf(attrs, "url"), attrs);
    if (url) return url;
  }

  for (const attrs of tagsOf(block, "media:content")) {
    const type = attrOf(attrs, "type")?.toLowerCase() ?? "";
    const medium = attrOf(attrs, "medium")?.toLowerCase() ?? "";
    if (medium !== "image" && !type.startsWith("image/")) continue;
    const url = usableImage(attrOf(attrs, "url"), attrs);
    if (url) return url;
  }

  for (const attrs of tagsOf(block, "media:thumbnail")) {
    const url = usableImage(attrOf(attrs, "url"), attrs);
    if (url) return url;
  }

  // The body, which is where a publisher that uses none of the above puts it.
  // Decoded twice: a feed that escaped its HTML once has no <img> until that
  // escaping is undone, and one that escaped it twice — the same defect
  // [plainText] corrects for — has none until it is undone a second time.
  const body = ["description", "content:encoded", "summary", "content"]
    .map((tag) => rawText(block, tag) ?? "")
    .join(" ");
  for (const attrs of tagsOf(decode(decode(body)), "img")) {
    const url = usableImage(attrOf(attrs, "src"), attrs);
    if (url) return url;
  }

  return null;
}

/** The URL if it is worth fetching from a device, else null. */
function usableImage(url: string | null, attrs: string): string | null {
  if (!url) return null;

  const cleaned = decode(url).trim();
  if (!cleaned || cleaned.length > 500) return null;
  // The device fetches this itself, so nothing but an ordinary URL is any use
  // to it — no data: payloads, no protocol-relative addresses.
  if (!/^https?:\/\//i.test(cleaned)) return null;
  if (IMAGE_DENYLIST.some((host) => cleaned.toLowerCase().includes(host))) return null;

  // A rewritten URL is a different picture from the one the attributes
  // describe, so the size the feed declared no longer applies to it.
  const bigger = enlarged(cleaned);
  if (bigger) return bigger;

  let declared = false;
  for (const name of ["width", "height"]) {
    const value = Number(attrOf(attrs, name));
    if (Number.isFinite(value) && value > 0) {
      declared = true;
      if (value < MIN_IMAGE_PX) return null;
    }
  }

  // The width/height attributes are optional, and on the CDNs this matters
  // for they are frequently absent — some services state the size as
  // width=/height= in the URL itself instead. Measured 2026-09-09: Hatena's
  // OGP thumbnail proxy (cdn-ak-scissors.b.st-hatena.com) states
  // "height=90;version=1;width=120" with no size attribute on the <img> at
  // all, and 120 is too small for a panel that is 480 pixels across. The
  // Register's image host is the reason this cannot just refuse anything
  // that mentions a width: it states "?imageId=5295166&width=800", a
  // perfectly usable picture. Only consulted when the attributes gave
  // nothing, and only for these two keys — a URL that states neither is not
  // evidence of a small image.
  if (!declared) {
    const width = /(?:^|[?&;/])width=(\d+)/i.exec(cleaned);
    const height = /(?:^|[?&;/])height=(\d+)/i.exec(cleaned);
    if (width && Number(width[1]) < MIN_IMAGE_PX) return null;
    if (height && Number(height[1]) < MIN_IMAGE_PX) return null;
  }

  return cleaned;
}

/**
 * Blogger states a thumbnail's size in its path, and the size it states is 72
 * pixels — 7,251 bytes of a picture that has to fill 480. The same URL asked
 * for s640 returned 135,810 bytes of the same image, measured against
 * android-developers on 2026-09-09.
 *
 * Narrow on purpose. This is one publisher's URL scheme, not a general
 * ability to resize other people's images by guessing at their addresses.
 */
function enlarged(url: string): string | null {
  if (!url.includes("googleusercontent.com")) return null;
  const rewritten = url.replace(/\/s\d+(-c)?\//, "/s640/").replace(/=s\d+(-c)?$/, "=s640");
  return rewritten === url ? null : rewritten;
}

/**
 * The first date-like element that parses, in order of how much a publisher is
 * likely to have meant it.
 *
 * `updated` is last on purpose: an Atom entry edited for a typo three years
 * later is not news, and sorting by it would push it to the top.
 */
function dateOf(block: string): string | null {
  for (const tag of ["pubDate", "published", "dc:date", "date", "updated"]) {
    const raw = text(block, tag);
    if (!raw) continue;
    const parsed = Date.parse(raw);
    if (!Number.isNaN(parsed)) return new Date(parsed).toISOString();
  }
  return null;
}

/**
 * The inner text of the first matching element, with CDATA unwrapped and
 * nothing else done to it.
 *
 * Split out from [text] because a summary needs the markup that is in there —
 * to throw it away deliberately — while a title needs it already gone.
 *
 * The tag is matched with an optional namespace prefix, because the same
 * element is `<title>` in RSS, `<title>` in Atom and occasionally
 * `<rss:title>` in a feed generated by something with opinions.
 */
function rawText(xml: string, tag: string): string | null {
  const name = tag.includes(":") ? tag : `(?:[a-zA-Z0-9]+:)?${tag}`;
  const match = new RegExp(`<${name}(?:\\s[^>]*)?>([\\s\\S]*?)</${name}>`).exec(xml);
  if (!match) return null;

  const cdata = /<!\[CDATA\[([\s\S]*?)\]\]>/.exec(match[1]);
  return cdata ? cdata[1] : match[1];
}

/** The text of the first matching element, CDATA unwrapped and entities decoded. */
function text(xml: string, tag: string): string | null {
  const raw = rawText(xml, tag);
  if (raw === null) return null;
  return decode(raw).replace(/\s+/g, " ").trim() || null;
}

/**
 * The entry's body, as something that can be put on a screen.
 *
 * Four elements in order of how likely a publisher is to have meant the short
 * one. `content` is last because Atom's is the whole article, and a feed that
 * offers both has already said which it thinks is the summary.
 */
function summaryOf(block: string): string | null {
  for (const tag of ["description", "summary", "content:encoded", "content"]) {
    const raw = rawText(block, tag);
    if (!raw) continue;

    const plain = plainText(raw);
    // "Read more", "Continue reading", and the empty wrappers left behind by a
    // template. Falling through to the next element finds the real body.
    if (plain.length < SUMMARY_MIN) continue;

    // Cut by code point, not by [plain.slice], which counts UTF-16 units and
    // can land inside a surrogate pair — most emoji outside the BMP are two
    // of them. A cut mid-pair leaves a lone high surrogate that reaches D1,
    // then Android's JSONObject, and renders as a replacement glyph right
    // before the ellipsis. Spreading the string walks it by code point, so
    // this always lands on a whole character; see [firstCodePoints].
    const cut = firstCodePoints(plain, SUMMARY_MAX);
    return cut.length < plain.length ? cut.trimEnd() + "…" : plain;
  }
  return null;
}

/**
 * The first `max` Unicode code points of a string.
 *
 * Not `text.slice(0, max)`, which counts UTF-16 units: a surrogate pair
 * split across the boundary leaves a lone high surrogate behind. Spreading
 * the string into an array walks it one code point at a time, so a cut here
 * always falls on a whole character. `max` is therefore a code point count,
 * not a UTF-16 length.
 */
export function firstCodePoints(text: string, max: number): string {
  return [...text].slice(0, max).join("");
}

/**
 * Markup out, entities in.
 *
 * Decode comes first: a feed that escaped its HTML has no tags to strip
 * until the escaping has been undone. The loop then runs twice, because
 * escaping is not consistently applied once — a feed that put its HTML in
 * CDATA presents tags immediately; one that escaped it once presents them
 * after this first decode; and one that escaped it twice — which happens,
 * and is the same class of mistake [decode] already handles for titles —
 * presents them only after the second.
 */
function plainText(html: string): string {
  let value = decode(html);
  for (let pass = 0; pass < 2; pass += 1) value = decode(strip(value));
  return value.replace(/\s+/g, " ").trim();
}

/**
 * Markup out, but only where it is shaped like a tag.
 *
 * The obvious pattern, /<[^>]+>/g, treats any "<" as a tag opener and eats
 * everything up to the next ">" — and by the time this runs, [plainText] has
 * already decoded entities, so a feed that escaped a comparison operator
 * hands this a real "<". On this subscription list — android-developers,
 * mozillagfx, The Register, Publickey — summaries with code and comparison
 * operators in them are routine, and the naive pattern turned "a &lt; b and c
 * &gt; d on every run" into "d on every run", silently dropping a clause
 * rather than failing loudly. Requiring the character after "<" to be a
 * letter (optionally after a "/") is enough to rule out a space, a digit or
 * "$" as a tag opener, which covers the cases actually seen: "a < b", "<$5",
 * "List<String>" (partially — see below). Comments are stripped separately
 * because "<!--" does not start with a letter and would otherwise survive as
 * plain text.
 *
 * `List<String>` is a genuine tie: `<String>` is shaped exactly like a tag and
 * is still removed, taking the type parameter with it. That is accepted —
 * losing one token is a much smaller failure than losing the clause around
 * it, which is what the old pattern did to entries like this one.
 */
function strip(html: string): string {
  return html
    .replace(/<script[\s\S]*?<\/script>/gi, " ")
    .replace(/<style[\s\S]*?<\/style>/gi, " ")
    .replace(/<!--[\s\S]*?-->/g, " ")
    .replace(/<\/?[a-zA-Z][^>]*>/g, " ");
}

/**
 * Enough entity decoding for titles. Numeric forms first, because a title that
 * has been escaped twice — which is common — decodes `&amp;#8217;` correctly
 * only if the ampersand is handled last.
 */
function decode(value: string): string {
  return value
    .replace(/&#x([0-9a-fA-F]+);/g, (_, hex) => String.fromCodePoint(parseInt(hex, 16)))
    .replace(/&#(\d+);/g, (_, dec) => String.fromCodePoint(Number(dec)))
    .replace(/&lt;/g, "<")
    .replace(/&gt;/g, ">")
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&nbsp;/g, " ")
    .replace(/&amp;/g, "&");
}

// ------------------------------------------------------------------ opml ---

export interface Subscription {
  title: string;
  xmlUrl: string;
  htmlUrl: string | null;
  category: string | null;
}

/**
 * Feeds out of an OPML export, with the folder they sit in as their category.
 *
 * Read once to seed the table and then not consulted again: the crawler learns
 * things the file cannot carry — which feeds have moved, which have been dead
 * for a year — and a re-import must not throw those away.
 */
export function parseOpml(xml: string): Subscription[] {
  const subscriptions: Subscription[] = [];
  const attrOf = (attrs: string, name: string) =>
    new RegExp(`${name}\\s*=\\s*"([^"]*)"`).exec(attrs)?.[1] ?? null;

  // Walked rather than matched, because the category is the enclosing
  // outline's text and a flat search over the file loses that nesting.
  let category: string | null = null;
  for (const match of xml.matchAll(/<outline\b([^>]*?)(\/?)>|<\/outline>/g)) {
    if (match[0] === "</outline>") {
      category = null;
      continue;
    }
    const attrs = match[1];
    const xmlUrl = attrOf(attrs, "xmlUrl");
    const title = attrOf(attrs, "title") ?? attrOf(attrs, "text") ?? "(untitled)";

    if (!xmlUrl) {
      // A container. Self-closing containers exist and hold nothing, so only a
      // real opening tag changes the current folder.
      if (match[2] !== "/") category = decode(title);
      continue;
    }

    subscriptions.push({
      title: decode(title),
      xmlUrl: decode(xmlUrl),
      htmlUrl: attrOf(attrs, "htmlUrl") ? decode(attrOf(attrs, "htmlUrl")!) : null,
      category,
    });
  }

  return subscriptions;
}
