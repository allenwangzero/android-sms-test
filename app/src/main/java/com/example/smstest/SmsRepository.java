package com.example.smstest;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.net.Uri;
import android.provider.Telephony;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Queries only content://sms. Deletion candidates are immutable, private SQLite snapshots. */
public final class SmsRepository {
    public interface ActiveCheck { void check() throws Exception; }

    private static final int PAGE_SIZE = 50;
    private static final String[] COLUMNS = {"_id", "address", "body", "date", "type", "read",
            "status", "locked", "protocol", "subject", "service_center", "date_sent", "seen", "thread_id"};
    private final Context context;
    private final Database helper;
    private final ActiveCheck active;
    // Also retain the known lower bound if a provider deletion succeeds but SQLite persistence fails.
    private final Map<String, Integer> knownProgress = new HashMap<>();

    public SmsRepository(Context context) {
        this(context, () -> { });
    }

    public SmsRepository(Context context, ActiveCheck active) {
        this.context = context.getApplicationContext();
        this.helper = new Database(this.context);
        this.active = active;
    }

    /** A batch exists before its first provider insertion, including failed/empty attempts. */
    public synchronized void beginImport(String scope, String jobId, int requested) throws Exception {
        validateKey(scope, jobId);
        requireRead();
        requireDefault();
        if (requested < 1 || requested > 100000) throw new IOException("导入批次数量无效");
        ContentValues values = new ContentValues();
        values.put("scope", scope); values.put("job_id", jobId);
        values.put("created_at", System.currentTimeMillis()); values.put("requested", requested);
        values.put("status", "writing"); values.put("recorded", 0);
        helper.getWritableDatabase().insertOrThrow("import_batches", null, values);
    }

    /** Only the provider-returned ID establishes ownership; never infer it from message contents. */
    public synchronized void recordInserted(String scope, String jobId, int position, Uri inserted) throws Exception {
        validateKey(scope, jobId);
        requireRead();
        requireDefault();
        if (inserted == null || !"content".equals(inserted.getScheme())
                || !"sms".equals(inserted.getAuthority()) || inserted.getLastPathSegment() == null
                || !inserted.getLastPathSegment().matches("[1-9][0-9]*") || position < 1) {
            throw new IOException("短信已写入但系统返回的记录标识无效，已停止；此条无法按批次清理");
        }
        String[] raw;
        try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, COLUMNS,
                "_id=?", new String[] {inserted.getLastPathSegment()}, null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                throw new IOException("短信已写入但无法回读记录，已停止；此条无法按批次清理");
            }
            raw = read(cursor);
            if (!inserted.getLastPathSegment().equals(raw[0]) || cursor.moveToNext()) {
                throw new IOException("短信已写入但回读标识不匹配，已停止");
            }
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor batch = db.rawQuery("SELECT requested,status,recorded FROM import_batches WHERE scope=? AND job_id=?",
                    new String[] {scope, jobId})) {
                if (!batch.moveToFirst() || !"writing".equals(batch.getString(1))
                        || batch.getInt(2) != position - 1 || position > batch.getInt(0)) {
                    throw new IOException("短信已写入但批次记录顺序异常，已停止");
                }
            }
            ContentValues values = new ContentValues();
            values.put("scope", scope); values.put("job_id", jobId); values.put("position", position);
            values.put("sms_id", raw[0]); values.put("raw", encode(raw));
            values.put("fingerprint", SmsFingerprint.of(raw));
            db.insertOrThrow("import_items", null, values);
            ContentValues progress = new ContentValues(); progress.put("recorded", position);
            if (db.update("import_batches", progress, "scope=? AND job_id=? AND recorded=? AND status='writing'",
                    new String[] {scope, jobId, Integer.toString(position - 1)}) != 1) {
                throw new IOException("导入批次记录计数保存失败，已停止");
            }
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized void finishImport(String scope, String jobId, String status) throws Exception {
        validateKey(scope, jobId);
        if (!"completed".equals(status) && !"failed".equals(status)) throw new IOException("导入批次状态无效");
        ContentValues values = new ContentValues(); values.put("status", status);
        // No row is possible when the initial ledger write failed, before beginImport was called.
        helper.getWritableDatabase().update("import_batches", values, "scope=? AND job_id=? AND status='writing'",
                new String[] {scope, jobId});
    }

    public synchronized void recoverImports() {
        ContentValues values = new ContentValues(); values.put("status", "interrupted");
        helper.getWritableDatabase().update("import_batches", values, "status='writing'", null);
    }

    public synchronized JSONObject listBatches(String scope, int page) throws Exception {
        validateKey(scope, scope);
        if (page < 0 || page > 10000000) throw new IOException("导入批次页码无效");
        SQLiteDatabase db = helper.getReadableDatabase();
        int total;
        try (Cursor cursor = db.rawQuery("SELECT COUNT(*) FROM import_batches WHERE scope=?", new String[] {scope})) {
            if (!cursor.moveToFirst()) throw new IOException("无法读取导入批次总数");
            total = cursor.getInt(0);
        }
        JSONArray batches = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT job_id,created_at,requested,status,recorded FROM import_batches WHERE scope=? ORDER BY created_at DESC,job_id DESC LIMIT ? OFFSET ?",
                new String[] {scope, Integer.toString(PAGE_SIZE), Long.toString((long) page * PAGE_SIZE)})) {
            while (cursor.moveToNext()) {
                batches.put(new JSONObject().put("jobId", cursor.getString(0)).put("createdAt", cursor.getLong(1))
                        .put("requested", cursor.getInt(2)).put("status", cursor.getString(3)).put("recorded", cursor.getInt(4)));
            }
        }
        return new JSONObject().put("total", total).put("page", page).put("pageSize", PAGE_SIZE).put("batches", batches);
    }

    public synchronized JSONObject list(JSONObject filters, int page) throws Exception {
        requireRead();
        active.check();
        if (page < 0 || page > 10000000) throw new IOException("短信页码无效");
        Query query = filters(filters);
        JSONArray rows = new JSONArray();
        int total = 0;
        long start = (long) page * PAGE_SIZE;
        try (Cursor cursor = query(query)) {
            while (cursor.moveToNext()) {
                active.check();
                if (total >= start && rows.length() < PAGE_SIZE) rows.put(preview(read(cursor)));
                total++;
            }
        }
        return new JSONObject().put("total", total).put("page", page)
                .put("pageSize", PAGE_SIZE).put("rows", rows);
    }

    public synchronized JSONObject prepare(String scope, String requestId, JSONObject filters,
            JSONObject selection) throws Exception {
        requireRead();
        active.check();
        validateKey(scope, requestId);
        String mode = selection.getString("mode");
        if (!mode.equals("selected") && !mode.equals("filtered") && !mode.equals("all") && !mode.equals("batch")) {
            throw new IOException("删除选择方式无效");
        }
        // Non-default apps can receive the provider's restricted inbox/sent view.
        if (mode.equals("all") || mode.equals("batch")) requireDefault();
        Query query = mode.equals("all") || mode.equals("batch") ? new Query() : filters(filters);
        String jobId = mode.equals("batch") ? selection.getString("jobId") : "";
        if (mode.equals("batch")) validateKey(scope, jobId);
        Map<String, String> selected = new HashMap<>();
        if (mode.equals("selected")) {
            JSONArray items = selection.getJSONArray("items");
            if (items.length() == 0 || items.length() > 100000) throw new IOException("请选择 1 至 100000 条短信");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String id = item.getString("id");
                String fingerprint = item.getString("fingerprint");
                if (!id.matches("[1-9][0-9]*") || !fingerprint.matches("[0-9a-f]{64}")
                        || selected.put(id, fingerprint) != null) throw new IOException("勾选短信标识无效或重复");
            }
        }
        if (mode.equals("batch")) {
            SQLiteDatabase history = helper.getReadableDatabase();
            try (Cursor batch = history.rawQuery("SELECT status FROM import_batches WHERE scope=? AND job_id=?", new String[] {scope, jobId})) {
                if (!batch.moveToFirst()) throw new IOException("此批次没有手机导入记录；旧版数据不能推断归属");
                if ("writing".equals(batch.getString(0))) throw new IOException("此批次仍在写入，请完成后再清理");
            }
            try (Cursor items = history.rawQuery("SELECT sms_id,fingerprint FROM import_items WHERE scope=? AND job_id=? ORDER BY position", new String[] {scope, jobId})) {
                while (items.moveToNext()) {
                    active.check();
                    if (selected.put(items.getString(0), items.getString(1)) != null) throw new IOException("批次短信标识重复，已停止");
                }
            }
        }
        List<String> signature = new ArrayList<>();
        signature.add(mode);
        signature.add(jobId);
        signature.add(query.sql());
        signature.addAll(query.args);
        List<String> ids = new ArrayList<>(selected.keySet());
        java.util.Collections.sort(ids);
        for (String id : ids) { signature.add(id); signature.add(selected.get(id)); }
        String requestHash = SmsFingerprint.of(signature.toArray(new String[0]));
        SmsBatchSelection batchSelection = mode.equals("batch") ? new SmsBatchSelection(selected) : null;
        if (batchSelection != null) selected.clear();
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            try (Cursor existing = db.rawQuery("SELECT request_hash FROM tasks WHERE scope=? AND request_id=?",
                    new String[] {scope, requestId})) {
                if (existing.moveToFirst()) {
                    if (!requestHash.equals(existing.getString(0))) throw new IOException("删除任务参数已改变，请重新创建任务");
                    JSONObject result = describe(db, scope, requestId);
                    db.setTransactionSuccessful();
                    return result;
                }
            }
            ContentValues task = new ContentValues();
            task.put("scope", scope); task.put("request_id", requestId);
            task.put("request_hash", requestHash); task.put("mode", mode);
            task.put("total", 0); task.put("deleted", 0);
            task.put("missing", 0); task.put("changed", 0); task.put("job_id", jobId);
            db.insertOrThrow("tasks", null, task);
            int count = 0;
            try (Cursor cursor = query(query)) {
                while (cursor.moveToNext()) {
                    active.check();
                    String[] raw = read(cursor);
                    if (mode.equals("selected") && !selected.containsKey(raw[0])) continue;
                    String fingerprint = SmsFingerprint.of(raw);
                    if (mode.equals("selected") && !fingerprint.equals(selected.remove(raw[0]))) {
                        throw new IOException("勾选短信已发生变化，请刷新列表后重新选择");
                    }
                    if (batchSelection != null && !batchSelection.matches(raw[0], fingerprint)) continue;
                    ContentValues item = new ContentValues();
                    item.put("scope", scope); item.put("request_id", requestId); item.put("position", count);
                    item.put("raw", encode(raw)); item.put("fingerprint", fingerprint);
                    db.insertOrThrow("items", null, item);
                    count++;
                }
            }
            if (mode.equals("selected") && !selected.isEmpty()) throw new IOException("勾选短信已消失或不再符合筛选条件，请刷新列表");
            ContentValues values = new ContentValues(); values.put("total", count);
            values.put("missing", batchSelection == null ? 0 : batchSelection.missing());
            values.put("changed", batchSelection == null ? 0 : batchSelection.changed());
            db.update("tasks", values, "scope=? AND request_id=?", new String[] {scope, requestId});
            JSONObject result = describe(db, scope, requestId);
            active.check();
            db.setTransactionSuccessful();
            return result;
        } finally { db.endTransaction(); }
    }

    public synchronized int count(String scope, String requestId) throws Exception {
        return taskNumber(scope, requestId, "total");
    }

    public synchronized int getProgress(String scope, String requestId) throws Exception {
        validateKey(scope, requestId);
        Integer known = knownProgress.get(scope + "/" + requestId);
        try {
            return Math.max(taskNumber(scope, requestId, "deleted"), known == null ? 0 : known);
        } catch (Exception error) {
            if (known != null) return known;
            throw error;
        }
    }

    public synchronized int deleteBatch(String scope, String requestId, int offset, int limit,
            ActiveCheck check) throws Exception {
        validateKey(scope, requestId);
        if (limit < 1 || limit > 200 || offset < 0) throw new IOException("删除批次参数无效");
        SQLiteDatabase db = helper.getWritableDatabase();
        int persisted = taskNumber(scope, requestId, "deleted");
        if (persisted != offset || getProgress(scope, requestId) != persisted) {
            throw new IOException("删除进度不一致，已停止以防止重复执行");
        }
        int total = count(scope, requestId);
        if (offset > total) throw new IOException("删除进度超过任务总数");
        int deleted = 0;
        try (Cursor cursor = db.rawQuery("SELECT position,raw,fingerprint FROM items WHERE scope=? AND request_id=? AND position>=? ORDER BY position LIMIT ?",
                new String[] {scope, requestId, Integer.toString(offset), Integer.toString(limit)})) {
            while (cursor.moveToNext()) {
                check.check();
                requireRead();
                requireDefault();
                if (cursor.getInt(0) != offset + deleted) throw new IOException("删除快照不完整，已停止");
                String[] raw = decode(cursor.getString(1));
                String fingerprint = cursor.getString(2);
                Query exact = exact(raw);
                try (Cursor current = context.getContentResolver().query(Telephony.Sms.CONTENT_URI,
                        COLUMNS, "_id=?", new String[] {raw[0]}, null)) {
                    if (current == null) throw new IOException("系统未返回短信查询结果");
                    if (!current.moveToFirst() || !fingerprint.equals(SmsFingerprint.of(read(current)))) {
                        throw new IOException("短信 " + raw[0] + " 已变化或不存在，删除已停止，请重新读取列表");
                    }
                }
                check.check();
                requireDefault();
                int affected = context.getContentResolver().delete(Telephony.Sms.CONTENT_URI,
                        exact.sql(), exact.args.toArray(new String[0]));
                if (affected != 1) throw new IOException("短信 " + raw[0] + " 在删除前已变化，删除已停止");
                deleted++;
                int progress = offset + deleted;
                knownProgress.put(scope + "/" + requestId, progress);
                ContentValues update = new ContentValues(); update.put("deleted", progress);
                if (db.update("tasks", update, "scope=? AND request_id=? AND deleted=?",
                        new String[] {scope, requestId, Integer.toString(progress - 1)}) != 1) {
                    throw new IOException("短信已删除但进度保存失败，已停止，禁止自动重试");
                }
            }
        }
        if (deleted != Math.min(limit, total - offset)) throw new IOException("删除快照缺失，已停止");
        return deleted;
    }

    private int taskNumber(String scope, String id, String column) throws Exception {
        validateKey(scope, id);
        try (Cursor cursor = helper.getReadableDatabase().rawQuery("SELECT " + column
                + " FROM tasks WHERE scope=? AND request_id=?", new String[] {scope, id})) {
            if (!cursor.moveToFirst()) throw new IOException("删除快照不存在，请重新创建任务");
            return cursor.getInt(0);
        }
    }

    private JSONObject describe(SQLiteDatabase db, String scope, String id) throws Exception {
        JSONObject result = new JSONObject();
        try (Cursor cursor = db.rawQuery("SELECT total,mode,missing,changed,job_id FROM tasks WHERE scope=? AND request_id=?", new String[] {scope, id})) {
            if (!cursor.moveToFirst()) throw new IOException("删除快照不存在");
            result.put("count", cursor.getInt(0)).put("selectionMode", cursor.getString(1));
            if ("batch".equals(cursor.getString(1))) {
                result.put("missing", cursor.getInt(2)).put("changed", cursor.getInt(3)).put("jobId", cursor.getString(4));
            }
        }
        JSONArray preview = new JSONArray();
        try (Cursor cursor = db.rawQuery("SELECT raw FROM items WHERE scope=? AND request_id=? ORDER BY position LIMIT 10", new String[] {scope, id})) {
            while (cursor.moveToNext()) preview.put(preview(decode(cursor.getString(0))));
        }
        return result.put("preview", preview);
    }

    private Cursor query(Query query) throws IOException {
        Cursor cursor = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, COLUMNS,
                query.sql(), query.args.toArray(new String[0]), "date DESC, _id DESC");
        if (cursor == null) throw new IOException("系统未返回短信查询结果");
        return cursor;
    }

    private static Query filters(JSONObject filters) throws Exception {
        Query query = new Query();
        String sender = filters.optString("sender", "");
        String keyword = filters.optString("keyword", "");
        if (!sender.isEmpty()) query.add("address = ?", sender);
        if (!keyword.isEmpty()) query.add("instr(COALESCE(body,''), ?) > 0", keyword);
        for (String key : new String[] {"dateFrom", "dateTo", "read", "status", "locked"}) {
            if (key.equals("locked") && !filters.has(key)) { query.add("COALESCE(locked,0) = CAST(? AS INTEGER)", "0"); continue; }
            if (!filters.has(key) || filters.isNull(key)) continue;
            Object value = filters.get(key);
            if (!(value instanceof Number) || !value.toString().matches("-?[0-9]+")) throw new IOException("筛选字段无效：" + key);
            if (key.equals("dateFrom")) query.add("date >= ?", value.toString());
            else if (key.equals("dateTo")) query.add("date <= ?", value.toString());
            else query.add("COALESCE(" + key + "," + (key.equals("status") ? "-1" : "0") + ") = CAST(? AS INTEGER)", value.toString());
        }
        return query;
    }

    private static Query exact(String[] raw) {
        Query query = new Query();
        for (int i = 0; i < COLUMNS.length; i++) {
            if (raw[i] == null) query.parts.add(COLUMNS[i] + " IS NULL");
            else query.add(COLUMNS[i] + " IS ?", raw[i]);
        }
        return query;
    }

    private static String[] read(Cursor cursor) {
        String[] raw = new String[COLUMNS.length];
        for (int i = 0; i < raw.length; i++) raw[i] = cursor.isNull(i) ? null : cursor.getString(i);
        return raw;
    }

    private static JSONObject preview(String[] raw) throws Exception {
        return new JSONObject().put("id", raw[0]).put("fingerprint", SmsFingerprint.of(raw))
                .put("sender", truncate(raw[1], 200)).put("body", truncate(raw[2], 2000))
                .put("timestamp", number(raw[3], 0)).put("type", number(raw[4], 0))
                .put("read", number(raw[5], 0)).put("status", number(raw[6], -1)).put("locked", number(raw[7], 0));
    }

    private static long number(String raw, long fallback) { return raw == null ? fallback : Long.parseLong(raw); }
    private static String truncate(String value, int max) {
        if (value == null) return "";
        if (value.length() <= max) return value;
        int end = Character.isHighSurrogate(value.charAt(max - 1)) ? max - 1 : max;
        return value.substring(0, end);
    }
    private static String encode(String[] raw) {
        JSONArray array = new JSONArray();
        for (String value : raw) array.put(value == null ? JSONObject.NULL : value);
        return array.toString();
    }
    private static String[] decode(String json) throws Exception {
        JSONArray array = new JSONArray(json);
        if (array.length() != COLUMNS.length) throw new IOException("删除快照字段不完整");
        String[] raw = new String[COLUMNS.length];
        for (int i = 0; i < raw.length; i++) raw[i] = array.isNull(i) ? null : array.getString(i);
        return raw;
    }
    private void requireRead() {
        if (context.checkSelfPermission(Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            throw new SecurityException("请先在手机授予读取短信权限");
        }
    }
    private void requireDefault() {
        if (!context.getPackageName().equals(Telephony.Sms.getDefaultSmsPackage(context))) {
            throw new SecurityException("默认短信应用已切换，删除已停止");
        }
    }
    private static void validateKey(String scope, String id) throws IOException {
        try {
            if (!UUID.fromString(scope).toString().equals(scope) || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new IOException("删除任务标识无效", error); }
    }
    private static final class Query {
        final List<String> parts = new ArrayList<>();
        final List<String> args = new ArrayList<>();
        void add(String sql, String arg) { parts.add(sql); args.add(arg); }
        String sql() { return parts.isEmpty() ? "1=1" : String.join(" AND ", parts); }
    }
    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context, "sms-management.db", null, 2); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE tasks(scope TEXT NOT NULL,request_id TEXT NOT NULL,request_hash TEXT NOT NULL,mode TEXT NOT NULL,total INTEGER NOT NULL,deleted INTEGER NOT NULL,missing INTEGER NOT NULL DEFAULT 0,changed INTEGER NOT NULL DEFAULT 0,job_id TEXT NOT NULL DEFAULT '',PRIMARY KEY(scope,request_id))");
            db.execSQL("CREATE TABLE items(scope TEXT NOT NULL,request_id TEXT NOT NULL,position INTEGER NOT NULL,raw TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(scope,request_id,position))");
            createImportTables(db);
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            if (oldVersion == 1 && newVersion == 2) {
                db.execSQL("ALTER TABLE tasks ADD COLUMN missing INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE tasks ADD COLUMN changed INTEGER NOT NULL DEFAULT 0");
                db.execSQL("ALTER TABLE tasks ADD COLUMN job_id TEXT NOT NULL DEFAULT ''");
                createImportTables(db);
            } else throw new IllegalStateException("不支持短信管理数据库版本迁移");
        }
        private void createImportTables(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE import_batches(scope TEXT NOT NULL,job_id TEXT NOT NULL,created_at INTEGER NOT NULL,requested INTEGER NOT NULL,status TEXT NOT NULL,recorded INTEGER NOT NULL,PRIMARY KEY(scope,job_id))");
            db.execSQL("CREATE TABLE import_items(scope TEXT NOT NULL,job_id TEXT NOT NULL,position INTEGER NOT NULL,sms_id TEXT NOT NULL,raw TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(scope,job_id,position),UNIQUE(scope,job_id,sms_id))");
            db.execSQL("CREATE INDEX import_batches_created ON import_batches(scope,created_at DESC,job_id DESC)");
        }
    }
}
