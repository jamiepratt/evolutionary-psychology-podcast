# Leda Cosmides Transcript Implementation Plan

## Summary
Transcribe **“Founding Evolutionary Psychology with Leda Cosmides”** from *Evolutionary Psychology (the podcast)* using `gpt-4o-transcribe-diarize`, preserving all intermediate artifacts. Source audio comes from the podcast feed first, with YouTube/`yt-dlp` only as fallback.

Final transcript style: readable edited Markdown with named speaker initials:
- `DPz`: Dave Pietraszewski
- `DPi`: David Pinsof
- `LC`: Leda Cosmides
- `UNK`: uncertain speaker, only if needed

## Implementation
- Create artifact directories:
  - `sources/` for feed metadata and episode metadata.
  - `audio/original/` for original downloaded audio.
  - `audio/processed/` for normalized audio.
  - `audio/chunks/` for upload chunks.
  - `audio/speaker_refs/` for 2-10 second known-speaker clips.
  - `transcripts/raw_chunks/` for per-chunk diarized JSON.
  - `transcripts/combined/` for merged raw JSON.
  - `transcripts/final/` for final Markdown transcript.

- Resolve and download audio:
  - Fetch `https://feed.podbean.com/epthepod/feed.xml`.
  - Select title exactly matching `Founding Evolutionary Psychology with Leda Cosmides`.
  - Save RSS XML and selected episode metadata.
  - Download the enclosure audio to `audio/original/`.
  - If RSS fails, use Podbean page/download link; if that fails, use YouTube with `yt-dlp`.

- Prepare audio:
  - Use `ffmpeg` to normalize to mono English speech audio.
  - Split into ~20-minute chunks below OpenAI’s 25 MB upload limit.
  - Save a `chunks-manifest.json` containing chunk path, start offset, duration, and byte size.

- Speaker references:
  - Run an initial diarization pass on a small early chunk if needed.
  - Extract clean 2-10 second clips for Dave Pietraszewski, David Pinsof, and Leda Cosmides.
  - Save clips in `audio/speaker_refs/`.
  - Save `speaker-map.json` documenting names, initials, and reference clip paths.

- Transcription:
  - Use `/v1/audio/transcriptions`.
  - Model: `gpt-4o-transcribe-diarize`.
  - Parameters:
    - `response_format="diarized_json"`
    - `chunking_strategy="auto"`
    - `language="en"`
    - `known_speaker_names`
    - `known_speaker_references`
  - Save every per-chunk API response unchanged in `transcripts/raw_chunks/`.

- Merge and edit:
  - Combine chunk JSON into one full-episode JSON with global timestamps.
  - Map speaker names to initials.
  - Generate a readable edited Markdown transcript with metadata, speaker list, and timestamped turns.
  - Preserve raw wording in intermediate JSON; apply readable cleanup only to final Markdown.

## Test Plan
- Confirm original audio duration is approximately 2h04m.
- Confirm every chunk is under 25 MB.
- Confirm all timestamps are monotonic after merging.
- Confirm final speaker labels are only `DPz`, `DPi`, `LC`, or `UNK`.
- Spot-check:
  - First 5 minutes.
  - First Leda Cosmides answer.
  - One chunk boundary.
  - Three random 60-second windows.
- Keep all intermediate files; do not delete audio, chunks, JSON, speaker refs, manifests, or logs.

## Assumptions
- `OPENAI_API_KEY` will be available in the shell environment.
- You have permission to download and transcribe the episode.
- Podcast feed audio is preferred over YouTube audio.
- Final transcript should be readable edited, while raw diarized JSON remains unedited.
