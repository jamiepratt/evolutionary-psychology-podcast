import fs from "node:fs";
import path from "node:path";

const manifest = JSON.parse(fs.readFileSync("audio/chunks-manifest.json", "utf8"));
const metadata = JSON.parse(fs.readFileSync("sources/episode-selected.json", "utf8"));
const speakerMap = JSON.parse(fs.readFileSync("audio/speaker_refs/speaker-map.json", "utf8"));
const rawDir = "transcripts/raw_chunks";
const combinedDir = "transcripts/combined";
const finalDir = "transcripts/final";

fs.mkdirSync(combinedDir, { recursive: true });
fs.mkdirSync(finalDir, { recursive: true });

const labelToInitials = new Map();
for (const speaker of speakerMap.speakers) {
  labelToInitials.set(speaker.initials, speaker.initials);
  labelToInitials.set(speaker.name, speaker.initials);
}

function speakerLabel(label) {
  if (!label) return "UNK";
  if (label === "A" || label === "B") return "DPi";
  return labelToInitials.get(label) ?? (["DPz", "DPi", "LC"].includes(label) ? label : "UNK");
}

function fmtTime(seconds) {
  const whole = Math.max(0, Math.floor(seconds));
  const h = Math.floor(whole / 3600);
  const m = Math.floor((whole % 3600) / 60);
  const s = whole % 60;
  return [h, m, s].map((part) => String(part).padStart(2, "0")).join(":");
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

const chunks = [];
const segments = [];
for (const chunk of manifest.chunks) {
  const rawPath = path.join(rawDir, `chunk_${String(chunk.index).padStart(3, "0")}.json`);
  const raw = JSON.parse(fs.readFileSync(rawPath, "utf8"));
  chunks.push({
    index: chunk.index,
    raw_path: rawPath,
    duration_seconds: raw.duration,
    text_length: raw.text?.length ?? 0,
    segment_count: raw.segments?.length ?? 0,
    usage: raw.usage ?? null,
  });
  for (const segment of raw.segments ?? []) {
    segments.push({
      type: segment.type ?? "transcript.text.segment",
      id: `chunk_${String(chunk.index).padStart(3, "0")}_${segment.id}`,
      chunk_index: chunk.index,
      start: Number((chunk.start_offset_seconds + Number(segment.start ?? 0)).toFixed(3)),
      end: Number((chunk.start_offset_seconds + Number(segment.end ?? 0)).toFixed(3)),
      speaker: speakerLabel(segment.speaker),
      source_speaker: segment.speaker ?? null,
      text: segment.text ?? "",
    });
  }
}

segments.sort((a, b) => a.start - b.start || a.end - b.end);

const combined = {
  task: "transcribe",
  metadata,
  speaker_map: speakerMap,
  fallback_speaker_label_overrides: {
    A: "DPi",
    B: "DPi",
  },
  manifest: "audio/chunks-manifest.json",
  raw_chunks: chunks,
  duration_seconds: manifest.total_duration_seconds,
  text: segments.map((segment) => segment.text).join(" ").replace(/\s+/g, " ").trim(),
  segments,
};

fs.writeFileSync(
  path.join(combinedDir, "leda-cosmides-diarized-combined.json"),
  `${JSON.stringify(combined, null, 2)}\n`,
);

const turns = [];
for (const segment of segments) {
  const text = cleanText(segment.text);
  if (!text) continue;
  const prior = turns.at(-1);
  if (prior && prior.speaker === segment.speaker && segment.start - prior.end < 2.5) {
    prior.end = segment.end;
    prior.text = cleanText(`${prior.text} ${text}`);
  } else {
    turns.push({
      speaker: segment.speaker,
      start: segment.start,
      end: segment.end,
      text,
    });
  }
}

const lines = [
  `# ${metadata.title}`,
  "",
  `Source: ${metadata.link}`,
  `Published: ${metadata.pubDate}`,
  `Duration: ${fmtTime(manifest.total_duration_seconds)}`,
  "",
  "## Speakers",
  "",
  "- DPz: Dave Pietraszewski",
  "- DPi: David Pinsof",
  "- LC: Leda Cosmides",
  "- UNK: uncertain speaker",
  "",
  "## Transcript",
  "",
];

for (const turn of turns) {
  lines.push(`**[${fmtTime(turn.start)}] ${turn.speaker}:** ${turn.text}`);
  lines.push("");
}

fs.writeFileSync(path.join(finalDir, "leda-cosmides-transcript.md"), `${lines.join("\n").trimEnd()}\n`);
console.log(`Wrote ${combinedDir}/leda-cosmides-diarized-combined.json`);
console.log(`Wrote ${finalDir}/leda-cosmides-transcript.md`);
