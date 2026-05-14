import assert from "node:assert/strict";
import { existsSync, readFileSync } from "node:fs";
import test from "node:test";

test("transcript player is built from ClojureScript into the committed web asset", () => {
  assert.equal(existsSync("package.json"), true, "package.json should define npm tooling");
  assert.equal(existsSync("shadow-cljs.edn"), true, "shadow-cljs.edn should define the player build");
  assert.equal(existsSync("src-cljs/epp/player.cljs"), true, "browser source should live in ClojureScript");
  assert.equal(existsSync("web_assets/player.js"), true, "legacy handwritten player source should remain available");
  assert.equal(existsSync("web/assets/player.js"), true, "production player asset should be committed");
  assert.equal(existsSync("web/assets/player.js.map"), false, "source maps should not be committed to web by default");

  const packageJson = JSON.parse(readFileSync("package.json", "utf8"));
  assert.equal(packageJson.scripts?.["build:player"], "shadow-cljs release player");
  assert.equal(packageJson.scripts?.["test:player"], "node --test test/player_*.test.mjs");

  const shadowConfig = readFileSync("shadow-cljs.edn", "utf8");
  assert.match(shadowConfig, /:output-dir\s+"web\/assets"/);
  assert.match(shadowConfig, /:modules\s+\{:player/);
});
