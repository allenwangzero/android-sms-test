package com.example.smstest;

import java.util.HashMap;
import java.util.Map;

/** Matches current provider rows only to IDs and complete fingerprints recorded by this batch. */
public final class SmsBatchSelection {
    private final Map<String, String> remaining;
    private int changed;

    public SmsBatchSelection(Map<String, String> recorded) {
        remaining = new HashMap<>(recorded);
    }

    public boolean matches(String id, String currentFingerprint) {
        String expected = remaining.remove(id);
        if (expected == null) return false;
        if (!expected.equals(currentFingerprint)) {
            changed++;
            return false;
        }
        return true;
    }

    public int missing() { return remaining.size(); }
    public int changed() { return changed; }
}
