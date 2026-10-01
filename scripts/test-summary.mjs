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
for (const file of files) {
  const xml = fs.readFileSync(file, 'utf8');
  const tag = xml.match(/<testsuite\s[^>]+>/)?.[0];
  if (!tag) continue;
  const attribute = name => clean(tag.match(new RegExp(`${name}="([^"]*)"`))?.[1] ?? '0').replace(/\|/g, '\\|');
  summary.push(`| ${attribute('name')} | ${attribute('tests')} | ${attribute('failures')} | ${attribute('errors')} | ${attribute('skipped')} |`);
  for (const match of xml.matchAll(/<(failure|error)\b[^>]*>([\s\S]*?)<\/\1>/g)) failures.push(clean(match[2].replace(/^<!\[CDATA\[|\]\]>$/g, '')).split('\n').slice(0, 60).join('\n'));
}
if (files.length === 0) summary.push('| No test report produced | — | — | — | — |');
if (failures.length) summary.push('', 'Failure details (synthetic test data only):', '```text', ...failures, '```');
summary.push('', 'No school files, real accounts or production credentials are used. No Actions dependency/AVD caches or build artifacts are retained.', '');
const output = summary.join('\n');
process.stdout.write(output);
if (process.env.GITHUB_STEP_SUMMARY) fs.appendFileSync(process.env.GITHUB_STEP_SUMMARY, output);
