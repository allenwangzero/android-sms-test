#!/bin/sh
set -eu
cd "$(dirname "$0")/.."
test_output=$(mktemp -d "${TMPDIR:-/tmp}/sms-test.XXXXXX")
trap 'rm -rf "$test_output"' EXIT HUP INT TERM
javac --release 17 -encoding UTF-8 -d "$test_output" \
    app/src/main/java/com/example/smstest/BatchImporter.java \
    app/src/main/java/com/example/smstest/SmsRecord.java \
    app/src/test/java/com/example/smstest/BatchImporterTest.java \
    app/src/test/java/com/example/smstest/SmsRecordTest.java \
    app/src/main/java/com/example/smstest/PairingUrl.java \
    app/src/test/java/com/example/smstest/PairingUrlTest.java
java -cp "$test_output" com.example.smstest.PairingUrlTest
java -cp "$test_output" com.example.smstest.BatchImporterTest
java -cp "$test_output" com.example.smstest.SmsRecordTest
