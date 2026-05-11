import fs from "node:fs";
import path from "node:path";

const DEFAULT_CONFIG = {
  slug: "leda-cosmides",
  title: "Founding Evolutionary Psychology with Leda Cosmides",
  transcript: "transcripts/combined/leda-cosmides-diarized-combined.json",
  audio: "audio/processed/leda-cosmides-normalized.mp3",
  outDir: "web",
};

const SPEAKER_FALLBACKS = {
  DPz: "Dave Pietraszewski",
  DPi: "David Pinsof",
  LC: "Leda Cosmides",
  UNK: "Uncertain speaker",
};

function parseArgs(argv) {
  const config = { ...DEFAULT_CONFIG };
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (!arg.startsWith("--")) {
      throw new Error(`Unexpected argument: ${arg}`);
    }
    const key = arg.slice(2);
    const value = argv[i + 1];
    if (!value || value.startsWith("--")) {
      throw new Error(`Missing value for --${key}`);
    }
    if (!["slug", "title", "transcript", "audio", "outDir"].includes(key)) {
      throw new Error(`Unknown option --${key}`);
    }
    config[key] = value;
    i += 1;
  }
  return config;
}

function cleanText(value) {
  let text = String(value ?? "")
    .replace(/\s+/g, " ")
    .replace(/\s+([,.?!;:])/g, "$1")
    .trim();
  const replacements = [
    [/\bDavid Pinsoff\b/g, "David Pinsof"],
    [/\bDavid Piotr Zewski\b/g, "Dave Pietraszewski"],
    [/\bDavid Pietra Zewski\b/g, "Dave Pietraszewski"],
    [/\bLita Kosmedes\b/g, "Leda Cosmides"],
    [/\bLita Cosmedes\b/g, "Leda Cosmides"],
    [/\bLita Cosmides\b/g, "Leda Cosmides"],
    [/\bLeta Cosmedes\b/g, "Leda Cosmides"],
    [/\bLeta Cosmides\b/g, "Leda Cosmides"],
    [/\bLeda Cosmedes\b/g, "Leda Cosmides"],
    [/\bLita\b/g, "Leda"],
    [/\bLeta\b/g, "Leda"],
    [/\bLado\b/g, "Leda"],
    [/\bAlita\b/g, "Leda"],
    [/\bJohn Toobie\b/g, "John Tooby"],
    [/\bRobert Rivers\b/g, "Robert Trivers"],
    [/\bConrad Lorenzen's\b/g, "Konrad Lorenz's"],
    [/\bConrad Lorenz's\b/g, "Konrad Lorenz's"],
    [/\bIrvin Bohr\b/g, "Irven DeVore"],
    [/\bSimeon Seminar\b/g, "Simian Seminar"],
    [/\bSimeon seminar\b/g, "Simian Seminar"],
    [/\bDon Simons\b/g, "Don Symons"],
    [/\bSimon says\b/g, "Symons says"],
    [/\bsimon's\b/g, "Symons"],
    [/\bsimon said\b/g, "Symons said"],
    [/\bJohn Leda\b/g, "John and Leda"],
    [/\bTversky and Kahneman\b/g, "Tversky and Kahneman"],
    [/\bGerd Gigerenzer\b/g, "Gerd Gigerenzer"],
  ];
  for (const [pattern, replacement] of replacements) {
    text = text.replace(pattern, replacement);
  }
  return text;
}

function fmtClock(seconds) {
  const whole = Math.max(0, Math.floor(seconds));
  const h = Math.floor(whole / 3600);
  const m = Math.floor((whole % 3600) / 60);
  const s = whole % 60;
  if (h > 0) {
    return [h, m, s].map((part) => String(part).padStart(2, "0")).join(":");
  }
  return [m, s].map((part) => String(part).padStart(2, "0")).join(":");
}

function escapeHtml(value) {
  return String(value ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

function slugify(value) {
  return String(value ?? "")
    .toLowerCase()
    .replace(/[^a-z0-9]+/g, "-")
    .replace(/^-+|-+$/g, "");
}

function speakerNames(transcript) {
  const names = { ...SPEAKER_FALLBACKS };
  for (const speaker of transcript.speaker_map?.speakers ?? []) {
    if (speaker.initials && speaker.name) {
      names[speaker.initials] = speaker.name;
    }
  }
  return names;
}

function normalizeSegments(transcript) {
  return (transcript.segments ?? [])
    .map((segment, index) => ({
      id: segment.id || `segment_${index}`,
      speaker: segment.speaker || "UNK",
      start: Number(segment.start),
      end: Number(segment.end),
      text: cleanText(segment.text),
    }))
    .filter((segment) => segment.text);
}

function validateSegments(segments) {
  const failures = [];
  for (let i = 0; i < segments.length; i += 1) {
    const segment = segments[i];
    if (!Number.isFinite(segment.start) || !Number.isFinite(segment.end)) {
      failures.push(`Segment ${segment.id} has invalid timing.`);
    }
    if (segment.end < segment.start) {
      failures.push(`Segment ${segment.id} ends before it starts.`);
    }
    if (!segment.text || !segment.speaker) {
      failures.push(`Segment ${segment.id} is missing text or speaker.`);
    }
    if (i > 0 && segment.start < segments[i - 1].start) {
      failures.push(`Segment ${segment.id} starts before the previous segment.`);
    }
  }
  if (failures.length) {
    throw new Error(`Transcript validation failed:\n${failures.join("\n")}`);
  }
}

function buildTurns(segments) {
  const turns = [];
  for (const segment of segments) {
    const prior = turns.at(-1);
    if (prior && prior.speaker === segment.speaker && segment.start - prior.end < 2.5) {
      prior.end = segment.end;
      prior.phrases.push(segment);
    } else {
      turns.push({
        speaker: segment.speaker,
        start: segment.start,
        end: segment.end,
        phrases: [segment],
      });
    }
  }
  return turns;
}

function renderPhrase(segment, index) {
  return `<span class="phrase" id="phrase-${index}" data-phrase-index="${index}" data-start="${segment.start}" data-end="${segment.end}" role="button" tabindex="0">${escapeHtml(segment.text)}</span>`;
}

function renderTurn(turn, turnIndex, names, phraseOffset) {
  const speakerName = names[turn.speaker] ?? turn.speaker;
  const phrases = turn.phrases
    .map((segment, offset) => renderPhrase(segment, phraseOffset + offset))
    .join(" ");
  return `<article class="turn" id="turn-${turnIndex}" data-speaker="${escapeHtml(turn.speaker)}">
  <header class="turn-meta">
    <a class="timestamp" href="#phrase-${phraseOffset}" data-seek="${turn.start}">${fmtClock(turn.start)}</a>
    <span class="speaker-code">${escapeHtml(turn.speaker)}</span>
    <span class="speaker-name">${escapeHtml(speakerName)}</span>
  </header>
  <p>${phrases}</p>
</article>`;
}

function renderHtml({ config, transcript, publicTranscript, turns, names, audioHref }) {
  let phraseOffset = 0;
  const renderedTurns = turns
    .map((turn, turnIndex) => {
      const html = renderTurn(turn, turnIndex, names, phraseOffset);
      phraseOffset += turn.phrases.length;
      return html;
    })
    .join("\n");
  const title = config.title || transcript.metadata?.title || "Episode Transcript";
  const sourceLink = transcript.metadata?.link;
  const published = transcript.metadata?.pubDate;
  const duration = transcript.duration_seconds;
  const jsonData = JSON.stringify(publicTranscript).replace(/</g, "\\u003c");

  return `<!doctype html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <title>${escapeHtml(title)}</title>
  <link rel="stylesheet" href="../../assets/styles.css">
</head>
<body>
  <header class="player-shell">
    <div class="episode-kicker">Evolutionary Psychology Podcast</div>
    <div class="episode-heading">
      <h1>${escapeHtml(title)}</h1>
      <div class="episode-meta">
        ${published ? `<span>${escapeHtml(published)}</span>` : ""}
        ${Number.isFinite(duration) ? `<span>${fmtClock(duration)}</span>` : ""}
        ${sourceLink ? `<a href="${escapeHtml(sourceLink)}">Source episode</a>` : ""}
      </div>
    </div>
    <div class="audio-row">
      <audio id="episode-audio" controls preload="metadata" src="${escapeHtml(audioHref)}"></audio>
      <button class="follow-button" type="button" data-follow-toggle aria-pressed="true">Following transcript</button>
    </div>
  </header>

  <main class="page">
    <section class="transcript-shell" aria-label="Transcript">
      ${renderedTurns}
    </section>
  </main>

  <script id="transcript-data" type="application/json">${jsonData}</script>
  <script src="../../assets/player.js"></script>
</body>
</html>
`;
}

function copySharedAssets(outDir) {
  const assetsDir = path.join(outDir, "assets");
  fs.mkdirSync(assetsDir, { recursive: true });
  for (const name of ["player.js", "styles.css"]) {
    const source = path.join("web_assets", name);
    const target = path.join(assetsDir, name);
    fs.copyFileSync(source, target);
  }
}

function main() {
  const config = parseArgs(process.argv.slice(2));
  const transcript = JSON.parse(fs.readFileSync(config.transcript, "utf8"));
  const segments = normalizeSegments(transcript);
  validateSegments(segments);

  const names = speakerNames(transcript);
  const turns = buildTurns(segments);
  const outDir = config.outDir;
  const episodeDir = path.join(outDir, "episodes", config.slug);
  const audioDir = path.join(outDir, "assets", "audio");
  fs.mkdirSync(episodeDir, { recursive: true });
  fs.mkdirSync(audioDir, { recursive: true });

  copySharedAssets(outDir);

  const audioExt = path.extname(config.audio) || ".mp3";
  const audioFile = `${config.slug}${audioExt}`;
  const audioTarget = path.join(audioDir, audioFile);
  fs.copyFileSync(config.audio, audioTarget);

  const publicTranscript = {
    slug: config.slug,
    title: config.title || transcript.metadata?.title || "Episode Transcript",
    source: transcript.metadata?.link ?? null,
    published: transcript.metadata?.pubDate ?? null,
    duration_seconds: transcript.duration_seconds ?? null,
    speaker_names: names,
    segments,
    turns: turns.map((turn) => ({
      speaker: turn.speaker,
      start: turn.start,
      end: turn.end,
      phrase_ids: turn.phrases.map((segment) => segment.id),
    })),
  };

  fs.writeFileSync(
    path.join(episodeDir, "transcript.json"),
    `${JSON.stringify(publicTranscript, null, 2)}\n`,
  );

  const html = renderHtml({
    config,
    transcript,
    publicTranscript,
    turns,
    names,
    audioHref: `../../assets/audio/${audioFile}`,
  });
  fs.writeFileSync(path.join(episodeDir, "index.html"), html);

  console.log(`Generated ${path.join(episodeDir, "index.html")}`);
  console.log(`Phrases: ${segments.length}`);
  console.log(`Turns: ${turns.length}`);
}

main();
