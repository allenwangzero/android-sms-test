package com.example.smstest;

/** Executes only a freshly confirmed immutable snapshot; there is no resume entry point. */
public final class SmsDeletion {
    public static final int BATCH_SIZE = 200;
    private SmsDeletion() { }

    public interface Operations {
        void checkActive() throws Exception;
        int deleteBatch(int offset, int limit) throws Exception;
        int deleted() throws Exception;
        void persist(int deleted) throws Exception;
        void report(int deleted) throws Exception;
        void progress(int deleted);
    }

    public static final class Outcome {
        public final int deleted;
        public final Exception error;
        Outcome(int deleted, Exception error) { this.deleted = deleted; this.error = error; }
    }

    public static Outcome run(int count, Operations operations) {
        int deleted = 0;
        try {
            if (count < 0) throw new IllegalArgumentException("删除数量无效");
            if (operations.deleted() != 0) throw new IllegalStateException("已有删除记录，禁止重复执行");
            while (deleted < count) {
                operations.checkActive();
                int limit = Math.min(BATCH_SIZE, count - deleted);
                int expected = deleted + limit;
                int changed = operations.deleteBatch(deleted, limit);
                int stored = operations.deleted();
                if (stored < deleted || stored > count) throw new IllegalStateException("删除进度异常");
                deleted = stored;
                if (changed != limit || deleted != expected) {
                    throw new IllegalStateException("删除批次未完整完成");
                }
                operations.persist(deleted);
                operations.progress(deleted);
                operations.report(deleted);
            }
            return new Outcome(deleted, null);
        } catch (Exception error) {
            try {
                int stored = operations.deleted();
                if (stored >= deleted && stored <= count) deleted = stored;
            } catch (Exception progressError) { error.addSuppressed(progressError); }
            return new Outcome(deleted, error);
        }
    }
}
