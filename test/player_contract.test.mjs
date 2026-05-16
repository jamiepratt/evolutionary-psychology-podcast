import assert from "node:assert/strict";
import { existsSync, readFileSync, readdirSync, statSync } from "node:fs";
import path from "node:path";
import test from "node:test";

test("transcript player is built from ClojureScript into the committed web asset", () => {
  assert.equal(existsSync("package.json"), true, "package.json should define npm tooling");
  assert.equal(existsSync("shadow-cljs.edn"), true, "shadow-cljs.edn should define the player build");
  assert.equal(existsSync("src-cljs/epp/player.cljs"), true, "browser source should live in ClojureScript");
  const legacyPlayerPath = path.join("web_assets", "player.js");
  assert.equal(existsSync(legacyPlayerPath), false, "legacy handwritten player source should be retired");
  assert.equal(existsSync("web/assets/player.js"), true, "production player asset should be committed");
  assert.equal(existsSync("web/assets/player.js.map"), false, "source maps should not be committed to web by default");

  const packageJson = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(packageJson.scripts?.["build:player"], "shadow-cljs release player");
  assert.equal(packageJson.scripts?.["test:player"], "node --test test/player_*.test.mjs");

  const shadowConfig = readFileSync("shadow-cljs.edn", "utf8");
  assert.match(shadowConfig, /:output-dir\s+"web\/assets"/);
  assert.match(shadowConfig, /:modules\s+\{:player/);
});

test("legacy Node pipeline scripts and handwritten player source are retired", () => {
  const scriptDir = "scripts";
  const legacyScriptFiles = existsSync(scriptDir)
    ? readdirSync(scriptDir).filter((name) => path.extname(name) === ".mjs")
    : [];
  assert.deepEqual(legacyScriptFiles, []);
  const legacyPlayerPath = path.join("web_assets", "player.js");
  assert.equal(existsSync(legacyPlayerPath), false);
  assert.equal(existsSync("web/assets/player.js"), true, "compiled player asset should stay deployable");

  const legacyScriptNames = [
    ["build", "chunks", "manifest"].join("_"),
    ["extract", "episode", "metadata"].join("_"),
    ["generate", "episode", "page"].join("_"),
    ["merge", "transcripts"].join("_"),
    ["transcribe", "chunks"].join("_"),
    ["validate", "outputs"].join("_"),
  ].map((name) => `${name}.mjs`);
  const searchableFiles = [
    "README.md",
    "bb.edn",
    "package.json",
    "shadow-cljs.edn",
    ".github/workflows/pages.yml",
    "src/epp/pipeline/episode_page.clj",
    "test/epp/pipeline_test.clj",
  ];
  for (const file of searchableFiles) {
    const contents = readFileSync(file, "utf8");
    for (const legacyScriptName of legacyScriptNames) {
      assert.equal(
        contents.includes(legacyScriptName),
        false,
        `${file} should not reference retired ${legacyScriptName}`,
      );
    }
  }
});

test("generated static web output includes deployable player and waveform assets", () => {
  assert.equal(existsSync("web/assets/styles.css"), true, "production CSS should be deployable");
  assert.equal(existsSync("web/assets/player.js"), true, "production player JS should be deployable");

  const episodeRoot = path.join("web", "episodes");
  const episodeSlugs = readdirSync(episodeRoot)
    .filter((name) => statSync(path.join(episodeRoot, name)).isDirectory());

  assert.notEqual(episodeSlugs.length, 0, "at least one episode should be generated");

  for (const slug of episodeSlugs) {
    const episodeDir = path.join(episodeRoot, slug);
    const html = readFileSync(path.join(episodeDir, "index.html"), "utf8");
    const manifest = JSON.parse(readFileSync(path.join(episodeDir, "waveform.json"), "utf8"));
    const audioMatch = html.match(/src="\.\.\/\.\.\/assets\/audio\/([^"]+)"/);
    const peaksPath = path.join(episodeDir, manifest.peaks);

    assert.equal(existsSync(path.join(episodeDir, "transcript.json")), true, `${slug} transcript should be deployable`);
    assert.equal(existsSync(peaksPath), true, `${slug} waveform peaks should be deployable`);
    assert.ok(statSync(peaksPath).size > 0, `${slug} waveform peaks should not be empty`);
    assert.equal(manifest.peak_format, "s16le-min-max");
    assert.equal(manifest.bits_per_peak, 16);
    assert.ok(audioMatch, `${slug} HTML should reference episode audio`);
    assert.equal(existsSync(path.join("web", "assets", "audio", audioMatch[1])), true, `${slug} audio should be deployable`);
  }
});
