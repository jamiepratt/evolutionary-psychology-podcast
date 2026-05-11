import fs from "node:fs";
import path from "node:path";
import { spawnSync } from "node:child_process";

const manifestPath = process.argv[2] ?? "audio/chunks-manifest.json";
const speakerMapPath = process.argv[3] ?? "audio/speaker_refs/speaker-map.json";
const outDir = "transcripts/raw_chunks";
const model = "gpt-4o-transcribe-diarize";

const apiKey = process.env.OPENAI_API_KEY;
if (!apiKey) {
  throw new Error("OPENAI_API_KEY is not set.");
}

const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
const speakerMap = fs.existsSync(speakerMapPath)
  ? JSON.parse(fs.readFileSync(speakerMapPath, "utf8"))
  : { speakers: [] };
const onlyChunks = process.env.ONLY_CHUNKS
  ? new Set(process.env.ONLY_CHUNKS.split(",").map((value) => Number(value.trim())))
  : null;
const outputPrefix = process.env.OUTPUT_PREFIX ?? "chunk";

function dataUrl(filePath) {
  const ext = path.extname(filePath).toLowerCase();
  const mime = ext === ".wav" ? "audio/wav" : "audio/mpeg";
  const encoded = fs.readFileSync(filePath).toString("base64");
  return `data:${mime};base64,${encoded}`;
}

const references = (speakerMap.speakers ?? [])
  .filter((speaker) => speaker.reference_clip)
  .map((speaker) => ({
    label: speaker.initials,
    reference: dataUrl(speaker.reference_clip),
  }));

fs.mkdirSync(outDir, { recursive: true });

for (const chunk of manifest.chunks) {
  if (onlyChunks && !onlyChunks.has(chunk.index)) continue;
  const outputPath = path.join(outDir, `${outputPrefix}_${String(chunk.index).padStart(3, "0")}.json`);
  if (fs.existsSync(outputPath)) {
    console.log(`Skipping existing ${outputPath}`);
    continue;
  }

  console.log(`Transcribing ${chunk.path} -> ${outputPath}`);
  const args = [
    "--silent",
    "--show-error",
    "--fail-with-body",
    "--max-time",
    "3600",
    "https://api.openai.com/v1/audio/transcriptions",
    "--header",
    `Authorization: Bearer ${apiKey}`,
    "--form",
    `file=@${chunk.path};type=audio/mpeg`,
    "--form-string",
    `model=${model}`,
    "--form-string",
    "response_format=diarized_json",
    "--form-string",
    "chunking_strategy=auto",
    "--form-string",
    "language=en",
  ];
  for (const item of references) {
    args.push("--form-string", `known_speaker_names[]=${item.label}`);
    args.push("--form-string", `known_speaker_references[]=${item.reference}`);
  }

  const result = spawnSync("curl", args, {
    encoding: "utf8",
    maxBuffer: 1024 * 1024 * 200,
  });
  const body = result.stdout ?? "";
  if (result.status !== 0) {
    const errorPath = outputPath.replace(/\.json$/, ".error.txt");
    fs.writeFileSync(errorPath, `${body}\n${result.stderr ?? ""}`.trimStart());
    throw new Error(`OpenAI API error or curl failure; wrote ${errorPath}`);
  }
  fs.writeFileSync(outputPath, body.endsWith("\n") ? body : `${body}\n`);
}
