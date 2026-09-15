#!/usr/bin/env python3
"""可信局域网短信测试服务；仅使用 SQLite 保存状态，不记录请求内容。"""
import argparse
import hmac
import io
import ipaddress
import json
import secrets
import socket
import sqlite3
import threading
import time
import uuid
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import urlencode, urlsplit

try:
    from .sms_management import ManagementError, SmsManagement
except ImportError:  # 直接运行 desktop/server.py
    from sms_management import ManagementError, SmsManagement

# 固定与当前管理端兼容的本地安装包，升级协议时同步更新。
APK_VERSION = "v1.4.0"
APK_FILENAME = f"android-sms-test-{APK_VERSION}.apk"
APK_DOWNLOAD_PATH = f"/downloads/{APK_FILENAME}"

MAX_BODY = 16 * 1024 * 1024
MAX_COUNT = 100000
BATCH_SIZE = 500
REQUIRED_SMS_FIELDS = {"sender", "body", "timestamp"}
SMS_INTEGER_RANGES = {"type": (1, 1), "protocol": (0, 255), "read": (0, 1),
                      "status": (-1, 255), "locked": (0, 1)}
SMS_TEXT_LIMITS = {"subject": 4000, "service_center": 100}
SMS_FIELDS = REQUIRED_SMS_FIELDS | SMS_INTEGER_RANGES.keys() | SMS_TEXT_LIMITS.keys() | {"toa", "sc_toa"}
MAX_SNAPSHOT = 256 * 1024 * 1024
TERMINAL = {"completed", "failed", "interrupted"}
TRANSITIONS = {
    "queued": {"received"},
    "received": {"writing", "failed", "interrupted"},
    "writing": {"completed", "failed", "interrupted"},
}
STATIC = {"/": ("index.html", "text/html; charset=utf-8"),
          "/index.html": ("index.html", "text/html; charset=utf-8"),
          "/app.js": ("app.js", "text/javascript; charset=utf-8"),
          "/sms-manager.js": ("sms-manager.js", "text/javascript; charset=utf-8"),
          "/sms-xml.js": ("sms-xml.js", "text/javascript; charset=utf-8"),
          "/style.css": ("style.css", "text/css; charset=utf-8")}


def now():
    return int(time.time() * 1000)


class ApiError(Exception):
    def __init__(self, status, message):
        super().__init__(message)
        self.status = status


def require(condition, message, status=400):
    if not condition:
        raise ApiError(status, message)


def is_uuid(value):
    try:
        return isinstance(value, str) and str(uuid.UUID(value)) == value.lower()
    except (ValueError, AttributeError):
        return False


def text_field(value, maximum):
    return isinstance(value, str) and 0 < len(value) <= maximum and bool(value.strip())


class Store(SmsManagement):
    def __init__(self, db_path, server_url):
        self.server_url = server_url
        self.lock = threading.RLock()
        self.db = sqlite3.connect(str(db_path), check_same_thread=False)
        self.db.row_factory = sqlite3.Row
        self.db.executescript("""
            PRAGMA journal_mode=WAL;
            CREATE TABLE IF NOT EXISTS settings (key TEXT PRIMARY KEY, value TEXT NOT NULL);
            CREATE TABLE IF NOT EXISTS devices (
                id TEXT PRIMARY KEY, client_id TEXT UNIQUE NOT NULL,
                token TEXT UNIQUE NOT NULL, name TEXT NOT NULL, last_seen INTEGER NOT NULL);
            CREATE TABLE IF NOT EXISTS jobs (
                id TEXT PRIMARY KEY, device_id TEXT NOT NULL, status TEXT NOT NULL,
                count INTEGER NOT NULL, written INTEGER NOT NULL, error TEXT NOT NULL,
                created_at INTEGER NOT NULL, updated_at INTEGER NOT NULL, messages TEXT NOT NULL);
        """)
        self.db.executescript("""
            CREATE TABLE IF NOT EXISTS cancelled_uploads (id TEXT PRIMARY KEY);
            CREATE TABLE IF NOT EXISTS uploads (
                id TEXT PRIMARY KEY, device_ids TEXT NOT NULL, count INTEGER NOT NULL,
                byte_count INTEGER NOT NULL DEFAULT 0, job_ids TEXT NOT NULL DEFAULT '[]');
            CREATE TABLE IF NOT EXISTS upload_batches (
                upload_id TEXT NOT NULL, batch_index INTEGER NOT NULL, messages TEXT NOT NULL,
                PRIMARY KEY (upload_id, batch_index));
        """)
        self.init_management()
        with self.lock, self.db:
            columns = {row["name"] for row in self.db.execute("PRAGMA table_info(jobs)")}
            if "upload_id" not in columns:
                self.db.execute("ALTER TABLE jobs ADD COLUMN upload_id TEXT")
            if not self.setting("admin_token"):
                self.set_setting("admin_token", secrets.token_urlsafe(32))
            self.admin_token = self.setting("admin_token")

    def setting(self, key):
        row = self.db.execute("SELECT value FROM settings WHERE key=?", (key,)).fetchone()
        return row[0] if row else None

    def set_setting(self, key, value):
        self.db.execute("INSERT OR REPLACE INTO settings VALUES (?, ?)", (key, str(value)))

    def pairing(self, rotate=False):
        with self.lock, self.db:
            expires = int(self.setting("pair_expires") or 0)
            if rotate or expires <= now():
                expires = now() + 600000
                self.set_setting("pair_token", secrets.token_urlsafe(32))
                self.set_setting("pair_expires", expires)
            query = urlencode({"url": self.server_url, "token": self.setting("pair_token")})
            return {"url": "sms-test://pair?" + query, "expiresAt": expires,
                    "serverUrl": self.server_url}

    def pair(self, data):
        token, name, client_id = data.get("token"), data.get("name"), data.get("clientId")
        require(text_field(name, 100), "设备名称须为 1–100 个字符")
        require(is_uuid(client_id), "clientId 必须为 UUID")
        with self.lock, self.db:
            expected = self.setting("pair_token") or ""
            require(isinstance(token, str) and token.isascii() and bool(expected)
                    and hmac.compare_digest(token, expected)
                    and int(self.setting("pair_expires") or 0) > now(), "配对码无效或已过期", 401)
            row = self.db.execute("SELECT * FROM devices WHERE client_id=?", (client_id,)).fetchone()
            device_id = row["id"] if row else str(uuid.uuid4())
            device_token = row["token"] if row else secrets.token_urlsafe(32)
            self.db.execute("INSERT OR REPLACE INTO devices VALUES (?, ?, ?, ?, ?)",
                            (device_id, client_id, device_token, name, now()))
            return {"deviceId": device_id, "deviceToken": device_token, "name": name}

    def admin(self, token):
        require(bool(token) and token.isascii() and hmac.compare_digest(token, self.admin_token), "管理认证失败", 401)

    def device(self, token):
        require(bool(token), "设备认证失败", 401)
        row = self.db.execute("SELECT id FROM devices WHERE token=?", (token,)).fetchone()
        require(row is not None, "设备认证失败", 401)
        return row["id"]

    def batch_messages(self, row, index):
        if row["upload_id"]:
            batch = self.db.execute("SELECT messages FROM upload_batches WHERE upload_id=? AND batch_index=?",
                                    (row["upload_id"], index)).fetchone()
            require(batch is not None, "短信分批数据不存在", 500)
            return json.loads(batch["messages"])
        return json.loads(row["messages"])[index * BATCH_SIZE:(index + 1) * BATCH_SIZE]

    def job(self, row, preview=False):
        result = {"id": row["id"], "deviceId": row["device_id"], "status": row["status"],
                  "count": row["count"], "written": row["written"], "error": row["error"],
                  "createdAt": row["created_at"], "updatedAt": row["updated_at"],
                  "batchSize": BATCH_SIZE, "batchCount": (row["count"] + BATCH_SIZE - 1) // BATCH_SIZE}
        if preview:
            result["preview"] = self.batch_messages(row, 0)[:20]
        return result

    def state(self):
        with self.lock:
            devices = [{"id": row["id"], "name": row["name"], "lastSeen": row["last_seen"]}
                       for row in self.db.execute("SELECT * FROM devices ORDER BY last_seen DESC")]
            jobs = [self.job(row) for row in self.db.execute("SELECT * FROM jobs ORDER BY rowid DESC")]
            return {"devices": devices, "jobs": jobs}

    def upload_result(self, row):
        ids = json.loads(row["job_ids"])
        return {"uploadId": row["id"], "count": row["count"], "batchSize": BATCH_SIZE,
                "batchCount": (row["count"] + BATCH_SIZE - 1) // BATCH_SIZE,
                "receivedBatches": [batch[0] for batch in self.db.execute(
                    "SELECT batch_index FROM upload_batches WHERE upload_id=? ORDER BY batch_index", (row["id"],))],
                "jobs": [self.job(self.db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())
                         for job_id in ids]}

    def require_active_upload(self, upload_id):
        require(not self.db.execute("SELECT 1 FROM cancelled_uploads WHERE id=?", (upload_id,)).fetchone(),
                "上传已经取消，请创建新的发送任务", 410)

    def create_upload(self, data):
        request_id, device_ids, count = data.get("requestId"), data.get("deviceIds"), data.get("count")
        require(is_uuid(request_id), "requestId 必须为 UUID")
        require(isinstance(device_ids, list) and 1 <= len(device_ids) <= 1000
                and all(is_uuid(value) for value in device_ids), "请选择有效设备")
        require(len(set(device_ids)) == len(device_ids), "设备不能重复")
        require(type(count) is int and 1 <= count <= MAX_COUNT, "每个任务须为 1–100000 条短信")
        canonical_devices = json.dumps(sorted(device_ids))
        with self.lock, self.db:
            self.require_active_upload(request_id)
            row = self.db.execute("SELECT * FROM uploads WHERE id=?", (request_id,)).fetchone()
            if row:
                require(row["device_ids"] == canonical_devices and row["count"] == count,
                        "requestId 已用于其他设备或短信数量", 409)
            else:
                for device_id in device_ids:
                    require(self.db.execute("SELECT 1 FROM devices WHERE id=?", (device_id,)).fetchone(),
                            "设备不存在", 404)
                self.db.execute("INSERT INTO uploads (id, device_ids, count) VALUES (?, ?, ?)",
                                (request_id, canonical_devices, count))
                row = self.db.execute("SELECT * FROM uploads WHERE id=?", (request_id,)).fetchone()
            return self.upload_result(row)

    @staticmethod
    def validate_messages(messages):
        require(isinstance(messages, list), "短信须为数组")
        for message in messages:
            require(isinstance(message, dict), "短信格式错误")
            require(REQUIRED_SMS_FIELDS <= message.keys(), "短信必须包含 sender/body/timestamp")
            require(message.keys() <= SMS_FIELDS, "短信包含不支持的字段")
            require(text_field(message["sender"], 100), "发送人须为 1–100 个字符")
            require(text_field(message["body"], 4000), "内容须为 1–4000 个字符")
            require(type(message["timestamp"]) is int and 0 <= message["timestamp"] <= 4102444800000,
                    "短信时间须为有效毫秒整数")
            for field, (minimum, maximum) in SMS_INTEGER_RANGES.items():
                if field not in message or (field == "protocol" and message[field] is None):
                    continue
                value = message[field]
                require(type(value) is int and minimum <= value <= maximum,
                        f"短信 {field} 须为 {minimum}–{maximum} 整数")
            for field, maximum in SMS_TEXT_LIMITS.items():
                value = message.get(field)
                require(value is None or (isinstance(value, str) and len(value) <= maximum),
                        f"短信 {field} 须为 null 或最多 {maximum} 个字符的字符串")
            for field in ("toa", "sc_toa"):
                require(message.get(field) is None, f"Android 标准短信数据库没有 {field} 列，仅支持 null")
        try:
            snapshot = json.dumps(messages, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
            size = len(snapshot.encode("utf-8"))
        except UnicodeEncodeError:
            raise ApiError(400, "短信包含无效 Unicode 字符") from None
        return snapshot, size

    def upload_batch(self, upload_id, index, data):
        messages = data.get("messages")
        snapshot, size = self.validate_messages(messages)
        with self.lock, self.db:
            self.require_active_upload(upload_id)
            row = self.db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()
            require(row is not None, "上传任务不存在", 404)
            require(0 <= index < (row["count"] + BATCH_SIZE - 1) // BATCH_SIZE, "分批索引越界")
            require(len(messages) == min(BATCH_SIZE, row["count"] - index * BATCH_SIZE), "分批短信数量不符")
            prior = self.db.execute("SELECT messages FROM upload_batches WHERE upload_id=? AND batch_index=?",
                                    (upload_id, index)).fetchone()
            if prior:
                require(prior["messages"] == snapshot, "此分批已上传不同内容", 409)
            else:
                require(row["job_ids"] == "[]", "任务已提交，不能再修改", 409)
                # Count the canonical full array: two brackets + records + separators.
                total_bytes = row["byte_count"] + size - 1
                require(total_bytes + 1 <= MAX_SNAPSHOT, "完整短信列表超过 256 MiB", 413)
                self.db.execute("INSERT INTO upload_batches VALUES (?, ?, ?)", (upload_id, index, snapshot))
                self.db.execute("UPDATE uploads SET byte_count=? WHERE id=?", (total_bytes, upload_id))
            return {"ok": True, "uploadId": upload_id, "index": index}

    def commit_upload(self, upload_id):
        with self.lock, self.db:
            self.require_active_upload(upload_id)
            row = self.db.execute("SELECT * FROM uploads WHERE id=?", (upload_id,)).fetchone()
            require(row is not None, "上传任务不存在", 404)
            ids = json.loads(row["job_ids"])
            if not ids:
                received = self.db.execute("SELECT COUNT(*) FROM upload_batches WHERE upload_id=?", (upload_id,)).fetchone()[0]
                require(received == (row["count"] + BATCH_SIZE - 1) // BATCH_SIZE, "短信尚未全部上传", 409)
                for device_id in json.loads(row["device_ids"]):
                    job_id, timestamp = str(uuid.uuid4()), now()
                    self.db.execute("INSERT INTO jobs (id, device_id, status, count, written, error, created_at, updated_at, messages, upload_id) "
                                    "VALUES (?, ?, 'queued', ?, 0, '', ?, ?, '[]', ?)",
                                    (job_id, device_id, row["count"], timestamp, timestamp, upload_id))
                    ids.append(job_id)
                self.db.execute("UPDATE uploads SET job_ids=? WHERE id=?", (json.dumps(ids), upload_id))
            return {"jobs": [self.job(self.db.execute("SELECT * FROM jobs WHERE id=?", (job_id,)).fetchone())
                             for job_id in ids]}

    def cancel_upload(self, upload_id):
        require(is_uuid(upload_id), "uploadId 必须为 UUID")
        with self.lock, self.db:
            row = self.db.execute("SELECT job_ids FROM uploads WHERE id=?", (upload_id,)).fetchone()
            if row:
                require(row["job_ids"] == "[]", "任务已提交，不能取消上传", 409)
                self.db.execute("DELETE FROM upload_batches WHERE upload_id=?", (upload_id,))
                self.db.execute("DELETE FROM uploads WHERE id=?", (upload_id,))
            self.db.execute("INSERT OR IGNORE INTO cancelled_uploads VALUES (?)", (upload_id,))
            return {"ok": True}

    def device_batch(self, token, job_id, index):
        with self.lock, self.db:
            device_id = self.device(token)
            row = self.db.execute("SELECT * FROM jobs WHERE id=? AND device_id=?", (job_id, device_id)).fetchone()
            require(row is not None, "任务不存在", 404)
            require(0 <= index < (row["count"] + BATCH_SIZE - 1) // BATCH_SIZE, "分批索引越界")
            require(row["status"] in {"received", "writing"}, "任务尚未接收或已经结束", 409)
            require(index * BATCH_SIZE == row["written"], "须先反馈上一分批完整进度", 409)
            self.db.execute("UPDATE devices SET last_seen=? WHERE id=?", (now(), device_id))
            return {"jobId": job_id, "index": index, "offset": index * BATCH_SIZE,
                    "total": row["count"], "messages": self.batch_messages(row, index)}

    def pending(self, token):
        with self.lock, self.db:
            device_id = self.device(token)
            self.db.execute("UPDATE devices SET last_seen=? WHERE id=?", (now(), device_id))
            row = self.db.execute("SELECT * FROM jobs WHERE device_id=? AND status IN ('queued','received','writing')"
                                  " ORDER BY rowid LIMIT 1", (device_id,)).fetchone()
            return {"job": self.job(row, True) if row else None}

    def update(self, token, job_id, data):
        status, written, error = data.get("status"), data.get("written"), data.get("error", "")
        require(isinstance(status, str) and status in {"received", "writing"} | TERMINAL, "状态无效")
        require(type(written) is int and written >= 0, "written 必须是非负整数")
        require(isinstance(error, str) and len(error) <= 4000, "错误信息最长 4000 字符")
        with self.lock, self.db:
            device_id = self.device(token)
            row = self.db.execute("SELECT * FROM jobs WHERE id=? AND device_id=?", (job_id, device_id)).fetchone()
            require(row is not None, "任务不存在", 404)
            require(row["written"] <= written <= row["count"], "写入数量越界或回退", 409)
            if row["status"] in TERMINAL:
                require(status == row["status"] and written == row["written"] and error == row["error"],
                        "终态任务不可更改", 409)
            else:
                require(status == row["status"] or status in TRANSITIONS.get(row["status"], set()),
                        "状态不可回退或跳跃", 409)
            require(status != "received" or written == 0, "未开始写入时数量必须为零", 409)
            require(not (row["status"] == "received" and status in {"failed", "interrupted"} and written != 0),
                    "未开始写入时数量必须为零", 409)
            require(status != "completed" or written == row["count"], "完成数量须等于短信总数", 409)
            self.db.execute("UPDATE jobs SET status=?, written=?, error=?, updated_at=? WHERE id=?",
                            (status, written, error, now(), job_id))
            self.db.execute("UPDATE devices SET last_seen=? WHERE id=?", (now(), device_id))
            return {"ok": True}


class Server(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, store, static_dir=None):
        super().__init__(address, Handler)
        self.store = store
        self.static_dir = static_dir or Path(__file__).parent / "static"
        self.apk_dir = Path(__file__).resolve().parent.parent / "dist"
        advertised = urlsplit(store.server_url)
        port = self.server_address[1]
        hosts = {"127.0.0.1", "localhost", advertised.hostname}
        if address[0] != "0.0.0.0":
            hosts.add(address[0])
        self.allowed_hosts = {f"{host}:{port}" for host in hosts}
        if port == 80:
            self.allowed_hosts.update(hosts)

    def get_request(self):
        conn, addr = super().get_request()
        conn.settimeout(10)
        return conn, addr


class Handler(BaseHTTPRequestHandler):
    server_version = "SmsTest/2"

    def log_message(self, format_string, *args):
        pass  # 配对码、设备令牌和短信正文均不记录。

    def send(self, status, payload, content_type="application/json; charset=utf-8", download_name=None):
        content = (json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode()
                   if isinstance(payload, dict) else payload)
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(content)))
        if download_name:
            self.send_header("Content-Disposition", f'attachment; filename="{download_name}"')
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        self.send_header("Referrer-Policy", "no-referrer")
        self.send_header("X-Frame-Options", "DENY")
        self.send_header("Content-Security-Policy", "default-src 'self'; img-src 'self' blob: data:; "
                         "style-src 'self'; script-src 'self'; connect-src 'self'; frame-ancestors 'none'")
        self.end_headers()
        self.wfile.write(content)

    def token(self):
        auth = self.headers.get("Authorization", "")
        return auth[7:] if auth.startswith("Bearer ") else ""

    def body(self):
        require(self.headers.get("Transfer-Encoding") is None, "不支持分块请求", 400)
        length = self.headers.get("Content-Length", "")
        require(length.isdigit(), "需要 Content-Length", 411)
        length = int(length)
        require(length <= MAX_BODY, "请求超过 16 MiB", 413)
        require(self.headers.get_content_type() == "application/json", "需要 application/json", 415)
        raw = self.rfile.read(length)
        require(len(raw) == length, "请求正文不完整")
        try:
            result = json.loads(raw)
        except (ValueError, UnicodeDecodeError, RecursionError):
            raise ApiError(400, "JSON 格式错误") from None
        require(isinstance(result, dict), "请求必须为 JSON 对象")
        return result

    def handle_api(self, method):
        host = self.headers.get("Host", "").lower()
        require(host in self.server.allowed_hosts, "Host 不允许", 403)
        origin = self.headers.get("Origin")
        require(origin is None or origin == "http://" + host, "不允许跨源请求", 403)
        require(self.headers.get("Sec-Fetch-Site") != "cross-site", "不允许跨站请求", 403)
        path = urlsplit(self.path).path
        store = self.server.store
        if path.startswith("/api/") and path != "/api/pair" and not path.startswith("/api/device/"):
            store.admin(self.token())
        if path.startswith("/api/device/"):
            with store.lock:
                store.device(self.token())
            require(self.headers.get("X-SMS-Protocol") == "5", "请升级安卓工具至 v1.4.0 或更新版本以管理手机短信", 426)
        parts = path.split("/")
        if method == "GET" and path == "/api/device/sms/requests":
            return self.send(200, store.pending_management(self.token()))
        if method == "GET" and len(parts) == 5 and parts[1:4] == ["api", "sms", "requests"]:
            return self.send(200, store.get_management(parts[4]))
        if method == "GET" and len(parts) == 7 and parts[1:4] == ["api", "device", "jobs"] and parts[5] == "batches":
            require(parts[6].isdigit() and len(parts[6]) <= 6, "分批索引无效")
            return self.send(200, store.device_batch(self.token(), parts[4], int(parts[6])))
        if method == "GET" and path == "/api/state":
            return self.send(200, store.state())
        if method == "GET" and path == APK_DOWNLOAD_PATH:
            apk = self.server.apk_dir / APK_FILENAME
            require(apk.is_file(), f"安装包不存在，请将 {APK_FILENAME} 放入电脑端 dist 目录", 404)
            return self.send(200, apk.read_bytes(), "application/vnd.android.package-archive", APK_FILENAME)
        if method == "GET" and path in {"/api/apk", "/api/apk/qr"}:
            require((self.server.apk_dir / APK_FILENAME).is_file(),
                    f"安装包不存在，请将 {APK_FILENAME} 放入电脑端 dist 目录", 404)
            download_url = store.server_url.rstrip("/") + APK_DOWNLOAD_PATH
            if path.endswith("/qr"):
                import qrcode
                import qrcode.image.svg
                output = io.BytesIO()
                qrcode.make(download_url, image_factory=qrcode.image.svg.SvgPathImage).save(output)
                return self.send(200, output.getvalue(), "image/svg+xml")
            return self.send(200, {"version": APK_VERSION, "url": download_url})
        if method == "GET" and path in {"/api/pairing", "/api/pairing/qr"}:
            pairing = store.pairing()
            if path.endswith("/qr"):
                import qrcode
                import qrcode.image.svg
                output = io.BytesIO()
                qrcode.make(pairing["url"], image_factory=qrcode.image.svg.SvgPathImage).save(output)
                return self.send(200, output.getvalue(), "image/svg+xml")
            return self.send(200, pairing)
        if method == "GET" and path == "/api/device/jobs":
            return self.send(200, store.pending(self.token()))
        if method == "POST":
            data = self.body()
            if path == "/api/pairing/rotate":
                return self.send(200, store.pairing(True))
            if path == "/api/pair":
                return self.send(200, store.pair(data))
            if path == "/api/sms/requests":
                return self.send(200, store.create_management(data))
            if len(parts) == 7 and parts[1:5] == ["api", "device", "sms", "requests"] and parts[6] == "status":
                return self.send(200, store.report_management(self.token(), parts[5], data))
            if path == "/api/uploads":
                return self.send(200, store.create_upload(data))
            if len(parts) == 6 and parts[1:3] == ["api", "uploads"] and parts[4] == "batches":
                require(parts[5].isdigit() and len(parts[5]) <= 6, "分批索引无效")
                return self.send(200, store.upload_batch(parts[3], int(parts[5]), data))
            if len(parts) == 5 and parts[1:3] == ["api", "uploads"] and parts[4] == "cancel":
                return self.send(200, store.cancel_upload(parts[3]))
            if len(parts) == 5 and parts[1:3] == ["api", "uploads"] and parts[4] == "commit":
                return self.send(200, store.commit_upload(parts[3]))
            if len(parts) == 6 and parts[1:4] == ["api", "device", "jobs"] and parts[5] == "status":
                return self.send(200, store.update(self.token(), parts[4], data))
        if method == "GET" and path in STATIC:
            filename, mime = STATIC[path]
            file = self.server.static_dir / filename
            require(file.is_file(), "页面资源不存在", 404)
            return self.send(200, file.read_bytes(), mime)
        raise ApiError(404, "路径不存在")

    def dispatch(self, method):
        try:
            self.handle_api(method)
        except (ApiError, ManagementError) as exc:
            self.close_connection = True
            self.send(exc.status, {"error": str(exc)})
        except (TimeoutError, socket.timeout):
            self.close_connection = True
            self.send(408, {"error": "请求超时"})
        except (BrokenPipeError, ConnectionResetError):
            pass
        except Exception:
            self.close_connection = True
            self.send(500, {"error": "服务内部错误，请检查运行环境"})

    def do_GET(self):
        self.dispatch("GET")

    def do_POST(self):
        self.dispatch("POST")

    def do_OPTIONS(self):
        self.send(405, {"error": "不支持此方法"})


def detect_lan_ip():
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as probe:
            probe.connect(("224.0.0.1", 9))  # 仅查询本机路由，不发送数据或连接公网。
            address = probe.getsockname()[0]
            if not ipaddress.ip_address(address).is_loopback:
                return address
    except OSError:
        pass
    try:
        for item in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET):
            address = item[4][0]
            if not ipaddress.ip_address(address).is_loopback:
                return address
    except OSError:
        pass
    return "127.0.0.1"


def main():
    parser = argparse.ArgumentParser(description="局域网短信测试管理服务")
    parser.add_argument("--host", default="0.0.0.0")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--advertise-host", help="二维码中的电脑局域网 IPv4")
    args = parser.parse_args()
    require(1 <= args.port <= 65535, "端口无效")
    address = args.advertise_host or detect_lan_ip()
    try:
        parsed_ip = ipaddress.IPv4Address(address)
        require(not parsed_ip.is_unspecified and not parsed_ip.is_multicast, "请指定有效局域网 IPv4")
    except ipaddress.AddressValueError:
        parser.error("--advertise-host 必须为 IPv4 地址")
    data_dir = Path(__file__).resolve().parent.parent / ".data"
    data_dir.mkdir(mode=0o700, exist_ok=True)
    store = Store(data_dir / "sms-test.db", f"http://{address}:{args.port}")
    server = Server((args.host, args.port), store)
    admin_host = "127.0.0.1" if args.host == "0.0.0.0" else args.host
    print(f"电脑管理页面：http://{admin_host}:{args.port}/#token={store.admin_token}", flush=True)
    print(f"手机连接地址：{store.server_url}（仅在可信测试局域网使用）", flush=True)
    if address == "127.0.0.1":
        print("未检测到局域网地址，请使用 --advertise-host 指定电脑 IPv4。", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()
        store.db.close()


if __name__ == "__main__":
    main()
