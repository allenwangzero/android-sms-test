package com.example.smstest;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.Telephony;
import java.io.IOException;
import java.util.Map;

/** One provider insertion; LanClient owns the durable batch state and worker. */
public final class SmsWriter {
    private SmsWriter() { }

    public static Uri insert(Context context, SmsRecord record) throws IOException {
        if (!context.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context))) {
            throw new SecurityException("默认短信应用已切换，写入停止");
        }
        ContentValues values = new ContentValues();
        for (Map.Entry<String, Object> entry : record.providerValues().entrySet()) {
            Object value = entry.getValue();
            if (value == null) values.putNull(entry.getKey());
            else if (value instanceof String) values.put(entry.getKey(), (String) value);
            else if (value instanceof Integer) values.put(entry.getKey(), (Integer) value);
            else if (value instanceof Long) values.put(entry.getKey(), (Long) value);
            else throw new IOException("短信字段类型无效：" + entry.getKey());
        }
        Uri inserted = context.getContentResolver().insert(Telephony.Sms.CONTENT_URI, values);
        if (inserted == null) throw new IOException("系统短信数据库未返回写入结果");
        return inserted;
    }
}
