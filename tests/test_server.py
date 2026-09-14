import http.client
import json
import tempfile
import threading
import unittest
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from desktop.server import ApiError, MAX_BODY, Server, Store


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
        request_headers = {"Content-Type": "application/json"}
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

    def jobs(self, devices):
        body = {"requestId": str(uuid.uuid4()), "deviceIds": [d["deviceId"] for d in devices],
                "messages": [{"sender": "TEST", "body": "含空格的测试短信 📨", "timestamp": 123}]}
        status, result = self.call("/api/jobs", body, self.admin)
        self.assertEqual(status, 200)
        return body, result["jobs"]

    def report(self, device, job, status, written=0, error=""):
        return self.call(f"/api/device/jobs/{job['id']}/status",
                         {"status": status, "written": written, "error": error}, device["deviceToken"])

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
        status, retry = self.call("/api/jobs", request, self.admin)
        self.assertEqual(status, 200)
        self.assertEqual(retry["jobs"], jobs)
        request["messages"][0]["body"] = "已修改"
        self.assertEqual(self.call("/api/jobs", request, self.admin)[0], 409)
        for device, job in zip(devices, jobs):
            pending = self.call("/api/device/jobs", token=device["deviceToken"])[1]["job"]
            self.assertEqual(pending["id"], job["id"])
            self.assertEqual(pending["messages"][0]["body"], "含空格的测试短信 📨")
        reopened = Store(self.db_path, self.store.server_url)
        try:
            self.assertEqual(reopened.admin_token, self.admin)
            self.assertEqual(len(reopened.state()["jobs"]), 2)
            self.assertEqual(reopened.pending(devices[0]["deviceToken"])["job"]["id"], jobs[0]["id"])
            request["messages"][0]["body"] = "含空格的测试短信 📨"
            self.assertEqual(reopened.create_jobs(request)["jobs"], jobs)
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
        request, _ = self.jobs([device])
        request["requestId"] = str(uuid.uuid4())
        for value in [True, -1, 4102444800001, "123"]:
            request["messages"][0]["timestamp"] = value
            self.assertEqual(self.call("/api/jobs", request, self.admin)[0], 400)
        request["messages"][0]["timestamp"] = 123
        request["messages"][0]["sender"] = " "
        self.assertEqual(self.call("/api/jobs", request, self.admin)[0], 400)
        self.assertEqual(self.call("/api/jobs", {}, self.admin,
                                   {"Content-Length": str(MAX_BODY + 1)})[0], 413)

    def test_large_batch_response_and_snapshot_limit(self):
        device = self.device()
        data = {"requestId": str(uuid.uuid4()), "deviceIds": [device["deviceId"]],
                "messages": [{"sender": "TEST", "body": "a" * 3945, "timestamp": 123}] * 4200}
        self.store.create_jobs(data)
        connection = http.client.HTTPConnection("127.0.0.1", self.server.server_port, timeout=5)
        connection.request("GET", "/api/device/jobs", headers={"Authorization": "Bearer " + device["deviceToken"]})
        response = connection.getresponse()
        raw = response.read()
        self.assertEqual(response.status, 200)
        self.assertLessEqual(len(raw), MAX_BODY)
        self.assertEqual(len(json.loads(raw)["job"]["messages"]), 4200)
        connection.close()
        data["requestId"] = str(uuid.uuid4())
        data["messages"] = [{"sender": "TEST", "body": "a" * 4000, "timestamp": 123}] * 4200
        with self.assertRaises(ApiError) as caught:
            self.store.create_jobs(data)
        self.assertEqual(caught.exception.status, 413)


if __name__ == "__main__":
    unittest.main()
