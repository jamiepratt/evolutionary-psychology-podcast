import fs from "node:fs";

const xmlPath = "sources/episode-selected.xml";
const outPath = "sources/episode-selected.json";
const xml = fs.readFileSync(xmlPath, "utf8");

function text(tag) {
  const match = xml.match(new RegExp(`<${tag}(?:\\s[^>]*)?>([\\s\\S]*?)<\\/${tag}>`));
  if (!match) return null;
  return decode(match[1].replace(/^<!\[CDATA\[/, "").replace(/\]\]>$/, "").trim());
}

function attr(tag, attrName) {
  const match = xml.match(new RegExp(`<${tag}\\s+([^>]*)\\/?>`));
  if (!match) return null;
  const attrMatch = match[1].match(new RegExp(`${attrName}="([^"]*)"`));
  return attrMatch ? decode(attrMatch[1]) : null;
}

function decode(value) {
  return value
    .replaceAll("&amp;", "&")
    .replaceAll("&quot;", '"')
    .replaceAll("&apos;", "'")
    .replaceAll("&lt;", "<")
    .replaceAll("&gt;", ">");
}

const metadata = {
  title: text("title"),
  itunes_title: text("itunes:title"),
  link: text("link"),
  guid: text("guid"),
  pubDate: text("pubDate"),
  enclosure: {
    url: attr("enclosure", "url"),
    length: Number(attr("enclosure", "length")),
    type: attr("enclosure", "type"),
  },
  duration_seconds: Number(text("itunes:duration")),
  author: text("itunes:author"),
  explicit: text("itunes:explicit"),
  source_feed: "https://feed.podbean.com/epthepod/feed.xml",
  selected_item_xml: xmlPath,
};

fs.writeFileSync(outPath, `${JSON.stringify(metadata, null, 2)}\n`);
console.log(`Wrote ${outPath}`);
