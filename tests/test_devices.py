import sqlite3
import tempfile
import unittest
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from desktop.server import ApiError, Store


class DeviceRemovalTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'state.db'
        self.store = Store(self.path, 'http://192.168.1.2:8765')
        self.client_id = str(uuid.uuid4())
        self.first = self.pair(self.client_id)
        self.second = self.pair(str(uuid.uuid4()))

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    def pair(self, client_id):
        token = parse_qs(urlsplit(self.store.pairing()['url']).query)['token'][0]
        return self.store.pair({'token': token, 'clientId': client_id, 'name': '测试手机'})

    def upload(self, devices):
        data = {'requestId': str(uuid.uuid4()), 'deviceIds': [d['deviceId'] for d in devices], 'count': 1}
        self.store.create_upload(data)
        self.store.upload_batch(data['requestId'], 0, {'messages': [{'sender': 'TEST', 'body': '测试', 'timestamp': 123}]})
        return data

    def test_revoke_and_repair_does_not_restore_old_identity(self):
        old = self.first
        self.store.remove_device(old['deviceId'])
        with self.assertRaises(ApiError) as error:
            self.store.device(old['deviceToken'])
        self.assertEqual(error.exception.status, 401)
        repaired = self.pair(self.client_id)
        self.assertNotEqual(repaired['deviceId'], old['deviceId'])
        self.assertNotEqual(repaired['deviceToken'], old['deviceToken'])
        self.assertEqual(self.store.remove_device(old['deviceId']), {'ok': True})
        self.assertEqual(self.store.device(repaired['deviceToken']), repaired['deviceId'])

    def test_pending_multi_device_upload_cancelled_without_changing_other_uploads(self):
        pending = self.upload([self.first, self.second])
        unaffected = self.upload([self.second])
        self.store.remove_device(self.first['deviceId'])
        for operation in (lambda: self.store.commit_upload(pending['requestId']),
                          lambda: self.store.create_upload(pending),
                          lambda: self.store.upload_batch(pending['requestId'], 0, {'messages': []})):
            with self.assertRaises(ApiError) as error:
                operation()
            self.assertEqual(error.exception.status, 410)
        self.assertIsNone(self.store.db.execute('SELECT 1 FROM upload_batches WHERE upload_id=?', (pending['requestId'],)).fetchone())
        self.assertEqual(len(self.store.commit_upload(unaffected['requestId'])['jobs']), 1)
        reopened = Store(self.path, self.store.server_url)
        try:
            with self.assertRaises(ApiError) as error:
                reopened.commit_upload(pending['requestId'])
            self.assertEqual(error.exception.status, 410)
            self.assertFalse(any(d['id'] == self.first['deviceId'] for d in reopened.state()['devices']))
        finally:
            reopened.db.close()

    def test_committed_shared_upload_keeps_other_device_and_snapshots(self):
        upload = self.upload([self.first, self.second])
        jobs = self.store.commit_upload(upload['requestId'])['jobs']
        by_device = {job['deviceId']: job for job in jobs}
        self.store.remove_device(self.first['deviceId'])
        statuses = {job['deviceId']: job['status'] for job in self.store.state()['jobs']}
        self.assertEqual(statuses, {self.first['deviceId']: 'interrupted', self.second['deviceId']: 'queued'})
        self.assertEqual(len(self.store.commit_upload(upload['requestId'])['jobs']), 2)
        pending = self.store.pending(self.second['deviceToken'])['job']
        self.assertEqual(pending['id'], by_device[self.second['deviceId']]['id'])
        self.assertEqual(pending['preview'][0]['body'], '测试')

    def test_management_progress_and_completed_history_are_preserved(self):
        request = {'requestId': str(uuid.uuid4()), 'deviceId': self.first['deviceId'], 'action': 'delete', 'selection': {'mode': 'all'}}
        self.store.create_management(request)
        preview = {'count': 3, 'preview': [], 'selectionMode': 'all'}
        report = {'status': 'ready', 'count': 3, 'processed': 0, 'deleted': 0, 'error': '', 'result': preview}
        self.store.report_management(self.first['deviceToken'], request['requestId'], report)
        self.store.report_management(self.first['deviceToken'], request['requestId'], dict(report, status='running'))
        self.store.report_management(self.first['deviceToken'], request['requestId'], dict(report, status='running', processed=1, deleted=1))
        other = {'requestId': str(uuid.uuid4()), 'deviceId': self.second['deviceId'], 'action': 'list'}
        self.store.create_management(other)
        complete = self.store.commit_upload(self.upload([self.first])['requestId'])['jobs'][0]
        self.store.db.execute("UPDATE jobs SET status='completed',written=count WHERE id=?", (complete['id'],))
        self.store.db.commit()
        self.store.remove_device(self.first['deviceId'])
        saved_job = next(job for job in self.store.state()['jobs'] if job['id'] == complete['id'])
        self.assertEqual((saved_job['status'], saved_job['written']), ('completed', 1))
        result = self.store.get_management(request['requestId'])
        self.assertEqual((result['status'], result['count'], result['processed'], result['deleted']), ('interrupted', 3, 1, 1))
        self.assertEqual(result['result'], preview)
        self.assertEqual(self.store.get_management(other['requestId'])['status'], 'queued')

    def test_transaction_failure_rolls_back_pairing_tasks_and_cancellation(self):
        upload = self.upload([self.first])
        self.store.db.execute("CREATE TRIGGER reject_device_delete BEFORE DELETE ON devices BEGIN SELECT RAISE(ABORT,'test failure'); END")
        self.store.db.commit()
        with self.assertRaises(sqlite3.IntegrityError):
            self.store.remove_device(self.first['deviceId'])
        self.assertEqual(self.store.device(self.first['deviceToken']), self.first['deviceId'])
        self.assertIsNone(self.store.db.execute('SELECT 1 FROM cancelled_uploads WHERE id=?', (upload['requestId'],)).fetchone())
        self.assertEqual(len(self.store.commit_upload(upload['requestId'])['jobs']), 1)
