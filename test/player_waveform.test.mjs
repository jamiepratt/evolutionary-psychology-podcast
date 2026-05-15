import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import test from "node:test";
import { JSDOM } from "jsdom";

const baseHtml = `<!doctype html>
<html>
<body>
  <header>
    <audio id="episode-audio"></audio>
    <button type="button" data-follow-toggle aria-pressed="true">Following transcript</button>
    <div id="episode-waveform" data-waveform-url="/waveform.json" data-waveform-duration="60"></div>
  </header>
  <main>
    <section class="transcript-shell" aria-label="Transcript">
      <article class="turn" id="turn-0">
        <p><span class="phrase" id="phrase-0" data-start="0" data-end="8" role="button" tabindex="0">Hello.</span></p>
      </article>
      <article class="turn" id="turn-1">
        <p><span class="phrase" id="phrase-1" data-start="14" data-end="20" role="button" tabindex="0">Middle.</span></p>
      </article>
      <article class="turn" id="turn-2">
        <p><span class="phrase" id="phrase-2" data-start="34" data-end="40" role="button" tabindex="0">Later.</span></p>
      </article>
    </section>
  </main>
</body>
</html>`;

const waveform = {
  schema_version: 1,
  duration_seconds: 60,
  resolutions: {
    fine: {
      window_seconds: 1,
      peaks: Array.from({ length: 60 }, (_, index) => [
        -0.2 - ((index % 4) * 0.1),
        0.25 + ((index % 5) * 0.1),
      ]),
    },
  },
};

function waveformWith(overrides) {
  return {
    ...windowSafeClone(waveform),
    ...overrides,
  };
}

function windowSafeClone(value) {
  return JSON.parse(JSON.stringify(value));
}

function transcriptHtml({ waveformAttrs = 'data-waveform-url="/waveform.json" data-waveform-duration="60"', phraseCount = 3 } = {}) {
  const phrases = Array.from({ length: phraseCount }, (_, index) => {
    const start = index === 0 ? 0 : index === 1 ? 14 : index === 2 ? 34 : index * 10;
    const end = start + (index < 3 ? [8, 6, 6][index] : 4);
    const text = index === 0 ? "Hello." : index === 1 ? "Middle." : index === 2 ? "Later." : `Phrase ${index}.`;
    return `      <article class="turn" id="turn-${index}">
        <p><span class="phrase" id="phrase-${index}" data-start="${start}" data-end="${end}" role="button" tabindex="0">${text}</span></p>
      </article>`;
  }).join("\n");

  return `<!doctype html>
<html>
<body>
  <header>
    <audio id="episode-audio"></audio>
    <button type="button" data-follow-toggle aria-pressed="true">Following transcript</button>
    <div id="episode-waveform" ${waveformAttrs}></div>
  </header>
  <main>
    <section class="transcript-shell" aria-label="Transcript">
${phrases}
    </section>
  </main>
</body>
</html>`;
}

function startPlayer({ fetchImpl, html = baseHtml, animationFrame = "timeout" } = {}) {
  const dom = new JSDOM(html, {
    pretendToBeVisual: true,
    runScripts: "outside-only",
    url: "https://example.test/episodes/fixture/",
  });
  const { window } = dom;
  const drawCalls = [];
  const animationFrames = [];
  const resizeObservers = [];

  window.Element.prototype.scrollIntoView = function scrollIntoView() {};
  window.HTMLCanvasElement.prototype.getContext = function getContext(kind) {
    assert.equal(kind, "2d");
    return {
      clearRect: (...args) => drawCalls.push(["clearRect", ...args]),
      fillRect: (...args) => drawCalls.push(["fillRect", ...args]),
      set fillStyle(value) {
        drawCalls.push(["fillStyle", value]);
      },
    };
  };
  window.HTMLCanvasElement.prototype.getBoundingClientRect = function getBoundingClientRect() {
    return {
      left: 100,
      right: 500,
      top: 20,
      bottom: 116,
      width: 400,
      height: 96,
    };
  };
  window.ResizeObserver = class ResizeObserver {
    constructor(callback) {
      this.callback = callback;
      resizeObservers.push(this);
    }
    observe() {}
    disconnect() {}
  };
  if (animationFrame === "manual") {
    window.requestAnimationFrame = (callback) => {
      animationFrames.push(callback);
      return animationFrames.length;
    };
    window.cancelAnimationFrame = () => {};
  } else {
    window.requestAnimationFrame = (callback) => window.setTimeout(callback, 0);
    window.cancelAnimationFrame = (id) => window.clearTimeout(id);
  }
  window.fetch = fetchImpl
    ? ((url) => fetchImpl(url, window))
    : (() => Promise.resolve({
    ok: true,
    json: () => Promise.resolve(window.JSON.parse(window.JSON.stringify(waveform))),
  }));

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
    audio.dispatchEvent(new window.Event("pause"));
  };

  window.eval(readFileSync("web/assets/player.js", "utf8"));

  const flushAnimationFrame = () => {
    const callbacks = animationFrames.splice(0);
    callbacks.forEach((callback) => callback(window.performance.now()));
  };
  const triggerResize = () => {
    resizeObservers.forEach((observer) => observer.callback([]));
  };

  return {
    audio,
    dom,
    drawCalls,
    flushAnimationFrame,
    triggerResize,
    window,
  };
}

test("compiled player mounts a read-only waveform that follows playback and active phrases", async () => {
  const fetchUrls = [];
  let resolveFetch;
  const fetchPromise = new Promise((resolve) => {
    resolveFetch = resolve;
  });
  const { audio, dom, drawCalls, window } = startPlayer({
    fetchImpl: (url, window) => {
      fetchUrls.push(url);
      return fetchPromise.then((response) => ({
        ...response,
        json: () => response.json().then((json) => window.JSON.parse(JSON.stringify(json))),
      }));
    },
  });
  const { document } = window;
  const mount = document.querySelector("#episode-waveform");

  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(fetchUrls.length, 1);
  assert.equal(fetchUrls[0].endsWith("/waveform.json"), true);
  assert.equal(mount.dataset.waveformState, "loading");
  assert.equal(mount.dataset.followMode, "true");
  assert.equal(mount.querySelector("canvas") instanceof window.HTMLCanvasElement, true);

  document.querySelector("#phrase-2").click();
  await Promise.resolve();
  assert.equal(audio.currentTime, 34);
  assert.equal(document.querySelector("#phrase-2").classList.contains("is-active"), true);
  assert.equal(mount.dataset.waveformState, "loading");

  resolveFetch({
    ok: true,
    json: () => Promise.resolve(waveform),
  });
  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.waveformState, "ready");

  audio.currentTime = 17;
  audio.dispatchEvent(new window.Event("timeupdate"));
  await new Promise((resolve) => window.setTimeout(resolve, 10));

  assert.equal(document.querySelector("#phrase-1").classList.contains("is-active"), true);
  assert.equal(mount.dataset.activeSegmentId, "phrase-1");
  assert.equal(Number(mount.dataset.visibleStart) < 17, true);
  assert.equal(Number(mount.dataset.visibleEnd) > 17, true);
  assert.equal(drawCalls.some(([method]) => method === "fillRect"), true);

  dom.window.close();
});

test("waveform drawing is coalesced behind animation frames", async () => {
  const { audio, dom, drawCalls, flushAnimationFrame, window } = startPlayer({
    animationFrame: "manual",
  });
  const { document } = window;
  const mount = document.querySelector("#episode-waveform");

  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.waveformState, "loading");
  assert.equal(drawCalls.length, 0);

  flushAnimationFrame();
  assert.equal(mount.dataset.waveformState, "ready");
  const firstFrameDraws = drawCalls.length;
  assert.equal(firstFrameDraws > 0, true);

  audio.currentTime = 10;
  audio.dispatchEvent(new window.Event("timeupdate"));
  audio.currentTime = 11;
  audio.dispatchEvent(new window.Event("timeupdate"));
  audio.currentTime = 12;
  audio.dispatchEvent(new window.Event("timeupdate"));

  assert.equal(drawCalls.length, firstFrameDraws);
  flushAnimationFrame();
  assert.equal(drawCalls.length > firstFrameDraws, true);
  assert.equal(Number(mount.dataset.visibleStart) <= 12, true);
  assert.equal(Number(mount.dataset.visibleEnd) >= 12, true);

  dom.window.close();
});

test("resize redraws the ready waveform on a later animation frame", async () => {
  const { dom, drawCalls, flushAnimationFrame, triggerResize, window } = startPlayer({
    animationFrame: "manual",
  });
  const mount = window.document.querySelector("#episode-waveform");

  await new Promise((resolve) => window.setTimeout(resolve, 10));
  flushAnimationFrame();
  assert.equal(mount.dataset.waveformState, "ready");
  const beforeResize = drawCalls.length;

  triggerResize();
  assert.equal(drawCalls.length, beforeResize);
  flushAnimationFrame();
  assert.equal(drawCalls.length > beforeResize, true);
  assert.equal(mount.dataset.waveformState, "ready");

  dom.window.close();
});

test("large transcript lists only draw visible waveform segment overlays", async () => {
  const { dom, drawCalls, window } = startPlayer({
    html: transcriptHtml({ phraseCount: 120 }),
  });
  const mount = window.document.querySelector("#episode-waveform");

  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.waveformState, "ready");
  assert.equal(Number(mount.dataset.visibleStart), 0);
  assert.equal(Number(mount.dataset.visibleEnd), 30);

  const overlayStyles = drawCalls.filter((call) => (
    call[0] === "fillStyle"
    && (call[1] === "rgba(15, 118, 110, 0.12)" || call[1] === "rgba(244, 185, 66, 0.24)")
  ));
  assert.equal(overlayStyles.length <= 3, true);

  dom.window.close();
});

test("waveform click seeks by visible position and pauses follow until recentered", async () => {
  const { audio, dom, window } = startPlayer();
  const { document } = window;
  const mount = document.querySelector("#episode-waveform");

  await new Promise((resolve) => window.setTimeout(resolve, 10));
  const canvas = mount.querySelector("canvas");
  assert.equal(canvas.getAttribute("tabindex"), "0");
  assert.equal(mount.dataset.waveformState, "ready");
  assert.equal(mount.dataset.windowSeconds, "30");
  assert.equal(mount.dataset.followMode, "true");

  canvas.dispatchEvent(new window.MouseEvent("click", {
    bubbles: true,
    clientX: 300,
  }));
  await new Promise((resolve) => window.setTimeout(resolve, 10));

  assert.equal(audio.currentTime, 15);
  assert.equal(mount.dataset.followMode, "false");
  assert.equal(document.querySelector("[data-follow-toggle]").getAttribute("aria-pressed"), "false");

  audio.currentTime = 45;
  audio.dispatchEvent(new window.Event("timeupdate"));
  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.followMode, "false");
  assert.equal(Number(mount.dataset.visibleStart), 0);
  assert.equal(Number(mount.dataset.visibleEnd), 30);

  document.querySelector("[data-waveform-recenter]").click();
  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.followMode, "true");
  assert.equal(document.querySelector("[data-follow-toggle]").getAttribute("aria-pressed"), "true");
  assert.equal(Number(mount.dataset.visibleStart) < 45, true);
  assert.equal(Number(mount.dataset.visibleEnd) > 45, true);

  dom.window.close();
});

test("waveform exposes window presets and keyboard playback controls", async () => {
  const { audio, dom, window } = startPlayer();
  const { document } = window;
  const mount = document.querySelector("#episode-waveform");

  await new Promise((resolve) => window.setTimeout(resolve, 10));
  const canvas = mount.querySelector("canvas");
  const twoMinutePreset = document.querySelector("[data-waveform-window][data-window-seconds='120']");
  const fiveMinutePreset = document.querySelector("[data-waveform-window][data-window-seconds='300']");
  assert.equal(Boolean(document.querySelector("[data-waveform-window][data-window-seconds='30']")), true);
  assert.equal(Boolean(twoMinutePreset), true);
  assert.equal(Boolean(fiveMinutePreset), true);

  twoMinutePreset.click();
  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.windowSeconds, "120");
  fiveMinutePreset.click();
  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.windowSeconds, "300");

  canvas.dispatchEvent(new window.KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    key: " ",
  }));
  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(audio.paused, false);

  canvas.dispatchEvent(new window.KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    key: "Enter",
  }));
  assert.equal(audio.paused, true);

  audio.currentTime = 20;
  canvas.dispatchEvent(new window.KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    key: "ArrowRight",
  }));
  assert.equal(audio.currentTime > 20, true);
  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.followMode, "false");

  canvas.dispatchEvent(new window.KeyboardEvent("keydown", {
    bubbles: true,
    cancelable: true,
    key: "ArrowLeft",
  }));
  assert.equal(audio.currentTime < 35, true);
  await new Promise((resolve) => window.setTimeout(resolve, 10));

  dom.window.close();
});

test("waveform fetch failures fall back without breaking transcript seeking", async () => {
  const { audio, dom, window } = startPlayer({
    fetchImpl: () => Promise.reject(new Error("network unavailable")),
  });
  const { document } = window;
  const mount = document.querySelector("#episode-waveform");
  const phrase2 = document.querySelector("#phrase-2");

  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.waveformState, "fallback");
  assert.equal(mount.dataset.waveformError, "load-failed");

  phrase2.click();
  await Promise.resolve();
  assert.equal(audio.currentTime, 34);
  assert.equal(phrase2.classList.contains("is-active"), true);

  dom.window.close();
});

test("missing waveform URL leaves transcript behavior available without fetching", async () => {
  const fetchUrls = [];
  const { audio, dom, window } = startPlayer({
    html: transcriptHtml({ waveformAttrs: "" }),
    fetchImpl: (url) => {
      fetchUrls.push(url);
      return Promise.reject(new Error("should not fetch"));
    },
  });
  const { document } = window;
  const mount = document.querySelector("#episode-waveform");
  const phrase1 = document.querySelector("#phrase-1");

  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.deepEqual(fetchUrls, []);
  assert.equal(mount.dataset.waveformState, undefined);
  assert.equal(mount.querySelector("canvas"), null);

  phrase1.click();
  await Promise.resolve();
  assert.equal(audio.currentTime, 14);
  assert.equal(phrase1.classList.contains("is-active"), true);

  dom.window.close();
});

test("malformed waveform data falls back without breaking active highlighting", async () => {
  const { audio, dom, window } = startPlayer({
    fetchImpl: () => Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ duration_seconds: 60, resolutions: {} }),
    }),
  });
  const { document } = window;
  const mount = document.querySelector("#episode-waveform");
  const phrase1 = document.querySelector("#phrase-1");

  await new Promise((resolve) => window.setTimeout(resolve, 10));
  assert.equal(mount.dataset.waveformState, "fallback");
  assert.equal(mount.dataset.waveformError, "malformed");

  audio.currentTime = 17;
  audio.dispatchEvent(new window.Event("timeupdate"));
  assert.equal(phrase1.classList.contains("is-active"), true);
  assert.equal(phrase1.getAttribute("aria-current"), "true");

  dom.window.close();
});

test("malformed peak entries and zero durations do not publish invalid window values", async () => {
  const badWaveforms = [
    {
      duration_seconds: 60,
      resolutions: {
        fine: {
          window_seconds: 1,
          peaks: [[-0.2, 0.4], ["not-a-number", 0.6]],
        },
      },
    },
    waveformWith({ duration_seconds: 0 }),
  ];

  for (const badWaveform of badWaveforms) {
    const { dom, window } = startPlayer({
      fetchImpl: () => Promise.resolve({
        ok: true,
        json: () => Promise.resolve(badWaveform),
      }),
    });
    const mount = window.document.querySelector("#episode-waveform");

    await new Promise((resolve) => window.setTimeout(resolve, 10));
    assert.equal(mount.dataset.waveformState, "fallback");
    assert.equal(mount.dataset.waveformError, "malformed");
    assert.notEqual(mount.dataset.visibleStart, "NaN");
    assert.notEqual(mount.dataset.visibleEnd, "Infinity");
    assert.notEqual(mount.dataset.visibleEnd, "NaN");

    dom.window.close();
  }
});

test("mobile waveform CSS uses the simplified non-precision layout", () => {
  const css = readFileSync("web/assets/styles.css", "utf8");

  assert.match(css, /@media \(max-width: 720px\)[\s\S]*\.waveform-window-controls\s*\{\s*display: none;/);
  assert.match(css, /@media \(max-width: 720px\)[\s\S]*#episode-waveform canvas\s*\{\s*height: 64px;/);
});
