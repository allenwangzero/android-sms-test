package com.example.smstest;

import android.content.BroadcastReceiver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.provider.Telephony;
import android.telephony.SmsMessage;
import android.util.Log;
import android.widget.Toast;

/** Preserves real SMS received while this test utility holds the default SMS role. */
public final class IncomingSmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())
                || !context.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context))) {
            return;
        }
        Context application = context.getApplicationContext();
        PendingResult pending = goAsync();
        Thread worker = new Thread(() -> {
            try {
                saveIncomingMessage(application, intent);
            } catch (RuntimeException error) {
                // Do not log phone numbers, message bodies, or provider exception details.
                Log.e("IncomingSmsReceiver", "Unable to store incoming SMS: "
                        + error.getClass().getSimpleName());
                new Handler(Looper.getMainLooper()).post(() -> Toast.makeText(application,
                        "真实短信保存失败，请立即恢复原默认短信应用。", Toast.LENGTH_LONG).show());
            } finally {
                pending.finish();
            }
        }, "incoming-sms-storage");
        worker.start();
    }

    private static void saveIncomingMessage(Context context, Intent intent) {
        SmsMessage[] parts = Telephony.Sms.Intents.getMessagesFromIntent(intent);
        if (parts == null || parts.length == 0 || parts[0] == null) {
            throw new IllegalArgumentException("Missing SMS parts");
        }
        StringBuilder body = new StringBuilder();
        for (SmsMessage part : parts) {
            if (part == null || part.getMessageBody() == null) {
                throw new IllegalArgumentException("Invalid SMS part");
            }
            body.append(part.getMessageBody());
        }
        SmsMessage first = parts[0];
        ContentValues values = new ContentValues();
        values.put(Telephony.Sms.ADDRESS, first.getOriginatingAddress());
        values.put(Telephony.Sms.BODY, body.toString());
        values.put(Telephony.Sms.DATE, System.currentTimeMillis());
        values.put(Telephony.Sms.DATE_SENT, first.getTimestampMillis());
        values.put(Telephony.Sms.READ, 0);
        values.put(Telephony.Sms.SEEN, 0);
        values.put(Telephony.Sms.PROTOCOL, first.getProtocolIdentifier());
        values.put(Telephony.Sms.REPLY_PATH_PRESENT, first.isReplyPathPresent() ? 1 : 0);
        values.put(Telephony.Sms.SERVICE_CENTER, first.getServiceCenterAddress());
        if (context.getContentResolver().insert(Telephony.Sms.Inbox.CONTENT_URI, values) == null) {
            throw new IllegalStateException("SMS provider returned no row");
        }
    }
}
