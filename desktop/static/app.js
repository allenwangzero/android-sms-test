'use strict';

const $ = (id) => document.getElementById(id);
const DRAFT_KEY = 'sms-test-draft-v1';
const TOKEN_KEY = 'sms-test-admin-token';
const PAGE_SIZE = 50;
const MAX_COUNT = 100000;
const BATCH_SIZE = 500;
const MAX_TASK_BYTES = 256 * 1024 * 1024;
let token = '';
let messages = [];
let devices = [];
let jobs = [];
let selected = new Set();
let page = 0;
let connected = false;
let sending = false;
let preparing = false;
let generating = false;
let importing = false;
let draftRevision = 0;
let draftLoaded = false;
let retryPayload = null;
let pairingExpiry = 0;
let pairingUrl = '';
let qrObjectUrl = null;
let draftTimer;
let pendingDatabase = null;
let pendingStorageReady = false;

function pendingScope() { return `${location.origin}|${token}`; }

async function openPendingStorage() {
  pendingDatabase = await new Promise((resolve, reject) => {
    const request = indexedDB.open('sms-test-pending-v1', 2);
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains('pending')) request.result.createObjectStore('pending');
      if (!request.result.objectStoreNames.contains('drafts')) request.result.createObjectStore('drafts');
    };
    request.onsuccess = () => resolve(request.result);
    request.onerror = () => reject(request.error);
    request.onblocked = () => reject(new Error('其他页面阻止了本地存储升级，请关闭其他工作台页面后刷新'));
  });
  pendingDatabase.onversionchange = () => {
    pendingDatabase.close();
    pendingStorageReady = false;
    notify('本地发送记录存储已发生变化，请刷新页面后继续。');
    updateSend();
  };
}

// Read and claim in one transaction so two tabs cannot replace each other's pending batch.
function pendingOperation(action, payload = null) {
  return new Promise((resolve, reject) => {
    if (!pendingDatabase) { reject(new Error('本地发送记录存储不可用')); return; }
    const transaction = pendingDatabase.transaction('pending', action === 'read' ? 'readonly' : 'readwrite');
    const store = transaction.objectStore('pending');
    const request = store.get(pendingScope());
    let result = null;
    request.onsuccess = () => {
      result = request.result || null;
      if (action === 'claim' && !result) {
        store.put(payload, pendingScope());
        result = payload;
      } else if (action === 'markAttempted' && result && result.requestId === payload.requestId) {
        result.uploadAttempted = true;
        store.put(result, pendingScope());
      } else if (action === 'clear' && result && result.requestId === payload.requestId) {
        store.delete(pendingScope());
        result = null;
      }
    };
    transaction.oncomplete = () => resolve(result);
    transaction.onabort = () => reject(transaction.error || new Error('本地发送记录保存失败'));
    transaction.onerror = () => reject(transaction.error || new Error('本地发送记录保存失败'));
  });
}

function showPending(payload, reason) {
  retryPayload = payload;
  $('retry-box').hidden = false;
  $('retry-description').textContent = `${reason} 已保留原批次的 ${payload.messages.length} 条短信及 ${payload.deviceIds.length} 台目标设备。点击重试会沿用原批次标识，编辑当前列表不会改变原批次。`;
}

function notify(text) {
  $('notice').textContent = text;
  $('notice').hidden = !text;
}

function element(tag, className, text) {
  const node = document.createElement(tag);
  if (className) node.className = className;
  if (text !== undefined) node.textContent = text;
  return node;
}

function randomNumber(min, max) {
  const range = max - min + 1;
  const values = new Uint32Array(1);
  const limit = Math.floor(4294967296 / range) * range;
  do { crypto.getRandomValues(values); } while (values[0] >= limit);
  return min + values[0] % range;
}

function uuid() {
  const bytes = crypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 15) | 64;
  bytes[8] = (bytes[8] & 63) | 128;
  const hex = Array.from(bytes, value => value.toString(16).padStart(2, '0')).join('');
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`;
}

async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: { Authorization: `Bearer ${token}`, ...(options.body ? { 'Content-Type': 'application/json' } : {}) },
    signal: AbortSignal.timeout(15000),
    cache: 'no-store',
  });
  if (!response.ok) {
    let detail = `请求失败（HTTP ${response.status}）`;
    try { const error = await response.json(); if (error.error) detail = error.error; } catch { /* Response may not be JSON. */ }
    const failure = new Error(detail);
    failure.httpStatus = response.status;
    throw failure;
  }
  return response;
}

function draftOperation(action, value = null) {
  return new Promise((resolve, reject) => {
    if (!pendingDatabase) { reject(new Error('本地草稿存储不可用')); return; }
    const transaction = pendingDatabase.transaction('drafts', action === 'read' ? 'readonly' : 'readwrite');
    const store = transaction.objectStore('drafts');
    const request = action === 'read' ? store.get(location.origin) : store.put(value, location.origin);
    transaction.oncomplete = () => resolve(request.result);
    transaction.onabort = () => reject(transaction.error || new Error('本地草稿保存失败'));
    transaction.onerror = () => reject(transaction.error || new Error('本地草稿保存失败'));
  });
}

async function loadDraft() {
  try {
    let stored = await draftOperation('read');
    const migrate = stored === undefined;
    if (migrate) stored = JSON.parse(localStorage.getItem(DRAFT_KEY) || '[]');
    if (!Array.isArray(stored) || stored.length > MAX_COUNT || stored.some(item =>
      !item || typeof item.sender !== 'string' || typeof item.body !== 'string' ||
      [...item.sender].length > 100 || [...item.body].length > 4000 ||
      !(item.timestamp === null || (Number.isInteger(item.timestamp) && item.timestamp >= 0 && item.timestamp <= 4102444800000)))) {
      throw new Error('草稿格式不正确');
    }
    messages = stored;
    if (migrate) {
      await draftOperation('write', stored);
      localStorage.removeItem(DRAFT_KEY);
    }
    $('draft-status').textContent = '草稿已从当前浏览器恢复';
  } catch (error) {
    $('draft-status').textContent = '草稿读取或迁移失败，请勿关闭原页面';
    notify(`无法完整恢复本地草稿：${error.message}`);
  }
}

function saveDraft() {
  const revision = ++draftRevision;
  $('draft-status').textContent = '草稿保存中…';
  clearTimeout(draftTimer);
  draftTimer = setTimeout(async () => {
    try {
      await draftOperation('write', messages);
      if (revision === draftRevision) $('draft-status').textContent = '草稿已保存到当前浏览器';
    } catch {
      if (revision === draftRevision) $('draft-status').textContent = '草稿未保存（浏览器空间不足或存储不可用），关闭页面会丢失修改';
    }
  }, 800);
}

function yieldToBrowser() { return new Promise(resolve => setTimeout(resolve, 0)); }

function localDate(timestamp) {
  if (!Number.isFinite(timestamp)) return '';
  const date = new Date(timestamp);
  const pad = value => String(value).padStart(2, '0');
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}T${pad(date.getHours())}:${pad(date.getMinutes())}:${pad(date.getSeconds())}`;
}

function updateCounts() {
  $('message-count').textContent = `${messages.length.toLocaleString()} 条短信`;
  const totalPages = Math.max(1, Math.ceil(messages.length / PAGE_SIZE));
  $('page-info').textContent = `第 ${page + 1} / ${totalPages} 页 · 每页 ${PAGE_SIZE} 条`;
  $('previous').disabled = page === 0;
  $('next').disabled = page + 1 >= totalPages;
  $('import-xml').disabled = !draftLoaded || generating || importing;
  $('import-xml').textContent = importing ? '正在解析 XML…' : '上传 XML 并追加';
  $('add').disabled = !draftLoaded || generating || importing || messages.length >= MAX_COUNT;
  $('clear').disabled = !draftLoaded || generating || importing || messages.length === 0;
  updateSend();
}

function renderMessages() {
  page = Math.min(page, Math.max(0, Math.ceil(messages.length / PAGE_SIZE) - 1));
  const fragment = document.createDocumentFragment();
  messages.slice(page * PAGE_SIZE, (page + 1) * PAGE_SIZE).forEach((message, offset) => {
    const index = page * PAGE_SIZE + offset;
    const row = element('tr');
    row.append(element('td', 'row-index', String(index + 1)));
    for (const field of ['sender', 'body', 'timestamp']) {
      const cell = element('td');
      const input = element(field === 'body' ? 'textarea' : 'input');
      input.disabled = importing;
      input.setAttribute('aria-label', `第 ${index + 1} 条${{ sender: '发送人', body: '短信内容', timestamp: '接收时间' }[field]}`);
      if (field === 'timestamp') {
        input.type = 'datetime-local';
        input.step = '1';
        input.value = localDate(message.timestamp);
      } else {
        input.maxLength = field === 'body' ? 4000 : 100;
        input.value = message[field];
      }
      input.addEventListener('input', () => {
        if (importing) return;
        message[field] = field === 'timestamp' ? (input.value ? new Date(input.value).getTime() : null) : input.value;
        saveDraft();
      });
      cell.append(input);
      row.append(cell);
    }
    const removeCell = element('td');
    const remove = element('button', 'remove-row', '×');
    remove.disabled = importing;
    remove.title = `删除第 ${index + 1} 条`;
    remove.setAttribute('aria-label', remove.title);
    remove.addEventListener('click', () => { if (importing) return; messages.splice(index, 1); saveDraft(); renderMessages(); });
    removeCell.append(remove);
    row.append(removeCell);
    fragment.append(row);
  });
  $('messages').replaceChildren(fragment);
  $('messages-empty').hidden = messages.length > 0;
  updateCounts();
}

function online(device) { return Date.now() - device.lastSeen <= 10000; }

function updateSend() {
  const available = devices.filter(device => selected.has(device.id) && online(device)).length;
  $('selection-summary').textContent = selected.size ? `已选 ${selected.size} 台手机 · ${available} 台在线` : '请选择目标手机';
  $('send').disabled = !draftLoaded || sending || preparing || generating || importing || !connected || !pendingStorageReady || !messages.length || !selected.size || selected.size !== available || Boolean(retryPayload);
  $('send').textContent = sending ? '正在分批传输…' : preparing ? '正在校验…' : '发送到手机';
  $('generate').disabled = !draftLoaded || generating || importing;
  $('generate').textContent = generating ? '正在生成…' : '随机生成并追加';
  $('retry').disabled = sending || !connected || !pendingStorageReady;
  $('cancel-upload').disabled = sending || !connected || !pendingStorageReady;
}

function renderDevices() {
  $('device-count').textContent = `${devices.length} 台`;
  const fragment = document.createDocumentFragment();
  devices.forEach(device => {
    const label = element('label', 'device');
    const checkbox = element('input');
    checkbox.type = 'checkbox';
    checkbox.checked = selected.has(device.id);
    checkbox.disabled = !online(device) && !checkbox.checked;
    checkbox.addEventListener('change', () => {
      if (checkbox.checked) selected.add(device.id); else selected.delete(device.id);
      updateSend();
    });
    const text = element('div', 'device-text');
    text.append(element('div', 'device-name', device.name));
    text.append(element('div', 'device-detail', `设备 ${device.id.slice(0, 8)}`));
    label.append(checkbox, text, element('span', online(device) ? 'badge online' : 'badge', online(device) ? '在线' : '离线'));
    fragment.append(label);
  });
  if (!devices.length) fragment.append(element('p', 'empty', '还没有连接的手机，请先扫码配对。'));
  $('devices').replaceChildren(fragment);
  updateSend();
}

function renderJobs() {
  const labels = { queued: '等待手机接收', received: '等待手机确认', writing: '正在写入', completed: '全部写入成功', failed: '写入失败', interrupted: '写入已中断' };
  const fragment = document.createDocumentFragment();
  [...jobs].sort((a, b) => b.createdAt - a.createdAt).forEach(job => {
    const device = devices.find(item => item.id === job.deviceId);
    const item = element('article', 'job');
    const heading = element('div', 'job-heading');
    heading.append(element('span', 'job-title', device ? device.name : job.deviceId));
    heading.append(element('span', `badge ${job.status === 'completed' ? 'online' : ['failed', 'interrupted'].includes(job.status) ? 'error' : ''}`, labels[job.status] || job.status));
    const metadata = element('div', 'job-meta');
    metadata.append(element('span', '', `共 ${job.count} 条 · ${job.batchCount || 1} 批 · 已确认写入 ${job.written} 条 · 未确认写入 ${job.count - job.written} 条`));
    metadata.append(element('span', '', new Date(job.createdAt).toLocaleString('zh-CN')));
    const progress = element('progress');
    progress.max = job.count || 1;
    progress.value = job.written;
    progress.setAttribute('aria-label', '写入进度');
    item.append(heading, metadata, progress);
    if (job.error) item.append(element('p', 'job-error', job.error));
    if (job.status === 'interrupted') item.append(element('p', 'job-error', '中断时可能已有短信写入但未反馈。请先在手机核对，避免重新发送产生重复。'));
    item.append(element('div', 'job-id', `任务 ${job.id}`));
    fragment.append(item);
  });
  if (!jobs.length) fragment.append(element('p', 'empty', '发送后，任务进度会显示在这里。'));
  $('jobs').replaceChildren(fragment);
}

async function refreshState() {
  try {
    const state = await (await api('/api/state')).json();
    devices = state.devices;
    jobs = state.jobs;
    connected = true;
    $('connection').textContent = '服务已连接';
    $('connection').className = 'badge online';
    renderDevices();
    renderJobs();
  } catch (error) {
    connected = false;
    $('connection').textContent = error.httpStatus === 401 ? '管理链接无效' : '服务连接中断';
    $('connection').className = 'badge error';
    if (error.httpStatus === 401) notify('请使用启动服务时输出的完整管理链接打开页面（包含 #token=…）。');
    renderDevices();
  }
}

function updateExpiry() {
  if (!pairingExpiry) return;
  const seconds = Math.max(0, Math.ceil((pairingExpiry - Date.now()) / 1000));
  $('expiry').textContent = seconds ? `配对码剩余 ${Math.floor(seconds / 60)} 分 ${seconds % 60} 秒` : '配对码已过期，请刷新配对码';
  $('qr').style.opacity = seconds ? '1' : '.25';
  $('copy-pairing').disabled = !seconds || !pairingUrl;
}

async function refreshPairing(rotate = false) {
  $('rotate').disabled = true;
  $('copy-pairing').disabled = true;
  pairingUrl = '';
  $('pairing-link').hidden = true;
  $('copy-result').textContent = '';
  $('qr').hidden = true;
  $('qr-placeholder').hidden = false;
  $('qr-placeholder').textContent = '正在获取配对码…';
  try {
    const pairing = await (await api(rotate ? '/api/pairing/rotate' : '/api/pairing', rotate ? { method: 'POST', body: '{}' } : {})).json();
    pairingExpiry = pairing.expiresAt;
    pairingUrl = pairing.url;
    $('server-url').textContent = pairing.serverUrl;
    updateExpiry();
    const qr = await (await api('/api/pairing/qr')).blob();
    if (qrObjectUrl) URL.revokeObjectURL(qrObjectUrl);
    qrObjectUrl = URL.createObjectURL(qr);
    $('qr').src = qrObjectUrl;
    $('qr').hidden = false;
    $('qr-placeholder').hidden = true;
  } catch (error) {
    $('qr-placeholder').textContent = `配对码获取失败：${error.message}`;
  } finally { $('rotate').disabled = false; }
}

function hasContent(value) { return /[^\s\u0085\u001c-\u001f]/u.test(value); }

async function validateMessages(records) {
  if (!records.length || records.length > MAX_COUNT) throw new Error('短信数量需为 1–100000 条。');
  let bytes = 2;
  const encoder = new TextEncoder();
  for (let index = 0; index < records.length; index++) {
    const message = records[index];
    if (!hasContent(message.sender) || [...message.sender].length > 100) throw new Error(`第 ${index + 1} 条发送人需为 1–100 个字符。`);
    if (!hasContent(message.body) || [...message.body].length > 4000) throw new Error(`第 ${index + 1} 条内容需为 1–4000 个字符。`);
    if (!Number.isInteger(message.timestamp) || message.timestamp < 0 || message.timestamp > 4102444800000) throw new Error(`第 ${index + 1} 条接收时间无效，请使用 1970–2100 年范围的时间。`);
    bytes += encoder.encode(JSON.stringify(message)).byteLength + (index ? 1 : 0);
    if (bytes > MAX_TASK_BYTES) throw new Error('完整列表超过 256 MiB，请减少短信数量或缩短内容。');
    if (index % BATCH_SIZE === BATCH_SIZE - 1) await yieldToBrowser();
  }
}

function showUploadProgress(completed, count, committing = false) {
  $('upload-progress').hidden = false;
  $('upload-meter').max = count;
  $('upload-meter').value = completed;
  $('upload-status').textContent = committing ? `已传输全部 ${count} 批，正在创建手机任务…` : `电脑传输进度：${completed} / ${count} 批（每批最多 ${BATCH_SIZE} 条）`;
}

async function uploadPayload(payload, onStarted) {
  const batchCount = Math.ceil(payload.messages.length / BATCH_SIZE);
  showUploadProgress(0, batchCount);
  const upload = await (await api('/api/uploads', { method: 'POST', body: JSON.stringify({
    requestId: payload.requestId, deviceIds: payload.deviceIds, count: payload.messages.length,
  }) })).json();
  onStarted();
  if (upload.uploadId !== payload.requestId || upload.batchSize !== BATCH_SIZE || upload.batchCount !== batchCount || upload.count !== payload.messages.length) {
    throw new Error('服务器返回的上传参数不匹配，请更新电脑服务后重试');
  }
  if (upload.jobs.length) { showUploadProgress(batchCount, batchCount); return { jobs: upload.jobs }; }
  if (!Array.isArray(upload.receivedBatches) || upload.receivedBatches.some(index => !Number.isInteger(index) || index < 0 || index >= batchCount)) {
    throw new Error('服务器返回的分批进度不正确');
  }
  const received = new Set(upload.receivedBatches);
  showUploadProgress(received.size, batchCount);
  for (let index = 0; index < batchCount; index++) {
    if (received.has(index)) continue;
    await api(`/api/uploads/${payload.requestId}/batches/${index}`, { method: 'POST', body: JSON.stringify({
      messages: payload.messages.slice(index * BATCH_SIZE, (index + 1) * BATCH_SIZE),
    }) });
    received.add(index);
    showUploadProgress(received.size, batchCount);
  }
  showUploadProgress(batchCount, batchCount, true);
  return (await api(`/api/uploads/${payload.requestId}/commit`, { method: 'POST', body: '{}' })).json();
}

async function submitPayload(payload) {
  if (!pendingStorageReady || sending) return;
  sending = true;
  notify('');
  updateSend();
  let persisted = false;
  let uploadStarted = false;
  let previousAttempt = false;
  try {
    const claimed = await pendingOperation('claim', payload);
    if (claimed.requestId !== payload.requestId) {
      showPending(claimed, '检测到其他页面或上次操作有未确认的发送，请先重试原批次。');
      return;
    }
    // Always use the committed snapshot, including on retries after a reload.
    payload = claimed;
    persisted = true;
    previousAttempt = Boolean(payload.uploadAttempted);
    await pendingOperation('markAttempted', payload);
    retryPayload = payload;
    const result = await uploadPayload(payload, () => { uploadStarted = true; });
    $('upload-status').textContent = `传输完成：${payload.messages.length} 条，${Math.ceil(payload.messages.length / BATCH_SIZE)} 批。`;
    const remaining = await pendingOperation('clear', payload);
    if (remaining) showPending(remaining, '另一页面已创建待确认批次。');
    else { retryPayload = null; $('retry-box').hidden = true; }
    notify(`已发送到 ${result.jobs.length} 台手机。请在手机端确认一次，依次导入全部短信；失败会停止。`);
    await refreshState();
  } catch (error) {
    $('upload-status').textContent = `传输已停止：${error.message}。`;
    if (persisted && !previousAttempt && !uploadStarted && [400, 404, 413, 415].includes(error.httpStatus)) {
      try {
        const remaining = await pendingOperation('clear', payload);
        if (remaining) showPending(remaining, '另一页面已有待确认批次。');
        else { retryPayload = null; $('retry-box').hidden = true; }
        notify(`服务器拒绝了本批次：${error.message}。请修改短信列表后重新发送。`);
      } catch (storageError) {
        showPending(payload, `请求被拒绝，但无法清除本地批次记录：${storageError.message}。请重试原批次。`);
      }
    } else if (persisted) showPending(payload, `发送或本地确认未完成：${error.message}。请重试原批次。`);
    else notify(`未发送：无法保存或读取本地批次记录（${error.message}）。请检查浏览器存储权限和剩余空间后重试。`);
  } finally { sending = false; updateSend(); }
}

async function cancelPendingUpload() {
  if (!retryPayload || sending || !pendingStorageReady) return;
  const payload = retryPayload;
  sending = true; updateSend();
  try {
    await api(`/api/uploads/${payload.requestId}/cancel`, { method: 'POST', body: '{}' });
    const remaining = await pendingOperation('clear', payload);
    if (remaining) showPending(remaining, '另一页面已有待确认任务。');
    else { retryPayload = null; $('retry-box').hidden = true; }
    $('upload-status').textContent = '已放弃未提交的上传。';
    notify('已放弃未提交的上传，当前短信草稿保留。可以修改后重新发送。');
  } catch (error) {
    showPending(payload, `未能放弃上传：${error.message}。如果任务已提交，请重试发送以查询结果。`);
  } finally { sending = false; updateSend(); }
}

async function importXmlFile(file) {
  if (!file || !draftLoaded || generating || importing) return;
  importing = true;
  renderMessages();
  try {
    if (file.size > 64 * 1024 * 1024) throw new Error('XML 文件超过 64 MiB，请拆分后导入。');
    const additions = parseSmsXml(await file.text());
    if (messages.length + additions.length > MAX_COUNT) throw new Error(`追加后超过 ${MAX_COUNT} 条，当前还可追加 ${MAX_COUNT - messages.length} 条。`);
    const combined = messages.concat(additions);
    const encoder = new TextEncoder();
    let bytes = 2;
    for (let index = 0; index < combined.length; index++) {
      bytes += encoder.encode(JSON.stringify(combined[index])).byteLength + (index ? 1 : 0);
      if (bytes > MAX_TASK_BYTES) throw new Error('追加后的列表超过 256 MiB，请减少短信数量。');
      if (index % BATCH_SIZE === BATCH_SIZE - 1) await yieldToBrowser();
    }
    clearTimeout(draftTimer);
    await draftOperation('write', combined);
    draftRevision++;
    page = Math.floor(messages.length / PAGE_SIZE);
    messages = combined;
    $('draft-status').textContent = '草稿已保存到当前浏览器';
    notify(`已从 ${file.name} 追加 ${additions.length} 条短信，合计 ${messages.length} 条。请检查列表后发送到手机。`);
  } catch (error) {
    saveDraft();
    notify(`XML 导入失败，原列表未改动：${error.message}`);
  } finally {
    importing = false;
    $('xml-file').value = '';
    renderMessages();
  }
}

$('import-xml').addEventListener('click', () => { if (draftLoaded && !generating && !importing) $('xml-file').click(); });
$('xml-file').addEventListener('change', () => importXmlFile($('xml-file').files[0]));

$('generator').addEventListener('submit', async event => {
  event.preventDefault();
  if (!draftLoaded || generating || importing) return;
  const quantity = Number($('quantity').value);
  if (!Number.isInteger(quantity) || quantity < 1 || quantity > MAX_COUNT) return notify('生成数量需为 1–100000 的整数。');
  if (messages.length + quantity > MAX_COUNT) return notify(`列表最多 ${MAX_COUNT} 条，当前还可追加 ${MAX_COUNT - messages.length} 条。`);
  const sender = $('sender-template').value;
  const template = $('body-template').value;
  if (sender && !hasContent(sender)) return notify('统一发送人不能只有空白字符。');
  if (template && !hasContent(template)) return notify('短信模板不能只有空白字符。');
  generating = true; updateCounts();
  const additions = [];
  const encoder = new TextEncoder();
  let draftBytes = 2;
  try {
    for (let index = 0; index < messages.length; index++) {
      draftBytes += encoder.encode(JSON.stringify(messages[index])).byteLength + (index ? 1 : 0);
      if (index % BATCH_SIZE === BATCH_SIZE - 1) await yieldToBrowser();
    }
    for (let index = 1; index <= quantity; index++) {
      const code = String(randomNumber(100000, 999999));
      const body = template ? template.replaceAll('{code}', code).replaceAll('{index}', String(index)) : `【测试】您的验证码为 ${code}，本短信仅用于测试。序号 ${index}。`;
      if ([...body].length > 4000) throw new Error(`第 ${index} 条模板展开后超过 4000 字符，请缩短内容。`);
      const message = { sender: sender || `13${randomNumber(0, 9)}${String(randomNumber(0, 99999999)).padStart(8, '0')}`, body, timestamp: Date.now() - randomNumber(0, 7 * 24 * 60 * 60) * 1000 };
      draftBytes += encoder.encode(JSON.stringify(message)).byteLength + (messages.length + additions.length ? 1 : 0);
      if (draftBytes > MAX_TASK_BYTES) throw new Error('生成后的列表将超过 256 MiB，未追加本次内容；请减少数量或缩短模板。');
      additions.push(message);
      if (index % BATCH_SIZE === 0) {
        $('generate').textContent = `正在生成 ${index} / ${quantity}…`;
        await yieldToBrowser();
      }
    }
    page = Math.floor(messages.length / PAGE_SIZE);
    messages = messages.concat(additions);
    notify(''); saveDraft(); renderMessages();
  } catch (error) { notify(error.message); }
  finally { generating = false; updateCounts(); }
});
$('add').addEventListener('click', () => {
  if (!draftLoaded || generating || importing || messages.length >= MAX_COUNT) return;
  messages.push({ sender: '', body: '', timestamp: Date.now() });
  page = Math.floor((messages.length - 1) / PAGE_SIZE);
  saveDraft(); renderMessages();
  $('messages').lastElementChild.querySelector('input').focus();
});
$('clear').addEventListener('click', () => {
  if (!draftLoaded || generating || importing) return;
  if (window.confirm(`清空当前 ${messages.length} 条短信草稿？已发送任务不受影响。`)) { messages = []; page = 0; saveDraft(); renderMessages(); }
});
$('previous').addEventListener('click', () => { if (page > 0) { page--; renderMessages(); } });
$('next').addEventListener('click', () => { if ((page + 1) * PAGE_SIZE < messages.length) { page++; renderMessages(); } });
$('rotate').addEventListener('click', () => refreshPairing(true));
$('copy-pairing').addEventListener('click', async () => {
  if (!pairingUrl || Date.now() >= pairingExpiry) return;
  try {
    await navigator.clipboard.writeText(pairingUrl);
    $('copy-result').textContent = '配对链接已复制，可在手机工具内粘贴。';
  } catch {
    $('pairing-link').value = pairingUrl;
    $('pairing-link').hidden = false;
    $('pairing-link').focus();
    $('pairing-link').select();
    $('copy-result').textContent = '自动复制不可用，请复制上方已选中的配对链接。';
  }
});
$('cancel-upload').addEventListener('click', () => {
  if (retryPayload && !sending && window.confirm('放弃这次尚未提交的上传？当前短信草稿会保留。')) cancelPendingUpload();
});
$('retry').addEventListener('click', () => { if (retryPayload && !sending) submitPayload(retryPayload); });
$('send').addEventListener('click', async () => {
  if (sending || preparing || generating || importing || retryPayload) return;
  preparing = true; updateSend();
  try {
    if (!selected.size) throw new Error('请先选择目标手机。');
    if ([...selected].some(id => !devices.some(device => device.id === id && online(device)))) throw new Error('所选手机已离线，请打开手机工具或取消选择离线设备。');
    const payload = { requestId: uuid(), deviceIds: [...selected], messages: messages.map(message => ({ ...message })) };
    await validateMessages(payload.messages);
    await submitPayload(payload);
  } catch (error) { notify(error.message); }
  finally { preparing = false; updateSend(); }
});

async function initialize() {
  const params = new URLSearchParams(location.hash.slice(1));
  const incomingToken = params.get('token');
  try {
    token = incomingToken || sessionStorage.getItem(TOKEN_KEY) || '';
    if (incomingToken) sessionStorage.setItem(TOKEN_KEY, incomingToken);
  } catch { token = incomingToken || ''; }
  if (incomingToken) history.replaceState(null, '', location.pathname + location.search);
  renderMessages();
  if (!token) notify('请使用启动服务时输出的完整管理链接打开页面（包含 #token=…）。');
  try {
    await openPendingStorage();
    await loadDraft();
    draftLoaded = true;
    renderMessages();
    const pending = await pendingOperation('read');
    if (pending) showPending(pending, '已恢复上次未确认的发送，请先重试原批次。');
    pendingStorageReady = true;
  } catch (error) {
    notify(`本地批次记录不可用，已禁用发送：${error.message}。请检查浏览器存储权限后刷新。`);
  }
  updateSend();
  await Promise.all([refreshState(), refreshPairing()]);
  setInterval(updateExpiry, 1000);
  async function poll() { await refreshState(); setTimeout(poll, 2000); }
  setTimeout(poll, 2000);
}

initialize();
