package com.example.smstest;

/** An in-memory test message. This does not represent an SMS sent by a carrier. */
public final class SmsRecord {
    public final String sender;
    public final String body;
    public final long timestamp;

    public SmsRecord(String sender, String body, long timestamp) {
        this.sender = sender;
        this.body = body;
        this.timestamp = timestamp;
    }
}
