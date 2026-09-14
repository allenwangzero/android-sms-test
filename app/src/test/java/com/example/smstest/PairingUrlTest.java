package com.example.smstest;

public final class PairingUrlTest {
    public static void main(String[] arguments) throws Exception {
        acceptsOnlyLiteralPrivateAddresses();
        rejectsPublicHostsCredentialsRedirectTargetsAndAmbiguousIps();
        System.out.println("PairingUrlTest: 17 address cases passed");
    }

    private static void acceptsOnlyLiteralPrivateAddresses() throws Exception {
        assertEquals("http://192.168.1.2:8765", PairingUrl.validate("http://192.168.1.2:8765/"));
        assertEquals("http://10.0.0.1:80", PairingUrl.validate("http://10.0.0.1:80"));
        assertEquals("http://172.31.0.2:9999", PairingUrl.validate("http://172.31.0.2:9999"));
    }

    private static void rejectsPublicHostsCredentialsRedirectTargetsAndAmbiguousIps() {
        String[] invalid = {"http://8.8.8.8:8765", "http://localhost:8765", "http://example.com:8765",
                "http://172.32.0.1:8765", "http://192.168.1.256:8765", "http://192.168.001.2:8765",
                "http://user@192.168.1.2:8765", "https://192.168.1.2:8765",
                "http://192.168.1.2:8765/path", "http://192.168.1.2:8765?x=1",
                "http://192.168.1.2:8765#x", "http://192.168.1.2", "http://192.168.1.2:0", null};
        for (String url : invalid) {
            try {
                PairingUrl.validate(url);
            } catch (Exception expected) {
                continue;
            }
            throw new AssertionError("Accepted invalid pairing URL: " + url);
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) throw new AssertionError("Unexpected URL: " + actual);
    }
}
