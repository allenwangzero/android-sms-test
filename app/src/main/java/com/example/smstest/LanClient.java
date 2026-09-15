package com.example.smstest;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Build;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** All network, ledger and provider work is serialized, independently of Activity recreation. */
public final class LanClient {
    private static LanClient instance;
    private final Context context;
    private final SharedPreferences prefs;
    private final SmsManagement management;
    private final SmsRepository imports;
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor();
    private String baseUrl;
    private String token;
    private String deviceId;
    private volatile boolean foreground;
    private volatile boolean busy;
    private volatile boolean stopped;
    private volatile Job current;
    private volatile String connection = "未连接电脑";
    private volatile String result = "扫码连接电脑，然后在电脑发送短信列表。";
    private volatile boolean storageBlocked;

    public static final class Job {
        public final String id;
        public final int count;
        public final int batchCount;
        public final List<SmsRecord> preview;
        Job(String id, int count, List<SmsRecord> preview) {
            this.id = id;
            this.count = count;
            this.batchCount = (count + BatchImporter.BATCH_SIZE - 1) / BatchImporter.BATCH_SIZE;
            this.preview = Collections.unmodifiableList(preview);
        }
    }

    private LanClient(Context context) {
        this.context = context;
        prefs = context.getSharedPreferences("lanSmsV1", Context.MODE_PRIVATE);
        baseUrl = prefs.getString("url", "");
        token = prefs.getString("token", "");
        deviceId = prefs.getString("deviceId", "");
        imports = new SmsRepository(context);
        management = new SmsManagement(context, prefs, new SmsManagement.Host() {
            @Override public String deviceId() { return deviceId; }
            @Override public JSONObject request(String path, JSONObject data) throws Exception {
                return http(baseUrl, token, path, data);
            }
            @Override public void checkActive() throws IOException {
                if (!foreground || stopped || storageBlocked) throw new IOException("应用已离开前台或本地记录异常，任务停止，不会自动续删");
            }
            @Override public void save(String key, String value) throws IOException { LanClient.this.save(key, value); }
            @Override public void result(String text) { result = text; }
            @Override public void busy(boolean value) { busy = value; }
        });
        worker.execute(() -> {
            try {
                management.recover();
                imports.recoverImports();
                for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                    if (!entry.getKey().startsWith("job:")) continue;
                    JSONObject ledger = new JSONObject((String) entry.getValue());
                    if ("writing".equals(ledger.getString("status"))) {
                        ledger.put("status", "interrupted").put("acked", false)
                                .put("error", "进程中断；已确认写入数量为下限，最后一条可能已写入。不会自动续写。");
                        save(entry.getKey(), ledger.toString());
                        result = "上次任务中断，部分短信已保留；结果将回传电脑，不会重复执行。";
                    }
                }
            } catch (Exception error) { blockStorage(error); }
        });
        worker.scheduleWithFixedDelay(this::tick, 0, 2, TimeUnit.SECONDS);
    }

    public static synchronized LanClient getInstance(Context context) {
        if (instance == null) instance = new LanClient(context.getApplicationContext());
        return instance;
    }

    public void setForeground(boolean value) {
        foreground = value;
        if (!value && busy) stopped = true;
    }
    public boolean isBusy() { return busy || storageBlocked; }
    public boolean isWorking() { return busy; }
    public Job getJob() { return current; }
    public SmsManagement.Pending getDeletion() { return management.pending(); }
    public String getConnection() { return connection; }
    public String getResult() { return result; }

    public void pair(String link) {
        worker.execute(() -> {
            if (current != null || management.pending() != null || storageBlocked) {
                result = "请先完成当前任务，再更换连接。";
                return;
            }
            busy = true;
            try {
                Uri uri = Uri.parse(link.trim());
                if (!"sms-test".equals(uri.getScheme()) || !"pair".equals(uri.getHost())) {
                    throw new IOException("请扫描电脑页面的配对二维码");
                }
                String url = PairingUrl.validate(uri.getQueryParameter("url"));
                String pairToken = uri.getQueryParameter("token");
                if (pairToken == null || !pairToken.matches("[A-Za-z0-9_-]{16,256}")) {
                    throw new IOException("配对码格式错误");
                }
                String clientId = prefs.getString("clientId", "");
                if (clientId.isEmpty()) {
                    clientId = UUID.randomUUID().toString();
                    save("clientId", clientId);
                }
                JSONObject request = new JSONObject().put("token", pairToken)
                        .put("clientId", clientId)
                        .put("name", Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE);
                JSONObject response = http(url, "", "/api/pair", request);
                String newToken = response.getString("deviceToken");
                String newId = UUID.fromString(response.getString("deviceId")).toString();
                if (!newToken.matches("[A-Za-z0-9_-]{16,256}")) throw new IOException("电脑返回无效凭据");
                SharedPreferences.Editor pairing = prefs.edit().putString("url", url)
                        .putString("token", newToken).putString("deviceId", newId);
                if (newId.equals(deviceId) && !url.equals(baseUrl)) {
                    // A stable device ID identifies the same desktop database at its new address.
                    // Move every ledger entry in the same commit as its connection credentials,
                    // including acknowledged history, so address changes cannot replay old jobs.
                    String oldPrefix = "job:" + baseUrl + ":" + deviceId + ":";
                    String newPrefix = "job:" + url + ":" + newId + ":";
                    for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
                        if (!entry.getKey().startsWith(oldPrefix)) continue;
                        pairing.putString(newPrefix + entry.getKey().substring(oldPrefix.length()),
                                (String) entry.getValue());
                        pairing.remove(entry.getKey());
                    }
                }
                if (!pairing.commit()) {
                    IOException error = new IOException("无法保存配对及任务记录，请重启应用后检查");
                    blockStorage(error);
                    throw error;
                }
                baseUrl = url;
                token = newToken;
                deviceId = newId;
                connection = "已连接：" + baseUrl;
                result = "配对成功，请在电脑选择本手机并发送短信。";
                try {
                    flushResults();
                } catch (Exception error) {
                    connection = "配对成功，结果待同步，将重试：" + error.getMessage();
                }
            } catch (Exception error) {
                result = "配对失败：" + error.getMessage();
            } finally { busy = false; }
        });
    }

    private void tick() {
        if (!foreground || storageBlocked || token.isEmpty()) return;
        try {
            stopped = false;
            flushResults();
            management.flush();
            if (current == null && management.tick()) {
                connection = "已连接：" + baseUrl;
                return;
            }
            JSONObject response = http(baseUrl, token, "/api/device/jobs", null);
            connection = "已连接：" + baseUrl;
            if (response.isNull("job")) { current = null; return; }
            JSONObject raw = response.getJSONObject("job");
            String id = UUID.fromString(raw.getString("id")).toString();
            String saved = prefs.getString(key(id), null);
            if (saved != null) {
                // Re-report known jobs even after a server retry; never reinsert them.
                JSONObject ledger = new JSONObject(saved);
                report(id, ledger);
                return;
            }
            if (current != null && current.id.equals(id)) return;
            if (!deviceId.equals(raw.getString("deviceId"))) throw new IOException("任务设备不匹配");
            String remoteStatus = raw.getString("status");
            if (!"queued".equals(remoteStatus) && !"received".equals(remoteStatus)) {
                JSONObject interrupted = ledger("interrupted", raw.getInt("written"),
                        "本机没有该执行任务记录，为防重复写入，已停止任务");
                save(key(id), interrupted.toString());
                report(id, interrupted);
                return;
            }
            Job job = parseJob(id, raw);
            report(id, ledger("received", 0, ""));
            current = job;
            result = "已收到 " + job.count + " 条，共 " + job.batchCount + " 批；确认一次后依次导入。";
        } catch (Exception error) {
            connection = "连接或同步失败，将重试：" + error.getMessage();
        }
    }

    private Job parseJob(String id, JSONObject raw) throws Exception {
        if (!raw.has("batchSize") || !raw.has("batchCount") || !raw.has("preview")) {
            throw new IOException("电脑端协议不兼容，请升级电脑端和手机 App 至 1.4.0 或更新版本");
        }
        int count = integer(raw, "count");
        if (count < 1 || count > 100000 || integer(raw, "batchSize") != BatchImporter.BATCH_SIZE
                || integer(raw, "batchCount") != (count + 499) / 500) {
            throw new IOException("任务分批信息无效，请升级电脑端和手机 App");
        }
        List<SmsRecord> preview = parseRecords(raw.getJSONArray("preview"), Math.min(20, count));
        return new Job(id, count, preview);
    }

    private int integer(JSONObject object, String name) throws Exception {
        Object value = object.get(name);
        if (!(value instanceof Integer || value instanceof Long)) throw new IOException("整数类型无效：" + name);
        long number = ((Number) value).longValue();
        if (number < 0 || number > Integer.MAX_VALUE) throw new IOException("整数范围无效：" + name);
        return (int) number;
    }

    private List<SmsRecord> parseRecords(JSONArray array, int expected) throws Exception {
        if (array.length() != expected) throw new IOException("分批短信数量不匹配");
        List<SmsRecord> records = new ArrayList<>();
        for (int i = 0; i < array.length(); i++) {
            JSONObject item = array.getJSONObject(i);
            Map<String, Object> fields = new HashMap<>();
            Iterator<String> names = item.keys();
            while (names.hasNext()) {
                String name = names.next();
                Object value = item.get(name);
                fields.put(name, value == JSONObject.NULL ? null : value);
            }
            SmsRecord record = SmsRecord.fromFields(fields);
            records.add(record);
        }
        return records;
    }

    public void confirm(String jobId) {
        worker.execute(() -> write(jobId));
    }

    public void confirmDeletion(String requestId, boolean accepted) {
        worker.execute(() -> {
            if (storageBlocked || current != null) return;
            stopped = !foreground;
            try { management.confirm(requestId, accepted); }
            catch (Exception error) { result = "删除结果待同步或已停止：" + error.getMessage(); }
        });
    }

    private void write(String jobId) {
        Job job = current;
        if (storageBlocked || management.pending() != null || job == null || !job.id.equals(jobId) || prefs.contains(key(jobId))) return;
        busy = true;
        stopped = !foreground;
        BatchImporter.Outcome outcome = BatchImporter.run(job.count, new BatchImporter.Operations() {
            private Uri lastInserted;
            @Override public void checkActive() throws IOException {
                if (stopped) throw new IOException("应用已离开前台，任务停止；不会自动续写");
            }
            @Override public List<SmsRecord> fetch(int index, int offset, int expected) throws Exception {
                JSONObject raw = http(baseUrl, token, "/api/device/jobs/" + job.id + "/batches/" + index, null);
                if (!job.id.equals(raw.getString("jobId")) || integer(raw, "index") != index
                        || integer(raw, "offset") != offset || integer(raw, "total") != job.count) {
                    throw new IOException("分批任务标识或顺序不匹配");
                }
                return parseRecords(raw.getJSONArray("messages"), expected);
            }
            @Override public void insert(SmsRecord record) throws Exception { lastInserted = SmsWriter.insert(context, record); }
            @Override public void recordInserted(int written) throws Exception {
                try { imports.recordInserted(deviceId, job.id, written, lastInserted); }
                catch (Exception error) {
                    throw new IOException("短信已写入，但批次记录保存失败；已停止，最后一条可能无法按批次清理：" + error.getMessage(), error);
                }
            }
            @Override public void persist(int written) throws Exception {
                // Provider and preferences are not atomic: never replay after an interrupted commit.
                save(key(job.id), ledger("writing", written, "").toString());
                if (written == 0) imports.beginImport(deviceId, job.id, job.count);
            }
            @Override public void report(int written) throws Exception {
                LanClient.this.report(job.id, ledger("writing", written, ""));
            }
            @Override public void progress(int written, int batchIndex) {
                result = "正在写入 " + written + " / " + job.count + " 条 · 第 "
                        + (batchIndex + 1) + " / " + job.batchCount + " 批，请保持前台。";
            }
        });
        int written = outcome.written;
        String status = outcome.error == null ? "completed" : "failed";
        String errorText = outcome.error == null ? "" : outcome.error.getClass().getSimpleName() + "：" + outcome.error.getMessage();
        try {
            imports.finishImport(deviceId, job.id, status);
            save(key(job.id), ledger(status, written, errorText).toString());
        } catch (Exception error) {
            // A failed commit may still update the in-memory preferences map. Do not infer
            // durability by reading it back, and never accept another task in this process.
            blockStorage(error);
            busy = false;
            return;
        }
        current = null;
        result = "任务已" + (outcome.error == null ? "完成" : "停止") + "，成功写入 " + written + " / " + job.count + " 条。"
                + (errorText.isEmpty() ? "" : "\n" + errorText)
                + "\n请恢复原短信应用查看收件箱；结果等待同步电脑。";
        try {
            flushResults();
        } catch (Exception error) {
            connection = "结果待回传：" + error.getMessage();
        } finally { busy = false; }
    }

    private String key(String id) { return "job:" + baseUrl + ":" + deviceId + ":" + id; }

    private JSONObject ledger(String status, int written, String error) throws JSONException {
        return new JSONObject().put("status", status).put("written", written)
                .put("error", error).put("acked", false);
    }

    private void flushResults() throws Exception {
        if (token.isEmpty()) return;
        String prefix = "job:" + baseUrl + ":" + deviceId + ":";
        for (Map.Entry<String, ?> entry : prefs.getAll().entrySet()) {
            if (!entry.getKey().startsWith(prefix)) continue;
            JSONObject ledger = new JSONObject((String) entry.getValue());
            if (ledger.optBoolean("acked") || "writing".equals(ledger.getString("status"))) continue;
            report(entry.getKey().substring(prefix.length()), ledger);
            ledger.put("acked", true);
            save(entry.getKey(), ledger.toString());
        }
    }

    private void report(String id, JSONObject ledger) throws IOException, JSONException {
        JSONObject request = new JSONObject().put("status", ledger.getString("status"))
                .put("written", ledger.getInt("written")).put("error", ledger.getString("error"));
        http(baseUrl, token, "/api/device/jobs/" + id + "/status", request);
    }

    private void save(String key, String value) throws IOException {
        if (storageBlocked) throw new IOException("本地任务记录异常，已禁止执行");
        if (!prefs.edit().putString(key, value).commit()) {
            IOException error = new IOException("任务状态无法持久保存，已停止执行");
            blockStorage(error);
            throw error;
        }
    }

    private void blockStorage(Exception error) {
        storageBlocked = true;
        result = "本地任务记录异常，已停用任务执行以避免重复：" + error.getMessage();
    }

    private JSONObject http(String base, String bearer, String path, JSONObject data)
            throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new java.net.URL(base + path).openConnection();
        connection.setInstanceFollowRedirects(false);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(10000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("X-SMS-Protocol", "5");
        if (!bearer.isEmpty()) connection.setRequestProperty("Authorization", "Bearer " + bearer);
        try {
            if (data != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] payload = data.toString().getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(payload.length);
                try (java.io.OutputStream stream = connection.getOutputStream()) { stream.write(payload); }
            }
            int code = connection.getResponseCode();
            if (code >= 300 && code < 400) throw new IOException("拒绝 HTTP 重定向");
            InputStream source = code >= 400 ? connection.getErrorStream() : connection.getInputStream();
            if (source == null) throw new IOException("HTTP " + code);
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (InputStream stream = source) {
                byte[] buffer = new byte[8192];
                int count;
                while ((count = stream.read(buffer)) != -1) {
                    if (bytes.size() + count > 17 * 1024 * 1024) throw new IOException("电脑响应超过 17 MiB");
                    bytes.write(buffer, 0, count);
                }
            }
            JSONObject response = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            if (code < 200 || code >= 300) throw new IOException("HTTP " + code + "：" + response.optString("error", "请求失败"));
            return response;
        } finally { connection.disconnect(); }
    }
}
