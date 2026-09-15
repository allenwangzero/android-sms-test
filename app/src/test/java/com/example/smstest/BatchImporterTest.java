package com.example.smstest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class BatchImporterTest {
    private static final class Harness implements BatchImporter.Operations {
        final int total;
        int inserts, durable, acknowledged, fetched;
        int failInsert = -1, failPersist = -1, failReport = -1, failFetch = -1, invalidBatch = -1;
        int stopAt = -1, invalidMetadataBatch = -1;
        Harness(int total) { this.total = total; }
        @Override public void checkActive() throws Exception {
            if (inserts == stopAt) throw new IOException("background");
        }
        @Override public List<SmsRecord> fetch(int index, int offset, int expected) throws Exception {
            if (offset != acknowledged || index != fetched || expected != Math.min(500, total - offset)) {
                throw new AssertionError("Fetched out of order or before acknowledged boundary");
            }
            fetched++;
            if (index == failFetch) throw new IOException("network");
            List<SmsRecord> records = new ArrayList<>();
            for (int i = 0; i < expected; i++) records.add(new SmsRecord("sender", "record-" + (offset + i), 0));
            if (index == invalidBatch) records.set(expected - 1, new SmsRecord("", "invalid", 0));
            if (index == invalidMetadataBatch) {
                records.set(expected - 1, new SmsRecord("sender", "invalid metadata", 0,
                        1, 0, null, null, 2, -1, 0));
            }
            return records;
        }
        @Override public void insert(SmsRecord record) throws Exception {
            if (inserts == failInsert) throw new IOException("provider");
            if (!record.body.equals("record-" + inserts)) throw new AssertionError("Duplicated or reordered record");
            inserts++;
        }
        @Override public void persist(int written) throws Exception {
            if (written == failPersist) throw new IOException("disk");
            durable = written;
        }
        @Override public void report(int written) throws Exception {
            if (written != durable) throw new AssertionError("Reported before persistence");
            if (written == failReport) throw new IOException("status network");
            acknowledged = written;
        }
        @Override public void progress(int written, int index) { }
    }
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected execution result");
    }
    public static void main(String[] args) {
        for (int total : new int[] {1, 499, 500, 501, 1000, 1001, 10000, 100000}) {
            Harness h = new Harness(total);
            BatchImporter.Outcome result = BatchImporter.run(total, h);
            check(result.error == null && result.written == total && h.inserts == total
                    && h.durable == total && h.acknowledged == total && h.fetched == (total + 499) / 500);
        }
        Harness provider = new Harness(1001); provider.failInsert = 507;
        failed(provider, 507, 2);
        Harness disk = new Harness(1001); disk.failPersist = 501;
        failed(disk, 501, 2); check(disk.durable == 500);
        Harness network = new Harness(1001); network.failFetch = 1;
        failed(network, 500, 2);
        Harness report = new Harness(1001); report.failReport = 25;
        failed(report, 25, 1);
        Harness boundary = new Harness(1001); boundary.failReport = 500;
        failed(boundary, 500, 1);
        Harness invalid = new Harness(1001); invalid.invalidBatch = 1;
        failed(invalid, 500, 2);
        Harness invalidMetadata = new Harness(1001); invalidMetadata.invalidMetadataBatch = 1;
        failed(invalidMetadata, 500, 2);
        Harness stopped = new Harness(1001); stopped.stopAt = 503;
        failed(stopped, 503, 2);
        Harness initialDisk = new Harness(1001); initialDisk.failPersist = 0;
        failed(initialDisk, 0, 0);
        Harness initialReport = new Harness(1001); initialReport.failReport = 0;
        failed(initialReport, 0, 0);
        System.out.println("BatchImporter: 18 boundary and failure-stop scenarios passed");
    }
    private static void failed(Harness h, int written, int fetched) {
        BatchImporter.Outcome result = BatchImporter.run(h.total, h);
        check(result.error != null && result.written == written && h.inserts == written && h.fetched == fetched);
    }
}
