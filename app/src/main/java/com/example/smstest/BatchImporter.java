package com.example.smstest;

import java.io.IOException;
import java.util.List;

/** Executes one confirmed task in order. A failed operation prevents all later writes. */
public final class BatchImporter {
    public static final int BATCH_SIZE = 500;

    public interface Operations {
        void checkActive() throws Exception;
        List<SmsRecord> fetch(int index, int offset, int expected) throws Exception;
        void insert(SmsRecord record) throws Exception;
        void persist(int written) throws Exception;
        void report(int written) throws Exception;
        void progress(int written, int batchIndex);
    }

    public static final class Outcome {
        public final int written;
        public final Exception error;
        Outcome(int written, Exception error) { this.written = written; this.error = error; }
    }

    private BatchImporter() { }

    public static Outcome run(int total, Operations operations) {
        int written = 0;
        try {
            if (total < 1 || total > 100000) throw new IOException("任务数量无效");
            operations.checkActive();
            operations.persist(0);
            operations.report(0);
            for (int offset = 0, index = 0; offset < total; offset += BATCH_SIZE, index++) {
                operations.checkActive();
                int expected = Math.min(BATCH_SIZE, total - offset);
                List<SmsRecord> records = operations.fetch(index, offset, expected);
                if (records == null || records.size() != expected) throw new IOException("分批短信数量不匹配");
                // Validate the entire batch before making its first provider insertion.
                for (SmsRecord record : records) validate(record);
                for (SmsRecord record : records) {
                    operations.checkActive();
                    operations.insert(record);
                    written++;
                    operations.persist(written);
                    operations.progress(written, index);
                    if (written % 25 == 0) operations.report(written);
                }
                // The server authorizes the next batch only after this checkpoint succeeds.
                if (written % 25 != 0) operations.report(written);
            }
            return new Outcome(written, null);
        } catch (Exception error) { return new Outcome(written, error); }
    }

    public static void validate(SmsRecord record) throws IOException {
        if (record == null || record.sender == null || record.body == null
                || record.sender.trim().isEmpty() || record.body.trim().isEmpty()
                || record.sender.codePointCount(0, record.sender.length()) > 100
                || record.body.codePointCount(0, record.body.length()) > 4000
                || record.type != 1
                || (record.protocol != null && (record.protocol < 0 || record.protocol > 255))
                || (record.subject != null && record.subject.codePointCount(0, record.subject.length()) > 4000)
                || (record.serviceCenter != null && record.serviceCenter.codePointCount(0, record.serviceCenter.length()) > 100)
                || record.read < 0 || record.read > 1 || record.locked < 0 || record.locked > 1
                || record.status < -1 || record.status > 255
                || record.timestamp < 0 || record.timestamp > 4102444800000L) {
            throw new IOException("短信内容无效，已停止任务");
        }
    }
}
