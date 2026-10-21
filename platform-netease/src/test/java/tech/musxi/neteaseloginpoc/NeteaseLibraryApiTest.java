package tech.musxi.neteaseloginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class NeteaseLibraryApiTest {
    interface Checked { void run() throws Exception; }
    private static JSONObject j(String text) throws Exception { return new JSONObject(text); }
    private static void fails(String code, Checked block) throws Exception {
        try { block.run(); fail("Expected safe failure"); }
        catch (NeteaseApi.ApiException failure) {
            assertEquals(code, failure.code);
            assertFalse(failure.getMessage().contains("secret"));
            assertNull(failure.getCause());
        }
    }

    @Test public void playlistPaginationIsBoundedAndMakesProgress() throws Exception {
        JSONObject body = j("{playlist:[{id:42,name:'List',trackCount:2}],more:true}");
        NeteaseLibraryApi.Page<NeteaseLibraryApi.Playlist> page = NeteaseLibraryApi.parsePlaylists(body, 30, 30);
        assertEquals(31, page.nextOffset);
        assertEquals("42", page.items.get(0).id);
        fails("protocol", () -> NeteaseLibraryApi.parsePlaylists(j("{playlist:[],more:true}"), 0, 30));
        fails("request", () -> NeteaseLibraryApi.parsePlaylists(body, -1, 30));
        fails("request", () -> NeteaseLibraryApi.parsePlaylists(body, 0, 101));
        fails("protocol", () -> NeteaseLibraryApi.parsePlaylists(j("{playlist:[],more:'false'}"), 0, 30));
        fails("protocol", () -> NeteaseLibraryApi.parsePlaylists(j("{playlist:[{id:1.5,name:'Bad',trackCount:0}],more:false}"), 0, 30));
    }

    @Test public void playlistIdsRequireCompleteMatchingIdentity() throws Exception {
        JSONObject body = j("{playlist:{id:42,trackCount:2,trackIds:[{id:1},{id:2}]}}");
        assertEquals(Arrays.asList("1", "2"), NeteaseLibraryApi.parseTrackIds(body,"42"));
        fails("identity", () -> NeteaseLibraryApi.parseTrackIds(body, "43"));
        fails("incomplete", () -> NeteaseLibraryApi.parseTrackIds(j("{playlist:{id:42,trackCount:2,trackIds:[{id:1}]}}"),"42"));
        fails("protocol", () -> NeteaseLibraryApi.parseTrackIds(j("{playlist:{id:42,trackCount:2,trackIds:[{id:1},{id:1}]}}"),"42"));
    }

    @Test public void songDetailsPreserveRequestedOrderAndMissingRows() throws Exception {
        JSONObject body = j("{songs:[{id:2,name:'Second',dt:2000,ar:[{name:'Artist'}],al:{name:'Album'}}]}");
        java.util.List<NeteaseLibraryApi.Track> tracks = NeteaseLibraryApi.parseTracks(body, Arrays.asList("1","2"));
        assertEquals("1",tracks.get(0).id);
        assertFalse(tracks.get(0).available);
        assertEquals("2",tracks.get(1).id);
        assertTrue(tracks.get(1).available);
        fails("identity", () -> NeteaseLibraryApi.parseTracks(body, Collections.singletonList("3")));
    }

    @Test public void searchUsesFixedFirstPageAndReturnsMetadataInSearchOrder() throws Exception {
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        JSONObject fixture = j("{code:200,result:{songCount:12,songs:["
                + "{id:2,name:'Second',dt:2000,ar:[{name:'Artist'}],al:{name:'Album'}},"
                + "{id:1,name:'First',dt:1000,ar:[],al:{name:'Other'}}]}}");
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(fixture, Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        java.util.List<NeteaseLibraryApi.Track> tracks = new NeteaseLibraryApi(api).search(session, "  中\u0001文\n  ");
        assertEquals(1, requests.size());
        NeteaseApi.Request request = requests.get(0);
        assertEquals(NeteaseApi.SEARCH, request.endpoint);
        NeteaseApi.requireEndpoint(new URL(request.endpoint));
        JSONObject payload = searchPayload(request);
        assertEquals("中文", payload.getString("s"));
        assertEquals(1, payload.getInt("type"));
        assertEquals(10, payload.getInt("limit"));
        assertEquals(0, payload.getInt("offset"));
        assertTrue(payload.getBoolean("total"));
        assertFalse(payload.getBoolean("e_r"));
        assertEquals(2, tracks.size());
        assertEquals("2", tracks.get(0).id);
        assertEquals("Second", tracks.get(0).name);
        assertEquals(Collections.singletonList("Artist"), tracks.get(0).artists);
        assertEquals("Album", tracks.get(0).album);
        assertEquals(2000, tracks.get(0).durationMs);
        assertEquals("1", tracks.get(1).id);
        try { tracks.clear(); fail("Must be immutable"); } catch (UnsupportedOperationException expected) { }
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(request.endpoint + "?s=secret")));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL("https://interfacepc.music.163.com/eapi/cloudsearch/other")));
    }

    @Test public void searchRejectsMalformedAndOversizedResultsAndAcceptsEmptyResult() throws Exception {
        assertTrue(NeteaseLibraryApi.parseSearch(j("{result:{songCount:0}}")).isEmpty());
        assertTrue(NeteaseLibraryApi.parseSearch(j("{result:{songCount:0,songs:[]}}")).isEmpty());
        for (String text : new String[]{"{}", "{result:[]}", "{result:{songCount:'0'}}",
                "{result:{songCount:1}}", "{result:{songCount:1,songs:[]}}",
                "{result:{songCount:0,songs:null}}", "{result:{songCount:1,songs:[null]}}",
                "{result:{songCount:1,songs:[{id:1,name:'secret',dt:1000,ar:[],al:null}]}}"})
            fails("protocol", () -> NeteaseLibraryApi.parseSearch(j(text)));
        JSONArray rows = new JSONArray();
        for (int i = 1; i <= 10; i++) rows.put(j("{id:" + i + ",name:'Song',dt:1000,ar:[],al:{name:'Album'}}"));
        JSONObject result = new JSONObject().put("songCount", 100).put("songs", rows);
        JSONObject body = new JSONObject().put("result", result);
        assertEquals(10, NeteaseLibraryApi.parseSearch(body).size());
        rows.put(rows.getJSONObject(0));
        fails("limit", () -> NeteaseLibraryApi.parseSearch(body));
        JSONArray duplicates = new JSONArray().put(rows.getJSONObject(0)).put(rows.getJSONObject(0));
        result.put("songs", duplicates);
        fails("identity", () -> NeteaseLibraryApi.parseSearch(body));
    }

    @Test public void searchBoundsAreCheckedBeforeTransportAndSingleSongNeverRequestsAudio() throws Exception {
        final java.util.List<String> endpoints = new java.util.ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            endpoints.add(request.endpoint);
            return new NeteaseApi.Response(jUnchecked(request.endpoint.equals(NeteaseApi.SEARCH)
                    ? "{code:200,result:{songCount:0}}"
                    : "{code:200,songs:[{id:1,name:'Song',dt:1000,ar:[],al:{name:'Album'}}]}"), Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        char[] chars = new char[128]; Arrays.fill(chars, 'x');
        String max = new String(chars);
        for (String query : new String[]{null, "", " \t\n\u0085", max + "x"})
            fails("request", () -> library.search(session, query));
        assertTrue(endpoints.isEmpty());
        assertTrue(library.search(session, max).isEmpty());
        NeteaseLibraryApi.Track track = library.song(session, "1");
        assertEquals("1", track.id);
        assertEquals("Song", track.name);
        assertEquals(Arrays.asList(NeteaseApi.SEARCH, NeteaseApi.SONG_DETAIL), endpoints);
    }

    private static JSONObject searchPayload(NeteaseApi.Request request) throws Exception {
        String hex = java.net.URLDecoder.decode(request.body.substring("params=".length()), "UTF-8");
        byte[] encrypted = new byte[hex.length() / 2];
        for (int i = 0; i < encrypted.length; i++) encrypted[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(
                "e82ckenh8dichen8".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "AES"));
        String[] payload = new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8).split("-36cd479b6b5-", -1);
        assertEquals("/api/cloudsearch/pc", payload[0]);
        return j(payload[1]);
    }

    private static JSONObject audio() throws Exception {
        return j("{data:[{id:1,code:200,url:'https://m7.music.126.net/path/secret.mp3',br:128000,expi:120,freeTrialInfo:null}]}");
    }
    @Test public void audioIsIdentityBoundAndRejectsTrialAndSpoofedHosts() throws Exception {
        NeteaseLibraryApi.Audio result = NeteaseLibraryApi.parseAudio(audio(),"1",1000);
        assertEquals(121000,result.expiresAtElapsedMs);
        fails("identity", () -> NeteaseLibraryApi.parseAudio(audio(),"2",1000));
        JSONObject trial=audio();trial.getJSONArray("data").getJSONObject(0).put("freeTrialInfo",new JSONObject());
        fails("permission", () -> NeteaseLibraryApi.parseAudio(trial,"1",1000));
        for (String url : new String[]{"https://m7.music.126.net.evil.test/a", "https://user@music.126.net/a", "file:///a", "https://127.0.0.1/a", "https://m7.music.126.net:8080/a", "https://m7.music.126.net:/a", "https://m7.music.126.net:0443/a"}) {
            JSONObject bad=audio();bad.getJSONArray("data").getJSONObject(0).put("url",url);
            fails("endpoint", () -> NeteaseLibraryApi.parseAudio(bad,"1",1000));
        }
        JSONObject cleartext=audio();cleartext.getJSONArray("data").getJSONObject(0).put("url","http://m7.music.126.net/path");
        assertEquals("https://m7.music.126.net/path",NeteaseLibraryApi.parseAudio(cleartext,"1",0).url);
        JSONObject unavailable=audio();unavailable.getJSONArray("data").getJSONObject(0).put("url",JSONObject.NULL);
        fails("unavailable", () -> NeteaseLibraryApi.parseAudio(unavailable,"1",1000));
    }

    @Test public void authenticatedReadOnlyCallsUseFixedTransportAndHideCredentials() throws Exception {
        final String[] endpoint={null};final String[] cookie={null};
        final JSONObject fixture=j("{code:200,playlist:[],more:false}");
        NeteaseApi api=new NeteaseApi(request -> {
            endpoint[0]=request.endpoint;cookie[0]=request.headers.get("Cookie");
            return new NeteaseApi.Response(fixture,Collections.emptyMap());
        });
        NeteaseApi.Session session=NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'private-secret',__csrf:'csrf'}}"));
        assertTrue(new NeteaseLibraryApi(api).playlists(session,0,30).items.isEmpty());
        assertEquals(NeteaseApi.USER_PLAYLIST,endpoint[0]);
        assertTrue(cookie[0].contains("MUSIC_U=private-secret"));
        fails("endpoint", () -> api.authenticatedCall(NeteaseApi.STATUS,new JSONObject(),session));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL("https://music.163.com/weapi/user/playlist?redirect=secret")));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL("https://evil.test/weapi/user/playlist")));
    }

    @Test public void fullQueueIdsAreNotLimitedToVisibleSongPageOrEagerStreams() throws Exception {
        JSONArray ids = new JSONArray();
        for (int i = 1; i <= 101; i++) ids.put(new JSONObject().put("id", i));
        JSONObject detail = new JSONObject().put("code", 200).put("playlist",
                new JSONObject().put("id", 42).put("trackCount", 101).put("trackIds", ids));
        final int[] calls = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            calls[0]++;
            assertEquals(NeteaseApi.PLAYLIST_DETAIL, request.endpoint);
            return new NeteaseApi.Response(detail, Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        java.util.List<String> snapshot = new NeteaseLibraryApi(api).trackIds(session, "42");
        assertEquals(101, snapshot.size());
        assertEquals("101", snapshot.get(100));
        assertEquals(1, calls[0]);
        try { snapshot.clear(); fail("Must be immutable"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void selectedSongDetailsRejectMissingSongBeforeAudioResolution() throws Exception {
        final int[] calls = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            calls[0]++;
            assertEquals(NeteaseApi.SONG_DETAIL, request.endpoint);
            return new NeteaseApi.Response(jUnchecked("{code:200,songs:[]}"), Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        fails("unavailable", () -> new NeteaseLibraryApi(api).song(session, "1"));
        assertEquals(1, calls[0]);
    }
    private static JSONObject jUnchecked(String text) {
        try { return new JSONObject(text); } catch (org.json.JSONException e) { throw new AssertionError(e); }
    }
}
