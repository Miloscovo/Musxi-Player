package tech.musxi.loginpoc;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.net.ssl.HttpsURLConnection;

/**
 * Direct, device-local login experiment; call on a worker thread.
 * Protocol adapted from KuGouMusicApi a5a98013cce79fe0ae2ad65fc84b68176ebcfc1e,
 * module/login_qr_{key,check,create}.js, module/user_detail.js and util/{request,helper,crypto}.js.
 * Copyright (c) 2023 MakcRe, MIT. See the accompanying third-party license.
 * No fingerprint simulation, CAPTCHA bypass, proxy server, or credential logging.
 */
public final class KugouApi {
    private static final String WEB_SALT = "NVPh5oo715z5DIWAeQlhMDsWXXQV4hwt";
    private static final String LITE_SALT = "LnT6xpN3khm36zse0QzvmgTZ3waWdRSA";
    // Public protocol RSA key from util/crypto.js, not a private credential.
    private static final BigInteger RSA_MODULUS = new BigInteger(
        "c40a2d0da76511f3bb1cc2bbd3afbd8bea83b4d6b05b6c13eb8920c53f1af7679b32ba0d0edb843240ef1b836efed3ee240734c14c1399fd6594d16af22f52525d14d72e0155c6dcc8638d4f7bb94f3a0b1f4c29f991972f2a160a25eb0a9e724336be7f69bbd319ffab1c6dd8470b021dc434f3faba89f4a2a01b33bdbdd08b", 16);
    private static final int RESPONSE_LIMIT = 256 * 1024;
    private static final int TIMEOUT_MS = 18000;
    private final Device device;
    private final Transport transport;

    public static final class Device {
        public final String mid;
        public Device(String mid) {
            if (mid == null || !mid.matches("[a-fA-F0-9]{32}"))
                throw new IllegalArgumentException("设备标识无效");
            this.mid = mid;
        }
    }

    public static final class Session {
        public final String token, userId, nickname;
        /** True only when the authenticated profile response echoed matching account IDs. */
        public final boolean accountIdEchoed;
        public Session(String token, String userId, String nickname) {
            this(token, userId, nickname, false);
        }
        private Session(String token, String userId, String nickname, boolean accountIdEchoed) {
            if (!validToken(token) || !validUserId(userId))
                throw new IllegalArgumentException("登录凭证无效");
            this.token = token;
            this.userId = userId;
            this.nickname = cleanNickname(nickname);
            this.accountIdEchoed = accountIdEchoed;
        }
    }

    public static final class Poll {
        /** 0 expired, 1 waiting, 2 confirmation pending, 4 credentials received. */
        public final int status;
        public final Session session;
        public Poll(int status, Session session) {
            if ((status != 0 && status != 1 && status != 2 && status != 4)
                || (status == 4) != (session != null))
                throw new IllegalArgumentException("登录状态无效");
            this.status = status;
            this.session = session;
        }
    }

    public enum ProbeResult { REJECTED, ACCEPTED }

    /** Sanitized diagnostics: neither server bodies nor URLs appear in messages or causes. */
    public static final class ApiException extends IOException {
        public final String code;
        /** Fixed identity checks and bounded, sanitized field/type schema; no response values. */
        public final String identityDiagnostic;
        ApiException(String code, String message) { this(code, message, null); }
        ApiException(String code, String message, String identityDiagnostic) {
            super(message);
            this.code = code;
            this.identityDiagnostic = identityDiagnostic;
        }
    }

    interface Transport { JSONObject execute(Request request) throws IOException; }

    static final class Request {
        final String endpoint, body;
        final Map<String, String> params, headers;
        Request(String endpoint, Map<String, String> params, String body, Map<String, String> headers) {
            this.endpoint = endpoint;
            this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
            this.body = body;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }
    }

    public KugouApi(Device device) { this(device, KugouApi::executeHttps); }
    KugouApi(Device device, Transport transport) {
        if (device == null || transport == null) throw new IllegalArgumentException("缺少登录配置");
        this.device = device;
        this.transport = transport;
    }

    public String createLogin() throws IOException {
        return parseCreate(transport.execute(createRequest(device, now())));
    }

    public Poll poll(String key) throws IOException {
        requireKey(key);
        return parsePoll(transport.execute(pollRequest(device, key, now())));
    }

    /** Checks the platform session via an authenticated profile request, matching IDs when echoed. */
    public Session verify(Session session) throws IOException {
        if (session == null) throw error("credentials", "请先完成官方登录确认");
        return parseVerify(transport.execute(verifyRequest(device, session, now())), session);
    }

    JSONObject libraryCall(String path, Session session, Map<String, String> extra, String body, boolean audio) throws IOException {
        if (!java.util.Arrays.asList("/v7/get_all_list", "/v4/get_list_all_file", "/v5/url", "/v3/search/song").contains(path)
            || audio != "/v5/url".equals(path))
            throw error("endpoint", "Unsupported library endpoint");
        long time = now();
        Map<String, String> params = defaults(device, time);
        params.put("userid", session.userId); params.put("token", session.token);
        params.putAll(extra);
        if (audio) params.put("key", md5(params.get("hash") + "185672dd44712f60bb1736df5a377e82" + "3116" + device.mid + session.userId));
        params.put("signature", androidSignature(params, body));
        Map<String, String> requestHeaders = headers(device, time);
        requestHeaders.put("x-router", audio ? "trackercdn.kugou.com" : "/v3/search/song".equals(path) ? "complexsearch.kugou.com" : "cloudlist.service.kugou.com");
        return transport.execute(new Request("https://gateway.kugou.com" + path, params, body, requestHeaders));
    }

    /**
     * Manual PoC negative control only. Makes exactly one read-only profile request
     * with a random invalid token; never retries, saves, or returns the probe token.
     * A rejection is evidence about this probe, not a production authentication guarantee.
     */
    public ProbeResult probeInvalidToken(Session current) throws IOException {
        if (current == null) throw error("credentials", "请先完成官方登录确认");
        SecureRandom random = new SecureRandom();
        String alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        String fake;
        do {
            StringBuilder text = new StringBuilder(current.token.length());
            for (int i = 0; i < current.token.length(); i++) text.append(alphabet.charAt(random.nextInt(alphabet.length())));
            fake = text.toString();
        } while (fake.equals(current.token));
        Session probe = new Session(fake, current.userId, current.nickname);
        JSONObject body = transport.execute(verifyRequest(device, probe, now()));
        if (body == null) throw error("protocol", "酷狗响应内容无效");
        if (present(body, "ssaCode") || present(body, "ssa_code"))
            throw error("risk", "账号需要在酷狗官方客户端完成安全验证，请验证后重试");
        String status = scalar(body, "status"), code = scalar(body, "error_code");
        boolean explicitFailureCode = code.matches("-?[0-9]{1,12}") && new BigInteger(code).signum() != 0;
        if ("0".equals(status) || explicitFailureCode) return ProbeResult.REJECTED;
        JSONObject data = successData(body);
        parseVerify(body, probe); // Reject mismatched or malformed account-ID echoes.
        if (!validProfile(data)) throw error("protocol", "探针返回的数据不足以判断资料接口是否接受无效凭证");
        return ProbeResult.ACCEPTED;
    }

    public static String authorizationUrl(String key) {
        if (!validKey(key)) throw new IllegalArgumentException("登录请求标识无效");
        return "https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=" + encode(key);
    }

    public static String webSignature(Map<String, String> params) {
        // Upstream sorts complete key=value strings for web signatures.
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, String> entry : params.entrySet())
            values.add(entry.getKey() + "=" + entry.getValue());
        Collections.sort(values);
        StringBuilder text = new StringBuilder(WEB_SALT);
        for (String value : values) text.append(value);
        return md5(text.append(WEB_SALT).toString());
    }

    static String androidSignature(Map<String, String> params, String body) {
        List<String> keys = new ArrayList<>(params.keySet());
        Collections.sort(keys);
        StringBuilder text = new StringBuilder(LITE_SALT);
        for (String key : keys) text.append(key).append('=').append(params.get(key));
        return md5(text.append(body == null ? "" : body).append(LITE_SALT).toString());
    }

    static Request createRequest(Device device, long time) {
        Map<String, String> params = defaults(device, time);
        params.put("appid", "1001");
        params.put("type", "1");
        params.put("plat", "4");
        params.put("qrcode_txt", "https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&");
        params.put("srcappid", "2919");
        params.put("signature", webSignature(params));
        return new Request("https://login-user.kugou.com/v2/qrcode", params, null, headers(device, time));
    }

    static Request pollRequest(Device device, String key, long time) throws IOException {
        requireKey(key);
        Map<String, String> params = defaults(device, time);
        params.put("plat", "4");
        params.put("srcappid", "2919");
        params.put("qrcode", key);
        params.put("signature", webSignature(params));
        return new Request("https://login-user.kugou.com/v2/get_userinfo_qrcode", params, null, headers(device, time));
    }

    static Request verifyRequest(Device device, Session session, long time) throws IOException {
        String plaintext = "{\"token\":" + JSONObject.quote(session.token) + ",\"clienttime\":" + time + "}";
        String encrypted = rsaEncrypt(plaintext);
        // Deterministic serialization is necessary: exactly these bytes enter the signature.
        String body = "{\"visit_time\":" + time + ",\"usertype\":1,\"p\":\"" + encrypted
            + "\",\"userid\":" + session.userId + "}";
        Map<String, String> params = defaults(device, time);
        params.put("token", session.token);
        params.put("userid", session.userId);
        params.put("plat", "1");
        params.put("signature", androidSignature(params, body));
        Map<String, String> headers = headers(device, time);
        headers.put("x-router", "usercenter.kugou.com");
        return new Request("https://gateway.kugou.com/v3/get_my_info", params, body, headers);
    }

    static String rsaEncrypt(String plaintext) throws IOException {
        byte[] bytes = plaintext.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > 128) throw error("credentials", "登录凭证超出当前协议支持范围");
        // Upstream raw RSA right-pads UTF-8 plaintext with zeros to the 1024-bit key size.
        byte[] padded = new byte[128];
        System.arraycopy(bytes, 0, padded, 0, bytes.length);
        BigInteger input = new BigInteger(1, padded);
        if (input.compareTo(RSA_MODULUS) >= 0) throw error("credentials", "登录凭证不符合协议要求");
        String hex = input.modPow(BigInteger.valueOf(65537), RSA_MODULUS).toString(16);
        StringBuilder out = new StringBuilder(256);
        for (int i = hex.length(); i < 256; i++) out.append('0');
        return out.append(hex).toString().toUpperCase(java.util.Locale.ROOT);
    }

    static String parseCreate(JSONObject body) throws IOException {
        JSONObject data = successData(body);
        String key = scalar(data, "qrcode");
        requireKey(key);
        return key;
    }

    static Poll parsePoll(JSONObject body) throws IOException {
        JSONObject data = successData(body);
        String status = scalar(data, "status");
        if ("0".equals(status)) return new Poll(0, null);
        if ("1".equals(status)) return new Poll(1, null);
        if ("2".equals(status)) return new Poll(2, null);
        if (!"4".equals(status)) throw error("protocol", "酷狗返回未知登录状态，请重新发起登录");
        String token = scalar(data, "token"), userId = scalar(data, "userid");
        if (!validToken(token) || !validUserId(userId))
            throw error("credentials", "酷狗未返回完整登录凭证，请重新登录");
        String nickname = scalar(data, "nickname");
        if (nickname.isEmpty()) nickname = scalar(data, "username");
        return new Poll(4, new Session(token, userId, nickname));
    }

    static Session parseVerify(JSONObject body, Session session) throws IOException {
        JSONObject data = successData(body);
        // Defensive compatibility candidates, not a claim that each alias has been
        // observed on the live endpoint. vendor module/scene_lists_v2.js,
        // user_video_love.js and youth_day_vip_upgrade.js explicitly map userid to
        // kugouid; kugou_id is its defensive naming alias, not a confirmed response.
        // Every present ID must validate and agree.
        String[] containers = {"", "userinfo", "user_info"};
        String[] fields = {"userid", "user_id", "userId", "kugouid", "kugou_id"};
        boolean found = false, allMatch = true;
        StringBuilder diagnostic = new StringBuilder();
        for (String container : containers) {
            JSONObject parent = container.isEmpty() ? data : data.optJSONObject(container);
            boolean malformedParent = !container.isEmpty() && data.has(container) && parent == null;
            for (String field : fields) {
                boolean present = parent != null && parent.has(field);
                Object value = present ? parent.opt(field) : null;
                String type = malformedParent ? "other" : !present ? "missing"
                    : value instanceof String ? "string" : value instanceof Number ? "number" : "other";
                String candidate = value instanceof String || value instanceof Number ? String.valueOf(value) : "";
                boolean valid = validUserId(candidate);
                boolean matches = valid && session.userId.equals(candidate);
                if (present) found = true;
                if (malformedParent || (present && !matches)) allMatch = false;
                if (diagnostic.length() > 0) diagnostic.append('\n');
                diagnostic.append("data.");
                if (!container.isEmpty()) diagnostic.append(container).append('.');
                diagnostic.append(field).append(": ").append(type)
                    .append(",valid=").append(valid).append(",matches=").append(matches);
            }
        }
        String nickname = scalar(data, "nickname");
        // The live profile endpoint can return a profile without echoing an account ID.
        // Credentials still come exclusively from this request's official status-4 reply.
        // A recognized profile shape corroborates session validity, not an independent ID match.
        boolean profileValid = validProfile(data);
        if (!allMatch || (!found && !profileValid)) {
            appendSchema(data, "data", 0, new int[] {0}, diagnostic);
            throw new ApiException("identity", "酷狗账号身份校验未通过，请重新登录", diagnostic.toString());
        }
        return new Session(session.token, session.userId, nickname, found);
    }

    private static boolean validProfile(JSONObject data) {
        String nickname = scalar(data, "nickname");
        return data.opt("nickname") instanceof String && !nickname.isEmpty()
            && !cleanNickname(nickname).trim().isEmpty() && hasProfileScalar(data);
    }

    private static boolean hasProfileScalar(JSONObject data) {
        for (String field : new String[] {"sex", "birthday", "pic", "actor_status"}) {
            Object value = data.opt(field);
            if ("birthday".equals(field) || "pic".equals(field)) {
                if (value instanceof String && !((String) value).trim().isEmpty()
                    && ((String) value).length() <= 4096) return true;
            } else if (value instanceof Number || value instanceof String) {
                String number = String.valueOf(value);
                if (number.matches("[0-9]{1,10}")) return true;
            }
        }
        return false;
    }

    /** Development-only shape summary: root plus at most two object levels, 24 nodes total. */
    private static void appendSchema(Object value, String path, int depth, int[] count, StringBuilder out) {
        if (count[0] >= 24) return;
        String type = value == null || value == JSONObject.NULL ? "null"
            : value instanceof JSONObject ? "object" : value instanceof org.json.JSONArray ? "array"
            : value instanceof String ? "string" : value instanceof Number ? "number"
            : value instanceof Boolean ? "boolean" : null;
        if (type == null) return;
        out.append("\nschema:").append(path).append(": ").append(type);
        count[0]++;
        if (!(value instanceof JSONObject) || depth >= 2) return;
        JSONObject object = (JSONObject) value;
        List<String> keys = new ArrayList<>();
        java.util.Iterator<String> names = object.keys();
        while (names.hasNext()) {
            String name = names.next();
            if (safeSchemaKey(name)) keys.add(name);
        }
        Collections.sort(keys, (left, right) -> {
            int priority = Integer.compare(schemaPriority(left), schemaPriority(right));
            return priority == 0 ? left.compareTo(right) : priority;
        });
        for (String key : keys) {
            if (count[0] >= 24) break;
            appendSchema(object.opt(key), path + "." + key, depth + 1, count, out);
        }
    }

    private static boolean safeSchemaKey(String key) {
        if (!key.matches("[A-Za-z_][A-Za-z0-9_]{0,39}")) return false;
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        for (String sensitive : new String[] {"token", "cookie", "secret", "password", "credential",
            "session", "auth", "passwd", "secu", "ticket", "phone", "mobile", "email", "signature"})
            if (lower.contains(sensitive)) return false;
        return true;
    }

    private static int schemaPriority(String key) {
        String lower = key.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("id") || lower.contains("user") || lower.contains("kugou") || lower.contains("info") ? 0 : 1;
    }

    private static JSONObject successData(JSONObject body) throws IOException {
        if (body == null) throw error("protocol", "酷狗响应内容无效");
        if (present(body, "ssaCode") || present(body, "ssa_code"))
            throw error("risk", "账号需要在酷狗官方客户端完成安全验证，请验证后重试");
        String status = scalar(body, "status");
        String code = scalar(body, "error_code");
        if ((!"1".equals(status) && !"200".equals(status))
            || (body.has("error_code") && !"0".equals(code)))
            throw error("platform", "酷狗未接受请求，请在官方客户端确认账号状态或稍后重试");
        JSONObject data = body.optJSONObject("data");
        if (data == null) throw error("protocol", "酷狗响应结构发生变化，请暂停验证");
        return data;
    }

    private static boolean present(JSONObject body, String key) {
        return body.has(key) && !body.isNull(key) && !"".equals(body.opt(key)) && !"0".equals(String.valueOf(body.opt(key)));
    }

    private static String scalar(JSONObject object, String key) {
        Object value = object.opt(key);
        return value instanceof String || value instanceof Number ? String.valueOf(value) : "";
    }

    private static Map<String, String> defaults(Device device, long time) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dfid", "-"); params.put("mid", device.mid); params.put("uuid", "-");
        params.put("appid", "3116"); params.put("clientver", "11440");
        params.put("clienttime", String.valueOf(time));
        return params;
    }

    private static Map<String, String> headers(Device device, long time) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
        headers.put("dfid", "-"); headers.put("mid", device.mid);
        headers.put("clienttime", String.valueOf(time)); headers.put("kg-rc", "1");
        headers.put("kg-thash", "5d816a0"); headers.put("kg-rec", "1");
        headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
        return headers;
    }

    static String query(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (out.length() > 0) out.append('&');
            out.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return out.toString();
    }

    private static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }

    private static JSONObject executeHttps(Request request) throws IOException {
        HttpsURLConnection connection = null;
        String stage = "connect";
        try {
            URL url = new URL(request.endpoint + "?" + query(request.params));
            requireEndpoint(url);
            connection = (HttpsURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> header : request.headers.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            if (request.body != null) {
                connection.setRequestMethod("POST");
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] body = request.body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode();
            stage = "read";
            String risk = connection.getHeaderField("ssa-code");
            if (risk != null && !risk.isEmpty() && !"0".equals(risk))
                throw error("risk", "账号需要在酷狗官方客户端完成安全验证，请验证后重试");
            if (status >= 300 && status < 400) throw error("redirect", "酷狗返回重定向，当前验证已停止");
            if (status < 200 || status >= 300) throw error("http", "酷狗服务暂时拒绝请求，请稍后重试");
            int responseLimit = request.endpoint.endsWith("/get_all_list") || request.endpoint.endsWith("/get_list_all_file") ? 2 * 1024 * 1024 : RESPONSE_LIMIT;
            if (connection.getContentLengthLong() > responseLimit)
                throw error("protocol", "酷狗响应超过验证程序限制");
            String body;
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] chunk = new byte[4096];
                int count;
                long deadline = System.nanoTime() + 30_000_000_000L;
                while ((count = in.read(chunk)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw error("cancelled", "当前请求已停止");
                    if (System.nanoTime() > deadline) throw error("timeout", "酷狗请求超时，请检查网络后重试");
                    if (count > responseLimit - out.size()) throw error("protocol", "酷狗响应超过验证程序限制");
                    out.write(chunk, 0, count);
                }
                body = new String(out.toByteArray(), StandardCharsets.UTF_8);
            }
            try { return new JSONObject(body); }
            catch (JSONException malformed) { throw error("protocol", "酷狗未返回有效数据，请稍后重试"); }
        } catch (ApiException safe) { throw safe; }
        catch (SocketTimeoutException timeout) { throw error("timeout", "酷狗请求超时，请检查网络后重试"); }
        catch (IOException network) {
            String kind = network instanceof javax.net.ssl.SSLException ? "ssl" : network instanceof java.net.UnknownHostException ? "dns" :
                network instanceof java.net.ConnectException ? "connect" : network instanceof java.io.EOFException ? "eof" : "io";
            throw new ApiException("network", "无法连接酷狗官方服务，请检查网络后重试", "transport_" + stage + "_" + kind);
        }
        finally { if (connection != null) connection.disconnect(); }
    }

    static void requireEndpoint(URL url) throws IOException {
        boolean login = "login-user.kugou.com".equals(url.getHost())
            && ("/v2/qrcode".equals(url.getPath()) || "/v2/get_userinfo_qrcode".equals(url.getPath()));
        boolean profile = "gateway.kugou.com".equals(url.getHost()) && "/v3/get_my_info".equals(url.getPath());
        boolean library = "gateway.kugou.com".equals(url.getHost()) && java.util.Arrays.asList("/v7/get_all_list", "/v4/get_list_all_file", "/v5/url", "/v3/search/song").contains(url.getPath());
        if (!"https".equals(url.getProtocol()) || (!login && !profile && !library)
            || url.getUserInfo() != null || (url.getPort() != -1 && url.getPort() != 443) || url.getRef() != null)
            throw error("endpoint", "请求目标不在允许的酷狗官方接口范围内");
    }

    private static String md5(String text) {
        try {
            byte[] hash = MessageDigest.getInstance("MD5").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(32);
            for (byte value : hash) out.append(Character.forDigit((value >>> 4) & 15, 16)).append(Character.forDigit(value & 15, 16));
            return out.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new AssertionError(impossible); }
    }

    private static boolean validKey(String key) { return key != null && key.matches("[A-Za-z0-9_-]{1,512}"); }
    private static boolean validToken(String token) { return token != null && token.matches("[!-~]{1,80}"); }
    private static boolean validUserId(String userId) {
        if (userId == null || !userId.matches("[1-9][0-9]{0,15}")) return false;
        return new BigInteger(userId).compareTo(BigInteger.valueOf(9007199254740991L)) <= 0;
    }
    private static String cleanNickname(String nickname) {
        if (nickname == null || nickname.isEmpty()) return "酷狗用户";
        String result = nickname.replaceAll("[\\p{Cc}\\p{Cf}]", "");
        return result.length() > 120 ? result.substring(0, 120) : result;
    }
    private static void requireKey(String key) throws IOException {
        if (!validKey(key)) throw error("protocol", "登录请求标识无效，请重新发起登录");
    }
    private static long now() { return System.currentTimeMillis() / 1000; }
    private static ApiException error(String code, String message) { return new ApiException(code, message); }
}
