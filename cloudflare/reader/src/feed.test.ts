import { describe, expect, it } from "vitest";
import { parseFeed } from "./feed";

/** 一件だけのRSSに仕立てる。テストが見たいのは item の中身だけ。 */
function rss(item: string): string {
  return `<rss version="2.0"><channel><title>Test</title>${item}</channel></rss>`;
}

describe("summary", () => {
  it("takes description and strips its tags", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title><link>https://example.com/a</link>
        <description><![CDATA[<p>Hello <b>world</b> and then some more text.</p>]]></description>
      </item>`),
    );
    expect(item.summary).toBe("Hello world and then some more text.");
  });

  it("decodes HTML that was escaped twice", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <description>&amp;lt;p&amp;gt;Alpha &amp;amp;amp; Beta, and a good deal more text here.&amp;lt;/p&amp;gt;</description>
      </item>`),
    );
    expect(item.summary).toBe("Alpha & Beta, and a good deal more text here.");
  });

  it("cuts a full article down to 400 characters", () => {
    const long = "abcde".repeat(200);
    const [item] = parseFeed(rss(`<item><title>T</title><description>${long}</description></item>`));
    expect(item.summary).toHaveLength(401); // 400 + 省略記号
    expect(item.summary?.endsWith("…")).toBe(true);
  });

  it("skips a description that is only a read-more stub", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title><description>Read more</description>
        <content:encoded><![CDATA[The actual body of the entry lives here.]]></content:encoded>
      </item>`),
    );
    expect(item.summary).toBe("The actual body of the entry lives here.");
  });

  it("is null when the entry carries no body at all", () => {
    const [item] = parseFeed(rss(`<item><title>T</title><link>https://example.com/a</link></item>`));
    expect(item.summary).toBeNull();
  });

  it("decodes HTML that was escaped once", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <description>&lt;p&gt;Gamma &amp;amp; Delta, and a good deal more text here.&lt;/p&gt;</description>
      </item>`),
    );
    expect(item.summary).toBe("Gamma & Delta, and a good deal more text here.");
  });
});

describe("imageUrl", () => {
  it("prefers an image enclosure over a thumbnail", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <media:thumbnail url="https://example.com/small.jpg" />
        <enclosure url="https://example.com/big.jpg" type="image/jpeg" />
      </item>`),
    );
    expect(item.imageUrl).toBe("https://example.com/big.jpg");
  });

  it("asks Blogger for a size that is worth showing", () => {
    // Measured 2026-09-09: the s72-c URL is 72x72 and 7,251 bytes; the s640
    // rewrite of the same URL is 135,810 bytes of the same picture.
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <media:thumbnail url="https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEg/s72-c/Meta.png" height="72" width="72"/>
      </item>`),
    );
    expect(item.imageUrl).toBe(
      "https://blogger.googleusercontent.com/img/b/R29vZ2xl/AVvXsEg/s640/Meta.png",
    );
  });

  it("drops a gravatar and falls through to the body", () => {
    // mozillagfx offers media:content, and what it offers is the author's face.
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <media:content url="https://0.gravatar.com/avatar/96d7?s=96" medium="image" />
        <description><![CDATA[<img src="https://example.com/article.jpg"> Body text goes here.]]></description>
      </item>`),
    );
    expect(item.imageUrl).toBe("https://example.com/article.jpg");
  });

  it("drops a tracking pixel", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <description><![CDATA[Body text.<img src="https://feeds.example.com/~r/x/~4/abc" height="1" width="1">]]></description>
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("drops a thumbnail that declares itself too small", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <media:thumbnail url="https://example.com/icon.png" width="72" height="72" />
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("refuses anything that is not http", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <enclosure url="data:image/png;base64,iVBORw0KGgo=" type="image/png" />
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("ignores a non-image enclosure", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <enclosure url="https://example.com/episode.mp3" type="audio/mpeg" />
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("is null when the entry offers nothing", () => {
    const [item] = parseFeed(rss(`<item><title>T</title><description>Body text.</description></item>`));
    expect(item.imageUrl).toBeNull();
  });

  it("drops a favicon offered with no declared size", () => {
    // Seen on Hatena's hotentry feed 2026-09-09: content:encoded carries a
    // quote card whose <img> is the linked article's favicon, 64x64, with no
    // width or height attribute for the size check to catch.
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <content:encoded><![CDATA[<blockquote><cite><img src="https://cdn-ak2.favicon.st-hatena.com/64?url=https%3A%2F%2Fexample.com%2Fentry" alt="" /></cite> Some quoted text.</blockquote>]]></content:encoded>
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("drops a URL that states a small size as width=/height=, with no attribute to catch it", () => {
    // Shaped like the Hatena scissors URL that motivated this rule
    // ("height=90;version=1;width=120", an unrelated key sitting between the
    // two so the match has to key on the exact names) but on a host that
    // is not also in IMAGE_DENYLIST, so this exercises the width=/height=
    // reading itself rather than the denylist below, which now intercepts
    // the real scissors host first.
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <enclosure url="https://example.com/thumb/backend=imagemagick;height=90;version=1;width=120/photo" type="image/jpeg" />
      </item>`),
    );
    expect(item.imageUrl).toBeNull();
  });

  it("keeps a URL whose width= states a size well above the floor", () => {
    // The Register's image host, from the design document's measured table:
    // it states its size as a query parameter and nothing else, so this is
    // also the case that rules out refusing anything that merely mentions a
    // width.
    const url = "https://image.theregister.com/?imageId=5295166&width=800";
    const [item] = parseFeed(rss(`<item><title>T</title><enclosure url="${url}" type="image/jpeg" /></item>`));
    expect(item.imageUrl).toBe(url);
  });

  it("keeps a URL with no size anywhere in it", () => {
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <enclosure url="https://example.com/uploads/article-photo.jpg" type="image/jpeg" />
      </item>`),
    );
    expect(item.imageUrl).toBe("https://example.com/uploads/article-photo.jpg");
  });

  it("refuses Hatena Bookmark's scissors thumbnail but keeps Hatena Blog's own image host", () => {
    // b.st-hatena.com is bookmark chrome; f.st-hatena.com is a different
    // Hatena product (Hatena Blog) that serves real article pictures, so the
    // denylist entry must tell these apart rather than matching the shared
    // "st-hatena.com" parent.
    const scissorsUrl =
      "https://cdn-ak-scissors.b.st-hatena.com/image/square/23b0075fb9f382bbed1d847b2fd98daf90cf313f/backend=imagemagick;height=90;version=1;width=120/https%3A%2F%2Fanond.hatelabo.jp%2Fassets%2Fimages%2Fog-image-1500.gif";
    const [bookmark] = parseFeed(
      rss(`<item><title>T</title><enclosure url="${scissorsUrl}" type="image/jpeg" /></item>`),
    );
    expect(bookmark.imageUrl).toBeNull();

    const blogUrl = "https://cdn-ak.f.st-hatena.com/images/fotolife/e/example/20260909/20260909120000.png";
    const [blog] = parseFeed(rss(`<item><title>T</title><enclosure url="${blogUrl}" type="image/png" /></item>`));
    expect(blog.imageUrl).toBe(blogUrl);
  });

  it("refuses Hatena Bookmark's badge proxy", () => {
    // Seen alongside the scissors thumbnail in the same content:encoded
    // quote card, once the scissors and favicon images ahead of it were
    // both blocked: an <img> whose alt text is "はてなブックマーク - <title>",
    // Hatena's own "add this bookmark" badge rather than the article.
    const url = "https://b.hatena.ne.jp/entry/image/https://anond.hatelabo.jp/20260908235134";
    const [item] = parseFeed(rss(`<item><title>T</title><enclosure url="${url}" type="image/jpeg" /></item>`));
    expect(item.imageUrl).toBeNull();
  });

  it("finds a body <img> that was escaped once", () => {
    // example.com on purpose: nothing on IMAGE_DENYLIST or in the size rules
    // should have a say here, so a failure can only mean the decode itself.
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <description>&lt;img src="https://example.com/photo.jpg"&gt; Some body text.</description>
      </item>`),
    );
    expect(item.imageUrl).toBe("https://example.com/photo.jpg");
  });

  it("finds a body <img> that was escaped twice", () => {
    // The case F3 exists for: a feed that escaped its HTML twice has no <img>
    // until it is decoded twice. Unlike the once-escaped case above, this one
    // would still be invisible after a single decode(body) — see the fix
    // report for the RED/GREEN trace against decode(decode(body)).
    const [item] = parseFeed(
      rss(`<item><title>T</title>
        <description>&amp;lt;img src="https://example.com/photo.jpg"&amp;gt; Some body text.</description>
      </item>`),
    );
    expect(item.imageUrl).toBe("https://example.com/photo.jpg");
  });
});
