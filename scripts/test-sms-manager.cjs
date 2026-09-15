'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require(process.argv[2] || 'jsdom');
const { IDBFactory } = require(process.argv[3] || 'fake-indexeddb');
const source = fs.readFileSync('desktop/static/sms-manager.js', 'utf8');
const sleep = () => new Promise(resolve => setTimeout(resolve, 5));
async function until(predicate) { for (let i = 0; i < 150; i++) { if (predicate()) return; await sleep(); } throw new Error('UI condition timed out'); }
function row(id) { return { id: String(id), fingerprint: 'a'.repeat(64), sender: '<img src=x onerror=alert(1)>', body: '<script>unsafe()</script>', timestamp: 1700000000000, type: 1, read: 0, status: -1, locked: 0 }; }
function response(value) { return { json: async () => value }; }
function error(status) { const failure = new Error(`HTTP ${status}`); failure.httpStatus = status; return failure; }
let sequence = 0;
async function harness(options = {}) {
  const dom = new JSDOM('<section id="sms-manager"></section>', { url: 'http://localhost:8765', runScripts: 'outside-only' });
  const { window } = dom;
  Object.defineProperty(window, 'indexedDB', { value: options.database || new IDBFactory() });
  window.confirm = () => options.confirm !== false;
  const calls = [], requests = options.requests || new Map(), downloads = [];
  window.URL.createObjectURL = blob => { downloads.push({ blob }); return 'blob:export'; };
  window.URL.revokeObjectURL = () => {};
  window.HTMLAnchorElement.prototype.click = function () { downloads[downloads.length - 1].filename = this.download; };
  let postHook = options.postHook;
  const api = async (path, init = {}) => {
    calls.push({ path, init });
    if (init.method === 'POST') {
      const payload = JSON.parse(init.body);
      if (postHook) return postHook(payload, requests);
      const old = requests.get(payload.requestId);
      if (old) return response(old);
      if (payload.action === 'batches') {
        const batch = index => ({ jobId: `import-${index}-01234567-89ab-cdef-0123-456789abcdef`, createdAt: 1700000000000, requested: 2000, recorded: index === 0 ? 0 : 1500, status: 'completed' });
        const request = { id: payload.requestId, deviceId: payload.deviceId, action: 'batches', status: 'completed', count: null, processed: 0, deleted: 0, result: { total: 51, page: payload.page, pageSize: 50, batches: payload.page === 0 ? Array.from({ length: 50 }, (_, index) => batch(index)) : [batch(50)] } };
        requests.set(payload.requestId, request); return response(request);
      }
      const request = { id: payload.requestId, deviceId: payload.deviceId, action: payload.action, status: payload.action === 'list' ? 'completed' : 'ready', count: payload.action === 'list' ? 51 : 51, processed: 0, deleted: 0, error: '', result: payload.action === 'list' ? { total: 51, page: payload.page, pageSize: 50, rows: payload.page === 0 ? Array.from({ length: 50 }, (_, index) => row(index + 1)) : [row(51)] } : { count: 51, preview: [row(1)], selectionMode: payload.selection.mode } };
      requests.set(payload.requestId, request); return response(request);
    }
    if (path.includes('/export?')) { if (options.downloadHook) return options.downloadHook(path); return { blob: async () => new window.Blob(['full-body-from-server']) }; }
    const request = requests.get(path.split('/').pop());
    if (!request) throw error(404);
    return response(request);
  };
  window.eval(source);
  const manager = window.initSmsManager({ api, uuid: () => `request-${++sequence}`, scope: options.scope || 'origin|token', onRemoveDevice: options.onRemoveDevice });
  await manager.ready;
  manager.setDevices([{ id: 'phone-1', name: 'Test phone' }, { id: 'phone-2', name: 'Second phone' }]);
  const el = id => window.document.getElementById(`sm-${id}`);
  async function select(id = 'phone-1') { el('device').value = id; el('device').dispatchEvent(new window.Event('change')); await sleep(); await sleep(); }
  function input(id, value) { el(id).value = value; el(id).dispatchEvent(new window.Event('input')); }
  async function load() { el('load').click(); await until(() => !el('load').disabled); }
  function posts() { return calls.filter(call => call.init.method === 'POST').map(call => JSON.parse(call.init.body)); }
  return { window, manager, el, select, input, load, posts, requests, calls, downloads, setPostHook(value) { postHook = value; }, close() { manager.dispose(); } };
}
(async () => {
  let tests = 0;
  async function test(name, run) { await run(); console.log(`PASS ${name}`); tests++; }
  await test('device heartbeat labels update without losing selection, rows or active requests; names are plain text', async () => {
    const h = await harness(); try {
      const now = 1800000000000; h.window.Date.now = () => now;
      const devices = [{ id: 'phone-1', name: '<img src=x onerror=alert(1)>', lastSeen: now - 10000 }, { id: 'phone-2', name: 'Second phone' }];
      h.manager.setDevices(devices); await h.select(); await h.load(); h.el('rows').querySelector('input').click();
      assert.match(h.el('device').selectedOptions[0].textContent, / · 在线$/);
      assert.match(h.el('device-status').textContent, /最后在线：/);
      h.window.Date.now = () => now + 1; h.manager.setDevices(devices);
      assert.equal(h.el('device').value, 'phone-1'); assert.match(h.el('device').selectedOptions[0].textContent, / · 离线$/);
      assert.equal(h.el('device').querySelector('img'), null); assert.equal(h.el('rows').children.length, 50); assert.match(h.el('selection').textContent, /1 条/);
      h.el('delete').click(); await until(() => h.posts().length === 2 && !h.el('retry').disabled);
      h.manager.setDevices(devices); assert.equal(h.el('retry').hidden, false); assert.match(h.el('progress').textContent, /等待手机确认/);
      await h.select('phone-2'); assert.match(h.el('device-status').textContent, /离线 · 最后在线：未知/);
    } finally { h.close(); }
  });
  await test('manager removal uses shared callback, blocks duplicates, and retains device on cancel or failure', async () => {
    let release, count = 0;
    const h = await harness({ onRemoveDevice: async id => { assert.equal(id, 'phone-1'); count++; return new Promise(resolve => { release = resolve; }); } });
    try {
      await h.select(); h.manager.setRemovalBlocked(true); assert.equal(h.el('remove-device').disabled, true);
      h.manager.setRemovalBlocked(false); h.el('remove-device').click(); h.el('remove-device').click();
      assert.equal(count, 1); assert.equal(h.el('load').disabled, true); release(false); await sleep();
      assert.equal(h.el('device').value, 'phone-1'); assert.equal(h.el('remove-device').disabled, false);
    } finally { h.close(); }
    const failed = await harness({ onRemoveDevice: async () => { throw new Error('network error'); } });
    try { await failed.select(); failed.el('remove-device').click(); await sleep(); assert.equal(failed.el('device').value, 'phone-1'); assert.match(failed.el('notice').textContent, /移除失败/); } finally { failed.close(); }
  });
  await test('removed device clears private results and rejects late response and stale device lists', async () => {
    let release;
    const h = await harness({ postHook: payload => new Promise(resolve => { release = () => resolve(response({ id: payload.requestId, deviceId: payload.deviceId, action: 'list', status: 'completed', count: 1, result: { total: 1, page: 0, pageSize: 50, rows: [row(1)] } })); }) });
    try {
      await h.select(); h.el('load').click(); await until(() => release);
      h.el('progress').textContent = 'old operation'; h.el('meter').hidden = false;
      h.manager.setDevices([{ id: 'phone-2', name: 'Second phone' }]);
      assert.equal(h.el('device').value, ''); assert.equal(h.el('progress').textContent, ''); assert.equal(h.el('meter').hidden, true); assert.equal(h.el('notice').hidden, true);
      release(); await sleep(); await sleep(); assert.equal(h.el('rows').children.length, 0); assert.equal(h.el('progress').textContent, '');
      h.manager.setDevices([{ id: 'phone-1', name: 'stale' }, { id: 'phone-2', name: 'Second phone' }]);
      assert.equal(h.el('device').querySelector('option[value="phone-1"]'), null);
    } finally { h.close(); }
  });
  await test('only user click reads SMS; default excludes locked; text renders safely', async () => {
    const h = await harness(); try { await h.select(); assert.equal(h.calls.length, 0); await h.load(); assert.equal(h.posts()[0].filters.locked, 0); assert.equal(h.el('rows').children.length, 50); assert.equal(h.el('rows').querySelector('img,script'), null); assert.match(h.el('rows').textContent, /<script>/); } finally { h.close(); }
  });
  await test('cross-page selection preserves exact IDs and fingerprints; phone must confirm', async () => {
    const h = await harness(); try { await h.select(); await h.load(); const checkbox = h.el('rows').querySelector('input'); checkbox.click(); h.el('next').click(); await until(() => h.el('page-info').textContent.includes('第 2') && !h.el('load').disabled); h.el('rows').querySelector('input').click(); assert.match(h.el('selection').textContent, /2 条/); h.el('delete').click(); await until(() => h.posts().length === 3); const request = h.posts()[2]; assert.deepEqual(request.selection.items.map(item => item.id), ['1', '51']); assert.equal(request.selection.items[0].fingerprint.length, 64); assert.equal(request.action, 'delete'); assert.equal(request.confirmed, undefined); await until(() => h.el('progress').textContent.includes('等待手机确认')); } finally { h.close(); }
  });
  await test('filter changes clear selection immediately and require explicit reread', async () => {
    const h = await harness(); try { await h.select(); await h.load(); h.el('filter-select').click(); assert.match(h.el('selection').textContent, /全部筛选/); h.input('keyword', '%_'); assert.match(h.el('selection').textContent, /0 条/); assert.equal(h.el('rows').children.length, 0); assert.equal(h.el('delete-filter').disabled, true); assert.equal(h.posts().length, 1); await h.load(); assert.equal(h.posts()[1].filters.keyword, '%_'); } finally { h.close(); }
  });
  await test('filtered selection sends mode without downloading all SMS', async () => {
    const h = await harness(); try { await h.select(); await h.load(); h.el('filter-select').click(); h.el('delete').click(); await until(() => h.posts().length === 2); assert.deepEqual(h.posts()[1].selection, { mode: 'filtered' }); } finally { h.close(); }
  });
  await test('clear-all requires desktop confirmation and uses all types plus locked', async () => {
    const h = await harness(); try { await h.select(); h.input('sender', 'restricted'); h.el('delete-all').click(); await until(() => h.posts().length === 1); assert.deepEqual(h.posts()[0].selection, { mode: 'all' }); assert.equal(h.posts()[0].filters.locked, null); assert.equal(h.posts()[0].filters.sender, ''); } finally { h.close(); }
    const cancelled = await harness({ confirm: false }); try { await cancelled.select(); cancelled.el('delete-all').click(); await sleep(); assert.equal(cancelled.posts().length, 0); } finally { cancelled.close(); }
  });
  await test('late page result cannot contaminate changed filters', async () => {
    let release;
    const h = await harness({ postHook: payload => new Promise(resolve => { release = () => resolve(response({ id: payload.requestId, deviceId: payload.deviceId, action: 'list', status: 'completed', count: 1, processed: 0, deleted: 0, result: { total: 1, page: 0, pageSize: 50, rows: [row(1)] } })); }) });
    try { await h.select(); h.el('load').click(); await until(() => release); h.input('sender', 'changed'); release(); await until(() => !h.el('load').disabled); assert.equal(h.el('rows').children.length, 0); } finally { h.close(); }
  });
  await test('device change clears selection and rejects old device asynchronous result', async () => {
    let release;
    const h = await harness({ postHook: payload => new Promise(resolve => { release = () => resolve(response({ id: payload.requestId, deviceId: payload.deviceId, action: 'list', status: 'completed', count: 1, processed: 0, deleted: 0, result: { total: 1, page: 0, pageSize: 50, rows: [row(1)] } })); }) });
    try { await h.select(); h.el('load').click(); await until(() => release); await h.select('phone-2'); release(); await until(() => !h.el('load').disabled); assert.equal(h.el('rows').children.length, 0); assert.match(h.el('selection').textContent, /0 条/); } finally { h.close(); }
  });
  await test('uncertain network failure retries same immutable request; no background POST', async () => {
    const h = await harness({ postHook: () => { throw new Error('network down'); } });
    try { await h.select(); h.el('delete-all').click(); await until(() => h.posts().length === 1 && !h.el('retry').disabled); const original = h.posts()[0]; h.input('sender', 'newfilter'); await h.manager.poll(); assert.equal(h.posts().length, 1); h.el('retry').click(); await until(() => h.posts().length === 2); assert.deepEqual(h.posts()[1], original); } finally { h.close(); }
  });
  await test('definite 409 plus GET 404 releases rejected claim without automatic retry', async () => {
    const h = await harness({ postHook: () => { throw error(409); } });
    try { await h.select(); h.el('delete-all').click(); await until(() => h.posts().length === 1 && !h.el('load').disabled); await h.manager.poll(); assert.equal(h.posts().length, 1); assert.equal(h.el('retry').hidden, true); } finally { h.close(); }
  });
  await test('two tabs atomically claim one deletion and never create competing IDs', async () => {
    const database = new IDBFactory();
    const first = await harness({ database }); const second = await harness({ database });
    try {
      await first.select(); await second.select(); first.el('delete-all').click(); second.el('delete-all').click();
      await until(() => first.posts().length + second.posts().length === 1 && !first.el('retry').disabled && !second.el('retry').disabled);
      assert.equal(first.posts().length + second.posts().length, 1);
      assert.equal(first.el('retry').hidden, false); assert.equal(second.el('retry').hidden, false);
    } finally { first.close(); second.close(); }
  });
  await test('invalid status and date ranges never create pending or send HTTP requests', async () => {
    const h = await harness(); try { await h.select(); h.input('status-filter', '999'); await h.load(); assert.equal(h.posts().length, 0); assert.equal(h.el('retry').hidden, true); h.input('status-filter', ''); h.input('from', '2026-01-02T00:00:00'); h.input('to', '2026-01-01T00:00:00'); await h.load(); assert.equal(h.posts().length, 0); } finally { h.close(); }
  });
  await test('failed deletion displays totals, invalidates stale rows, never resumes itself', async () => {
    const h = await harness(); try { await h.select(); await h.load(); h.el('delete-filter').click(); await until(() => h.posts().length === 2 && !h.el('retry').disabled); const request = h.requests.get(h.posts()[1].requestId); Object.assign(request, { status: 'failed', processed: 21, deleted: 20, error: 'provider failure' }); await h.manager.poll(); assert.match(h.el('progress').textContent, /已处理 21 · 已删除 20/); assert.match(h.el('notice').textContent, /不会自动继续删除/); assert.equal(h.el('rows').children.length, 0); assert.equal(h.el('retry').hidden, true); assert.equal(h.posts().length, 2); } finally { h.close(); }
  });
  await test('refresh restores immutable deletion from IndexedDB and scope isolates it', async () => {
    const database = new IDBFactory(); const first = await harness({ database, postHook: () => { throw new Error('lost response'); } });
    await first.select(); first.el('delete-all').click(); await until(() => first.posts().length === 1 && !first.el('retry').disabled); const original = first.posts()[0]; first.close();
    const second = await harness({ database, postHook: () => { throw new Error('still offline'); } });
    try { await second.select(); assert.equal(second.el('retry').hidden, false); assert.equal(second.posts().length, 0); second.el('retry').click(); await until(() => second.posts().length === 1); assert.deepEqual(second.posts()[0], original); } finally { second.close(); }
    const other = await harness({ database, scope: 'origin|other-token' }); try { await other.select(); assert.equal(other.el('retry').hidden, true); assert.equal(other.calls.length, 0); } finally { other.close(); }
  });
  await test('batch inventory reads only on click, paginates and survives SMS filter edits', async () => {
    const h = await harness(); try {
      await h.select(); assert.equal(h.posts().length, 0);
      h.input('status-filter', '999'); h.el('batches-load').click();
      await until(() => h.el('batches-rows').children.length === 50 && !h.el('batches-load').disabled);
      assert.equal(h.posts()[0].action, 'batches'); assert.equal(h.posts()[0].page, 0); assert.equal(h.posts()[0].filters.status, null);
      const buttons = h.el('batches-rows').querySelectorAll('button'); assert.equal(buttons[0].disabled, true); assert.equal(buttons[1].disabled, false);
      assert.match(h.el('batches-rows').textContent, /import-1-01234567-89ab-cdef-0123-456789abcdef/);
      h.input('sender', 'new sender'); assert.equal(h.el('batches-rows').children.length, 50);
      h.el('batches-next').click(); await until(() => h.el('batches-info').textContent.includes('第 2') && !h.el('batches-load').disabled);
      assert.equal(h.posts()[1].page, 1); assert.equal(h.el('batches-rows').children.length, 1);
      h.el('batches-prev').click(); await until(() => h.el('batches-info').textContent.includes('第 1') && !h.el('batches-load').disabled);
    } finally { h.close(); }
  });
  await test('batch cleanup uses exact job ID independently of filters, displays skips and invalidates history on failure', async () => {
    const h = await harness(); try {
      await h.select(); h.el('batches-load').click(); await until(() => !h.el('batches-load').disabled);
      h.input('status-filter', '999'); h.el('batches-rows').querySelectorAll('button')[1].click();
      await until(() => h.posts().length === 2 && !h.el('retry').disabled);
      const payload = h.posts()[1]; assert.deepEqual(payload.selection, { mode: 'batch', jobId: 'import-1-01234567-89ab-cdef-0123-456789abcdef' });
      assert.equal(payload.filters.status, null); assert.equal(payload.filters.locked, null); assert.equal(payload.confirmed, undefined);
      const request = h.requests.get(payload.requestId);
      Object.assign(request, { status: 'failed', processed: 8, deleted: 8, error: 'provider stopped', result: { selectionMode: 'batch', missing: 2, changed: 3 } });
      await h.manager.poll(); assert.match(h.el('progress').textContent, /已不存在跳过 2 · 已变化跳过 3/);
      assert.equal(h.el('batches-rows').children.length, 0); assert.equal(h.el('retry').hidden, true);
      await h.manager.poll(); assert.equal(h.posts().length, 2);
    } finally { h.close(); }
  });
  await test('batch cleanup requires desktop confirmation', async () => {
    const h = await harness({ confirm: false }); try {
      await h.select(); h.el('batches-load').click(); await until(() => !h.el('batches-load').disabled);
      h.el('batches-rows').querySelectorAll('button')[1].click(); await sleep(); assert.equal(h.posts().length, 1);
    } finally { h.close(); }
  });
  await test('late batch response survives filter edits but never contaminates another device', async () => {
    for (const switchDevice of [false, true]) {
      let release;
      const h = await harness({ postHook: payload => new Promise(resolve => { release = () => resolve(response({ id: payload.requestId, deviceId: payload.deviceId, action: 'batches', status: 'completed', count: null, result: { total: 1, page: 0, pageSize: 50, batches: [{ jobId: 'batch-one', createdAt: 1700000000000, requested: 10, recorded: 8, status: 'failed' }] } })); }) });
      try {
        await h.select(); h.el('batches-load').click(); await until(() => release);
        if (switchDevice) await h.select('phone-2'); else h.input('keyword', 'changed');
        release(); await until(() => !h.el('batches-load').disabled);
        assert.equal(h.el('batches-rows').children.length, switchDevice ? 0 : 1);
      } finally { h.close(); }
    }
  });
  await test('uncertain batch cleanup preserves exact request and never automatically reposts', async () => {
    const h = await harness(); try {
      await h.select(); h.el('batches-load').click(); await until(() => !h.el('batches-load').disabled);
      h.setPostHook(() => { throw new Error('connection lost'); });
      h.el('batches-rows').querySelectorAll('button')[1].click(); await until(() => h.posts().length === 2 && !h.el('retry').disabled);
      const original = h.posts()[1]; await h.manager.poll(); assert.equal(h.posts().length, 2);
      h.el('retry').click(); await until(() => h.posts().length === 3); assert.deepEqual(h.posts()[2], original);
    } finally { h.close(); }
  });
  await test('batch cleanup shares atomic per-device claims across tabs', async () => {
    const database = new IDBFactory(); const first = await harness({ database }); const second = await harness({ database });
    try {
      await first.select(); await second.select();
      first.el('batches-load').click(); await until(() => !first.el('batches-load').disabled);
      second.el('batches-load').click(); await until(() => !second.el('batches-load').disabled);
      first.el('batches-rows').querySelectorAll('button')[1].click(); second.el('delete-all').click();
      await until(() => first.posts().length + second.posts().length === 3 && !first.el('retry').disabled && !second.el('retry').disabled);
      assert.equal([...first.posts(), ...second.posts()].filter(payload => payload.action === 'delete').length, 1);
    } finally { first.close(); second.close(); }
  });
  await test('refresh restores batch cleanup without creating a new request', async () => {
    const database = new IDBFactory(); const first = await harness({ database });
    let original;
    try {
      await first.select(); first.el('batches-load').click(); await until(() => !first.el('batches-load').disabled);
      first.setPostHook(() => { throw new Error('lost response'); });
      first.el('batches-rows').querySelectorAll('button')[1].click(); await until(() => first.posts().length === 2 && !first.el('retry').disabled);
      original = first.posts()[1];
    } finally { first.close(); }
    const second = await harness({ database, postHook: () => { throw new Error('still offline'); } });
    try {
      await second.select(); assert.equal(second.el('retry').hidden, false); assert.equal(second.posts().length, 0);
      second.el('retry').click(); await until(() => second.posts().length === 1); assert.deepEqual(second.posts()[0], original);
    } finally { second.close(); }
  });
  function exportStatus(payload, status = 'queued', count = 501) {
    return { id: payload.requestId, deviceId: payload.deviceId, action: 'export', status, count, processed: status === 'completed' ? count : 0, deleted: 0, result: status === 'completed' ? { count, batchSize: 500, batchCount: Math.ceil(count / 500) } : null };
  }
  await test('export selected sends IDs and fingerprints only, no truncated row content or delete confirmation', async () => {
    const h = await harness({ confirm: false }); try {
      await h.select(); await h.load(); h.el('rows').querySelector('input').click();
      h.setPostHook((payload, requests) => { const request = exportStatus(payload); requests.set(payload.requestId, request); return response(request); });
      h.el('export').click(); await until(() => h.posts().length === 2 && !h.el('retry').disabled);
      assert.equal(h.posts()[1].action, 'export'); assert.deepEqual(h.posts()[1].selection, { mode: 'selected', items: [{ id: '1', fingerprint: 'a'.repeat(64) }] });
      assert.equal(h.el('export-files').hidden, true); assert.equal(h.el('delete-all').disabled, true);
    } finally { h.close(); }
  });
  await test('filtered export and all-inbox export use separate scopes; all ignores invalid filters and locked default', async () => {
    for (const mode of ['filter', 'all']) {
      const h = await harness(); try {
        await h.select(); await h.load(); h.input('keyword', 'literal'); await h.load();
        if (mode === 'all') h.input('status-filter', '999');
        h.setPostHook(payload => response(exportStatus(payload, 'completed')));
        h.el(`export-${mode}`).click(); await until(() => h.posts().length === 3 && !h.el('load').disabled);
        const payload = h.posts()[2]; assert.deepEqual(payload.selection, { mode: mode === 'all' ? 'all' : 'filtered' });
        assert.equal(payload.filters.locked, mode === 'all' ? null : 0); assert.equal(payload.filters.keyword, mode === 'all' ? '' : 'literal');
      } finally { h.close(); }
    }
  });
  await test('full export beyond page size downloads server blob only after completion and survives filter edits', async () => {
    const h = await harness({ postHook: (payload, requests) => { const value = exportStatus(payload); requests.set(payload.requestId, value); return response(value); } }); try {
      await h.select(); h.el('export-all').click(); await until(() => h.posts().length === 1 && !h.el('retry').disabled);
      assert.equal(h.el('export-files').hidden, true); const payload = h.posts()[0];
      Object.assign(h.requests.get(payload.requestId), { status: 'running', processed: 500 }); await h.manager.poll();
      assert.match(h.el('progress').textContent, /总计 501 · 已上传 500/); assert.equal(h.el('meter').hidden, false);
      Object.assign(h.requests.get(payload.requestId), exportStatus(payload, 'completed')); await h.manager.poll();
      assert.equal(h.el('export-files').hidden, false); h.input('keyword', 'changed');
      h.el('export-xml').click(); await until(() => h.downloads.length === 1);
      assert.equal(h.downloads[0].filename, `sms-export-${payload.requestId}.xml`); assert.equal(h.downloads[0].blob.size, 21);
      assert.match(h.calls.at(-1).path, /export\?format=xml$/); assert.equal(h.posts().length, 1);
      h.el('export-json').click(); await until(() => h.downloads.length === 2); assert.match(h.calls.at(-1).path, /format=json$/);
    } finally { h.close(); }
  });
  await test('failed and incomplete exports never enable downloads or automatically POST', async () => {
    for (const status of ['failed', 'interrupted', 'completed']) {
      const h = await harness({ postHook: payload => response({ ...exportStatus(payload, status), processed: 1 }) }); try {
        await h.select(); h.el('export-all').click(); await until(() => h.posts().length === 1 && !h.el('retry').disabled);
        assert.equal(h.el('export-files').hidden, true); assert.equal(h.el('export-xml').disabled, true);
        await h.manager.poll(); assert.equal(h.posts().length, 1);
      } finally { h.close(); }
    }
  });
  await test('export retries and refresh retain the original request; completion persists download buttons', async () => {
    const database = new IDBFactory(), requests = new Map();
    const first = await harness({ database, requests, postHook: (payload, records) => { records.set(payload.requestId, exportStatus(payload)); throw new Error('lost response'); } });
    let original;
    try { await first.select(); first.el('export-all').click(); await until(() => first.posts().length === 1 && !first.el('retry').disabled); original = first.posts()[0]; first.el('retry').click(); await until(() => first.posts().length === 2); assert.deepEqual(first.posts()[1], original); } finally { first.close(); }
    const second = await harness({ database, requests });
    try { await second.select(); assert.equal(second.posts().length, 0); assert.equal(second.el('retry').hidden, false); Object.assign(requests.get(original.requestId), exportStatus(original, 'completed')); await second.manager.poll(); assert.equal(second.el('export-files').hidden, false); } finally { second.close(); }
    const third = await harness({ database, requests });
    try { await third.select(); assert.equal(third.el('export-files').hidden, false); assert.equal(third.posts().length, 0); await third.select('phone-2'); assert.equal(third.el('export-files').hidden, true); } finally { third.close(); }
  });
  await test('late export response and late download never contaminate another device', async () => {
    let release;
    const h = await harness({ postHook: payload => new Promise(resolve => { release = () => resolve(response(exportStatus(payload, 'completed'))); }) });
    try { await h.select(); h.el('export-all').click(); await until(() => release); await h.select('phone-2'); release(); await until(() => !h.el('load').disabled); assert.equal(h.el('export-files').hidden, true); } finally { h.close(); }
    let releaseDownload;
    const second = await harness({ postHook: payload => response(exportStatus(payload, 'completed')), downloadHook: () => new Promise(resolve => { releaseDownload = () => resolve({ blob: async () => new second.window.Blob(['data']) }); }) });
    try { await second.select(); second.el('export-all').click(); await until(() => !second.el('export-files').hidden); second.el('export-json').click(); await until(() => releaseDownload); await second.select('phone-2'); releaseDownload(); await sleep(); assert.equal(second.downloads.length, 0); } finally { second.close(); }
  });
  await test('download errors never save an error response or create a new export', async () => {
    const h = await harness({ postHook: payload => response(exportStatus(payload, 'completed')), downloadHook: () => { throw error(404); } });
    try { await h.select(); h.el('export-all').click(); await until(() => !h.el('export-files').hidden); h.el('export-xml').click(); await until(() => h.el('notice').textContent.includes('下载失败')); assert.equal(h.downloads.length, 0); assert.equal(h.posts().length, 1); assert.equal(h.el('export-xml').disabled, false); } finally { h.close(); }
  });
  await test('export and deletion share one atomic per-device claim across tabs', async () => {
    const database = new IDBFactory(); const first = await harness({ database }); const second = await harness({ database });
    try { await first.select(); await second.select(); first.el('export-all').click(); second.el('delete-all').click(); await until(() => first.posts().length + second.posts().length === 1 && !first.el('retry').disabled && !second.el('retry').disabled); assert.equal(first.posts().length + second.posts().length, 1); } finally { first.close(); second.close(); }
  });
  console.log(`${tests} SMS manager tests passed`);
})().catch(error => { console.error(error); process.exitCode = 1; });
