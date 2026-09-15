'use strict';

globalThis.normalizeSmsMetadata = function normalizeSmsMetadata(record, index = 0) {
  const fail = message => { throw new Error(`第 ${index + 1} 条短信：${message}`); };
  if (!record || typeof record !== 'object' || Array.isArray(record)) fail('记录必须是短信对象。');
  const result = { ...record };
  if (Object.hasOwn(record, 'type') && record.type !== 1) fail('仅支持 type="1" 的收件短信。');
  const integers = { type: [1, 1, 1], protocol: [0, 255, 0], read: [0, 1, 1], status: [-1, 255, -1], locked: [0, 1, 0] };
  for (const [field, [min, max, fallback]] of Object.entries(integers)) {
    const value = Object.hasOwn(record, field) ? record[field] : fallback;
    if (!(field === 'protocol' && value === null) && (!Number.isInteger(value) || value < min || value > max)) {
      fail(`${field} 必须是 ${min}–${max} 范围内的整数${field === 'protocol' ? '或 null' : ''}。`);
    }
    result[field] = value;
  }
  for (const [field, limit] of Object.entries({ subject: 4000, service_center: 100 })) {
    const value = Object.hasOwn(record, field) ? record[field] : null;
    if (value !== null && (typeof value !== 'string' || [...value].length > limit)) fail(`${field} 必须是 null 或最多 ${limit} 个字符的文本。`);
    result[field] = value;
  }
  for (const field of ['toa', 'sc_toa']) {
    const value = Object.hasOwn(record, field) ? record[field] : null;
    if (value !== null) fail(`${field} 在标准 Android 短信库中没有对应字段，仅支持 null。`);
    result[field] = null;
  }
  return result;
};

globalThis.parseSmsXml = function parseSmsXml(text) {
  const maxCount = 100000;
  const maxTimestamp = 4102444800000;
  const hasContent = value => /[^\s\u0085\u001c-\u001f]/u.test(value);
  const fail = (index, message) => {
    throw new Error(`第 ${index + 1} 条短信：${message}`);
  };

  if (typeof text !== 'string' || !text.trim()) throw new Error('XML 文件不能为空。');
  if (/<!\s*(?:DOCTYPE|ENTITY)\b/i.test(text)) {
    throw new Error('XML 不允许包含 DOCTYPE 或 ENTITY 声明。');
  }

  const document = new DOMParser().parseFromString(text, 'application/xml');
  if (document.getElementsByTagName('parsererror').length ||
      document.getElementsByTagNameNS('*', 'parsererror').length) {
    throw new Error('XML 格式损坏，请检查标签、属性及字符转义。');
  }
  const root = document.documentElement;
  if (!root || root.tagName !== 'smses' || root.namespaceURI) {
    throw new Error('XML 根元素必须是 smses。');
  }
  for (const node of document.childNodes) {
    if (node.nodeType === 7) throw new Error('XML 不允许包含处理指令。');
  }

  const records = [];
  for (const node of root.childNodes) {
    if (node.nodeType === 8) continue;
    if ((node.nodeType === 3 || node.nodeType === 4) && !node.textContent.trim()) continue;
    const index = records.length;
    if (node.nodeType !== 1 || node.tagName !== 'sms' || node.namespaceURI) {
      fail(index, 'smses 下只能包含 sms 元素，不支持彩信或其他内容。');
    }
    if (index >= maxCount) throw new Error('短信数量不能超过 100000 条。');
    for (const child of node.childNodes) {
      if (child.nodeType === 8) continue;
      if ((child.nodeType === 3 || child.nodeType === 4) && !child.textContent.trim()) continue;
      fail(index, 'sms 元素只能通过属性提供短信数据，不能包含嵌套内容。');
    }
    for (const field of ['address', 'body', 'date', 'type']) {
      if (!node.hasAttribute(field)) fail(index, `缺少 ${field} 属性。`);
    }

    const sender = node.getAttribute('address');
    const body = node.getAttribute('body');
    const date = node.getAttribute('date');
    if (!hasContent(sender) || [...sender].length > 100) fail(index, '发送人需为 1–100 个字符，不能只有空白。');
    if (!hasContent(body) || [...body].length > 4000) fail(index, '内容需为 1–4000 个字符，不能只有空白。');
    const timestamp = Number(date);
    if (!/^\d+$/.test(date) || !Number.isSafeInteger(timestamp) || timestamp > maxTimestamp) {
      fail(index, 'date 必须是 0–4102444800000 范围内的十进制整数毫秒时间戳。');
    }
    const metadata = {};
    for (const field of ['type', 'protocol', 'read', 'status', 'locked']) {
      if (!node.hasAttribute(field)) continue;
      const value = node.getAttribute(field);
      if (field === 'protocol' && value === 'null') metadata[field] = null;
      else {
        if (!/^-?\d+$/.test(value)) fail(index, `${field} 必须是十进制整数${field === 'protocol' ? '或 null' : ''}。`);
        metadata[field] = Number(value);
      }
    }
    for (const field of ['subject', 'service_center', 'toa', 'sc_toa']) {
      if (node.hasAttribute(field)) metadata[field] = node.getAttribute(field) === 'null' ? null : node.getAttribute(field);
    }
    records.push(normalizeSmsMetadata({ sender, body, timestamp, ...metadata }, index));
  }

  if (!records.length) throw new Error('XML 中没有可导入的短信。');
  if (root.hasAttribute('count')) {
    const count = root.getAttribute('count');
    if (!/^\d+$/.test(count) || !Number.isSafeInteger(Number(count)) || Number(count) !== records.length) {
      throw new Error(`XML 的 count 属性必须是与实际短信数量 ${records.length} 一致的非负整数。`);
    }
  }
  return records;
};
