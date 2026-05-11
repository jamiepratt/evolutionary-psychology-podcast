# Evolutionary Psychology Podcast transcript site

Static, timestamped episode transcripts for the Evolutionary Psychology Podcast.

## Published site

The GitHub Pages workflow deploys the contents of `web/` on every push to `main`.

## Local preview

```sh
python3 -m http.server 8000 --directory web
```

Then open `http://localhost:8000`.

## Notes

Source and intermediate audio are intentionally ignored by Git. The deployable
episode audio used by the site is kept in `web/assets/audio/`.

