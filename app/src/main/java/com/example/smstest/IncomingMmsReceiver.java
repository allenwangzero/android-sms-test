package com.example.smstest;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.provider.Telephony;
import android.widget.Toast;

/** MMS transport is deliberately outside the test utility's scope. */
public final class IncomingMmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Telephony.Sms.Intents.WAP_PUSH_DELIVER_ACTION.equals(intent.getAction())
                || !context.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context))) {
            return;
        }
        Toast.makeText(context,
                "收到彩信通知，但本工具不支持下载彩信。请恢复原默认短信应用。",
                Toast.LENGTH_LONG).show();
    }
}
