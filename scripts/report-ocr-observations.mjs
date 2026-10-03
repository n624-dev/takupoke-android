import fs from 'node:fs';
const input = fs.readFileSync(0, 'utf8');
if (Buffer.byteLength(input) > 4 * 1024 * 1024) throw new Error('Invented OCR observation exceeds log bound');
const expected = ['script', 'texts', 'confidenceScores', 'confidenceSupported', 'inputState', 'rules', 'verifiedBlankCells', 'syntheticOnly'];
const reports = [];
for (const line of input.split(/\r?\n/).map(line => line.trim()).filter(line => line.startsWith('{'))) {
  let report;
  try { report = JSON.parse(line); } catch { throw new Error('Malformed invented OCR observation'); }
  const keys = [...line.matchAll(/"(?:\\.|[^"\\])*"\s*:/g)].map(match => JSON.parse(match[0].slice(0, match[0].lastIndexOf(':')).trim()));
  const valid = keys.length === expected.length && new Set(keys).size === keys.length && expected.every(key => Object.hasOwn(report, key)) &&
    Object.keys(report).length === expected.length && report.syntheticOnly === true && report.script === 'Japanese' &&
    Array.isArray(report.texts) && report.texts.length > 0 && report.texts.length <= 128 && report.texts.every(text => typeof text === 'string' && text.length <= 64 && !/[\u0000-\u001f]/.test(text)) &&
    Array.isArray(report.confidenceScores) && report.confidenceScores.length > 0 && report.confidenceScores.length <= 1024 &&
    report.confidenceScores.every(score => typeof score === 'number' && Number.isFinite(score) && score >= -1 && score <= 1 || ['NaN', 'Infinity', '-Infinity'].includes(score)) &&
    typeof report.confidenceSupported === 'boolean' && ['COMPLETE', 'PARTIAL'].includes(report.inputState) &&
    Number.isInteger(report.rules) && report.rules >= 0 && report.rules <= 100000 && Number.isInteger(report.verifiedBlankCells) && report.verifiedBlankCells >= 0 && report.verifiedBlankCells <= 100000;
  if (!valid) throw new Error('Invalid invented OCR observation');
  reports.push(report);
}
if (reports.length !== 1) throw new Error('Expected exactly one invented Japanese OCR observation');
const serialized = JSON.stringify(reports[0]);
process.stdout.write(`TAKUPOKE_OCR_REPORT ${serialized}\n`);
if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, `\nInvented native Japanese OCR observation:\n\n\`\`\`json\n${serialized}\n\`\`\`\n`);
