package tech.musxi.neteaseloginpoc;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPOutputStream;
import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;

public class NeteaseXeapiTest {
    @Test public void registersOnceAndUsesFreshEnvelopesWithTheCurrentAccount() throws Exception {
        List<NeteaseApi.Request> requests = new ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            if (NeteaseXeapi.KEY_ENDPOINT.equals(request.endpoint)) return XeapiTestPeer.bootstrap(request);
            NeteaseApi.requireEndpoint(new URL(request.endpoint));
            assertEquals("https://interface3.music.163.com/xeapi/song/enhance/player/url/v1", request.endpoint);
            assertEquals("ENCRYPTED", request.headers.get("X-Client-Enc-State"));
            assertEquals("true", request.headers.get("x-aeapi"));
            assertEquals("android", request.headers.get("x-os"));
            assertEquals("9.5.61", request.headers.get("x-appver"));
            assertEquals(request.headers.get("x-deviceid"), request.headers.get("x-sdeviceid"));
            JSONObject payload = XeapiTestPeer.payload(request);
            assertEquals("[2026565329]", payload.optString("ids"));
            assertEquals("standard", payload.optString("level"));
            assertEquals("flac", payload.optString("encodeType"));
            assertFalse(payload.has("header")); assertFalse(payload.has("e_r"));
            return response("{code:200,data:[{id:2026565329,code:200,url:'https://m7.music.126.net/fixture.mp3',"
                    + "br:128000,expi:120,freeTrialInfo:null,level:'standard'}]}");
        });
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        library.audio(session("7", "first"), "2026565329", 1000);
        library.audio(session("8", "second"), "2026565329", 1000);
        assertEquals(3, requests.size());
        assertEquals("first", requests.get(1).headers.get("x-music-u"));
        assertEquals("second", requests.get(2).headers.get("x-music-u"));
        assertFalse(requests.get(2).headers.get("Cookie").contains("first"));
        assertNotEquals(XeapiTestPeer.form(requests.get(1).body).get("C"),
                XeapiTestPeer.form(requests.get(2).body).get("C"));
        assertNotEquals(XeapiTestPeer.form(requests.get(1).body).get("S"),
                XeapiTestPeer.form(requests.get(2).body).get("S"));
    }

    @Test public void invalidBootstrapStopsBeforeSendingAccountCredentials() throws Exception {
        for (String mutation : Arrays.asList("signature", "encryptedData", "timestamp")) {
            List<String> endpoints = new ArrayList<>();
            NeteaseApi api = new NeteaseApi(request -> {
                endpoints.add(request.endpoint);
                NeteaseApi.Response bootstrap = XeapiTestPeer.bootstrap(request);
                try { bootstrap.body.getJSONObject("data").put(mutation, "invalid"); }
                catch (org.json.JSONException bad) { throw new IOException(bad); }
                return bootstrap;
            });
            assertEquals("protocol", assertThrows(NeteaseApi.ApiException.class, () ->
                    new NeteaseLibraryApi(api).audio(session("7", "secret"), "1", 0)).code);
            assertEquals(Collections.singletonList(NeteaseXeapi.KEY_ENDPOINT), endpoints);
        }
    }

    @Test public void bootstrapAndStreamRiskStopWithoutFallbackOrQualityRetries() throws Exception {
        for (boolean duringBootstrap : new boolean[]{true, false}) {
            List<String> endpoints = new ArrayList<>();
            NeteaseApi api = new NeteaseApi(request -> {
                endpoints.add(request.endpoint);
                if (!duringBootstrap && NeteaseXeapi.KEY_ENDPOINT.equals(request.endpoint))
                    return XeapiTestPeer.bootstrap(request);
                return response("{code:-460}");
            });
            assertEquals("risk", assertThrows(NeteaseApi.ApiException.class, () ->
                    new NeteaseLibraryApi(api).qualities(session("7", "secret"), "1", 0)).code);
            assertEquals(duringBootstrap ? Collections.singletonList(NeteaseXeapi.KEY_ENDPOINT)
                    : Arrays.asList(NeteaseXeapi.KEY_ENDPOINT, NeteaseApi.SONG_URL), endpoints);
        }
        List<String> endpoints = new ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            endpoints.add(request.endpoint); throw new IOException("private-error");
        });
        NeteaseApi.ApiException error = assertThrows(NeteaseApi.ApiException.class, () ->
                new NeteaseLibraryApi(api).audio(session("7", "secret"), "1", 0));
        assertEquals("network", error.code); assertFalse(error.getMessage().contains("private-error"));
        assertEquals(Collections.singletonList(NeteaseXeapi.KEY_ENDPOINT), endpoints);
    }

    @Test public void responseSupportsEncryptedJsonAndGzipAndRejectsInvalidData() throws Exception {
        byte[] json = "{\"code\":-460}".getBytes(StandardCharsets.UTF_8);
        assertEquals(-460, NeteaseXeapi.decodeResponse(encrypt(json)).getInt("code"));
        assertEquals(-460, NeteaseXeapi.decodeResponse(encrypt(gzip(json))).getInt("code"));
        for (byte[] invalid : new byte[][]{new byte[0], json, encrypt("{}".getBytes(StandardCharsets.UTF_8)),
                encrypt(new byte[]{0x1f, (byte)0x8b, 0, 0})}) {
            assertEquals("protocol", assertThrows(NeteaseApi.ApiException.class, () ->
                    NeteaseXeapi.decodeResponse(invalid)).code);
        }
        byte[] oversized = new byte[4 * 1024 * 1024 + 1]; Arrays.fill(oversized, (byte)' ');
        assertEquals("protocol", assertThrows(NeteaseApi.ApiException.class, () ->
                NeteaseXeapi.decodeResponse(encrypt(gzip(oversized)))).code);
    }

    @Test public void onlyFixedBootstrapAndPlaybackEndpointsAreAllowed() throws Exception {
        NeteaseApi.requireEndpoint(new URL(NeteaseXeapi.KEY_ENDPOINT));
        NeteaseApi.requireEndpoint(new URL(NeteaseApi.SONG_URL));
        for (String url : Arrays.asList(NeteaseXeapi.KEY_ENDPOINT + "?redirect=x", NeteaseApi.SONG_URL + "#x",
                "https://interface3.music.163.com/xeapi/other", "https://evil.test/api/bsr/sk/get",
                "https://interfacepc.music.163.com/eapi/song/enhance/player/url/v1")) {
            assertEquals("endpoint", assertThrows(NeteaseApi.ApiException.class, () ->
                    NeteaseApi.requireEndpoint(new URL(url))).code);
        }
    }

    private static byte[] encrypt(byte[] value) throws Exception {
        Cipher aes = Cipher.getInstance("AES/ECB/PKCS5Padding");
        aes.init(Cipher.ENCRYPT_MODE, new SecretKeySpec("e82ckenh8dichen8".getBytes(StandardCharsets.US_ASCII), "AES"));
        return aes.doFinal(value);
    }
    private static byte[] gzip(byte[] value) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) { gzip.write(value); }
        return out.toByteArray();
    }
    private static NeteaseApi.Session session(String id, String cookie) throws Exception {
        return NeteaseApi.Session.fromJson(new JSONObject().put("userId", id).put("nickname", "Fixture")
                .put("cookies", new JSONObject().put("MUSIC_U", cookie).put("__csrf", "csrf")));
    }
    private static NeteaseApi.Response response(String json) throws IOException {
        try { return new NeteaseApi.Response(new JSONObject(json), Collections.emptyMap()); }
        catch (org.json.JSONException | RuntimeException bad) { throw new IOException(bad); }
    }
}
