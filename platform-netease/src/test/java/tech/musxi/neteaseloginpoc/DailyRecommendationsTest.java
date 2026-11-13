package tech.musxi.neteaseloginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.net.URL;
import java.util.Collections;
import java.util.List;
import static org.junit.Assert.*;

/** Offline contract for the pinned module/recommend_songs.js daily snapshot read.
 * No real account, network or Gradle device run is involved here.
 */
public class DailyRecommendationsTest {
    @Test public void usesPinnedWeapiRouteAndCarriesTheSessionCookies() throws Exception {
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(daily(song("1", "Song")), Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                json("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));
        List<NeteaseLibraryApi.Track> tracks = new NeteaseLibraryApi(api).dailyRecommendations(session);
        assertEquals(1, tracks.size());

        assertEquals(1, requests.size());
        NeteaseApi.Request request = requests.get(0);
        assertEquals(NeteaseApi.DAILY_RECOMMENDATIONS, request.endpoint);
        assertEquals("https://music.163.com/weapi/v3/discovery/recommend/songs", request.endpoint);
        NeteaseApi.requireEndpoint(new URL(request.endpoint));
        // WEAPI uses a per-request random secret, so only the profile shape is observable here.
        assertTrue(request.body.contains("params="));
        assertTrue(request.body.contains("encSecKey="));
        String cookie = request.headers.get("Cookie");
        assertTrue(cookie.contains("MUSIC_U=secret"));
        assertTrue(cookie.contains("__csrf=csrf"));
        // The pinned module sends only afresh, which is omitted when unset: no write intent.
        assertEquals("https://music.163.com/", request.headers.get("Referer"));
        assertNull(request.headers.get("X-antiCheatToken"));
    }

    @Test public void preservesDailySongOrderAndParsesTheExistingTrackShape() throws Exception {
        JSONObject body = daily(
                song("900", "Third"),
                song("100", "First"),
                song("500", "Second"));
        List<NeteaseLibraryApi.Track> tracks = NeteaseLibraryApi.parseDailyRecommendations(body);
        assertEquals(3, tracks.size());
        assertEquals("900", tracks.get(0).id);
        assertEquals("100", tracks.get(1).id);
        assertEquals("500", tracks.get(2).id);
        NeteaseLibraryApi.Track first = tracks.get(1);
        assertEquals("First", first.name);
        assertEquals("Album", first.album);
        assertEquals(Collections.singletonList("Artist"), first.artists);
        assertEquals(Collections.singletonList("12"), first.artistIds);
        assertEquals("20", first.albumId);
        assertEquals(1000L, first.durationMs);
        assertTrue(first.available);
        assertEquals("https://p1.music.126.net/cover.jpg", first.coverUrl);
        try {
            tracks.clear();
            fail("Must be immutable");
        } catch (UnsupportedOperationException expected) { }
    }

    @Test public void emptySnapshotIsAllowedButStructuredDamageIsNotAnEmptySuccess() throws Exception {
        assertEquals(Collections.emptyList(),
                NeteaseLibraryApi.parseDailyRecommendations(json("{code:200,data:{dailySongs:[]}}")));
        assertTrue(new NeteaseLibraryApi(new NeteaseApi(request ->
                new NeteaseApi.Response(json("{code:200,data:{dailySongs:[]}}"), Collections.emptyMap())))
                .dailyRecommendations(session()).isEmpty());

        for (String malformed : new String[]{
                "{}",
                "{code:200}",
                "{code:200,data:null}",
                "{code:200,data:{}}",
                "{code:200,data:{dailySongs:null}}",
                "{code:200,data:{dailySongs:{}}}",
                "{code:200,data:{dailySongs:[null]}}",
                "{code:200,data:{dailySongs:[1]}}",
                "{code:200,data:{dailySongs:[{id:0}]}}",
                "{code:200,data:{dailySongs:[{id:'01'}]}}",
                "{code:200,data:{dailySongs:[{id:1},{id:2}]}}",
                // A row without the ar/al/dt detail shape is structural damage, not a missing song.
                "{code:200,data:{dailySongs:[{id:1,dt:1000,ar:[{id:12,name:'A'}]}]}}",
                "{code:200,data:{dailySongs:[{id:1,dt:1000,ar:null,al:{id:20,name:'B'}}]}}",
                "{code:200,data:{dailySongs:[{id:1,dt:-1,ar:[{id:12,name:'A'}],al:{id:20,name:'B'}}]}}"})
            fails("protocol", () -> NeteaseLibraryApi.parseDailyRecommendations(json(malformed)));
        fails("identity", () -> NeteaseLibraryApi.parseDailyRecommendations(
                json("{code:200,data:{dailySongs:[" + songText("1") + "," + songText("1") + "]}}")));

        JSONArray tooMany = new JSONArray();
        for (int i = 1; i <= 200; i++) tooMany.put(song(String.valueOf(i), "Song"));
        assertEquals(200, NeteaseLibraryApi.parseDailyRecommendations(
                new JSONObject().put("code", 200).put("data",
                        new JSONObject().put("dailySongs", tooMany))).size());
        tooMany.put(song("201", "Extra"));
        fails("limit", () -> NeteaseLibraryApi.parseDailyRecommendations(
                new JSONObject().put("code", 200).put("data",
                        new JSONObject().put("dailySongs", tooMany))));
    }

    @Test public void platformAndTransportFailuresAreNeverReturnedAsAnEmptyList() throws Exception {
        fails("platform", () -> new NeteaseLibraryApi(new NeteaseApi(request ->
                new NeteaseApi.Response(json("{code:500}"), Collections.emptyMap())))
                .dailyRecommendations(session()));
        fails("expired", () -> new NeteaseLibraryApi(new NeteaseApi(request ->
                new NeteaseApi.Response(json("{code:301}"), Collections.emptyMap())))
                .dailyRecommendations(session()));
        fails("protocol", () -> new NeteaseLibraryApi(new NeteaseApi(request ->
                new NeteaseApi.Response(json("{data:{dailySongs:[]}}"), Collections.emptyMap())))
                .dailyRecommendations(session()));
        fails("credentials", () -> new NeteaseLibraryApi(new NeteaseApi(request -> {
            fail("A missing session must not reach the transport");
            return null;
        })).dailyRecommendations(null));
    }

    @Test public void lateAccountChangeShortCircuitsBeforeTheRequestAndAfterTheResponse() throws Exception {
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(daily(song("1", "Song")), Collections.emptyMap());
        });
        fails("account_changed", () -> new NeteaseLibraryApi(api, () -> false).dailyRecommendations(session()));
        assertTrue(requests.isEmpty());

        final int[] seen = {0};
        fails("account_changed", () -> new NeteaseLibraryApi(api, () -> seen[0]++ == 0)
                .dailyRecommendations(session()));
        assertEquals(1, requests.size());

        Thread.currentThread().interrupt();
        try {
            fails("account_changed", () -> new NeteaseLibraryApi(api).dailyRecommendations(session()));
        } finally {
            Thread.interrupted();
        }
    }

    private static NeteaseApi.Session session() throws Exception {
        return NeteaseApi.Session.fromJson(
                json("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret'}}"));
    }
    /** Transport lambdas cannot declare checked exceptions, so fixtures fail as assertion errors. */
    private static JSONObject daily(Object... songs) {
        JSONArray rows = new JSONArray();
        for (Object song : songs) rows.put(song);
        try {
            return new JSONObject().put("code", 200)
                    .put("data", new JSONObject().put("dailySongs", rows));
        } catch (org.json.JSONException invalid) {
            throw new AssertionError(invalid);
        }
    }
    private static JSONObject song(String id, String name) {
        return json("{id:" + id + ",name:'" + name + "',dt:1000,alg:'daily',"
                + "ar:[{id:12,name:'Artist'}],al:{id:20,name:'Album',picUrl:'https://p1.music.126.net/cover.jpg'}}");
    }
    private static String songText(String id) {
        return "{id:" + id + ",name:'Song',dt:1000,alg:'daily',ar:[{id:12,name:'Artist'}],"
                + "al:{id:20,name:'Album',picUrl:'https://p1.music.126.net/cover.jpg'}}";
    }
    private static JSONObject json(String text) {
        try {
            return new JSONObject(text);
        } catch (org.json.JSONException invalid) {
            throw new AssertionError(invalid);
        }
    }
    private static void fails(String code, Body body) {
        try {
            body.run();
            fail("Expected " + code);
        } catch (NeteaseApi.ApiException expected) {
            assertEquals(code, expected.code);
            assertFalse(expected.getMessage().isEmpty());
        } catch (Exception other) {
            fail("Expected " + code + " but got " + other);
        }
    }
    private interface Body { void run() throws Exception; }
}
