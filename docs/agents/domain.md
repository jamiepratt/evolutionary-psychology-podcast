# Domain Context

This repo builds and publishes a static transcript site for the Evolutionary Psychology Podcast.

## Product Surface

The published site is a GitHub Pages-hosted static site under `web/`.

Site visitors can:

- Browse episode transcript pages.
- Play episode audio.
- Click transcript phrases or timestamps to seek the audio.
- Follow the active transcript phrase while audio plays.
- Read transcript HTML without JavaScript.

## Local Pipeline

The local pipeline prepares transcript assets and deployable static files.

It includes:

- Selecting and extracting episode metadata from podcast feed data.
- Building an audio chunk manifest for transcription.
- Transcribing audio chunks through the OpenAI API.
- Merging chunk transcripts into combined transcript data.
- Producing final markdown transcript output.
- Generating episode HTML, public transcript JSON, audio assets, CSS, and browser JS for the static site.
- Validating generated transcript outputs.

Source and intermediate audio are local build inputs and are intentionally ignored by Git. Deployable audio lives under `web/assets/audio/`.

## Current Migration Direction

The project is migrating handwritten code to the Clojure ecosystem:

- Babashka for local scripts and pipeline tasks.
- ClojureScript compiled with Shadow CLJS for browser code.
- Reagent/React as a progressive enhancement layer over static transcript HTML.
- Generated browser JavaScript committed under `web/assets/player.js`.
- GitHub Pages remains the hosting target.

The migration should preserve current behavior, file formats, output paths, and deployable static-site structure unless a later PRD explicitly changes them.

## Constraints

- Keep the site readable without JavaScript.
- Keep GitHub Pages deployment simple.
- Treat generated transcript/audio artifacts carefully because they can be large or expensive to regenerate.
- Avoid redesigning the site as part of infrastructure or language migrations unless explicitly scoped.
