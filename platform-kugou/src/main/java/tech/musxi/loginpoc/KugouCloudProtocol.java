package tech.musxi.loginpoc;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.RSAPublicKeySpec;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Offline cryptography for the KuGou cloud playlist (cloudlist) service.
 *
 * <p>Transport-free: it only derives the per-request session material, encrypts request bodies,
 * decrypts response bodies, and builds the {@code p} portrait parameter. The protocol mirrors the
 * pinned reference {@code util/request.js#createCloudRequest} (kuGouMusicApi
 * 7ac64e1cdd333ad9bfff644ded82a0ffeef002d1) with the lite app identity, so the lite app key must
 * never be mixed with the standard web key.
 */
final class KugouCloudProtocol {
    private static final String APP_ID = "3116";
    private static final String CLIENT_VER = "11440";
    // Lite app key from util/request.js CLOUDLIST_CONF; not interchangeable with the web salt.
    private static final String APP_KEY = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    // Same lite public key as KugouApi.RSA_MODULUS (SubjectPublicKeyInfo in util/crypto.js).
    private static final BigInteger RSA_MODULUS = new BigInteger(
        "c40a2d0da76511f3bb1cc2bbd3afbd8bea83b4d6b05b6c13eb8920c53f1af7679b32ba0d0edb843240ef1b836efed3ee240734c14c1399fd6594d16af22f52525d14d72e0155c6dcc8638d4f7bb94f3a0b1f4c29f991972f2a160a25eb0a9e724336be7f69bbd319ffab1c6dd8470b021dc434f3faba89f4a2a01b33bdbdd08b", 16);
    private static final BigInteger RSA_EXPONENT = BigInteger.valueOf(65537);
    /** 1024-bit RSAES-PKCS1-v1_5 limit: modulus length minus eleven bytes of padding. */
    private static final int RSA_MAX_PLAINTEXT = 117;
    private static final int BODY_LIMIT = 256 * 1024;
    private static final int AES_LENGTH = 6;
    private static final char[] ALPHABET =
        "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String PROTOCOL_MESSAGE = "酷狗云歌单响应无法识别，请稍后重试";
    private static final String CREDENTIALS_MESSAGE = "云歌单登录凭证不符合协议要求，请重新登录";

    private final String aes;
    private final byte[] key;
    private final byte[] iv;

    KugouCloudProtocol(String aes) {
        if (!validAes(aes)) throw new IllegalArgumentException("云歌单会话标识无效");
        String hash = md5(aes);
        this.aes = aes;
        this.key = hash.substring(0, 16).getBytes(StandardCharsets.UTF_8);
        this.iv = hash.substring(16, 32).getBytes(StandardCharsets.UTF_8);
    }

    /** Random six-character ASCII alphanumeric session identifier, as {@code randomString(6)}. */
    static KugouCloudProtocol create() {
        char[] value = new char[AES_LENGTH];
        for (int i = 0; i < value.length; i++) value[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return new KugouCloudProtocol(new String(value));
    }

    /** Legacy playlist_del.js uses randomString(6) with only lowercase letters and digits. */
    static KugouCloudProtocol createForDeletion() {
        String alphabet = "0123456789abcdefghijklmnopqrstuvwxyz";
        char[] value = new char[AES_LENGTH];
        for (int i = 0; i < value.length; i++) value[i] = alphabet.charAt(RANDOM.nextInt(alphabet.length()));
        return new KugouCloudProtocol(new String(value));
    }

    String aes() { return aes; }

    /** AES-128-CBC/PKCS5Padding over UTF-8, with key and IV taken from the session MD5. */
    byte[] encrypt(String clear) {
        if (clear == null) throw new IllegalArgumentException("缺少云歌单请求内容");
        Cipher cipher = cipher(Cipher.ENCRYPT_MODE);
        try {
            return cipher.doFinal(clear.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException failure) {
            throw new AssertionError(failure);
        }
    }

    /**
     * Decrypts a response body to UTF-8 text. The service answers unencrypted JSON (normally an
     * error object) when it refuses the request, so a body that starts with '{' and does not
     * decrypt is returned verbatim as long as it is well-formed UTF-8.
     */
    String decode(byte[] raw) throws IOException {
        if (raw == null || raw.length == 0 || raw.length > BODY_LIMIT) throw protocolError();
        byte[] plain = null;
        if (raw.length % 16 == 0) {
            Cipher cipher = cipher(Cipher.DECRYPT_MODE);
            try {
                plain = cipher.doFinal(raw);
            } catch (GeneralSecurityException notEncrypted) {
                plain = null;
            }
        }
        if (plain == null) return plainJson(raw);
        return text(plain);
    }

    /** Only a well-formed JSON object body is echoed back; anything else stays a protocol error. */
    private static String plainJson(byte[] raw) throws IOException {
        if (raw[0] != '{') throw protocolError();
        String body = text(raw);
        try {
            new JSONObject(body);
        } catch (JSONException invalid) {
            throw protocolError();
        }
        return body;
    }

    /** {@code p}: RSAES-PKCS1-v1_5 of the session JSON, uppercase hex, using the lite key. */
    String portrait(KugouApi.Session session) throws IOException {
        return portrait(session, RSA_MODULUS, RSA_EXPONENT);
    }

    /** Test seam: the same portrait against an independently generated public key. */
    String portrait(KugouApi.Session session, BigInteger modulus, BigInteger exponent) throws IOException {
        if (session == null || modulus == null || exponent == null || exponent.signum() <= 0)
            throw credentialsError();
        String plain = "{\"aes\":\"" + escape(aes) + "\",\"uid\":\"" + escape(session.userId)
            + "\",\"token\":\"" + escape(session.token) + "\"}";
        byte[] bytes = plain.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > RSA_MAX_PLAINTEXT) throw credentialsError();
        try {
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, KeyFactory.getInstance("RSA")
                .generatePublic(new RSAPublicKeySpec(modulus, exponent)));
            return uppercaseHex(cipher.doFinal(bytes));
        } catch (GeneralSecurityException unsupported) {
            throw credentialsError();
        }
    }

    /** {@code key}: MD5 of appid + appkey + clientver + clienttime, lowercase hex. */
    static String signature(long clienttime) {
        return md5(APP_ID + APP_KEY + CLIENT_VER + clienttime);
    }

    private Cipher cipher(int mode) {
        try {
            Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            cipher.init(mode, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
            return cipher;
        } catch (GeneralSecurityException unsupported) {
            throw new AssertionError(unsupported);
        }
    }

    /** Strict UTF-8 decoding: malformed or unmappable input is a protocol error. */
    private static String text(byte[] value) throws IOException {
        if (value.length == 0) throw protocolError();
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            String text = decoder.decode(ByteBuffer.wrap(value)).toString();
            if (text.isEmpty()) throw protocolError();
            return text;
        } catch (CharacterCodingException invalid) {
            throw protocolError();
        }
    }

    private static boolean validAes(String aes) {
        if (aes == null || aes.length() != AES_LENGTH) return false;
        for (int i = 0; i < aes.length(); i++) {
            char value = aes.charAt(i);
            boolean alphanumeric = (value >= '0' && value <= '9')
                || (value >= 'a' && value <= 'z') || (value >= 'A' && value <= 'Z');
            if (!alphanumeric) return false;
        }
        return true;
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char current = value.charAt(i);
            if (current == '"' || current == '\\') {
                out.append('\\').append(current);
            } else if (current < 0x20) {
                out.append("\\u00").append(HEX[(current >>> 4) & 15]).append(HEX[current & 15]);
            } else {
                out.append(current);
            }
        }
        return out.toString();
    }

    private static String uppercaseHex(byte[] value) {
        char[] out = new char[value.length * 2];
        for (int i = 0; i < value.length; i++) {
            out[i * 2] = HEX[(value[i] >>> 4) & 15];
            out[i * 2 + 1] = HEX[value[i] & 15];
        }
        return new String(out);
    }

    private static String md5(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            char[] out = new char[hash.length * 2];
            for (int i = 0; i < hash.length; i++) {
                out[i * 2] = Character.toLowerCase(HEX[(hash[i] >>> 4) & 15]);
                out[i * 2 + 1] = Character.toLowerCase(HEX[hash[i] & 15]);
            }
            return new String(out);
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static KugouApi.ApiException protocolError() {
        return new KugouApi.ApiException("protocol", PROTOCOL_MESSAGE);
    }

    private static KugouApi.ApiException credentialsError() {
        return new KugouApi.ApiException("credentials", CREDENTIALS_MESSAGE);
    }
}
