package com.example.smstest;

import java.util.HashMap;
import java.util.Map;

public final class SmsBatchSelectionTest {
    private static void check(boolean condition) {
        if (!condition) throw new AssertionError("Unexpected batch selection");
    }

    public static void main(String[] args) {
        String fingerprint = SmsFingerprint.of(new String[] {"1", "sender", "body", "0"});
        Map<String, String> recorded = new HashMap<>();
        recorded.put("1", fingerprint);
        recorded.put("2", fingerprint);
        recorded.put("3", fingerprint);
        SmsBatchSelection selection = new SmsBatchSelection(recorded);
        check(!selection.matches("99", fingerprint)); // Identical contents do not establish ownership.
        check(selection.matches("1", fingerprint));
        check(!selection.matches("1", fingerprint)); // Each recorded ID can be selected once.
        check(!selection.matches("2", SmsFingerprint.of(new String[] {"2", "changed"})));
        check(selection.changed() == 1 && selection.missing() == 1);
        check(recorded.size() == 3); // Selection must not rewrite durable/original identities.

        SmsBatchSelection retry = new SmsBatchSelection(recorded);
        check(!retry.matches("2", "changed"));
        check(retry.matches("3", fingerprint)); // A previously deleted row is simply absent on retry.
        check(retry.missing() == 1 && retry.changed() == 1);

        Map<String, String> large = new HashMap<>();
        for (int i = 1; i <= 100000; i++) large.put(Integer.toString(i), fingerprint);
        SmsBatchSelection largeSelection = new SmsBatchSelection(large);
        for (int i = 1; i <= 100000; i++) check(largeSelection.matches(Integer.toString(i), fingerprint));
        check(largeSelection.missing() == 0 && largeSelection.changed() == 0);
        SmsBatchSelection empty = new SmsBatchSelection(new HashMap<>());
        check(empty.missing() == 0 && empty.changed() == 0 && !empty.matches("1", fingerprint));
        System.out.println("SmsBatchSelectionTest: identity, change, missing, retry, empty and 100000-row cases passed");
    }
}
