import fs from 'node:fs';
import path from 'node:path';

const roots = ['core/build/test-results', 'app/build/test-results', 'app/build/outputs/androidTest-results'];
const files = [];
function scan(directory) {
  if (!fs.existsSync(directory)) return;
  for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
    const target = path.join(directory, entry.name);
    if (entry.isDirectory()) scan(target);
    else if (entry.isFile() && entry.name.endsWith('.xml') && fs.statSync(target).size <= 2 * 1024 * 1024) files.push(target);
  }
}
roots.forEach(scan);
const clean = value => value.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&quot;/g, '"').replace(/&apos;/g, "'").replace(/&amp;/g, '&').replace(/\u001b\[[0-9;]*m/g, '').replace(/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/g, '');
const summary = ['### Android verification', '', '| Suite | Tests | Failures | Errors | Skipped |', '| --- | ---: | ---: | ---: | ---: |'];
let failures = [];
const ocrReports = [];
for (const file of files) {
  const xml = fs.readFileSync(file, 'utf8');
  const tag = xml.match(/<testsuite\s[^>]+>/)?.[0];
  if (!tag) continue;
  const attribute = name => clean(tag.match(new RegExp(`${name}="([^"]*)"`))?.[1] ?? '0').replace(/\|/g, '\\|');
  const label = file.startsWith('app/build/outputs/androidTest-results') ? 'Android device (all instrumentation tests)' : attribute('name');
  summary.push(`| ${label} | ${attribute('tests')} | ${attribute('failures')} | ${attribute('errors')} | ${attribute('skipped')} |`);
  // Only this wholly invented native fixture emits OCR metrics. Do not
  // forward arbitrary instrumentation stdout or user data into CI summaries.
  if (xml.includes('jp.n624.takupoke.android.RecoveryOcrNativeTest')) {
    for (const match of clean(xml).matchAll(/TAKUPOKE_OCR_REPORT (\{[^\r\n]*?\})/g)) {
      try {
        const report = JSON.parse(match[1]);
        if (report.syntheticOnly === true && report.script === 'Japanese' && Array.isArray(report.texts) && report.texts.every(text => typeof text === 'string' && text.length <= 64)) ocrReports.push(JSON.stringify(report));
      } catch { /* An incomplete metric never becomes proof of OCR quality. */ }
    }
  }
  for (const match of xml.matchAll(/<(failure|error)\b([^>]*?)(?:\/>|>([\s\S]*?)<\/\1>)/g)) {
    const message = match[2].match(/message="([^"]*)"/)?.[1] ?? '';
    failures.push(clean([message, (match[3] ?? '').replace(/^<!\[CDATA\[|\]\]>$/g, '')].filter(Boolean).join('\n')).split('\n').slice(0, 60).join('\n'));
  }
}
if (files.length === 0) summary.push('| No test report produced | — | — | — | — |');
if (ocrReports.length) summary.push('', 'Invented Japanese OCR observations:', '```json', ...ocrReports, '```');
if (failures.length) summary.push('', 'Failure details (synthetic test data only):', '```text', ...failures, '```');
summary.push('', 'No school files, real accounts or production credentials are used. No Actions dependency/AVD caches or build artifacts are retained.', '');
const output = summary.join('\n');
process.stdout.write(output);
if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, output);
