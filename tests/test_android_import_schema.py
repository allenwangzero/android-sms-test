"""Exercise the SQLite DDL/query shipped by Android without installing an Android SDK."""

import json
from pathlib import Path
import re
import sqlite3
import unittest


SOURCE = (Path(__file__).resolve().parents[1] /
          "app/src/main/java/com/example/smstest/SmsRepository.java").read_text()


def statements(section):
    return [json.loads('"' + value + '"')
            for value in re.findall(r'db\.execSQL\("((?:[^"\\]|\\.)*)"\)', section)]


class AndroidImportSchemaTest(unittest.TestCase):
    def setUp(self):
        self.db = sqlite3.connect(":memory:")
        self.addCleanup(self.db.close)
        self.create = SOURCE.split("@Override public void onCreate(", 1)[1].split(
            "@Override public void onUpgrade(", 1)[0]
        self.upgrade, self.imports = SOURCE.split(
            "@Override public void onUpgrade(", 1)[1].split("private void createImportTables(", 1)

    def create_current(self):
        for sql in statements(self.create) + statements(self.imports):
            self.db.execute(sql)

    def test_upgrade_preserves_old_deletion_tasks_and_snapshots(self):
        self.db.execute("CREATE TABLE tasks(scope TEXT NOT NULL,request_id TEXT NOT NULL,"
                        "request_hash TEXT NOT NULL,mode TEXT NOT NULL,total INTEGER NOT NULL,"
                        "deleted INTEGER NOT NULL,PRIMARY KEY(scope,request_id))")
        self.db.execute("CREATE TABLE items(scope TEXT NOT NULL,request_id TEXT NOT NULL,"
                        "position INTEGER NOT NULL,raw TEXT NOT NULL,fingerprint TEXT NOT NULL,"
                        "PRIMARY KEY(scope,request_id,position))")
        self.db.execute("INSERT INTO tasks VALUES('device','request','hash','selected',2,1)")
        self.db.execute("INSERT INTO items VALUES('device','request',0,'original snapshot','fingerprint')")
        for sql in statements(self.upgrade) + statements(self.imports):
            self.db.execute(sql)
        self.assertEqual(self.db.execute("SELECT total,deleted,missing,changed,job_id FROM tasks").fetchone(),
                         (2, 1, 0, 0, ""))
        self.assertEqual(self.db.execute("SELECT raw,fingerprint FROM items").fetchone(),
                         ("original snapshot", "fingerprint"))
        upgraded = self.db.execute("SELECT name,sql FROM sqlite_master WHERE type='table' ORDER BY name").fetchall()
        fresh = sqlite3.connect(":memory:")
        self.addCleanup(fresh.close)
        for sql in statements(self.create) + statements(self.imports):
            fresh.execute(sql)
        for name, _ in upgraded:
            self.assertEqual(self.db.execute(f"PRAGMA table_info({name})").fetchall(),
                             fresh.execute(f"PRAGMA table_info({name})").fetchall())

    def test_duplicate_provider_id_rejected_only_within_same_batch_and_scope(self):
        self.create_current()
        sql = "INSERT INTO import_items(scope,job_id,position,sms_id,raw,fingerprint) VALUES(?,?,?,?,?,?)"
        self.db.execute(sql, ("device-a", "job", 1, "19", "raw", "fingerprint"))
        with self.assertRaises(sqlite3.IntegrityError):
            self.db.execute(sql, ("device-a", "job", 2, "19", "raw", "fingerprint"))
        self.db.execute(sql, ("device-b", "job", 1, "19", "raw", "fingerprint"))
        self.db.execute(sql, ("device-a", "other-job", 1, "19", "raw", "fingerprint"))
        self.assertEqual(self.db.execute("SELECT COUNT(*) FROM import_items WHERE scope=? AND job_id=?",
                                         ("device-a", "job")).fetchone()[0], 1)

    def test_history_query_scopes_and_paginates_large_history_using_index(self):
        self.create_current()
        insert = "INSERT INTO import_batches(scope,job_id,created_at,requested,status,recorded) VALUES(?,?,?,?,?,?)"
        self.db.executemany(insert, (("device-a", f"{i:06}", i // 3, 100000, "interrupted", i % 100001)
                                     for i in range(100000)))
        self.db.execute(insert, ("device-b", "other-device", 999999, 1, "completed", 1))
        match = re.search(r'db\.rawQuery\("(SELECT job_id,created_at,requested,status,recorded[^"\n]+)"', SOURCE)
        self.assertIsNotNone(match)
        query = match.group(1)
        first = self.db.execute(query, ("device-a", "50", "0")).fetchall()
        second = self.db.execute(query, ("device-a", "50", "50")).fetchall()
        self.assertEqual(len(first), 50)
        self.assertEqual(len(second), 50)
        self.assertEqual(first[0], ("099999", 33333, 100000, "interrupted", 99999))
        self.assertFalse({row[0] for row in first} & {row[0] for row in second})
        plan = " ".join(row[3] for row in self.db.execute("EXPLAIN QUERY PLAN " + query,
                                                         ("device-a", "50", "50")))
        self.assertIn("import_batches_created", plan)
        self.assertNotIn("TEMP B-TREE", plan)


if __name__ == "__main__":
    unittest.main()
