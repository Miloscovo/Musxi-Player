package tech.musxi.neteaseloginpoc;

import org.json.JSONObject;
import org.json.JSONException;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import javax.crypto.Cipher;
import javax.crypto.KeyAgreement;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

/** Offline XEAPI peer; it deliberately shares no crypto implementation with production code. */
final class XeapiTestPeer {
    private static final String STATIC_KEY =
            "ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84";
    private static final String SIGN_KEY =
            "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g==";
    private static final byte[] X25519_SPKI = unhex("302a300506032b656e032100");
    private static final String VERSION = "fixture-v1";
    private static final String SK = "fixture-sk";
    private static final KeyPair SERVER = keyPair();

    private XeapiTestPeer() { }

    static NeteaseApi api(NeteaseApi.Transport downstream) {
        Objects.requireNonNull(downstream, "downstream");
        return new NeteaseApi(request -> request.endpoint.equals(NeteaseXeapi.KEY_ENDPOINT)
                ? bootstrap(request) : downstream.execute(request));
    }

    static NeteaseApi.Response bootstrap(NeteaseApi.Request request) throws IOException {
        try {
            assertEquals(NeteaseXeapi.KEY_ENDPOINT, request.endpoint);
            Map<String,String> values = form(request.body);
            String nonce = values.get("nonce"), timestamp = values.get("timestamp");
            assertNotNull(nonce);
            assertTrue(nonce.matches("[0-9]{16}"));
            assertNotNull(timestamp);
            assertEquals(sign(timestamp, nonce), values.get("signature"));
            assertEquals("", values.get("uid"));
            assertFalse(values.containsKey("MUSIC_U"));
            assertFalse(request.headers.toString().contains("MUSIC_U"));

            JSONObject key = new JSONObject().put("publicKey", serverPublic())
                    .put("version", VERSION).put("sk", SK);
            String encrypted = Base64.getEncoder().encodeToString(
                    aes(Cipher.ENCRYPT_MODE, unhex(STATIC_KEY), utf8(key.toString())));
            String responseTimestamp = String.valueOf(System.currentTimeMillis());
            JSONObject data = new JSONObject().put("timestamp", responseTimestamp)
                    .put("signature", sign(responseTimestamp, nonce)).put("encryptedData", encrypted);
            return new NeteaseApi.Response(new JSONObject().put("code", 200).put("data", data),
                    Collections.emptyMap());
        } catch (GeneralSecurityException | JSONException | RuntimeException failure) {
            throw malformed(failure);
        }
    }

    static JSONObject payload(NeteaseApi.Request request) throws IOException {
        try {
            Map<String,String> envelope = form(request.body);
            String c = envelope.get("C"), s = envelope.get("S"), r = envelope.get("R");
            assertNotNull(c);
            assertNotNull(s);
            assertNotNull(r);

            byte[] sealed = Base64.getDecoder().decode(s);
            assertTrue(sealed.length > 60);
            byte[] ephemeral = Arrays.copyOfRange(sealed, 0, 32);
            byte[] iv = Arrays.copyOfRange(sealed, 32, 44);
            byte[] sealedText = Arrays.copyOfRange(sealed, 44, sealed.length - 16);
            byte[] tag = Arrays.copyOfRange(sealed, sealed.length - 16, sealed.length);
            PublicKey client = KeyFactory.getInstance("X25519").generatePublic(
                    new X509EncodedKeySpec(concat(X25519_SPKI, ephemeral)));
            byte[] shared = agreement(SERVER.getPrivate(), client);
            byte[] prk = hmac(new byte[32], shared);
            byte[] aesKey = Arrays.copyOf(hmac(prk, concat(ephemeral, new byte[]{1})), 16);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.DECRYPT_MODE, new SecretKeySpec(aesKey, "AES"),
                    new GCMParameterSpec(128, iv));
            String[] fields = utf8(gcm.doFinal(concat(sealedText, tag))).split("\\|", -1);
            assertEquals(3, fields.length);
            assertEquals("android", fields[1]);
            assertEquals(SK, fields[2]);
            byte[] dynamicKey = Base64.getDecoder().decode(fields[0]);
            assertEquals(16, dynamicKey.length);

            byte[] mixed = aes(Cipher.DECRYPT_MODE, dynamicKey, Base64.getUrlDecoder().decode(c));
            assertTrue(mixed.length > 32 && (mixed.length - 16) % 16 == 0);
            byte[] mask = Arrays.copyOfRange(mixed, 0, 16);
            byte[] first = new byte[mixed.length - 16];
            int rotation = (mask[0] & 15) % first.length;
            for (int i = 0; i < first.length; i++) {
                int source = (i + rotation) % first.length;
                first[source] = (byte)(mixed[16 + i] ^ mask[source & 15]);
            }
            JSONObject plain = new JSONObject(utf8(
                    aes(Cipher.DECRYPT_MODE, unhex(STATIC_KEY), first)));
            assertEquals("e_r=true", plain.getString("queryString"));
            JSONObject content = new JSONObject(form(plain.getString("content")));
            assertFalse(content.has("e_r"));
            assertEquals(VERSION + "|", utf8(aes(Cipher.DECRYPT_MODE,
                    unhex(STATIC_KEY), Base64.getDecoder().decode(r))));
            return content;
        } catch (GeneralSecurityException | JSONException | RuntimeException failure) {
            throw malformed(failure);
        }
    }

    static Map<String,String> form(String body) {
        Map<String,String> values = new LinkedHashMap<>();
        if (body == null || body.isEmpty()) return values;
        for (String pair : body.split("&", -1)) {
            int separator = pair.indexOf('=');
            if (separator <= 0) throw new IllegalArgumentException("Malformed form field");
            String name = URLDecoder.decode(pair.substring(0, separator), StandardCharsets.UTF_8);
            String value = URLDecoder.decode(pair.substring(separator + 1), StandardCharsets.UTF_8);
            if (values.put(name, value) != null)
                throw new IllegalArgumentException("Duplicate form field");
        }
        return values;
    }

    private static KeyPair keyPair() {
        try {
            return KeyPairGenerator.getInstance("X25519").generateKeyPair();
        } catch (GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }

    private static String serverPublic() {
        byte[] encoded = SERVER.getPublic().getEncoded();
        return Base64.getEncoder().encodeToString(Arrays.copyOfRange(
                encoded, encoded.length - 32, encoded.length));
    }

    private static byte[] agreement(PrivateKey privateKey, PublicKey publicKey)
            throws GeneralSecurityException {
        KeyAgreement agreement = KeyAgreement.getInstance("X25519");
        agreement.init(privateKey);
        agreement.doPhase(publicKey, true);
        return agreement.generateSecret();
    }

    private static String sign(String timestamp, String nonce) throws GeneralSecurityException {
        return Base64.getEncoder().encodeToString(
                hmac(utf8(SIGN_KEY), utf8(timestamp + nonce)));
    }

    private static byte[] hmac(byte[] key, byte[] value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(value);
    }

    private static byte[] aes(int mode, byte[] key, byte[] value)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES"));
        return cipher.doFinal(value);
    }

    private static byte[] concat(byte[] first, byte[] second) {
        byte[] out = Arrays.copyOf(first, first.length + second.length);
        System.arraycopy(second, 0, out, first.length, second.length);
        return out;
    }

    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    private static String utf8(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static byte[] unhex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++)
            out[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }

    private static IOException malformed(Throwable failure) {
        return new IOException("Malformed XEAPI fixture", failure);
    }
}
