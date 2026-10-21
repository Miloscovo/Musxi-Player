package tech.musxi.loginpoc;

import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONObject;

/** Offline conformance and fail-closed tests. No platform requests or real credentials. */
public final class KugouApiTest {
    private static int passed;
    private static final KugouApi.Device DEVICE = new KugouApi.Device("0123456789abcdef0123456789abcdef");
    private static final long TIME = 1700000000L;

    public static void main(String[] args) throws Exception {
        // Reference outputs generated using the pinned vendor helper.js and crypto.js,
        // process.env.platform='lite'; this catches parameter/sort/RSA padding differences.
        KugouApi.Request create = KugouApi.createRequest(DEVICE, TIME);
        equal("20019e8efc9ad5fd61d7206bc98b57a6", create.params.get("signature"));
        equal("1001", create.params.get("appid"));
        equal("11440", create.params.get("clientver"));
        equal("https://h5.kugou.com/apps/loginQRCode/html/index.html?appid=3116&", create.params.get("qrcode_txt"));
        equal("https://login-user.kugou.com/v2/qrcode", create.endpoint);
        equal("1700000000", create.headers.get("clienttime"));
        equal(DEVICE.mid, create.headers.get("mid"));

        Map<String, String> signature = new LinkedHashMap<>();
        signature.put("z", "a+b&c"); signature.put("a", "你好");
        equal("e6599b8acbfb5ada769eaea5ab3233ec", KugouApi.androidSignature(signature, "{}"));
        equal("8CDAA1E793270E36FAA77C3139237B7126290C444AB7A43ED89F156394FF9A300180A1989B1201295452B74F9A4D57DEB35FF7BB191D80F4CA9E6791FC4158AC83CDC450E230BB8E9AEF1EEADECBAE270C015AEB3573C49C1376A248BDFB96FCDFDEE73B4FDFF0B4A98DA03A35F413437D07EA32B84420E1375C4141F13DCFEB",
            KugouApi.rsaEncrypt("{\"token\":\"fixture-token-123\",\"clienttime\":1700000000}"));
        fails("credentials", () -> KugouApi.rsaEncrypt(repeat("x", 129)));

        KugouApi.Request poll = KugouApi.pollRequest(DEVICE, "fixture_key-123", TIME);
        equal("3116", poll.params.get("appid"));
        equal("fixture_key-123", poll.params.get("qrcode"));
        equal("https://h5.kugou.com/apps/loginQRCode/html/index.html?qrcode=fixture_key-123", KugouApi.authorizationUrl("fixture_key-123"));
        fails("protocol", () -> KugouApi.pollRequest(DEVICE, "../secret?token=x", TIME));
        failsArgument(() -> KugouApi.authorizationUrl("bad&key=x"));
        failsArgument(() -> new KugouApi.Device("invalid"));

        String query = KugouApi.query(signature);
        equal("z=a%2Bb%26c&a=%E4%BD%A0%E5%A5%BD", query);
        equal("a+b&c", URLDecoder.decode(query.split("&")[0].substring(2), "UTF-8"));

        equal("fixture-key", KugouApi.parseCreate(ok("{\"qrcode\":\"fixture-key\"}")));
        fails("protocol", () -> KugouApi.parseCreate(ok("{}")));
        fails("protocol", () -> KugouApi.parseCreate(ok("{\"qrcode\":{}}")));
        fails("protocol", () -> KugouApi.parseCreate(ok("{\"qrcode\":\"bad&key\"}")));
        for (int status : new int[] {0, 1, 2}) {
            KugouApi.Poll state = KugouApi.parsePoll(ok("{\"status\":" + status + "}"));
            equal(status, state.status); equal(null, state.session);
        }
        fails("protocol", () -> KugouApi.parsePoll(ok("{\"status\":3}")));
        fails("protocol", () -> KugouApi.parsePoll(ok("{\"status\":null}")));
        fails("protocol", () -> KugouApi.parsePoll(ok("{\"status\":true}")));
        fails("credentials", () -> KugouApi.parsePoll(ok("{\"status\":4,\"userid\":123}")));
        fails("credentials", () -> KugouApi.parsePoll(ok("{\"status\":4,\"userid\":0,\"token\":\"fixture\"}")));
        fails("credentials", () -> KugouApi.parsePoll(ok("{\"status\":4,\"userid\":9007199254740992,\"token\":\"fixture\"}")));
        fails("credentials", () -> KugouApi.parsePoll(ok("{\"status\":4,\"userid\":123,\"token\":{}}")));
        KugouApi.Poll connected = KugouApi.parsePoll(ok("{\"status\":4,\"userid\":123,\"token\":\"fixture-token-123\",\"nickname\":\"测试\\n用户\"}"));
        equal(4, connected.status); equal("123", connected.session.userId);
        equal("测试用户", connected.session.nickname);

        KugouApi.Session session = connected.session;
        KugouApi.Request verify = KugouApi.verifyRequest(DEVICE, session, TIME);
        equal("https://gateway.kugou.com/v3/get_my_info", verify.endpoint);
        equal("usercenter.kugou.com", verify.headers.get("x-router"));
        equal("fixture-token-123", verify.params.get("token"));
        equal("951bdfcadebb27a21a4e540d84b86f24", verify.params.get("signature"));
        JSONObject verifyBody = new JSONObject(verify.body);
        equal(123, verifyBody.getInt("userid")); equal(TIME, verifyBody.getLong("visit_time"));
        equal(256, verifyBody.getString("p").length());
        equal("已验证", KugouApi.parseVerify(ok("{\"userid\":123,\"nickname\":\"已验证\"}"), session).nickname);
        equal(true, KugouApi.parseVerify(ok("{\"userid\":123,\"nickname\":\"已验证\"}"), session).accountIdEchoed);
        equal(false, session.accountIdEchoed);
        fails("identity", () -> KugouApi.parseVerify(ok("{\"userid\":124}"), session));
        fails("identity", () -> KugouApi.parseVerify(ok("{\"nickname\":\"缺少身份\"}"), session));
        for (String field : new String[] {"\"sex\":1", "\"sex\":\"0\"", "\"birthday\":\"2000-01-01\"", "\"pic\":\"https://example.invalid/fixture.jpg\"", "\"actor_status\":0"}) {
            KugouApi.Session profile = KugouApi.parseVerify(ok("{\"nickname\":\"真实\\n资料\\u200b\"," + field + "}"), session);
            equal("真实资料", profile.nickname);
            equal(false, profile.accountIdEchoed);
            equal(session.userId, profile.userId);
            equal(session.token, profile.token);
        }
        for (String invalidProfile : new String[] {
            "{\"sex\":1}", "{\"nickname\":null,\"sex\":1}", "{\"nickname\":123,\"sex\":1}",
            "{\"nickname\":\"\",\"sex\":1}", "{\"nickname\":\"   \",\"sex\":1}",
            "{\"nickname\":\"\\n\\u200b\",\"sex\":1}", "{\"nickname\":\"test\",\"sex\":true}",
            "{\"nickname\":\"test\",\"sex\":{}}", "{\"nickname\":\"test\",\"birthday\":[]}",
            "{\"nickname\":\"test\",\"pic\":42}", "{\"nickname\":\"test\",\"actor_status\":false}",
            "{\"nickname\":\"test\",\"sex\":-1}", "{\"nickname\":\"test\",\"actor_status\":1.5}",
            "{\"nickname\":\"test\",\"id\":123}",
            "{\"nickname\":\"test\",\"sex\":1,\"userid\":124}",
            "{\"nickname\":\"test\",\"sex\":1,\"userinfo\":{\"userid\":null}}"
        }) {
            final String response = invalidProfile;
            fails("identity", () -> KugouApi.parseVerify(ok(response), session));
        }
        fails("platform", () -> KugouApi.parseVerify(new JSONObject("{\"status\":0,\"data\":{\"nickname\":\"test\",\"sex\":1}}"), session));
        fails("platform", () -> KugouApi.parseVerify(new JSONObject("{\"status\":1,\"error_code\":1001,\"data\":{\"nickname\":\"test\",\"sex\":1}}"), session));
        fails("protocol", () -> KugouApi.parseVerify(new JSONObject("{\"status\":1,\"data\":[]}"), session));

        // Alias support is defensive parsing, not a live-response claim.
        for (String container : new String[] {"", "userinfo", "user_info"}) {
            for (String field : new String[] {"userid", "user_id", "userId", "kugouid", "kugou_id"}) {
                String matching = "{\"" + field + "\":\"123\"}";
                if (!container.isEmpty()) matching = "{\"" + container + "\":" + matching + "}";
                equal("123", KugouApi.parseVerify(ok(matching), session).userId);
            }
        }
        equal("123", KugouApi.parseVerify(ok("{\"userid\":123,\"user_id\":\"123\",\"userinfo\":{\"userId\":123},\"user_info\":{\"userid\":123}}"), session).userId);
        equal("123", KugouApi.parseVerify(ok("{\"kugouid\":123,\"kugou_id\":\"123\",\"userinfo\":{\"kugou_id\":123},\"user_info\":{\"kugouid\":123}}"), session).userId);
        for (String invalid : new String[] {
            "{\"userid\":123,\"user_id\":124}",
            "{\"userid\":123,\"kugouid\":124}",
            "{\"kugouid\":123,\"kugou_id\":124}",
            "{\"kugouid\":123,\"userinfo\":{\"kugouid\":124}}",
            "{\"kugouid\":123,\"user_info\":{\"kugou_id\":null}}",
            "{\"kugouid\":123,\"kugou_id\":\"0123\"}",
            "{\"userid\":123,\"userinfo\":{\"userId\":124}}",
            "{\"userid\":123,\"user_info\":{\"userid\":null}}",
            "{\"userid\":123,\"user_id\":true}",
            "{\"userid\":123,\"user_id\":{\"secret\":\"private-body\"}}",
            "{\"userid\":123,\"user_id\":\"0123\"}",
            "{\"userid\":123,\"user_id\":-123}",
            "{\"userid\":123,\"userinfo\":\"private-body\"}",
            "{\"id\":123,\"nickname\":\"private-body\"}",
            "{\"userid\":123.5}",
            "{\"userinfo\":{\"userid\":{\"token\":\"private-body\"}}}",
            "{\"userinfo\":{},\"user_info\":{},\"token\":\"private-body\"}"
        }) {
            final String response = invalid;
            fails("identity", () -> KugouApi.parseVerify(ok(response), session));
            try { KugouApi.parseVerify(ok(response), session); }
            catch (KugouApi.ApiException error) {
                String diagnostic = error.identityDiagnostic;
                String[] lines = diagnostic.split("\n");
                equal(true, lines.length >= 16 && lines.length <= 39);
                for (int lineIndex = 0; lineIndex < 15; lineIndex++) {
                    String line = lines[lineIndex];
                    if (!line.matches("data\\.(?:(?:userinfo|user_info)\\.)?(?:userid|user_id|userId|kugouid|kugou_id): (?:missing|string|number|other),valid=(?:true|false),matches=(?:true|false)"))
                        throw new AssertionError("身份诊断包含非固定格式信息");
                    passed++;
                }
                if (diagnostic.contains("private-body") || diagnostic.contains("fixture-token") || diagnostic.contains("123"))
                    throw new AssertionError("身份诊断泄漏账号或响应数据");
            }
        }
        try { KugouApi.parseVerify(ok("{\"user_id\":124}"), session); }
        catch (KugouApi.ApiException error) {
            equal(true, error.identityDiagnostic.contains("data.userid: missing,valid=false,matches=false"));
            equal(true, error.identityDiagnostic.contains("data.user_id: number,valid=true,matches=false"));
        }
        try { KugouApi.parseCreate(new JSONObject("{\"status\":0}")); }
        catch (KugouApi.ApiException error) { equal(null, error.identityDiagnostic); }

        String schema = identityDiagnostic("{\"empty\":null,\"enabled\":true,\"error_code\":1001,\"message\":\"random-private-8950184\",\"profile\":{\"marker\":\"random-private-8950184\",\"deeper\":{\"neverTraverse\":\"random-private-8950184\"}},\"items\":[{\"neverTraverse\":\"random-private-8950184\"}],\"ACCESS_TOKEN\":\"random-private-8950184\",\"mySession\":{},\"credentials\":{},\"passwordHint\":{},\"cookieJar\":{},\"secretValue\":{},\"authorization\":{},\"bad.key\":{},\"bad\\nkey\":{},\"123start\":{},\"非ascii\":{},\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\":{}}", session);
        equal(true, schema.contains("schema:data: object"));
        equal(true, schema.contains("schema:data.empty: null"));
        equal(true, schema.contains("schema:data.enabled: boolean"));
        equal(true, schema.contains("schema:data.error_code: number"));
        equal(true, schema.contains("schema:data.message: string"));
        equal(true, schema.contains("schema:data.profile: object"));
        equal(true, schema.contains("schema:data.profile.deeper: object"));
        equal(true, schema.contains("schema:data.items: array"));
        for (String forbidden : new String[] {"random-private", "1001", "neverTraverse", "ACCESS_TOKEN", "mySession", "credentials", "passwordHint", "cookieJar", "secretValue", "authorization", "bad.key", "bad\\nkey", "123start", "非ascii", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
            equal(false, schema.contains(forbidden));
        for (String line : schema.split("\n")) {
            if (!line.startsWith("schema:")) continue;
            if (!line.matches("schema:data(?:\\.[A-Za-z_][A-Za-z0-9_]{0,39}){0,2}: (?:object|array|string|number|boolean|null)"))
                throw new AssertionError("结构摘要超出安全格式");
            passed++;
        }
        JSONObject oversized = new JSONObject();
        for (int i = 0; i < 80; i++) oversized.put("field_" + i, "random-private-8950184");
        String bounded = identityDiagnostic(oversized.toString(), session);
        int nodeCount = 0;
        for (String line : bounded.split("\n")) if (line.startsWith("schema:")) nodeCount++;
        equal(24, nodeCount);
        equal(false, bounded.contains("random-private"));
        oversized.put("zzKugouInfo", "private-priority-marker");
        oversized.put("zzUserInfo", "private-priority-marker");
        oversized.put("zzUserID", "private-priority-marker");
        oversized.put("zzInfo", "private-priority-marker");
        String prioritized = identityDiagnostic(oversized.toString(), session);
        for (String keyName : new String[] {"zzKugouInfo", "zzUserInfo", "zzUserID", "zzInfo"}) {
            equal(true, prioritized.contains("schema:data." + keyName + ": string"));
            equal(true, prioritized.indexOf("schema:data." + keyName) < prioritized.indexOf("schema:data.field_0"));
        }
        equal(false, prioritized.contains("private-priority-marker"));

        fails("risk", () -> KugouApi.parseCreate(new JSONObject("{\"status\":1,\"ssaCode\":\"risk\",\"data\":{\"qrcode\":\"fixture\"}}")));
        fails("risk", () -> KugouApi.parseCreate(new JSONObject("{\"status\":0,\"ssa_code\":\"risk\"}")));
        fails("platform", () -> KugouApi.parseCreate(new JSONObject("{\"status\":0,\"error_code\":123,\"msg\":\"fixture-token-123\"}")));
        fails("platform", () -> KugouApi.parseCreate(new JSONObject("{\"status\":1,\"error_code\":null,\"data\":{}}")));
        fails("protocol", () -> KugouApi.parseCreate(new JSONObject("{\"status\":1,\"data\":[]}")));

        KugouApi.requireEndpoint(new URL("https://login-user.kugou.com/v2/qrcode")); passed++;
        KugouApi.requireEndpoint(new URL("https://gateway.kugou.com/v3/get_my_info")); passed++;
        for (String target : new String[] {"http://login-user.kugou.com/v2/qrcode", "https://login-user.kugou.com.evil.example/v2/qrcode", "https://login-user.kugou.com:8443/v2/qrcode", "https://user:password@login-user.kugou.com/v2/qrcode", "https://gateway.kugou.com/v3/unknown", "https://gateway.kugou.com/v3/get_my_info#private"})
            fails("endpoint", () -> KugouApi.requireEndpoint(new URL(target)));

        // Public API exercises the same request construction and parsing with a fake transport.
        int[] calls = {0};
        KugouApi api = new KugouApi(DEVICE, request -> {
            calls[0]++;
            try {
                if (request.endpoint.endsWith("/qrcode")) return ok("{\"qrcode\":\"fixture-key\"}");
                if (request.endpoint.endsWith("/get_userinfo_qrcode"))
                    return ok("{\"status\":4,\"userid\":123,\"token\":\"fixture-token-123\"}");
                return ok("{\"userid\":123,\"nickname\":\"验证完成\"}");
            } catch (Exception error) { throw new IOException("测试响应构建失败"); }
        });
        String key = api.createLogin();
        KugouApi.Poll result = api.poll(key);
        equal("验证完成", api.verify(result.session).nickname);
        equal(3, calls[0]);
        fails("credentials", () -> api.verify(null));
        fails("protocol", () -> api.poll("invalid?key"));
        equal(3, calls[0]); // Invalid requests never reach transport.
        JSONObject[] probeResponses = {
            new JSONObject("{\"status\":0}"),
            new JSONObject("{\"status\":1,\"error_code\":1001}"),
            new JSONObject("{\"status\":200,\"error_code\":\"-7\"}"),
            ok("{\"nickname\":\"资料\",\"sex\":1}"),
            ok("{\"userid\":123,\"nickname\":\"资料\",\"actor_status\":0}")
        };
        for (int index = 0; index < probeResponses.length; index++) {
            JSONObject response = probeResponses[index];
            int[] probeCalls = {0};
            KugouApi probeApi = new KugouApi(DEVICE, request -> {
                probeCalls[0]++;
                equal("https://gateway.kugou.com/v3/get_my_info", request.endpoint);
                equal(session.userId, request.params.get("userid"));
                String fake = request.params.get("token");
                equal(session.token.length(), fake.length());
                equal(false, session.token.equals(fake));
                equal(true, fake.matches("[A-Za-z0-9]+"));
                equal(false, request.body.contains(session.token));
                return response;
            });
            equal(index < 3 ? KugouApi.ProbeResult.REJECTED : KugouApi.ProbeResult.ACCEPTED, probeApi.probeInvalidToken(session));
            equal(1, probeCalls[0]);
            equal("fixture-token-123", session.token);
        }
        String[] inconclusive = {
            "{\"status\":0,\"ssaCode\":\"risk\"}",
            "{\"status\":1,\"ssa_code\":\"risk\",\"error_code\":1001}",
            "{\"status\":1,\"data\":{}}",
            "{\"status\":1,\"data\":{\"userid\":123}}",
            "{\"status\":1,\"data\":{\"userid\":124,\"nickname\":\"资料\",\"sex\":1}}",
            "{\"status\":1,\"data\":[]}",
            "{\"status\":1,\"error_code\":true}",
            "{\"status\":7,\"data\":{\"nickname\":\"资料\",\"sex\":1}}",
            "{\"message\":\"fixture-token-123\"}"
        };
        String[] expectedCodes = {"risk", "risk", "identity", "protocol", "identity", "protocol", "platform", "platform", "platform"};
        for (int index = 0; index < inconclusive.length; index++) {
            JSONObject response = new JSONObject(inconclusive[index]);
            int[] probeCalls = {0};
            KugouApi probeApi = new KugouApi(DEVICE, request -> { probeCalls[0]++; return response; });
            fails(expectedCodes[index], () -> probeApi.probeInvalidToken(session));
            equal(1, probeCalls[0]);
        }
        KugouApi offlineProbe = new KugouApi(DEVICE, request -> { throw new KugouApi.ApiException("network", "离线测试网络错误"); });
        fails("network", () -> offlineProbe.probeInvalidToken(session));
        fails("credentials", () -> offlineProbe.probeInvalidToken(null));
        System.out.println("KuGou protocol offline checks passed: " + passed);
    }

    private static JSONObject ok(String data) throws Exception { return new JSONObject("{\"status\":1,\"error_code\":0,\"data\":" + data + "}"); }
    private static String identityDiagnostic(String data, KugouApi.Session session) throws Exception {
        try { KugouApi.parseVerify(ok(data), session); }
        catch (KugouApi.ApiException error) {
            equal("identity", error.code);
            return error.identityDiagnostic;
        }
        throw new AssertionError("无身份响应未拒绝");
    }
    private interface Action { void run() throws Exception; }
    private static void equal(Object expected, Object actual) {
        if (expected == null ? actual != null : !expected.equals(actual))
            throw new AssertionError("离线测试断言失败");
        passed++;
    }
    private static void fails(String code, Action action) throws Exception {
        try { action.run(); }
        catch (KugouApi.ApiException error) {
            equal(code, error.code);
            if (error.getCause() != null || error.getMessage().contains("fixture-token") || error.getMessage().contains("https://"))
                throw new AssertionError("错误信息包含原始协议数据");
            return;
        }
        throw new AssertionError("无效响应未被拒绝");
    }
    private static void failsArgument(Action action) throws Exception {
        try { action.run(); }
        catch (IllegalArgumentException expected) { passed++; return; }
        throw new AssertionError("无效输入未被拒绝");
    }
    private static String repeat(String text, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(text);
        return out.toString();
    }
}
