package tech.musxi.loginpoc;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPublicKey;
import java.util.Arrays;

import javax.crypto.Cipher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Offline conformance for the cloud playlist crypto. Expected ciphertexts were produced by an
 * independent Node {@code crypto} implementation (AES-128-CBC + MD5), not by this class or the
 * vendored reference helpers, so a self-consistent but wrong key derivation cannot pass.
 */
public class KugouCloudProtocolTest {
    private static final String AES = "a1B2c3";
    /** MD5("a1B2c3") = c384985ae5122eb5c7c3cfc1faff7278, key 0..16, IV 16..32. */
    private static final String BODY_HEX =
        "c8175da07ff2ddf92f2c731fa530b2a85f314d2b4e93ce93985985a11fdf00ac";
    private static final String BODY_CLEAR = "{\"status\":1,\"data\":{\"list\":[]}}";
    /** Same key/IV, UTF-8 plaintext {"name":"酷狗","n":3}. */
    private static final String UTF8_HEX =
        "cdea2c900b9f4ddf6337d1b6b58bfc0f8fe175071213ff755e84f6c25167154c";
    private static final String UTF8_CLEAR = "{\"name\":\"酷狗\",\"n\":3}";
    /** Well-formed PKCS#5 padding carrying the malformed UTF-8 sequence 7b c3 28 7d. */
    private static final String BAD_UTF8_HEX = "35CF0C6D8BDE3A40600D27E47280C716";
    private static final int BODY_LIMIT = 256 * 1024;

    @Test public void encryptMatchesIndependentVector() throws Exception {
        KugouCloudProtocol protocol = new KugouCloudProtocol(AES);
        assertEquals(BODY_HEX, hex(protocol.encrypt(BODY_CLEAR)));
        assertEquals(UTF8_HEX, hex(protocol.encrypt(UTF8_CLEAR)));
        // PKCS#5 pads a full block: 27 bytes of plaintext become 32 bytes of ciphertext.
        assertEquals(32, protocol.encrypt(BODY_CLEAR).length);
        assertEquals(16, protocol.encrypt("").length);
    }

    @Test public void decodeRoundTripsAndAcceptsPlainJsonError() throws Exception {
        KugouCloudProtocol protocol = new KugouCloudProtocol(AES);
        assertEquals(BODY_CLEAR, protocol.decode(unhex(BODY_HEX)));
        assertEquals(UTF8_CLEAR, protocol.decode(unhex(UTF8_HEX)));
        // Refusals arrive unencrypted; the original text is handed back for the caller to parse.
        String refusal = "{\"status\":0,\"error_code\":20010,\"msg\":\"signature error\"}";
        assertEquals(refusal, protocol.decode(refusal.getBytes(StandardCharsets.UTF_8)));
        // A well-formed but unrelated object is still JSON, so the fallback keeps it verbatim.
        assertEquals("{}", protocol.decode("{}".getBytes(StandardCharsets.UTF_8)));
    }

    @Test public void decodeRejectsUnusableBodies() throws Exception {
        KugouCloudProtocol protocol = new KugouCloudProtocol(AES);
        byte[] tampered = unhex(BODY_HEX);
        tampered[tampered.length - 1] ^= 0x01;
        assertProtocolFailure(() -> protocol.decode(tampered));
        assertProtocolFailure(() -> protocol.decode(unhex(BAD_UTF8_HEX)));
        assertProtocolFailure(() -> protocol.decode(new byte[] {'<', 'h', 't', 'm', 'l', '>'}));
        assertProtocolFailure(() -> protocol.decode("{\"status\":}".getBytes(StandardCharsets.UTF_8)));
        assertProtocolFailure(() -> protocol.decode(new byte[] {'{', (byte) 0xc3, 0x28, '}'}));
        assertProtocolFailure(() -> protocol.decode(new byte[0]));
        assertProtocolFailure(() -> protocol.decode(null));
        // The limit is inclusive: one byte over is refused, exactly at it is still parsed in full.
        byte[] atLimit = padded("{\"status\":0}", BODY_LIMIT);
        assertEquals(new String(atLimit, StandardCharsets.UTF_8), protocol.decode(atLimit));
        assertProtocolFailure(() -> protocol.decode(padded("{\"status\":0}", BODY_LIMIT + 1)));
    }

    @Test public void signatureMatchesLiteAppIdentity() {
        assertEquals("bad0ee207bf429bd91402b77fc8f7a5b", KugouCloudProtocol.signature(1700000000L));
        assertEquals("85665eb60e65590d362a95c14188e66c", KugouCloudProtocol.signature(1700000001L));
        assertEquals("9cbe1c6b7979b3a3b21e4852b0c977b6", KugouCloudProtocol.signature(0L));
    }

    @Test public void createProducesRandomSixCharacterAsciiAlphanumerics() {
        String previous = null;
        for (int i = 0; i < 16; i++) {
            KugouCloudProtocol protocol = KugouCloudProtocol.create();
            String aes = protocol.aes();
            assertTrue(aes, aes.matches("[0-9A-Za-z]{6}"));
            if (previous != null) assertNotEquals(previous, aes);
            previous = aes;
        }
        for (String invalid : new String[] {null, "", "abcde", "abcdefg", "abcde ", "abcde-", "ab节df"}) {
            try {
                new KugouCloudProtocol(invalid);
                fail("invalid session identifier accepted");
            } catch (IllegalArgumentException expected) {
                // expected
            }
        }
    }

    @Test public void deletionSessionsUseOnlyTheLegacyLowercaseDigitAlphabet() {
        java.util.Set<String> values = new java.util.HashSet<>();
        for (int i = 0; i < 32; i++) {
            String aes = KugouCloudProtocol.createForDeletion().aes();
            assertTrue(aes.matches("[0-9a-z]{6}"));
            values.add(aes);
        }
        assertTrue(values.size() > 1);
    }

    @Test public void portraitIsUppercaseHexOfFullModulusWidth() throws Exception {
        KugouCloudProtocol protocol = new KugouCloudProtocol(AES);
        String first = protocol.portrait(session("fixture-token-123", "123456"));
        String second = protocol.portrait(session("fixture-token-123", "123456"));
        assertEquals(256, first.length());
        assertTrue(first.matches("[0-9A-F]{256}"));
        assertNotEquals(first, second);
        assertFalse(first.contains(AES));
    }

    @Test public void portraitUsesRandomisedPkcs1Padding() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        KeyPair keyPair = generator.generateKeyPair();
        RSAPublicKey publicKey = (RSAPublicKey) keyPair.getPublic();
        KugouCloudProtocol protocol = new KugouCloudProtocol(AES);
        KugouApi.Session session = session("fixture-token-123", "123456");

        String previous = null;
        for (int i = 0; i < 8; i++) {
            String portrait = protocol.portrait(session, publicKey.getModulus(),
                publicKey.getPublicExponent());
            assertEquals(256, portrait.length());
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, keyPair.getPrivate());
            byte[] plain = cipher.doFinal(unhex(portrait));
            // PKCS#1 v1.5 requires at least eight non-zero padding bytes, so 117 is the ceiling.
            assertTrue(plain.length <= 117);
            JSONObject json = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            assertEquals(AES, json.getString("aes"));
            assertEquals("123456", json.getString("uid"));
            assertEquals("fixture-token-123", json.getString("token"));
            // uid is a string in the wire format, never a number.
            assertTrue(json.get("uid") instanceof String);
            assertNotEquals(previous, portrait);
            previous = portrait;
        }
    }

    @Test public void portraitFailsClosedWithoutLeakingSession() throws Exception {
        KugouCloudProtocol protocol = new KugouCloudProtocol(AES);
        assertEquals(256, protocol.portrait(session("fixture-token-123", "123456")).length());
        String token = "t".repeat(80);
        try {
            protocol.portrait(session(token, "9007199254740991"));
            fail("oversized portrait accepted");
        } catch (KugouApi.ApiException error) {
            assertEquals("credentials", error.code);
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains(token));
            assertFalse(error.getMessage().contains("9007199254740991"));
            assertFalse(error.getMessage().contains(AES));
        }
        try {
            protocol.portrait(null);
            fail("missing session accepted");
        } catch (KugouApi.ApiException error) {
            assertEquals("credentials", error.code);
            assertNull(error.getCause());
        }
    }

    private interface Action { void run() throws Exception; }

    private static void assertProtocolFailure(Action action) throws Exception {
        try {
            action.run();
            fail("unusable body accepted");
        } catch (KugouApi.ApiException error) {
            assertEquals("protocol", error.code);
            assertNull(error.getCause());
            assertFalse(error.getMessage().contains("signature error"));
        }
    }

    private static KugouApi.Session session(String token, String userId) {
        return new KugouApi.Session(token, userId, "fixture");
    }

    private static String hex(byte[] value) {
        StringBuilder out = new StringBuilder(value.length * 2);
        for (byte current : value) {
            out.append(Character.forDigit((current >>> 4) & 15, 16))
                .append(Character.forDigit(current & 15, 16));
        }
        return out.toString();
    }

    /** Right-pads a JSON body with spaces, which JSON parsing ignores, up to an exact byte size. */
    private static byte[] padded(String json, int size) {
        byte[] value = new byte[size];
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        System.arraycopy(bytes, 0, value, 0, bytes.length);
        Arrays.fill(value, bytes.length, size, (byte) ' ');
        return value;
    }

    private static byte[] unhex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) {
            out[i] = (byte) Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        }
        return out;
    }
}
