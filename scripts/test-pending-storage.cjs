'use strict';

// npm install --prefix /tmp/sms-pending-test --no-audit --no-fund fake-indexeddb@6.2.5
// node scripts/test-pending-storage.cjs /tmp/sms-pending-test/node_modules/fake-indexeddb
// Uses the production functions directly, with separate VM contexts representing browser tabs.
const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const vm = require('node:vm');
const { indexedDB } = require(process.argv[2] || 'fake-indexeddb');

const appSource = fs.readFileSync(path.join(__dirname, '../desktop/static/app.js'), 'utf8');
const storageSource = appSource.slice(0, appSource.indexOf('\nfunction notify('));
const submitSource = appSource.slice(appSource.indexOf('async function submitPayload('), appSource.indexOf("\n$('generator').addEventListener"));
assert.ok(storageSource.includes('function pendingOperation('), 'Load the actual production persistence implementation');

async function tab(origin = 'http://127.0.0.1:8765', adminToken = 'admin-a') {
  const nodes = new Map();
  const context = vm.createContext({ indexedDB, location: { origin }, structuredClone, document: {
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, {}); return nodes.get(id); },
  } });
  vm.runInContext(storageSource, context);
  context.adminTokenForTest = adminToken;
  vm.runInContext('token = adminTokenForTest', context);
  await vm.runInContext('openPendingStorage()', context);
  vm.runInContext(`
    function updateSend() {}
    function notify() {}
    async function refreshState() {}
    let apiCallsForTest = 0;
    let rejectionStatusForTest = null;
    async function api() {
      apiCallsForTest++;
      if (rejectionStatusForTest !== null) {
        const failure = new Error('Simulated API error');
        if (rejectionStatusForTest !== 'network') failure.httpStatus = rejectionStatusForTest;
        throw failure;
      }
      return { json: async () => ({ jobs: [{ id: 'created-job' }] }) };
    }
    pendingStorageReady = true;
  ` + submitSource, context);
  return {
    operation(action, payload = null) {
      context.actionForTest = action;
      context.payloadForTest = payload;
      return vm.runInContext('pendingOperation(actionForTest, payloadForTest)', context);
    },
    submit(payload, rejectionStatus = null) {
      context.payloadForTest = payload;
      context.statusForTest = rejectionStatus;
      return vm.runInContext('rejectionStatusForTest = statusForTest; submitPayload(payloadForTest)', context);
    },
    apiCalls() { return vm.runInContext('apiCallsForTest', context); },
    close() { vm.runInContext('pendingDatabase.close()', context); },
  };
}

function batch(id, body = '【测试】固定快照') {
  return { requestId: id, deviceIds: ['phone-a'], messages: [{ sender: '13800138000', body, timestamp: 1789372800000 }] };
}

async function run() {
  const firstTab = await tab();
  const secondTab = await tab();
  const payloadA = batch('request-a');
  const payloadB = batch('request-b');
  const [firstClaim, secondClaim] = await Promise.all([
    firstTab.operation('claim', payloadA),
    secondTab.operation('claim', payloadB),
  ]);
  assert.equal(firstClaim.requestId, secondClaim.requestId, 'Concurrent claims must resolve to the same stored batch');
  const winner = firstClaim.requestId;
  assert.ok(['request-a', 'request-b'].includes(winner));
  console.log('PASS: atomic concurrent claim preserves exactly one batch');

  payloadA.messages[0].body = 'changed draft A';
  payloadB.messages[0].body = 'changed draft B';
  firstTab.close();
  secondTab.close();
  const reloadedTab = await tab();
  const restored = await reloadedTab.operation('read');
  assert.equal(restored.requestId, winner);
  assert.equal(restored.messages[0].body, '【测试】固定快照');
  console.log('PASS: page reload restores the original requestId and immutable snapshot');

  await reloadedTab.operation('clear', batch('wrong-request-id'));
  assert.equal((await reloadedTab.operation('read')).requestId, winner);
  await reloadedTab.operation('clear', batch(winner));
  assert.equal(await reloadedTab.operation('read'), null);
  console.log('PASS: clear requires a matching requestId');

  const anotherToken = await tab('http://127.0.0.1:8765', 'admin-b');
  const anotherOrigin = await tab('http://127.0.0.1:9876', 'admin-a');
  await Promise.all([
    reloadedTab.operation('claim', batch('scope-a')),
    anotherToken.operation('claim', batch('scope-b')),
    anotherOrigin.operation('claim', batch('scope-c')),
  ]);
  assert.equal((await reloadedTab.operation('read')).requestId, 'scope-a');
  assert.equal((await anotherToken.operation('read')).requestId, 'scope-b');
  assert.equal((await anotherOrigin.operation('read')).requestId, 'scope-c');
  console.log('PASS: admin token and server origin isolate pending records');

  await reloadedTab.operation('clear', batch('scope-a'));
  const largeBody = '中'.repeat(1000);
  const largeBatch = { requestId: 'large-batch', deviceIds: ['phone-a'], messages: Array.from({ length: 4000 }, () => ({ sender: 'sender', body: largeBody, timestamp: 0 })) };
  assert.ok(Buffer.byteLength(JSON.stringify(largeBatch)) > 10 * 1024 * 1024);
  await reloadedTab.operation('claim', largeBatch);
  assert.equal((await reloadedTab.operation('read')).messages.length, 4000);
  console.log('PASS: a batch larger than localStorage capacity round-trips through IndexedDB');

  for (const status of [400, 404, 413, 415]) {
    const rejectedTab = await tab('http://127.0.0.1:8765', `rejected-${status}`);
    await rejectedTab.submit(batch(`rejected-${status}`), status);
    assert.equal(rejectedTab.apiCalls(), 1);
    assert.equal(await rejectedTab.operation('read'), null, `Definitive rejection ${status} must release the pending batch`);
    await rejectedTab.submit(batch(`corrected-${status}`));
    assert.equal(rejectedTab.apiCalls(), 2, 'An edited new batch may be submitted after definitive rejection');
    rejectedTab.close();
  }
  console.log('PASS: definitive 400/404/413/415 responses clear pending and permit corrected new submissions');

  for (const status of [401, 409, 500, 'network']) {
    const uncertainTab = await tab('http://127.0.0.1:8765', `uncertain-${status}`);
    await uncertainTab.submit(batch(`uncertain-${status}`), status);
    assert.equal((await uncertainTab.operation('read')).requestId, `uncertain-${status}`);
    await uncertainTab.submit(batch('different-request'));
    assert.equal(uncertainTab.apiCalls(), 1, 'A competing new request must not reach the server');
    await uncertainTab.submit(batch(`uncertain-${status}`));
    assert.equal(await uncertainTab.operation('read'), null);
    uncertainTab.close();
  }
  console.log('PASS: 401/409/500/network failures preserve requestId and block competing new submissions');

  const brokenStorageTab = await tab('http://127.0.0.1:8765', 'broken-storage');
  brokenStorageTab.close();
  await brokenStorageTab.submit(batch('must-not-send'));
  assert.equal(brokenStorageTab.apiCalls(), 0);
  console.log('PASS: IndexedDB failure prevents a network submission');
  reloadedTab.close();
  anotherToken.close();
  anotherOrigin.close();
}

run().catch(error => { console.error(error); process.exitCode = 1; });
