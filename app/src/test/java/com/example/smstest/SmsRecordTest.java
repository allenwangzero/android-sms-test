package com.example.smstest;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class SmsRecordTest {
    private static int assertions;
    private static Map<String, Object> base() {
        Map<String, Object> fields = new HashMap<>();
        fields.put("sender", "测试发送人");
        fields.put("body", "测试内容");
        fields.put("timestamp", 1234567890000L);
        return fields;
    }
    private static void check(boolean condition) {
        assertions++;
        if (!condition) throw new AssertionError("SMS metadata assertion " + assertions);
    }
    private static void rejects(String key, Object value) throws Exception {
        Map<String, Object> fields = base();
        fields.put(key, value);
        try {
            SmsRecord.fromFields(fields);
            throw new AssertionError("Accepted invalid " + key + ": " + value);
        } catch (IOException expected) { assertions++; }
    }
    public static void main(String[] args) throws Exception {
        SmsRecord defaults = SmsRecord.fromFields(base());
        check(defaults.type == 1 && defaults.protocol == 0 && defaults.read == 1
                && defaults.status == -1 && defaults.locked == 0
                && defaults.subject == null && defaults.serviceCenter == null);
        Map<String, Object> fields = base();
        fields.put("type", 1L);
        fields.put("protocol", 255L);
        fields.put("subject", "主题");
        fields.put("service_center", "+639170000000");
        fields.put("read", 0);
        fields.put("status", 255);
        fields.put("locked", 1);
        fields.put("toa", null);
        fields.put("sc_toa", null);
        SmsRecord record = SmsRecord.fromFields(fields);
        Map<String, Object> values = record.providerValues();
        check(values.size() == 11 && !values.containsKey("toa") && !values.containsKey("sc_toa"));
        check(values.get("address").equals(fields.get("sender")) && values.get("body").equals(fields.get("body"))
                && values.get("date").equals(fields.get("timestamp")));
        check(values.get("type").equals(1) && values.get("protocol").equals(255));
        check(values.get("subject").equals("主题") && values.get("service_center").equals("+639170000000"));
        check(values.get("read").equals(0) && values.get("seen").equals(0)
                && values.get("status").equals(255) && values.get("locked").equals(1));
        check(record.previewMetadata().contains("read：未读（0）")
                && record.previewMetadata().contains("status：其他状态报告（255）")
                && record.previewMetadata().contains("locked：已锁定（1）"));
        for (String name : new String[] {"protocol", "subject", "service_center"}) fields.put(name, null);
        values = SmsRecord.fromFields(fields).providerValues();
        for (String name : new String[] {"protocol", "subject", "service_center"}) {
            check(values.containsKey(name) && values.get(name) == null);
        }
        for (String name : new String[] {"type", "protocol", "read", "status", "locked"}) {
            rejects(name, true);
            rejects(name, "1");
            rejects(name, 1.0);
            rejects(name, Long.MAX_VALUE);
            if (!name.equals("protocol")) rejects(name, null);
        }
        for (int type : new int[] {0, 2, 3, 4, 5, 6}) rejects("type", type);
        rejects("protocol", -1); rejects("protocol", 256);
        rejects("read", -1); rejects("read", 2);
        rejects("locked", -1); rejects("locked", 2);
        rejects("status", -2); rejects("status", 256);
        for (String name : new String[] {"toa", "sc_toa"}) {
            rejects(name, 0); rejects(name, "null"); rejects(name, false);
        }
        for (String name : new String[] {"subject", "service_center"}) {
            rejects(name, true); rejects(name, 0);
        }
        rejects("subject", "x".repeat(4001)); rejects("service_center", "x".repeat(101));
        fields = base();
        fields.put("subject", "😀".repeat(4000));
        fields.put("service_center", "😀".repeat(100));
        check(SmsRecord.fromFields(fields).subject.codePointCount(0, 8000) == 4000);
        for (int status : new int[] {-1, 0, 32, 64, 255}) {
            fields.put("status", status);
            check(SmsRecord.fromFields(fields).providerValues().get("status").equals(status));
        }
        rejects("timestamp", true); rejects("timestamp", 1.0); rejects("timestamp", null);
        rejects("timestamp", -1L); rejects("timestamp", 4102444800001L);
        System.out.println("SmsRecord: " + assertions + " metadata parsing, boundary, preview and provider mapping checks passed");
    }
}
