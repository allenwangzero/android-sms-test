package com.example.smstest;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

/** A test SMS with the metadata supported by Android's SMS provider. */
public final class SmsRecord {
    public final String sender, body, subject, serviceCenter;
    public final long timestamp, dateSent;
    public final int type, read, status, locked, seen;
    public final Integer protocol;

    public SmsRecord(String sender, String body, long timestamp) {
        this(sender, body, timestamp, 1, 0, null, null, 1, -1, 0);
    }

    public SmsRecord(String sender, String body, long timestamp, int type, Integer protocol,
            String subject, String serviceCenter, int read, int status, int locked) {
        this(sender, body, timestamp, type, protocol, subject, serviceCenter, read, status, locked, 0, read);
    }

    public SmsRecord(String sender, String body, long timestamp, int type, Integer protocol,
            String subject, String serviceCenter, int read, int status, int locked, long dateSent, int seen) {
        this.dateSent = dateSent;
        this.seen = seen;
        this.sender = sender;
        this.body = body;
        this.timestamp = timestamp;
        this.type = type;
        this.protocol = protocol;
        this.subject = subject;
        this.serviceCenter = serviceCenter;
        this.read = read;
        this.status = status;
        this.locked = locked;
    }

    public static SmsRecord fromFields(Map<String, Object> fields) throws IOException {
        Object sender = fields.get("sender"), body = fields.get("body"), timestamp = fields.get("timestamp");
        if (!(sender instanceof String) || !(body instanceof String)
                || !(timestamp instanceof Integer || timestamp instanceof Long)) {
            throw new IOException("短信字段类型无效");
        }
        for (String name : new String[] {"toa", "sc_toa"}) {
            if (fields.get(name) != null) throw new IOException(name + " 不支持非空值，已停止任务");
        }
        if (fields.containsKey("type") && (!(fields.get("type") instanceof Integer || fields.get("type") instanceof Long)
                || ((Number) fields.get("type")).longValue() != 1)) {
            throw new IOException("仅支持 type=1 的收件短信");
        }
        Integer protocol = fields.containsKey("protocol") && fields.get("protocol") == null
                ? null : integer(fields, "protocol", 0, 0, 255);
        SmsRecord record = new SmsRecord((String) sender, (String) body, ((Number) timestamp).longValue(),
                integer(fields, "type", 1, 1, 1), protocol,
                nullableString(fields, "subject", 4000), nullableString(fields, "service_center", 100),
                integer(fields, "read", 1, 0, 1), integer(fields, "status", -1, -1, 255),
                integer(fields, "locked", 0, 0, 1), timestamp(fields, "date_sent"),
                integer(fields, "seen", integer(fields, "read", 1, 0, 1), 0, 1));
        BatchImporter.validate(record);
        return record;
    }

    private static long timestamp(Map<String, Object> fields, String name) throws IOException {
        if (!fields.containsKey(name)) return 0;
        Object value = fields.get(name);
        if (!(value instanceof Integer || value instanceof Long)) throw new IOException(name + " 必须是整数");
        long number = ((Number) value).longValue();
        if (number < 0 || number > 4102444800000L) throw new IOException(name + " 超出允许范围");
        return number;
    }

    private static int integer(Map<String, Object> fields, String name, int fallback, int min, int max)
            throws IOException {
        if (!fields.containsKey(name)) return fallback;
        Object value = fields.get(name);
        if (!(value instanceof Integer || value instanceof Long)) throw new IOException(name + " 必须是整数");
        long number = ((Number) value).longValue();
        if (number < min || number > max) throw new IOException(name + " 超出允许范围");
        return (int) number;
    }

    private static String nullableString(Map<String, Object> fields, String name, int max) throws IOException {
        Object value = fields.get(name);
        if (value == null) return null;
        if (!(value instanceof String)) throw new IOException(name + " 必须是字符串或 null");
        String text = (String) value;
        if (text.codePointCount(0, text.length()) > max) throw new IOException(name + " 过长");
        return text;
    }

    /** Standard SMS provider columns only; explicit nulls must reach ContentValues.putNull. */
    public Map<String, Object> providerValues() throws IOException {
        BatchImporter.validate(this);
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("address", sender);
        values.put("body", body);
        values.put("date", timestamp);
        values.put("type", type);
        values.put("protocol", protocol);
        values.put("subject", subject);
        values.put("service_center", serviceCenter);
        values.put("read", read);
        values.put("seen", seen);
        values.put("date_sent", dateSent);
        values.put("status", status);
        values.put("locked", locked);
        return values;
    }

    public String previewMetadata() {
        String statusLabel;
        if (status == -1) statusLabel = "无状态报告";
        else if (status == 0) statusLabel = "完成";
        else if (status == 32) statusLabel = "等待";
        else if (status == 64) statusLabel = "失败";
        else statusLabel = "其他状态报告";
        return "类型：收件箱（" + type + "）"
                + "\nread：" + (read == 1 ? "已读" : "未读") + "（" + read + "）"
                + "  status：" + statusLabel + "（" + status + "）"
                + "  locked：" + (locked == 1 ? "已锁定" : "未锁定") + "（" + locked + "）";
    }
}
