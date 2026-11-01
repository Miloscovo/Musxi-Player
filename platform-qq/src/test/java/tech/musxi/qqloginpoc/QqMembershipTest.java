package tech.musxi.qqloginpoc;

import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;

/** Offline tests for the fixed QQ VIP read endpoint and membership parser. */
public final class QqMembershipTest {
    private static final String KEY = "fixture-music-key";
    private static final String CREDENTIAL = "{\"musicid\":12345,\"musickey\":\"fixture-music-key\",\"loginType\":2}";

    @Test
    public void usesFixedReadOnlyEndpointWithAuthenticatedComm() throws Exception {
        Fixture fixture = new Fixture();
        fixture.add(dataResponse("{\"identity\":{\"vip\":1},\"userinfo\":{\"expire\":\"1798761600\"}}"));
        QqMusicApi.Membership result = api(fixture).membership(session());

        assertEquals(1, result.isVip);
        assertEquals("绿钻会员", result.name);
        assertEquals(Long.valueOf(1798761600000L), result.expiresAtEpochMs);
        assertEquals(1, fixture.requests.size());
        QqMusicApi.Request request = fixture.requests.get(0);
        assertEquals("https://u.y.qq.com/cgi-bin/musicu.fcg", request.endpoint);
        assertEquals("application/json; charset=utf-8", request.contentType);

        JSONObject body = new JSONObject(request.body);
        JSONObject identity = body.getJSONObject("req_0");
        assertEquals("VipLogin.VipLoginInter", identity.getString("module"));
        assertEquals("vip_login_base", identity.getString("method"));
        assertFalse(identity.getJSONObject("param").keys().hasNext());
        JSONObject comm = body.getJSONObject("comm");
        assertEquals("12345", comm.getString("uin"));
        assertEquals("12345", comm.getString("qq"));
        assertEquals(KEY, comm.getString("authst"));
        assertEquals("2", comm.getString("tmeLoginType"));
        assertEquals(String.valueOf(QqMusicApi.hash33(KEY, 5381)), comm.getString("g_tk"));
    }

    @Test
    public void reportsExplicitPositiveIdentitiesAndPriorities() throws Exception {
        QqMusicApi.Membership superVip = membership(
            "{\"svip\":1,\"identity\":{\"vip\":1},\"userinfo\":{\"expire\":\"1798761600000\"}}");
        assertEquals(1, superVip.isVip);
        assertEquals("超级会员", superVip.name);
        assertEquals(Long.valueOf(1798761600000L), superVip.expiresAtEpochMs);

        QqMusicApi.Membership star = membership(
            "{\"star\":1,\"starend\":\"20270101\",\"identity\":{\"vip\":0,\"HugeVip\":0}}");
        assertEquals(1, star.isVip);
        assertEquals("星级会员", star.name);
        assertEquals(Long.valueOf(1798732800000L), star.expiresAtEpochMs);

        QqMusicApi.Membership yearStar = membership(
            "{\"ystar\":1,\"ystarend\":\"1798761600\",\"identity\":{\"vip\":0,\"HugeVip\":0}}");
        assertEquals(1, yearStar.isVip);
        assertEquals("星级会员", yearStar.name);
        assertEquals(Long.valueOf(1798761600000L), yearStar.expiresAtEpochMs);

        QqMusicApi.Membership huge = membership(
            "{\"identity\":{\"huge_vip\":1,\"huge_vip_end\":\"1798761600\"}}");
        assertEquals(1, huge.isVip);
        assertEquals("豪华绿钻", huge.name);
        assertEquals(Long.valueOf(1798761600000L), huge.expiresAtEpochMs);
    }

    @Test
    public void reportsNonMemberOnlyWhenVipAndHugeVipAreExplicitZero() throws Exception {
        for (String identity : new String[] {
            "{\"vip\":0,\"HugeVip\":0}",
            "{\"vip\":0,\"huge_vip\":0,\"GroupVipFlag\":0}"
        }) {
            QqMusicApi.Membership result = membership("{\"identity\":" + identity + "}");
            assertEquals(0, result.isVip);
            assertEquals("非会员", result.name);
            assertNull(result.expiresAtEpochMs);
        }
    }

    @Test
    public void missingOrIncompleteIdentityIsUnknown() throws Exception {
        for (String data : new String[] {
            "{}",
            "{\"identity\":{}}",
            "{\"identity\":{\"vip\":0}}",
            "{\"identity\":{\"HugeVip\":0}}",
            "{\"svip\":0,\"star\":0,\"ystar\":0}"
        }) {
            QqMusicApi.Membership result = membership(data);
            assertEquals(-1, result.isVip);
            assertNull(result.name);
            assertNull(result.expiresAtEpochMs);
        }
    }

    @Test
    public void parsesSupportedExpiryFormatsAndDropsInvalidOnes() throws Exception {
        QqMusicApi.Membership epochSeconds = membership(
            "{\"identity\":{\"vip\":1},\"userinfo\":{\"expire\":1798761600}}");
        assertEquals(Long.valueOf(1798761600000L), epochSeconds.expiresAtEpochMs);

        QqMusicApi.Membership epochMillis = membership(
            "{\"identity\":{\"vip\":1},\"userinfo\":{\"expire\":\"1798761600000\"}}");
        assertEquals(Long.valueOf(1798761600000L), epochMillis.expiresAtEpochMs);

        QqMusicApi.Membership beijingDate = membership(
            "{\"identity\":{\"vip\":1},\"userinfo\":{\"expire\":\"20270101\"}}");
        assertEquals(Long.valueOf(1798732800000L), beijingDate.expiresAtEpochMs);

        QqMusicApi.Membership invalidDate = membership(
            "{\"identity\":{\"HugeVip\":1,\"HugeVipEnd\":\"20270230\"},"
                + "\"userinfo\":{\"expire\":\"20270101\"}}");
        assertEquals(1, invalidDate.isVip);
        assertNull(invalidDate.expiresAtEpochMs);

        QqMusicApi.Membership invalidLength = membership(
            "{\"identity\":{\"HugeVip\":1,\"HugeVipEnd\":\"123456789\"}}");
        assertNull(invalidLength.expiresAtEpochMs);
    }

    private static QqMusicApi api(Fixture fixture) {
        return new QqMusicApi(fixture);
    }

    private static QqMusicApi.Membership membership(String data) throws IOException {
        Fixture fixture = new Fixture();
        fixture.add(dataResponse(data));
        return api(fixture).membership(session());
    }

    private static QqMusicApi.Session session() throws IOException {
        try { return QqMusicApi.Session.fromJson(new JSONObject("{\"credential\":" + CREDENTIAL + "}")); }
        catch (org.json.JSONException invalid) { throw new AssertionError("Invalid session fixture", invalid); }
    }

    private static QqMusicApi.Response dataResponse(String data) {
        String body = "{\"code\":0,\"req_0\":{\"code\":0,\"data\":" + data + "}}";
        return new QqMusicApi.Response(200, body.getBytes(StandardCharsets.UTF_8),
            Collections.emptyMap());
    }

    private static final class Fixture implements QqMusicApi.Transport {
        final ArrayDeque<QqMusicApi.Response> replies = new ArrayDeque<>();
        final List<QqMusicApi.Request> requests = new ArrayList<>();
        void add(QqMusicApi.Response response) { replies.add(response); }
        @Override public QqMusicApi.Response execute(QqMusicApi.Request request) {
            requests.add(request);
            if (replies.isEmpty()) throw new AssertionError("Unexpected protocol call");
            return replies.remove();
        }
    }
}
