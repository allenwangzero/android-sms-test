"""持久化短信读取/删除请求中继；删除确认只发生在手机。"""
import json
import re
import time
import uuid

try:
    from .sms_export import encode_document
except ImportError:
    from sms_export import encode_document

EXPORT_BATCH_SIZE = 500
EXPORT_MAX_BYTES = 256 * 1024 * 1024

ACTIVE = {"queued", "ready", "running"}
TERMINAL = {"completed", "failed", "interrupted", "cancelled"}
DEFAULT_FILTERS = {"sender": "", "keyword": "", "dateFrom": None, "dateTo": None,
                   "read": None, "status": None, "locked": 0}


class ManagementError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def require(condition, message, status=400):
    if not condition:
        raise ManagementError(status, message)


def integer(value, minimum, maximum):
    return type(value) is int and minimum <= value <= maximum


def object_fields(value, allowed, required=()):
    require(isinstance(value, dict) and set(required) <= value.keys() <= set(allowed), "字段缺失或包含不支持的字段")


def identifier(value):
    require(isinstance(value, str) and re.fullmatch(r"[1-9][0-9]{0,18}", value)
            and int(value) <= 9223372036854775807, "短信 ID 必须为正整数文本")


def fingerprint(value):
    require(isinstance(value, str) and re.fullmatch(r"[0-9a-f]{64}", value), "短信指纹无效")


def canonical_uuid(value, name):
    try:
        valid = isinstance(value, str) and str(uuid.UUID(value)) == value
    except (ValueError, AttributeError):
        valid = False
    require(valid, name + " 必须为规范 UUID")


def canonical(data):
    return json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def validate_request(data):
    object_fields(data, {"requestId", "deviceId", "action", "filters", "page", "selection"},
                  {"requestId", "deviceId", "action"})
    for key in ("requestId", "deviceId"):
        canonical_uuid(data[key], key)
    require(data["action"] in ("list", "batches", "delete", "export"), "请求操作无效")
    filters = data.get("filters", {})
    object_fields(filters, DEFAULT_FILTERS)
    filters = DEFAULT_FILTERS | filters
    for key, maximum in (("sender", 100), ("keyword", 4000)):
        require(isinstance(filters[key], str) and len(filters[key]) <= maximum, "筛选文本超过限制")
    for key, low, high in (("dateFrom", 0, 4102444800000), ("dateTo", 0, 4102444800000),
                           ("read", 0, 1), ("locked", 0, 1), ("status", -1, 255)):
        require(filters[key] is None or integer(filters[key], low, high), "筛选 " + key + " 无效")
    require(filters["dateFrom"] is None or filters["dateTo"] is None
            or filters["dateFrom"] <= filters["dateTo"], "开始时间不能晚于结束时间")
    page = data.get("page", 0)
    require(integer(page, 0, 10000000), "页码无效")
    result = dict(data, filters=filters, page=page)
    if data["action"] in ("list", "batches"):
        require("selection" not in data, "读取请求不能包含删除选择")
        if data["action"] == "batches":
            result["filters"] = dict(DEFAULT_FILTERS)
        return result
    selection = data.get("selection")
    object_fields(selection, {"mode", "items", "jobId"}, {"mode"})
    require(selection["mode"] in ("selected", "filtered", "all", "batch"), "删除选择无效")
    require(data["action"] != "export" or selection["mode"] != "batch", "导出不支持批次选择")
    if selection["mode"] == "batch":
        canonical_uuid(selection.get("jobId"), "jobId")
    else:
        require("jobId" not in selection, "该选择模式不能指定批次")
    if selection["mode"] == "selected":
        items = selection.get("items")
        require(isinstance(items, list) and 1 <= len(items) <= 100000, "须选择 1–100000 条短信")
        ids = set()
        for item in items:
            object_fields(item, {"id", "fingerprint"}, {"id", "fingerprint"})
            identifier(item["id"])
            fingerprint(item["fingerprint"])
            require(item["id"] not in ids, "短信 ID 不能重复")
            ids.add(item["id"])
        result["selection"] = {"mode": "selected", "items": sorted(items, key=lambda item: int(item["id"]))}
    else:
        require("items" not in selection, "该选择模式不能指定短信 ID")
    if selection["mode"] in ("all", "batch"):
        result["filters"] = DEFAULT_FILTERS | {"locked": None}
    result["page"] = 0
    return result


def validate_rows(rows, maximum):
    require(isinstance(rows, list) and len(rows) <= maximum, "预览条数超过限制")
    ids = set()
    fields = {"id", "fingerprint", "sender", "body", "timestamp", "type", "read", "status", "locked"}
    for row in rows:
        object_fields(row, fields, fields)
        identifier(row["id"])
        fingerprint(row["fingerprint"])
        require(row["id"] not in ids, "预览短信 ID 重复")
        ids.add(row["id"])
        for key, limit in (("sender", 200), ("body", 2000)):
            require(isinstance(row[key], str) and len(row[key]) <= limit, "预览文本超过限制")
        for key, low, high in (("timestamp", 0, 9223372036854775807), ("type", 0, 6),
                               ("read", 0, 1), ("locked", 0, 1), ("status", -1, 255)):
            require(integer(row[key], low, high), "预览 " + key + " 无效")


def validate_batches(batches):
    require(isinstance(batches, list) and len(batches) <= 50, "批次条数超过限制")
    ids = set()
    fields = {"jobId", "createdAt", "requested", "recorded", "status"}
    for batch in batches:
        object_fields(batch, fields, fields)
        canonical_uuid(batch["jobId"], "jobId")
        require(batch["jobId"] not in ids, "批次 ID 不能重复")
        ids.add(batch["jobId"])
        require(integer(batch["createdAt"], 0, 9223372036854775807), "批次时间无效")
        require(integer(batch["requested"], 1, 100000), "批次目标数量无效")
        require(integer(batch["recorded"], 0, batch["requested"]), "批次记录数量无效")
        require(batch["status"] in ("writing", "completed", "failed", "interrupted"), "批次状态无效")


class SmsManagement:
    def init_management(self):
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS sms_requests (
                id TEXT PRIMARY KEY, device_id TEXT NOT NULL, action TEXT NOT NULL,
                payload TEXT NOT NULL, status TEXT NOT NULL, count INTEGER,
                processed INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0,
                result TEXT, error TEXT NOT NULL DEFAULT '', last_report TEXT);
            CREATE INDEX IF NOT EXISTS sms_requests_device ON sms_requests(device_id, status);
            CREATE TABLE IF NOT EXISTS sms_export_batches (
                request_id TEXT NOT NULL, batch_index INTEGER NOT NULL,
                messages TEXT NOT NULL, count INTEGER NOT NULL, byte_size INTEGER NOT NULL,
                PRIMARY KEY (request_id, batch_index));
        """)

    @staticmethod
    def management_result(row, device=False):
        result = {"id": row["id"], "deviceId": row["device_id"], "action": row["action"],
                  "status": row["status"], "count": row["count"], "processed": row["processed"],
                  "deleted": row["deleted"], "error": row["error"],
                  "result": json.loads(row["result"]) if row["result"] is not None else None}
        if device:
            payload = json.loads(row["payload"])
            result.update({key: payload[key] for key in ("filters", "page", "selection") if key in payload})
        return result

    def create_management(self, data):
        payload = validate_request(data)
        encoded = canonical(payload)
        with self.lock, self.db:
            row = self.db.execute("SELECT * FROM sms_requests WHERE id=?", (payload["requestId"],)).fetchone()
            if row:
                require(row["payload"] == encoded, "requestId 已用于其他请求", 409)
                return self.management_result(row)
            device_id = payload["deviceId"]
            require(self.db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone(), "设备不存在", 404)
            require(not self.db.execute("SELECT 1 FROM sms_requests WHERE device_id=? AND status IN ('queued','ready','running')",
                                        (device_id,)).fetchone(), "该设备有未结束的短信管理请求", 409)
            self.db.execute("INSERT INTO sms_requests(id,device_id,action,payload,status) VALUES(?,?,?,?,'queued')",
                            (payload["requestId"], device_id, payload["action"], encoded))
            return self.get_management(payload["requestId"])

    def get_management(self, request_id):
        with self.lock:
            row = self.db.execute("SELECT * FROM sms_requests WHERE id=?", (request_id,)).fetchone()
            require(row is not None, "短信管理请求不存在", 404)
            return self.management_result(row)

    def pending_management(self, token):
        with self.lock, self.db:
            device_id = self.device(token)
            self.db.execute("UPDATE devices SET last_seen=? WHERE id=?", (int(time.time() * 1000), device_id))
            row = self.db.execute("SELECT * FROM sms_requests WHERE device_id=? AND status IN ('queued','ready','running') ORDER BY rowid LIMIT 1",
                                  (device_id,)).fetchone()
            return {"request": self.management_result(row, True) if row else None}

    def report_management(self, token, request_id, data):
        fields = {"status", "count", "processed", "deleted", "error", "result"}
        object_fields(data, fields, fields)
        require(isinstance(data["status"], str) and data["status"] in TERMINAL | {"ready", "running"}, "状态无效")
        for key in ("count", "processed", "deleted"):
            require(integer(data[key], 0, 2147483647), "计数无效")
        require(data["deleted"] <= data["processed"] <= data["count"], "处理计数无效")
        require(isinstance(data["error"], str) and len(data["error"]) <= 2000, "错误信息超过限制")
        with self.lock, self.db:
            device_id = self.device(token)
            row = self.db.execute("SELECT * FROM sms_requests WHERE id=? AND device_id=?", (request_id, device_id)).fetchone()
            require(row is not None, "短信管理请求不存在", 404)
            self.db.execute("UPDATE devices SET last_seen=? WHERE id=?", (int(time.time() * 1000), device_id))
            encoded = canonical(data)
            if row["last_report"] == encoded:
                return self.management_result(row)
            require(row["status"] not in TERMINAL, "请求已结束", 409)
            target = data["status"]
            if row["action"] in ("list", "batches"):
                require(row["status"] == "queued" and target in {"completed", "failed"}, "读取状态流转无效", 409)
                require(data["processed"] == data["deleted"] == 0, "读取请求不能删除短信")
            elif row["action"] == "export":
                transitions = {"queued": {"running", "failed", "interrupted"},
                               "running": {"running", "completed", "failed", "interrupted"}}
                require(target in transitions[row["status"]], "导出状态流转无效", 409)
                require(data["count"] <= 100000 and data["deleted"] == 0, "导出计数无效")
                received = self.db.execute(
                    "SELECT COALESCE(SUM(count),0) FROM sms_export_batches WHERE request_id=?",
                    (request_id,)).fetchone()[0]
                require(data["processed"] <= received, "导出进度超过已接收数量", 409)
                if row["status"] == "queued":
                    require(data["processed"] == 0, "导出快照建立前不能有传输进度")
                if target == "completed":
                    require(data["processed"] == data["count"] == received, "导出尚未完整接收", 409)
            else:
                transitions = {"queued": {"ready", "failed", "interrupted", "cancelled"},
                               "ready": {"running", "failed", "interrupted", "cancelled"},
                               "running": {"running", "completed", "failed", "interrupted"}}
                require(target in transitions[row["status"]], "删除状态流转无效", 409)
                if row["status"] in {"queued", "ready"}:
                    require(data["processed"] == data["deleted"] == 0, "手机确认前不能删除短信")
            require(row["count"] is None or data["count"] == row["count"], "目标数量不能改变", 409)
            require(data["processed"] >= row["processed"] and data["deleted"] >= row["deleted"], "进度不能回退", 409)
            if target == "completed" and row["action"] == "delete":
                require(data["processed"] == data["count"], "完成状态须处理全部目标")
            result = data["result"]
            if result is not None:
                if row["action"] in ("list", "batches"):
                    entries_key = "batches" if row["action"] == "batches" else "rows"
                    fields = {"total", "page", "pageSize", entries_key}
                    object_fields(result, fields, fields)
                    require(integer(result["total"], 0, 2147483647) and result["total"] == data["count"]
                            and type(result["pageSize"]) is int and result["pageSize"] == 50
                            and type(result["page"]) is int and result["page"] == json.loads(row["payload"])["page"], "列表分页无效")
                    if row["action"] == "batches":
                        validate_batches(result[entries_key])
                    else:
                        validate_rows(result[entries_key], 50)
                    require(len(result[entries_key]) == min(50, max(0, result["total"] - result["page"] * 50)), "列表条数与分页不符")
                elif row["action"] == "export":
                    fields = {"count", "batchSize", "batchCount"}
                    object_fields(result, fields, fields)
                    require(type(result["count"]) is int and result["count"] == data["count"]
                            and type(result["batchSize"]) is int and result["batchSize"] == EXPORT_BATCH_SIZE
                            and type(result["batchCount"]) is int
                            and result["batchCount"] == (data["count"] + EXPORT_BATCH_SIZE - 1) // EXPORT_BATCH_SIZE,
                            "导出快照元数据无效")
                else:
                    selection = json.loads(row["payload"])["selection"]
                    selection_mode = selection["mode"]
                    fields = {"count", "preview", "selectionMode"}
                    if selection_mode == "batch":
                        fields |= {"jobId", "missing", "changed"}
                    object_fields(result, fields, fields)
                    require(type(result["count"]) is int and result["count"] == data["count"]
                            and result["selectionMode"] == selection_mode, "删除预览无效")
                    if selection_mode == "batch":
                        require(result["jobId"] == selection["jobId"], "删除预览批次不符")
                        require(integer(result["missing"], 0, 100000) and integer(result["changed"], 0, 100000)
                                and result["count"] + result["missing"] + result["changed"] <= 100000,
                                "批次跳过数量无效")
                    validate_rows(result["preview"], 10)
                    require(len(result["preview"]) <= data["count"], "预览超过目标数量")
            if row["action"] in ("list", "batches") and target == "completed" or target == "ready":
                require(result is not None, "必须包含预览结果")
            if row["action"] == "export" and target in {"running", "completed"}:
                require(result is not None or row["result"] is not None, "必须包含导出快照元数据")
            if row["action"] in ("delete", "export") and row["result"] is not None and result is not None:
                require(canonical(result) == row["result"], "已冻结的请求结果不能改变", 409)
            stored_result = canonical(result) if result is not None else row["result"]
            self.db.execute("UPDATE sms_requests SET status=?,count=?,processed=?,deleted=?,error=?,result=?,last_report=? WHERE id=?",
                            (target, data["count"], data["processed"], data["deleted"], data["error"], stored_result, encoded, request_id))
            return self.get_management(request_id)

    def store_export_batch(self, token, request_id, index, data):
        object_fields(data, {"messages"}, {"messages"})
        require(integer(index, 0, 199), "导出分批索引无效")
        with self.lock, self.db:
            device_id = self.device(token)
            row = self.db.execute("SELECT * FROM sms_requests WHERE id=? AND device_id=?",
                                  (request_id, device_id)).fetchone()
            require(row is not None, "短信管理请求不存在", 404)
            require(row["action"] == "export" and row["status"] == "running", "导出请求未运行", 409)
            expected = min(EXPORT_BATCH_SIZE, row["count"] - index * EXPORT_BATCH_SIZE)
            require(isinstance(data["messages"], list) and expected > 0
                    and len(data["messages"]) == expected, "导出分批数量不符")
            snapshot, size = self.validate_messages(data["messages"])
            existing = self.db.execute(
                "SELECT messages FROM sms_export_batches WHERE request_id=? AND batch_index=?",
                (request_id, index)).fetchone()
            if existing:
                require(existing["messages"] == snapshot, "导出分批内容不能改变", 409)
            else:
                received = self.db.execute(
                    "SELECT COUNT(*) AS batches, COALESCE(SUM(byte_size),0) AS bytes "
                    "FROM sms_export_batches WHERE request_id=?", (request_id,)).fetchone()
                require(index == received["batches"], "导出分批须按顺序传输", 409)
                require(received["bytes"] + size <= EXPORT_MAX_BYTES, "导出快照超过 256 MiB 限制", 413)
                self.db.execute("INSERT INTO sms_export_batches VALUES(?,?,?,?,?)",
                                (request_id, index, snapshot, len(data["messages"]), size))
            self.db.execute("UPDATE devices SET last_seen=? WHERE id=?", (int(time.time() * 1000), device_id))
            return {"requestId": request_id, "index": index, "count": expected}

    def export_document(self, request_id, format_name):
        require(format_name in ("xml", "json"), "只支持 XML 或 JSON 导出")
        with self.lock:
            row = self.db.execute("SELECT * FROM sms_requests WHERE id=?", (request_id,)).fetchone()
            require(row is not None, "短信管理请求不存在", 404)
            require(row["action"] == "export" and row["status"] == "completed", "导出尚未完成", 409)
            batches = self.db.execute(
                "SELECT messages FROM sms_export_batches WHERE request_id=? ORDER BY batch_index",
                (request_id,)).fetchall()
            messages = [message for batch in batches for message in json.loads(batch["messages"])]
            require(len(messages) == row["count"], "导出快照不完整", 409)
        try:
            document, mime = encode_document(messages, format_name)
        except ValueError as error:
            raise ManagementError(400, str(error)) from None
        message = ("XML 文件超过 256 MiB 限制，请下载 JSON 格式" if format_name == "xml"
                   else "JSON 文件超过 256 MiB 限制")
        require(len(document) <= EXPORT_MAX_BYTES, message, 413)
        return document, mime, "sms-export-" + request_id + "." + format_name
