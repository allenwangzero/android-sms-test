package com.example.smstest;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.widget.Toast;

/** Declares the default SMS role entry point without sending messages. */
public final class RespondViaMessageService extends Service {
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Toast.makeText(this,
                "本工具不支持发送短信或短信拒接，未发送任何消息。请恢复原默认短信应用。",
                Toast.LENGTH_LONG).show();
        stopSelf(startId);
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
