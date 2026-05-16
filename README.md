# Evolutionary Psychology Podcast transcript site

Static, timestamped episode transcripts for the Evolutionary Psychology Podcast.

## Published site

The GitHub Pages workflow builds the transcript player, regenerates the static
site from committed transcript artifacts with Babashka, and deploys only the
contents of `web/` on every push to `main`.

## Build commands

Install local command-line tools used by the transcript and waveform pipeline:

```sh
ffmpeg -version
ffprobe -version
```

`ffmpeg` generates the deployable waveform peak files for the custom player.
`ffprobe` is only needed when rebuilding `audio/chunks-manifest.json` from local
source audio.

Install the JavaScript dependencies once:

```sh
npm ci
```

Build the ClojureScript transcript player:

```sh
npm run build:player
```

Regenerate the local static site outputs from committed transcript artifacts:

```sh
bb extract-metadata
bb merge-transcripts
bb validate-outputs
bb generate-episode-page
```

If you have the ignored local source audio under `audio/chunks/`, regenerate
`audio/chunks-manifest.json` first:

```sh
bb build-chunks-manifest
```

## Local preview

Start a local development server with live ClojureScript rebuilds:

```sh
npm run dev
```

Then open `http://localhost:8000`.

Shadow watch mode writes development assets under `web/assets/`; run
`npm run build:player` before committing if you want to restore the production
player bundle.

## ClojureScript REPL

`npm run dev` also starts a Shadow nREPL on port `7888`. After opening the
local site in a browser, connect to that port and select the `:player` build:

```sh
clj-nrepl-eval -p 7888 "(require '[shadow.cljs.devtools.api :as shadow]) (shadow/nrepl-select :player)"
```

Editor REPL clients such as CIDER can connect to `localhost:7888` and choose the
Shadow `:player` build.

## Notes

Source and intermediate audio are intentionally ignored by Git. The deployable
episode audio used by the site is kept in `web/assets/audio/`.
