/* Phone inventory and deletion are separate from the import draft. */
(function () {
  'use strict';
  const terminal = new Set(['completed', 'failed', 'interrupted', 'cancelled']);
  const labels = { queued: '等待手机处理', ready: '等待手机确认删除', running: '手机正在处理', completed: '已完成', failed: '失败，已停止', interrupted: '已中断，停止处理', cancelled: '手机已取消' };
  window.initSmsManager = function ({ api, uuid, scope }) {
    const root = document.getElementById('sms-manager');
    root.innerHTML = `<div class="section-title"><h2>04 / 手机短信管理</h2><span class="muted">读取、筛选与删除</span></div>
      <p>选择一台手机，点击读取。手机工具需保持前台并授予读取权限；完整列表和清空全部需先临时设为默认短信应用，否则系统可能仅返回收件和已发送短信。删除前会在手机再次确认。</p>
      <label>目标手机<select id="sm-device"><option value="">请选择手机</option></select></label>
      <div class="sm-filters">
        <label>发送人（精确匹配）<input id="sm-sender" maxlength="100"></label><label>内容关键词（字面包含）<input id="sm-keyword" maxlength="4000"></label>
        <label>起始时间（本地时间，包含）<input id="sm-from" type="datetime-local" step="1"></label><label>截止时间（本地时间，包含）<input id="sm-to" type="datetime-local" step="1"></label>
        <label>已读 read<select id="sm-read"><option value="">全部</option><option value="0">未读 (0)</option><option value="1">已读 (1)</option></select></label>
        <label>状态 status<input id="sm-status-filter" type="number" min="-1" max="255" step="1" placeholder="全部" list="sm-status-values"><datalist id="sm-status-values"><option value="-1">无报告</option><option value="0">完成</option><option value="32">等待</option><option value="64">失败</option></datalist></label>
        <label>锁定 locked<select id="sm-locked"><option value="0">未锁定 (0)</option><option value="1">已锁定 (1)</option><option value="">全部（含锁定）</option></select></label>
      </div>
      <div class="actions sm-wrap"><button id="sm-load" class="primary">读取 / 应用筛选</button><button id="sm-retry" class="secondary" hidden>重试连接（原请求）</button></div>
      <div id="sm-notice" class="notice" role="status" hidden></div>
      <div class="table-toolbar sm-wrap"><strong id="sm-selection">已选择 0 条</strong><div class="actions sm-wrap"><button id="sm-page-select" class="secondary">选择当前页</button><button id="sm-filter-select" class="secondary">选择全部筛选结果</button><button id="sm-unselect" class="text-button">取消选择</button></div></div>
      <div class="table-scroll"><table class="sm-table"><thead><tr><th>选择</th><th>发送人</th><th>短信内容</th><th>时间</th><th>read</th><th>status</th><th>locked</th><th>type</th></tr></thead><tbody id="sm-rows"></tbody></table></div>
      <div class="pagination"><span id="sm-page-info">尚未读取</span><div class="actions"><button id="sm-prev" class="secondary">上一页</button><button id="sm-next" class="secondary">下一页</button></div></div>
      <div class="actions sm-wrap"><button id="sm-delete" class="secondary danger">请求删除选中短信</button><button id="sm-delete-filter" class="secondary danger">请求删除全部筛选结果</button><button id="sm-delete-all" class="sm-danger">一键清空所有短信</button></div>
      <small>普通筛选默认排除锁定短信。“一键清空”包含锁定短信及全部类型；清空范围以手机准备删除时的快照为准。删除不可撤销，失败停止，不自动恢复删除。</small>
      <p id="sm-progress" role="status"></p><progress id="sm-meter" hidden aria-label="删除总进度"></progress>`;
    const el = id => document.getElementById(`sm-${id}`);
    let database, storageError = '', device = '', revision = 0, rows = [], total = null, page = 0, loadedKey = '', selected = new Map(), filtered = false, pending = null, busy = false, polling = false;
    const filterIds = ['sender', 'keyword', 'from', 'to', 'read', 'status-filter', 'locked'];
    function filters() {
      const numeric = id => el(id).value === '' ? null : Number(el(id).value);
      const date = id => el(id).value === '' ? null : new Date(el(id).value).getTime();
      const value = { sender: el('sender').value, keyword: el('keyword').value, dateFrom: date('from'), dateTo: date('to'), read: numeric('read'), status: numeric('status-filter'), locked: numeric('locked') };
      if ([value.dateFrom, value.dateTo, value.status].some(number => number !== null && !Number.isSafeInteger(number))) throw new Error('请输入有效的时间和整数状态。');
      if (value.status !== null && (value.status < -1 || value.status > 255)) throw new Error('短信状态必须在 -1 到 255 之间。');
      if ([value.dateFrom, value.dateTo].some(number => number !== null && (number < 0 || number > 4102444800000))) throw new Error('时间必须在 1970 年到 2100 年之间。');
      if (value.dateFrom !== null && value.dateTo !== null && value.dateFrom > value.dateTo) throw new Error('起始时间不能晚于截止时间。');
      return value;
    }
    function key() { return JSON.stringify({ device, filters: filters() }); }
    function notice(message) { el('notice').textContent = message; el('notice').hidden = !message; }
    function storage(action, value, target = device) {
      return new Promise((resolve, reject) => {
        if (!database) return reject(new Error(storageError || '浏览器存储尚未就绪'));
        const tx = database.transaction('requests', action === 'read' ? 'readonly' : 'readwrite');
        const store = tx.objectStore('requests'), storageKey = `${scope}|${target}`;
        let result;
        const request = store.get(storageKey);
        request.onsuccess = () => {
          result = request.result || null;
          if (action === 'claim' && !result) { store.put(value, storageKey); result = value; }
          if (action === 'clear' && result && result.payload.requestId === value.payload.requestId) store.delete(storageKey);
        };
        tx.oncomplete = () => resolve(result);
        tx.onerror = tx.onabort = () => reject(tx.error || new Error('无法保存管理请求'));
      });
    }
    function update() {
      const available = !!device && !!database && !busy && !pending;
      const loaded = total !== null && rows.length > 0;
      el('load').disabled = !available;
      el('retry').hidden = !pending;
      el('retry').disabled = busy || polling;
      el('prev').disabled = !available || total === null || page === 0;
      el('next').disabled = !available || total === null || (page + 1) * 50 >= total;
      el('page-select').disabled = !available || !loaded;
      el('filter-select').disabled = !available || !loaded;
      el('unselect').disabled = !selected.size && !filtered;
      el('delete').disabled = !available || (!selected.size && !filtered);
      el('delete-filter').disabled = !available || total === null || total === 0;
      el('delete-all').disabled = !available;
      el('selection').textContent = filtered ? `已选择全部筛选结果（上次读取 ${total} 条，最终数量以手机预览为准）` : `已选择 ${selected.size} 条`;
      el('page-info').textContent = total === null ? '尚未读取 / 筛选已变更，请重新读取' : `共 ${total} 条 · 第 ${page + 1} / ${Math.max(1, Math.ceil(total / 50))} 页`;
    }
    function render() {
      const fragment = document.createDocumentFragment();
      for (const row of rows) {
        const tr = document.createElement('tr'), td = document.createElement('td'), check = document.createElement('input');
        check.type = 'checkbox'; check.checked = filtered || selected.has(String(row.id)); check.disabled = filtered || !!pending || busy;
        check.setAttribute('aria-label', `选择短信 ${row.id}`);
        check.addEventListener('change', () => { if (check.checked) selected.set(String(row.id), { id: row.id, fingerprint: row.fingerprint }); else selected.delete(String(row.id)); update(); });
        td.append(check); tr.append(td);
        const read = row.read === 1 ? '已读 (1)' : `未读 (${row.read})`;
        const status = ({ '-1': '无报告', 0: '完成', 32: '等待', 64: '失败' })[row.status] || '其他';
        for (const text of [row.sender, row.body, new Date(row.timestamp).toLocaleString(), read, `${status} (${row.status})`, row.locked === 1 ? '锁定 (1)' : `未锁定 (${row.locked})`, row.type]) {
          const cell = document.createElement('td'); cell.textContent = String(text ?? ''); tr.append(cell);
        }
        fragment.append(tr);
      }
      el('rows').replaceChildren(fragment); update();
    }
    function invalidate() { revision++; rows = []; total = null; loadedKey = ''; selected.clear(); filtered = false; page = 0; render(); }
    function progress(request) {
      const count = request.count === null ? '待手机统计' : request.count;
      el('progress').textContent = request.action === 'list' ? `读取：${labels[request.status] || request.status}${request.error ? ` · ${request.error}` : ''}` : `删除：${labels[request.status] || request.status} · 总计 ${count} · 已处理 ${request.processed} · 已删除 ${request.deleted}${request.error ? ` · ${request.error}` : ''}`;
      el('meter').hidden = request.action !== 'delete' || request.count === null;
      el('meter').max = request.count || 1; el('meter').value = request.processed || 0;
    }
    async function accept(request, record, rev, target) {
      if (request.id !== record.payload.requestId || request.deviceId !== target || request.action !== record.payload.action) throw new Error('服务器返回的管理请求不匹配，已停止更新。');
      if (target === device) progress(request);
      if (!terminal.has(request.status)) return;
      // Clear only this immutable request; another browser tab may already own a newer one.
      await storage('clear', record, target);
      if (target !== device || !pending || pending.payload.requestId !== request.id) return;
      pending = null;
      if (request.status === 'completed' && request.action === 'list' && rev === revision && record.viewKey === key()) {
        const result = request.result;
        if (!result || !Array.isArray(result.rows) || result.pageSize !== 50 || !Number.isSafeInteger(result.total) || result.total < 0 || result.page !== record.payload.page || result.rows.length > 50) throw new Error('短信列表响应格式错误，请重新读取。');
        rows = result.rows; total = result.total; page = result.page; loadedKey = record.viewKey;
      } else if (request.action === 'delete') {
        invalidate();
      }
      if (request.status === 'failed' || request.status === 'interrupted') notice(`${request.error || '手机处理失败'}。已停止，不会自动继续删除；请重新读取核对剩余短信。`);
      render();
    }
    async function transmit(record, rev, target) {
      try {
        const response = await (await api('/api/sms/requests', { method: 'POST', body: JSON.stringify(record.payload) })).json();
        await accept(response, record, rev, target);
      } catch (error) {
        // A definite rejection can release a claim only after confirming this ID was never created.
        if ([400, 404, 409, 413, 415].includes(error.httpStatus)) {
          let absent = false;
          try {
            const existing = await (await api(`/api/sms/requests/${encodeURIComponent(record.payload.requestId)}`)).json();
            await accept(existing, record, rev, target);
          } catch (lookupError) { absent = lookupError.httpStatus === 404; }
          if (absent) {
            await storage('clear', record, target);
            if (target === device && pending && pending.payload.requestId === record.payload.requestId) pending = null;
          }
        }
        throw error;
      }
    }
    async function start(action, selection, requestedPage = 0) {
      if (busy || pending || !device) return;
      busy = true; update(); const target = device, rev = revision;
      try {
        const all = action === 'delete' && selection.mode === 'all';
        const viewKey = all ? '' : key();
        const payload = { requestId: uuid(), deviceId: target, action, filters: all ? { sender: '', keyword: '', dateFrom: null, dateTo: null, read: null, status: null, locked: null } : filters() };
        if (action === 'list') payload.page = requestedPage; else payload.selection = selection;
        const proposed = { payload, viewKey };
        const record = await storage('claim', proposed, target);
        if (target === device) pending = record;
        if (record.payload.requestId !== proposed.payload.requestId) throw new Error('此手机已有未完成管理请求，已恢复原请求，请重试连接。');
        notice(''); await transmit(record, rev, target);
      } catch (error) { if (target === device) notice(`${error.message}。若请求结果不确定，请重试连接原请求。`); }
      finally { busy = false; render(); }
    }
    async function poll() {
      if (!pending || polling || busy) return;
      polling = true; const record = pending, target = device, rev = revision;
      try {
        const response = await (await api(`/api/sms/requests/${encodeURIComponent(record.payload.requestId)}`)).json();
        await accept(response, record, rev, target);
      } catch (error) { if (target === device) notice(`连接未确认：${error.message}。原请求已保留，可重试连接；不会创建重复删除任务。`); }
      finally { polling = false; update(); }
    }
    async function restore() {
      const target = device;
      pending = null; update();
      if (!target || !database) return;
      busy = true; update();
      try { const saved = await storage('read', null, target); if (device === target) pending = saved; }
      catch (error) { notice(error.message); }
      finally { busy = false; update(); }
      await poll();
    }
    el('device').addEventListener('change', () => { device = el('device').value; invalidate(); el('progress').textContent = ''; el('meter').hidden = true; notice(''); restore(); });
    for (const id of filterIds) el(id).addEventListener('input', invalidate);
    el('load').addEventListener('click', () => start('list', null, 0));
    el('prev').addEventListener('click', () => start('list', null, page - 1));
    el('next').addEventListener('click', () => start('list', null, page + 1));
    el('page-select').addEventListener('click', () => { for (const row of rows) selected.set(String(row.id), { id: row.id, fingerprint: row.fingerprint }); filtered = false; render(); });
    el('filter-select').addEventListener('click', () => { selected.clear(); filtered = true; render(); });
    el('unselect').addEventListener('click', () => { selected.clear(); filtered = false; render(); });
    function deleteRequest(mode) {
      if (busy || pending || !device) return;
      const isAll = mode === 'all';
      if (!isAll && (!loadedKey || loadedKey !== key())) return notice('筛选已变更，请重新读取后选择。');
      const selection = mode === 'selected' ? (filtered ? { mode: 'filtered' } : { mode, items: [...selected.values()] }) : { mode };
      if (selection.mode === 'selected' && !selection.items.length) return;
      const name = el('device').selectedOptions[0].textContent;
      const message = isAll ? `请求清空“${name}”的所有短信，包含锁定短信和所有类型。删除不可撤销。手机还会显示数量和样例，需在手机确认后才删除。继续？` : `请求删除“${name}”${selection.mode === 'filtered' ? '全部筛选结果（最终数量以手机预览为准）' : `选中的 ${selection.items.length} 条短信`}。删除不可撤销，仍需在手机确认。继续？`;
      if (window.confirm(message)) start('delete', selection);
    }
    el('delete').addEventListener('click', () => deleteRequest('selected'));
    el('delete-filter').addEventListener('click', () => deleteRequest('filtered'));
    el('delete-all').addEventListener('click', () => deleteRequest('all'));
    el('retry').addEventListener('click', async () => {
      if (!pending || busy || polling) return;
      busy = true; update(); const record = pending, target = device, rev = revision;
      try { notice(''); await transmit(record, rev, target); }
      catch (error) { if (target === device) notice(`${error.message}。原请求仍保留，请稍后重试。`); }
      finally { busy = false; render(); }
    });
    const ready = new Promise(resolve => {
      try {
        const request = indexedDB.open('sms-management-v1', 1);
        request.onupgradeneeded = () => request.result.createObjectStore('requests');
        request.onsuccess = () => { database = request.result; database.onversionchange = () => { database.close(); database = null; notice('浏览器存储版本已变化，请刷新。'); update(); }; update(); restore().then(resolve); };
        request.onerror = request.onblocked = () => { storageError = '管理请求存储不可用，已禁用操作，请检查浏览器存储权限后刷新'; notice(storageError); update(); resolve(); };
      } catch (error) { storageError = error.message; notice(storageError); update(); resolve(); }
    });
    const timer = setInterval(poll, 2000);
    update();
    return {
      ready,
      setDevices(devices) {
        const old = device;
        el('device').replaceChildren(new Option('请选择手机', ''));
        for (const item of devices) el('device').append(new Option(item.name || item.id, item.id));
        if (devices.some(item => item.id === old)) el('device').value = old;
        else if (old) { device = ''; pending = null; invalidate(); }
      },
      dispose() { clearInterval(timer); if (database) database.close(); },
      poll,
    };
  };
})();
