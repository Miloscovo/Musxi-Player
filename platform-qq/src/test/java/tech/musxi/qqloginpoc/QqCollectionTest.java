package tech.musxi.qqloginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class QqCollectionTest {
    private static final String ARTIST_MID = "003abcDEF01234";
    private static final String OTHER_ARTIST_MID = "004abcDEF01235";
    private static final String ALBUM_MID = "005abcDEF01236";
    private static final String SONG_MID = "009abcDEF01237";
    private static final String MEDIA_MID = "001abcDEF01234001abcDEF01234";

    @Test public void artistRequestsAndWrappedPagesMatchSdkProtocol() throws Exception {
        JSONObject firstParam = QqLibraryApi.artistTracksParam(ARTIST_MID, 0, 30);
        assertEquals("musichall.song_list_server", QqLibraryApi.ARTIST_TRACKS_MODULE);
        assertEquals("GetSingerSongList", QqLibraryApi.ARTIST_TRACKS_METHOD);
        assertEquals(ARTIST_MID, firstParam.getString("singerMid"));
        assertEquals(1, firstParam.getInt("order"));
        assertEquals(30, firstParam.getInt("number"));
        assertEquals(0, firstParam.getInt("begin"));

        QqLibraryApi.Page<QqLibraryApi.Song> first = QqLibraryApi.parseArtistTracks(
            artistPage(31, 30, ARTIST_MID), ARTIST_MID, 0, 30);
        assertEquals(30, first.items.size());
        assertEquals(30, first.nextOffset);
        assertEquals(31, first.total);

        QqLibraryApi.Page<QqLibraryApi.Song> last = QqLibraryApi.parseArtistTracks(
            artistPage(31, 1, ARTIST_MID), ARTIST_MID, 30, 30);
        assertEquals(1, last.items.size());
        assertEquals(-1, last.nextOffset);
        assertEquals(31, last.total);
        assertEquals(30, QqLibraryApi.artistTracksParam(ARTIST_MID, 30, 30).getInt("begin"));
    }

    @Test public void albumRequestsUseMidAndWrappedSongInfoShape() throws Exception {
        JSONObject param = QqLibraryApi.albumTracksParam(ALBUM_MID, 30, 30);
        assertEquals("music.musichallAlbum.AlbumSongList", QqLibraryApi.ALBUM_TRACKS_MODULE);
        assertEquals("GetAlbumSongList", QqLibraryApi.ALBUM_TRACKS_METHOD);
        assertEquals(ALBUM_MID, param.getString("albumMid"));
        assertEquals(30, param.getInt("begin"));
        assertEquals(30, param.getInt("num"));
        assertFalse(param.has("albumId"));

        QqLibraryApi.Page<QqLibraryApi.Song> page = QqLibraryApi.parseAlbumTracks(
            albumPage(31, 30, ALBUM_MID), ALBUM_MID, 0, 30);
        assertEquals(30, page.items.size());
        assertEquals(30, page.nextOffset);
        assertEquals(31, page.total);
        assertEquals(SONG_MID, page.items.get(0).id);
    }

    @Test public void collectionIdentityPaginationAndShapeFailuresAreRejected() throws Exception {
        rejects("identity", () -> QqLibraryApi.parseArtistTracks(
            artistPage(1, 1, OTHER_ARTIST_MID), ARTIST_MID, 0, 30));
        rejects("identity", () -> QqLibraryApi.parseAlbumTracks(
            albumPage(1, 1, "006abcDEF01238"), ALBUM_MID, 0, 30));
        rejects("incomplete", () -> QqLibraryApi.parseArtistTracks(
            artistPage(31, 1, ARTIST_MID), ARTIST_MID, 0, 30));
        rejects("incomplete", () -> QqLibraryApi.parseAlbumTracks(
            albumPage(0, 0, ALBUM_MID), ALBUM_MID, 1, 30));
        rejects("incomplete", () -> QqLibraryApi.parseArtistTracks(
            artistPage(1, 0, ARTIST_MID), ARTIST_MID, 0, 30));
        rejects("limit", () -> QqLibraryApi.parseAlbumTracks(
            albumPage(1, 1, ALBUM_MID), ALBUM_MID, 20_001, 30));

        JSONObject wrongLength = artistPage(31, 1, ARTIST_MID);
        wrongLength.put("songList", new JSONArray().put(new JSONObject().put("songInfo", song(0))));
        rejects("incomplete", () -> QqLibraryApi.parseArtistTracks(
            wrongLength, ARTIST_MID, 0, 30));

        JSONObject missingInfo = artistPage(1, 1, ARTIST_MID);
        missingInfo.put("songList", new JSONArray().put(new JSONObject()));
        rejects("protocol", () -> QqLibraryApi.parseArtistTracks(
            missingInfo, ARTIST_MID, 0, 30));

        JSONArray duplicate = artistPage(2, 2, ARTIST_MID).getJSONArray("songList");
        duplicate.put(1, new JSONObject(duplicate.getJSONObject(0).toString()));
        rejects("identity", () -> QqLibraryApi.parseArtistTracks(
            new JSONObject().put("singerMid", ARTIST_MID).put("totalNum", 2).put("songList", duplicate),
            ARTIST_MID, 0, 30));
    }

    @Test public void songEntityIdsAreSafeAndAlignedWithArtistNames() throws Exception {
        JSONObject row = song(0);
        QqLibraryApi.Song song = QqLibraryApi.song(row);
        assertEquals(2, song.artists.size());
        assertEquals(2, song.artistIds.size());
        assertEquals(ARTIST_MID, song.artistIds.get(0));
        assertEquals(OTHER_ARTIST_MID, song.artistIds.get(1));
        assertEquals(ALBUM_MID, song.albumId);

        JSONObject partial = song(0);
        partial.getJSONArray("singer").getJSONObject(1).put("mid", "");
        assertTrue(QqLibraryApi.song(partial).artistIds.isEmpty());

        JSONObject missing = song(0);
        missing.getJSONArray("singer").getJSONObject(0).remove("mid");
        assertTrue(QqLibraryApi.song(missing).artistIds.isEmpty());

        JSONObject nullId = song(0);
        nullId.getJSONArray("singer").getJSONObject(0).put("mid", JSONObject.NULL);
        assertTrue(QqLibraryApi.song(nullId).artistIds.isEmpty());

        JSONObject noAlbumMid = song(0);
        noAlbumMid.getJSONObject("album").put("mid", "");
        assertNull(QqLibraryApi.song(noAlbumMid).albumId);

        JSONObject removedAlbumMid = song(0);
        removedAlbumMid.getJSONObject("album").remove("mid");
        assertNull(QqLibraryApi.song(removedAlbumMid).albumId);

        JSONObject nullAlbumMid = song(0);
        nullAlbumMid.getJSONObject("album").put("mid", JSONObject.NULL);
        assertNull(QqLibraryApi.song(nullAlbumMid).albumId);

        JSONObject badArtist = song(0);
        badArtist.getJSONArray("singer").getJSONObject(0).put("mid", "bad");
        assertTrue(QqLibraryApi.song(badArtist).artistIds.isEmpty());

        JSONObject badAlbum = song(0);
        badAlbum.getJSONObject("album").put("mid", "bad");
        assertNull(QqLibraryApi.song(badAlbum).albumId);
    }

    @Test public void collectionMidAndPagingBoundariesMatchExistingIdentityStyle() throws Exception {
        for (String invalid : new String[]{null, "", "003abcDEF0123", "003abcDEF012345",
            "003abcDEF0123-", "003abcDEF0123_", "003 abcDEF01234"}) {
            rejects("identity", () -> QqLibraryApi.artistTracksParam(invalid, 0, 30));
            rejects("identity", () -> QqLibraryApi.albumTracksParam(invalid, 0, 30));
        }
        rejects("limit", () -> QqLibraryApi.artistTracksParam(ARTIST_MID, -1, 30));
        rejects("limit", () -> QqLibraryApi.artistTracksParam(ARTIST_MID, 20_001, 30));
        rejects("limit", () -> QqLibraryApi.albumTracksParam(ALBUM_MID, 0, 0));
        rejects("limit", () -> QqLibraryApi.albumTracksParam(ALBUM_MID, 0, 101));
        rejects("identity", () -> QqLibraryApi.song(new JSONObject()));
    }

    private static JSONObject artistPage(int total, int count, String echoed) throws Exception {
        return page(total, count, "singerMid", echoed);
    }

    private static JSONObject albumPage(int total, int count, String echoed) throws Exception {
        return page(total, count, "albumMid", echoed);
    }

    private static JSONObject page(int total, int count, String identity, String echoed) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < count; i++) rows.put(new JSONObject().put("songInfo", song(i)));
        return new JSONObject().put(identity, echoed).put("totalNum", total).put("songList", rows);
    }

    private static JSONObject song(int index) throws Exception {
        return new JSONObject()
            .put("mid", (index == 0 ? SONG_MID : String.format("009abcDEF01%03d", index)))
            .put("name", "Song " + index)
            .put("interval", 123)
            .put("type", 0)
            .put("singer", new JSONArray()
                .put(new JSONObject().put("name", "Artist A").put("mid", ARTIST_MID))
                .put(new JSONObject().put("name", "Artist B").put("mid", OTHER_ARTIST_MID)))
            .put("album", new JSONObject().put("name", "Album").put("mid", ALBUM_MID))
            .put("file", new JSONObject().put("media_mid", MEDIA_MID));
    }

    private interface Checked {
        void run() throws Exception;
    }

    private static void rejects(String code, Checked work) throws Exception {
        try {
            work.run();
            fail("Expected fixed failure");
        } catch (QqMusicApi.ApiException ex) {
            assertEquals(code, ex.code);
        }
    }
}
