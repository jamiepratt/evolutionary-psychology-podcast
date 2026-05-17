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

function pointerEvent(window, type, options = {}) {
  const event = new window.Event(type, {
    bubbles: true,
    cancelable: true,
  });
  Object.assign(event, {
    button: 0,
    buttons: type === "pointerup" ? 0 : 1,
    clientX: 0,
    clientY: 0,
    pointerId: 1,
    pointerType: "mouse",
    ...options,
  });
  return event;
}

function startPlayer({
  waveform = false,
  waveformManifest = {},
  waveformPeaks,
  canvasWidth = 600,
  waveformPending = false,
} = {}) {
  const dom = new JSDOM(html, {
    runScripts: "outside-only",
    url: "https://example.test/episodes/fixture/",
  });
  const { window } = dom;
  const scrollCalls = [];
  const drawCalls = [];
  const fetchCalls = [];
  const frameCallbacks = [];
  let nextFrameId = 1;

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
      set lineCap(value) {
        drawCalls.push(["lineCap", value]);
      },
    };
  };
  window.requestAnimationFrame = (callback) => {
    callback.frameId = nextFrameId;
    frameCallbacks.push(callback);
    nextFrameId += 1;
    return callback.frameId;
  };
  window.cancelAnimationFrame = (frameId) => {
    const callbackIndex = frameCallbacks.findIndex((callback) => callback.frameId === frameId);
    if (callbackIndex >= 0) {
      frameCallbacks.splice(callbackIndex, 1);
    }
  };

  const audio = window.document.querySelector("#episode-audio");
  const canvas = window.document.querySelector("[data-waveform-canvas]");
  Object.defineProperty(canvas, "clientWidth", {
    configurable: true,
    get: () => canvasWidth,
  });
  Object.defineProperty(canvas, "clientHeight", {
    configurable: true,
    get: () => 72,
  });
  canvas.getBoundingClientRect = () => ({
    bottom: 72,
    height: 72,
    left: 100,
    right: 100 + canvasWidth,
    top: 0,
    width: canvasWidth,
    x: 100,
    y: 0,
  });
  canvas.setPointerCapture = () => {};
  canvas.releasePointerCapture = () => {};
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
      ...waveformManifest,
    };
    const peaks = peaksBuffer(
      waveformPeaks ?? [-500, 500, -1000, 1000, -250, 250, -1200, 1200, -700, 700, -50, 50],
    );
    window.fetch = async (url) => {
      fetchCalls.push(String(url));
      if (waveformPending) {
        return new Promise(() => {});
      }
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

function audibleWaveformXs(drawCalls) {
  return drawCalls
    .filter(([name, , y]) => name === "moveTo" && y !== 36)
    .map(([, x]) => x);
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

test("compiled player animates the waveform canvas while peaks are loading", async () => {
  const { dom, drawCalls, fetchCalls, frameCallbacks, window } = startPlayer({
    waveform: true,
    waveformPending: true,
  });
  const canvas = window.document.querySelector("[data-waveform-canvas]");

  assert.deepEqual(fetchCalls, ["waveform.json"]);
  assert.equal(frameCallbacks.length, 1);

  frameCallbacks.shift()(123);

  assert.equal(canvas.width, 600);
  assert.equal(canvas.height, 72);
  assert.equal(drawCalls.some(([name]) => name === "clearRect"), true);
  assert.equal(drawCalls.some(([name, value]) => name === "strokeStyle" && value === "rgba(94, 234, 212, 0.5)"), true);
  assert.equal(drawCalls.some(([name, value]) => name === "lineCap" && value === "round"), true);
  assert.equal(drawCalls.some(([name]) => name === "lineTo"), true);
  assert.equal(frameCallbacks.length, 1);

  dom.window.close();
});

test("compiled player loads waveform peaks and schedules canvas rendering", async () => {
  const { audio, dom, drawCalls, fetchCalls, frameCallbacks, window } = startPlayer({ waveform: true });
  const { document } = window;
  const canvas = document.querySelector("[data-waveform-canvas]");

  await flushAsync();

  assert.deepEqual(fetchCalls, ["waveform.json", "waveform.peaks"]);
  assert.equal(frameCallbacks.length, 1);

  audio.currentTime = 0;
  frameCallbacks.shift()(123);

  assert.equal(canvas.width, 600);
  assert.equal(canvas.height, 72);
  assert.equal(drawCalls.some(([name]) => name === "clearRect"), true);
  assert.equal(drawCalls.some(([name]) => name === "lineTo"), true);
  assert.equal(frameCallbacks.length, 1);

  dom.window.close();
});

test("compiled player centers the audio start at the waveform playhead", async () => {
  const { audio, dom, drawCalls, frameCallbacks } = startPlayer({
    waveform: true,
    waveformManifest: {
      bucket_seconds: 1,
      peak_count: 4,
    },
    waveformPeaks: [-500, 500, -1000, 1000, -250, 250, -1200, 1200],
  });

  await flushAsync();
  audio.currentTime = 0;
  frameCallbacks.shift()(123);

  const audibleXs = audibleWaveformXs(drawCalls);
  assert.equal(Math.min(...audibleXs), 300);
  assert.equal(audibleXs.some((x) => x < 300), false);

  dom.window.close();
});

test("compiled player scrolls the waveform left as soon as playback starts", async () => {
  const waveformOptions = {
    waveform: true,
    waveformManifest: {
      bucket_seconds: 1,
      peak_count: 4,
    },
    waveformPeaks: [-500, 500, -1000, 1000, -250, 250, -1200, 1200],
  };
  const zero = startPlayer(waveformOptions);
  await flushAsync();
  zero.audio.currentTime = 0;
  zero.frameCallbacks.shift()(123);
  const zeroStartX = Math.min(...audibleWaveformXs(zero.drawCalls));
  zero.dom.window.close();

  const started = startPlayer(waveformOptions);
  await flushAsync();
  started.audio.currentTime = 5;
  started.frameCallbacks.shift()(456);
  const startedStartX = Math.min(...audibleWaveformXs(started.drawCalls));

  assert.equal(startedStartX, zeroStartX - 10);
  assert.ok(startedStartX < zeroStartX);

  started.dom.window.close();
});

test("compiled player derives waveform visible time from canvas width, peak bucket size, and display density", async () => {
  const wide = startPlayer({
    waveform: true,
    waveformManifest: {
      bucket_seconds: 1,
      peak_count: 100,
    },
    waveformPeaks: Array.from({ length: 200 }, (_, index) => (index % 2 === 0 ? -800 : 800)),
    canvasWidth: 100,
  });
  await flushAsync();
  wide.audio.currentTime = 50;
  wide.frameCallbacks.shift()(456);
  const wideXs = audibleWaveformXs(wide.drawCalls);
  assert.equal(wideXs.length, 50);
  assert.deepEqual(wideXs.slice(0, 4), [0, 2, 4, 6]);
  assert.deepEqual(wideXs.slice(-4), [92, 94, 96, 98]);

  wide.dom.window.close();
});

test("compiled player seeks waveform pointers with the loaded peak bucket size and display density", async () => {
  const { audio, dom, window } = startPlayer({
    waveform: true,
    waveformManifest: {
      bucket_seconds: 1,
      peak_count: 100,
    },
    waveformPeaks: Array.from({ length: 200 }, (_, index) => (index % 2 === 0 ? -800 : 800)),
    canvasWidth: 100,
  });
  const canvas = window.document.querySelector("[data-waveform-canvas]");

  await flushAsync();
  audio.currentTime = 50;
  canvas.dispatchEvent(pointerEvent(window, "pointerdown", { clientX: 100 }));
  await Promise.resolve();

  assert.equal(audio.currentTime, 25);

  dom.window.close();
});

test("compiled player seeks from waveform pointer clicks and throttled dragging", async () => {
  const { audio, dom, frameCallbacks, scrollCalls, window } = startPlayer();
  const { document } = window;
  const canvas = document.querySelector("[data-waveform-canvas]");
  const followButton = document.querySelector("[data-follow-toggle]");
  const phrase1 = document.querySelector("#phrase-1");

  followButton.click();
  assert.equal(followButton.getAttribute("aria-pressed"), "false");

  canvas.dispatchEvent(pointerEvent(window, "pointerdown", { clientX: 120 }));
  await Promise.resolve();

  assert.equal(audio.currentTime, 0);
  assert.equal(audio.paused, false);
  assert.equal(followButton.getAttribute("aria-pressed"), "true");
  assert.equal(phrase1.classList.contains("is-active"), false);
  assert.equal(canvas.getAttribute("aria-valuenow"), "0");
  assert.equal(scrollCalls.at(-1), undefined);

  canvas.dispatchEvent(pointerEvent(window, "pointermove", { clientX: 140 }));
  canvas.dispatchEvent(pointerEvent(window, "pointermove", { clientX: 180 }));

  assert.equal(audio.currentTime, 0);
  assert.equal(frameCallbacks.length, 1);

  frameCallbacks.shift()(456);
  await Promise.resolve();

  assert.equal(audio.currentTime, 0);
  assert.equal(canvas.getAttribute("aria-valuenow"), "0");

  canvas.dispatchEvent(pointerEvent(window, "pointermove", { clientX: 700 }));

  assert.equal(frameCallbacks.length, 1);

  frameCallbacks.shift()(789);
  await Promise.resolve();

  assert.equal(audio.currentTime, 3);
  assert.equal(canvas.getAttribute("aria-valuenow"), "3");
  assert.equal(scrollCalls.at(-1).id, "phrase-1");

  canvas.dispatchEvent(pointerEvent(window, "pointerup", { clientX: 430 }));
  assert.equal(frameCallbacks.length, 0);

  dom.window.close();
});

test("compiled player supports waveform keyboard seek and play-pause controls", async () => {
  const { audio, dom, window } = startPlayer();
  const { document } = window;
  const canvas = document.querySelector("[data-waveform-canvas]");
  const playButton = document.querySelector("[data-play-toggle]");
  const phrase1 = document.querySelector("#phrase-1");

  audio.currentTime = 0;
  canvas.dispatchEvent(new window.KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    key: "ArrowRight",
  }));
  await Promise.resolve();

  assert.equal(audio.currentTime, 5);
  assert.equal(audio.paused, false);
  assert.equal(phrase1.classList.contains("is-active"), true);
  assert.equal(canvas.getAttribute("aria-valuenow"), "5");

  canvas.dispatchEvent(new window.KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    key: "ArrowLeft",
  }));
  await Promise.resolve();

  assert.equal(audio.currentTime, 0);
  assert.equal(canvas.getAttribute("aria-valuenow"), "0");

  canvas.dispatchEvent(new window.KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    key: " ",
  }));

  assert.equal(audio.paused, true);
  assert.equal(playButton.textContent, "Play");

  canvas.dispatchEvent(new window.KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    key: " ",
  }));
  await Promise.resolve();

  assert.equal(audio.paused, false);
  assert.equal(playButton.textContent, "Pause");

  dom.window.close();
});
