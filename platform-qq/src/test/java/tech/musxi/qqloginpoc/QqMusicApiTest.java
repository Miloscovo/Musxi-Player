package tech.musxi.qqloginpoc;

import org.json.JSONObject;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Offline protocol/credential-boundary tests. No network or real account data. */
public final class QqMusicApiTest {
    private static int passed;
    private static final String KEY = "fixture-music-key";
    private static final String CALLBACK_URL = "https://ssl.ptlogin2.graph.qq.com/check_sig?uin=12345&ptsigx=fixture-sigx&service=ptqrlogin&s_url=https%3A%2F%2Fgraph.qq.com%2Foauth2.0%2Flogin_jump";
    private static final String CREDENTIAL = "{\"musicid\":12345,\"musickey\":\"fixture-music-key\",\"encryptUin\":\"fixture-euin\",\"refresh_token\":\"fixture-refresh\",\"loginType\":2}";

    public static void main(String[] args) throws Exception {
        equal(0, QqMusicApi.hash33("", 0));
        equal(5381, QqMusicApi.hash33("", 5381));
        equal(108966, QqMusicApi.hash33("abc", 0));
        equal(193485963, QqMusicApi.hash33("abc", 5381));
        equal("a=a%2Bb%26c&b=%E4%BD%A0%E5%A5%BD", QqMusicApi.query(map("a", "a+b&c", "b", "你好")));
        equal("a+b&c", QqMusicApi.parseQuery("a=a%2Bb%26c").get("a"));
        fails("protocol", () -> QqMusicApi.parseQuery("code=a&code=b"));
        fails("protocol", () -> QqMusicApi.parseQuery("code=%XX"));
        fails("protocol", () -> QqMusicApi.parseQuery(null));
        for (String url : new String[] {"http://ssl.ptlogin2.qq.com/ptqrshow", "https://evil.example/ptqrshow",
            "https://ssl.ptlogin2.qq.com.evil.example/ptqrshow", "https://ssl.ptlogin2.qq.com/ptqrshow/x",
            "https://user@ssl.ptlogin2.qq.com/ptqrshow", "https://ssl.ptlogin2.qq.com:444/ptqrshow",
            "https://ssl.ptlogin2.qq.com/ptqrshow#x", "https://ssl.ptlogin2.qq.com/ptqrshow?x=y"}) {
            fails("endpoint", () -> QqMusicApi.requireEndpoint(new URL(url)));
        }
        QqMusicApi.requireEndpoint(new URL("https://u.y.qq.com/cgi-bin/musicu.fcg")); passed++;
        for (String url : new String[] {"http://y.qq.com/portal/wx_redirect.html?code=x", "https://y.qq.com.evil.example/portal/wx_redirect.html",
                "https://evil@y.qq.com/portal/wx_redirect.html", "https://y.qq.com:444/portal/wx_redirect.html",
                "https://y.qq.com/portal/../portal/wx_redirect.html", "https://y.qq.com/portal/wx_redirect.html#x"}) {
            fails("redirect", () -> QqMusicApi.checkedUrl(url, "y.qq.com", "/portal/wx_redirect.html"));
        }
        equal("123", QqMusicApi.callback("ptuiCB('66','0','123','','wait');").get(2));
        equal("a'b", QqMusicApi.callback("ptuiCB('66','a\\'b');").get(1));
        for (String callback : new String[] {"other('66')", "ptuiCB(66)", "ptuiCB('66');evil()", "ptuiCB('66',)",
                "ptuiCB('66',x)", "ptuiCB()", "ptuiCB('66','\\x00')", "ptuiCB('66' 'x')", "ptuiCB('66','unterminated)"})
            fails("protocol", () -> QqMusicApi.callback(callback));

        Map<String, List<String>> cookieHeaders = headers("Set-Cookie", "p_skey=fixture-skey; Domain=.graph.qq.com; Path=/; Secure",
            "empty=; Domain=.graph.qq.com; Path=/", "alien=bad; Domain=.evil.example; Path=/",
            "hostonly=bad; Path=/", "private=bad; Domain=.graph.qq.com; Path=/private",
            "expired=bad; Domain=.graph.qq.com; Path=/; Max-Age=0");
        Map<String, String> cookie = QqMusicApi.cookies(response(302, "", cookieHeaders), "ssl.ptlogin2.graph.qq.com");
        equal(2, cookie.size()); equal("fixture-skey", cookie.get("p_skey")); equal("", cookie.get("empty"));
        fails("protocol", () -> QqMusicApi.cookies(response(302, "", headers("set-cookie", "p_skey=a; Domain=.graph.qq.com; Path=/",
            "p_skey=b; Domain=.graph.qq.com; Path=/")), "ssl.ptlogin2.graph.qq.com"));

        Fixture fixture = new Fixture();
        fixture.add(create());
        QqMusicApi api = new QqMusicApi(fixture);
        QqMusicApi.Attempt attempt = api.createLogin();
        equal(null, attempt.png);
        equal("https://ssl.ptlogin2.qq.com/ptqrlogin?k=fixture", attempt.authorizationPayload);
        equal("1", fixture.requests.get(0).params.get("type"));
        equal("100497308", fixture.requests.get(0).params.get("pt_3rd_aid"));
        truth(!fixture.requests.get(0).headers.containsKey("Cookie"));
        truth(!attempt.toString().contains("fixture-qrsig"));
        for (int state : new int[] {66, 67, 65, 68}) {
            fixture.add(response(200, "ptuiCB('" + state + "','0','','','fixture');", Collections.emptyMap()));
            QqMusicApi.Poll result = api.poll(attempt); equal(state, result.status); equal(null, result.session);
            QqMusicApi.Request req = fixture.last();
            equal("qrsig=fixture-qrsig", req.headers.get("Cookie"));
            equal(String.valueOf(QqMusicApi.hash33("fixture-qrsig", 0)), req.params.get("ptqrtoken"));
        }
        fixture.add(done()); fixture.add(checked()); fixture.add(authorized()); fixture.add(cgi(CREDENTIAL));
        QqMusicApi.Poll completed = api.poll(attempt);
        equal(0, completed.status); equal("12345", completed.session.musicId);
        QqMusicApi.Request check = fixture.requests.get(fixture.requests.size() - 3);
        equal("https://ssl.ptlogin2.graph.qq.com/check_sig", check.endpoint);
        truth(check.redirectResponse); equal("fixture-sigx", check.params.get("ptsigx"));
        truth(!check.headers.containsKey("Cookie"));
        QqMusicApi.Request authorize = fixture.requests.get(fixture.requests.size() - 2);
        equal("https://graph.qq.com/oauth2.0/authorize", authorize.endpoint);
        equal("p_skey=fixture-skey; empty=", authorize.headers.get("Cookie"));
        equal("100497308", QqMusicApi.parseQuery(authorize.body).get("client_id"));
        equal(String.valueOf(QqMusicApi.hash33("fixture-skey", 5381)), QqMusicApi.parseQuery(authorize.body).get("g_tk"));
        JSONObject exchange = new JSONObject(fixture.last().body);
        equal("QQConnectLogin.LoginServer", exchange.getJSONObject("req_0").getString("module"));
        equal("QQLogin", exchange.getJSONObject("req_0").getString("method"));
        equal("fixture-code", exchange.getJSONObject("req_0").getJSONObject("param").getString("code"));
        equal("24", exchange.getJSONObject("comm").getString("ct"));
        equal("2", exchange.getJSONObject("comm").getString("tmeLoginType"));
        truth(!exchange.getJSONObject("comm").has("authst"));
        truth(!fixture.last().headers.containsKey("Cookie"));

        JSONObject saved = completed.session.toJson();
        saved.getJSONObject("credential").put("musickey", "changed-copy");
        equal(KEY, completed.session.toJson().getJSONObject("credential").getString("musickey"));
        JSONObject original = new JSONObject("{\"credential\":" + CREDENTIAL + ",\"nickname\":\"fixture\\nname\"}");
        QqMusicApi.Session restored = QqMusicApi.Session.fromJson(original);
        original.getJSONObject("credential").put("musickey", "changed-copy");
        equal(KEY, restored.toJson().getJSONObject("credential").getString("musickey"));
        equal("fixturename", restored.nickname);
        truth(!restored.toString().contains(KEY));
        fixture.add(cgi("{\"musicid\":12345,\"name\":\"private-authenticated-field\"}"));
        fixture.add(cgi("{\"Info\":{\"BaseInfo\":{\"Name\":\"verified name\"}}}"));
        QqMusicApi.Session verified = api.verify(restored);
        equal("verified name", verified.nickname);
        QqMusicApi.Request verify = fixture.requests.get(fixture.requests.size() - 2);
        JSONObject verifyBody = new JSONObject(verify.body);
        equal("GetLoginUserInfo", verifyBody.getJSONObject("req_0").getString("method"));
        equal(KEY, verifyBody.getJSONObject("comm").getString("authst"));
        equal("12345", verifyBody.getJSONObject("comm").getString("qq"));
        truth(verify.headers.get("Cookie").contains("qqmusic_key=" + KEY));
        equal("GetHomepageHeader", new JSONObject(fixture.last().body).getJSONObject("req_0").getString("method"));
        fixture.add(cgiCode(1000));
        equal(QqMusicApi.ProbeResult.REJECTED, api.probeInvalidToken(verified));
        JSONObject probe = new JSONObject(fixture.last().body);
        equal("12345", probe.getJSONObject("comm").getString("qq"));
        truth(!KEY.equals(probe.getJSONObject("comm").getString("authst")));
        truth(!fixture.last().headers.get("Cookie").contains(KEY));
        fixture.add(cgi("{\"musicid\":12345}"));
        equal(QqMusicApi.ProbeResult.ACCEPTED, api.probeInvalidToken(verified));
        fixture.add(cgiCode(2001)); fails("rate", () -> api.probeInvalidToken(verified));
        fixture.add(cgi("{}")); fails("identity", () -> api.probeInvalidToken(verified));
        fixture.add(cgiCode(1000)); fails("expired", () -> api.verify(verified));
        fixture.add(cgi("{\"musicid\":98765}")); fails("identity", () -> api.verify(verified));
        fixture.add(cgi("{}")); fails("identity", () -> api.verify(verified));
        fixture.add(response(200, "{\"code\":0,\"req_0\":{\"data\":{}}}", Collections.emptyMap())); fails("protocol", () -> api.verify(verified));
        fixture.add(response(200, "{\"code\":0,\"req_0\":{\"code\":0,\"data\":{\"code\":104401}}}", Collections.emptyMap())); fails("expired", () -> api.verify(verified));
        fixture.add(cgiCode(2000)); fails("signature", () -> api.verify(verified));

        for (String badCredential : new String[] {"{}", "{\"musicid\":0,\"musickey\":\"x\"}", "{\"musicid\":true,\"musickey\":\"x\"}",
            "{\"musicid\":1,\"musickey\":\"\"}", "{\"musicid\":1,\"musickey\":\"x; injected=y\"}",
            "{\"musicid\":-1,\"musickey\":\"x\"}", "{\"musicid\":1.5,\"musickey\":\"x\"}",
            "{\"musicid\":\"9223372036854775808\",\"musickey\":\"x\"}", "{\"musicid\":1,\"musickey\":\"x\",\"refresh_token\":{}}"})
            fails("credentials", () -> QqMusicApi.Session.fromJson(new JSONObject("{\"credential\":" + badCredential + "}")));
        fails("storage", () -> QqMusicApi.Session.fromJson(new JSONObject()));
        fails("request", () -> api.poll(null));

        for (String badCreate : new String[] {"ptui_qrcode_CB({\"ec\":0,\"qrcode\":\"12345678\"});evil()",
            "ptui_qrcode_CB({\"ec\":0,\"qrcode\":12345678})", "ptui_qrcode_CB({\"ec\":1,\"qrcode\":\"12345678\"})",
            "ptui_qrcode_CB({\"ec\":0,\"qrcode\":\"bad value\"})", "other({\"ec\":0,\"qrcode\":\"12345678\"})", "not-json"}) {
            Fixture f = new Fixture(); f.add(response(200, badCreate, headers("Set-Cookie", "qrsig=fixture-qrsig; Path=/")));
            fails("protocol", () -> new QqMusicApi(f).createLogin());
        }
        Fixture missingCookie = new Fixture(); missingCookie.add(response(200, "ptui_qrcode_CB({\"ec\":0,\"qrcode\":\"12345678\"})", Collections.emptyMap()));
        fails("protocol", () -> new QqMusicApi(missingCookie).createLogin());
        Fixture malicious = new Fixture(); malicious.add(create());
        QqMusicApi attacked = new QqMusicApi(malicious); QqMusicApi.Attempt attackAttempt = attacked.createLogin();
        malicious.add(response(200, "ptuiCB('0','0','https://evil.example/check_sig?uin=1&ptsigx=x');", Collections.emptyMap()));
        fails("redirect", () -> attacked.poll(attackAttempt)); equal(2, malicious.requests.size());
        malicious.add(done()); malicious.add(checked()); malicious.add(response(302, "", headers("Location", "https://evil.example/portal/wx_redirect.html?code=secret&state=state")));
        fails("redirect", () -> attacked.poll(attackAttempt)); equal(5, malicious.requests.size());
        malicious.add(done()); malicious.add(checked()); malicious.add(response(302, "", headers("Location", "https://y.qq.com/portal/wx_redirect.html?code=secret&state=wrong")));
        fails("authorize", () -> attacked.poll(attackAttempt));
        malicious.add(response(302, "", headers("Location", "https://evil.example"))); fails("http", () -> attacked.poll(attackAttempt));
        malicious.add(response(200, repeat("x", 256 * 1024 + 1), Collections.emptyMap())); fails("protocol", () -> attacked.poll(attackAttempt));
        QqMusicApi failing = new QqMusicApi(request -> { throw new IOException("raw-secret-token-12345"); });
        try { failing.createLogin(); throw new AssertionError("Expected sanitized error"); }
        catch (QqMusicApi.ApiException safe) { equal("network", safe.code); truth(!safe.toString().contains("raw-secret")); equal(null, safe.getCause()); }
        truth(fixture.replies.isEmpty());
        System.out.println(passed + " offline QQ music protocol checks passed");
    }

    private static final class Fixture implements QqMusicApi.Transport {
        final ArrayDeque<QqMusicApi.Response> replies = new ArrayDeque<>();
        final List<QqMusicApi.Request> requests = new ArrayList<>();
        void add(QqMusicApi.Response value) { replies.add(value); }
        QqMusicApi.Request last() { return requests.get(requests.size() - 1); }
        public QqMusicApi.Response execute(QqMusicApi.Request request) {
            requests.add(request); if (replies.isEmpty()) throw new AssertionError("Unexpected protocol call"); return replies.remove();
        }
    }
    private static QqMusicApi.Response create() {
        return response(200, "ptui_qrcode_CB({\"ec\":0,\"qrcode\":\"https://ssl.ptlogin2.qq.com/ptqrlogin?k=fixture\"});",
            headers("Set-Cookie", "qrsig=fixture-qrsig; Domain=.ptlogin2.qq.com; Path=/; Secure"));
    }
    private static QqMusicApi.Response done() { return response(200, "ptuiCB('0','0','" + CALLBACK_URL + "','0','fixture');", Collections.emptyMap()); }
    private static QqMusicApi.Response checked() { return response(302, "", headers("Set-Cookie", "p_skey=fixture-skey; Domain=.graph.qq.com; Path=/; Secure", "empty=; Domain=.graph.qq.com; Path=/")); }
    private static QqMusicApi.Response authorized() { return response(302, "", headers("Location", "https://y.qq.com/portal/wx_redirect.html?code=fixture-code&state=state")); }
    private static QqMusicApi.Response cgi(String data) { return response(200, "{\"code\":0,\"req_0\":{\"code\":0,\"data\":" + data + "}}", Collections.emptyMap()); }
    private static QqMusicApi.Response cgiCode(int code) { return response(200, "{\"code\":0,\"req_0\":{\"code\":" + code + ",\"data\":{}}}", Collections.emptyMap()); }
    private static QqMusicApi.Response response(int code, String body, Map<String, List<String>> headers) { return new QqMusicApi.Response(code, body.getBytes(StandardCharsets.UTF_8), headers); }
    private static Map<String, List<String>> headers(String key, String... values) { Map<String, List<String>> out = new LinkedHashMap<>(); out.put(key, Arrays.asList(values)); return out; }
    private static Map<String, String> map(String... pairs) { Map<String, String> out = new LinkedHashMap<>(); for (int i = 0; i < pairs.length; i += 2) out.put(pairs[i], pairs[i+1]); return out; }
    private static String repeat(String value, int count) { StringBuilder out = new StringBuilder(); for (int i=0;i<count;i++) out.append(value); return out.toString(); }
    private interface Operation { void run() throws Exception; }
    private static void fails(String code, Operation operation) throws Exception {
        try { operation.run(); throw new AssertionError("Expected failure: " + code); }
        catch (QqMusicApi.ApiException expected) { equal(code, expected.code); equal(null, expected.getCause()); }
    }
    private static void truth(boolean condition) { if (!condition) throw new AssertionError("Condition failed"); passed++; }
    private static void equal(Object expected, Object actual) { if (expected == null ? actual != null : !expected.equals(actual)) throw new AssertionError("Expected " + expected + " but got " + actual); passed++; }
}
