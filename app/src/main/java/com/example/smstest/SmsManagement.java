package com.example.smstest;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Telephony;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.text.DateFormat;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

/** Invoked exclusively on LanClient's worker. Durable reports never trigger another deletion. */
public final class SmsManagement {
    public interface Host {
        String deviceId();
        JSONObject request(String path, JSONObject data) throws Exception;
        void checkActive() throws Exception;
        void save(String key, String value) throws Exception;
        void result(String text);
        void busy(boolean value);
    }

    public static final class Pending {
        public final String id;
        public final int count;
        public final String mode;
        public final String preview;
        public final String batchSummary;
        Pending(String id, JSONObject prepared) throws Exception {
            this.id = id;
            count = prepared.getInt("count");
            mode = prepared.getString("selectionMode");
            preview = formatRows(prepared.getJSONArray("preview"));
            batchSummary = "batch".equals(mode) ? "导入批次：" + prepared.getString("jobId")
                    + "\n本次可清理 " + count + " 条；已不存在 " + prepared.getInt("missing")
                    + " 条，字段已变化 " + prepared.getInt("changed") + " 条（均跳过）。\n"
                    + (count == 0 ? "没有可删除目标；确认后仅完成本次任务。\n" : "")
                    + "仅清理本机可靠记录且字段未变化的短信。\n" : "";
        }
        public String modeLabel() {
            return "batch".equals(mode) ? "清理本次导入批次" : "all".equals(mode) ? "清空所有短信（包含锁定短信，不包含彩信）"
                    : "filtered".equals(mode) ? "删除全部筛选结果" : "删除勾选的短信";
        }
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final SmsRepository repository;
    private final Host host;
    private volatile Pending pending;

    public SmsManagement(Context context, SharedPreferences prefs, Host host) {
        this.context = context;
        this.prefs = prefs;
        this.host = host;
        repository = new SmsRepository(context, this::checkRead);
    }

    public Pending pending() { return pending; }

    public void recover() throws Exception {
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith("sms-manage:")) continue;
            JSONObject saved = new JSONObject((String) entry.getValue());
            String state = saved.getString("state");
            if ("preparing".equals(state) || "ready".equals(state) || "running".equals(state)) {
                JSONObject previous = saved.getJSONObject("report");
                JSONObject terminal = report(!"delete".equals(saved.getString("action")) ? "failed" : "interrupted",
                        previous.optInt("count", 0), previous.optInt("deleted", 0),
                        "应用进程中断，任务停止，不会自动续删。已删除数量为已确认下限；请重新读取手机短信。", null);
                saved.put("state", terminal.getString("status")).put("report", terminal).put("acked", false);
                host.save(entry.getKey(), saved.toString());
            }
        }
    }

    public void flush() throws Exception {
        String prefix = prefix();
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            JSONObject saved = new JSONObject((String) entry.getValue());
            if (saved.optBoolean("acked") || !isTerminal(saved.getString("state"))) continue;
            post(entry.getKey().substring(prefix.length()), saved.getJSONObject("report"));
            saved.put("acked", true);
            host.save(entry.getKey(), saved.toString());
        }
    }

    /** Returns true while a request was processed or awaits confirmation, reserving the worker. */
    public boolean tick() throws Exception {
        flush();
        if (pending != null) {
            host.request("/api/device/sms/requests", null); // Keep the paired device heartbeat alive while awaiting consent.
            return true;
        }
        JSONObject response = host.request("/api/device/sms/requests", null);
        if (response.isNull("request")) return false;
        JSONObject request = response.getJSONObject("request");
        String id = UUID.fromString(request.getString("id")).toString();
        if (!host.deviceId().equals(request.getString("deviceId"))) throw new IOException("短信管理任务设备不匹配");
        String action = request.getString("action");
        if (!"list".equals(action) && !"batches".equals(action) && !"delete".equals(action)) throw new IOException("未知短信管理操作");
        String raw = prefs.getString(key(id), null);
        if (raw != null) {
            JSONObject saved = new JSONObject(raw);
            post(id, saved.getJSONObject("report"));
            return true;
        }
        if (!"queued".equals(request.getString("status"))) {
            if (!"ready".equals(request.getString("status")) && !"running".equals(request.getString("status"))) {
                throw new IOException("短信管理任务状态不兼容");
            }
            JSONObject terminal = report(!"delete".equals(action) ? "failed" : "interrupted",
                    request.optInt("count", 0), request.optInt("deleted", 0),
                    "本机没有该管理任务记录，已停止，禁止重复删除。", null);
            store(id, action, terminal.getString("status"), terminal);
            flush();
            return true;
        }
        host.busy(true);
        try {
            // This marker precedes every provider read and immutable snapshot creation.
            store(id, action, "preparing", report("failed", 0, 0, "准备中", null));
            host.checkActive();
            if ("batches".equals(action)) {
                JSONObject rows = repository.listBatches(host.deviceId(), request.getInt("page"));
                host.checkActive();
                store(id, action, "completed", report("completed", rows.getInt("total"), 0, "", rows));
                host.result("已读取本机导入批次，共 " + rows.getInt("total") + " 批；请在电脑选择要清理的批次。");
            } else if ("list".equals(action)) {
                checkRead();
                JSONObject rows = repository.list(request.getJSONObject("filters"), request.getInt("page"));
                host.checkActive();
                store(id, action, "completed", report("completed", rows.getInt("total"), 0, "", rows));
                host.result("已读取短信列表，共 " + rows.getInt("total") + " 条匹配；请在电脑勾选或筛选。\n"
                        + formatRows(rows.getJSONArray("rows")));
            } else {
                checkRead();
                String mode = request.getJSONObject("selection").getString("mode");
                if ("all".equals(mode) || "batch".equals(mode)) {
                    if (!context.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context))) {
                        throw new SecurityException("清空短信或按批次清理前，请临时设为默认短信应用，以读取完整短信范围，然后在电脑重新提交任务");
                    }
                    checkDelete();
                }
                JSONObject prepared = repository.prepare(host.deviceId(), id, request.getJSONObject("filters"),
                        request.getJSONObject("selection"));
                host.checkActive();
                JSONObject ready = report("ready", prepared.getInt("count"), 0, "", prepared);
                store(id, action, "ready", ready);
                post(id, ready);
                pending = new Pending(id, prepared);
                host.result(pending.batchSummary + "已准备删除 " + pending.count + " 条，等待手机确认。尚未删除任何短信。");
            }
        } catch (Exception error) {
            JSONObject saved = new JSONObject(prefs.getString(key(id), "{}"));
            JSONObject previous = saved.optJSONObject("report");
            int count = previous == null ? 0 : previous.optInt("count", 0);
            store(id, action, "failed", report("failed", count, 0, errorText(error), null));
            pending = null;
            host.result("短信管理任务失败：" + errorText(error));
        } finally { host.busy(false); }
        flush();
        return true;
    }

    public void confirm(String id, boolean accepted) throws Exception {
        Pending selected = pending;
        if (selected == null || !selected.id.equals(id)) return;
        JSONObject saved = new JSONObject(prefs.getString(key(id), "{}"));
        if (!"ready".equals(saved.optString("state"))) return;
        host.busy(true);
        int deleted = 0;
        Exception failure = null;
        String terminalStatus = accepted ? "completed" : "cancelled";
        try {
            if (!accepted) {
                host.checkActive();
            } else {
                checkDelete();
                JSONObject response = host.request("/api/device/sms/requests", null);
                JSONObject active = response.getJSONObject("request");
                if (!id.equals(active.getString("id")) || !host.deviceId().equals(active.getString("deviceId"))
                        || !"delete".equals(active.getString("action")) || !"ready".equals(active.getString("status"))
                        || active.getInt("count") != selected.count) {
                    throw new IOException("电脑任务已变化，删除已停止，请重新读取短信");
                }
                if (repository.count(host.deviceId(), id) != selected.count || repository.getProgress(host.deviceId(), id) != 0) {
                    throw new IOException("本机删除快照已变化或已执行，禁止重复删除");
                }
                JSONObject running = report("running", selected.count, 0, "", null);
                store(id, "delete", "running", running);
                post(id, running); // Acknowledgement is required before the first deletion.
                SmsDeletion.Outcome outcome = SmsDeletion.run(selected.count, new SmsDeletion.Operations() {
                    @Override public void checkActive() throws Exception { checkDelete(); }
                    @Override public int deleteBatch(int offset, int limit) throws Exception {
                        return repository.deleteBatch(host.deviceId(), id, offset, limit, () -> {
                            checkDelete();
                            progress(repository.getProgress(host.deviceId(), id));
                        });
                    }
                    @Override public int deleted() throws Exception { return repository.getProgress(host.deviceId(), id); }
                    @Override public void persist(int count) throws Exception {
                        store(id, "delete", "running", SmsManagement.report("running", selected.count, count, "", null));
                    }
                    @Override public void report(int count) throws Exception {
                        post(id, SmsManagement.report("running", selected.count, count, "", null));
                    }
                    @Override public void progress(int count) {
                        host.result("正在删除 " + count + " / " + selected.count + " 条；每批 200 条，请保持前台。");
                    }
                });
                deleted = outcome.deleted;
                failure = outcome.error;
                if (failure != null) terminalStatus = "failed";
            }
        } catch (Exception error) {
            terminalStatus = "failed";
            failure = error;
            try { deleted = repository.getProgress(host.deviceId(), id); }
            catch (Exception progressError) { error.addSuppressed(progressError); }
        } finally { host.busy(false); }
        String message = failure == null ? "" : errorText(failure);
        store(id, "delete", terminalStatus, report(terminalStatus, selected.count, deleted, message, null));
        pending = null;
        host.result(("cancelled".equals(terminalStatus) ? "删除已取消" : failure == null ? "删除已完成" : "删除已停止")
                + "，已删除 " + deleted + " / " + selected.count + " 条。"
                + (message.isEmpty() ? "" : "\n" + message) + "\n请在电脑重新读取短信查看最新结果。");
        flush();
    }

    private void checkRead() throws Exception {
        host.checkActive();
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("请先点击手机的“授权读取短信”，然后在电脑重新提交任务");
        }
    }

    private void checkDelete() throws Exception {
        checkRead();
        if (!context.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context))) {
            throw new SecurityException("删除前请将本工具设为默认短信应用，然后在电脑重新提交任务");
        }
    }

    private String prefix() { return "sms-manage:" + host.deviceId() + ":"; }
    private String key(String id) { return prefix() + id; }
    private void store(String id, String action, String state, JSONObject report) throws Exception {
        host.save(key(id), new JSONObject().put("action", action).put("state", state)
                .put("report", report).put("acked", false).toString());
    }
    private void post(String id, JSONObject report) throws Exception {
        host.request("/api/device/sms/requests/" + id + "/status", report);
    }
    private static JSONObject report(String status, int count, int deleted, String error, JSONObject result) throws Exception {
        return new JSONObject().put("status", status).put("count", count).put("processed", deleted)
                .put("deleted", deleted).put("error", error).put("result", result == null ? JSONObject.NULL : result);
    }
    private static boolean isTerminal(String state) {
        return "completed".equals(state) || "failed".equals(state) || "interrupted".equals(state) || "cancelled".equals(state);
    }
    private static String errorText(Exception error) {
        String text = error.getClass().getSimpleName() + "：" + error.getMessage();
        return text.length() > 2000 ? text.substring(0, 2000) : text;
    }
    private static String formatRows(JSONArray rows) throws Exception {
        StringBuilder text = new StringBuilder();
        DateFormat format = DateFormat.getDateTimeInstance();
        for (int i = 0; i < Math.min(10, rows.length()); i++) {
            JSONObject row = rows.getJSONObject(i);
            text.append("\n").append(i + 1).append(". ").append(row.getString("sender"))
                    .append("\n").append(format.format(new Date(row.getLong("timestamp"))))
                    .append("\nread=").append(row.getInt("read")).append(row.getInt("read") == 1 ? "（已读）" : "（未读）")
                    .append(" status=").append(row.getInt("status")).append("（").append(statusLabel(row.getInt("status"))).append("）")
                    .append(" locked=").append(row.getInt("locked")).append(row.getInt("locked") == 1 ? "（锁定）" : "（未锁定）")
                    .append("\n").append(row.getString("body")).append("\n");
        }
        return text.toString();
    }

    private static String statusLabel(int status) {
        if (status == -1) return "无状态报告";
        if (status == 0) return "完成";
        if (status == 32) return "等待";
        if (status == 64) return "失败";
        return "其他状态报告";
    }
}
