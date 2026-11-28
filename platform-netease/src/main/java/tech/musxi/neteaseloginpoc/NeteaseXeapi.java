package tech.musxi.neteaseloginpoc;

import org.json.JSONObject;
import org.json.JSONException;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.GZIPInputStream;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/** Song playback profile adapted from api-enhanced 23d9e9d7 (MIT), util/crypto.js,
 * util/request.js and module/register_xeapikey.js. No SDK or remote code execution.
 * Only the verified public-key bootstrap is cached; every request uses fresh ephemeral keys.
 * No account-bound encryption session is shared between accounts or persisted. */
final class NeteaseXeapi {
    static final String KEY_ENDPOINT = "https://interface.music.163.com/api/bsr/sk/get";
    static final String USER_AGENT = "NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00 Build/cd737a2.0)";
    static final String APP_VERSION = "9.5.61";
    private static final byte[] STATIC_KEY = unhex("ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84");
    private static final String SIGN_KEY = "mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g==";
    private static final int LIMIT = 4 * 1024 * 1024;
    private final SecureRandom random = new SecureRandom();
    private final NeteaseApi.Transport transport;
    private JSONObject publicKey;

    NeteaseXeapi(NeteaseApi.Transport transport) { this.transport = transport; }

    NeteaseApi.Request request(String endpoint, JSONObject input, Map<String,String> cookies,
            String deviceId) throws IOException {
        try {
            JSONObject key = key(deviceId);
            byte[] dynamicKey = bytes(16);
            Map<String,String> content = new LinkedHashMap<>();
            java.util.Iterator<String> names = input.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (!"e_r".equals(name)) content.put(name, input.get(name).toString());
            }
            JSONObject plain = new JSONObject().put("content", NeteaseApi.form(content))
                    .put("queryString", "e_r=true");
            byte[] first = aes(Cipher.ENCRYPT_MODE, STATIC_KEY, utf8(plain.toString()));
            byte[] mask = bytes(16);
            byte[] mixed = new byte[16 + first.length];
            System.arraycopy(mask, 0, mixed, 0, 16);
            int rotation = (mask[0] & 15) % first.length;
            for (int i = 0; i < first.length; i++) {
                int index = (i + rotation) % first.length;
                mixed[16 + i] = (byte)(first[index] ^ mask[index & 15]);
            }

            Curve25519 curve = Curve25519.getInstance(Curve25519.JAVA);
            Curve25519KeyPair ephemeral = curve.generateKeyPair();
            byte[] shared = curve.calculateAgreement(Base64.getDecoder().decode(key.getString("publicKey")),
                    ephemeral.getPrivateKey());
            if (MessageDigest.isEqual(shared, new byte[32])) throw invalid();
            byte[] prk = hmac(new byte[32], shared);
            byte[] aesKey = Arrays.copyOf(hmac(prk, concat(ephemeral.getPublicKey(), new byte[]{1})), 16);
            byte[] iv = bytes(12);
            Cipher gcm = Cipher.getInstance("AES/GCM/NoPadding");
            gcm.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey, "AES"), new GCMParameterSpec(128, iv));
            byte[] sealed = gcm.doFinal(utf8(Base64.getEncoder().encodeToString(dynamicKey)
                    + "|android|" + key.getString("sk")));
            Map<String,String> form = new LinkedHashMap<>();
            form.put("C", Base64.getUrlEncoder().withoutPadding().encodeToString(aes(Cipher.ENCRYPT_MODE, dynamicKey, mixed)));
            form.put("S", Base64.getEncoder().encodeToString(concat(ephemeral.getPublicKey(), iv, sealed)));
            form.put("R", Base64.getEncoder().encodeToString(aes(Cipher.ENCRYPT_MODE, STATIC_KEY,
                    utf8(key.get("version").toString() + "|"))));

            Map<String,String> headers = new LinkedHashMap<>();
            headers.put("User-Agent", USER_AGENT);
            headers.put("Accept", "*/*");
            headers.put("Accept-Encoding", "identity");
            headers.put("X-Client-Enc-State", "ENCRYPTED");
            headers.put("x-aeapi", "true");
            headers.put("x-deviceid", deviceId);
            headers.put("x-sdeviceid", deviceId);
            headers.put("x-os", "android");
            headers.put("x-osver", "16");
            headers.put("x-appver", APP_VERSION);
            headers.put("x-buildver", String.valueOf(System.currentTimeMillis() / 1000));
            headers.put("x-music-u", cookies.get("MUSIC_U"));
            Map<String,String> cookie = new LinkedHashMap<>(cookies);
            cookie.put("os", "android"); cookie.put("osver", headers.get("x-osver"));
            cookie.put("appver", APP_VERSION); cookie.put("deviceId", deviceId);
            cookie.put("sDeviceId", deviceId); cookie.put("buildver", headers.get("x-buildver"));
            headers.put("Cookie", cookieHeader(cookie));
            return new NeteaseApi.Request(endpoint, NeteaseApi.form(form), headers);
        } catch (NeteaseApi.ApiException safe) { throw safe; }
        catch (GeneralSecurityException | JSONException | RuntimeException bad) { throw invalid(); }
    }

    private synchronized JSONObject key(String deviceId) throws IOException, GeneralSecurityException, JSONException {
        if (publicKey != null) return publicKey;
        StringBuilder nonce = new StringBuilder();
        for (int i = 0; i < 16; i++) nonce.append(random.nextInt(10));
        String timestamp = String.valueOf(System.currentTimeMillis());
        Map<String,String> data = new LinkedHashMap<>();
        data.put("appVersion", APP_VERSION); data.put("currentKeyVersion", "");
        data.put("deviceId", deviceId); data.put("nonce", nonce.toString());
        data.put("os", "android"); data.put("requestType", "active");
        data.put("signature", sign(timestamp, nonce.toString()));
        data.put("t1", ""); data.put("t2", ""); data.put("timestamp", timestamp); data.put("uid", "");
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", USER_AGENT); headers.put("Accept-Encoding", "identity");
        headers.put("Cookie", "deviceId=" + deviceId);
        NeteaseApi.Response response = transport.execute(new NeteaseApi.Request(KEY_ENDPOINT, NeteaseApi.form(data), headers));
        if (response == null || response.body == null) throw invalid();
        int code = NeteaseApi.responseCode(response.body);
        if (code == -460 || code == -462 || code == 405 || code == 406)
            throw new NeteaseApi.ApiException("risk", "网易云暂时限制播放取流，请稍后重试");
        if (code == 429 || code == 503) throw new NeteaseApi.ApiException("rate", "网易云请求频率受限，请稍后重试");
        if (code != 200) throw new NeteaseApi.ApiException("platform", "网易云暂未接受播放密钥请求");
        JSONObject dataBody = response.body.optJSONObject("data");
        if (dataBody == null) throw invalid();
        String receivedTimestamp = dataBody.optString("timestamp", "");
        if (!receivedTimestamp.matches("[0-9]{10,16}")) throw invalid();
        byte[] signature = Base64.getDecoder().decode(dataBody.getString("signature"));
        if (!MessageDigest.isEqual(signature, Base64.getDecoder().decode(sign(receivedTimestamp, nonce.toString()))))
            throw invalid();
        JSONObject decoded = new JSONObject(new String(aes(Cipher.DECRYPT_MODE, STATIC_KEY,
                Base64.getDecoder().decode(dataBody.getString("encryptedData"))), StandardCharsets.UTF_8));
        if (!(decoded.opt("publicKey") instanceof String)
                || Base64.getDecoder().decode(decoded.getString("publicKey")).length != 32
                || !safeScalar(decoded.opt("version"), 128) || !(decoded.opt("sk") instanceof String)
                || !safeScalar(decoded.opt("sk"), 4096)) throw invalid();
        publicKey = decoded;
        return decoded;
    }

    static JSONObject decodeResponse(byte[] encrypted) throws IOException {
        try {
            byte[] plain = aes(Cipher.DECRYPT_MODE, utf8("e82ckenh8dichen8"), encrypted);
            if (plain.length >= 2 && plain[0] == (byte)0x1f && plain[1] == (byte)0x8b) {
                try (GZIPInputStream stream = new GZIPInputStream(new ByteArrayInputStream(plain))) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = stream.read(buffer)) != -1) {
                        if (count > LIMIT - out.size()) throw invalid();
                        out.write(buffer, 0, count);
                    }
                    plain = out.toByteArray();
                }
            }
            if (plain.length > LIMIT) throw invalid();
            JSONObject body = new JSONObject(new String(plain, StandardCharsets.UTF_8));
            NeteaseApi.responseCode(body);
            return body;
        } catch (GeneralSecurityException | JSONException | RuntimeException bad) { throw invalid(); }
        catch (IOException bad) { throw invalid(); }
    }

    private static boolean safeScalar(Object value, int max) {
        return (value instanceof String || value instanceof Number) && !value.toString().isEmpty()
                && value.toString().length() <= max && value.toString().matches("[^|\\p{Cntrl}]+");
    }
    private byte[] bytes(int size) { byte[] value = new byte[size]; random.nextBytes(value); return value; }
    private static String cookieHeader(Map<String,String> values) {
        return NeteaseApi.form(values).replace("&", "; ").replace("+", "%20");
    }
    private static String sign(String timestamp, String nonce) throws GeneralSecurityException {
        // The upstream HMAC key is the literal Base64-looking text, not decoded bytes.
        return Base64.getEncoder().encodeToString(hmac(utf8(SIGN_KEY), utf8(timestamp + nonce)));
    }
    private static byte[] hmac(byte[] key, byte[] value) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256"); mac.init(new SecretKeySpec(key, "HmacSHA256")); return mac.doFinal(value);
    }
    private static byte[] aes(int mode, byte[] key, byte[] value) throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(mode, new SecretKeySpec(key, "AES")); return cipher.doFinal(value);
    }
    private static byte[] concat(byte[]... values) {
        int size = 0; for (byte[] value : values) size += value.length;
        byte[] out = new byte[size]; int offset = 0;
        for (byte[] value : values) { System.arraycopy(value, 0, out, offset, value.length); offset += value.length; }
        return out;
    }
    private static byte[] utf8(String value) { return value.getBytes(StandardCharsets.UTF_8); }
    private static byte[] unhex(String value) {
        byte[] out = new byte[value.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte)Integer.parseInt(value.substring(i * 2, i * 2 + 2), 16);
        return out;
    }
    private static NeteaseApi.ApiException invalid() {
        return new NeteaseApi.ApiException("protocol", "网易云播放加密协议校验未完成");
    }
}
