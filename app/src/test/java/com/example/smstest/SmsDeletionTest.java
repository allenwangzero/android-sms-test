package com.example.smstest;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public final class SmsDeletionTest {
    private static final class Fake implements SmsDeletion.Operations {
        int deleted;
        int fetches;
        int failAt = -1;
        int reportFailAt = -1;
        int persistFailAt = -1;
        boolean active = true;
        boolean shortBatch;
        boolean wrongProgress;
        final List<Integer> limits = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        @Override public void checkActive() throws Exception {
            if (!active) throw new IOException("background");
        }
        @Override public int deleteBatch(int offset, int limit) throws Exception {
            check(offset == deleted, "sequential snapshot offset");
            fetches++;
            limits.add(limit);
            int actual = shortBatch ? limit - 1 : limit;
            for (int i = 0; i < actual; i++) {
                checkActive();
                if (deleted == failAt) throw new IOException("provider failure");
                deleted++;
            }
            if (wrongProgress) deleted--;
            return actual;
        }
        @Override public int deleted() { return deleted; }
        @Override public void persist(int count) throws Exception {
            events.add("persist:" + count);
            if (count == persistFailAt) throw new IOException("storage failure");
        }
        @Override public void report(int count) throws Exception {
            events.add("report:" + count);
            if (count == reportFailAt) throw new IOException("network failure");
        }
        @Override public void progress(int count) { events.add("progress:" + count); }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] arguments) {
        int cases = 0;
        for (int count : new int[]{0, 1, 199, 200, 201, 401, 12345}) {
            Fake fake = new Fake();
            SmsDeletion.Outcome result = SmsDeletion.run(count, fake);
            check(result.error == null && result.deleted == count, "complete count " + count);
            check(fake.fetches == (count + 199) / 200, "batch count");
            for (int limit : fake.limits) check(limit > 0 && limit <= 200, "batch bounds");
            for (int i = 0; i < fake.events.size(); i += 3) {
                check(fake.events.get(i).startsWith("persist:"), "persist before report");
                check(fake.events.get(i + 2).startsWith("report:"), "report before next batch");
            }
            cases++;
        }
        Fake provider = new Fake(); provider.failAt = 250;
        SmsDeletion.Outcome failed = SmsDeletion.run(1000, provider);
        check(failed.error != null && failed.deleted == 250 && provider.fetches == 2, "partial batch stops with durable progress"); cases++;
        Fake network = new Fake(); network.reportFailAt = 200;
        failed = SmsDeletion.run(1000, network);
        check(failed.error != null && failed.deleted == 200 && network.fetches == 1, "no next batch after failed report"); cases++;
        Fake storage = new Fake(); storage.persistFailAt = 200;
        failed = SmsDeletion.run(1000, storage);
        check(failed.error != null && failed.deleted == 200 && storage.fetches == 1 && storage.events.size() == 1, "failed persistence prevents report and continuation"); cases++;
        Fake background = new Fake(); background.active = false;
        failed = SmsDeletion.run(1000, background);
        check(failed.error != null && failed.deleted == 0 && background.fetches == 0, "background stops before provider"); cases++;
        Fake replay = new Fake(); replay.deleted = 200;
        failed = SmsDeletion.run(1000, replay);
        check(failed.error != null && failed.deleted == 200 && replay.fetches == 0, "existing snapshot never resumed"); cases++;
        Fake shortBatch = new Fake(); shortBatch.shortBatch = true;
        failed = SmsDeletion.run(1000, shortBatch);
        check(failed.error != null && failed.deleted == 199 && shortBatch.fetches == 1, "partial returned batch stops"); cases++;
        Fake mismatch = new Fake(); mismatch.wrongProgress = true;
        failed = SmsDeletion.run(1000, mismatch);
        check(failed.error != null && mismatch.fetches == 1, "progress mismatch stops"); cases++;
        Fake invalid = new Fake();
        failed = SmsDeletion.run(-1, invalid);
        check(failed.error != null && invalid.fetches == 0, "negative count rejected"); cases++;
        System.out.println("SmsDeletionTest: " + cases + " cases passed");
    }
}
