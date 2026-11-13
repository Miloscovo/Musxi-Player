package tech.musxi.qqloginpoc;

import org.json.JSONArray;
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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/** Offline protocol tests for the credentialed personal-recommendation read.
 * Fixtures only: no network, no real account, no platform write. */
public class PersonalRecommendationsTest {
    private static final String MID_A = "003abcDEF01234";
    private static final String MID_B = "004abcDEF01235";
    private static final String MEDIA = "001abcDEF01234001abcDEF01234";

    /** Pinned 0.8.1 recommend.get_guess_recommend: the radio parameter block is server-order read
     * of the caller's own recommendations. It never carries a date or a batch cursor. */
    @Test public void requestKeepsPinnedRadioContractWithoutDateOrPaging() throws Exception {
        Fixture fixture = new Fixture(payload(rows(2)));
        List<QqLibraryApi.Song> songs = new QqLibraryApi(fixture.api).personalRecommendations(session());

        assertEquals(1, fixture.requests.size());
        QqMusicApi.Request request = fixture.requests.get(0);
        JSONObject root = new JSONObject(request.body);
        JSONObject req0 = root.getJSONObject("req_0");
        assertEquals("music.radioProxy.MbTrackRadioSvr", req0.getString("module"));
        assertEquals("get_radio_track", req0.getString("method"));
        JSONObject param = req0.getJSONObject("param");
        assertEquals(99, param.getInt("id"));
        assertEquals(30, param.getInt("num"));
        assertEquals(0, param.getInt("from"));
        assertEquals(0, param.getInt("scene"));
        assertEquals(0, param.getJSONArray("song_ids").length());
        assertEquals(5, param.length());
        assertFalse(param.has("page"));
        assertFalse(param.has("date"));
        // The read is always bound to the caller, never an anonymous feed.
        assertEquals("12345", root.getJSONObject("comm").getString("uin"));
        assertEquals("fixture-key", root.getJSONObject("comm").getString("authst"));
        assertTrue(request.headers.get("Cookie").contains("qm_keyst=fixture-key"));
        assertEquals(2, songs.size());
    }

    @Test public void serverOrderAndFieldsArePreservedAndListIsReadOnly() throws Exception {
        List<QqLibraryApi.Song> songs =
            new QqLibraryApi(new Fixture(payload(rows(2))).api).personalRecommendations(session());
        assertEquals(MID_A, songs.get(0).id);
        assertEquals(MID_B, songs.get(1).id);
        assertEquals("Song A", songs.get(0).name);
        assertEquals(Collections.singletonList("Artist A"), songs.get(0).artists);
        assertEquals("Album A", songs.get(0).album);
        assertEquals(123_000L, songs.get(0).durationMs);
        try { songs.add(songs.get(0)); fail("Expected immutable list"); }
        catch (UnsupportedOperationException expected) { /* read-only snapshot */ }
    }

    /** An empty result is a valid read; a missing tracks structure is a protocol failure and must
     * not be reported as an empty success. */
    @Test public void emptyTracksIsSuccessButMissingStructureFails() throws Exception {
        assertTrue(new QqLibraryApi(new Fixture(payload(new JSONArray())).api)
            .personalRecommendations(session()).isEmpty());

        rejects("protocol", () -> new QqLibraryApi(new Fixture(new JSONObject()).api)
            .personalRecommendations(session()));
        rejects("protocol", () -> new QqLibraryApi(new Fixture(new JSONObject().put("tracks", "x")).api)
            .personalRecommendations(session()));
        rejects("protocol", () -> new QqLibraryApi(new Fixture(new JSONObject()
            .put("tracks", new JSONArray().put(JSONObject.NULL))).api).personalRecommendations(session()));
    }

    @Test public void boundedCountDuplicateAndBrokenRowsAreRejected() throws Exception {
        rejects("limit", () -> new QqLibraryApi(new Fixture(payload(rows(31))).api)
            .personalRecommendations(session()));

        JSONArray duplicate = rows(2);
        duplicate.getJSONObject(1).put("mid", MID_A);
        rejects("identity", () -> new QqLibraryApi(new Fixture(payload(duplicate)).api)
            .personalRecommendations(session()));

        JSONObject broken = rows(1).getJSONObject(0);
        broken.put("mid", "not-a-mid");
        rejects("identity", () -> new QqLibraryApi(new Fixture(payload(new JSONArray().put(broken))).api)
            .personalRecommendations(session()));

        rejects("permission", () -> new QqLibraryApi(new Fixture(
            new JSONObject().put("retCode", 1000).put("tracks", rows(1))).api)
            .personalRecommendations(session()));
    }

    /** Expired credentials must fail closed rather than return a partial or empty list. */
    @Test public void accountGuardAndTransportFailureNeverLookLikeEmptySuccess() throws Exception {
        rejects("account_changed", () -> new QqLibraryApi(new Fixture(payload(rows(1))).api, () -> false)
            .personalRecommendations(session()));
        rejects("network", () -> new QqLibraryApi(new Fixture() {
            @Override public QqMusicApi.Response execute(QqMusicApi.Request request) throws IOException {
                throw new IOException("offline");
            }
        }.api).personalRecommendations(session()));
    }

    private static JSONObject payload(JSONArray tracks) throws Exception {
        return new JSONObject().put("retCode", 0).put("tracks", tracks);
    }

    private static JSONArray rows(int count) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 0; i < count; i++) {
            rows.put(new JSONObject().put("mid", i == 0 ? MID_A : i == 1 ? MID_B : String.format("001%011d", i + 1))
                .put("name", i == 0 ? "Song A" : "Song " + i)
                .put("interval", 123).put("type", 0)
                .put("singer", new JSONArray().put(new JSONObject().put("name", i == 0 ? "Artist A" : "Artist " + i)))
                .put("album", new JSONObject().put("name", i == 0 ? "Album A" : "Album " + i))
                .put("file", new JSONObject().put("media_mid", MEDIA)));
        }
        return rows;
    }

    private static QqMusicApi.Session session() throws Exception {
        return QqMusicApi.Session.fromJson(new JSONObject(
            "{\"credential\":{\"musicid\":12345,\"musickey\":\"fixture-key\",\"encryptUin\":\"fixture-euin\",\"loginType\":2},\"nickname\":\"Fixture\"}"));
    }

    private interface Checked { void run() throws Exception; }

    private static void rejects(String code, Checked work) throws Exception {
        try { work.run(); fail("Expected fixed failure " + code); }
        catch (QqMusicApi.ApiException ex) {
            assertEquals(code, ex.code);
            assertFalse(ex.getMessage().contains("fixture-key"));
        }
    }

    private static class Fixture implements QqMusicApi.Transport {
        final ArrayDeque<JSONObject> data = new ArrayDeque<>();
        final List<QqMusicApi.Request> requests = new ArrayList<>();
        final QqMusicApi api = new QqMusicApi(this);
        Fixture(JSONObject... payloads) { Collections.addAll(data, payloads); }
        @Override public QqMusicApi.Response execute(QqMusicApi.Request request) throws IOException {
            requests.add(request);
            try {
                JSONObject body = new JSONObject().put("code", 0)
                    .put("req_0", new JSONObject().put("code", 0).put("data", data.remove()));
                return new QqMusicApi.Response(200, body.toString().getBytes(StandardCharsets.UTF_8),
                    Collections.emptyMap());
            } catch (Exception invalid) { throw new IOException(); }
        }
    }
}
