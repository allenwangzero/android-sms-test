import http.client
import json
import tempfile
import threading
import unittest
import uuid
from unittest.mock import patch
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from desktop.server import ApiError, BATCH_SIZE, MAX_BODY, Server, Store


class ServerTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.db_path = Path(self.temp.name) / "test.db"
        self.store = Store(self.db_path, "http://192.168.1.2:8765")
        self.server = Server(("127.0.0.1", 0), self.store)
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()
        self.admin = self.store.admin_token

    def tearDown(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join()
        self.store.db.close()
        self.temp.cleanup()

    def call(self, path, data=None, token=None, headers=None):
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=3)
        request_headers = {"Content-Type": "application/json", "X-SMS-Protocol": "4"}
        if token:
            request_headers["Authorization"] = "Bearer " + token
        request_headers.update(headers or {})
        connection.request("POST" if data is not None else "GET", path,
                           json.dumps(data).encode() if data is not None else None, request_headers)
        response = connection.getresponse()
        raw = response.read()
        result = json.loads(raw) if "application/json" in response.getheader("Content-Type", "") else raw
        status = response.status
        connection.close()
        return status, result

    def pairing(self):
        status, pair = self.call("/api/pairing", token=self.admin)
        self.assertEqual(status, 200)
        token = parse_qs(urlsplit(pair["url"]).query)["token"][0]
        return token

    def device(self, client_id=None):
        body = {"token": self.pairing(), "name": "测试设备", "clientId": client_id or str(uuid.uuid4())}
        status, device = self.call("/api/pair", body)
        self.assertEqual(status, 200)
        return device

    def start_upload(self, devices, count, request_id=None):
        body = {"requestId": request_id or str(uuid.uuid4()),
                "deviceIds": [d["deviceId"] for d in devices], "count": count}
        status, result = self.call("/api/uploads", body, self.admin)
        self.assertEqual(status, 200)
        return body, result

    def put_batch(self, upload, index, messages):
        return self.call(f"/api/uploads/{upload}/batches/{index}", {"messages": messages}, self.admin)

    def commit(self, upload):
        return self.call(f"/api/uploads/{upload}/commit", {}, self.admin)

    def jobs(self, devices):
        body, _ = self.start_upload(devices, 1)
        self.assertEqual(self.put_batch(body["requestId"], 0, [self.message()])[0], 200)
        status, result = self.commit(body["requestId"])
        self.assertEqual(status, 200)
        return body, result["jobs"]

    @staticmethod
    def message(index=123):
        return {"sender": "TEST", "body": "含空格的测试短信 📨", "timestamp": index}

    def get_batch(self, device, job, index):
        return self.call(f"/api/device/jobs/{job['id']}/batches/{index}", token=device["deviceToken"])

    def report(self, device, job, status, written=0, error=""):
        return self.call(f"/api/device/jobs/{job['id']}/status",
                         {"status": status, "written": written, "error": error}, device["deviceToken"])

    def test_management_http_auth_and_protocol(self):
        first, second = self.device(), self.device()
        request = {"requestId": str(uuid.uuid4()), "deviceId": first["deviceId"], "action": "list"}
        path = "/api/sms/requests"
        self.assertEqual(self.call(path, request)[0], 401)
        self.assertEqual(self.call(path, request, first["deviceToken"])[0], 401)
        status, queued = self.call(path, request, self.admin)
        self.assertEqual(status, 200)
        self.assertEqual(queued["status"], "queued")
        self.assertEqual(self.call(path + "/" + request["requestId"], token=self.admin)[1], queued)
        device_path = "/api/device/sms/requests"
        self.assertEqual(self.call(device_path)[0], 401)
        for protocol in ("1", "2", "3"):
            self.assertEqual(self.call(device_path, token=first["deviceToken"], headers={"X-SMS-Protocol": protocol})[0], 426)
        self.assertEqual(self.call(device_path, token=second["deviceToken"])[1], {"request": None})
        self.assertEqual(self.call(device_path, token=first["deviceToken"])[1]["request"]["id"], request["requestId"])
        report = {"status": "completed", "count": 0, "processed": 0, "deleted": 0,
                  "error": "", "result": {"total": 0, "page": 0, "pageSize": 50, "rows": []}}
        status_path = device_path + "/" + request["requestId"] + "/status"
        self.assertEqual(self.call(status_path, report, second["deviceToken"])[0], 404)
        self.assertEqual(self.call(status_path, report, self.admin)[0], 401)
        self.assertEqual(self.call(status_path, report, first["deviceToken"])[0], 200)
        self.assertEqual(self.call(status_path, report, first["deviceToken"])[0], 200)
        self.assertEqual(self.call(device_path, token=first["deviceToken"])[1], {"request": None})
        self.assertNotIn("sms_requests", self.call("/api/state", token=self.admin)[1])

    def test_pair_qr_retry_rotation_expiration(self):
        token = self.pairing()
        self.assertNotEqual(token, self.admin)
        self.assertEqual(self.call("/api/pairing/qr", token=self.admin)[0], 200)
        self.assertIn(b"<svg", self.call("/api/pairing/qr", token=self.admin)[1])
        client_id = str(uuid.uuid4())
        device = self.device(client_id)
        self.assertEqual(device, self.device(client_id))
        self.call("/api/pairing/rotate", {}, self.admin)
        self.assertNotEqual(token, self.pairing())
        self.assertEqual(self.call("/api/pair", {"token": token, "name": "old", "clientId": client_id})[0], 401)
        token = self.pairing()
        with self.store.lock, self.store.db:
            self.store.set_setting("pair_expires", 1)
        self.assertEqual(self.call("/api/pair", {"token": token, "name": "old", "clientId": client_id})[0], 401)
        self.assertNotEqual(token, self.pairing())

    def test_multi_device_snapshot_idempotency_and_persistence(self):
        devices = [self.device(), self.device()]
        request, jobs = self.jobs(devices)
        self.assertEqual(len(jobs), 2)
        self.assertNotIn("messages", jobs[0])
        status, retry = self.commit(request["requestId"])
        self.assertEqual(status, 200)
        self.assertEqual(retry["jobs"], jobs)
        modified = self.message()
        modified["body"] = "已修改"
        self.assertEqual(self.put_batch(request["requestId"], 0, [modified])[0], 409)
        for device in devices:
            job = next(job for job in jobs if job["deviceId"] == device["deviceId"])
            pending = self.call("/api/device/jobs", token=device["deviceToken"])[1]["job"]
            self.assertEqual(pending["id"], job["id"])
            self.assertEqual(pending["preview"][0]["body"], "含空格的测试短信 📨")
        reopened = Store(self.db_path, self.store.server_url)
        try:
            self.assertEqual(reopened.admin_token, self.admin)
            self.assertEqual(len(reopened.state()["jobs"]), 2)
            self.assertEqual(reopened.pending(devices[0]["deviceToken"])["job"]["deviceId"], devices[0]["deviceId"])
            self.assertEqual(reopened.commit_upload(request["requestId"])["jobs"], jobs)
            self.assertEqual(reopened.create_upload(request)["jobs"], jobs)
            rows = reopened.db.execute("SELECT messages, upload_id FROM jobs").fetchall()
            self.assertTrue(all(row["messages"] == "[]" and row["upload_id"] == request["requestId"] for row in rows))
        finally:
            reopened.db.close()

    def test_auth_isolation_origin_host_and_paths(self):
        first, second = self.device(), self.device()
        _, jobs = self.jobs([first])
        self.assertEqual(self.call("/api/state")[0], 401)
        self.assertEqual(self.call("/api/state", token=first["deviceToken"])[0], 401)
        self.assertEqual(self.call("/api/state", token="é")[0], 401)
        self.assertEqual(self.call("/api/pair", {"token": "非法", "name": "测试", "clientId": str(uuid.uuid4())})[0], 401)
        self.assertEqual(self.call("/api/device/jobs", token=self.admin)[0], 401)
        self.assertEqual(self.report(second, jobs[0], "received")[0], 404)
        self.assertEqual(self.call("/api/state", token=self.admin, headers={"Origin": "http://evil.test"})[0], 403)
        self.assertEqual(self.call("/api/state", token=self.admin, headers={"Host": "evil.test"})[0], 403)
        self.assertEqual(self.call("/../server.py")[0], 404)
        state = self.call("/api/state", token=self.admin)[1]
        self.assertNotIn(first["deviceToken"], json.dumps(state))

    def test_progress_terminal_and_order(self):
        device = self.device()
        _, jobs = self.jobs([device])
        job = jobs[0]
        self.assertEqual(self.report(device, job, "completed", 1)[0], 409)
        self.assertEqual(self.report(device, job, "received", 1)[0], 409)
        self.assertEqual(self.report(device, job, "received")[0], 200)
        self.assertEqual(self.report(device, job, "received")[0], 200)
        self.assertEqual(self.report(device, job, "failed", 1)[0], 409)
        self.assertEqual(self.report(device, job, "writing")[0], 200)
        self.assertEqual(self.report(device, job, "received")[0], 409)
        self.assertEqual(self.report(device, job, "completed")[0], 409)
        self.assertEqual(self.report(device, job, "writing", 2)[0], 409)
        self.assertEqual(self.report(device, job, "writing", True)[0], 400)
        self.assertEqual(self.report(device, job, "writing", 1)[0], 200)
        self.assertEqual(self.report(device, job, "writing", 0)[0], 409)
        self.assertEqual(self.report(device, job, "completed", 1)[0], 200)
        self.assertEqual(self.report(device, job, "completed", 1)[0], 200)
        self.assertEqual(self.report(device, job, "failed", 1)[0], 409)
        self.assertIsNone(self.call("/api/device/jobs", token=device["deviceToken"])[1]["job"])

    def test_interrupted_and_failed(self):
        device = self.device()
        _, jobs = self.jobs([device])
        self.report(device, jobs[0], "received")
        self.report(device, jobs[0], "writing")
        self.assertEqual(self.report(device, jobs[0], "interrupted", 0, "重启")[0], 200)
        self.assertEqual(self.report(device, jobs[0], "writing")[0], 409)
        _, next_jobs = self.jobs([device])
        self.report(device, next_jobs[0], "received")
        self.assertEqual(self.report(device, next_jobs[0], "failed", 0, "权限不足")[0], 200)
        _, interrupted_jobs = self.jobs([device])
        self.report(device, interrupted_jobs[0], "received")
        self.assertEqual(self.report(device, interrupted_jobs[0], "interrupted", 1)[0], 409)
        self.assertEqual(self.report(device, interrupted_jobs[0], "interrupted", 0, "写入前重启")[0], 200)

    def test_validation(self):
        device = self.device()
        request, _ = self.start_upload([device], 1)
        for value in [True, -1, 4102444800001, "123"]:
            message = self.message(value)
            self.assertEqual(self.put_batch(request["requestId"], 0, [message])[0], 400)
        message = self.message()
        message["sender"] = " "
        self.assertEqual(self.put_batch(request["requestId"], 0, [message])[0], 400)
        message["sender"] = "\ud800"
        self.assertEqual(self.put_batch(request["requestId"], 0, [message])[0], 400)
        for count in [0, 100001, True, "1"]:
            invalid = dict(request, requestId=str(uuid.uuid4()), count=count)
            self.assertEqual(self.call("/api/uploads", invalid, self.admin)[0], 400)
        self.assertEqual(self.call("/api/uploads", {}, self.admin,
                                   {"Content-Length": str(MAX_BODY + 1)})[0], 413)

    def test_large_ordered_batches_and_protocol(self):
        device, other = self.device(), self.device()
        count = 10001
        request, created = self.start_upload([device], count)
        upload_id = request["requestId"]
        self.assertEqual(created["batchCount"], 21)
        self.assertEqual(created["jobs"], [])
        self.assertEqual(self.commit(upload_id)[0], 409)
        for index in range(21):
            messages = [self.message(i) for i in range(index * BATCH_SIZE, min((index + 1) * BATCH_SIZE, count))]
            self.assertEqual(self.put_batch(upload_id, index, messages)[0], 200)
        self.assertIsNone(self.call("/api/device/jobs", token=device["deviceToken"])[1]["job"])
        status, result = self.commit(upload_id)
        self.assertEqual(status, 200)
        job = result["jobs"][0]
        pending = self.call("/api/device/jobs", token=device["deviceToken"])[1]["job"]
        self.assertNotIn("messages", pending)
        self.assertEqual(len(pending["preview"]), 20)
        self.assertEqual(self.get_batch(device, job, 0)[0], 409)
        self.assertEqual(self.get_batch(other, job, 0)[0], 404)
        self.assertEqual(self.call("/api/device/jobs", token=device["deviceToken"],
                                   headers={"X-SMS-Protocol": "1"})[0], 426)
        self.assertEqual(self.report(device, job, "received")[0], 200)
        self.assertEqual(self.report(device, job, "writing")[0], 200)
        collected = []
        for index in range(21):
            if index < 20:
                self.assertEqual(self.get_batch(device, job, index + 1)[0], 409)
            status, batch = self.get_batch(device, job, index)
            self.assertEqual(status, 200)
            self.assertEqual(batch["offset"], len(collected))
            self.assertEqual(batch["total"], count)
            collected.extend(message["timestamp"] for message in batch["messages"])
            self.assertEqual(self.report(device, job, "writing", len(collected))[0], 200)
        self.assertEqual(collected, list(range(count)))
        self.assertEqual(self.report(device, job, "completed", count)[0], 200)
        self.assertEqual(self.get_batch(device, job, 20)[0], 409)

    def test_metadata_snapshot_preview_batches_and_restart(self):
        device = self.device()
        request, _ = self.start_upload([device], 501)
        upload = request["requestId"]
        metadata = {"type": 1, "protocol": 255, "subject": "主题 📨", "service_center": "+639170000130",
                    "read": 0, "status": 32, "locked": 1, "toa": None, "sc_toa": None}
        messages = [dict(self.message(i), **metadata) for i in range(501)]
        messages[1].update(protocol=None, subject=None, service_center=None)
        messages[2].update(subject="", service_center="")
        messages[3] = self.message(3)
        original = json.dumps(messages, ensure_ascii=False)
        self.assertEqual(self.put_batch(upload, 0, messages[:500])[0], 200)
        self.assertEqual(self.put_batch(upload, 1, messages[500:])[0], 200)
        self.assertEqual(self.put_batch(upload, 0, messages[:500])[0], 200)
        changed = [dict(message) for message in messages[:500]]
        changed[0]["locked"] = 0
        self.assertEqual(self.put_batch(upload, 0, changed)[0], 409)
        self.assertEqual(json.dumps(messages, ensure_ascii=False), original)
        job = self.commit(upload)[1]["jobs"][0]
        self.assertEqual(self.call("/api/device/jobs", token=device["deviceToken"])[1]["job"]["preview"], messages[:20])
        self.assertEqual(self.report(device, job, "received")[0], 200)
        self.assertEqual(self.get_batch(device, job, 0)[1]["messages"], messages[:500])
        self.assertEqual(self.report(device, job, "writing", 500)[0], 200)
        self.assertEqual(self.get_batch(device, job, 1)[1]["messages"], messages[500:])
        reopened = Store(self.db_path, self.store.server_url)
        try:
            self.assertEqual(reopened.pending(device["deviceToken"])["job"]["preview"], messages[:20])
            self.assertEqual(reopened.device_batch(device["deviceToken"], job["id"], 1)["messages"], messages[500:])
            restored = reopened.db.execute("SELECT messages FROM upload_batches WHERE upload_id=? ORDER BY batch_index", (upload,)).fetchall()
            self.assertEqual([message for row in restored for message in json.loads(row["messages"])], messages)
        finally:
            reopened.db.close()

    def test_metadata_validation_and_old_protocol_rejected(self):
        device = self.device()
        request, _ = self.start_upload([device], 1)
        invalid = {"type": [True, 0, 2, 3, 4, 5, 6, 7, None, "1", 1.0],
                   "protocol": [True, -1, 256, "0", 0.0],
                   "read": [True, -1, 2, None, "0", 0.0],
                   "status": [True, -2, 256, None, "32", 32.0],
                   "locked": [False, -1, 2, None, "1", 1.0],
                   "subject": [False, 1, "x" * 4001, "\ud800"],
                   "service_center": [False, 1, "x" * 101],
                   "toa": [0, "null", "145", False], "sc_toa": [0, "null", "145", False],
                   "unknown": [None]}
        for field, values in invalid.items():
            for value in values:
                with self.subTest(field=field, value=repr(value)[:30]):
                    status, result = self.put_batch(request["requestId"], 0, [dict(self.message(), **{field: value})])
                    self.assertEqual(status, 400)
                    if field in ("toa", "sc_toa"):
                        self.assertIn("Android 标准短信数据库", result["error"])
        missing = self.message()
        del missing["body"]
        self.assertEqual(self.put_batch(request["requestId"], 0, [missing])[0], 400)
        for sms_type in (1,):
            for sms_status in (-1, 0, 32, 64, 255):
                snapshot, _ = Store.validate_messages([dict(self.message(), type=sms_type, status=sms_status)])
                self.assertEqual(json.loads(snapshot)[0]["status"], sms_status)
        for protocol in ("1", "2", ""):
            status, result = self.call("/api/device/jobs", token=device["deviceToken"], headers={"X-SMS-Protocol": protocol})
            self.assertEqual(status, 426)
            self.assertIn("v1.3.0", result["error"])

    def test_partial_upload_restart_conflicts_and_size_limit(self):
        device = self.device()
        request, _ = self.start_upload([device], 501)
        upload = request["requestId"]
        messages = [self.message(i) for i in range(500)]
        self.assertEqual(self.put_batch(upload, 0, messages)[0], 200)
        self.assertEqual(self.put_batch(upload, 0, messages)[0], 200)
        self.assertEqual(self.put_batch(upload, 1, messages)[0], 400)
        for index in [-1, 2, "abc"]:
            self.assertEqual(self.put_batch(upload, index, [self.message()])[0], 400)
        self.assertEqual(self.call("/api/uploads", dict(request, count=500), self.admin)[0], 409)
        self.assertEqual(self.put_batch(str(uuid.uuid4()), 0, [self.message()])[0], 404)
        reopened = Store(self.db_path, self.store.server_url)
        try:
            resumed = reopened.create_upload(request)
            self.assertEqual(resumed["receivedBatches"], [0])
            self.assertIsNone(reopened.pending(device["deviceToken"])["job"])
            reopened.upload_batch(upload, 1, {"messages": [self.message(500)]})
            jobs = reopened.commit_upload(upload)["jobs"]
            self.assertEqual(reopened.commit_upload(upload)["jobs"], jobs)
            bytes_stored = reopened.db.execute("SELECT byte_count FROM uploads WHERE id=?", (upload,)).fetchone()[0] + 1
            _, exact_size = Store.validate_messages(messages + [self.message(500)])
            self.assertEqual(bytes_stored, exact_size)
        finally:
            reopened.db.close()
        request, _ = self.start_upload([device], 501)
        _, first_size = Store.validate_messages(messages)
        with patch("desktop.server.MAX_SNAPSHOT", first_size):
            self.assertEqual(self.put_batch(request["requestId"], 0, messages)[0], 200)
            self.assertEqual(self.put_batch(request["requestId"], 1, [self.message()])[0], 413)
            self.assertEqual(self.commit(request["requestId"])[0], 409)

    def test_cancel_upload_and_commit_race(self):
        device = self.device()
        for _ in range(5):
            request, _ = self.start_upload([device], 1)
            upload = request["requestId"]
            self.assertEqual(self.put_batch(upload, 0, [self.message()])[0], 200)
            gate = threading.Barrier(2)
            results = {}

            def run(name, operation):
                gate.wait()
                try:
                    operation(upload)
                    results[name] = 200
                except ApiError as exc:
                    results[name] = exc.status

            threads = [threading.Thread(target=run, args=("cancel", self.store.cancel_upload)),
                       threading.Thread(target=run, args=("commit", self.store.commit_upload))]
            for thread in threads:
                thread.start()
            for thread in threads:
                thread.join()
            self.assertIn(results, [{"cancel": 200, "commit": 410}, {"cancel": 409, "commit": 200}])
            if results["cancel"] == 200:
                self.assertEqual(self.store.db.execute("SELECT COUNT(*) FROM upload_batches WHERE upload_id=?", (upload,)).fetchone()[0], 0)
                self.assertEqual(self.call(f"/api/uploads/{upload}/cancel", {}, self.admin)[0], 200)
            else:
                self.assertEqual(self.call(f"/api/uploads/{upload}/cancel", {}, self.admin)[0], 409)
                self.assertEqual(len(self.store.commit_upload(upload)["jobs"]), 1)
        self.assertEqual(self.call(f"/api/uploads/{uuid.uuid4()}/cancel", {}, device["deviceToken"])[0], 401)

    def test_cancel_tombstone_blocks_delayed_start_and_survives_restart(self):
        device = self.device()
        for already_started in [False, True]:
            request = {"requestId": str(uuid.uuid4()), "deviceIds": [device["deviceId"]], "count": 1}
            upload = request["requestId"]
            if already_started:
                self.assertEqual(self.call("/api/uploads", request, self.admin)[0], 200)
                self.assertEqual(self.put_batch(upload, 0, [self.message()])[0], 200)
            self.assertEqual(self.call(f"/api/uploads/{upload}/cancel", {}, self.admin)[0], 200)
            self.assertEqual(self.call(f"/api/uploads/{upload}/cancel", {}, self.admin)[0], 200)
            self.assertEqual(self.call("/api/uploads", request, self.admin)[0], 410)
            self.assertEqual(self.put_batch(upload, 0, [self.message()])[0], 410)
            self.assertEqual(self.commit(upload)[0], 410)
            reopened = Store(self.db_path, self.store.server_url)
            try:
                for operation in [lambda: reopened.create_upload(request),
                                  lambda: reopened.upload_batch(upload, 0, {"messages": [self.message()]}),
                                  lambda: reopened.commit_upload(upload)]:
                    with self.assertRaises(ApiError) as caught:
                        operation()
                    self.assertEqual(caught.exception.status, 410)
                self.assertEqual(reopened.state()["jobs"], [])
                self.assertEqual(reopened.cancel_upload(upload), {"ok": True})
            finally:
                reopened.db.close()
        self.assertEqual(self.call("/api/uploads/not-a-uuid/cancel", {}, self.admin)[0], 400)

    def test_existing_database_jobs_are_preserved(self):
        device = self.device()
        job_id = str(uuid.uuid4())
        with self.store.lock, self.store.db:
            self.store.db.execute("INSERT INTO jobs (id,device_id,status,count,written,error,created_at,updated_at,messages) "
                                  "VALUES (?,?,'received',501,0,'',1,1,?)",
                                  (job_id, device["deviceId"], json.dumps([self.message(i) for i in range(501)])))
        job = {"id": job_id}
        self.assertEqual(len(self.get_batch(device, job, 0)[1]["messages"]), 500)
        self.assertEqual(self.report(device, job, "writing", 500)[0], 200)
        self.assertEqual(self.get_batch(device, job, 1)[1]["messages"][0]["timestamp"], 500)


if __name__ == "__main__":
    unittest.main()
