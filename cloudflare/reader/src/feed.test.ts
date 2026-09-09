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
});
