package com.example.smstest;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Bounded export orchestration, independent of Android and network transport. */
public final class SmsExporter {
    public static final int BATCH_SIZE = 500;
    public static final int MAX_COUNT = 100000;
    public static final long MAX_BYTES = 256L * 1024 * 1024;
    public static final int MAX_BATCH_BYTES = 16 * 1024 * 1024;
    public interface Operations {
        void checkActive() throws Exception;
        void upload(int index, int offset, int count) throws Exception;
        void progress(int processed) throws Exception;
    }
    public interface LedgerCleanup {
        void persist() throws Exception;
        void clearSnapshot() throws Exception;
    }

    /** Never lose a terminal ledger, and never turn cleanup failure into task replay. */
    public static Exception finish(LedgerCleanup operations) throws Exception {
        operations.persist();
        try { operations.clearSnapshot(); return null; }
        catch (Exception error) { return error; }
    }

    private SmsExporter() { }

    public static void run(int count, Operations operations) throws Exception {
        if (count < 0 || count > MAX_COUNT) throw new IOException("导出数量超过 100000 条");
        for (int offset = 0, index = 0; offset < count; index++) {
            operations.checkActive();
            int size = Math.min(BATCH_SIZE, count - offset);
            operations.upload(index, offset, size);
            offset += size;
            operations.progress(offset);
        }
        operations.checkActive();
    }

    /** Same fourteen raw columns as the management list fingerprint; no preview truncation. */
    public static Map<String, Object> fields(String[] raw) throws IOException {
        if (raw.length != 14) throw new IOException("短信导出字段不完整");
        String id = raw[0];
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("sender", string(id, "sender", raw[1], 100, false));
        fields.put("body", string(id, "body", raw[2], 4000, false));
        fields.put("timestamp", number(id, "timestamp", raw[3], 0, 4102444800000L));
        fields.put("type", number(id, "type", raw[4], 1, 1));
        fields.put("read", number(id, "read", raw[5], 0, 1));
        fields.put("status", number(id, "status", raw[6], -1, 255));
        fields.put("locked", number(id, "locked", raw[7], 0, 1));
        fields.put("protocol", raw[8] == null ? null : number(id, "protocol", raw[8], 0, 255));
        fields.put("subject", string(id, "subject", raw[9], 4000, true));
        fields.put("service_center", string(id, "service_center", raw[10], 100, true));
        fields.put("date_sent", number(id, "date_sent", raw[11], 0, 4102444800000L));
        fields.put("seen", number(id, "seen", raw[12], 0, 1));
        fields.put("toa", null);
        fields.put("sc_toa", null);
        return fields;
    }

    private static String string(String id, String field, String value, int max, boolean nullable) throws IOException {
        if (value == null && nullable) return null;
        if (value == null || (!nullable && value.trim().isEmpty()) || value.codePointCount(0, value.length()) > max) {
            throw invalid(id, field);
        }
        // UTF-8 encoding replaces isolated UTF-16 surrogates; reject instead of changing text.
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (Character.isHighSurrogate(current)) {
                if (index + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(index + 1))) throw invalid(id, field);
                index++;
            } else if (Character.isLowSurrogate(current)) throw invalid(id, field);
        }
        return value;
    }
    private static long number(String id, String field, String value, long min, long max) throws IOException {
        try {
            if (value == null || !value.matches("-?[0-9]+")) throw invalid(id, field);
            long parsed = Long.parseLong(value);
            if (parsed < min || parsed > max) throw invalid(id, field);
            return parsed;
        } catch (NumberFormatException error) { throw invalid(id, field); }
    }
    private static IOException invalid(String id, String field) {
        return new IOException("短信 " + id + " 的 " + field + " 不符合可导入范围，导出停止（未截断或丢弃）");
    }
}
