import fs from "node:fs";

const manifest = JSON.parse(fs.readFileSync("audio/chunks-manifest.json", "utf8"));
const combined = JSON.parse(fs.readFileSync("transcripts/combined/leda-cosmides-diarized-combined.json", "utf8"));
const allowed = new Set(["DPz", "DPi", "LC", "UNK"]);
const maxBytes = 25 * 1024 * 1024;

const failures = [];
if (Math.abs(manifest.total_duration_seconds - 7431) > 90) {
  failures.push(`Duration ${manifest.total_duration_seconds}s is not close to 7431s.`);
}
for (const chunk of manifest.chunks) {
  if (chunk.byte_size >= maxBytes) {
    failures.push(`${chunk.path} is ${chunk.byte_size} bytes, above the 25 MB limit.`);
  }
}
for (let i = 1; i < combined.segments.length; i += 1) {
  if (combined.segments[i].start < combined.segments[i - 1].start) {
    failures.push(`Non-monotonic timestamp at segment ${i}.`);
    break;
  }
}
for (const segment of combined.segments) {
  if (!allowed.has(segment.speaker)) {
    failures.push(`Unexpected speaker label ${segment.speaker}.`);
    break;
  }
}

const report = {
  checked_at: new Date().toISOString(),
  chunk_count: manifest.chunk_count,
  duration_seconds: manifest.total_duration_seconds,
  max_chunk_bytes: Math.max(...manifest.chunks.map((chunk) => chunk.byte_size)),
  segment_count: combined.segments.length,
  speaker_labels: [...new Set(combined.segments.map((segment) => segment.speaker))].sort(),
  failures,
};

fs.writeFileSync("transcripts/combined/validation-report.json", `${JSON.stringify(report, null, 2)}\n`);
if (failures.length) {
  console.error(JSON.stringify(report, null, 2));
  process.exit(1);
}
console.log(JSON.stringify(report, null, 2));
