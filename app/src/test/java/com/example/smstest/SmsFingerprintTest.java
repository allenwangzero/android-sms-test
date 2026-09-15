package com.example.smstest;

public final class SmsFingerprintTest {
    public static void main(String[] args) {
        different(new String[] {null}, new String[] {""});
        different(new String[] {"a", "bc"}, new String[] {"ab", "c"});
        different(new String[] {"1", "message", "0"}, new String[] {"1", "message", "1"});
        different(new String[] {"1", "message"}, new String[] {"2", "message"});
        String longBody = "x".repeat(2000);
        different(new String[] {"1", longBody + "original"}, new String[] {"1", longBody + "changed"});
        String[] unicode = {"123", "发送人", "😀 菲律宾短信", null, "0", "-1"};
        String result = SmsFingerprint.of(unicode);
        if (!result.matches("[0-9a-f]{64}") || !result.equals(SmsFingerprint.of(unicode.clone()))) {
            throw new AssertionError("Fingerprint must be stable lowercase SHA-256");
        }
        System.out.println("SmsFingerprintTest: 6 identity/null/full-content cases passed");
    }

    private static void different(String[] before, String[] after) {
        if (SmsFingerprint.of(before).equals(SmsFingerprint.of(after))) {
            throw new AssertionError("Different original records must not share a fingerprint");
        }
    }
}
