package tech.musxi.loginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

public final class KugouAuthorizedAudioTest {
    private static final String HASH = "a".repeat(32);
    private static final String HIGH_HASH = "b".repeat(32);
    private static final String MID = "c".repeat(32);
    private static final KugouApi.Device DEVICE = new KugouApi.Device(MID);
    private static final KugouApi.Session SESSION =
        new KugouApi.Session("fixture-token", "123", "Fixture");

    @Test public void officialChainUsesFixedHostsFieldsAndSignatures() throws Exception {
        List<KugouAuthorizedAudio.Request> requests = new ArrayList<>();
        KugouAuthorizedAudio audio = authorized(requests);

        KugouAuthorizedAudio.Resolved resolved =
            audio.resolve(SESSION, HASH + "_12_34", "kugou:high");

        assertEquals(HIGH_HASH, resolved.hash);
        assertEquals("kugou:high", resolved.quality);
        assertEquals(4, requests.size());
        assertChain(requests);

        KugouAuthorizedAudio.Request privilege = requests.get(0);
        assertEquals("POST", privilege.method);
        assertEquals("https://gateway.kugou.com/v2/get_res_privilege/lite", privilege.endpoint);
        assertEquals("media.store.kugou.com", privilege.headers.get("x-router"));
        JSONObject body = new JSONObject(privilege.body);
        assertEquals(HASH, body.getJSONArray("resource").getJSONObject(0).getString("hash"));
        // Android's org.json has no JSONArray.toList(); walk the array instead.
        org.json.JSONArray qualities = body.getJSONArray("qualities");
        boolean high = false;
        for (int i = 0; i < qualities.length(); i++) high |= "high".equals(qualities.optString(i));
        assertTrue(high);

        KugouAuthorizedAudio.Request verify = requests.get(1);
        assertEquals("51", verify.params.get("module_id"));
        assertEquals(SESSION.userId, verify.params.get("userid"));
        assertEquals(SESSION.token, verify.params.get("token"));

        KugouAuthorizedAudio.Request authorization = requests.get(2);
        assertEquals("user-auth", authorization.params.get("authorization"));
        assertEquals("35", authorization.params.get("album_audio_id"));
        assertEquals("11561", authorization.params.get("clientver"));
        assertEquals("51", authorization.params.get("module_id"));
        assertEquals(HIGH_HASH, authorization.params.get("hash"));

        KugouAuthorizedAudio.Request url = requests.get(3);
        assertEquals("song-auth", url.params.get("auth"));
        assertEquals("1700000000", url.params.get("open_time"));
        assertEquals("high", url.params.get("quality"));
        assertEquals(HIGH_HASH, url.params.get("hash"));
        assertEquals("35", url.params.get("album_audio_id"));
        assertEquals(24, url.params.get("dfid").length());
        assertTrue(url.params.get("dfid").matches("[1-9A-Z]{24}"));
        String keyInput = HIGH_HASH + "185672dd44712f60bb1736df5a377e82"
            + "3116" + MID + SESSION.userId;
        assertEquals(md5(keyInput), url.params.get("key"));
    }

    @Test public void privilegeSupportsArrayObjectAndNestedDataShapes() throws Exception {
        KugouAuthorizedAudio.TrackRef original =
            new KugouAuthorizedAudio.TrackRef(HASH + "_12_34");
        JSONObject row = resource(HASH, "12", "34", "128");
        row.put("relate_goods", new JSONArray().put(resource(HIGH_HASH, "12", "35", "high")));

        assertEquals(HIGH_HASH, selectVariant(
            new JSONObject().put("status", 1).put("data", new JSONArray().put(row)),
            original).hash);
        assertEquals(HIGH_HASH, selectVariant(
            new JSONObject().put("status", 1).put("data",
                new JSONObject().put("resource", new JSONArray().put(row))), original).hash);
        assertEquals(HIGH_HASH, selectVariant(
            new JSONObject().put("status", 1).put("data",
                new JSONObject().put("data", new JSONArray().put(row))), original).hash);
    }

    @Test public void privilegeRequiresUniqueStrictBaseHashAndNonCollidingHighHash()
            throws Exception {
        KugouAuthorizedAudio.TrackRef original =
            new KugouAuthorizedAudio.TrackRef(HASH + "_12_34");
        rejects("unavailable", () -> selectVariant(new JSONObject().put("status", 1)
            .put("data", new JSONArray().put(resource(HIGH_HASH, "12", "34", "128"))), original));

        rejects("identity", () -> selectVariant(new JSONObject().put("status", 1)
            .put("data", new JSONArray()
                .put(resource(HASH, "12", "34", "128"))
                .put(resource(HASH, "12", "35", "high"))), original));

        JSONObject shared = resource(HASH, "12", "34", "128");
        shared.put("relate_goods", new JSONArray().put(resource(HASH, "12", "35", "high")));
        rejects("unavailable", () -> selectVariant(new JSONObject().put("status", 1)
            .put("data", new JSONArray().put(shared)), original));
    }

    @Test public void privilegeRejectsHighVariantFromAnotherAlbum() throws Exception {
        KugouAuthorizedAudio.TrackRef original =
            new KugouAuthorizedAudio.TrackRef(HASH + "_12_34");
        JSONObject row = resource(HASH, "12", "34", "128");
        row.put("relate_goods", new JSONArray().put(resource(HIGH_HASH, "99", "35", "high")));

        rejects("identity", () -> selectVariant(new JSONObject().put("status", 1)
            .put("data", new JSONArray().put(row)), original));
    }

    @Test public void privilegeRejectsExplicitNonAudioTrackerTypes() throws Exception {
        KugouAuthorizedAudio.TrackRef original =
            new KugouAuthorizedAudio.TrackRef(HASH + "_12_34");
        for (String type : Arrays.asList("ringtone", "mv")) {
            JSONObject high = resource(HIGH_HASH, "12", "35", "high");
            high.put("info", new JSONObject().put("tracker_type", type));
            JSONObject row = resource(HASH, "12", "34", "128");
            row.put("relate_goods", new JSONArray().put(high));

            rejects("unavailable", () -> selectVariant(new JSONObject().put("status", 1)
                .put("data", new JSONArray().put(row)), original));
        }
    }

    @Test public void privilegeAllowsPinnedFullTrackerType() throws Exception {
        KugouAuthorizedAudio.TrackRef original =
            new KugouAuthorizedAudio.TrackRef(HASH + "_12_34");
        JSONObject high = resource(HIGH_HASH, "12", "35", "high");
        high.put("info", new JSONObject().put("tracker_type", "full"));
        JSONObject row = resource(HASH, "12", "34", "128");
        row.put("relate_goods", new JSONArray().put(high));

        assertEquals(HIGH_HASH, selectVariant(new JSONObject().put("status", 1)
            .put("data", new JSONArray().put(row)), original).hash);
    }

    @Test public void authorizationRejectsInvalidVerifyEmptyAuthAndStatusCode() throws Exception {
        for (String verifyBody : Arrays.asList(
            "{\"status\":1,\"data\":{\"valid\":0,\"auth\":\"user-auth\"}}",
            "{\"status\":1,\"data\":{\"valid\":1,\"auth\":\"\"}}",
            "{\"status\":0,\"error_code\":20006,\"data\":{\"valid\":1,\"auth\":\"user-auth\"}}")) {
            int[] calls = {0};
            KugouAuthorizedAudio audio = new KugouAuthorizedAudio(DEVICE, authorizedTransport(request -> {
                calls[0]++;
                if (request.endpoint.endsWith("get_res_privilege/lite")) return privilege();
                return new JSONObject(verifyBody);
            }));
            rejects("permission", () -> audio.resolve(
                SESSION, HASH + "_12_34", "kugou:high"));
            assertEquals(2, calls[0]);
        }
    }

    @Test public void authorizedPayloadKeepsExactHashAndRefusesPreviews() throws Exception {
        KugouLibraryApi.Audio audio = KugouLibraryApi.parseAuthorizedAudio(
            highUrlPayload(HIGH_HASH), HIGH_HASH, 0, "kugou:high");
        assertEquals("kugou:high", audio.qualityId);
        assertEquals(180_000L, audio.expectedDurationMs);
        assertEquals(1, audio.candidates.size());
        assertTrue(audio.candidates.get(0).startsWith("https://"));

        JSONObject flat = new JSONObject().put("status", 1).put("hash", HIGH_HASH)
            .put("bitRate", 2_000_000).put("extName", "flac")
            .put("duration_ms", 180_000L).put("url",
                new JSONArray().put("https://fsandroid.tx.kugou.com/high.flac"));
        assertEquals(180_000L, KugouLibraryApi.parseAuthorizedAudio(
            flat, HIGH_HASH, 0, "kugou:high").expectedDurationMs);

        rejects("identity", () -> KugouLibraryApi.parseAuthorizedAudio(
            highUrlPayload(HASH), HIGH_HASH, 0, "kugou:high"));
        JSONObject trial = highUrlPayload(HIGH_HASH);
        trial.getJSONObject("data").put("is_free_part", 1);
        rejects("preview", () -> KugouLibraryApi.parseAuthorizedAudio(
            trial, HIGH_HASH, 0, "kugou:high"));
    }

    @Test public void streamInfoRequiresActualHighResolutionAndBoundedDuration()
            throws Exception {
        KugouLibraryApi.FlacInfo high = KugouLibraryApi.parseFlacInfo(
            flacHeader(96_000, 24, 180_000), 180_000);
        assertEquals(96_000, high.sampleRate);
        assertEquals(24, high.bitsPerSample);
        assertEquals(180_000, high.durationMs);

        rejects("unavailable", () -> KugouLibraryApi.parseFlacInfo(
            flacHeader(44_100, 16, 180_000), 180_000));

        KugouLibraryApi.parseFlacInfo(flacHeader(96_000, 24, 185_000), 180_000);
        rejects("unavailable", () -> KugouLibraryApi.parseFlacInfo(
            flacHeader(96_000, 24, 190_000), 180_000));
        KugouLibraryApi.parseFlacInfo(flacHeader(96_000, 24, 210_000), 200_000);
        rejects("unavailable", () -> KugouLibraryApi.parseFlacInfo(
            flacHeader(96_000, 24, 211_000), 200_000));
    }

    @Test public void highIsListedOnlyAfterPrivilegeAuthorizationAndStreamInfo() throws Exception {
        int[] baseCalls = {0};
        int[] highReads = {0};
        List<KugouAuthorizedAudio.Request> chain = new ArrayList<>();
        KugouAuthorizedAudio authorized = authorized(chain);
        KugouApi api = new KugouApi(DEVICE, apiTransport(request -> {
            baseCalls[0]++;
            switch (request.params.get("quality")) {
                case "128": return basicAudio("128", "mp3", 128000);
                case "320": return basicAudio("320", "mp3", 320000);
                case "flac": return basicAudio("flac", "flac", 900000);
                default: throw new IOException("unexpected basic quality");
            }
        }));
        KugouLibraryApi library = new KugouLibraryApi(api, url -> {}, authorized,
            (url, duration) -> {
                highReads[0]++;
                assertEquals("https://fsandroid.tx.kugou.com/high.flac", url);
                assertEquals(180_000L, duration);
            });

        List<KugouLibraryApi.AudioQuality> qualities =
            library.qualities(SESSION, HASH + "_12_34", 0);

        assertEquals(Arrays.asList("kugou:128", "kugou:320", "kugou:flac", "kugou:high"),
            Arrays.asList(qualities.get(0).id, qualities.get(1).id,
                qualities.get(2).id, qualities.get(3).id));
        assertEquals(3, baseCalls[0]);
        assertEquals(1, highReads[0]);
        assertChain(chain);
    }

    @Test public void networkFailureNeverFallsBackToAuthorization() throws Exception {
        int[] authorizedCalls = {0};
        KugouAuthorizedAudio authorized = new KugouAuthorizedAudio(DEVICE, authorizedTransport(request -> {
            authorizedCalls[0]++;
            throw new IOException("must not run");
        }));
        KugouLibraryApi library = new KugouLibraryApi(
            new KugouApi(DEVICE, apiTransport(request -> {
                throw new KugouApi.ApiException("network", "offline");
            })), url -> {}, authorized, (url, duration) -> {});

        rejects("network", () -> library.audio(SESSION, HASH + "_12_34", 0, "kugou:128"));
        assertEquals(0, authorizedCalls[0]);
    }

    @Test public void explicitPermissionFallsBackToAuthorizationWithoutPreview() throws Exception {
        KugouAuthorizedAudio authorized = new KugouAuthorizedAudio(DEVICE, authorizedTransport(request -> {
            if (request.endpoint.endsWith("get_res_privilege/lite"))
                return new JSONObject().put("status", 1).put("data", new JSONArray().put(
                    resource(HASH, "12", "34", "128")));
            if (request.endpoint.endsWith("/v1/user_verify"))
                return new JSONObject().put("status", 1)
                    .put("data", new JSONObject().put("valid", 1).put("auth", "user-auth"));
            if (request.endpoint.endsWith("/v1/authorization"))
                return new JSONObject().put("status", 1).put("data",
                    new JSONObject().put("auth", "song-auth").put("open_time", "1700000000"));
            return new JSONObject().put("status", 1).put("data", new JSONObject()
                .put("hash", HASH).put("bitRate", 128029).put("extName", "mp3")
                .put("url", new JSONArray().put("https://fsandroid.tx.kugou.com/song.mp3")));
        }));
        KugouLibraryApi library = new KugouLibraryApi(
            new KugouApi(DEVICE, apiTransport(request -> new JSONObject()
                .put("status", 2).put("data", new JSONObject().put("status", 2)))),
            url -> {}, authorized, (url, duration) -> {});

        KugouLibraryApi.Audio audio = library.audio(SESSION, HASH + "_12_34", 0, "kugou:128");
        assertEquals("kugou:128", audio.qualityId);
        assertEquals("https://fsandroid.tx.kugou.com/song.mp3", audio.url);
    }

    private static KugouAuthorizedAudio authorized(
            List<KugouAuthorizedAudio.Request> requests) {
        return new KugouAuthorizedAudio(DEVICE, authorizedTransport(request -> {
            requests.add(request);
            if (request.endpoint.endsWith("get_res_privilege/lite")) return privilege();
            if (request.endpoint.endsWith("/v1/user_verify"))
                return new JSONObject().put("status", 1)
                    .put("data", new JSONObject().put("valid", 1).put("auth", "user-auth"));
            if (request.endpoint.endsWith("/v1/authorization"))
                return new JSONObject().put("status", 1).put("data",
                    new JSONObject().put("auth", "song-auth").put("open_time", "1700000000"));
            return highUrlPayload(HIGH_HASH);
        }), new SecureRandom() {
            @Override public int nextInt(int bound) {
                return 0;
            }
        });
    }

    private static JSONObject privilege() throws org.json.JSONException {
        JSONObject row = resource(HASH, "12", "34", "128");
        row.put("relate_goods", new JSONArray().put(resource(HIGH_HASH, "12", "35", "high")));
        return new JSONObject().put("status", 1).put("data", new JSONArray().put(row));
    }

    private static JSONObject resource(
            String hash, String album, String audioId, String quality) throws org.json.JSONException {
        return new JSONObject().put("hash", hash).put("album_id", album)
            .put("album_audio_id", audioId).put("quality", quality);
    }

    private static KugouAuthorizedAudio.Variant selectVariant(
            JSONObject response, KugouAuthorizedAudio.TrackRef original) throws IOException {
        return KugouAuthorizedAudio.selectVariant(response, HASH, "high", original);
    }

    private static JSONObject highUrlPayload(String hash) throws org.json.JSONException {
        return new JSONObject().put("status", 1).put("data", new JSONObject()
            .put("hash", hash).put("bitRate", 2_000_000).put("extName", "flac")
            .put("duration_ms", 180_000L).put("url",
                new JSONArray().put("https://fsandroid.tx.kugou.com/high.flac")));
    }

    private static JSONObject basicAudio(String quality, String extension, int bitrate) throws org.json.JSONException {
        String host = "flac".equals(quality)
            ? "https://fsandroid.tx.kugou.com/song.flac"
            : "https://fsandroid.tx.kugou.com/song.mp3";
        return new JSONObject().put("status", 1).put("hash", HASH)
            .put("bitRate", bitrate).put("extName", extension)
            .put("url", new JSONArray().put(host));
    }

    private static byte[] flacHeader(int sampleRate, int bits, long durationMs) {
        long totalSamples = Math.multiplyExact(durationMs, sampleRate) / 1000L;
        long packed = ((long) sampleRate << 44) | (1L << 41)
            | ((long) (bits - 1) << 36) | totalSamples;
        byte[] header = new byte[42];
        header[0] = 'f';
        header[1] = 'L';
        header[2] = 'a';
        header[3] = 'C';
        header[4] = (byte) 0x80;
        header[7] = 34;
        for (int index = 7; index >= 0; index--) {
            header[18 + index] = (byte) (packed & 0xff);
            packed >>>= 8;
        }
        return header;
    }

    private static void assertChain(List<KugouAuthorizedAudio.Request> requests)
            throws Exception {
        assertEquals(Arrays.asList(
            "https://gateway.kugou.com/v2/get_res_privilege/lite",
            "https://trackercdngz.kugou.com/v1/user_verify",
            "https://trackercdngz.kugou.com/v1/authorization",
            "https://gateway.kugou.com/tracker/v5/url"),
            Arrays.asList(requests.get(0).endpoint, requests.get(1).endpoint,
                requests.get(2).endpoint, requests.get(3).endpoint));
        for (KugouAuthorizedAudio.Request request : requests) {
            Map<String, String> unsigned =
                new java.util.LinkedHashMap<>(request.params);
            String signature = unsigned.remove("signature");
            assertEquals(KugouApi.androidSignature(unsigned, request.body), signature);
        }
    }

    private static String md5(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("MD5")
            .digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest);
    }

    private interface AuthorizedFixture {
        JSONObject execute(KugouAuthorizedAudio.Request request) throws Exception;
    }
    private interface ApiFixture {
        JSONObject execute(KugouApi.Request request) throws Exception;
    }
    private static KugouAuthorizedAudio.Transport authorizedTransport(AuthorizedFixture fixture) {
        return request -> {
            try { return fixture.execute(request); }
            catch (IOException failure) { throw failure; }
            catch (Exception invalid) { throw new AssertionError("Invalid JSON fixture", invalid); }
        };
    }
    private static KugouApi.Transport apiTransport(ApiFixture fixture) {
        return request -> {
            try { return fixture.execute(request); }
            catch (IOException failure) { throw failure; }
            catch (Exception invalid) { throw new AssertionError("Invalid JSON fixture", invalid); }
        };
    }

    private interface Check {
        void run() throws Exception;
    }

    private static void rejects(String expectedCode, Check check) throws Exception {
        try {
            check.run();
            fail("Expected " + expectedCode);
        } catch (KugouApi.ApiException rejected) {
            assertEquals(expectedCode, rejected.code);
        }
    }
}
