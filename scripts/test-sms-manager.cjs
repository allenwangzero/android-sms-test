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
  const calls = [], requests = new Map();
  let postHook = options.postHook;
  const api = async (path, init = {}) => {
    calls.push({ path, init });
    if (init.method === 'POST') {
      const payload = JSON.parse(init.body);
      if (postHook) return postHook(payload, requests);
      const old = requests.get(payload.requestId);
      if (old) return response(old);
      const request = { id: payload.requestId, deviceId: payload.deviceId, action: payload.action, status: payload.action === 'list' ? 'completed' : 'ready', count: payload.action === 'list' ? 51 : 51, processed: 0, deleted: 0, error: '', result: payload.action === 'list' ? { total: 51, page: payload.page, pageSize: 50, rows: payload.page === 0 ? Array.from({ length: 50 }, (_, index) => row(index + 1)) : [row(51)] } : { count: 51, preview: [row(1)], selectionMode: payload.selection.mode } };
      requests.set(payload.requestId, request); return response(request);
    }
    const request = requests.get(path.split('/').pop());
    if (!request) throw error(404);
    return response(request);
  };
  window.eval(source);
  const manager = window.initSmsManager({ api, uuid: () => `request-${++sequence}`, scope: options.scope || 'origin|token' });
  await manager.ready;
  manager.setDevices([{ id: 'phone-1', name: 'Test phone' }, { id: 'phone-2', name: 'Second phone' }]);
  const el = id => window.document.getElementById(`sm-${id}`);
  async function select(id = 'phone-1') { el('device').value = id; el('device').dispatchEvent(new window.Event('change')); await sleep(); await sleep(); }
  function input(id, value) { el(id).value = value; el(id).dispatchEvent(new window.Event('input')); }
  async function load() { el('load').click(); await until(() => !el('load').disabled); }
  function posts() { return calls.filter(call => call.init.method === 'POST').map(call => JSON.parse(call.init.body)); }
  return { window, manager, el, select, input, load, posts, requests, calls, setPostHook(value) { postHook = value; }, close() { manager.dispose(); } };
}
(async () => {
  let tests = 0;
  async function test(name, run) { await run(); console.log(`PASS ${name}`); tests++; }
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
  console.log(`${tests} SMS manager tests passed`);
})().catch(error => { console.error(error); process.exitCode = 1; });
