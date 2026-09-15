import json
import tempfile
import unittest
import uuid
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch
from urllib.parse import parse_qs, urlsplit

from desktop.server import ApiError, Store
from desktop.sms_management import ManagementError, validate_request
from desktop.sms_export import encode_document


class ExportTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.path = Path(self.temp.name) / 'state.db'
        self.store = Store(self.path, 'http://192.168.1.2:8765')
        pairing = parse_qs(urlsplit(self.store.pairing()['url']).query)['token'][0]
        self.devices = [self.store.pair(dict(token=pairing, clientId=str(uuid.uuid4()), name=name))
                        for name in ('甲', '乙')]
        self.token = self.devices[0]['deviceToken']
        self.request = dict(requestId=str(uuid.uuid4()), deviceId=self.devices[0]['deviceId'],
                            action='export', selection={'mode': 'all'})
        self.id = self.request['requestId']
        self.store.create_management(self.request)

    def tearDown(self):
        self.store.db.close()
        self.temp.cleanup()

    @staticmethod
    def message(index=0):
        return dict(sender='发送人' + str(index), body='完整内容' * 600 + '\n\t\r<&"', timestamp=123,
                    read=1, status=-1, locked=1, type=1, subject='null', service_center=None,
                    protocol=None, toa=None, sc_toa=None, seen=0, date_sent=100)

    def report(self, status, count, processed=0, result=None, deleted=0):
        return self.store.report_management(self.token, self.id,
                                           dict(status=status, count=count, processed=processed,
                                                deleted=deleted, error='', result=result))

    def start(self, count):
        return self.report('running', count,
                           result=dict(count=count, batchSize=500, batchCount=(count + 499) // 500))

    def upload(self, index, messages):
        return self.store.store_export_batch(self.token, self.id, index, {'messages': messages})

    def assert_error(self, status, callback, *args):
        with self.assertRaises(ManagementError) as caught:
            callback(*args)
        self.assertEqual(caught.exception.status, status)

    def test_selection_normalization_and_reject_batch(self):
        normalized = validate_request(dict(self.request, filters={'sender': 'ignored'}, page=5))
        self.assertIsNone(normalized['filters']['locked'])
        self.assertEqual(normalized['filters']['sender'], '')
        self.assertEqual(normalized['page'], 0)
        self.assert_error(400, validate_request,
                          dict(self.request, selection={'mode': 'batch', 'jobId': str(uuid.uuid4())}))
        filtered = validate_request(dict(self.request, selection={'mode': 'filtered'}, filters={'sender': 'A'}))
        self.assertEqual(filtered['filters']['sender'], 'A')
        selected = validate_request(dict(self.request, selection={'mode': 'selected', 'items': [
            {'id': '2', 'fingerprint': 'a' * 64}, {'id': '1', 'fingerprint': 'b' * 64}]}))
        self.assertEqual(selected['selection']['items'][0]['id'], '1')

    def test_complete_multiple_batches_persistence_and_full_text(self):
        self.start(501)
        first = [self.message(index) for index in range(500)]
        final = [self.message(500)]
        self.upload(0, first)
        self.report('running', 501, 500)
        self.upload(1, final)
        done = self.report('completed', 501, 501)
        self.assertEqual(done, self.report('completed', 501, 501))
        self.assertNotIn('完整内容', json.dumps(self.store.state(), ensure_ascii=False))
        self.assertNotIn('body', json.dumps(done))
        self.store.db.close()
        self.store = Store(self.path, 'http://192.168.1.2:8765')
        document, mime, filename = self.store.export_document(self.id, 'json')
        self.assertEqual(json.loads(document), first + final)
        self.assertEqual(mime, 'application/json; charset=utf-8')
        self.assertTrue(filename.endswith(self.id + '.json'))
        self.assertEqual(self.store.create_management(self.request), done)
        self.assert_error(409, self.upload, 1, final)

    def test_upload_order_idempotence_and_device_isolation(self):
        self.assert_error(409, self.upload, 0, [self.message()])
        self.start(501)
        first = [self.message()] * 500
        self.assert_error(409, self.upload, 1, [self.message()])
        self.assert_error(400, self.upload, 0, [self.message()])
        self.assert_error(404, self.store.store_export_batch,
                          self.devices[1]['deviceToken'], self.id, 0, {'messages': first})
        original = self.upload(0, first)
        self.assertEqual(original, self.upload(0, first))
        self.assert_error(409, self.upload, 0, [dict(self.message(), body='changed')] * 500)
        self.assert_error(400, self.upload, 2, [self.message()])
        self.assert_error(400, self.upload, True, [self.message()])
        self.assert_error(400, self.store.store_export_batch,
                          self.token, self.id, 1, {'messages': [self.message()], 'unexpected': True})

    def test_state_machine_count_progress_and_metadata_are_frozen(self):
        self.assert_error(409, self.report, 'ready', 1)
        self.assert_error(409, self.report, 'completed', 0)
        self.assert_error(400, self.start, 100001)
        self.assert_error(400, self.report, 'running', 1)
        for metadata in ({'count': 1, 'batchSize': 499, 'batchCount': 1},
                         {'count': 1, 'batchSize': 500, 'batchCount': True},
                         {'count': 2, 'batchSize': 500, 'batchCount': 1}):
            self.assert_error(400, self.report, 'running', 1, 0, metadata)
        self.start(1)
        self.assert_error(409, self.report, 'running', 1, 1)
        self.assert_error(409, self.report, 'completed', 1)
        self.upload(0, [self.message()])
        self.assert_error(400, self.report, 'running', 1, 1, None, 1)
        self.assert_error(409, self.report, 'running', 2)
        self.report('running', 1, 1)
        self.assert_error(409, self.report, 'running', 1, 0)
        self.assert_error(409, self.report, 'cancelled', 1, 1)
        self.report('completed', 1, 1)

    def test_size_limit_counts_utf8_bytes_and_replay_does_not_add_bytes(self):
        self.start(501)
        first = [self.message()] * 500
        size = self.store.validate_messages(first)[1]
        with patch('desktop.sms_management.EXPORT_MAX_BYTES', size):
            self.upload(0, first)
            self.upload(0, first)
            self.assert_error(413, self.upload, 1, [self.message()])
        self.assertEqual(self.store.db.execute('SELECT COUNT(*) FROM sms_export_batches').fetchone()[0], 1)

    def test_empty_export_and_failed_or_partial_cannot_download(self):
        self.assert_error(409, self.store.export_document, self.id, 'json')
        self.start(0)
        self.assert_error(400, self.upload, 0, [])
        self.report('completed', 0)
        self.assertEqual(json.loads(self.store.export_document(self.id, 'json')[0]), [])
        root = ET.fromstring(self.store.export_document(self.id, 'xml')[0])
        self.assertEqual(root.attrib, {'format': 'sms-test-v1', 'count': '0'})
        self.assert_error(400, self.store.export_document, self.id, 'html')
        self.request['requestId'] = self.id = str(uuid.uuid4())
        self.store.create_management(self.request)
        self.start(501)
        self.upload(0, [self.message()] * 500)
        self.assert_error(409, self.store.export_document, self.id, 'xml')
        failed = self.report('failed', 501, 500)
        self.assertEqual(failed, self.report('failed', 501, 500))
        self.assert_error(409, self.store.export_document, self.id, 'json')
        self.assert_error(409, self.upload, 1, [self.message()])

    def test_xml_preserves_attribute_whitespace_and_null_strings(self):
        records = [self.message(), dict(self.message(), subject=None, service_center='null')]
        document, mime = encode_document(records, 'xml')
        root = ET.fromstring(document)
        self.assertEqual(mime, 'application/xml; charset=utf-8')
        self.assertEqual(root.attrib['format'], 'sms-test-v1')
        for record, element in zip(records, root):
            for key, value in record.items():
                xml_key = {'sender': 'address', 'timestamp': 'date'}.get(key, key)
                if value is None:
                    self.assertEqual(element.attrib[xml_key + '_null'], 'true')
                    self.assertNotIn(xml_key, element.attrib)
                else:
                    self.assertEqual(element.attrib[xml_key], str(value))
                    self.assertNotIn(xml_key + '_null', element.attrib)

    def test_xml_invalid_characters_are_rejected_but_json_preserves_them(self):
        for character in ('\x00', '\x01', '\x0b', '\ufffe'):
            message = dict(self.message(), body='prefix' + character + 'suffix')
            with self.assertRaisesRegex(ValueError, 'JSON'):
                encode_document([message], 'xml')
            self.assertEqual(json.loads(encode_document([message], 'json')[0]), [message])

    def test_download_size_limit_rejects_expanded_xml(self):
        message = dict(self.message(), body="&" * 4000)
        self.start(1)
        self.upload(0, [message])
        self.report('completed', 1, 1)
        json_size = len(self.store.export_document(self.id, 'json')[0])
        with patch('desktop.sms_management.EXPORT_MAX_BYTES', json_size):
            self.store.export_document(self.id, 'json')
            with self.assertRaisesRegex(ManagementError, 'JSON') as caught:
                self.store.export_document(self.id, 'xml')
            self.assertEqual(caught.exception.status, 413)

    def test_export_record_validation_uses_upload_rules(self):
        self.start(1)
        for value in (dict(self.message(), body='x' * 4001), dict(self.message(), extra='bad'),
                      dict(self.message(), read=True), dict(self.message(), type=2)):
            with self.assertRaises(ApiError) as caught:
                self.upload(0, [value])
            self.assertEqual(caught.exception.status, 400)
        self.assertEqual(self.store.db.execute('SELECT COUNT(*) FROM sms_export_batches').fetchone()[0], 0)


if __name__ == '__main__':
    unittest.main()
