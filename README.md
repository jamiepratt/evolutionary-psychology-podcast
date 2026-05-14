# Evolutionary Psychology Podcast transcript site

Static, timestamped episode transcripts for the Evolutionary Psychology Podcast.

## Published site

The GitHub Pages workflow builds the transcript player, regenerates the static
site with Babashka, and deploys only the contents of `web/` on every push to
`main`.

## Build commands

Install the JavaScript dependencies once:

```sh
npm ci
```

Build the ClojureScript transcript player:

```sh
npm run build:player
```

Regenerate the local static site outputs:

```sh
bb extract-metadata
bb build-chunks-manifest
bb merge-transcripts
bb validate-outputs
bb generate-episode-page
```

## Local preview

After running the build commands, serve the generated `web/` directory:

```sh
python3 -m http.server 8000 --directory web
```

Then open `http://localhost:8000`.

## Notes

Source and intermediate audio are intentionally ignored by Git. The deployable
episode audio used by the site is kept in `web/assets/audio/`.
