import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { JSDOM } from "jsdom";

const html = `<!doctype html>
<html>
<body>
  <header>
    <audio id="episode-audio"></audio>
    <button type="button" data-follow-toggle aria-pressed="true">Following transcript</button>
  </header>
  <main>
    <section class="transcript-shell" aria-label="Transcript">
      <article class="turn" id="turn-0">
        <header><a class="timestamp" href="#phrase-0" data-seek="0">00:00</a></header>
        <p><span class="phrase" id="phrase-0" data-start="0" data-end="1.5" role="button" tabindex="0">Hello.</span></p>
      </article>
      <article class="turn" id="turn-1">
        <header><a class="timestamp" href="#phrase-1" data-seek="2">00:02</a></header>
        <p><span class="phrase" id="phrase-1" data-start="2" data-end="3" role="button" tabindex="0">World.</span></p>
      </article>
    </section>
  </main>
</body>
</html>`;

function startPlayer() {
  const dom = new JSDOM(html, {
    runScripts: "outside-only",
    url: "https://example.test/episodes/fixture/",
  });
  const { window } = dom;
  const scrollCalls = [];

  window.Element.prototype.scrollIntoView = function scrollIntoView(options) {
    scrollCalls.push({ id: this.id, options });
  };

  const audio = window.document.querySelector("#episode-audio");
  let paused = true;
  Object.defineProperty(audio, "paused", {
    configurable: true,
    get: () => paused,
  });
  audio.play = () => {
    paused = false;
    audio.dispatchEvent(new window.Event("play"));
    return Promise.resolve();
  };
  audio.pause = () => {
    paused = true;
  };

  window.eval(readFileSync("web/assets/player.js", "utf8"));

  return { audio, dom, scrollCalls, window };
}

test("compiled transcript player preserves seeking, following, and active transcript behavior", async () => {
  const { audio, dom, scrollCalls, window } = startPlayer();
  const { document } = window;
  const followButton = document.querySelector("[data-follow-toggle]");
  const phrase0 = document.querySelector("#phrase-0");
  const phrase1 = document.querySelector("#phrase-1");
  const timestamp1 = document.querySelector("[data-seek='2']");

  assert.equal(followButton.getAttribute("aria-pressed"), "true");
  assert.equal(followButton.textContent, "Following transcript");

  await audio.play();
  audio.currentTime = 2.2;
  audio.dispatchEvent(new window.Event("timeupdate"));
  assert.equal(phrase1.classList.contains("is-active"), true);
  assert.equal(phrase1.getAttribute("aria-current"), "true");
  assert.equal(document.querySelector("#turn-1").classList.contains("is-current"), true);
  assert.equal(scrollCalls.at(-1).options.block, "center");
  assert.equal(scrollCalls.at(-1).options.behavior, "smooth");

  await new Promise((resolve) => window.setTimeout(resolve, 700));
  window.dispatchEvent(new window.Event("scroll"));
  assert.equal(followButton.getAttribute("aria-pressed"), "false");
  assert.equal(followButton.textContent, "Resume follow");

  phrase0.click();
  await Promise.resolve();
  assert.equal(audio.currentTime, 0);
  assert.equal(followButton.getAttribute("aria-pressed"), "true");
  assert.equal(phrase0.classList.contains("is-active"), true);
  assert.equal(phrase1.classList.contains("is-active"), false);

  timestamp1.click();
  await Promise.resolve();
  assert.equal(audio.currentTime, 2);
  assert.equal(phrase1.classList.contains("is-active"), true);

  phrase0.dispatchEvent(new window.KeyboardEvent("keydown", {
    bubbles: true,
    key: "Enter",
  }));
  await Promise.resolve();
  assert.equal(audio.currentTime, 0);

  followButton.click();
  assert.equal(followButton.getAttribute("aria-pressed"), "false");
  followButton.click();
  assert.equal(followButton.getAttribute("aria-pressed"), "true");
  assert.equal(scrollCalls.at(-1).id, "phrase-0");

  dom.window.close();
});
