package com.example.smstest;

import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.provider.Telephony;
import java.io.IOException;

/** One provider insertion; LanClient owns the durable batch state and worker. */
public final class SmsWriter {
    private SmsWriter() { }

    public static void insert(Context context, SmsRecord record) throws IOException {
        if (!context.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context))) {
            throw new SecurityException("默认短信应用已切换，写入停止");
        }
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, record.sender);
        values.put(Telephony.Sms.BODY, record.body);
        values.put(Telephony.Sms.DATE, record.timestamp);
        values.put(Telephony.Sms.TYPE, Telephony.Sms.MESSAGE_TYPE_INBOX);
        values.put(Telephony.Sms.READ, 1);
        values.put(Telephony.Sms.SEEN, 1);
        Uri inserted = context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, values);
        if (inserted == null) throw new IOException("系统短信数据库未返回写入结果");
    }
}
