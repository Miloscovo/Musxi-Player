package tech.musxi.neteaseloginpoc;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class NeteasePlaylistSubscriptionTest {
    private static final String TOKEN = "fixture/fresh+token_123456789012=";

    @Test public void productionDefaultBlocksBothWriteDirectionsBeforeTransport() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        NeteaseApi api = new NeteaseApi(request -> {
            calls.incrementAndGet();
            throw new AssertionError("Missing v2 provider must prevent playlist transport");
        });
        for (boolean followed : new boolean[] {true, false})
            fails("verification_required", () -> api.setPlaylistFollowed(session(), "42", followed, () -> true));
        assertEquals(0, calls.get());
    }

    @Test public void productionDefaultStillReadsStateAndSkipsUnchangedIntent() throws Exception {
        List<NeteaseApi.Request> requests = new ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return response(detail(8, false, 0));
        });
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        assertFalse(library.playlistSubscription(session(), "42").followed);
        library.setPlaylistFollowed(session(), "42", false);
        assertEquals(2, requests.size());
        for (NeteaseApi.Request request : requests)
            assertEquals(NeteaseApi.PLAYLIST_DETAIL, request.endpoint);
        fails("verification_required", () -> library.setPlaylistFollowed(session(), "42", true));
        assertEquals(3, requests.size());
        assertEquals(NeteaseApi.PLAYLIST_DETAIL, requests.get(2).endpoint);
    }

    @Test public void failedV2ProviderDoesNotFallbackOrWrite() throws Exception {
        AtomicInteger calls = new AtomicInteger(), tokens = new AtomicInteger();
        NeteaseApi api = new NeteaseApi(request -> {
            calls.incrementAndGet();
            return response("{code:200}");
        }, () -> {
            tokens.incrementAndGet();
            throw new NeteaseApi.ApiException("verification_required", "Platform verification unavailable");
        });
        fails("verification_required", () -> api.setPlaylistFollowed(session(), "42", true, () -> true));
        assertEquals(1, tokens.get());
        assertEquals(0, calls.get());
    }

    @Test public void subscriptionUsesFreshTokenInEncryptedHeaderAndCookieOnly() throws Exception {
        List<NeteaseApi.Request> requests = new ArrayList<>();
        AtomicInteger tokens = new AtomicInteger();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return response("{code:200}");
        }, () -> { tokens.incrementAndGet(); return TOKEN; });
        api.setPlaylistFollowed(session(), "42", true, () -> true);
        api.setPlaylistFollowed(session(), "42", false, () -> true);
        assertEquals(2, tokens.get());
        assertEquals(2, requests.size());
        for (int i = 0; i < requests.size(); i++) {
            NeteaseApi.Request request = requests.get(i);
            boolean followed = i == 0;
            assertEquals(followed ? NeteaseApi.PLAYLIST_SUBSCRIBE : NeteaseApi.PLAYLIST_UNSUBSCRIBE, request.endpoint);
            NeteaseApi.requireEndpoint(new URL(request.endpoint));
            JSONObject data = payload(request, followed ? "/api/playlist/subscribe" : "/api/playlist/unsubscribe");
            assertEquals("42", data.getString("id"));
            assertEquals(TOKEN, data.getJSONObject("header").getString("X-antiCheatToken"));
            assertFalse(request.headers.containsKey("X-antiCheatToken"));
            assertTrue(URLDecoder.decode(request.headers.get("Cookie"), "UTF-8")
                .contains("X-antiCheatToken=" + TOKEN));
            if (followed) {
                assertTrue(data.getString("checkToken").matches("[a-f0-9]{100,256}"));
                assertNotEquals(TOKEN, data.getString("checkToken"));
            } else assertFalse(data.has("checkToken"));
        }
        // Generic authenticatedCall cannot bypass the fresh-token-only write surface.
        fails("endpoint", () -> api.authenticatedCall(NeteaseApi.PLAYLIST_SUBSCRIBE, new JSONObject(), session()));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(NeteaseApi.PLAYLIST_SUBSCRIBE + "?id=42")));
        assertEquals(2, requests.size());
    }

    @Test public void idempotentStateAndOwnerPermissionAvoidTokenFetchAndMutation() throws Exception {
        AtomicInteger tokens = new AtomicInteger();
        List<NeteaseApi.Request> requests = new ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return response(detail(8, true, 0));
        }, () -> { tokens.incrementAndGet(); return TOKEN; });
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        assertTrue(library.playlistSubscription(session(), "42").followed);
        library.setPlaylistFollowed(session(), "42", true);
        assertEquals(0, tokens.get());
        assertEquals(2, requests.size());
        for (String row : new String[] { detail(7, false, 0), detail(8, false, 5) }) {
            NeteaseApi owned = new NeteaseApi(request -> response(row), () -> {
                fail("Permission rejection must not fetch validation token"); return TOKEN;
            });
            fails("permission", () -> new NeteaseLibraryApi(owned).setPlaylistFollowed(session(), "42", true));
        }
    }

    @Test public void incompleteSubscriptionNeverBecomesFalse() throws Exception {
        for (String row : new String[] {
            "{code:200,playlist:{id:42,name:'External',specialType:0,trackCount:0,creator:{userId:8}}}",
            "{code:200,playlist:{id:42,name:'External',subscribed:false,specialType:0,trackCount:0}}"
        }) {
            NeteaseApi api = new NeteaseApi(request -> response(row), () -> TOKEN);
            fails("incomplete", () -> new NeteaseLibraryApi(api).playlistSubscription(session(), "42"));
        }
    }

    @Test public void accountChangeDuringTokenFetchPreventsWrite() throws Exception {
        AtomicBoolean current = new AtomicBoolean(true);
        AtomicInteger writes = new AtomicInteger();
        NeteaseApi api = new NeteaseApi(request -> { writes.incrementAndGet(); return response("{code:200}"); },
            () -> { current.set(false); return TOKEN; });
        fails("account_changed", () -> api.setPlaylistFollowed(session(), "42", true, current::get));
        assertEquals(0, writes.get());
        current.set(true);
        fails("request", () -> api.setPlaylistFollowed(session(), "0", true, current::get));
        assertEquals(0, writes.get());
    }

    @Test public void missingTokenStopsBeforeTransportWithoutFallback() throws Exception {
        for (String token : new String[] {null, "", "short", "invalid;cookie=secret"}) {
            AtomicInteger writes = new AtomicInteger();
            NeteaseApi api = new NeteaseApi(request -> { writes.incrementAndGet(); return response("{code:200}"); }, () -> token);
            fails("verification_required", () -> api.setPlaylistFollowed(session(), "42", true, () -> true));
            assertEquals(0, writes.get());
        }
    }

    @Test public void mutationFailureNeverRetriesAndReportsRisk() throws Exception {
        AtomicInteger writes = new AtomicInteger();
        NeteaseApi api = new NeteaseApi(request -> { writes.incrementAndGet(); return response("{code:-462}"); }, () -> TOKEN);
        fails("risk", () -> api.setPlaylistFollowed(session(), "42", true, () -> true));
        assertEquals(1, writes.get());
    }

    private static NeteaseApi.Session session() throws Exception {
        return NeteaseApi.Session.fromJson(new JSONObject("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
    }
    private static String detail(int owner, boolean subscribed, int specialType) {
        return "{code:200,playlist:{id:42,name:'External',specialType:" + specialType +
            ",trackCount:0,creator:{userId:" + owner + "},subscribed:" + subscribed + "}}";
    }
    private static NeteaseApi.Response response(String json) {
        try { return new NeteaseApi.Response(new JSONObject(json), Collections.emptyMap()); }
        catch (Exception invalid) { throw new AssertionError(invalid); }
    }
    private static JSONObject payload(NeteaseApi.Request request, String uri) throws Exception {
        String hex = URLDecoder.decode(request.body.substring("params=".length()), "UTF-8");
        byte[] encrypted = new byte[hex.length() / 2];
        for (int i = 0; i < encrypted.length; i++) encrypted[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        Cipher cipher = Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII), "AES"));
        String[] parts = new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8).split("-36cd479b6b5-", -1);
        assertEquals(uri, parts[0]);
        return new JSONObject(parts[1]);
    }
    private interface Action { void run() throws Exception; }
    private static void fails(String code, Action action) throws Exception {
        NeteaseApi.ApiException failure = assertThrows(NeteaseApi.ApiException.class, action::run);
        assertEquals(code, failure.code);
    }
}
