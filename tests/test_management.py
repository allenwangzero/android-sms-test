import json
import sqlite3
import tempfile
import unittest
import uuid
from pathlib import Path
from urllib.parse import parse_qs, urlsplit

from desktop.server import Store
from desktop.sms_management import ManagementError, validate_request


class ManagementTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'state.db'
        self.store = Store(self.path, 'http://192.168.1.2:8765')
        token = parse_qs(urlsplit(self.store.pairing()['url']).query)['token'][0]
        self.devices = [self.store.pair({'token': token, 'clientId': str(uuid.uuid4()), 'name': name})
                        for name in ('甲', '乙')]

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    def request(self, action='list', **values):
        return dict(requestId=str(uuid.uuid4()), deviceId=self.devices[0]['deviceId'], action=action, **values)

    def report(self, request, status, count=0, processed=0, deleted=0, result=None, error=''):
        data = dict(status=status, count=count, processed=processed, deleted=deleted, result=result, error=error)
        return self.store.report_management(self.devices[0]['deviceToken'], request['requestId'], data)

    def assert_error(self, code, function, *args):
        with self.assertRaises(ManagementError) as caught:
            function(*args)
        self.assertEqual(caught.exception.status, code)

    @staticmethod
    def row(index=1):
        return dict(id=str(index), fingerprint='a' * 64, sender='TEST', body='测试内容',
                    timestamp=123, type=1, read=0, status=-1, locked=0)

    def test_default_filters_canonical_idempotency_and_active_conflict(self):
        request = self.request()
        original = self.store.create_management(request)
        explicit = dict(request, page=0, filters={'locked': 0})
        self.assertEqual(original, self.store.create_management(explicit))
        self.assert_error(409, self.store.create_management, dict(request, filters={'locked': None}))
        self.assert_error(409, self.store.create_management, self.request())
        pending = self.store.pending_management(self.devices[0]['deviceToken'])['request']
        self.assertEqual(pending['filters']['locked'], 0)
        self.assertIsNone(self.store.pending_management(self.devices[1]['deviceToken'])['request'])
        self.assertNotIn('sms_requests', self.store.state())
        self.assertNotIn('result', json.dumps(self.store.state()))

    def test_read_result_and_terminal_replay_are_persistent(self):
        request = self.request(page=1)
        self.store.create_management(request)
        result = dict(total=51, page=1, pageSize=50, rows=[self.row()])
        self.assert_error(400, self.report, request, 'completed', 51, 1, 0, result)
        self.assert_error(400, self.report, request, 'completed', 51, 0, 0, dict(result, page=0))
        self.store.db.execute("UPDATE devices SET last_seen=0")
        self.store.db.commit()
        done = self.report(request, 'completed', 51, result=result)
        self.assertGreater(self.store.db.execute("SELECT last_seen FROM devices WHERE id=?", (self.devices[0]['deviceId'],)).fetchone()[0], 0)
        self.store.db.execute("UPDATE devices SET last_seen=0")
        self.store.db.commit()
        self.assertEqual(done, self.report(request, 'completed', 51, result=result))
        self.assertGreater(self.store.db.execute("SELECT last_seen FROM devices WHERE id=?", (self.devices[0]['deviceId'],)).fetchone()[0], 0)
        self.assertIsNone(self.store.pending_management(self.devices[0]['deviceToken'])['request'])
        self.assert_error(409, self.report, request, 'failed', 51)
        reopened = Store(self.path, self.store.server_url)
        try:
            self.assertEqual(reopened.get_management(request['requestId']), done)
            self.assertEqual(reopened.create_management(request), done)
        finally:
            reopened.db.close()
        self.store.create_management(self.request())

    def test_delete_confirmation_progress_failure_and_result_immutability(self):
        request = self.request('delete', selection={'mode': 'filtered'})
        self.store.create_management(request)
        preview = dict(count=3, preview=[self.row()], selectionMode='filtered')
        self.assert_error(409, self.report, request, 'running', 3)
        self.assert_error(400, self.report, request, 'ready', 3)
        self.report(request, 'ready', 3, result=preview)
        self.assert_error(400, self.report, request, 'running', 3, 1, 1)
        self.report(request, 'running', 3)
        self.report(request, 'running', 3, 2, 1)
        self.assert_error(409, self.report, request, 'running', 3, 1, 1)
        self.assert_error(409, self.report, request, 'running', 4, 2, 1)
        self.assert_error(400, self.report, request, 'running', 3, 2, 3)
        self.assert_error(409, self.report, request, 'running', 3, 2, 1, dict(preview, preview=[]))
        self.assert_error(400, self.report, request, 'completed', 3, 2, 1)
        failed = self.report(request, 'failed', 3, 2, 1, error='短信已改变')
        self.assertEqual(failed['result'], preview)
        self.assert_error(409, self.report, request, 'running', 3, 2, 1)

    def test_all_includes_locked_and_selected_canonical_sort(self):
        request = self.request('delete', filters={'sender': 'TEST', 'locked': 0}, selection={'mode': 'all'})
        canonical = validate_request(request)
        self.assertEqual(canonical['filters']['sender'], '')
        self.assertIsNone(canonical['filters']['locked'])
        request['selection'] = {'mode': 'selected', 'items': [{'id': '2', 'fingerprint': 'b' * 64},
                                                           {'id': '1', 'fingerprint': 'a' * 64}]}
        first = self.store.create_management(request)
        request['selection']['items'].reverse()
        self.assertEqual(first, self.store.create_management(request))
        request['selection']['items'].append(request['selection']['items'][0])
        self.assert_error(400, validate_request, request)

    def test_validation_limits_unknown_fields_and_types(self):
        examples = [{'extra': 1}, {'page': True}, {'page': 10000001}, {'filters': {'extra': 1}},
                    {'filters': {'sender': 'a' * 101}}, {'filters': {'keyword': 'a' * 4001}},
                    {'filters': {'dateFrom': 2, 'dateTo': 1}}, {'filters': {'read': True}},
                    {'filters': {'status': 256}}, {'filters': {'locked': 2}},
                    {'filters': {'dateTo': 4102444800001}}, {'selection': {'mode': 'all'}}]
        for changes in examples:
            with self.subTest(changes=changes):
                self.assert_error(400, validate_request, self.request(**changes))
        for selection in ({'mode': 'all', 'items': []}, {'mode': 'selected', 'items': []},
                          {'mode': 'selected', 'items': [{'id': '01', 'fingerprint': 'a' * 64}]},
                          {'mode': 'selected', 'items': [{'id': '1', 'fingerprint': 'A' * 64}]}):
            self.assert_error(400, validate_request, self.request('delete', selection=selection))
        validate_request(self.request(filters={'dateFrom': 0, 'dateTo': 4102444800000,
                                               'read': None, 'status': -1, 'locked': 1}))

    def test_preview_limits_and_device_isolation(self):
        request = self.request('delete', selection={'mode': 'all'})
        self.store.create_management(request)
        body = dict(status='ready', count=11, processed=0, deleted=0, error='',
                    result={'count': 11, 'preview': [self.row(i) for i in range(1, 12)], 'selectionMode': 'all'})
        self.assert_error(404, self.store.report_management, self.devices[1]['deviceToken'], request['requestId'], body)
        self.assert_error(400, self.store.report_management, self.devices[0]['deviceToken'], request['requestId'], body)
        body['result']['preview'] = [self.row()]
        body['result']['preview'][0]['body'] = '字' * 2001
        self.assert_error(400, self.store.report_management, self.devices[0]['deviceToken'], request['requestId'], body)
        self.report(request, 'interrupted', error='准备过程被中断')
        request = self.request('delete', selection={'mode': 'all'})
        self.store.create_management(request)
        self.report(request, 'cancelled')

    def test_success_and_empty_delete(self):
        for count in (0, 2):
            request = self.request('delete', selection={'mode': 'all'})
            self.store.create_management(request)
            self.report(request, 'ready', count, result={'count': count, 'preview': [], 'selectionMode': 'all'})
            self.report(request, 'running', count)
            self.assertEqual(self.report(request, 'completed', count, count, count)['status'], 'completed')

    def test_upgrade_old_database(self):
        old = Path(self.temp.name) / 'old.db'
        connection = sqlite3.connect(old)
        connection.executescript("CREATE TABLE jobs(id TEXT PRIMARY KEY,device_id TEXT,status TEXT,count INTEGER,written INTEGER,error TEXT,created_at INTEGER,updated_at INTEGER,messages TEXT);")
        connection.close()
        reopened = Store(old, self.store.server_url)
        try:
            self.assertEqual(reopened.db.execute('SELECT COUNT(*) FROM sms_requests').fetchone()[0], 0)
            self.assertIn('upload_id', {row['name'] for row in reopened.db.execute('PRAGMA table_info(jobs)')})
        finally:
            reopened.db.close()


if __name__ == '__main__':
    unittest.main()
