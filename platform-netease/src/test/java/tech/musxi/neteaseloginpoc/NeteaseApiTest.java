package tech.musxi.neteaseloginpoc;

import org.json.JSONObject;
import java.io.IOException;
import java.net.URL;
import java.util.*;

/** Offline fixtures only. No real platform requests or saved accounts. */
public final class NeteaseApiTest {
    private static int checks;
    interface Run { void run() throws Exception; }
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("Check " + checks); }
    private static void fails(String code, Run action) throws Exception {
        try { action.run(); throw new AssertionError("Expected " + code); }
        catch (NeteaseApi.ApiException failure) {
            check(code.equals(failure.code)); check(failure.getCause() == null);
            check(!failure.getMessage().contains("fixture-private"));
        }
    }
    private static JSONObject json(String value) {
        try { return new JSONObject(value); }
        catch (org.json.JSONException invalid) { throw new AssertionError("Invalid test fixture"); }
    }
    private static NeteaseApi.Response response(String value) {
        return new NeteaseApi.Response(json(value), Collections.emptyMap());
    }
    private static Map<String,List<String>> cookies(String... values) {
        Map<String,List<String>> out = new LinkedHashMap<>(); out.put("sEt-CoOkIe", Arrays.asList(values)); return out;
    }
    private static final class Queue implements NeteaseApi.Transport {
        final Deque<NeteaseApi.Response> responses = new ArrayDeque<>();
        final List<NeteaseApi.Request> requests = new ArrayList<>();
        Queue add(String value) { responses.add(response(value)); return this; }
        Queue addCookie(String value, String... cookies) { responses.add(new NeteaseApi.Response(json(value), cookies(cookies))); return this; }
        public NeteaseApi.Response execute(NeteaseApi.Request request) {
            requests.add(request); if (responses.isEmpty()) throw new AssertionError("Unexpected request"); return responses.remove();
        }
    }
    private static NeteaseApi.Session stored() throws Exception {
        return NeteaseApi.Session.fromJson(json("{\"userId\":\"12345\",\"nickname\":\"样例\",\"cookies\":{\"MUSIC_U\":\"fixture-private-token\",\"__csrf\":\"csrf-fixture\"}}"));
    }
    private static void invalidBrowserCookies(String header) throws Exception {
        try { NeteaseApi.fromBrowserCookies(header); throw new AssertionError("Expected invalid browser cookies"); }
        catch (IOException failure) {
            check(failure.getCause() == null);
            check(!failure.getMessage().contains("fixture-private"));
        }
    }
    private static String repeat(char value, int count) {
        char[] chars = new char[count]; Arrays.fill(chars, value); return new String(chars);
    }
    private static void browserLoginChecks() throws Exception {
        check(WebLoginPolicy.LOGIN_URL.equals("https://y.music.163.com/m/login"));
        for (String domain : new String[]{"music.163.com", "reg.163.com", "qq.com", "weibo.com", "weibo.cn"}) {
            check(WebLoginPolicy.allowsNavigation("https://" + domain));
            check(WebLoginPolicy.allowsNavigation("https://login.deep." + domain + ":443/path?q=x#fragment"));
            check(WebLoginPolicy.allowsNavigation("HTTPS://" + domain.toUpperCase(Locale.ROOT) + "/"));
            check(!WebLoginPolicy.allowsNavigation("http://" + domain));
            check(!WebLoginPolicy.allowsNavigation("https://" + domain + ".evil.test/"));
            check(!WebLoginPolicy.allowsNavigation("https://evil" + domain + "/"));
        }
        for (String url : new String[]{null, "", "about:blank", "javascript:alert(1)", "file:///music.163.com",
                "intent://music.163.com", "//music.163.com", "https:music.163.com", "https:///music.163.com",
                "https://163.com", "https://example.com", "https://127.0.0.1", "https://2130706433",
                "https://[::1]", "https://music.163.com:80", "https://music.163.com:444",
                "https://music.163.com:65536", "https://music.163.com:", "https://music.163.com:bad",
                "https://user@music.163.com", "https://@music.163.com", "https://music.163.com@evil.test",
                "https://evil.test@music.163.com", "https://music.163.com./", "https://.music.163.com/",
                "https://x..music.163.com", "https://-x.music.163.com", "https://x-.music.163.com",
                "https://x_y.music.163.com", "https://" + repeat('x', 64) + ".music.163.com",
                "https://music%2e163.com", "https://music.163.com%40evil.test", "https://music.163.com\\@evil.test/",
                "https://music.163.com\r\n.evil.test", " https://music.163.com", "https://ｍusic.163.com"})
            check(!WebLoginPolicy.allowsNavigation(url));

        Map<String,String> imported = WebLoginPolicy.parseMusicCookies(
                "ignored=one; MUSIC_U=fixture-private-token==; __csrf=csrf-fixture; music_u=other; MUSIC_U_extra=other");
        check(imported.size() == 2 && imported.get("MUSIC_U").equals("fixture-private-token=="));
        check(imported.get("__csrf").equals("csrf-fixture"));
        check(WebLoginPolicy.parseMusicCookies("MUSIC_U=ok; __csrf=").get("__csrf").isEmpty());
        check(WebLoginPolicy.parseMusicCookies("\tMUSIC_U=ok; ignored=one; ignored=two").size() == 1);
        check(WebLoginPolicy.parseMusicCookies("MUSIC_U=%3B%0A+abc/=~").get("MUSIC_U").equals("%3B%0A+abc/=~"));
        // These are ignored request-cookie names, not Set-Cookie expiry/path instructions.
        check(WebLoginPolicy.parseMusicCookies("MUSIC_U=ok; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/other; Max-Age=0").size() == 1);
        for (String header : new String[]{null, "", "ignored=one", "music_u=ok", "MUSIC_U_extra=ok", "__csrf=ok",
                "MUSIC_U", "MUSIC_U=", "MUSIC_U=one; MUSIC_U=two", "MUSIC_U=same; MUSIC_U=same",
                "MUSIC_U=ok; __csrf=one; __csrf=two", "MUSIC_U=ok; __csrf=; __csrf=",
                "MUSIC_U=ok; __csrf", "MUSIC_U=ok; __csrf=bad value", "MUSIC_U=ok; ignored=x\r\nX-Test: injected",
                "MUSIC_U=ok, MUSIC_U=other", "MUSIC_U=\"one; MUSIC_U=two\""}) invalidBrowserCookies(header);
        for (String value : new String[]{"has space", "leading ", " trailing", "tab\t", "line\r\n", "nul\u0000",
                "del\u007f", "unicodeé", "comma,value", "quote\"", "back\\slash"}) {
            invalidBrowserCookies("MUSIC_U=" + value);
            invalidBrowserCookies("MUSIC_U=ok; __csrf=" + value);
        }
        check(WebLoginPolicy.parseMusicCookies("MUSIC_U=" + repeat('x', 8192) + "; __csrf=" + repeat('c', 256)).size() == 2);
        invalidBrowserCookies("MUSIC_U=" + repeat('x', 8193));
        invalidBrowserCookies("MUSIC_U=ok; __csrf=" + repeat('c', 257));
        String prefix = "MUSIC_U=ok; ignored=";
        String limit = prefix + repeat('x', 32768 - prefix.length());
        check(WebLoginPolicy.parseMusicCookies(limit).size() == 1);
        invalidBrowserCookies(limit + "x");

        NeteaseApi.Session browser = NeteaseApi.fromBrowserCookies(
                "ignored=do-not-forward; MUSIC_U=fixture-private-token; __csrf=csrf-fixture");
        check(browser.userId.isEmpty() && browser.nickname.equals("网易云用户"));
        fails("storage", () -> browser.toJson());
        Queue verified = new Queue().add("{code:200,account:{id:12345},profile:{userId:12345,nickname:'浏览器样例'}}");
        NeteaseApi.Session identity = new NeteaseApi(verified).verify(browser);
        check(identity.userId.equals("12345") && identity.nickname.equals("浏览器样例"));
        check(identity.toJson().getJSONObject("cookies").getString("MUSIC_U").equals("fixture-private-token"));
        check(verified.requests.size() == 1 && verified.responses.isEmpty());
        check(verified.requests.get(0).endpoint.equals(NeteaseApi.STATUS));
        check(verified.requests.get(0).headers.get("Cookie").equals(
                "os=pc; __remember_me=true; MUSIC_U=fixture-private-token; __csrf=csrf-fixture"));
        fails("storage", () -> browser.toJson());
        for (String[] fixture : new String[][]{
                {"expired", "{code:301}"}, {"expired", "{code:200,account:null,profile:null}"},
                {"identity", "{code:200,account:{id:7},profile:{userId:8,nickname:'x'}}"},
                {"protocol", "{code:'200'}"}, {"risk", "{code:405}"}}) {
            Queue rejected = new Queue().add(fixture[1]);
            fails(fixture[0], () -> new NeteaseApi(rejected).verify(browser));
            check(rejected.requests.size() == 1 && rejected.responses.isEmpty());
            fails("storage", () -> browser.toJson());
        }
        fails("network", () -> new NeteaseApi(request -> { throw new IOException("fixture-private-token"); }).verify(browser));
        fails("storage", () -> browser.toJson());
    }
    public static void main(String[] ignored) throws Exception {
        browserLoginChecks();
        // Independent Node/OpenSSL AES-CBC + raw RSA_NO_PADDING fixture, based on vendor crypto.js.
        Map<String,String> vector = NeteaseApi.weapi("{\"type\":3,\"e_r\":false,\"csrf_token\":\"\"}", "abcdefghijklmnop");
        check(vector.get("params").equals("y4m4rPdW1aoZjQRAM8PwffZhaQ5OFlrbTmSrlDwo9lUo/BWlDIsJC5jgTtS5qiNlNXEsJ8bTc6IV42l211i/awyovqzsLnXOMC1Nc3mu4tg="));
        check(vector.get("encSecKey").equals("d15a1683c992095d0c234c19966605c5c5964911268bbeda8cb8d08d834913e59d53b32358903a121b5fca784c1f5ae44951fd02524df58ecc98e52cc7cf8689b42c2e93ddf05b0592512d87f5960467e2f086c018849d76014d323500e30f13ef4cafbb0cf5a66731a3f1776c75ca35d0062dac70a3e33245afabcf47938487"));
        fails("protocol", () -> NeteaseApi.weapi("{}", "short"));
        check(NeteaseApi.eapi("/api/login/qrcode/unikey", "{\"type\":3,\"e_r\":false}").equals("1CA81A97B7BAEB099F29A9B99A25CD6E58E7BA5B35F25231033065C776830C0ACD212933C9432002B586ED0F458155F631B44BD2C5B733983A66E6224C0585A52777484232CB13E16B21684479BA3B9B3CF9FA119BBE8E17B07F40E02346BE3781A9B22A53CC3C44EC77569FAD1631FF"));
        Queue q = new Queue().add("{code:200,unikey:'fixture-key-12345'}")
            .add("{code:801}").add("{code:802}").addCookie("{code:803}",
                "MUSIC_U=fixture-private-token; Domain=.music.163.com; Path=/; HttpOnly; Secure",
                "__csrf=csrf-fixture; Domain=.music.163.com; Path=/")
            .add("{code:200,account:{id:12345},profile:{userId:12345,nickname:'样例'}}")
            .add("{code:200,account:null,profile:null}");
        NeteaseApi api = new NeteaseApi(q);
        NeteaseApi.Attempt attempt = api.createLogin();
        check(attempt.authorizationUrl.equals("https://music.163.com/login?codekey=fixture-key-12345"));
        check(api.poll(attempt).status == 801); check(api.poll(attempt).status == 802);
        NeteaseApi.Poll done = api.poll(attempt); check(done.status == 803 && done.session != null);
        fails("storage", () -> done.session.toJson());
        NeteaseApi.Session saved = api.verify(done.session); check(saved.userId.equals("12345"));
        check(NeteaseApi.Session.fromJson(saved.toJson()).nickname.equals("样例"));
        check(api.probeInvalidToken(saved) == NeteaseApi.ProbeResult.REJECTED);
        check(q.requests.size() == 6 && q.responses.isEmpty());
        check(q.requests.get(0).endpoint.equals(NeteaseApi.KEY));
        check(q.requests.get(3).endpoint.equals(NeteaseApi.POLL));
        check(q.requests.get(4).endpoint.equals(NeteaseApi.STATUS));
        String real = q.requests.get(4).headers.get("Cookie"), fake = q.requests.get(5).headers.get("Cookie");
        check(real.contains("MUSIC_U=fixture-private-token")); check(!fake.contains("fixture-private-token"));
        check(fake.contains("__csrf=csrf-fixture"));
        check(!q.requests.get(0).body.contains("type"));
        check(q.requests.get(0).body.matches("params=[0-9A-F]+"));
        check(q.requests.get(4).body.matches("params=.+&encSecKey=[0-9a-f]{256}"));
        check(!q.requests.get(4).body.contains("csrf-fixture"));
        fails("request", () -> api.poll(null));
        for (int code : new int[]{800,801,802}) {
            NeteaseApi.Poll result = new NeteaseApi(new Queue().add("{code:"+code+"}")).poll(attempt);
            check(result.status == code && result.session == null);
        }
        fails("credentials", () -> new NeteaseApi(new Queue().add("{code:803}")).poll(attempt));
        fails("protocol", () -> new NeteaseApi(new Queue().add("{code:200,data:{unikey:'fixture-key-12345'}}")).createLogin());
        fails("protocol", () -> new NeteaseApi(new Queue().add("{code:200,unikey:'https://evil.test'}")).createLogin());
        for (String payload : new String[]{"{}", "{code:'200'}", "{code:200.1}", "{code:true}"})
            fails("protocol", () -> NeteaseApi.responseCode(json(payload)));
        for (String payload : new String[]{
            "{code:200,account:{id:9},profile:{userId:12345,nickname:'x'}}",
            "{code:200,account:{id:9},profile:{userId:9,nickname:'x'}}",
            "{code:200,account:{id:12345},profile:{userId:12345,nickname:3}}"})
            fails("identity", () -> new NeteaseApi(new Queue().add(payload)).verify(stored()));
        fails("expired", () -> new NeteaseApi(new Queue().add("{code:200,account:null,profile:null}")).verify(stored()));
        for (int code : new int[]{301,302}) {
            check(new NeteaseApi(new Queue().add("{code:"+code+"}")).probeInvalidToken(stored()) == NeteaseApi.ProbeResult.REJECTED);
            fails("expired", () -> new NeteaseApi(new Queue().add("{code:"+code+"}")).verify(stored()));
        }
        for (int code : new int[]{-462,405,406}) fails("risk", () -> new NeteaseApi(new Queue().add("{code:"+code+"}")).probeInvalidToken(stored()));
        for (int code : new int[]{429,503}) fails("rate", () -> new NeteaseApi(new Queue().add("{code:"+code+"}")).verify(stored()));
        fails("protocol", () -> new NeteaseApi(new Queue().add("{code:200}")).poll(attempt));
        fails("expired", () -> new NeteaseApi(new Queue().add("{code:200,account:null}")).probeInvalidToken(stored()));
        check(new NeteaseApi(new Queue().add("{code:200,account:{id:12345},profile:{userId:12345,nickname:'x'}}")).probeInvalidToken(stored()) == NeteaseApi.ProbeResult.ACCEPTED);
        fails("network", () -> new NeteaseApi(request -> { throw new IOException("fixture-private-token"); }).verify(stored()));
        check(NeteaseApi.authCookies(cookies("MUSIC_U=ok; Domain=.music.163.com; Path=/", "ignored=anything")).size() == 1);
        check(NeteaseApi.authCookies(cookies("MUSIC_U=ok; Domain=.163.com; Path=/", "__csrf=; Path=/")).size() == 2);
        for (String cookie : new String[]{"MUSIC_U=ok; Domain=evil.test", "MUSIC_U=ok; Path=/other", "MUSIC_U=ok; Max-Age=0", "MUSIC_U=ok; Expires=Thu, 01 Jan 1970 00:00:00 GMT"})
            fails("credentials", () -> NeteaseApi.authCookies(cookies(cookie)));
        fails("protocol", () -> NeteaseApi.authCookies(cookies("MUSIC_U=one", "MUSIC_U=two")));
        check(NeteaseApi.authCookies(cookies("MUSIC_U=same", "MUSIC_U=same")).size() == 1);
        for (String value : new String[]{"", "has space", "inject;field=one", "tab\t", "quote\"", "back\\slash"}) {
            JSONObject bad = stored().toJson(); bad.getJSONObject("cookies").put("MUSIC_U", value);
            fails("credentials", () -> NeteaseApi.Session.fromJson(bad));
        }
        JSONObject unknown = stored().toJson(); unknown.getJSONObject("cookies").put("other", "value");
        fails("credentials", () -> NeteaseApi.Session.fromJson(unknown));
        for (String id : new String[]{"", "0", "-1", "1.5", "9007199254740992", "true"}) {
            JSONObject bad = stored().toJson(); bad.put("userId", id);
            fails("storage", () -> NeteaseApi.Session.fromJson(bad));
        }
        JSONObject source = stored().toJson(); NeteaseApi.Session copy = NeteaseApi.Session.fromJson(source);
        source.getJSONObject("cookies").put("MUSIC_U", "changed");
        check(copy.toJson().getJSONObject("cookies").getString("MUSIC_U").equals("fixture-private-token"));
        for (String endpoint : new String[]{NeteaseApi.KEY,NeteaseApi.POLL,NeteaseApi.STATUS}) { NeteaseApi.requireEndpoint(new URL(endpoint)); checks++; }
        for (String endpoint : new String[]{"http://music.163.com/weapi/login/qrcode/unikey",NeteaseApi.KEY+"?secret=one",NeteaseApi.KEY+"#x","https://evil.test/weapi/login/qrcode/unikey","https://user@music.163.com/weapi/login/qrcode/unikey","https://music.163.com:444/weapi/login/qrcode/unikey","https://music.163.com/weapi/other"})
            fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(endpoint)));
        System.out.println("NetEase offline checks passed: " + checks);
    }
}
