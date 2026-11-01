package tech.musxi.neteaseloginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class NeteaseCollectionTest {
    interface Checked { void run() throws Exception; }

    private static JSONObject json(String text) {
        try { return new JSONObject(text); }
        catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
    }

    private static void fails(String code, Checked block) throws Exception {
        try {
            block.run();
            fail("Expected " + code);
        } catch (NeteaseApi.ApiException failure) {
            assertEquals(code, failure.code);
            assertNull(failure.getCause());
        }
    }

    private static NeteaseApi.Session session() throws Exception {
        return NeteaseApi.Session.fromJson(json(
                "{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
    }

    private static JSONObject song(long id, long artistId, long albumId) throws org.json.JSONException {
        JSONArray artists = new JSONArray().put(new JSONObject()
                .put("id", artistId).put("name", "Artist " + artistId));
        JSONObject album = new JSONObject().put("id", albumId).put("name", "Album " + albumId);
        return new JSONObject().put("id", id).put("name", "Song " + id)
                .put("dt", 1000).put("ar", artists).put("al", album);
    }

    private static JSONObject eapiPayload(NeteaseApi.Request request, String uri) throws Exception {
        String hex = java.net.URLDecoder.decode(request.body.substring("params=".length()), "UTF-8");
        byte[] encrypted = new byte[hex.length() / 2];
        for (int i = 0; i < encrypted.length; i++) {
            encrypted[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        }
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(
                "e82ckenh8dichen8".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "AES"));
        String[] payload = new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8)
                .split("-36cd479b6b5-", -1);
        assertEquals(uri, payload[0]);
        return json(payload[1]);
    }

    @Test public void artistTracksUsePinnedRequestAndServerPagination() throws Exception {
        final List<NeteaseApi.Request> requests = new ArrayList<>();
        WebFixture response = new WebFixture();
        response.body = json("{code:200,total:62,more:true,songs:[{" +
                "id:1,name:'Song',dt:1000,ar:[{id:12,name:'Artist'}],al:{id:20,name:'Album'}}]}");
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(response.body, Collections.emptyMap());
        });

        NeteaseLibraryApi.Page<NeteaseLibraryApi.Track> page =
                new NeteaseLibraryApi(api).artistTracks(session(), "12", 30, 1);

        assertEquals(NeteaseApi.ARTIST_TRACKS, requests.get(0).endpoint);
        JSONObject payload = eapiPayload(requests.get(0), "/api/v1/artist/songs");
        assertEquals("12", payload.getString("id"));
        assertEquals("true", payload.getString("private_cloud"));
        assertEquals(1, payload.getInt("work_type"));
        assertEquals("hot", payload.getString("order"));
        assertEquals(30, payload.getInt("offset"));
        assertEquals(1, payload.getInt("limit"));
        assertEquals(31, page.nextOffset);
        assertEquals(62, page.total);
        assertEquals("1", page.items.get(0).id);
        assertEquals(Arrays.asList("12"), page.items.get(0).artistIds);
        assertEquals("20", page.items.get(0).albumId);
    }

    @Test public void artistTracksAcceptFinalPageAndRejectWrongEntity() throws Exception {
        WebFixture response = new WebFixture();
        response.body = json("{code:200,total:30,more:false,songs:[{" +
                "id:30,name:'Song',dt:1000,ar:[{id:12,name:'Artist'}],al:{id:20,name:'Album'}}]}");
        List<NeteaseApi.Request> requests = new ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(response.body, Collections.emptyMap());
        });
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        assertEquals("30", library.artistTracks(session(), "12", 29, 1)
                .items.get(0).id);
        assertEquals(-1, library.artistTracks(session(), "12", 29, 1).nextOffset);
        response.body = json("{code:200,total:1,more:false,songs:[{" +
                "id:1,name:'Song',dt:1000,ar:[{id:12,name:'Artist'}],al:{id:20,name:'Album'}}]}");
        fails("identity", () -> library.artistTracks(session(), "13", 0, 30));
        assertEquals(3, requests.size());
    }

    @Test public void artistPagingRequiresConsistentCountAndUsesThirtyBoundary() throws Exception {
        JSONObject one = json("{code:200,total:31,more:false,songs:[{" +
                "id:1,name:'Song',dt:1000,ar:[{id:12,name:'Artist'}],al:{id:20,name:'Album'}}]}");
        assertEquals(31, NeteaseLibraryApi.parseArtistTracks(one, "12", 30, 1).total);
        fails("incomplete", () -> NeteaseLibraryApi.parseArtistTracks(
                json("{code:200,total:31,more:false,songs:[]}"), "12", 30, 1));
        fails("incomplete", () -> NeteaseLibraryApi.parseArtistTracks(
                json("{code:200,total:31,more:false,songs:[{" +
                        "id:1,name:'Song',dt:1000,ar:[{id:12,name:'Artist'}],al:{id:20,name:'Album'}}]}"),
                "12", 0, 30));
        fails("protocol", () -> NeteaseLibraryApi.parseArtistTracks(
                json("{code:200,total:0,songs:[]}"), "12", 0, 30));
        fails("incomplete", () -> NeteaseLibraryApi.parseArtistTracks(
                json("{code:200,total:1,more:false,songs:[{" +
                        "id:1,name:'Song',dt:1000,ar:[{name:'Artist'}],al:{id:20,name:'Album'}}]}"),
                "12", 0, 30));
        fails("request", () -> NeteaseLibraryApi.parseArtistTracks(one, "12", 0, 101));
    }

    @Test public void albumUsesDynamicWeapiPathAndSlicesTheCompleteTrackList() throws Exception {
        final List<NeteaseApi.Request> requests = new ArrayList<>();
        JSONArray songs = new JSONArray();
        for (int i = 1; i <= 3; i++) songs.put(song(i, 12, 20));
        JSONObject body = json("{code:200,album:{id:20,name:'Album'}}").put("songs", songs);
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(body, Collections.emptyMap());
        });

        NeteaseLibraryApi.Page<NeteaseLibraryApi.Track> page =
                new NeteaseLibraryApi(api).albumTracks(session(), "20", 1, 30);

        assertEquals(NeteaseApi.ALBUM_TRACKS + "20", requests.get(0).endpoint);
        assertTrue(requests.get(0).body.contains("encSecKey="));
        assertEquals(Arrays.asList("2", "3"),
                Arrays.asList(page.items.get(0).id, page.items.get(1).id));
        assertEquals(-1, page.nextOffset);
        assertEquals(3, page.total);
    }

    @Test public void tracksKeepArtistAndAlbumIdsOnlyWhenSafe() throws Exception {
        JSONObject body = json("{code:200,songs:[" +
                "{id:1,name:'Complete',dt:1000,ar:[{id:12,name:'A'},{id:13,name:'B'}],al:{id:20,name:'Album'}}," +
                "{id:2,name:'MissingArtist',dt:1000,ar:[{id:12,name:'A'},{name:'B'}],al:{id:20,name:'Album'}}," +
                "{id:3,name:'MissingAlbum',dt:1000,ar:[{id:12,name:'A'}],al:{name:'Album'}}]}");
        List<NeteaseLibraryApi.Track> tracks =
                NeteaseLibraryApi.parseTracks(body, Arrays.asList("1", "2", "3"));
        assertEquals(Arrays.asList("12", "13"), tracks.get(0).artistIds);
        assertEquals("20", tracks.get(0).albumId);
        assertEquals(Arrays.asList("A", "B"), tracks.get(1).artists);
        assertTrue(tracks.get(1).artistIds.isEmpty());
        assertNull(tracks.get(2).albumId);
        NeteaseLibraryApi.Track legacy = new NeteaseLibraryApi.Track("4", "Legacy", "Album",
                Collections.singletonList("A"), 1, true);
        assertTrue(legacy.artistIds.isEmpty());
        assertNull(legacy.albumId);
        NeteaseLibraryApi.Track legacyWithCover = new NeteaseLibraryApi.Track("5", "Legacy", "Album",
                Collections.singletonList("A"), 1, true, null);
        assertTrue(legacyWithCover.artistIds.isEmpty());
        assertNull(legacyWithCover.albumId);
        assertTrue(NeteaseLibraryApi.parseTracks(
                json("{songs:[{id:1,name:'Bad',dt:1000,ar:[{id:'bad',name:'A'}],al:{id:20,name:'Album'}}]}"),
                Collections.singletonList("1")).get(0).artistIds.isEmpty());
    }

    @Test public void albumIdentityAndBoundsFailBeforeFakeCompletion() throws Exception {
        JSONObject body = json("{code:200,album:{id:20},songs:[{" +
                "id:1,name:'Song',dt:1000,ar:[{id:12,name:'Artist'}],al:{id:21,name:'Wrong'}}]}");
        JSONObject valid = json("{code:200,album:{id:20},songs:[{" +
                "id:1,name:'Song',dt:1000,ar:[{id:12,name:'Artist'}],al:{id:20,name:'Album'}}]}");
        fails("identity", () -> NeteaseLibraryApi.parseAlbumTracks(body, "20", 0, 30));
        fails("identity", () -> NeteaseLibraryApi.parseAlbumTracks(
                json("{code:200,album:{id:21},songs:[]}"), "20", 0, 30));
        fails("incomplete", () -> NeteaseLibraryApi.parseAlbumTracks(
                json("{code:200,album:{id:20},songs:[]}"), "20", 1, 30));
        fails("protocol", () -> NeteaseLibraryApi.parseAlbumTracks(
                json("{code:200,album:{id:20},songs:[null]}"), "20", 0, 30));
        fails("request", () -> NeteaseLibraryApi.parseAlbumTracks(valid, "20", -1, 30));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(NeteaseApi.ALBUM_TRACKS + "../weapi/user/playlist")));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(NeteaseApi.ALBUM_TRACKS + "20/extra")));
    }

    @Test public void entityIdsAndPageLimitsAreValidatedBeforeTransport() throws Exception {
        final List<NeteaseApi.Request> requests = new ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(json("{code:200,total:0,more:false,songs:[]}"),
                    Collections.emptyMap());
        });
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        for (String bad : Arrays.asList(null, "", "0", "01", "-1", "1.5")) {
            fails("protocol", () -> library.artistTracks(session(), bad, 0, 30));
            fails("protocol", () -> library.albumTracks(session(), bad, 0, 30));
        }
        fails("request", () -> library.artistTracks(session(), "12", -1, 30));
        fails("request", () -> library.albumTracks(session(), "20", 0, 101));
        assertTrue(requests.isEmpty());
    }

    private static final class WebFixture {
        JSONObject body;
    }
}
