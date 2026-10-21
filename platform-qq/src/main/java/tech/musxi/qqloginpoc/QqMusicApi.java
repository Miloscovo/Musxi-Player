package tech.musxi.qqloginpoc;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpCookie;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.HttpsURLConnection;

/**
 * Device-local HTTPS experiment. Run on a worker thread; no global cookie jar.
 * Protocol adapted from QQMusicApi 0.8.1, commit
 * 3fc57f02e4eddb68a069bba4e0e12a7893f2a814, by Luren and contributors,
 * modules/login.py, core/versioning.py and models/request.py; GPL-3.0-or-later.
 * WEB common parameters are a candidate awaiting actual device verification.
 */
public final class QqMusicApi {
    private static final String SHOW = "https://ssl.ptlogin2.qq.com/ptqrshow";
    private static final String POLL = "https://ssl.ptlogin2.qq.com/ptqrlogin";
    private static final String CHECK = "https://ssl.ptlogin2.graph.qq.com/check_sig";
    private static final String AUTHORIZE = "https://graph.qq.com/oauth2.0/authorize";
    private static final String MUSIC = "https://u.y.qq.com/cgi-bin/musicu.fcg";
    private static final String REDIRECT = "https://y.qq.com/portal/wx_redirect.html?login_type=1&surl=https://y.qq.com/";
    private static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";
    private static final int RESPONSE_LIMIT = 256 * 1024, PNG_LIMIT = 1024 * 1024;
    private static final Pattern CALLBACK = Pattern.compile("\\A\\s*ptuiCB\\((.*)\\)\\s*;?\\s*\\z", Pattern.DOTALL);
    private final Transport transport;

    public static final class Attempt {
        /** Legacy image fallback only; type=1 creates one textual attempt and no second QR. */
        public final byte[] png;
        /** Only pass to the explicit official QQ intent; never render or log this value. */
        public final String authorizationPayload;
        private final String qrsig;
        private Attempt(String authorizationPayload, String qrsig) {
            this.png = null; this.authorizationPayload = authorizationPayload; this.qrsig = qrsig;
        }
    }

    public static final class Session {
        public final String musicId, nickname;
        private final JSONObject credential;
        private Session(JSONObject credential, String nickname) throws IOException {
            this.credential = copyCredential(credential);
            musicId = scalar(this.credential, "musicid");
            if (!validId(musicId) || !safeSecret(scalar(this.credential, "musickey")))
                throw error("credentials", "QQ 音乐未返回有效音乐凭证");
            this.nickname = cleanName(nickname);
        }
        /** A fresh copy for encrypted local storage only; never render or log it. */
        public JSONObject toJson() throws IOException {
            try { return new JSONObject().put("credential", copyCredential(credential)).put("nickname", nickname); }
            catch (JSONException invalid) { throw error("storage", "本地音乐会话格式无效"); }
        }
        public static Session fromJson(JSONObject value) throws IOException {
            if (value == null || value.optJSONObject("credential") == null)
                throw error("storage", "本地音乐会话格式无效");
            return new Session(value.optJSONObject("credential"), scalar(value, "nickname"));
        }
    }

    public static final class Poll {
        /** 66 waiting, 67 confirmation pending, 65 expired, 68 refused, 0 music credentials exchanged. */
        public final int status;
        public final Session session;
        private Poll(int status, Session session) { this.status = status; this.session = session; }
    }

    public enum ProbeResult { REJECTED, ACCEPTED }

    public static final class ApiException extends IOException {
        public final String code, diagnostic;
        ApiException(String code, String message) { this(code, message, null); }
        ApiException(String code, String message, String diagnostic) { super(message); this.code = code; this.diagnostic = diagnostic; }
    }

    interface Transport { Response execute(Request request) throws IOException; }
    static final class Request {
        final String endpoint, body, contentType;
        final Map<String, String> params, headers;
        final int limit;
        final boolean redirectResponse;
        Request(String endpoint, Map<String, String> params, String body, String contentType,
                Map<String, String> headers, int limit, boolean redirectResponse) {
            this.endpoint = endpoint; this.params = immutable(params); this.body = body;
            this.contentType = contentType; this.headers = immutable(headers);
            this.limit = limit; this.redirectResponse = redirectResponse;
        }
    }
    static final class Response {
        final int status;
        final byte[] body;
        final Map<String, List<String>> headers;
        Response(int status, byte[] body, Map<String, List<String>> headers) {
            this.status = status; this.body = body.clone();
            Map<String, List<String>> copy = new LinkedHashMap<>();
            for (Map.Entry<String, List<String>> entry : headers.entrySet())
                copy.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
            this.headers = Collections.unmodifiableMap(copy);
        }
        String header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet())
                if (name.equalsIgnoreCase(entry.getKey()) && !entry.getValue().isEmpty()) return entry.getValue().get(0);
            return "";
        }
    }

    public QqMusicApi() { this(QqMusicApi::executeHttps); }
    QqMusicApi(Transport transport) { if (transport == null) throw new IllegalArgumentException("缺少网络配置"); this.transport = transport; }

    public Attempt createLogin() throws IOException {
        // Official mobile ptlogin uses type=1 JSONP to open this same request in QQ.
        Map<String, String> params = map("s", "8", "e", "0", "appid", "716027609", "type", "1",
            "t", String.valueOf(new SecureRandom().nextDouble()), "u1", "https://graph.qq.com/oauth2.0/login_jump",
            "daid", "383", "pt_3rd_aid", "100497308");
        Response response = execute(new Request(SHOW, params, null, null, loginHeaders(), RESPONSE_LIMIT, false));
        String qrsig = cookies(response, "ssl.ptlogin2.qq.com").get("qrsig");
        String text = new String(response.body, StandardCharsets.UTF_8);
        Matcher match = Pattern.compile("\\A\\s*ptui_qrcode_CB\\(\\s*(\\{.*\\})\\s*\\)\\s*;?\\s*\\z", Pattern.DOTALL).matcher(text);
        if (!match.matches()) throw error("protocol", "QQ 登录请求响应格式发生变化");
        JSONObject value = json(match.group(1).getBytes(StandardCharsets.UTF_8));
        Object ec = value.opt("ec"), payload = value.opt("qrcode");
        if (!(ec instanceof Number) || !"0".equals(String.valueOf(ec)) || !(payload instanceof String)
            || !safePayload((String)payload) || !safeSecret(qrsig))
            throw error("protocol", "QQ 未返回有效同机登录请求");
        return new Attempt((String)payload, qrsig);
    }

    public Poll poll(Attempt attempt) throws IOException {
        if (attempt == null || !safeSecret(attempt.qrsig)) throw error("request", "请先创建 QQ 登录请求");
        Map<String, String> params = map("u1", "https://graph.qq.com/oauth2.0/login_jump",
            "ptqrtoken", String.valueOf(hash33(attempt.qrsig, 0)), "ptredirect", "0", "h", "1", "t", "1", "g", "1",
            "from_ui", "1", "ptlang", "2052", "action", "0-0-" + System.currentTimeMillis(), "js_ver", "20102616",
            "js_type", "1", "pt_uistyle", "40", "aid", "716027609", "daid", "383", "pt_3rd_aid", "100497308", "has_onekey", "1");
        Map<String, String> headers = loginHeaders(); headers.put("Cookie", "qrsig=" + attempt.qrsig);
        Response response = execute(new Request(POLL, params, null, null, headers, RESPONSE_LIMIT, false));
        List<String> args = callback(new String(response.body, StandardCharsets.UTF_8));
        String status = args.get(0);
        if ("66".equals(status) || "67".equals(status) || "65".equals(status) || "68".equals(status))
            return new Poll(Integer.parseInt(status), null);
        if (!"0".equals(status) || args.size() < 3) throw error("protocol", "QQ 返回未知登录状态，请重新创建请求");
        URL result = checkedUrl(args.get(2), "ssl.ptlogin2.graph.qq.com", "/check_sig");
        Map<String, String> query = parseQuery(result.getQuery());
        String uin = query.get("uin"), sigx = query.get("ptsigx");
        if (!validId(uin) || !safeSecret(sigx)) throw error("protocol", "QQ 授权结果缺少必要参数");
        return new Poll(0, exchange(uin, sigx));
    }

    private Session exchange(String uin, String sigx) throws IOException {
        Map<String, String> params = map("uin", uin, "pttype", "1", "service", "ptqrlogin", "nodirect", "0", "ptsigx", sigx,
            "s_url", "https://graph.qq.com/oauth2.0/login_jump", "ptlang", "2052", "ptredirect", "100", "aid", "716027609",
            "daid", "383", "j_later", "0", "low_login_hour", "0", "regmaster", "0", "pt_login_type", "3", "pt_aid", "0",
            "pt_aaid", "16", "pt_light", "0", "pt_3rd_aid", "100497308");
        Response checked = execute(new Request(CHECK, params, null, null, loginHeaders(), RESPONSE_LIMIT, true));
        Map<String, String> cookie = cookies(checked, "ssl.ptlogin2.graph.qq.com");
        String skey = cookie.get("p_skey");
        if (!safeSecret(skey)) throw error("authorize", "QQ 未返回完整授权结果，请重试");
        Map<String, String> form = map("response_type", "code", "client_id", "100497308", "redirect_uri", REDIRECT,
            "scope", "get_user_info,get_app_friends", "state", "state", "switch", "", "from_ptlogin", "1", "src", "1",
            "update_auth", "1", "openapi", "1010_1030", "g_tk", String.valueOf(hash33(skey, 5381)),
            "auth_time", String.valueOf((System.currentTimeMillis() / 1000) * 1000), "ui", UUID.randomUUID().toString());
        Map<String, String> headers = baseHeaders(); headers.put("Cookie", cookieHeader(cookie));
        Response authorized = execute(new Request(AUTHORIZE, Collections.emptyMap(), query(form), "application/x-www-form-urlencoded; charset=utf-8",
            headers, RESPONSE_LIMIT, true));
        if (authorized.status < 300 || authorized.status >= 400) throw error("authorize", "QQ 授权未完成，请在官方客户端检查");
        URL redirect = checkedUrl(authorized.header("Location"), "y.qq.com", "/portal/wx_redirect.html");
        Map<String, String> result = parseQuery(redirect.getQuery());
        String code = result.get("code");
        if (!safeSecret(code) || !"state".equals(result.get("state")))
            throw error("authorize", "QQ 未返回有效音乐授权码");
        JSONObject data = cgi("QQConnectLogin.LoginServer", "QQLogin", object("code", code), null);
        return new Session(data, "QQ 音乐用户");
    }

    /** Verify with the music login-info endpoint, not the public nickname endpoint. */
    public Session verify(Session session) throws IOException {
        if (session == null) throw error("credentials", "缺少 QQ 音乐会话");
        JSONObject data = cgi("music.UserInfo.userInfoServer", "GetLoginUserInfo", new JSONObject(), session);
        if (data.length() == 0) throw error("identity", "音乐身份校验未返回有效数据");
        for (String name : new String[] {"musicid", "musicId", "str_musicid"}) {
            if (data.has(name) && !session.musicId.equals(scalar(data, name)))
                throw error("identity", "音乐身份校验账号不一致");
        }
        String nickname = session.nickname;
        String euin = scalar(session.credential, "encryptUin");
        if (!euin.isEmpty()) {
            try {
                JSONObject profile = cgi("music.UnifiedHomepage.UnifiedHomepageSrv", "GetHomepageHeader",
                    object("uin", euin, "IsQueryTabDetail", 1), session);
                JSONObject info = profile.optJSONObject("Info");
                JSONObject base = info == null ? null : info.optJSONObject("BaseInfo");
                if (base != null) nickname = cleanName(scalar(base, "Name"));
            } catch (ApiException unavailable) { /* Public display info is optional, never identity proof. */ }
        }
        return new Session(session.credential, nickname);
    }

    /** One negative control, same music account with a freshly random invalid key. */
    public ProbeResult probeInvalidToken(Session session) throws IOException {
        if (session == null) throw error("credentials", "缺少 QQ 音乐会话");
        JSONObject invalid = copyCredential(session.credential);
        try { invalid.put("musickey", "musxi-invalid-" + UUID.randomUUID()); }
        catch (JSONException impossible) { throw error("protocol", "无法创建鉴权对照"); }
        Session probe = new Session(invalid, session.nickname);
        try {
            JSONObject data = cgi("music.UserInfo.userInfoServer", "GetLoginUserInfo", new JSONObject(), probe);
            if (data.length() == 0) throw error("identity", "鉴权对照返回空结构，暂无法判断");
            return ProbeResult.ACCEPTED;
        } catch (ApiException rejected) {
            if ("expired".equals(rejected.code)) return ProbeResult.REJECTED;
            throw rejected;
        }
    }

    // Package-private bounded music surface. The sole write exception is one-song favorites.
    JSONObject library(Session session, String module, String method, JSONObject param) throws IOException {
        String endpoint = module + ":" + method;
        boolean favoriteWrite = favoriteWrite(module, method, param);
        if (session == null || (!favoriteWrite && !java.util.Arrays.asList(
            "music.musicasset.PlaylistBaseRead:GetPlaylistByUin",
            "music.musicasset.PlaylistFavRead:CgiGetPlaylistFavInfo",
            "music.srfDissInfo.DissInfo:CgiGetDiss",
            "music.search.SearchCgiService:DoSearchForQQMusicMobile",
            "music.pf_song_detail_svr:get_song_detail_yqq",
            "music.musichallSong.PlayLyricInfo:GetPlayLyricInfo",
            "music.vkey.GetVkey:UrlGetVkey",
            "music.audioCdnDispatch.cdnDispatch:GetCdnDispatch").contains(endpoint)))
            throw error("endpoint", "Unsupported music operation");
        return cgi(module, method, param, session, 4 * 1024 * 1024);
    }
    private static boolean favoriteWrite(String module, String method, JSONObject param) {
        if (!"music.musicasset.PlaylistDetailWrite".equals(module) ||
            !("AddSonglist".equals(method) || "DelSonglist".equals(method)) ||
            param == null || param.length() != 4) return false;
        Object dirId = param.opt("dirId"), tid = param.opt("tid"), utf8 = param.opt("bFmtUtf8");
        JSONArray songs = param.optJSONArray("v_songInfo");
        if (!integer(dirId, 201, 201) || !integer(tid, 0, 0) ||
            !Boolean.TRUE.equals(utf8) || songs == null || songs.length() != 1) return false;
        JSONObject song = songs.optJSONObject(0);
        if (song == null || song.length() != 2) return false;
        Object songId = song.opt("songId"), songType = song.opt("songType");
        return integer(songId, 1, 9_007_199_254_740_991L) && integer(songType, 0, 1_000_000);
    }
    private static boolean integer(Object value, long minimum, long maximum) {
        if (!(value instanceof Number)) return false;
        String raw = String.valueOf(value);
        if (!raw.matches("(?:0|[1-9][0-9]{0,15})")) return false;
        long number;
        try { number = Long.parseLong(raw); } catch (NumberFormatException invalid) { return false; }
        return number >= minimum && number <= maximum;
    }
    String encryptedUin(Session session) { return scalar(session.credential, "encryptUin"); }
    private JSONObject cgi(String module, String method, JSONObject param, Session session) throws IOException {
        return cgi(module, method, param, session, RESPONSE_LIMIT);
    }
    private JSONObject cgi(String module, String method, JSONObject param, Session session, int limit) throws IOException {
        String key = session == null ? "" : scalar(session.credential, "musickey");
        int gtk = key.isEmpty() ? 5381 : hash33(key, 5381);
        JSONObject comm = object("ct", "24", "cv", "4747474", "platform", "yqq.json", "chid", "0",
            "uin", session == null ? "0" : session.musicId, "g_tk", String.valueOf(gtk), "g_tk_new_20200303", String.valueOf(gtk),
            "format", "json", "inCharset", "utf-8", "outCharset", "utf-8", "notice", "0", "need_new_code", "1", "tmeLoginType", "2");
        // Explicitly provide the actual music key for the protected login-info CGI.
        // Generic WEB comm in the Python library otherwise contains only a key hash.
        if (session != null) {
            put(comm, "qq", session.musicId); put(comm, "authst", key);
            String type = scalar(session.credential, "loginType");
            if (!type.isEmpty()) put(comm, "tmeLoginType", type);
        }
        // Upstream song detail explicitly selects WEB; favorites write uses the
        // default Android profile. Keep this change confined to the single-song
        // 201 write exception rather than changing already-verified read calls.
        if (favoriteWrite(module, method, param)) {
            comm = object("ct", "11", "cv", "20090008", "v", "20090008", "chid", "10003505",
                "qq", session.musicId, "authst", key, "tmeAppID", "qqmusic",
                "tmeLoginType", scalar(session.credential, "loginType"),
                "traceid", "10002_" + session.musicId + "_" + (System.currentTimeMillis() / 1000));
        }
        JSONObject payload = object("comm", comm, "req_0", object("module", module, "method", method, "param", param));
        Map<String, String> headers = baseHeaders();
        if (session != null) headers.put("Cookie", "uin=" + session.musicId + "; qqmusic_uin=" + session.musicId
            + "; qm_keyst=" + key + "; qqmusic_key=" + key);
        Response response = execute(new Request(MUSIC, Collections.emptyMap(), payload.toString(), "application/json; charset=utf-8",
            headers, limit, false));
        JSONObject root = json(response.body);
        requireCode(root, "platform");
        JSONObject item = root.optJSONObject("req_0");
        if (item == null) throw error("protocol", "QQ 音乐响应结构发生变化");
        requireCode(item, "platform");
        JSONObject data = item.optJSONObject("data");
        if (data == null) throw error("protocol", "QQ 音乐响应缺少数据");
        if (data.has("code")) requireCode(data, "platform");
        return data;
    }

    private static void requireCode(JSONObject value, String fallback) throws IOException {
        Object raw = value.opt("code");
        if (!(raw instanceof Number) || !String.valueOf(raw).matches("-?[0-9]{1,10}"))
            throw error("protocol", "QQ 音乐响应缺少有效状态码");
        long code = ((Number)raw).longValue();
        if (code == 0) return;
        if (code == 1000 || code == 104400 || code == 104401) throw error("expired", "QQ 音乐凭证无效或已过期，请重新登录");
        if (code == 2000) throw error("signature", "音乐接口要求额外签名，当前候选方案尚未通过");
        if (code == 104604 || code == 2001 || code == 100001) throw error("rate", "请求过于频繁，请稍后重试");
        throw new ApiException(fallback, "QQ 音乐未接受请求，请在官方客户端检查账号状态", "cgi_code_" + code);
    }

    private Response execute(Request request) throws IOException {
        requireEndpoint(new URL(request.endpoint));
        try {
            Response response = transport.execute(request);
            if (response == null || response.body.length > request.limit) throw error("protocol", "官方服务响应超过限制");
            boolean ok = response.status >= 200 && response.status < 300;
            boolean redirect = request.redirectResponse && response.status >= 300 && response.status < 400;
            if (!ok && !redirect) throw error("http", "官方服务暂时拒绝请求，请稍后重试");
            return response;
        } catch (ApiException safe) { throw safe; }
        catch (IOException network) { throw error("network", "无法连接 QQ 官方服务，请检查网络后重试"); }
    }

    static List<String> callback(String text) throws IOException {
        Matcher match = CALLBACK.matcher(text);
        if (!match.matches()) throw error("protocol", "QQ 登录状态响应格式发生变化");
        String inner = match.group(1);
        List<String> args = new ArrayList<>(); int p = 0;
        while (p < inner.length()) {
            while (p < inner.length() && Character.isWhitespace(inner.charAt(p))) p++;
            if (p >= inner.length() || inner.charAt(p++) != '\'') throw error("protocol", "QQ 登录状态参数无效");
            StringBuilder value = new StringBuilder(); boolean closed = false;
            while (p < inner.length()) {
                char c = inner.charAt(p++);
                if (c == '\'') { closed = true; break; }
                if (c == '\\') {
                    if (p >= inner.length()) throw error("protocol", "QQ 登录状态转义无效");
                    char escaped = inner.charAt(p++);
                    if (escaped != '\\' && escaped != '\'' && escaped != '/') throw error("protocol", "QQ 登录状态转义无效");
                    c = escaped;
                }
                if (c < 32 || value.length() >= 4096) throw error("protocol", "QQ 登录状态参数超过限制");
                value.append(c);
            }
            if (!closed || args.size() >= 16) throw error("protocol", "QQ 登录状态参数无效");
            args.add(value.toString());
            while (p < inner.length() && Character.isWhitespace(inner.charAt(p))) p++;
            if (p == inner.length()) break;
            if (inner.charAt(p++) != ',' || p == inner.length()) throw error("protocol", "QQ 登录状态参数无效");
        }
        if (args.isEmpty()) throw error("protocol", "QQ 登录状态参数为空");
        return args;
    }

    static Map<String, String> cookies(Response response, String sourceHost) throws IOException {
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : response.headers.entrySet()) {
            if (!"Set-Cookie".equalsIgnoreCase(entry.getKey())) continue;
            for (String line : entry.getValue()) {
                if (line == null || line.length() > 8192) throw error("protocol", "官方授权 Cookie 格式无效");
                List<HttpCookie> parsed;
                try { parsed = HttpCookie.parse(line); }
                catch (IllegalArgumentException malformed) { throw error("protocol", "官方授权 Cookie 格式无效"); }
                for (HttpCookie cookie : parsed) {
                    String name = cookie.getName(), value = cookie.getValue();
                    String domain = cookie.getDomain();
                    if (domain != null) {
                        if (domain.startsWith(".")) domain = domain.substring(1);
                        domain = domain.toLowerCase(java.util.Locale.ROOT);
                        if (!domain.equals("qq.com") && !domain.endsWith(".qq.com")) continue;
                        if (!sourceHost.equals(domain) && !sourceHost.endsWith("." + domain)) continue;
                        // Cookies are forwarded only to graph.qq.com, never other domains.
                        if (!"graph.qq.com".equals(domain) && !"qq.com".equals(domain)
                            && !"ssl.ptlogin2.qq.com".equals(sourceHost)) continue;
                    } else if (!"ssl.ptlogin2.qq.com".equals(sourceHost)) {
                        // A host-only cookie from ssl.ptlogin2.graph.qq.com cannot go to graph.qq.com.
                        continue;
                    }
                    if (!name.matches("[A-Za-z0-9_]{1,80}") || value == null || (!value.isEmpty() && !safeSecret(value))) continue;
                    String path = cookie.getPath();
                    if (path != null && !path.isEmpty() && !"/".equals(path)) continue;
                    if (cookie.hasExpired()) continue;
                    if (result.containsKey(name) && !result.get(name).equals(value)) throw error("protocol", "授权 Cookie 存在歧义");
                    result.put(name, value);
                    if (result.size() > 64) throw error("protocol", "授权 Cookie 超过限制");
                }
            }
        }
        return result;
    }

    private static String cookieHeader(Map<String, String> cookies) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : cookies.entrySet()) {
            if (out.length() > 0) out.append("; "); out.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return out.toString();
    }

    static void requireEndpoint(URL url) throws IOException {
        String address = url.getProtocol() + "://" + url.getHost() + url.getPath();
        if (!SHOW.equals(address) && !POLL.equals(address) && !CHECK.equals(address) && !AUTHORIZE.equals(address) && !MUSIC.equals(address))
            throw error("endpoint", "请求目标不在允许的 QQ 官方接口范围内");
        if (url.getUserInfo() != null || url.getQuery() != null || url.getRef() != null || (url.getPort() != -1 && url.getPort() != 443))
            throw error("endpoint", "请求目标格式无效");
    }

    static URL checkedUrl(String raw, String host, String path) throws IOException {
        if (raw == null || raw.length() > 8192) throw error("redirect", "QQ 授权回跳地址无效");
        URL url;
        try { url = new URL(raw); } catch (IOException invalid) { throw error("redirect", "QQ 授权回跳地址无效"); }
        if (!"https".equals(url.getProtocol()) || !host.equals(url.getHost()) || !path.equals(url.getPath())
            || url.getUserInfo() != null || url.getRef() != null || (url.getPort() != -1 && url.getPort() != 443))
            throw error("redirect", "QQ 授权回跳地址不在允许范围内");
        return url;
    }

    static Map<String, String> parseQuery(String query) throws IOException {
        Map<String, String> out = new LinkedHashMap<>();
        if (query == null || query.length() > 8192) throw error("protocol", "QQ 授权参数无效");
        try {
            for (String pair : query.split("&")) {
                String[] kv = pair.split("=", 2);
                String key = URLDecoder.decode(kv[0], "UTF-8"), value = URLDecoder.decode(kv.length > 1 ? kv[1] : "", "UTF-8");
                if (out.containsKey(key)) throw error("protocol", "QQ 授权参数存在歧义");
                out.put(key, value);
                if (out.size() > 64) throw error("protocol", "QQ 授权参数超过限制");
            }
        } catch (IllegalArgumentException invalid) { throw error("protocol", "QQ 授权参数编码无效"); }
        return out;
    }

    static int hash33(String text, int initial) {
        int value = initial;
        for (int i = 0; i < text.length(); i++) value = value * 33 + text.charAt(i);
        return value & 0x7fffffff;
    }
    static String query(Map<String, String> params) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (out.length() > 0) out.append('&'); out.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return out.toString();
    }
    private static String encode(String value) {
        try { return URLEncoder.encode(value, "UTF-8"); }
        catch (java.io.UnsupportedEncodingException impossible) { throw new AssertionError(impossible); }
    }
    private static JSONObject json(byte[] bytes) throws IOException {
        try { return new JSONObject(new String(bytes, StandardCharsets.UTF_8)); }
        catch (JSONException malformed) { throw error("protocol", "QQ 音乐未返回有效数据"); }
    }
    private static JSONObject copyCredential(JSONObject source) throws IOException {
        if (source == null || source.toString().length() > 16384) throw error("credentials", "QQ 音乐凭证格式无效");
        JSONObject out = new JSONObject();
        for (String name : new String[] {"openid", "refresh_token", "access_token", "expired_at", "musicid", "musickey", "unionid",
                "str_musicid", "refresh_key", "musickeyCreateTime", "keyExpiresIn", "first_login", "bindAccountType", "needRefreshKeyIn", "encryptUin", "loginType"}) {
            if (!source.has(name)) continue;
            Object value = source.opt(name);
            if (!(value instanceof String) && !(value instanceof Number)) throw error("credentials", "QQ 音乐凭证字段无效");
            String text = String.valueOf(value);
            if (text.length() > 4096 || text.matches("(?s).*[\\p{Cc}\\p{Cf}].*")) throw error("credentials", "QQ 音乐凭证字段无效");
            put(out, name, value);
        }
        if (!out.has("loginType")) put(out, "loginType", scalar(out, "musickey").startsWith("W_X") ? 1 : 2);
        return out;
    }
    private static String scalar(JSONObject value, String key) {
        Object raw = value.opt(key); return raw instanceof String || raw instanceof Number ? String.valueOf(raw) : "";
    }
    private static JSONObject object(Object... pairs) throws IOException {
        JSONObject out = new JSONObject();
        for (int i = 0; i < pairs.length; i += 2) put(out, (String)pairs[i], pairs[i + 1]);
        return out;
    }
    private static void put(JSONObject target, String name, Object value) throws IOException {
        try { target.put(name, value); } catch (JSONException invalid) { throw error("protocol", "QQ 音乐参数格式无效"); }
    }
    private static Map<String, String> map(String... pairs) {
        Map<String, String> out = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i + 1]); return out;
    }
    private static Map<String, String> immutable(Map<String, String> source) { return Collections.unmodifiableMap(new LinkedHashMap<>(source)); }
    private static Map<String, String> baseHeaders() { return map("User-Agent", UA, "Accept", "*/*", "Accept-Encoding", "identity"); }
    private static Map<String, String> loginHeaders() { Map<String, String> out = baseHeaders(); out.put("Referer", "https://xui.ptlogin2.qq.com/"); return out; }
    private static boolean safeSecret(String value) { return value != null && value.matches("[A-Za-z0-9_!~.*'()%+/:=@-]{1,4096}"); }
    private static boolean safePayload(String value) {
        return value != null && value.length() >= 8 && value.length() <= 4096 && value.matches("[!-~]+");
    }
    private static boolean validId(String value) {
        if (value == null || !value.matches("[1-9][0-9]{0,18}")) return false;
        try { return Long.parseLong(value) > 0; } catch (NumberFormatException invalid) { return false; }
    }
    private static String cleanName(String value) {
        if (value == null || value.isEmpty()) return "QQ 音乐用户";
        String clean = value.replaceAll("[\\p{Cc}\\p{Cf}]", ""); return clean.isEmpty() ? "QQ 音乐用户" : clean.substring(0, Math.min(120, clean.length()));
    }
    private static boolean isPng(byte[] bytes) {
        int[] magic = {137, 80, 78, 71, 13, 10, 26, 10};
        if (bytes.length < magic.length) return false;
        for (int i = 0; i < magic.length; i++) if ((bytes[i] & 255) != magic[i]) return false;
        return true;
    }
    private static ApiException error(String code, String message) { return new ApiException(code, message); }

    private static Response executeHttps(Request request) throws IOException {
        HttpsURLConnection connection = null;
        try {
            URL base = new URL(request.endpoint); requireEndpoint(base);
            String encoded = query(request.params);
            connection = (HttpsURLConnection)new URL(request.endpoint + (encoded.isEmpty() ? "" : "?" + encoded)).openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(18000); connection.setReadTimeout(18000);
            connection.setUseCaches(false);
            for (Map.Entry<String, String> header : request.headers.entrySet()) connection.setRequestProperty(header.getKey(), header.getValue());
            if (request.body != null) {
                byte[] body = request.body.getBytes(StandardCharsets.UTF_8);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", request.contentType); connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) { out.write(body); }
            }
            int status = connection.getResponseCode();
            boolean allowedRedirect = request.redirectResponse && status >= 300 && status < 400;
            if (!allowedRedirect && (status < 200 || status >= 300)) throw error("http", "QQ 官方服务暂时拒绝请求");
            if (connection.getContentLengthLong() > request.limit) throw error("protocol", "QQ 官方响应超过限制");
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            if (!allowedRedirect) {
                try (InputStream in = connection.getInputStream()) {
                    byte[] chunk = new byte[4096]; int count;
                    long deadline = System.nanoTime() + 30_000_000_000L;
                    while ((count = in.read(chunk)) != -1) {
                        if (Thread.currentThread().isInterrupted()) throw error("cancelled", "当前请求已停止");
                        if (System.nanoTime() > deadline) throw error("timeout", "QQ 官方请求超时，请重试");
                        if (count > request.limit - body.size()) throw error("protocol", "QQ 官方响应超过限制");
                        body.write(chunk, 0, count);
                    }
                }
            }
            Map<String, List<String>> headers = new LinkedHashMap<>(); int headerSize = 0;
            for (Map.Entry<String, List<String>> entry : connection.getHeaderFields().entrySet()) {
                if (entry.getKey() == null) continue;
                if (!entry.getKey().equalsIgnoreCase("Location") && !entry.getKey().equalsIgnoreCase("Set-Cookie")) continue;
                for (String value : entry.getValue()) { headerSize += value.length(); if (headerSize > 32768) throw error("protocol", "QQ 授权响应头超过限制"); }
                headers.put(entry.getKey(), entry.getValue());
            }
            return new Response(status, body.toByteArray(), headers);
        } catch (ApiException safe) { throw safe; }
        catch (SocketTimeoutException timeout) { throw error("timeout", "QQ 官方请求超时，请检查网络后重试"); }
        catch (IOException network) { throw error("network", "无法连接 QQ 官方服务，请检查网络后重试"); }
        finally { if (connection != null) connection.disconnect(); }
    }
}
