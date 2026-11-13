package tech.musxi.loginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Offline protocol checks for the current-account personal daily recommendation.
 * Fixed request profile: KuGouMusicApi a5a98013 module/recommend_songs.js.
 */
public class DailyRecommendationsTest {
    private static final String HASH_A = "0123456789abcdef0123456789abcdef";
    private static final String HASH_B = "fedcba9876543210fedcba9876543210";
    private static final KugouApi.Session SESSION = new KugouApi.Session("fixture-token", "123", "Fixture");

    @Test public void usesStandardSignedDailyProfileWithBoundEndpoint() throws Exception {
        List<KugouApi.Request> calls = new ArrayList<>();
        KugouLibraryApi library = library(calls, "{status:1,data:{song_list:[]}}");

        assertTrue(library.dailyRecommendations(SESSION).isEmpty());
        assertEquals(1, calls.size());
        KugouApi.Request call = calls.get(0);
        assertEquals("https://gateway.kugou.com/everyday_song_recommend", call.endpoint);
        assertEquals("everydayrec.service.kugou.com", call.headers.get("x-router"));
        assertEquals("1005", call.params.get("appid"));
        assertEquals("20489", call.params.get("clientver"));
        assertEquals("123", call.params.get("userid"));
        assertEquals("fixture-token", call.params.get("token"));
        assertEquals("b".repeat(32), call.params.get("mid"));
        assertTrue(call.body.contains("\"platform\":\"android\""));
        assertTrue(call.body.contains("\"userid\":\"123\""));
        // The standard salt profile signs sorted params plus the exact body.
        Map<String, String> signed = new LinkedHashMap<>(call.params);
        String signature = signed.remove("signature");
        assertEquals(KugouApi.standardSignature(signed, call.body), signature);
        KugouApi.requireEndpoint(new URL(call.endpoint));
        // The daily endpoint is additive: neighbouring gateway paths stay refused.
        rejects("endpoint", () -> KugouApi.requireEndpoint(
            new URL("https://gateway.kugou.com/everyday/api/v1/get_history")));
    }

    @Test public void keepsProviderOrderAndMapsExistingTrackFields() throws Exception {
        List<KugouApi.Request> calls = new ArrayList<>();
        KugouLibraryApi library = library(calls, "{status:1,data:{song_list:[" + row(HASH_A, "First", 61) + ","
            + row(HASH_B, "Second", 122) + "]}}");

        List<KugouLibraryApi.Track> tracks = library.dailyRecommendations(SESSION);
        assertEquals(2, tracks.size());
        assertEquals(Arrays.asList("First", "Second"),
            Arrays.asList(tracks.get(0).name, tracks.get(1).name));
        KugouLibraryApi.Track first = tracks.get(0);
        assertEquals(HASH_A + "_12_34", first.id);
        assertEquals("Album A", first.album);
        assertEquals(Arrays.asList("Artist One", "Artist Two"), first.artists);
        assertEquals(Arrays.asList("11", "12"), first.artistIds);
        assertEquals(61_000L, first.durationMs);
        assertEquals("https://imge.kugou.com/stdmusic/400/fixture.jpg", first.coverUrl);
        // Unmodifiable: a caller cannot reorder the provider batch in place.
        try { tracks.add(tracks.get(0)); fail("immutable list expected"); }
        catch (UnsupportedOperationException expected) { }
    }

    @Test public void fallsBackToAuthorNameAndUnionCoverWithoutSplitting() throws Exception {
        KugouLibraryApi.Track track = single("[{hash:'" + HASH_A + "',album_id:12,mixsongid:34,"
            + "songname:'Title.mp3',author_name:'Solo Artist',time_length:200,"
            + "trans_param:{union_cover:'http://imge.kugou.com/stdmusic/{size}/x.jpg'}}]");
        assertEquals("Title", track.name);
        assertEquals(java.util.Collections.singletonList("Solo Artist"), track.artists);
        assertTrue(track.artistIds.isEmpty());
        assertEquals(200_000L, track.durationMs);
        assertEquals("https://imge.kugou.com/stdmusic/400/x.jpg", track.coverUrl);
    }

    @Test public void emptySongListIsAnExplicitEmptySuccess() throws Exception {
        List<KugouApi.Request> calls = new ArrayList<>();
        assertTrue(library(calls, "{status:1,data:{song_list:[]}}").dailyRecommendations(SESSION).isEmpty());
        assertEquals(1, calls.size());
        // A missing or malformed list is never reported as an empty day.
        rejects("protocol", () -> KugouLibraryApi.parseDailyRecommendations(json("{status:1,data:{}}")));
        rejects("protocol", () -> KugouLibraryApi.parseDailyRecommendations(json("{status:1,data:{song_list:0}}")));
        rejects("protocol", () -> KugouLibraryApi.parseDailyRecommendations(json("{status:1}")));
        rejects("protocol", () -> KugouLibraryApi.parseDailyRecommendations(json("{data:{song_list:[]}}")));
    }

    @Test public void structuralAndIdentityDamageStaysAFixedProtocolFailure() throws Exception {
        rejects("protocol", () -> KugouLibraryApi.parseDailyRecommendations(json("{status:1,data:{song_list:[0]}}")));
        rejects("identity", () -> KugouLibraryApi.parseDailyRecommendations(json("{status:1,data:{song_list:["
            + "{hash:'not-a-hash',album_id:12,mixsongid:34,songname:'X'}]}}")));
        rejects("identity", () -> KugouLibraryApi.parseDailyRecommendations(json("{status:1,data:{song_list:["
            + row(HASH_A, "Same", 61) + "," + row(HASH_A, "Same", 61) + "]}}")));
        rejects("protocol", () -> KugouLibraryApi.parseDailyRecommendations(json("{status:1,data:{song_list:["
            + "{hash:'" + HASH_A + "',album_id:12,mixsongid:34,songname:'   '}]}}")));
        rejects("limit", () -> KugouLibraryApi.parseDailyRecommendations(json("{status:1,data:{song_list:["
            + rows(201) + "]}}")));
        rejects("platform", () -> KugouLibraryApi.parseDailyRecommendations(json("{status:0,error_code:20017}")));
        rejects("risk", () -> KugouLibraryApi.parseDailyRecommendations(
            json("{status:1,ssaCode:'fixture',data:{song_list:[]}}")));
    }

    @Test public void missingSessionIsRejectedBeforeAnyRequest() throws Exception {
        List<KugouApi.Request> calls = new ArrayList<>();
        KugouLibraryApi library = library(calls, "{status:1,data:{song_list:[]}}");
        rejects("credentials", () -> library.dailyRecommendations(null));
        assertTrue("no credential may be sent without a session", calls.isEmpty());
    }

    @Test public void dailyBatchIsBoundedAtProviderMaximum() throws Exception {
        List<KugouLibraryApi.Track> tracks = KugouLibraryApi.parseDailyRecommendations(
            json("{status:1,data:{song_list:[" + rows(200) + "]}}"));
        assertEquals(200, tracks.size());
        assertEquals(String.format("%032x", 1) + "_1_" + 1000, tracks.get(0).id);
        assertNull(tracks.get(0).coverUrl);
    }

    private static KugouLibraryApi library(List<KugouApi.Request> calls, String response) {
        return new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)), request -> {
            calls.add(request);
            return json(response);
        }));
    }

    private static KugouLibraryApi.Track single(String rows) throws Exception {
        List<KugouLibraryApi.Track> tracks = KugouLibraryApi.parseDailyRecommendations(
            json("{status:1,data:{song_list:" + rows + "}}"));
        assertEquals(1, tracks.size());
        return tracks.get(0);
    }

    private static String row(String hash, String name, int seconds) {
        return "{hash:'" + hash + "',album_id:12,mixsongid:34,songname:'" + name + "',album_name:'Album A',"
            + "time_length:" + seconds + ",sizable_cover:'http://imge.kugou.com/stdmusic/{size}/fixture.jpg',"
            + "singerinfo:[{id:11,name:'Artist One'},{id:12,name:'Artist Two'}]}";
    }

    private static String rows(int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) out.append(',');
            out.append("{hash:'").append(String.format("%032x", i + 1)).append("',album_id:1,mixsongid:")
                .append(1000 + i).append(",songname:'S").append(i).append("',time_length:60}");
        }
        return out.toString();
    }

    private static JSONObject json(String source) {
        try { return new JSONObject(source); }
        catch (org.json.JSONException malformed) { throw new AssertionError(malformed); }
    }

    private static void rejects(String code, Action action) throws Exception {
        try {
            action.run();
            fail("Expected fixed failure " + code);
        } catch (KugouApi.ApiException failure) {
            assertEquals(code, failure.code);
            assertNull(failure.getCause());
            assertFalse(failure.getMessage() == null || failure.getMessage().isEmpty());
        }
    }

    private interface Action { void run() throws Exception; }
}
