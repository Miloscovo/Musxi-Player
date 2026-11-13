package tech.musxi.neteaseloginpoc;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.Assert.*;

/**
 * Fake-transport tests for the pinned module/playlist_delete.js adaptation
 * (NeteaseCloudMusicApiEnhanced/api-enhanced 2aab9957dfd5231e5b192aecdb177f93ace4c92e).
 * They prove the request shape, permission guarding and failure containment; they do not
 * prove that the live platform accepts a deletion.
 */
public class NeteasePlaylistDeletionTest {
    @Test public void pinnedDeleteIsOneWeapiWriteAfterTheListingAndNeedsNoCheckToken() throws Exception {
        List<NeteaseApi.Request> requests = new ArrayList<>();
        final int[] call = {0};
        // The pinned module passes createOption(query, 'weapi') with no checkToken, so a
        // fresh SDK-v2 provider must never be consulted for this surface.
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            call[0]++;
            if (call[0] == 1) return response(listing(false, liked(7), owned(42, 0)));
            return response("{code:200}");
        }, () -> {
            fail("Playlist deletion must not request a fresh verification token");
            return "unused";
        });
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        library.deletePlaylist(session(), "42");

        // Exactly one listing read and one write; no post-write read-back at this layer.
        assertEquals(2, requests.size());
        assertEquals(NeteaseApi.USER_PLAYLIST, requests.get(0).endpoint);
        assertEquals(NeteaseApi.PLAYLIST_DELETE, requests.get(1).endpoint);
        assertEquals("https://music.163.com/weapi/playlist/remove", requests.get(1).endpoint);
        NeteaseApi.requireEndpoint(new URL(requests.get(1).endpoint));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(NeteaseApi.PLAYLIST_DELETE + "?id=42")));

        // The write uses the existing WEAPI profile, not the EAPI host.
        assertTrue(requests.get(1).body.contains("encSecKey="));
        assertTrue(requests.get(1).body.contains("params="));
        assertFalse(requests.get(1).endpoint.contains("interfacepc.music.163.com"));

        // WEAPI encrypts a per-request secret, so the exact fields are asserted on the builder.
        JSONObject payload = NeteaseLibraryApi.deletePlaylistPayload("42");
        assertEquals("[42]", payload.getString("ids"));
        assertEquals(1, payload.length());
        assertFalse(payload.has("checkToken"));
        fails("protocol", () -> NeteaseLibraryApi.deletePlaylistPayload("0"));
        fails("protocol", () -> NeteaseLibraryApi.deletePlaylistPayload("abc"));
        fails("protocol", () -> NeteaseLibraryApi.deletePlaylistPayload(null));
    }

    @Test public void deletablePlaylistReadsOneCompleteListingAndNeverWrites() throws Exception {
        List<NeteaseApi.Request> requests = new ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return response(listing(false, owned(42, 0), liked(7)));
        });
        NeteaseLibraryApi.Playlist target = new NeteaseLibraryApi(api).deletablePlaylist(session(), "42");
        assertNotNull(target);
        assertEquals("42", target.id);
        assertTrue(target.writable);
        assertFalse(target.liked);
        assertEquals(NeteaseLibraryApi.Playlist.Kind.CREATED, target.kind);
        assertEquals(1, requests.size());
        assertEquals(NeteaseApi.USER_PLAYLIST, requests.get(0).endpoint);
    }

    @Test public void likedForeignAndSystemListsAreRejectedBeforeAnyWrite() throws Exception {
        String[][] cases = {
            // foreign owner, kept in the full listing rather than filtered out as unwritable
            {"permission", "{id:42,name:'External',specialType:0,trackCount:0,creator:{userId:8}}"},
            // the account's own liked-songs list
            {"permission", "{id:42,name:'Liked',specialType:5,trackCount:0,creator:{userId:7}}"},
            // any other system-managed list marker
            {"permission", "{id:42,name:'System',specialType:1,trackCount:0,creator:{userId:7}}"},
            // an owned row whose creator cannot be read
            {"incomplete", "{id:42,name:'Unknown',specialType:0,trackCount:0}"},
            // an owned row with no system marker cannot be proved a normal playlist
            {"incomplete", "{id:42,name:'NoMarker',trackCount:0,creator:{userId:7}}"},
            // negative or oversized markers are protocol errors, never "plain"
            {"protocol", "{id:42,name:'Bad',specialType:-1,trackCount:0,creator:{userId:7}}"},
        };
        for (String[] row : cases) {
            List<NeteaseApi.Request> requests = new ArrayList<>();
            NeteaseApi api = new NeteaseApi(request -> {
                requests.add(request);
                return response(listing(false, liked(7), row[1]));
            });
            fails(row[0], () -> new NeteaseLibraryApi(api).deletablePlaylist(session(), "42"));
            fails(row[0], () -> new NeteaseLibraryApi(api).deletePlaylist(session(), "42"));
            // Listing only: a rejected target never reaches the transport as a write.
            assertEquals(2, requests.size());
            for (NeteaseApi.Request request : requests)
                assertEquals(NeteaseApi.USER_PLAYLIST, request.endpoint);
        }
    }

    @Test public void listingWithoutTheLikedSongsRowIsIncompleteNotAnAbsentPlaylist() throws Exception {
        List<NeteaseApi.Request> requests = new ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return response(listing(false, owned(42, 0)));
        });
        fails("incomplete", () -> new NeteaseLibraryApi(api).deletablePlaylist(session(), "42"));
        fails("incomplete", () -> new NeteaseLibraryApi(api).deletePlaylist(session(), "42"));
        assertEquals(2, requests.size());
    }

    @Test public void targetOnALaterPageIsFoundAndBrokenPagingIsRejected() throws Exception {
        List<NeteaseApi.Request> requests = new ArrayList<>();
        final int[] call = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            call[0]++;
            if (call[0] == 1) return response(listing(true, liked(7), owned(41, 0)));
            return response(listing(false, owned(42, 0)));
        });
        assertEquals("42", new NeteaseLibraryApi(api).deletablePlaylist(session(), "42").id);
        assertEquals(2, requests.size());
        for (NeteaseApi.Request request : requests) assertEquals(NeteaseApi.USER_PLAYLIST, request.endpoint);

        // Non-terminal page with no rows: progress is impossible, so this is not a short account.
        NeteaseApi stuck = new NeteaseApi(request -> response(listing(true, liked(7))));
        fails("protocol", () -> new NeteaseLibraryApi(stuck).deletablePlaylist(session(), "42"));

        // The same playlist id twice means the listing is unusable as an existence witness.
        final int[] duplicate = {0};
        NeteaseApi repeated = new NeteaseApi(request -> {
            duplicate[0]++;
            return response(duplicate[0] == 1
                    ? listing(true, liked(7), owned(42, 0))
                    : listing(false, owned(42, 0)));
        });
        fails("protocol", () -> new NeteaseLibraryApi(repeated).deletablePlaylist(session(), "42"));

        // A non-boolean continuation flag is a protocol error, not "complete".
        NeteaseApi malformed = new NeteaseApi(request -> response(
                "{code:200,playlist:[" + liked(7) + "," + owned(42, 0) + "],more:'false'}"));
        fails("protocol", () -> new NeteaseLibraryApi(malformed).deletablePlaylist(session(), "42"));

        // Rows that are not objects are rejected instead of being skipped silently.
        NeteaseApi notObject = new NeteaseApi(request -> response(
                "{code:200,playlist:[" + liked(7) + ",5],more:false}"));
        fails("protocol", () -> new NeteaseLibraryApi(notObject).deletablePlaylist(session(), "42"));
    }

    @Test public void genuinelyAbsentPlaylistReturnsNullAndTheWriteIsSkipped() throws Exception {
        List<NeteaseApi.Request> requests = new ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return response(listing(false, liked(7), owned(41, 0)));
        });
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        assertNull(library.deletablePlaylist(session(), "42"));
        library.deletePlaylist(session(), "42");
        // Only the two verifications ran; an absent target produces no delete request at all.
        assertEquals(2, requests.size());
        for (NeteaseApi.Request request : requests) assertEquals(NeteaseApi.USER_PLAYLIST, request.endpoint);
    }

    @Test public void accountChangeStopsBeforeOrAfterTheListingWithoutAnyWrite() throws Exception {
        // Already-replaced account: the transport is never reached.
        List<NeteaseApi.Request> untouched = new ArrayList<>();
        NeteaseApi idle = new NeteaseApi(request -> {
            untouched.add(request);
            return response(listing(false, liked(7), owned(42, 0)));
        });
        fails("account_changed", () -> new NeteaseLibraryApi(idle, () -> false)
                .deletablePlaylist(session(), "42"));
        fails("account_changed", () -> new NeteaseLibraryApi(idle, () -> false)
                .deletePlaylist(session(), "42"));
        assertTrue(untouched.isEmpty());

        // The account switches while the listing response is being consumed.
        List<NeteaseApi.Request> switched = new ArrayList<>();
        AtomicBoolean current = new AtomicBoolean(true);
        NeteaseApi changing = new NeteaseApi(request -> {
            switched.add(request);
            current.set(false);
            return response(listing(false, liked(7), owned(42, 0)));
        });
        fails("account_changed", () -> new NeteaseLibraryApi(changing, current::get)
                .deletePlaylist(session(), "42"));
        assertEquals(1, switched.size());
        assertEquals(NeteaseApi.USER_PLAYLIST, switched.get(0).endpoint);
    }

    @Test public void lostWriteResponseIsNotRetried() throws Exception {
        List<NeteaseApi.Request> requests = new ArrayList<>();
        final int[] call = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            call[0]++;
            if (call[0] == 1) return response(listing(false, liked(7), owned(42, 0)));
            // The server may already have deleted the playlist; the caller reconciles.
            throw new IOException("connection lost");
        });
        fails("network", () -> new NeteaseLibraryApi(api).deletePlaylist(session(), "42"));
        assertEquals(2, requests.size());
        assertEquals(NeteaseApi.PLAYLIST_DELETE, requests.get(1).endpoint);
    }

    @Test public void writeRejectionIsClassifiedWithoutRetry() throws Exception {
        for (String[] expected : new String[][] {{"-462", "risk"}, {"301", "expired"}, {"429", "rate"},
                {"500", "platform"}}) {
            AtomicInteger writes = new AtomicInteger();
            final int[] call = {0};
            NeteaseApi api = new NeteaseApi(request -> {
                call[0]++;
                if (call[0] == 1) return response(listing(false, liked(7), owned(42, 0)));
                writes.incrementAndGet();
                return response("{code:" + expected[0] + "}");
            });
            fails(expected[1], () -> new NeteaseLibraryApi(api).deletePlaylist(session(), "42"));
            assertEquals(1, writes.get());
        }
    }

    @Test public void requestBoundsAndMissingSessionAreRejectedBeforeTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NeteaseApi api = new NeteaseApi(request -> {
            calls.incrementAndGet();
            return response(listing(false, liked(7), owned(42, 0)));
        });
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        fails("protocol", () -> library.deletablePlaylist(session(), "0"));
        fails("protocol", () -> library.deletablePlaylist(session(), "42abc"));
        fails("protocol", () -> library.deletePlaylist(session(), "0"));
        fails("credentials", () -> library.deletablePlaylist(null, "42"));
        fails("credentials", () -> library.deletePlaylist(null, "42"));
        assertEquals(0, calls.get());
    }

    @Test public void renameAlsoRefusesEverySystemMarkerAndMissingNormalMarker() throws Exception {
        for (int marker : new int[]{1, 5, 10}) {
            AtomicInteger calls = new AtomicInteger();
            NeteaseApi api = new NeteaseApi(request -> {
                calls.incrementAndGet();
                return response("{code:200,playlist:" + owned(42, marker) + "}");
            });
            fails("permission", () -> new NeteaseLibraryApi(api).renamePlaylist(session(), "42", "New"));
            assertEquals(1, calls.get());
        }
        AtomicInteger calls = new AtomicInteger();
        NeteaseApi api = new NeteaseApi(request -> {
            calls.incrementAndGet();
            return response("{code:200,playlist:{id:42,creator:{userId:7}}}");
        });
        fails("protocol", () -> new NeteaseLibraryApi(api).renamePlaylist(session(), "42", "New"));
        assertEquals(1, calls.get());
    }

    private static String owned(int id, int specialType) {
        return "{id:" + id + ",name:'Mine" + id + "',specialType:" + specialType
                + ",trackCount:3,creator:{userId:7}}";
    }
    private static String liked(int owner) {
        return "{id:9,name:'Liked',specialType:5,trackCount:1,creator:{userId:" + owner + "}}";
    }
    private static String listing(boolean more, String... rows) {
        StringBuilder out = new StringBuilder("{code:200,playlist:[");
        for (int i = 0; i < rows.length; i++) {
            if (i > 0) out.append(',');
            out.append(rows[i]);
        }
        return out.append("],more:").append(more).append('}').toString();
    }
    private static NeteaseApi.Session session() throws Exception {
        return NeteaseApi.Session.fromJson(new JSONObject(
                "{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
    }
    private static NeteaseApi.Response response(String json) {
        try {
            return new NeteaseApi.Response(new JSONObject(json), Collections.emptyMap());
        } catch (org.json.JSONException invalid) {
            throw new AssertionError(invalid);
        }
    }
    private interface Action { void run() throws Exception; }
    private static void fails(String code, Action action) throws Exception {
        NeteaseApi.ApiException failure = assertThrows(NeteaseApi.ApiException.class, action::run);
        assertEquals(code, failure.code);
    }
}
