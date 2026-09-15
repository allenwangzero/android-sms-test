"""持久化短信读取/删除请求中继；删除确认只发生在手机。"""
import json
import re
import time
import uuid

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


def canonical(data):
    return json.dumps(data, ensure_ascii=False, sort_keys=True, separators=(",", ":"))


def validate_request(data):
    object_fields(data, {"requestId", "deviceId", "action", "filters", "page", "selection"},
                  {"requestId", "deviceId", "action"})
    for key in ("requestId", "deviceId"):
        value = data[key]
        try:
            valid = isinstance(value, str) and str(uuid.UUID(value)) == value
        except (ValueError, AttributeError):
            valid = False
        require(valid, key + " 必须为规范 UUID")
    require(data["action"] in ("list", "delete"), "请求操作无效")
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
    if data["action"] == "list":
        require("selection" not in data, "读取请求不能包含删除选择")
        return result
    selection = data.get("selection")
    object_fields(selection, {"mode", "items"}, {"mode"})
    require(selection["mode"] in ("selected", "filtered", "all"), "删除选择无效")
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
    if selection["mode"] == "all":
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


class SmsManagement:
    def init_management(self):
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS sms_requests (
                id TEXT PRIMARY KEY, device_id TEXT NOT NULL, action TEXT NOT NULL,
                payload TEXT NOT NULL, status TEXT NOT NULL, count INTEGER,
                processed INTEGER NOT NULL DEFAULT 0, deleted INTEGER NOT NULL DEFAULT 0,
                result TEXT, error TEXT NOT NULL DEFAULT '', last_report TEXT);
            CREATE INDEX IF NOT EXISTS sms_requests_device ON sms_requests(device_id, status);
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
            if row["action"] == "list":
                require(row["status"] == "queued" and target in {"completed", "failed"}, "读取状态流转无效", 409)
                require(data["processed"] == data["deleted"] == 0, "读取请求不能删除短信")
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
                if row["action"] == "list":
                    object_fields(result, {"total", "page", "pageSize", "rows"}, {"total", "page", "pageSize", "rows"})
                    require(integer(result["total"], 0, 2147483647) and result["total"] == data["count"]
                            and type(result["pageSize"]) is int and result["pageSize"] == 50
                            and type(result["page"]) is int and result["page"] == json.loads(row["payload"])["page"], "列表分页无效")
                    validate_rows(result["rows"], 50)
                    require(len(result["rows"]) == min(50, max(0, result["total"] - result["page"] * 50)), "列表条数与分页不符")
                else:
                    object_fields(result, {"count", "preview", "selectionMode"}, {"count", "preview", "selectionMode"})
                    require(type(result["count"]) is int and result["count"] == data["count"]
                            and result["selectionMode"] == json.loads(row["payload"])["selection"]["mode"], "删除预览无效")
                    validate_rows(result["preview"], 10)
                    require(len(result["preview"]) <= data["count"], "预览超过目标数量")
            if row["action"] == "list" and target == "completed" or target == "ready":
                require(result is not None, "必须包含预览结果")
            if row["action"] == "delete" and row["result"] is not None and result is not None:
                require(canonical(result) == row["result"], "已确认的删除预览不能改变", 409)
            stored_result = canonical(result) if result is not None else row["result"]
            self.db.execute("UPDATE sms_requests SET status=?,count=?,processed=?,deleted=?,error=?,result=?,last_report=? WHERE id=?",
                            (target, data["count"], data["processed"], data["deleted"], data["error"], stored_result, encoded, request_id))
            return self.get_management(request_id)
