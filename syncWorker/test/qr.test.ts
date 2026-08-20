import assert from "node:assert/strict";
import { createHash } from "node:crypto";
import test from "node:test";
import { encodeQr, qrSvg } from "../src/qr.ts";

const SETUP_LINK = "shinsou://sync/setup?endpoint=https%3A%2F%2Fsync.example.test" +
  "&instance=00000000-0000-4000-8000-000000000001";

test("setup deep link matches the independent qrose byte-mode M golden matrix", () => {
  const matrix = encodeQr(SETUP_LINK);
  assert.equal(matrix.length, 45, "fixture must exercise version 7 and multi-block RS interleaving");
  const bits = matrix.flatMap((row) => row.map((module) => module ? "1" : "0")).join("");
  assert.equal(
    createHash("sha256").update(bits).digest("hex"),
    "a67664793063c8772dbf94adfb0fb9134f88eb386d547dcbe2ae1a44557234e8",
  );
});

test("inline SVG has a four-module quiet zone and embeds no active or remote content", () => {
  const escaped = SETUP_LINK.replaceAll("&", "&amp;");
  const svg = qrSvg(SETUP_LINK);
  assert.match(svg, /id="setup-qr"/);
  assert.match(svg, /viewBox="0 0 53 53"/);
  assert.match(svg, /<rect width="53" height="53" fill="#fff"\/>/);
  assert.match(svg, /<path d="M/);
  assert.ok(svg.includes(`data-payload="${escaped}"`));
  assert.doesNotMatch(svg, /<(?:script|image)\b|\s(?:href|src)=/i);
});
