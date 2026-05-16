import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { JSDOM } from "jsdom";

const html = `<!doctype html>
<html>
<body>
  <header>
    <audio id="episode-audio" controls preload="metadata" data-waveform-manifest="waveform.json"></audio>
    <div class="custom-player" data-custom-player hidden>
      <button class="play-button" type="button" data-play-toggle aria-label="Play audio" aria-pressed="false">Play</button>
      <div class="time-readout" aria-live="off">
        <span data-current-time>00:00</span>
        <span aria-hidden="true">/</span>
        <span data-duration>01:05</span>
      </div>
      <div class="waveform-shell" data-waveform-container>
        <canvas class="waveform-canvas" data-waveform-canvas role="slider" tabindex="0" aria-label="Audio waveform seek control" aria-valuemin="0" aria-valuemax="65.7" aria-valuenow="0"></canvas>
        <div class="waveform-playhead" aria-hidden="true"></div>
      </div>
      <button type="button" data-follow-toggle aria-pressed="true">Following transcript</button>
    </div>
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

function peaksBuffer(values) {
  return new Int16Array(values).buffer;
}

async function flushAsync() {
  for (let index = 0; index < 8; index += 1) {
    await Promise.resolve();
  }
}

function startPlayer({ waveform = false } = {}) {
  const dom = new JSDOM(html, {
    runScripts: "outside-only",
    url: "https://example.test/episodes/fixture/",
  });
  const { window } = dom;
  const scrollCalls = [];
  const drawCalls = [];
  const fetchCalls = [];
  const frameCallbacks = [];

  window.Element.prototype.scrollIntoView = function scrollIntoView(options) {
    scrollCalls.push({ id: this.id, options });
  };
  window.HTMLCanvasElement.prototype.getContext = function getContext(type) {
    if (type !== "2d") {
      return null;
    }
    return {
      clearRect: (...args) => drawCalls.push(["clearRect", ...args]),
      beginPath: (...args) => drawCalls.push(["beginPath", ...args]),
      moveTo: (...args) => drawCalls.push(["moveTo", ...args]),
      lineTo: (...args) => drawCalls.push(["lineTo", ...args]),
      stroke: (...args) => drawCalls.push(["stroke", ...args]),
      set lineWidth(value) {
        drawCalls.push(["lineWidth", value]);
      },
      set strokeStyle(value) {
        drawCalls.push(["strokeStyle", value]);
      },
    };
  };
  window.requestAnimationFrame = (callback) => {
    frameCallbacks.push(callback);
    return frameCallbacks.length;
  };
  window.cancelAnimationFrame = () => {};

  const audio = window.document.querySelector("#episode-audio");
  const canvas = window.document.querySelector("[data-waveform-canvas]");
  Object.defineProperty(canvas, "clientWidth", {
    configurable: true,
    get: () => 600,
  });
  Object.defineProperty(canvas, "clientHeight", {
    configurable: true,
    get: () => 72,
  });
  let paused = true;
  Object.defineProperty(audio, "paused", {
    configurable: true,
    get: () => paused,
  });
  Object.defineProperty(audio, "duration", {
    configurable: true,
    get: () => 65.7,
  });
  audio.play = () => {
    paused = false;
    audio.dispatchEvent(new window.Event("play"));
    return Promise.resolve();
  };
  audio.pause = () => {
    paused = true;
    audio.dispatchEvent(new window.Event("pause"));
  };
  if (waveform) {
    const manifest = {
      duration_seconds: 65.7,
      bucket_seconds: 0.02,
      peak_format: "s16le-min-max",
      bits_per_peak: 16,
      channels: 1,
      peak_count: 6,
      peaks: "waveform.peaks",
    };
    const peaks = peaksBuffer([-500, 500, -1000, 1000, -250, 250, -1200, 1200, -700, 700, -50, 50]);
    window.fetch = async (url) => {
      fetchCalls.push(String(url));
      if (String(url).endsWith("waveform.json")) {
        return {
          ok: true,
          url: "https://example.test/episodes/fixture/waveform.json",
          json: async () => manifest,
        };
      }
      return {
        ok: true,
        arrayBuffer: async () => peaks,
      };
    };
  }

  window.eval(readFileSync("web/assets/player.js", "utf8"));
  if (window.document.readyState === "loading") {
    window.document.dispatchEvent(new window.Event("DOMContentLoaded"));
  }

  return { audio, dom, drawCalls, fetchCalls, frameCallbacks, scrollCalls, window };
}

test("compiled transcript player preserves seeking, following, and active transcript behavior", async () => {
  const { audio, dom, scrollCalls, window } = startPlayer();
  const { document } = window;
  const followButton = document.querySelector("[data-follow-toggle]");
  const customPlayer = document.querySelector("[data-custom-player]");
  const playButton = document.querySelector("[data-play-toggle]");
  const currentTime = document.querySelector("[data-current-time]");
  const duration = document.querySelector("[data-duration]");
  const phrase0 = document.querySelector("#phrase-0");
  const phrase1 = document.querySelector("#phrase-1");
  const timestamp1 = document.querySelector("[data-seek='2']");

  assert.equal(customPlayer.hidden, false);
  assert.equal(audio.hidden, true);
  assert.equal(audio.hasAttribute("controls"), false);
  assert.equal(followButton.getAttribute("aria-pressed"), "true");
  assert.equal(followButton.textContent, "Following transcript");
  assert.equal(playButton.getAttribute("aria-pressed"), "false");
  assert.equal(playButton.textContent, "Play");
  assert.equal(duration.textContent, "01:05");

  playButton.click();
  await Promise.resolve();
  assert.equal(audio.paused, false);
  assert.equal(playButton.getAttribute("aria-pressed"), "true");
  assert.equal(playButton.textContent, "Pause");

  audio.currentTime = 2.2;
  audio.dispatchEvent(new window.Event("timeupdate"));
  assert.equal(currentTime.textContent, "00:02");
  assert.equal(phrase1.classList.contains("is-active"), true);
  assert.equal(phrase1.getAttribute("aria-current"), "true");
  assert.equal(document.querySelector("#turn-1").classList.contains("is-current"), true);
  assert.equal(scrollCalls.at(-1).options.block, "center");
  assert.equal(scrollCalls.at(-1).options.behavior, "smooth");

  await new Promise((resolve) => window.setTimeout(resolve, 700));
  window.dispatchEvent(new window.Event("scroll"));
  assert.equal(followButton.getAttribute("aria-pressed"), "false");
  assert.equal(followButton.textContent, "Resume follow");

  playButton.click();
  assert.equal(audio.paused, true);
  assert.equal(playButton.getAttribute("aria-pressed"), "false");
  assert.equal(playButton.textContent, "Play");

  phrase0.click();
  await Promise.resolve();
  assert.equal(audio.currentTime, 0);
  assert.equal(audio.paused, false);
  assert.equal(currentTime.textContent, "00:00");
  assert.equal(playButton.textContent, "Pause");
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

test("compiled player loads waveform peaks and schedules canvas rendering", async () => {
  const { audio, dom, drawCalls, fetchCalls, frameCallbacks, window } = startPlayer({ waveform: true });
  const { document } = window;
  const canvas = document.querySelector("[data-waveform-canvas]");

  await flushAsync();

  assert.deepEqual(fetchCalls, ["waveform.json", "waveform.peaks"]);
  assert.equal(frameCallbacks.length, 1);

  audio.currentTime = 12;
  frameCallbacks.shift()(123);

  assert.equal(canvas.width, 600);
  assert.equal(canvas.height, 72);
  assert.equal(drawCalls.some(([name]) => name === "clearRect"), true);
  assert.equal(drawCalls.some(([name]) => name === "lineTo"), true);
  assert.equal(frameCallbacks.length, 1);

  dom.window.close();
});
