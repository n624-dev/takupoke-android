import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';

// Screens from a fresh offline test install only. No artifact/cache storage or production data.
const marker = 'TAKUPOKE_SCREENSHOT ';
const names = ['00-setup', '01-home', '02-links', '03-timetable', '04-settings', '05-materials',
  'recovery-preview', 'recovery-original', 'recovery-models', 'recovery-exam', 'recovery-return'].map(name => `${name}.png`);
const magic = Buffer.from('89504e470d0a1a0a', 'hex');
const limit = 2 * 1024 * 1024;
const digest = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const valid = bytes => bytes.length > 8 && bytes.length <= limit && bytes.subarray(0, 8).equals(magic);
const emit = record => process.stdout.write(marker + JSON.stringify(record) + '\n');
const [mode, source, destination] = process.argv.slice(2);
if (mode === 'encode') {
  for (const name of names) {
    const bytes = fs.readFileSync(path.join(source, name));
    if (!valid(bytes)) throw Error(`Invalid PNG: ${name}`);
    emit({ type: 'begin', name, size: bytes.length, sha256: digest(bytes) });
    const data = bytes.toString('base64');
    for (let offset = 0; offset < data.length; offset += 2048) emit({ type: 'chunk', name, offset, data: data.slice(offset, offset + 2048) });
    emit({ type: 'end', name });
  }
} else if (mode === 'decode') {
  if (!destination) throw Error('Usage: node scripts/screenshot-transfer.mjs decode JOB_LOG PRIVATE_OUTPUT_DIRECTORY');
  const log = fs.readFileSync(source, 'utf8');
  if (Buffer.byteLength(log) > 32 * 1024 * 1024) throw Error('Log too large');
  const pending = new Map();
  const completed = new Map();
  for (const line of log.split('\n')) {
    const index = line.indexOf(marker);
    if (index < 0) continue;
    const record = JSON.parse(line.slice(index + marker.length));
    if (!names.includes(record.name)) throw Error('Unexpected screenshot name');
    if (record.type === 'begin') {
      if (pending.has(record.name) || completed.has(record.name) || !Number.isInteger(record.size) || record.size > limit || record.size <= 8 || !/^[a-f0-9]{64}$/.test(record.sha256)) throw Error('Invalid screenshot header');
      pending.set(record.name, { ...record, data: '' });
    } else if (record.type === 'chunk') {
      const current = pending.get(record.name);
      if (!current || record.offset !== current.data.length || typeof record.data !== 'string' || !/^[A-Za-z0-9+/]{1,2048}={0,2}$/.test(record.data) || current.data.length + record.data.length > Math.ceil(limit / 3) * 4) throw Error(`Invalid screenshot chunk: ${record.name}, expected offset ${current?.data.length ?? 'no header'}, received ${record.offset}, chunk length ${typeof record.data === 'string' ? record.data.length : 'non-string'}`);
      current.data += record.data;
    } else if (record.type === 'end') {
      const current = pending.get(record.name);
      if (!current) throw Error('Missing screenshot header');
      const bytes = Buffer.from(current.data, 'base64');
      if (!valid(bytes) || bytes.length !== current.size || digest(bytes) !== current.sha256) throw Error('Screenshot checksum mismatch');
      pending.delete(record.name); completed.set(record.name, bytes);
    } else throw Error('Unknown screenshot record');
  }
  if (pending.size || completed.size !== names.length) throw Error('Incomplete screenshot run');
  fs.mkdirSync(destination, { recursive: true, mode: 0o700 });
  for (const [name, bytes] of completed) {
    fs.writeFileSync(path.join(destination, name), bytes, { flag: 'wx', mode: 0o600 });
    process.stdout.write(`${name}: SHA-256 verified\n`);
  }
} else throw Error('Usage: screenshot-transfer.mjs encode DIRECTORY | decode JOB_LOG PRIVATE_OUTPUT_DIRECTORY');
