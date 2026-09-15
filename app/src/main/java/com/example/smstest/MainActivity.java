package com.example.smstest;

import android.app.AlertDialog;
import android.app.role.RoleManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.provider.Telephony;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
import java.text.DateFormat;
import java.util.Date;

/** Receives explicit inbox test records from a paired LAN computer. */
public final class MainActivity extends ComponentActivity {
    private static final int REQUEST_DEFAULT = 1;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private LanClient client;
    private TextView roleStatus;
    private TextView status;
    private TextView connection;
    private TextView preview;
    private Button makeDefault;
    private Button confirm;
    private Button restore;
    private Button scan;
    private Button paste;
    private String previewId = "";
    private final ActivityResultLauncher<ScanOptions> scanner = registerForActivityResult(
            new ScanContract(), result -> {
                if (result.getContents() != null) client.pair(result.getContents());
            });
    private final Runnable refresh = new Runnable() {
        @Override public void run() {
            render();
            handler.postDelayed(this, 500);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        client = LanClient.getInstance(getApplicationContext());
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density);
        layout.setPadding(padding, padding, padding, padding);
        layout.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(padding + insets.getSystemWindowInsetLeft(), padding + insets.getSystemWindowInsetTop(),
                    padding + insets.getSystemWindowInsetRight(), padding + insets.getSystemWindowInsetBottom());
            return insets;
        });
        TextView heading = new TextView(this);
        heading.setText("短信测试工具");
        heading.setTextSize(26);
        layout.addView(heading);
        TextView description = new TextView(this);
        description.setText("电脑编辑 → 扫码连接 → 手机确认写入\n保持手机和电脑在同一可信局域网，保持本应用前台。\n\n仅用于测试机。短信追加到系统收件箱，设为已读。本工具不发送真实短信、不支持彩信；写入完成后立即恢复原短信应用。\n");
        layout.addView(description);
        connection = text(layout);
        scan = button(layout, "1. 扫描电脑配对二维码", () -> {
            ScanOptions options = new ScanOptions();
            options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
            options.setPrompt("扫描电脑页面的配对二维码");
            options.setBeepEnabled(false);
            options.setOrientationLocked(false);
            scanner.launch(options);
        });
        paste = button(layout, "备用：粘贴配对链接", () -> {
            EditText input = new EditText(this);
            input.setSingleLine(true);
            input.setHint("sms-test://pair?url=…&token=…");
            new AlertDialog.Builder(this).setTitle("粘贴电脑配对链接").setView(input)
                    .setNegativeButton("取消", null)
                    .setPositiveButton("连接", (dialog, which) -> client.pair(input.getText().toString())).show();
        });
        roleStatus = text(layout);
        makeDefault = button(layout, "2. 临时设为默认短信应用", this::requestDefault);
        status = text(layout);
        confirm = button(layout, "3. 确认写入完整任务", () -> {
            LanClient.Job job = client.getJob();
            if (job == null || client.isBusy()) return;
            if (!isDefault()) { showMessage("请先设为默认短信应用"); return; }
            new AlertDialog.Builder(this).setTitle("确认写入 " + job.count + " 条短信")
                    .setMessage("这些记录将追加到系统收件箱。确认一次后每 500 条自动分批导入；请保持前台，失败或离开前台即停止，不会自动续写。")
                    .setNegativeButton("返回检查", null)
                    .setPositiveButton("写入", (dialog, which) -> client.confirm(job.id)).show();
        });
        restore = button(layout, "4. 恢复原短信应用", this::restoreDefault);
        preview = text(layout);
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(layout);
        setContentView(scroll);
        if (Intent.ACTION_SENDTO.equals(getIntent().getAction())) {
            showMessage("本工具不支持发送短信，请恢复原短信应用后发送。");
        }
    }

    private TextView text(LinearLayout layout) {
        TextView view = new TextView(this);
        view.setTextIsSelectable(true);
        view.setPadding(0, 12, 0, 12);
        layout.addView(view);
        return view;
    }

    private Button button(LinearLayout layout, String title, Runnable action) {
        Button button = new Button(this);
        button.setText(title);
        button.setOnClickListener(view -> action.run());
        layout.addView(button);
        return button;
    }

    private boolean isDefault() {
        return getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(this));
    }

    private void requestDefault() {
        String previous = Telephony.Sms.getDefaultSmsPackage(this);
        if (getPackageName().equals(previous)) return;
        if (previous != null) {
            getPreferences(MODE_PRIVATE).edit().putString("previousSmsApp", previous).apply();
        }
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                RoleManager roles = getSystemService(RoleManager.class);
                if (roles == null || !roles.isRoleAvailable(RoleManager.ROLE_SMS)) {
                    showMessage("该设备不支持默认短信应用角色。");
                    return;
                }
                startActivityForResult(roles.createRequestRoleIntent(RoleManager.ROLE_SMS), REQUEST_DEFAULT);
            } else {
                Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
                intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, getPackageName());
                startActivityForResult(intent, REQUEST_DEFAULT);
            }
        } catch (ActivityNotFoundException | SecurityException error) {
            showMessage("无法打开授权页面：" + error.getMessage());
        }
    }

    private void restoreDefault() {
        try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                String previous = getPreferences(MODE_PRIVATE).getString("previousSmsApp", "");
                if (!previous.isEmpty()) {
                    Intent intent = new Intent(Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT);
                    intent.putExtra(Telephony.Sms.Intents.EXTRA_PACKAGE_NAME, previous);
                    startActivity(intent);
                    return;
                }
            }
            startActivity(new Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS));
            showMessage("在默认应用设置中，将短信应用改回原来的应用。");
        } catch (ActivityNotFoundException | SecurityException error) {
            showMessage("请手动进入系统设置 → 默认应用 → 短信应用，恢复原来的应用。");
        }
    }

    private void render() {
        boolean busy = client.isBusy();
        boolean defaultApp = isDefault();
        LanClient.Job job = client.getJob();
        roleStatus.setText(defaultApp ? "当前：本工具为默认短信应用" : "当前：本工具不是默认短信应用");
        connection.setText(client.getConnection());
        status.setText(client.getResult());
        makeDefault.setEnabled(!defaultApp && !busy);
        confirm.setEnabled(defaultApp && !busy && job != null);
        restore.setEnabled(!client.isWorking());
        scan.setEnabled(!busy && job == null);
        paste.setEnabled(!busy && job == null);
        String nextId = job == null ? "" : job.id;
        if (!nextId.equals(previewId)) {
            previewId = nextId;
            if (job == null) preview.setText("");
            else {
                StringBuilder content = new StringBuilder("任务共 " + job.count + " 条 · " + job.batchCount + " 批 · 预览前 20 条\n");
                DateFormat format = DateFormat.getDateTimeInstance();
                for (int i = 0; i < job.preview.size(); i++) {
                    SmsRecord record = job.preview.get(i);
                    content.append("\n").append(i + 1).append(". ").append(record.sender)
                            .append("\n").append(format.format(new Date(record.timestamp)))
                            .append("\n").append(record.body).append("\n");
                }
                preview.setText(content.toString());
            }
        }
        // A foreground receiver must stay awake to poll and receive result acknowledgements.
        getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }

    private void showMessage(String message) {
        android.widget.Toast.makeText(this, message, android.widget.Toast.LENGTH_LONG).show();
    }

    @Override protected void onResume() {
        super.onResume();
        client.setForeground(true);
        handler.post(refresh);
    }

    @Override protected void onStop() {
        if (!isChangingConfigurations()) client.setForeground(false);
        super.onStop();
    }

    @Override protected void onPause() {
        handler.removeCallbacks(refresh);
        super.onPause();
    }
}
