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
            self.assertEqual(reopened.db.execute('SELECT COUNT(*) FROM sms_export_batches').fetchone()[0], 0)
            self.assertIn('upload_id', {row['name'] for row in reopened.db.execute('PRAGMA table_info(jobs)')})
        finally:
            reopened.db.close()

    @staticmethod
    def batch(**values):
        return dict(dict(jobId=str(uuid.uuid4()), createdAt=123, requested=100,
                         recorded=50, status='interrupted'), **values)

    def test_batches_pagination_read_only_and_persistent_replay(self):
        request = self.request('batches', page=1, filters={'sender': 'ignored', 'locked': 1})
        self.store.create_management(request)
        self.assertEqual(validate_request(request)['filters'], validate_request(self.request())['filters'])
        self.assertEqual(self.store.create_management(request),
                         self.store.create_management(dict(request, filters={})))
        self.assert_error(400, validate_request, dict(request, selection={'mode': 'all'}))
        result = dict(total=51, page=1, pageSize=50, batches=[self.batch()])
        self.assert_error(409, self.report, request, 'ready', 51, 0, 0, result)
        self.assert_error(400, self.report, request, 'completed', 51, 1, 0, result)
        self.assert_error(400, self.report, request, 'completed', 51)
        for invalid in (dict(result, page=0), dict(result, pageSize=True), dict(result, total=52),
                        dict(result, batches=[]), dict(result, rows=[])):
            self.assert_error(400, self.report, request, 'completed', 51, 0, 0, invalid)
        done = self.report(request, 'completed', 51, result=result)
        self.assertEqual(done, self.report(request, 'completed', 51, result=result))
        reopened = Store(self.path, self.store.server_url)
        try:
            self.assertEqual(reopened.create_management(request), done)
        finally:
            reopened.db.close()
        empty = self.request('batches', page=3)
        self.store.create_management(empty)
        self.report(empty, 'completed', result=dict(total=0, page=3, pageSize=50, batches=[]))
        failed = self.request('batches')
        self.store.create_management(failed)
        self.report(failed, 'failed', error='无法读取批次记录')

    def test_batches_validate_metadata_duplicates_and_page_length(self):
        request = self.request('batches')
        self.store.create_management(request)
        batch = self.batch()
        examples = [{'jobId': 'invalid'}, {'jobId': 'AAAAAAAA-AAAA-4AAA-AAAA-AAAAAAAAAAAA'}, {'createdAt': True},
                    {'createdAt': -1}, {'requested': 0}, {'requested': 100001}, {'requested': True},
                    {'recorded': -1}, {'recorded': 101}, {'recorded': True}, {'status': 'queued'},
                    {'status': []}, {'extra': 1}]
        for values in examples:
            with self.subTest(values=values):
                result = dict(total=1, page=0, pageSize=50, batches=[dict(batch, **values)])
                self.assert_error(400, self.report, request, 'completed', 1, 0, 0, result)
        duplicate = dict(total=2, page=0, pageSize=50, batches=[batch, batch])
        self.assert_error(400, self.report, request, 'completed', 2, 0, 0, duplicate)
        overflow = dict(total=51, page=0, pageSize=50, batches=[self.batch() for _ in range(51)])
        self.assert_error(400, self.report, request, 'completed', 51, 0, 0, overflow)
        for status in ('writing', 'completed', 'failed', 'interrupted'):
            result = dict(total=1, page=0, pageSize=50, batches=[self.batch(status=status)])
            current = request if status == 'writing' else self.request('batches')
            if current is not request:
                self.store.create_management(current)
            self.report(current, 'completed', 1, result=result)

    def test_batch_delete_normalizes_filters_and_validates_selection(self):
        job_id = str(uuid.uuid4())
        request = self.request('delete', page=4, filters={'sender': 'ignored', 'locked': 0},
                               selection={'mode': 'batch', 'jobId': job_id})
        normalized = validate_request(request)
        self.assertEqual(normalized['filters']['sender'], '')
        self.assertIsNone(normalized['filters']['locked'])
        self.assertEqual(normalized['page'], 0)
        self.assertEqual(normalized['selection'], request['selection'])
        first = self.store.create_management(request)
        self.assertEqual(first, self.store.create_management(dict(request, filters={}, page=0)))
        self.assert_error(409, self.store.create_management,
                          dict(request, selection={'mode': 'batch', 'jobId': str(uuid.uuid4())}))
        for selection in ({'mode': 'batch'}, {'mode': 'batch', 'jobId': 'invalid'},
                          {'mode': 'batch', 'jobId': job_id, 'items': []},
                          {'mode': 'all', 'jobId': job_id}, {'mode': 'filtered', 'jobId': job_id},
                          {'mode': 'selected', 'jobId': job_id, 'items': [{'id': '1', 'fingerprint': 'a' * 64}]}):
            self.assert_error(400, validate_request, dict(request, selection=selection))

    def test_batch_delete_preview_skip_counts_and_confirmation(self):
        request = self.request('delete', selection={'mode': 'batch', 'jobId': str(uuid.uuid4())})
        self.store.create_management(request)
        result = dict(count=1, preview=[self.row()], selectionMode='batch',
                      jobId=request['selection']['jobId'], missing=2, changed=3)
        missing_field = dict(result)
        del missing_field['missing']
        missing_job = dict(result)
        del missing_job['jobId']
        for invalid in (missing_field, missing_job, dict(result, jobId=str(uuid.uuid4())),
                        dict(result, jobId=None), dict(result, missing=True), dict(result, changed=-1),
                        dict(result, missing=100001), dict(result, missing=99999, changed=1),
                        dict(result, selectionMode='all')):
            self.assert_error(400, self.report, request, 'ready', 1, 0, 0, invalid)
        self.assert_error(409, self.report, request, 'running', 1)
        self.report(request, 'ready', 1, result=result)
        self.assert_error(400, self.report, request, 'running', 1, 1, 1)
        self.report(request, 'running', 1)
        self.assert_error(409, self.report, request, 'running', 1, 0, 0, dict(result, changed=4))
        done = self.report(request, 'completed', 1, 1, 1)
        self.assertEqual(done['result'], result)
        self.assertEqual(done, self.report(request, 'completed', 1, 1, 1))
        regular = self.request('delete', selection={'mode': 'all'})
        self.store.create_management(regular)
        self.assert_error(400, self.report, regular, 'ready', 1, 0, 0, dict(result, selectionMode='all'))
        self.assert_error(400, self.report, regular, 'ready', 1, 0, 0,
                          dict(count=1, preview=[], selectionMode='all', jobId=request['selection']['jobId']))


if __name__ == '__main__':
    unittest.main()
