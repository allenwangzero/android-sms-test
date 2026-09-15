package com.example.smstest;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.provider.Telephony;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;

/** Private immutable full-text snapshots; a failed freeze rolls back every row. */
public final class SmsExportRepository {
    private static final String[] COLUMNS = {"_id", "address", "body", "date", "type", "read",
            "status", "locked", "protocol", "subject", "service_center", "date_sent", "seen", "thread_id"};
    private final Context context;
    private final Database helper;
    private final SmsRepository.ActiveCheck active;
    public SmsExportRepository(Context context, SmsRepository.ActiveCheck active) {
        this.context = context.getApplicationContext();
        this.helper = new Database(this.context);
        this.active = active;
    }

    public synchronized int freeze(String scope, String id, JSONObject filters, JSONObject selection) throws Exception {
        validateKey(scope, id);
        active.check();
        String mode = selection.getString("mode");
        if (!mode.equals("selected") && !mode.equals("filtered") && !mode.equals("all")) {
            throw new IOException("导出选择方式无效");
        }
        Query query = mode.equals("filtered") ? filters(filters) : new Query();
        // Selected records are checked explicitly, including non-inbox IDs, rather than silently excluded.
        if (!mode.equals("selected")) query.parts.add("type=1");
        Map<String, String> selected = new HashMap<>();
        if (mode.equals("selected")) {
            JSONArray items = selection.getJSONArray("items");
            if (items.length() < 1 || items.length() > SmsExporter.MAX_COUNT) throw new IOException("请选择 1 至 100000 条短信");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String smsId = item.getString("id"), fingerprint = item.getString("fingerprint");
                if (!smsId.matches("[1-9][0-9]*") || !fingerprint.matches("[0-9a-f]{64}")
                        || selected.put(smsId, fingerprint) != null) throw new IOException("勾选短信标识无效或重复");
            }
        }
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            ContentValues task = new ContentValues(); task.put("scope", scope); task.put("request_id", id); task.put("total", 0);
            db.insertOrThrow("tasks", null, task); // Duplicate IDs never re-read a changed phone dataset.
            int count = 0;
            long bytes = 2; // JSON array delimiters; commas are counted below.
            try (Cursor cursor = context.getContentResolver().query(Telephony.Sms.CONTENT_URI, COLUMNS,
                    query.sql(), query.args.toArray(new String[0]), "date DESC, _id DESC")) {
                if (cursor == null) throw new IOException("系统未返回短信查询结果");
                while (cursor.moveToNext()) {
                    active.check();
                    String smsId = cursor.getString(0);
                    if (mode.equals("selected") && !selected.containsKey(smsId)) continue;
                    String[] raw = new String[COLUMNS.length];
                    for (int i = 0; i < raw.length; i++) raw[i] = cursor.isNull(i) ? null : cursor.getString(i);
                    if (mode.equals("selected") && !SmsFingerprint.of(raw).equals(selected.remove(smsId))) {
                        throw new IOException("勾选短信 " + smsId + " 已变化，请重新读取后导出");
                    }
                    JSONObject row = new JSONObject();
                    for (Map.Entry<String, Object> field : SmsExporter.fields(raw).entrySet()) {
                        row.put(field.getKey(), field.getValue() == null ? JSONObject.NULL : field.getValue());
                    }
                    String json = row.toString();
                    if (json == null) throw new IOException("短信 " + smsId + " 无法编码为 JSON");
                    bytes += json.getBytes(StandardCharsets.UTF_8).length + (count == 0 ? 0 : 1);
                    if (bytes > SmsExporter.MAX_BYTES) throw new IOException("导出数据超过 256 MiB，请缩小筛选范围");
                    if (count >= SmsExporter.MAX_COUNT) throw new IOException("导出数量超过 100000 条，请缩小筛选范围");
                    ContentValues item = new ContentValues(); item.put("scope", scope); item.put("request_id", id);
                    item.put("position", count++); item.put("json", json);
                    db.insertOrThrow("items", null, item);
                }
            }
            if (!selected.isEmpty()) throw new IOException("勾选短信 " + selected.keySet().iterator().next() + " 已不存在，请重新读取后导出");
            ContentValues total = new ContentValues(); total.put("total", count);
            db.update("tasks", total, "scope=? AND request_id=?", new String[] {scope, id});
            active.check();
            db.setTransactionSuccessful();
            return count;
        } finally { db.endTransaction(); }
    }

    /** Only this task's private export data; its terminal ledger is retained separately. */
    public synchronized void clear(String scope, String id) throws Exception {
        validateKey(scope, id);
        SQLiteDatabase db = helper.getWritableDatabase();
        db.beginTransaction();
        try {
            String[] key = {scope, id};
            db.delete("items", "scope=? AND request_id=?", key);
            db.delete("tasks", "scope=? AND request_id=?", key);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }

    public synchronized JSONObject batch(String scope, String id, int offset, int expected) throws Exception {
        validateKey(scope, id);
        active.check();
        if (offset < 0 || expected < 1 || expected > SmsExporter.BATCH_SIZE) throw new IOException("导出批次参数无效");
        JSONArray rows = new JSONArray();
        try (Cursor cursor = helper.getReadableDatabase().rawQuery(
                "SELECT position,json FROM items WHERE scope=? AND request_id=? AND position>=? ORDER BY position LIMIT ?",
                new String[] {scope, id, Integer.toString(offset), Integer.toString(expected)})) {
            while (cursor.moveToNext()) {
                active.check();
                if (cursor.getInt(0) != offset + rows.length()) throw new IOException("导出快照不完整");
                rows.put(new JSONObject(cursor.getString(1)));
            }
        }
        if (rows.length() != expected) throw new IOException("导出快照缺失");
        JSONObject payload = new JSONObject().put("messages", rows);
        if (payload.toString().getBytes(StandardCharsets.UTF_8).length > SmsExporter.MAX_BATCH_BYTES) {
            throw new IOException("导出批次超过 16 MiB，任务已停止");
        }
        return payload;
    }

    private static Query filters(JSONObject filters) throws Exception {
        Query query = new Query();
        String sender = filters.optString("sender", ""), keyword = filters.optString("keyword", "");
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
    private static void validateKey(String scope, String id) throws IOException {
        try {
            if (!UUID.fromString(scope).toString().equals(scope) || !UUID.fromString(id).toString().equals(id)) throw new IllegalArgumentException();
        } catch (IllegalArgumentException error) { throw new IOException("导出任务标识无效", error); }
    }
    private static final class Query {
        final List<String> parts = new ArrayList<>();
        final List<String> args = new ArrayList<>();
        void add(String sql, String arg) { parts.add(sql); args.add(arg); }
        String sql() { return parts.isEmpty() ? "1=1" : String.join(" AND ", parts); }
    }
    private static final class Database extends SQLiteOpenHelper {
        Database(Context context) { super(context, "sms-export.db", null, 1); }
        @Override public void onCreate(SQLiteDatabase db) {
            db.execSQL("CREATE TABLE tasks(scope TEXT NOT NULL,request_id TEXT NOT NULL,total INTEGER NOT NULL,PRIMARY KEY(scope,request_id))");
            db.execSQL("CREATE TABLE items(scope TEXT NOT NULL,request_id TEXT NOT NULL,position INTEGER NOT NULL,json TEXT NOT NULL,PRIMARY KEY(scope,request_id,position))");
        }
        @Override public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            throw new IllegalStateException("不支持导出数据库版本迁移");
        }
    }
}
