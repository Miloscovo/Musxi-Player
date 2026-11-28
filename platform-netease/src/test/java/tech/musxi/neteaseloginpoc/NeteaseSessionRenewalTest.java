package tech.musxi.neteaseloginpoc;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;
import org.junit.Test;

/** Account verification and the first stream request use offline responses only. */
public class NeteaseSessionRenewalTest {
    private static final String VERIFIED = "{code:200,account:{id:7},profile:{userId:7,nickname:'Fixture'}}";

    @Test public void verifiedRenewalSurvivesStorageAndIsUsedForPlayback() throws Exception {
        NeteaseApi.Session original = savedSession();
        NeteaseApi api = XeapiTestPeer.api(request -> {
            if (NeteaseApi.STATUS.equals(request.endpoint)) {
                assertTrue(request.headers.get("Cookie").contains("MUSIC_U=old-session"));
                return verified("MUSIC_U=new-session; Domain=.music.163.com; Path=/; Secure; HttpOnly",
                        "__csrf=new-csrf; Domain=.music.163.com; Path=/; Secure");
            }
            assertEquals(NeteaseApi.SONG_URL, request.endpoint);
            String cookie = request.headers.get("Cookie");
            assertTrue(cookie.contains("MUSIC_U=new-session"));
            assertTrue(cookie.contains("__csrf=new-csrf"));
            assertFalse(cookie.contains("old-session"));
            return response("{code:200}");
        });
        NeteaseApi.Session restored = NeteaseApi.Session.fromJson(api.verify(original).toJson());
        api.authenticatedCall(NeteaseApi.SONG_URL, new JSONObject(), restored);
        assertEquals("old-session", original.toJson().getJSONObject("cookies").getString("MUSIC_U"));
    }

    @Test public void renewalIgnoresOutOfScopeCookiesAndCanClearOnlyTheCsrfToken() throws Exception {
        NeteaseApi api = new NeteaseApi(request -> verified(
                "MUSIC_U=host-only; Path=/; Secure",
                "MUSIC_U=wrong-host; Domain=example.test; Path=/; Secure",
                "MUSIC_U=wrong-path; Domain=.music.163.com; Path=/weapi; Secure",
                "__csrf=stale; Domain=.music.163.com; Path=/; Max-Age=0; Secure"));
        JSONObject cookies = api.verify(savedSession()).toJson().getJSONObject("cookies");
        assertEquals("old-session", cookies.getString("MUSIC_U"));
        assertFalse(cookies.has("__csrf"));
    }

    @Test public void rejectsAnotherAccountAndNeverRetainsARevokedSession() throws Exception {
        String renewal = "MUSIC_U=new-session; Domain=.music.163.com; Path=/; Secure";
        NeteaseApi switched = new NeteaseApi(request -> response(
                "{code:200,account:{id:8},profile:{userId:8,nickname:'Other'}}", renewal));
        assertEquals("identity", assertThrows(NeteaseApi.ApiException.class,
                () -> switched.verify(savedSession())).code);

        NeteaseApi revoked = new NeteaseApi(request -> verified(
                "MUSIC_U=revoked; Domain=.music.163.com; Path=/; Max-Age=0; Secure"));
        assertEquals("expired", assertThrows(NeteaseApi.ApiException.class,
                () -> revoked.verify(savedSession())).code);
        NeteaseApi ambiguous = new NeteaseApi(request -> verified(renewal,
                "MUSIC_U=another-session; Domain=.music.163.com; Path=/; Secure"));
        assertEquals("protocol", assertThrows(NeteaseApi.ApiException.class,
                () -> ambiguous.verify(savedSession())).code);
    }

    private static NeteaseApi.Session savedSession() throws Exception {
        return NeteaseApi.Session.fromJson(new JSONObject(
                "{userId:'7',nickname:'Fixture',cookies:{MUSIC_U:'old-session',__csrf:'old-csrf'}}"));
    }

    private static NeteaseApi.Response verified(String... cookies) throws IOException {
        return response(VERIFIED, cookies);
    }

    private static NeteaseApi.Response response(String body, String... cookies) throws IOException {
        Map<String,List<String>> headers = Collections.singletonMap("Set-Cookie", Arrays.asList(cookies));
        try { return new NeteaseApi.Response(new JSONObject(body), headers); }
        catch (org.json.JSONException invalid) { throw new IOException("Invalid test fixture", invalid); }
    }
}
