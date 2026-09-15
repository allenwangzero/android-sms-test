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
const draftSource = appSource.slice(appSource.indexOf('function draftOperation('), appSource.indexOf('function localDate('));
const submitSource = appSource.slice(appSource.indexOf('function hasContent('), appSource.indexOf("\nasync function importSmsFile("));
assert.ok(storageSource.includes('function pendingOperation('), 'Load the actual production persistence implementation');

async function tab(origin = 'http://127.0.0.1:8765', adminToken = 'admin-a') {
  const nodes = new Map();
  const localValues = new Map();
  const context = vm.createContext({ indexedDB, location: { origin }, structuredClone, TextEncoder, setTimeout, clearTimeout, localStorage: {
    getItem(key) { return localValues.get(key) || null; },
    setItem(key, value) { localValues.set(key, value); },
    removeItem(key) { localValues.delete(key); },
  }, document: {
    getElementById(id) { if (!nodes.has(id)) nodes.set(id, {}); return nodes.get(id); },
  } });
  vm.runInContext(fs.readFileSync(path.join(__dirname, '../desktop/static/sms-xml.js'), 'utf8') + storageSource + draftSource, context);
  context.adminTokenForTest = adminToken;
  vm.runInContext('token = adminTokenForTest', context);
  await vm.runInContext('openPendingStorage()', context);
  vm.runInContext(`
    function updateSend() {}
    function notify() {}
    async function refreshState() {}
    let apiCallsForTest = 0;
    let rejectionStatusForTest = null;
    let requestsForTest = [];
    let receivedForTest = [];
    let failPathForTest = null;
    let uploadCountForTest = 0;
    async function api(path, options) {
      apiCallsForTest++;
      requestsForTest.push({ path, body: JSON.parse(options.body) });
      if (rejectionStatusForTest !== null && (!failPathForTest || path.endsWith(failPathForTest))) {
        const failure = new Error('Simulated API error');
        if (rejectionStatusForTest !== 'network') failure.httpStatus = rejectionStatusForTest;
        throw failure;
      }
      if (path === '/api/uploads') {
        const body = JSON.parse(options.body);
        uploadCountForTest = body.count;
        return { json: async () => ({ uploadId: body.requestId, count: body.count, batchSize: 500, batchCount: Math.ceil(body.count / 500), receivedBatches: receivedForTest, jobs: [] }) };
      }
      if (path.includes('/batches/')) receivedForTest.push(Number(path.split('/').pop()));
      return { json: async () => ({ jobs: [{ id: 'created-job', count: uploadCountForTest }] }) };
    }
    pendingStorageReady = true;
  ` + submitSource, context);
  return {
    validate(records) {
      context.recordsForTest = records;
      return vm.runInContext('validateMessages(recordsForTest)', context);
    },
    async restoreDraft(legacy = null) {
      if (legacy) localValues.set('sms-test-draft-v1', JSON.stringify(legacy));
      await vm.runInContext('loadDraft()', context);
      return vm.runInContext('messages', context);
    },
    draft(action, value = null) {
      context.draftActionForTest = action;
      context.draftValueForTest = value;
      return vm.runInContext('draftOperation(draftActionForTest, draftValueForTest)', context);
    },
    legacy() { return localValues.get('sms-test-draft-v1'); },
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
    cancel(status = null) { context.statusForTest = status; return vm.runInContext('rejectionStatusForTest = statusForTest; cancelPendingUpload()', context); },
    configure({ received = [], failPath = null } = {}) {
      context.receivedInputForTest = received;
      context.failPathInputForTest = failPath;
      vm.runInContext('receivedForTest = [...receivedInputForTest]; failPathForTest = failPathInputForTest', context);
    },
    requests() { return vm.runInContext('requestsForTest', context); },
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
    assert.equal(rejectedTab.apiCalls(), 4, 'An edited new batch may be submitted after definitive rejection');
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

  const many = batch('many-messages');
  many.messages = Array.from({ length: 12001 }, (_, index) => ({ sender: 'TEST', body: `短信 ${index}`, timestamp: 0 }));
  const chunkTab = await tab('http://127.0.0.1:8765', 'chunks');
  chunkTab.configure({ received: [0, 2, 3] });
  await chunkTab.submit(many);
  const requests = chunkTab.requests();
  const chunks = requests.filter(request => request.path.includes('/batches/'));
  assert.equal(chunks.length, 22);
  assert.equal(chunks[0].path, '/api/uploads/many-messages/batches/1');
  assert.equal(chunks[0].body.messages.length, 500);
  assert.equal(chunks.at(-1).body.messages.length, 1);
  assert.equal(requests.filter(request => request.path.endsWith('/commit')).length, 1);
  assert.equal(await chunkTab.operation('read'), null);
  console.log('PASS: more than 10000 records upload in 500-record chunks, skip received chunks, and commit once');

  for (const status of [400, 404, 413, 415, 409, 500, 'network']) {
    const stoppedTab = await tab('http://127.0.0.1:8765', `stopped-${status}`);
    stoppedTab.configure({ failPath: '/batches/1' });
    await stoppedTab.submit(many, status);
    assert.equal(stoppedTab.requests().length, 3, 'Stop after the first failed chunk');
    assert.equal(stoppedTab.requests().filter(request => request.path.endsWith('/commit')).length, 0);
    assert.equal((await stoppedTab.operation('read')).messages.length, 12001);
    assert.equal((await stoppedTab.operation('read')).uploadAttempted, true);
    stoppedTab.configure();
    await stoppedTab.submit(many, 404);
    assert.equal((await stoppedTab.operation('read')).requestId, many.requestId, 'An existing upload must survive later start errors');
    stoppedTab.configure({ received: [0] });
    await stoppedTab.submit(many);
    assert.equal(stoppedTab.requests().filter(request => request.path.endsWith('/batches/0')).length, 1, 'Retry must skip chunk already uploaded');
    assert.equal(stoppedTab.requests().filter(request => request.path.endsWith('/commit')).length, 1);
    assert.equal(await stoppedTab.operation('read'), null);
    stoppedTab.close();
  }
  console.log('PASS: any mid-upload failure stops without commit, retains snapshot, and retries only missing chunks');
  chunkTab.close();

  await chunkTab.validate(many.messages);
  await assert.rejects(chunkTab.validate([{ sender: '\u0085', body: 'test', timestamp: 0 }]), /发送人/);
  await assert.rejects(chunkTab.validate([{ sender: 'TEST', body: '\u001c', timestamp: 0 }]), /内容/);
  const oversized = Array.from({ length: 23000 }, () => ({ sender: 'TEST', body: '中'.repeat(4000), timestamp: 0 }));
  await assert.rejects(chunkTab.validate(oversized), /256 MiB/);
  console.log('PASS: validation accepts more than 10000, rejects server whitespace and UTF8 tasks above 256 MiB');

  const cancelledTab = await tab('http://127.0.0.1:8765', 'cancel-upload');
  cancelledTab.configure({ failPath: '/batches/0' });
  await cancelledTab.submit(batch('cancel-me'), 400);
  assert.ok(await cancelledTab.operation('read'));
  cancelledTab.configure({ failPath: '/cancel' });
  await cancelledTab.cancel(400);
  assert.ok(await cancelledTab.operation('read'), 'A failed cancellation keeps the pending snapshot');
  await cancelledTab.cancel(409);
  assert.ok(await cancelledTab.operation('read'), 'Already committed upload cannot be cancelled');
  cancelledTab.configure({ failPath: '/unused' });
  await cancelledTab.cancel();
  assert.equal(await cancelledTab.operation('read'), null);
  cancelledTab.close();
  console.log('PASS: cancelling a rejected upload clears pending only after server acknowledgement; 409 preserves it');

  const draftTab = await tab('http://127.0.0.1:8766');
  const legacyDraft = batch('legacy').messages;
  assert.equal((await draftTab.restoreDraft(legacyDraft))[0].body, legacyDraft[0].body);
  assert.equal(draftTab.legacy(), undefined, 'Remove legacy draft only after migration succeeds');
  const hugeDraft = Array.from({ length: 100000 }, () => ({ sender: 'TEST', body: '测试草稿', timestamp: 0 }));
  await draftTab.draft('write', hugeDraft);
  assert.equal((await draftTab.restoreDraft()).length, 100000);
  draftTab.close();
  const draftReload = await tab('http://127.0.0.1:8766');
  assert.equal((await draftReload.restoreDraft()).length, 100000);
  draftReload.close();
  console.log('PASS: old localStorage draft migrates safely and 100000-record IndexedDB draft survives reload');

  const metadataTab = await tab('http://127.0.0.1:8767', 'metadata');
  const rich = batch('metadata-snapshot');
  Object.assign(rich.messages[0], { type: 1, protocol: null, subject: 'A&B', service_center: '+63917', read: 0, seen: 1, date_sent: 1789372799000, status: 64, locked: 1, toa: null, sc_toa: null });
  await metadataTab.draft('write', rich.messages);
  const richRestored = await metadataTab.restoreDraft();
  assert.deepEqual(JSON.parse(JSON.stringify(richRestored)), rich.messages);
  await metadataTab.submit(rich, 'network');
  rich.messages[0].read = 1;
  const pendingRich = await metadataTab.operation('read');
  assert.equal(pendingRich.messages[0].read, 0);
  await metadataTab.submit(pendingRich);
  const sentRich = metadataTab.requests().find(request => request.path.endsWith('/batches/0')).body.messages[0];
  assert.equal(sentRich.read, 0);
  assert.equal(sentRich.locked, 1);
  assert.equal(sentRich.status, 64);
  assert.equal(sentRich.protocol, null);
  assert.equal(sentRich.subject, 'A&B');
  assert.equal(sentRich.service_center, '+63917');
  for (const [field, value] of [['read', true], ['read', null], ['status', '64'], ['locked', 2], ['protocol', 256], ['subject', 5], ['toa', 'null'], ['sc_toa', 0]]) {
    await assert.rejects(metadataTab.validate([{ sender: 'TEST', body: 'test', timestamp: 0, [field]: value }]), new RegExp(field));
  }
  await metadataTab.draft('write', [{sender: 'A', body: 'B', timestamp: 0, read: '0'}]);
  assert.equal((await metadataTab.restoreDraft())[0].read, 0, 'Invalid metadata draft must not replace the valid in-memory draft');
  metadataTab.close();
  console.log('PASS: metadata survives draft recovery, immutable snapshot, retry and transfer; invalid values are rejected');

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
