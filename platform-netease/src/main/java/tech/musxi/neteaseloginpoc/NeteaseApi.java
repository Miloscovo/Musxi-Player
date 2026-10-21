package tech.musxi.neteaseloginpoc;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.HttpCookie;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.SecureRandom;
import java.security.MessageDigest;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.X509EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.HttpsURLConnection;

/**
 * Phone-local login experiment, call on a worker thread.
 * Adapted from NeteaseCloudMusicApiEnhanced/api-enhanced revision
 * 2aab9957dfd5231e5b192aecdb177f93ace4c92e: module/login_qr_*.js,
 * module/login_status.js and util/{crypto,request}.js.
 * Copyright (c) 2013-2022 Binaryify, MIT; original license in APK assets.
 * Matches vendor EAPI for QR and WEAPI for status; no server, fingerprint service or JS execution.
 */
public final class NeteaseApi {
    private static final String ORIGIN = "https://music.163.com";
    static final String KEY = "https://interfacepc.music.163.com/eapi/login/qrcode/unikey";
    static final String POLL = "https://interfacepc.music.163.com/eapi/login/qrcode/client/login";
    static final String STATUS = ORIGIN + "/weapi/w/nuser/account/get";
    private static final String PUBLIC_KEY = "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ37BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvaklV8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44oncaTWz7OBGLbCiK45wIDAQAB";
    private static final int LIMIT = 4 * 1024 * 1024;
    static final String USER_PLAYLIST = ORIGIN + "/weapi/user/playlist";
    static final String PLAYLIST_DETAIL = "https://interfacepc.music.163.com/eapi/v6/playlist/detail";
    static final String SONG_DETAIL = ORIGIN + "/weapi/v3/song/detail";
    static final String SEARCH = "https://interfacepc.music.163.com/eapi/cloudsearch/pc";
    static final String SONG_URL = "https://interfacepc.music.163.com/eapi/song/enhance/player/url/v1";
    static final String LYRIC = "https://interfacepc.music.163.com/eapi/song/lyric";
    static final String LIKED_IDS = "https://interfacepc.music.163.com/eapi/song/like/get";
    static final String SET_LIKE = "https://interfacepc.music.163.com/eapi/radio/like";
    private final Transport transport;
    private final SecureRandom random = new SecureRandom();
    private final String deviceId = java.util.UUID.randomUUID().toString().replace("-", "");

    public static final class Attempt {
        public final String authorizationUrl;
        private final String key;
        private Attempt(String key) {
            this.key = key;
            authorizationUrl = ORIGIN + "/login?codekey=" + encode(key);
        }
    }
    public static final class Session {
        public final String userId, nickname;
        private final Map<String,String> cookies;
        private Session(Map<String,String> cookies, String userId, String nickname) throws IOException {
            this.cookies = immutable(validateCookies(cookies));
            if (!userId.isEmpty() && !validId(userId)) throw error("identity", "网易云账号标识无效");
            this.userId = userId;
            this.nickname = cleanName(nickname);
        }
        /** Encrypted storage only: never render or log this object. */
        public JSONObject toJson() throws IOException {
            if (!validId(userId)) throw error("storage", "只能保存已验证的网易云会话");
            return object("cookies", new JSONObject(cookies), "userId", userId, "nickname", nickname);
        }
        public static Session fromJson(JSONObject json) throws IOException {
            if (json == null || !(json.opt("userId") instanceof String) || !validId(json.optString("userId"))
                    || !(json.opt("nickname") instanceof String) || json.optJSONObject("cookies") == null)
                throw error("storage", "本机网易云会话格式无效");
            JSONObject source = json.optJSONObject("cookies");
            Map<String,String> cookies = new LinkedHashMap<>();
            java.util.Iterator<String> names = source.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (!(source.opt(name) instanceof String)) throw error("storage", "本机会话字段无效");
                cookies.put(name, source.optString(name));
            }
            return new Session(cookies, json.optString("userId"), json.optString("nickname"));
        }
    }
    public static final class Poll {
        public final int status;
        public final Session session;
        private Poll(int status, Session session) { this.status = status; this.session = session; }
    }
    public enum ProbeResult { ACCEPTED, REJECTED }
    public static final class ApiException extends IOException {
        public final String code;
        ApiException(String code, String message) { super(message); this.code = code; }
    }
    interface Transport { Response execute(Request request) throws IOException; }
    static final class Request {
        final String endpoint, body;
        final Map<String,String> headers;
        Request(String endpoint, String body, Map<String,String> headers) {
            this.endpoint = endpoint; this.body = body; this.headers = immutable(headers);
        }
    }
    static final class Response {
        final JSONObject body;
        final Map<String,List<String>> headers;
        Response(JSONObject body, Map<String,List<String>> headers) {
            this.body = body;
            Map<String,List<String>> safe = new LinkedHashMap<>();
            for (Map.Entry<String,List<String>> row : headers.entrySet())
                safe.put(row.getKey(), Collections.unmodifiableList(new ArrayList<>(row.getValue())));
            this.headers = Collections.unmodifiableMap(safe);
        }
    }
    public NeteaseApi() { this(NeteaseApi::executeHttps); }
    NeteaseApi(Transport transport) { this.transport = transport; }

    /** Browser credentials remain unsavable until verify returns an identity. */
    public static Session fromBrowserCookies(String header) throws IOException {
        return new Session(WebLoginPolicy.parseMusicCookies(header), "", "网易云用户");
    }

    public Attempt createLogin() throws IOException {
        Response response = call(KEY, object("type", 3), Collections.emptyMap());
        requireSuccess(response.body);
        String key = scalar(response.body, "unikey");
        // The direct platform response is not the adapter's data wrapper.
        if (!key.matches("[A-Za-z0-9_-]{8,512}")) throw error("protocol", "网易云未返回有效登录请求");
        return new Attempt(key);
    }
    public Poll poll(Attempt attempt) throws IOException {
        if (attempt == null) throw error("request", "请先创建网易云登录请求");
        Response response = call(POLL, object("key", attempt.key, "type", 3), Collections.emptyMap());
        int code = responseCode(response.body);
        if (code == 800 || code == 801 || code == 802) return new Poll(code, null);
        if (code != 803) { requireSuccess(response.body); throw error("protocol", "网易云返回未知登录状态"); }
        Map<String,String> cookies = authCookies(response.headers);
        return new Poll(803, new Session(cookies, "", "网易云用户"));
    }
    public Session verify(Session session) throws IOException {
        if (session == null) throw error("credentials", "缺少网易云登录凭证");
        Response response = call(STATUS, new JSONObject(), session.cookies);
        return parseIdentity(response.body, session);
    }
    /** Exactly one read-only request; never saves, retries, or returns the fake token. */
    public ProbeResult probeInvalidToken(Session session) throws IOException {
        if (session == null || !validId(session.userId)) throw error("credentials", "请先完成网易云登录");
        String original = session.cookies.get("MUSIC_U");
        String fake;
        do {
            StringBuilder out = new StringBuilder();
            for (int i = 0; i < original.length(); i++) out.append("0123456789abcdef".charAt(random.nextInt(16)));
            fake = out.toString();
        } while (fake.equals(original));
        Map<String,String> cookies = new LinkedHashMap<>(session.cookies);
        cookies.put("MUSIC_U", fake);
        Session probe = new Session(cookies, session.userId, session.nickname);
        Response response = call(STATUS, new JSONObject(), probe.cookies);
        int code = responseCode(response.body);
        if (code == 301 || code == 302) return ProbeResult.REJECTED;
        requireSuccess(response.body);
        // Logged-out account/get convention: both identity objects explicitly null.
        if (response.body.has("account") && response.body.isNull("account")
                && response.body.has("profile") && response.body.isNull("profile")) return ProbeResult.REJECTED;
        parseIdentity(response.body, probe);
        return ProbeResult.ACCEPTED;
    }
    /** Internal authenticated read-only API. Credential serialization never crosses the platform module. */
    JSONObject authenticatedCall(String endpoint, JSONObject input, Session session) throws IOException {
        if (session == null || !validId(session.userId)) throw error("credentials", "请先完成网易云登录");
        if (!USER_PLAYLIST.equals(endpoint) && !PLAYLIST_DETAIL.equals(endpoint)
                && !SONG_DETAIL.equals(endpoint) && !SONG_URL.equals(endpoint) && !SEARCH.equals(endpoint)
                && !LYRIC.equals(endpoint) && !LIKED_IDS.equals(endpoint) && !SET_LIKE.equals(endpoint))
            throw error("endpoint", "请求目标不在允许的网易云官方接口范围内");
        Response response = call(endpoint, input, session.cookies);
        if (!LYRIC.equals(endpoint)) requireSuccess(response.body);
        return response.body;
    }

    private Response call(String endpoint, JSONObject input, Map<String,String> cookies) throws IOException {
        requireEndpoint(new URL(endpoint));
        String csrf = cookies.getOrDefault("__csrf", "");
        put(input, "e_r", false);
        StringBuilder key = new StringBuilder();
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < 16; i++) key.append(alphabet.charAt(random.nextInt(alphabet.length())));
        Map<String,String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Mozilla/5.0 (Linux; Android 16) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Mobile Safari/537.36");
        headers.put("Referer", ORIGIN + "/");
        headers.put("Accept", "application/json"); headers.put("Accept-Encoding", "identity");
        StringBuilder cookie = new StringBuilder("os=pc; __remember_me=true");
        for (Map.Entry<String,String> row : cookies.entrySet()) cookie.append("; ").append(row.getKey()).append('=').append(row.getValue());
        Map<String,String> encrypted;
        if ("interfacepc.music.163.com".equals(new URL(endpoint).getHost())) {
            // The QR modules use default EAPI, unlike login_status's explicit WEAPI.
            JSONObject header = object("os", "pc", "appver", "3.1.17.204416",
                "osver", "Microsoft-Windows-10-Professional-build-19045-64bit", "deviceId", deviceId,
                "versioncode", "140", "mobilename", "", "buildver", String.valueOf(System.currentTimeMillis()/1000),
                "resolution", "1920x1080", "__csrf", csrf, "channel", "netease",
                "requestId", System.currentTimeMillis() + "_" + String.format(java.util.Locale.ROOT, "%04d", random.nextInt(1000)));
            if (cookies.containsKey("MUSIC_U")) put(header, "MUSIC_U", cookies.get("MUSIC_U"));
            put(input, "header", header);
            encrypted = Collections.singletonMap("params", eapi("/api/" + new URL(endpoint).getPath().substring(6), input.toString()));
            cookie = new StringBuilder();
            java.util.Iterator<String> names = header.keys();
            while (names.hasNext()) {
                String name = names.next();
                if (cookie.length() > 0) cookie.append("; ");
                cookie.append(encode(name)).append('=').append(encode(header.optString(name)).replace("+", "%20"));
            }
            headers.put("User-Agent", "NeteaseMusic 9.0.90/5038 (iPhone; iOS 16.2; zh_CN)");
        } else {
            put(input, "csrf_token", csrf);
            encrypted = weapi(input.toString(), key.toString());
        }
        headers.put("Cookie", cookie.toString());
        try {
            Response response = transport.execute(new Request(endpoint, form(encrypted), headers));
            if (response == null || response.body == null) throw error("protocol", "网易云未返回有效数据");
            return response;
        } catch (ApiException safe) { throw safe; }
        catch (IOException failure) { throw error("network", "无法连接网易云官方服务"); }
    }
    static Session parseIdentity(JSONObject body, Session session) throws IOException {
        requireSuccess(body);
        JSONObject profile = body.optJSONObject("profile"), account = body.optJSONObject("account");
        if (profile == null || account == null) throw error("expired", "网易云登录态无效或已过期");
        String id = scalar(profile, "userId"), accountId = scalar(account, "id");
        if (!validId(id) || !id.equals(accountId) || (!session.userId.isEmpty() && !id.equals(session.userId)))
            throw error("identity", "网易云返回的账号身份未匹配");
        if (!(profile.opt("nickname") instanceof String)) throw error("identity", "网易云未返回有效账号资料");
        return new Session(session.cookies, id, profile.optString("nickname"));
    }
    static Map<String,String> weapi(String text, String secret) throws IOException {
        if (!secret.matches("[A-Za-z0-9]{16}")) throw error("protocol", "请求密钥格式无效");
        try {
            String first = aes(text, "0CoJUm6Qyw8W8jud");
            String params = aes(first, secret);
            RSAPublicKey publicKey = (RSAPublicKey)KeyFactory.getInstance("RSA").generatePublic(
                new X509EncodedKeySpec(Base64.getDecoder().decode(PUBLIC_KEY)));
            byte[] reversed = new StringBuilder(secret).reverse().toString().getBytes(StandardCharsets.US_ASCII);
            String hex = new BigInteger(1, reversed).modPow(publicKey.getPublicExponent(), publicKey.getModulus()).toString(16);
            StringBuilder padded = new StringBuilder();
            for (int i = hex.length(); i < 256; i++) padded.append('0');
            Map<String,String> out = new LinkedHashMap<>(); out.put("params", params); out.put("encSecKey", padded.append(hex).toString());
            return out;
        } catch (java.security.GeneralSecurityException | IllegalArgumentException failure) {
            throw error("protocol", "本机请求加密未完成");
        }
    }
    static String eapi(String uri, String text) throws IOException {
        try {
            String digest = hex(MessageDigest.getInstance("MD5").digest(("nobody" + uri + "use" + text + "md5forencrypt").getBytes(StandardCharsets.UTF_8)));
            String data = uri + "-36cd479b6b5-" + text + "-36cd479b6b5-" + digest;
            Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII), "AES"));
            return hex(cipher.doFinal(data.getBytes(StandardCharsets.UTF_8))).toUpperCase(java.util.Locale.ROOT);
        } catch (java.security.GeneralSecurityException failure) { throw error("protocol", "本机请求加密未完成"); }
    }
    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder();
        for (byte b : data) { out.append("0123456789abcdef".charAt((b & 255) >>> 4)); out.append("0123456789abcdef".charAt(b & 15)); }
        return out.toString();
    }
    private static String aes(String text, String key) throws java.security.GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key.getBytes(StandardCharsets.US_ASCII), "AES"),
            new IvParameterSpec("0102030405060708".getBytes(StandardCharsets.US_ASCII)));
        return Base64.getEncoder().encodeToString(cipher.doFinal(text.getBytes(StandardCharsets.UTF_8)));
    }
    static Map<String,String> authCookies(Map<String,List<String>> headers) throws IOException {
        Map<String,String> out = new LinkedHashMap<>();
        int size = 0;
        for (Map.Entry<String,List<String>> row : headers.entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(row.getKey())) continue;
            for (String line : row.getValue()) {
                size += line.length(); if (size > 32768) throw error("protocol", "网易云凭证响应超过限制");
                List<HttpCookie> parsed;
                try { parsed = HttpCookie.parse(line); } catch (IllegalArgumentException bad) { throw error("protocol", "网易云凭证响应无效"); }
                for (HttpCookie c : parsed) {
                    if (!"MUSIC_U".equals(c.getName()) && !"__csrf".equals(c.getName())) continue;
                    String domain = c.getDomain();
                    if (domain != null) {
                        if (domain.startsWith(".")) domain = domain.substring(1);
                        if (!"music.163.com".equalsIgnoreCase(domain) && !"163.com".equalsIgnoreCase(domain)) continue;
                    }
                    if (c.getPath() != null && !c.getPath().isEmpty() && !"/".equals(c.getPath())) continue;
                    if (c.hasExpired()) continue;
                    String value = c.getValue();
                    if (out.containsKey(c.getName()) && !out.get(c.getName()).equals(value)) throw error("protocol", "网易云凭证存在歧义");
                    out.put(c.getName(), value);
                }
            }
        }
        return validateCookies(out);
    }
    private static Map<String,String> validateCookies(Map<String,String> source) throws IOException {
        if (source == null || source.size() > 2 || !safeCookie(source.get("MUSIC_U"), 8192))
            throw error("credentials", "网易云未返回完整登录凭证");
        for (Map.Entry<String,String> row : source.entrySet()) {
            if (!"MUSIC_U".equals(row.getKey()) && !"__csrf".equals(row.getKey())) throw error("credentials", "本机凭证字段无效");
            if ("__csrf".equals(row.getKey()) && !("".equals(row.getValue()) || safeCookie(row.getValue(), 256)))
                throw error("credentials", "本机校验字段无效");
        }
        return source;
    }
    private static boolean safeCookie(String value, int length) {
        return value != null && !value.isEmpty() && value.length() <= length && value.matches("[!-~]+")
            && !value.matches(".*[;,\\\"\\\\].*");
    }
    private static boolean validId(String id) {
        return id != null && id.matches("[1-9][0-9]{0,15}") && new BigInteger(id).compareTo(BigInteger.valueOf(9007199254740991L)) <= 0;
    }
    private static String cleanName(String value) {
        if (value == null) return "网易云用户";
        String clean = value.replaceAll("[\\p{Cc}\\p{Cf}]", "").trim();
        return clean.isEmpty() ? "网易云用户" : clean.substring(0, Math.min(120, clean.length()));
    }
    private static String scalar(JSONObject object, String key) {
        Object value = object.opt(key); return value instanceof String || value instanceof Number ? String.valueOf(value) : "";
    }
    static int responseCode(JSONObject body) throws IOException {
        if (body == null || !(body.opt("code") instanceof Number) || !scalar(body,"code").matches("-?[0-9]{1,6}"))
            throw error("protocol", "网易云响应缺少有效状态");
        return ((Number)body.opt("code")).intValue();
    }
    private static void requireSuccess(JSONObject body) throws IOException {
        int code = responseCode(body);
        if (code == 200) return;
        if (code == 301 || code == 302) throw error("expired", "网易云登录态无效或已过期");
        if (code == -462 || code == 405 || code == 406) throw error("risk", "请在网易云官方应用完成安全验证");
        if (code == 429 || code == 503) throw error("rate", "网易云请求频率受限，请稍后重试");
        throw error("platform", "网易云暂未接受本次请求");
    }
    static void requireEndpoint(URL url) throws IOException {
        String endpoint = url.getProtocol() + "://" + url.getHost() + url.getPath();
        if ((!KEY.equals(endpoint) && !POLL.equals(endpoint) && !STATUS.equals(endpoint)
                && !USER_PLAYLIST.equals(endpoint) && !PLAYLIST_DETAIL.equals(endpoint)
                && !SONG_DETAIL.equals(endpoint) && !SONG_URL.equals(endpoint) && !SEARCH.equals(endpoint)
                && !LYRIC.equals(endpoint) && !LIKED_IDS.equals(endpoint) && !SET_LIKE.equals(endpoint))
                || url.getUserInfo() != null
                || url.getQuery() != null || url.getRef() != null || (url.getPort() != -1 && url.getPort() != 443))
            throw error("endpoint", "请求目标不在允许的网易云官方接口范围内");
    }
    private static Response executeHttps(Request request) throws IOException {
        HttpsURLConnection connection = null;
        try {
            URL url = new URL(request.endpoint); requireEndpoint(url);
            connection = (HttpsURLConnection)url.openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(18000); connection.setReadTimeout(18000);
            connection.setUseCaches(false); connection.setRequestMethod("POST"); connection.setDoOutput(true);
            for (Map.Entry<String,String> row : request.headers.entrySet()) connection.setRequestProperty(row.getKey(), row.getValue());
            connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8");
            byte[] sent = request.body.getBytes(StandardCharsets.UTF_8); connection.setFixedLengthStreamingMode(sent.length);
            try (OutputStream stream = connection.getOutputStream()) { stream.write(sent); }
            int http = connection.getResponseCode();
            if (http >= 300 && http < 400) throw error("redirect", "网易云返回重定向，本次请求停止");
            if (http < 200 || http >= 300) throw error("http", "网易云官方服务暂时拒绝请求");
            if (connection.getContentLengthLong() > LIMIT) throw error("protocol", "网易云响应超过限制");
            ByteArrayOutputStream received = new ByteArrayOutputStream();
            try (InputStream stream = connection.getInputStream()) {
                byte[] chunk = new byte[4096]; int count;
                long deadline = System.nanoTime() + 30_000_000_000L;
                while ((count = stream.read(chunk)) != -1) {
                    if (System.nanoTime() >= deadline) throw error("timeout", "网易云请求超时");
                    if (count > LIMIT - received.size()) throw error("protocol", "网易云响应超过限制");
                    received.write(chunk, 0, count);
                }
            }
            JSONObject json;
            try { json = new JSONObject(new String(received.toByteArray(), StandardCharsets.UTF_8)); }
            catch (JSONException invalid) { throw error("protocol", "网易云未返回有效数据"); }
            Map<String,List<String>> headers = new LinkedHashMap<>();
            int size = 0;
            for (Map.Entry<String,List<String>> row : connection.getHeaderFields().entrySet()) {
                if (!"Set-Cookie".equalsIgnoreCase(row.getKey())) continue;
                for (String value : row.getValue()) { size += value.length(); if (size > 32768) throw error("protocol", "网易云响应头超过限制"); }
                headers.put(row.getKey(), row.getValue());
            }
            return new Response(json, headers);
        } catch (ApiException safe) { throw safe; }
        catch (java.net.SocketTimeoutException timeout) { throw error("timeout", "网易云请求超时"); }
        catch (IOException network) { throw error("network", "无法连接网易云官方服务"); }
        finally { if (connection != null) connection.disconnect(); }
    }
    static String form(Map<String,String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String,String> row : params.entrySet()) {
            if (out.length() > 0) out.append('&'); out.append(encode(row.getKey())).append('=').append(encode(row.getValue()));
        }
        return out.toString();
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); } catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private static JSONObject object(Object... pairs) throws IOException {
        JSONObject object = new JSONObject(); for (int i = 0; i < pairs.length; i += 2) put(object, (String)pairs[i], pairs[i+1]); return object;
    }
    private static void put(JSONObject object, String key, Object value) throws IOException {
        try { object.put(key, value); } catch (JSONException invalid) { throw error("protocol", "本机请求格式无效"); }
    }
    private static Map<String,String> immutable(Map<String,String> source) { return Collections.unmodifiableMap(new LinkedHashMap<>(source)); }
    private static ApiException error(String code, String message) { return new ApiException(code, message); }
}
