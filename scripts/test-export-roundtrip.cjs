'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const { spawnSync } = require('node:child_process');
const { JSDOM } = require(process.argv[2] || 'jsdom');
const root = path.join(__dirname, '..');
const dom = new JSDOM('', { runScripts: 'outside-only' });
dom.window.eval(fs.readFileSync(path.join(root, 'desktop/static/sms-xml.js'), 'utf8'));
const records = Array.from({ length: 501 }, (_, index) => ({
  sender: `测试${index}`, body: `完整正文${'x'.repeat(3000)}\n\r\t<&>"'😀`,
  timestamp: 1789281245486 + index, type: 1, protocol: index % 2 ? null : 0,
  subject: index % 2 ? null : 'null', service_center: index % 2 ? 'null' : null,
  read: index % 2, status: index % 2 ? 64 : -1, locked: index % 2,
  seen: (index + 1) % 2, date_sent: 1789281245000, toa: null, sc_toa: null,
}));
const python = 'import json,sys; from desktop.sms_export import encode_document; records=json.load(sys.stdin); print(json.dumps({format:encode_document(records,format)[0].decode("utf-8") for format in ("xml","json")},ensure_ascii=False))';
const result = spawnSync('python3', ['-c', python], {
  cwd: root, input: JSON.stringify(records), encoding: 'utf8', maxBuffer: 32 * 1024 * 1024,
});
assert.equal(result.status, 0, result.stderr || String(result.error));
const documents = JSON.parse(result.stdout);
for (const [format, parser] of [['xml', 'parseSmsXml'], ['json', 'parseSmsJson']]) {
  const restored = JSON.parse(JSON.stringify(dom.window[parser](documents[format])));
  assert.deepEqual(restored, records, `${format} must retain every supported field, null and full message`);
}
dom.window.close();
console.log('501 SMS export/import roundtrip passed: XML + JSON, complete body, whitespace, Unicode, literal null, seen/date_sent');
