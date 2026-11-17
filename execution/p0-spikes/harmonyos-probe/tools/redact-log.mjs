import { readFileSync, writeFileSync } from 'node:fs';
import { redactLogLineJson } from '../entry/src/main/ets/logic/LogRedactor';

const [, , inputPath, outputPath] = process.argv;

if (!inputPath) {
  console.error('Usage: node --import ./tests/resolve-ts.mjs ./tools/redact-log.mjs <raw-log.jsonl> [output.jsonl]');
  process.exit(1);
}

const raw = readFileSync(inputPath, 'utf8');
const lines = raw.split(/\r?\n/).filter((line) => line.trim().length > 0);
const redacted = lines.map((line) => redactLogLineJson(line)).join('\n') + (lines.length > 0 ? '\n' : '');

if (outputPath) {
  writeFileSync(outputPath, redacted, 'utf8');
} else {
  process.stdout.write(redacted);
}
