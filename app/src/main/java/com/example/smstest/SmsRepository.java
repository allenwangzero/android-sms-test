package com.example.smstest;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
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
        if (!mode.equals("selected") && !mode.equals("filtered") && !mode.equals("all")) {
            throw new IOException("删除选择方式无效");
        }
        // Non-default apps can receive the provider's restricted inbox/sent view.
        if (mode.equals("all")) requireDefault();
        Query query = mode.equals("all") ? new Query() : filters(filters);
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
        List<String> signature = new ArrayList<>();
        signature.add(mode);
        signature.add(query.sql());
        signature.addAll(query.args);
        List<String> ids = new ArrayList<>(selected.keySet());
        java.util.Collections.sort(ids);
        for (String id : ids) { signature.add(id); signature.add(selected.get(id)); }
        String requestHash = SmsFingerprint.of(signature.toArray(new String[0]));
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
                    ContentValues item = new ContentValues();
                    item.put("scope", scope); item.put("request_id", requestId); item.put("position", count);
                    item.put("raw", encode(raw)); item.put("fingerprint", fingerprint);
                    db.insertOrThrow("items", null, item);
                    count++;
                }
            }
            if (!selected.isEmpty()) throw new IOException("勾选短信已消失或不再符合筛选条件，请刷新列表");
            ContentValues values = new ContentValues(); values.put("total", count);
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
        try (Cursor cursor = db.rawQuery("SELECT total,mode FROM tasks WHERE scope=? AND request_id=?", new String[] {scope, id})) {
            if (!cursor.moveToFirst()) throw new IOException("删除快照不存在");
            result.put("count", cursor.getInt(0)).put("selectionMode", cursor.getString(1));
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
        Database(Context context) { super(context, "sms-management.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE tasks(scope TEXT NOT NULL,request_id TEXT NOT NULL,request_hash TEXT NOT NULL,mode TEXT NOT NULL,total INTEGER NOT NULL,deleted INTEGER NOT NULL,PRIMARY KEY(scope,request_id))");
            db.execSQL("CREATE TABLE items(scope TEXT NOT NULL,request_id TEXT NOT NULL,position INTEGER NOT NULL,raw TEXT NOT NULL,fingerprint TEXT NOT NULL,PRIMARY KEY(scope,request_id,position))");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("不支持删除快照数据库版本迁移");
        }
    }
}
