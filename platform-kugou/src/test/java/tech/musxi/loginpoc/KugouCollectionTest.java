package tech.musxi.loginpoc;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class KugouCollectionTest {
    @Test public void searchUsesExplicitSingersWithoutSplittingDisplayNames() throws Exception {
        JSONObject root=json("{status:1,data:{lists:[{FileHash:'"+ARTIST_HASH+"',AlbumID:1,MixSongID:2,"+
            "OriSongName:'Track',SingerName:'Joined display',Duration:120,Singers:[{id:10,name:'First'},{id:20,name:'Second'}]}]}}");
        KugouLibraryApi.Song song=KugouLibraryApi.parseSearch(root).get(0);
        assertEquals(List.of("First","Second"),song.artists);
        assertEquals(List.of("10","20"),song.artistIds);
        root.getJSONObject("data").getJSONArray("lists").getJSONObject(0).getJSONArray("Singers").getJSONObject(1).put("id",0);
        assertTrue(KugouLibraryApi.parseSearch(root).get(0).artistIds.isEmpty());
        root.getJSONObject("data").getJSONArray("lists").getJSONObject(0).remove("Singers");
        assertEquals(List.of("Joined display"),KugouLibraryApi.parseSearch(root).get(0).artists);
    }
    private static final String MID = "b".repeat(32);
    private static final String ARTIST_HASH = "a".repeat(32);
    private static final String SECOND_ARTIST_HASH = "c".repeat(32);
    private static final String ALBUM_HASH_1 = "d".repeat(32);
    private static final String ALBUM_HASH_2 = "e".repeat(32);

    @Test
    public void artistAndAlbumShapesPreserveMillisecondsAndAlignedArtistIds() throws Exception {
        KugouLibraryApi.Page<KugouLibraryApi.Track> artists = KugouLibraryApi.parseCollectionTracks(
            artistPage(), "7001", 0, 30, true);

        assertEquals(2, artists.items.size());
        assertEquals(2, artists.total);
        assertEquals(-1, artists.nextOffset);
        KugouLibraryApi.Track first = artists.items.get(0);
        assertEquals(ARTIST_HASH + "_101_201", first.id);
        assertEquals("Artist title", first.name);
        assertEquals("", first.album);
        assertEquals(List.of("Singer A"), first.artists);
        assertTrue(first.artistIds.isEmpty());
        assertEquals(61_001L, first.durationMs);
        assertNull(first.coverUrl);
        assertEquals(SECOND_ARTIST_HASH + "_102_202", artists.items.get(1).id);
        assertEquals(62_002L, artists.items.get(1).durationMs);

        KugouLibraryApi.Page<KugouLibraryApi.Track> albums = KugouLibraryApi.parseCollectionTracks(
            albumPage(), "8001", 0, 30, false);

        assertEquals(2, albums.items.size());
        assertEquals(2, albums.total);
        assertEquals(-1, albums.nextOffset);
        KugouLibraryApi.Track lead = albums.items.get(0);
        assertEquals(ALBUM_HASH_1 + "_8001_3001", lead.id);
        assertEquals("8001", lead.albumId);
        assertEquals("Album title", lead.album);
        assertEquals("https://imgessl.kugou.com/album.jpg", lead.coverUrl);
        assertEquals(List.of("Lead Artist", "Guest Artist"), lead.artists);
        assertEquals(List.of("901", "902"), lead.artistIds);
        assertEquals(lead.artists.size(), lead.artistIds.size());
        assertEquals(245_678L, lead.durationMs);
        assertEquals(List.of("Third Artist"), albums.items.get(1).artists);
        assertEquals(List.of("903"), albums.items.get(1).artistIds);
        assertEquals(3_600_999L, albums.items.get(1).durationMs);
    }

    @Test
    public void albumTracksRejectAnyRowFromAnotherAlbum() throws Exception {
        JSONObject mismatch = json("{status:1,data:{total:1,songs:[{" +
            "base:{album_id:8002,album_audio_id:3001,audio_name:'Wrong album',author_name:'Artist'}," +
            "audio_info:{hash:'" + ALBUM_HASH_1 + "',duration:245678}," +
            "authors:[{author_id:901,author_name:'Lead Artist'}]," +
            "album_info:{album_name:'Another album',cover:'https://imgessl.kugou.com/wrong.jpg'}}]}}");

        rejects("identity", () -> KugouLibraryApi.parseCollectionTracks(
            mismatch, "8001", 0, 30, false));
    }

    @Test
    public void missingHashIsRejectedForBothCollectionShapes() throws Exception {
        JSONObject missingArtistHash = json("{status:1,total:1,data:[{" +
            "album_id:101,album_audio_id:201,audio_name:'Artist title'," +
            "author_name:'Singer A',timelength:61001}]}");
        JSONObject missingAlbumHash = json("{status:1,data:{total:1,songs:[{" +
            "base:{album_id:8001,album_audio_id:3001,audio_name:'Album title',author_name:'Artist'}," +
            "audio_info:{duration:245678}," +
            "authors:[{author_id:901,author_name:'Lead Artist'}]," +
            "album_info:{album_name:'Album title'}}]}}");

        rejects("identity", () -> KugouLibraryApi.parseCollectionTracks(
            missingArtistHash, "7001", 0, 30, true));
        rejects("identity", () -> KugouLibraryApi.parseCollectionTracks(
            missingAlbumHash, "8001", 0, 30, false));
    }

    @Test
    public void shortPagesCannotHideMissingCollectionRows() throws Exception {
        JSONObject shortArtistPage = json("{status:1,total:31,data:[{" +
            "hash:'" + ARTIST_HASH + "',album_id:101,album_audio_id:201," +
            "audio_name:'Artist title',author_name:'Singer A',timelength:61001}]}");
        JSONObject shortAlbumPage = json("{status:1,data:{total:31,songs:[{" +
            "base:{album_id:8001,album_audio_id:3001,audio_name:'Album title',author_name:'Artist'}," +
            "audio_info:{hash:'" + ALBUM_HASH_1 + "',duration:245678}," +
            "authors:[{author_id:901,author_name:'Lead Artist'}]," +
            "album_info:{album_name:'Album title'}}]}}");

        rejects("incomplete", () -> KugouLibraryApi.parseCollectionTracks(
            shortArtistPage, "7001", 0, 30, true));
        rejects("incomplete", () -> KugouLibraryApi.parseCollectionTracks(
            shortAlbumPage, "8001", 0, 30, false));
    }

    @Test
    public void collectionRequestsUseFixedEndpointsBodiesHeadersAndSignatures() throws Exception {
        List<KugouApi.Request> requests = new ArrayList<>();
        KugouApi api = new KugouApi(new KugouApi.Device(MID), request -> {
            requests.add(request);
            if (request.endpoint.endsWith("/audio_group/author")) {
                return json("{status:1,total:1,data:[{" +
                    "hash:'" + ARTIST_HASH + "',album_id:101,album_audio_id:201," +
                    "audio_name:'Artist title',author_name:'Singer A',timelength:61001}]}");
            }
            return albumPage();
        });
        KugouLibraryApi library = new KugouLibraryApi(api);
        KugouApi.Session session = new KugouApi.Session("fixture-token", "123", "Fixture");

        library.artistTracks(session, "7001", 0, 30);
        library.albumTracks(session, "8001", 0, 30);

        assertEquals(2, requests.size());
        KugouApi.Request artist = requests.get(0);
        KugouApi.Request album = requests.get(1);
        assertEquals("https://openapi.kugou.com/kmr/v1/audio_group/author", artist.endpoint);
        assertEquals("https://gateway.kugou.com/v1/album_audio/lite", album.endpoint);
        assertEquals("openapi.kugou.com", artist.headers.get("x-router"));
        assertEquals("openapi.kugou.com", album.headers.get("x-router"));
        assertEquals("220", artist.headers.get("kg-tid"));
        assertEquals("255", album.headers.get("kg-tid"));
        for (KugouApi.Request request : requests) {
            assertEquals(MID, request.headers.get("mid"));
            assertEquals("Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi",
                request.headers.get("User-Agent"));
            assertEquals(request.params.get("clienttime"), request.headers.get("clienttime"));
            assertEquals("123", request.params.get("userid"));
            assertEquals("fixture-token", request.params.get("token"));
            assertSigned(request);
        }

        JSONObject artistBody = new JSONObject(artist.body);
        assertEquals("7001", artistBody.getString("author_id"));
        assertEquals(1, artistBody.getInt("page"));
        assertEquals(30, artistBody.getInt("pagesize"));
        assertEquals(3116, artistBody.getInt("appid"));
        assertEquals(11440, artistBody.getInt("clientver"));
        assertEquals(MID, artistBody.getString("mid"));
        assertEquals(artist.params.get("clienttime"), artistBody.get("clienttime").toString());
        assertEquals(1, artistBody.getInt("sort"));
        assertEquals("all", artistBody.getString("area_code"));
        assertTrue(artistBody.has("key"));

        JSONObject albumBody = new JSONObject(album.body);
        assertEquals("8001", albumBody.getString("album_id"));
        assertEquals(1, albumBody.getInt("page"));
        assertEquals(30, albumBody.getInt("pagesize"));
        assertEquals("", albumBody.getString("is_buy"));
        assertFalse(albumBody.has("author_id"));
    }

    @Test
    public void collectionInputsRequireNumericIdsAndLimitAtMostThirty() throws Exception {
        int[] calls = {0};
        KugouLibraryApi library = new KugouLibraryApi(new KugouApi(
            new KugouApi.Device(MID), request -> {
                calls[0]++;
                return artistPage();
            }));
        KugouApi.Session session = new KugouApi.Session("fixture-token", "123", "Fixture");

        rejects("request", () -> library.artistTracks(session, "artist-7001", 0, 30));
        rejects("request", () -> library.albumTracks(session, "8001x", 0, 30));
        rejects("request", () -> library.artistTracks(session, "7001", 0, 31));
        assertEquals(0, calls[0]);
    }

    private static JSONObject artistPage() {
        return json("{status:1,total:2,data:[" +
            "{hash:'" + ARTIST_HASH + "',album_id:101,album_audio_id:201," +
            "audio_name:'Artist title',author_name:'Singer A',timelength:61001}," +
            "{hash:'" + SECOND_ARTIST_HASH + "',album_id:102,album_audio_id:202," +
            "audio_name:'Second title',author_name:'Singer A',timelength:62002}]}");
    }

    private static JSONObject albumPage() {
        return json("{status:1,data:{total:2,songs:[" +
            "{base:{album_id:8001,album_audio_id:3001,audio_name:'Album title',author_name:'Artist'}," +
            "audio_info:{hash:'" + ALBUM_HASH_1 + "',duration:245678}," +
            "authors:[{author_id:901,author_name:'Lead Artist'},{author_id:'902',author_name:'Guest Artist'}]," +
            "album_info:{album_name:'Album title',cover:'http://imgessl.kugou.com/album.jpg'}}," +
            "{base:{album_id:8001,album_audio_id:3002,audio_name:'Second album title',author_name:'Artist'}," +
            "audio_info:{hash:'" + ALBUM_HASH_2 + "',duration:3600999}," +
            "authors:[{author_id:903,author_name:'Third Artist'}]," +
            "album_info:{album_name:'Album title',cover:'http://imgessl.kugou.com/album2.jpg'}}]}}");
    }

    private static JSONObject json(String source) {
        try { return new JSONObject(source); }
        catch (org.json.JSONException invalid) { throw new AssertionError(invalid); }
    }

    private static void assertSigned(KugouApi.Request request) {
        Map<String, String> unsigned = new LinkedHashMap<>(request.params);
        String signature = unsigned.remove("signature");
        assertEquals(KugouApi.androidSignature(unsigned, request.body), signature);
        assertFalse(unsigned.containsKey("signature"));
    }

    private static void rejects(String code, ThrowingRunnable action) throws Exception {
        try {
            action.run();
            fail("Expected fixed failure");
        } catch (KugouApi.ApiException failure) {
            assertEquals(code, failure.code);
        }
    }

    private interface ThrowingRunnable {
        void run() throws Exception;
    }
}
