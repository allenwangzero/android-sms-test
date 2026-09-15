package com.example.smstest;

import java.io.IOException;
import java.util.Map;

public final class SmsExporterTest {
    private static final class Fake implements SmsExporter.Operations {
        int uploaded, batches, processed;
        int failUpload = -1, failProgress = -1;
        boolean active = true;
        @Override public void checkActive() throws Exception {
            if (!active) throw new IOException("background");
        }
        @Override public void upload(int index, int offset, int count) throws Exception {
            check(index == batches && offset == uploaded, "ordered, no replay");
            check(count > 0 && count <= 500, "bounded batch");
            if (index == failUpload) throw new IOException("upload failed");
            uploaded += count; batches++;
        }
        @Override public void progress(int count) throws Exception {
            check(count == uploaded, "report acknowledged upload only");
            if (count == failProgress) throw new IOException("report failed");
            processed = count;
        }
    }
    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
    }
    private static String[] raw() {
        return new String[] {"123", "sender", "full message", "1750000000000", "1", "0", "-1", "1",
                null, null, "+123", "1750000000000", "1", "33"};
    }
    private static void invalid(int column, String value, String field) throws Exception {
        String[] values = raw(); values[column] = value;
        try { SmsExporter.fields(values); throw new AssertionError("accepted invalid " + field); }
        catch (IOException error) { check(error.getMessage().contains("123") && error.getMessage().contains(field), "ID and field error"); }
    }
    public static void main(String[] args) throws Exception {
        int cases = 0;
        for (int count : new int[] {0, 1, 499, 500, 501, 10001, 100000}) {
            Fake fake = new Fake(); SmsExporter.run(count, fake);
            check(fake.uploaded == count && fake.processed == count && fake.batches == (count + 499) / 500, "complete " + count);
            cases++;
        }
        for (int count : new int[] {-1, 100001}) {
            Fake fake = new Fake();
            try { SmsExporter.run(count, fake); throw new AssertionError("bad count"); }
            catch (IOException expected) { check(fake.uploaded == 0, "invalid count never uploads"); }
            cases++;
        }
        Fake upload = new Fake(); upload.failUpload = 1;
        try { SmsExporter.run(2000, upload); throw new AssertionError("continued failed upload"); }
        catch (IOException expected) { check(upload.processed == 500 && upload.batches == 1, "stops failed upload"); } cases++;
        Fake progress = new Fake(); progress.failProgress = 500;
        try { SmsExporter.run(2000, progress); throw new AssertionError("continued failed report"); }
        catch (IOException expected) { check(progress.uploaded == 500 && progress.batches == 1, "stops failed report"); } cases++;
        Fake inactive = new Fake(); inactive.active = false;
        try { SmsExporter.run(500, inactive); throw new AssertionError("background upload"); }
        catch (IOException expected) { check(inactive.uploaded == 0, "background prevented"); } cases++;
        String[] values = raw(); values[2] = "😀".repeat(4000); values[9] = "subject";
        Map<String, Object> fields = SmsExporter.fields(values);
        check(fields.get("body").equals(values[2]), "full Unicode body preserved, not preview");
        check(fields.containsKey("protocol") && fields.get("protocol") == null, "null preserved");
        check(fields.containsKey("toa") && fields.get("toa") == null
                && fields.containsKey("sc_toa") && fields.get("sc_toa") == null, "canonical unsupported null columns included in snapshot size");
        check(fields.get("date_sent").equals(1750000000000L) && fields.get("seen").equals(1L), "sent and seen preserved");
        check(!fields.containsKey("thread_id") && !fields.containsKey("id"), "device identities omitted"); cases++;
        invalid(1, " ", "sender"); invalid(1, "a".repeat(101), "sender");
        invalid(2, null, "body"); invalid(2, "a".repeat(4001), "body");
        invalid(3, "-1", "timestamp"); invalid(3, "4102444800001", "timestamp");
        invalid(4, "2", "type"); invalid(5, "2", "read"); invalid(6, "256", "status");
        invalid(7, null, "locked"); invalid(8, "256", "protocol"); invalid(9, "a".repeat(4001), "subject");
        invalid(10, "a".repeat(101), "service_center"); invalid(11, "-1", "date_sent");
        invalid(11, "1.0", "date_sent"); invalid(12, "2", "seen");
        invalid(2, String.valueOf((char) 0xD800), "body");
        invalid(9, String.valueOf((char) 0xDC00), "subject");
        cases += 18;
        StringBuilder lifecycle = new StringBuilder();
        Exception cleanup = SmsExporter.finish(new SmsExporter.LedgerCleanup() {
            @Override public void persist() { lifecycle.append("saved;"); }
            @Override public void clearSnapshot() { lifecycle.append("cleared;"); }
        });
        check(cleanup == null && lifecycle.toString().equals("saved;cleared;"), "persist terminal before clear"); cases++;
        lifecycle.setLength(0);
        cleanup = SmsExporter.finish(new SmsExporter.LedgerCleanup() {
            @Override public void persist() { lifecycle.append("saved;"); }
            @Override public void clearSnapshot() throws Exception { throw new IOException("disk cleanup failure"); }
        });
        check(cleanup != null && lifecycle.toString().equals("saved;"), "cleanup failure retains terminal without replay"); cases++;
        lifecycle.setLength(0);
        try {
            SmsExporter.finish(new SmsExporter.LedgerCleanup() {
                @Override public void persist() throws Exception { throw new IOException("ledger failure"); }
                @Override public void clearSnapshot() { lifecycle.append("cleared;"); }
            });
            throw new AssertionError("ledger persistence failure swallowed");
        } catch (IOException expected) { check(lifecycle.length() == 0, "failed persistence never clears snapshot"); } cases++;
        System.out.println("SmsExporterTest: " + cases + " cases passed");
    }
}
