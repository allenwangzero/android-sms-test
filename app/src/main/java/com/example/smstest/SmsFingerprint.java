package com.example.smstest;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Length-prefixed nullable values avoid delimiter collisions and preserve SQL NULL. */
public final class SmsFingerprint {
    private SmsFingerprint() { }

    public static String of(String[] values) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String value : values) {
                if (value == null) {
                    digest.update((byte) 0);
                } else {
                    digest.update((byte) 1);
                    byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
                    int length = bytes.length;
                    digest.update(new byte[] {(byte) (length >>> 24), (byte) (length >>> 16),
                            (byte) (length >>> 8), (byte) length});
                    digest.update(bytes);
                }
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(Character.forDigit((value >>> 4) & 15, 16));
                result.append(Character.forDigit(value & 15, 16));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 unavailable", error);
        }
    }
}
