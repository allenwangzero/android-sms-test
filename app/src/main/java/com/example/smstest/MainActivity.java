package com.example.smstest;

import android.app.AlertDialog;
import android.Manifest;
import android.content.pm.PackageManager;
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
import androidx.activity.result.contract.ActivityResultContracts;
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
    private Button allowRead;
    private Button delete;
    private Button cancelDelete;
    private String previewId = "";
    private final ActivityResultLauncher<String> readPermission = registerForActivityResult(
            new ActivityResultContracts.RequestPermission(), granted -> {
                showMessage(granted ? "已授权读取短信，请在电脑读取列表。" : "未获得读取权限，无法读取或删除短信。可在系统应用权限设置中开启短信权限。");
                render();
            });
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
        description.setText("电脑编辑 / 读取筛选 → 扫码连接 → 手机确认写入或删除\n保持手机和电脑在同一可信局域网，保持本应用前台。\n\n仅用于测试机。导入短信保留设置的已读、状态及锁定字段。授权读取后，电脑可查看短信并筛选。完整短信列表需临时设为默认短信应用，否则系统可能只返回收件和已发送短信；清空所有短信前也需先设为默认应用。删除需在本机确认且不可恢复。本工具不发送真实短信、不支持彩信；操作完成后恢复原短信应用。\n");
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
        allowRead = button(layout, "授权读取短信（仅查看无需设为默认应用）", () ->
                readPermission.launch(Manifest.permission.READ_SMS));
        makeDefault = button(layout, "2. 临时设为默认短信应用", this::requestDefault);
        status = text(layout);
        confirm = button(layout, "3. 确认写入完整任务", () -> {
            LanClient.Job job = client.getJob();
            if (job == null || client.isBusy()) return;
            if (!isDefault() || !canRead()) { showMessage("请先授权读取短信，并设为默认短信应用，以记录导入批次"); return; }
            new AlertDialog.Builder(this).setTitle("确认写入 " + job.count + " 条短信")
                    .setMessage("这些记录将追加到系统收件箱。确认一次后每 500 条自动分批导入；请保持前台，失败或离开前台即停止，不会自动续写。")
                    .setNegativeButton("返回检查", null)
                    .setPositiveButton("写入", (dialog, which) -> client.confirm(job.id)).show();
        });
        delete = button(layout, "确认删除电脑选择的短信", () -> {
            SmsManagement.Pending pending = client.getDeletion();
            if (pending == null || client.isBusy()) return;
            if (!isDefault() || !canRead()) { showMessage("请先授权读取，并设为默认短信应用"); return; }
            new AlertDialog.Builder(this).setTitle(pending.modeLabel())
                    .setMessage(pending.batchSummary + "即将永久删除 " + pending.count + " 条手机短信，无法恢复。\n\n"
                            + ("all".equals(pending.mode) ? "包含所有短信文件夹及锁定短信；不包含彩信。\n" : "范围为本次准备的固定短信列表。\n")
                            + "准备完成后新收到的短信不在本次删除范围内。\n"
                            + "确认一次后每 200 条分批删除；显示总进度，失败或离开前台即停止，已删除短信不会恢复。\n\n样例：\n" + pending.preview)
                    .setNegativeButton("返回检查", null)
                    .setPositiveButton("永久删除 " + pending.count + " 条", (dialog, which) -> client.confirmDeletion(pending.id, true)).show();
        });
        cancelDelete = button(layout, "拒绝并取消本次删除任务", () -> {
            SmsManagement.Pending pending = client.getDeletion();
            if (pending != null && !client.isBusy()) client.confirmDeletion(pending.id, false);
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

    private boolean canRead() {
        return checkSelfPermission(Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED;
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
        SmsManagement.Pending pending = client.getDeletion();
        roleStatus.setText((defaultApp ? "当前：本工具为默认短信应用" : "当前：本工具不是默认短信应用")
                + (canRead() ? "\n短信读取权限：已授权" : "\n短信读取权限：未授权"));
        connection.setText(client.getConnection());
        status.setText(client.getResult());
        makeDefault.setEnabled(!defaultApp && !busy);
        allowRead.setEnabled(!canRead() && !busy);
        confirm.setEnabled(defaultApp && canRead() && !busy && job != null);
        delete.setEnabled(defaultApp && canRead() && !busy && pending != null);
        delete.setText(pending == null ? "确认删除电脑选择的短信" : "确认" + pending.modeLabel() + "（" + pending.count + " 条）");
        cancelDelete.setEnabled(!busy && pending != null);
        restore.setEnabled(!client.isWorking());
        scan.setEnabled(!busy && job == null && pending == null);
        paste.setEnabled(!busy && job == null && pending == null);
        String nextId = pending != null ? "delete:" + pending.id : job == null ? "" : "import:" + job.id;
        if (!nextId.equals(previewId)) {
            previewId = nextId;
            if (pending != null) preview.setText(pending.batchSummary + pending.modeLabel() + " · 共 " + pending.count + " 条 · 预览前 10 条\n" + pending.preview);
            else if (job == null) preview.setText("");
            else {
                StringBuilder content = new StringBuilder("任务共 " + job.count + " 条 · " + job.batchCount + " 批 · 预览前 20 条\n");
                DateFormat format = DateFormat.getDateTimeInstance();
                for (int i = 0; i < job.preview.size(); i++) {
                    SmsRecord record = job.preview.get(i);
                    content.append("\n").append(i + 1).append(". ").append(record.sender)
                            .append("\n").append(format.format(new Date(record.timestamp)))
                            .append("\n").append(record.previewMetadata())
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
