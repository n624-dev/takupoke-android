import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import { spawnSync } from 'node:child_process';
import assert from 'node:assert/strict';

const temporary = fs.mkdtempSync(path.join(os.tmpdir(), 'takupoke-screenshot-transfer-'));
const names = ['00-setup', '01-home', '02-links', '03-timetable', '04-settings', '05-materials',
  'recovery-preview', 'recovery-original', 'recovery-models', 'recovery-exam', 'recovery-return'];
// Independent one-pixel PNG fixture. No captured user or school information.
const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+a5v8AAAAASUVORK5CYII=', 'base64');
const run = args => spawnSync(process.execPath, ['scripts/screenshot-transfer.mjs', ...args], { encoding: 'utf8' });
try {
  const source = path.join(temporary, 'source'); fs.mkdirSync(source);
  names.forEach(name => fs.writeFileSync(path.join(source, `${name}.png`), png));
  const encoded = run(['encode', source]); assert.equal(encoded.status, 0, encoded.stderr);
  const log = path.join(temporary, 'job.log');
  const formatted = encoded.stdout.trimEnd().split('\n').map(line => `2026-10-02T00:00:00.0000000Z ${line}`).join('\n');
  fs.writeFileSync(log, formatted);
  const output = path.join(temporary, 'output');
  const decoded = run(['decode', log, output]); assert.equal(decoded.status, 0, decoded.stderr);
  names.forEach(name => assert.deepEqual(fs.readFileSync(path.join(output, `${name}.png`)), png));
  const invalid = [
    formatted.replace(/"sha256":"[a-f0-9]{64}"/, '"sha256":"' + '0'.repeat(64) + '"'),
    formatted.slice(0, formatted.lastIndexOf('\n')),
    formatted.replace('00-setup.png', '../outside.png'),
    formatted.replace('"offset":0', '"offset":1'),
    formatted.replace('"size":68', '"size":2097153'),
  ];
  invalid.forEach((value, index) => {
    fs.writeFileSync(log, value);
    const target = path.join(temporary, `invalid-${index}`);
    assert.notEqual(run(['decode', log, target]).status, 0);
    assert.equal(fs.existsSync(target), false, 'Invalid logs must not write partial screenshots');
  });
  console.log('Screenshot transport: round-trip and 5 invalid-log checks passed');
} finally { fs.rmSync(temporary, { recursive: true, force: true }); }
