import fs from "node:fs";
import path from "node:path";
import { execFileSync } from "node:child_process";

const chunksDir = "audio/chunks";
const outPath = "audio/chunks-manifest.json";
const expectedChunkSeconds = Number(process.env.CHUNK_SECONDS ?? 1200);
const files = fs
  .readdirSync(chunksDir)
  .filter((name) => name.endsWith(".mp3"))
  .sort();

function probeDuration(filePath) {
  const output = execFileSync("ffprobe", [
    "-v",
    "error",
    "-show_entries",
    "format=duration",
    "-of",
    "default=noprint_wrappers=1:nokey=1",
    filePath,
  ], { encoding: "utf8" });
  return Number(output.trim());
}

let offset = 0;
const chunks = files.map((name, index) => {
  const filePath = path.join(chunksDir, name);
  const stat = fs.statSync(filePath);
  const duration = probeDuration(filePath);
  const chunk = {
    index,
    path: filePath,
    start_offset_seconds: Number(offset.toFixed(3)),
    duration_seconds: Number(duration.toFixed(3)),
    byte_size: stat.size,
  };
  offset += duration || expectedChunkSeconds;
  return chunk;
});

const manifest = {
  source_audio: "audio/processed/leda-cosmides-normalized.mp3",
  chunk_seconds_target: expectedChunkSeconds,
  max_upload_bytes: 25 * 1024 * 1024,
  chunk_count: chunks.length,
  total_duration_seconds: Number(offset.toFixed(3)),
  chunks,
};

fs.writeFileSync(outPath, `${JSON.stringify(manifest, null, 2)}\n`);
console.log(`Wrote ${outPath}`);
