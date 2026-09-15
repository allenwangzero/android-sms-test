'use strict';
const assert = require('node:assert/strict');
const fs = require('node:fs');
const { JSDOM } = require(process.argv[2] || 'jsdom');
const source = fs.readFileSync('desktop/static/app.js', 'utf8').replace(/\ninitialize\(\);\s*$/, '\n');
const html = fs.readFileSync('desktop/static/index.html', 'utf8');
const fixture = [{ id: 'phone-1', name: '<img src=x onerror=alert(1)>', lastSeen: Date.now() }, { id: 'phone-2', name: 'Second phone', lastSeen: Date.now() }];
const response = value => ({ json: async () => value });
function harness(options = {}) {
  const dom = new JSDOM(html, { url: 'http://localhost:8765', runScripts: 'outside-only' });
  const { window } = dom, calls = [], confirmations = [], managementStates = [];
  let remoteDevices = fixture.slice(), pending = options.pending || null;
  window.confirm = text => { confirmations.push(text); return options.confirm !== false; };
  window.smsManager = { setDevices: value => managementStates.push(value), setRemovalBlocked: value => { window.managerBlocked = value; } };
  window.mockApi = async (path, init = {}) => {
    calls.push({ path, init });
    if (path === '/api/state') return options.stateHook ? options.stateHook() : response({ devices: remoteDevices, jobs: [] });
    if (options.removeHook) return options.removeHook(path, init);
    remoteDevices = remoteDevices.filter(item => !path.includes(item.id)); return response({ ok: true });
  };
  window.mockPending = async (action, payload) => {
    if (action === 'clear' && pending?.requestId === payload.requestId) pending = null;
    return pending;
  };
  window.fixture = fixture;
  window.eval(source + `
    api = window.mockApi; pendingOperation = window.mockPending;
    devices = window.fixture.slice(); selected.add('phone-1'); connected = true; draftLoaded = true; pendingStorageReady = true;
    window.deviceTest = { removeDevice, refreshState, renderDevices, updateSend,
      getDevices: () => devices, getSelected: () => [...selected],
      setBusy: (value) => { sending = value; updateSend(); },
      setPreparing: (value) => { preparing = value; updateSend(); },
      setPending: (value) => { retryPayload = value; }, getPending: () => retryPayload };
    renderDevices();
  `);
  return { window, ui: window.deviceTest, calls, confirmations, managementStates, el: id => window.document.getElementById(id), close: () => window.close() };
}
(async () => {
  let count = 0;
  async function test(name, run) { await run(); count++; console.log(`PASS ${name}`); }
  await test('device removal requires confirmation; buttons are outside labels and names render safely', async () => {
    const h = harness({ confirm: false }); try {
      assert.equal(h.el('devices').querySelector('img'), null);
      assert.equal(h.el('devices').querySelector('label button'), null);
      assert.match(h.el('devices').textContent, /<img src=x/);
      assert.equal(await h.ui.removeDevice('phone-1'), false); assert.equal(h.calls.length, 0);
      assert.match(h.confirmations[0], /不删除手机短信/); assert.match(h.confirmations[0], /整次上传会取消/);
      assert.match(h.confirmations[0], /旧批次记录不再关联/); assert.match(h.confirmations[0], /下一次连接时才停止/);
      assert.equal(h.ui.getDevices().length, 2); assert.equal(h.ui.getSelected().length, 1);
    } finally { h.close(); }
  });
  await test('successful removal clears device and pending upload selection without touching draft or SMS APIs', async () => {
    const pending = { requestId: 'upload-1', deviceIds: ['phone-1', 'phone-2'], messages: [{ body: 'draft' }] };
    const h = harness({ pending }); try {
      h.ui.setPending(pending); assert.equal(await h.ui.removeDevice('phone-1'), true);
      assert.equal(h.calls[0].path, '/api/devices/phone-1/remove'); assert.equal(h.calls[0].init.method, 'POST'); assert.equal(h.calls[0].init.body, '{}');
      assert.equal(h.calls.filter(call => call.init.method === 'POST').length, 1);
      assert.equal(h.ui.getDevices().length, 1); assert.equal(h.ui.getSelected().length, 0);
      assert.equal(h.ui.getPending(), null); assert.equal(h.el('retry-box').hidden, true);
      assert.equal(h.managementStates.at(-1).length, 1); assert.match(h.el('notice').textContent, /设备已移除/);
    } finally { h.close(); }
  });
  await test('uncertain failure retains device and selection, never automatically reposts', async () => {
    const h = harness({ removeHook: () => { throw new Error('network unavailable'); } }); try {
      assert.equal(await h.ui.removeDevice('phone-1'), false);
      assert.equal(h.ui.getDevices().length, 2); assert.equal(h.ui.getSelected().length, 1);
      assert.equal(h.calls.length, 1); assert.match(h.el('notice').textContent, /不会自动重试/);
      assert.equal(h.el('devices').querySelector('button').disabled, false);
    } finally { h.close(); }
  });
  await test('removal blocks duplicate actions and sending or preparing blocks removal', async () => {
    let release;
    const h = harness({ removeHook: () => new Promise(resolve => { release = () => resolve(response({ ok: true })); }) }); try {
      h.ui.setBusy(true); assert.equal(await h.ui.removeDevice('phone-1'), false);
      h.ui.setBusy(false); h.ui.setPreparing(true); assert.equal(await h.ui.removeDevice('phone-1'), false);
      h.ui.setPreparing(false); const operation = h.ui.removeDevice('phone-1');
      assert.equal(h.window.managerBlocked, true); assert.equal(h.el('devices').querySelector('button').disabled, true);
      assert.equal(await h.ui.removeDevice('phone-2'), false); assert.equal(h.calls.length, 1);
      release(); await operation; assert.equal(h.window.managerBlocked, false);
      // Even a stale server response cannot resurrect the acknowledged removal.
      assert.equal(h.ui.getDevices().some(device => device.id === 'phone-1'), false);
    } finally { h.close(); }
  });
  await test('late pre-removal state cannot restore removed device or selected target', async () => {
    let release, sequence = 0;
    const h = harness({ stateHook: () => ++sequence === 1 ? new Promise(resolve => { release = () => resolve(response({ devices: fixture, jobs: [] })); }) : response({ devices: [fixture[1]], jobs: [] }) });
    try {
      const stale = h.ui.refreshState(); await h.ui.removeDevice('phone-1'); release(); await stale;
      assert.equal(h.ui.getDevices().length, 1); assert.equal(h.ui.getSelected().length, 0);
      assert.equal(h.managementStates.at(-1).some(device => device.id === 'phone-1'), false);
    } finally { h.close(); }
  });
  await test('state connection failure still refreshes manager heartbeat display', async () => {
    const h = harness({ stateHook: () => { throw new Error('offline'); } }); try {
      await h.ui.refreshState(); assert.equal(h.managementStates.length, 1);
      assert.equal(h.ui.getDevices().length, 2); assert.match(h.el('connection').textContent, /中断/);
    } finally { h.close(); }
  });
  console.log(`${count} device management tests passed`);
})().catch(error => { console.error(error); process.exitCode = 1; });
