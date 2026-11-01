package tech.musxi.loginpoc;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Account-authorized KuGou audio chain from the pinned MIT KuGouMusicApi modules
 * privilege_lite, user_verify, song_auth and song_url_auth.
 *
 * <p>All service endpoints are fixed HTTPS endpoints. The song URL endpoint is kept
 * separate from the basic /v5/url request because it uses gateway /tracker/v5/url,
 * a random 24-character dfid and both key and Android signature parameters.</p>
 */
public final class KugouAuthorizedAudio {
    private static final String TRACKER_HOST = "trackercdngz.kugou.com";
    private static final String GATEWAY_HOST = "gateway.kugou.com";
    private static final String PRIVILEGE_PATH = "/v2/get_res_privilege/lite";
    private static final String URL_PATH = "/tracker/v5/url";
    private static final String USER_VERIFY_PATH = "/v1/user_verify";
    private static final String AUTHORIZATION_PATH = "/v1/authorization";
    private static final String KEY_SALT = "185672dd44712f60bb1736df5a377e82";
    private static final String RANDOM_CHARS = "1234567890ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final int RESPONSE_LIMIT = 512 * 1024;
    private static final int TIMEOUT_MS = 18_000;

    interface Transport {
        JSONObject execute(Request request) throws IOException;
    }

    static final class Request {
        final String method;
        final String endpoint;
        final Map<String, String> params;
        final String body;
        final Map<String, String> headers;

        Request(String method, String endpoint, Map<String, String> params, String body,
                Map<String, String> headers) {
            this.method = method;
            this.endpoint = endpoint;
            this.params = Collections.unmodifiableMap(new LinkedHashMap<>(params));
            this.body = body;
            this.headers = Collections.unmodifiableMap(new LinkedHashMap<>(headers));
        }
    }

    static final class TrackRef {
        final String hash;
        final String albumId;
        final String albumAudioId;

        TrackRef(String songId) throws IOException {
            String[] parts = songId == null ? new String[0] : songId.split("_", -1);
            if (parts.length != 3 || !parts[0].matches("[a-f0-9]{32}"))
                throw error("identity");
            positive(parts[1]);
            positive(parts[2]);
            hash = parts[0];
            albumId = parts[1];
            albumAudioId = parts[2];
        }
    }

    static final class Variant {
        final String hash;
        final String albumId;
        final String albumAudioId;

        Variant(String hash, String albumId, String albumAudioId) {
            this.hash = hash;
            this.albumId = albumId;
            this.albumAudioId = albumAudioId;
        }
    }

    static final class Resolved {
        final JSONObject body;
        final String hash;
        final String quality;

        Resolved(JSONObject body, String hash, String quality) {
            this.body = body;
            this.hash = hash;
            this.quality = quality;
        }
    }

    private final KugouApi.Device device;
    private final Transport transport;
    private final SecureRandom random;

    public KugouAuthorizedAudio(KugouApi.Device device) {
        this(device, KugouAuthorizedAudio::executeHttps, new SecureRandom());
    }

    KugouAuthorizedAudio(KugouApi.Device device, Transport transport) {
        this(device, transport, new SecureRandom());
    }

    KugouAuthorizedAudio(KugouApi.Device device, Transport transport, SecureRandom random) {
        if (device == null || transport == null || random == null)
            throw new IllegalArgumentException("missing authorized-audio dependency");
        this.device = device;
        this.transport = transport;
        this.random = random;
    }

    Resolved resolve(KugouApi.Session session, String songId, String quality) throws IOException {
        if (session == null) throw error("credentials");
        String requested = qualityParameter(quality);
        TrackRef original = new TrackRef(songId);
        Variant target = selectVariant(privilege(session, original), original.hash, requested, original);

        String userAuth = userVerify(session);
        String[] songAuth = authorization(session, userAuth, target);
        JSONObject body = urlRequest(session, target, requested, songAuth[0], songAuth[1]);
        return new Resolved(body, target.hash, quality);
    }

    private JSONObject privilege(KugouApi.Session session, TrackRef track) throws IOException {
        long time = now();
        Map<String, String> params = deviceDefaults(time);
        session(params, session);
        String body = "{\"appid\":3116,\"area_code\":1,\"behavior\":\"play\",\"clientver\":11440,"
            + "\"need_hash_offset\":1,\"relate\":1,\"support_verify\":1,"
            + "\"resource\":[{\"type\":\"audio\",\"page_id\":0,\"hash\":"
            + JSONObject.quote(track.hash) + ",\"album_id\":" + JSONObject.quote(track.albumId)
            + "}],\"qualities\":[\"128\",\"320\",\"flac\",\"high\",\"viper_atmos\","
            + "\"viper_tape\",\"viper_clear\",\"super\",\"multitrack\"]}";
        params.put("signature", KugouApi.androidSignature(params, body));
        Map<String, String> headers = headers(params, time);
        headers.put("x-router", "media.store.kugou.com");
        headers.put("Content-Type", "application/json");
        return transport.execute(new Request("POST", "https://" + GATEWAY_HOST + PRIVILEGE_PATH,
            params, body, headers));
    }

    private String userVerify(KugouApi.Session session) throws IOException {
        long time = now();
        Map<String, String> params = deviceDefaults(time);
        session(params, session);
        params.put("module_id", "51");
        params.put("signature", KugouApi.androidSignature(params, null));
        JSONObject root = transport.execute(request("GET", TRACKER_HOST, USER_VERIFY_PATH,
            params, null, headers(params, time)));
        requireSuccess(root);
        JSONObject data = data(root);
        String valid = scalar(data, "valid");
        if (!"1".equals(valid)) throw error("permission");
        String auth = authString(data);
        return auth;
    }

    private String[] authorization(KugouApi.Session session, String userAuth, Variant target) throws IOException {
        long time = now();
        Map<String, String> params = deviceDefaults(time);
        session(params, session);
        params.put("authorization", userAuth);
        params.put("module_id", "51");
        params.put("album_audio_id", target.albumAudioId);
        params.put("clientver", "11561");
        params.put("hash", target.hash.toLowerCase(Locale.ROOT));
        params.put("signature", KugouApi.androidSignature(params, null));
        JSONObject root = transport.execute(request("GET", TRACKER_HOST, AUTHORIZATION_PATH,
            params, null, headers(params, time)));
        requireSuccess(root);
        JSONObject data = data(root);
        String auth = authString(data);
        String openTime = optionalNonnegative(data, "open_time", "0");
        return new String[] {auth, openTime};
    }

    private JSONObject urlRequest(KugouApi.Session session, Variant target, String quality,
            String songAuth, String openTime) throws IOException {
        long time = now();
        String dfid = randomString(24);
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dfid", dfid);
        params.put("mid", device.mid);
        params.put("uuid", "-");
        params.put("appid", "3116");
        params.put("clientver", "11561");
        params.put("clienttime", String.valueOf(time));
        session(params, session);
        params.put("album_id", target.albumId);
        params.put("area_code", "1");
        params.put("module", "");
        params.put("hash", target.hash.toLowerCase(Locale.ROOT));
        params.put("need_m", "0");
        params.put("ssa_flag", "is_fromtrack");
        params.put("version", "11430");
        params.put("open_time", openTime);
        params.put("ptype", "0");
        params.put("need_ogg", "1");
        params.put("page_id", "967177915");
        params.put("auth", songAuth);
        params.put("mtype", "0");
        params.put("quality", quality);
        params.put("album_audio_id", target.albumAudioId);
        params.put("behavior", "play");
        params.put("pid", "411");
        params.put("module_id", "51");
        params.put("cmd", "26");
        params.put("ppage_id", "356753938,823673182,967485191");
        params.put("pidversion", "3001");
        params.put("cdnBackup", "1");
        params.put("key", key(params.get("hash"), device.mid, session.userId, "3116"));

        // song_url_auth sets notSign (not notSignature). The pinned wrapper therefore
        // still emits the ordinary Android signature over params and an empty body.
        params.put("signature", KugouApi.androidSignature(params, null));
        return transport.execute(request("GET", GATEWAY_HOST, URL_PATH, params, null, headers(params, time)));
    }

    static Variant selectVariant(JSONObject root, String baseHash, String quality, TrackRef original)
            throws IOException {
        requireSuccess(root);
        JSONArray resources = resources(root);

        baseHash = baseHash.toLowerCase(Locale.ROOT);
        JSONObject matching = matchingResource(resources, baseHash);
        if (matching == null) throw error("unavailable");

        JSONArray related = matching.optJSONArray("relate_goods");
        if (related == null) related = matching.optJSONArray("relate");
        List<JSONObject> variants = new ArrayList<>();
        variants.add(matching);
        if (related != null) {
            for (int i = 0; i < related.length(); i++) {
                JSONObject value = related.optJSONObject(i);
                if (value == null) throw error("protocol");
                variants.add(value);
            }
        }

        Map<String, Set<String>> qualitiesByHash = new HashMap<>();
        for (JSONObject variant : variants) {
            String hash = lowerHash(variant.opt("hash"));
            if (hash == null) continue;
            String tier = scalar(variant, "quality");
            if (!tier.isEmpty()) {
                qualitiesByHash.computeIfAbsent(hash, ignored -> new HashSet<>()).add(tier);
            }
        }

        JSONObject selected = null;
        for (JSONObject variant : variants) {
            if (!quality.equals(scalar(variant, "quality"))) continue;
            String hash = lowerHash(variant.opt("hash"));
            if (hash == null || !supportedTrackerType(trackerType(variant))) continue;
            Set<String> tiers = qualitiesByHash.get(hash);
            if (tiers == null || tiers.size() != 1 || !tiers.contains(quality)) continue;
            if (selected != null) throw error("identity");
            selected = variant;
        }
        if (selected == null) throw error("unavailable");

        String selectedAlbum = positiveOrNull(selected.opt("album_id"));
        String matchingAlbum = positiveOrNull(matching.opt("album_id"));
        if ((selectedAlbum != null && !selectedAlbum.equals(original.albumId))
                || (matchingAlbum != null && !matchingAlbum.equals(original.albumId)))
            throw error("identity");
        String albumId = selectedAlbum != null ? selectedAlbum : matchingAlbum;
        String audioId = positiveOrNull(selected.opt("album_audio_id"));
        if (audioId == null) audioId = positiveOrNull(matching.opt("album_audio_id"));
        return new Variant(lowerHash(selected.opt("hash")),
            albumId == null ? original.albumId : albumId,
            audioId == null ? original.albumAudioId : audioId);
    }

    private static JSONObject matchingResource(JSONArray resources, String baseHash) throws IOException {
        JSONObject found = null;
        for (int i = 0; i < resources.length(); i++) {
            JSONObject row = resources.optJSONObject(i);
            if (row == null) throw error("protocol");
            String rowHash = lowerHash(row.opt("hash"));
            if (!baseHash.equals(rowHash)) continue;
            if (found != null) throw error("identity");
            found = row;
        }
        return found;
    }

    private static String trackerType(JSONObject variant) {
        JSONObject info = variant.optJSONObject("info");
        return info == null ? "" : scalar(info, "tracker_type");
    }

    private static boolean supportedTrackerType(String type) {
        // The pinned vendor fixture uses "full"; existing playlist fixtures omit it.
        return type.isEmpty() || "full".equals(type);
    }

    private static String qualityParameter(String id) throws IOException {
        switch (id) {
            case "kugou:128": return "128";
            case "kugou:320": return "320";
            case "kugou:flac": return "flac";
            case "kugou:high": return "high";
            default: throw error("request");
        }
    }

    private static Request request(String method, String host, String path, Map<String, String> params,
            String body, Map<String, String> headers) throws IOException {
        String endpoint = "https://" + host + path;
        validateEndpoint(method, endpoint);
        return new Request(method, endpoint, params, body, headers);
    }

    private static void validateEndpoint(String method, String endpoint) throws IOException {
        URL url = new URL(endpoint);
        String host = url.getHost();
        String path = url.getPath();
        boolean tracker = TRACKER_HOST.equals(host)
            && (USER_VERIFY_PATH.equals(path) || AUTHORIZATION_PATH.equals(path));
        boolean gateway = GATEWAY_HOST.equals(host)
            && (PRIVILEGE_PATH.equals(path) || URL_PATH.equals(path));
        if (!"https".equals(url.getProtocol()) || url.getUserInfo() != null || url.getRef() != null
            || (url.getPort() != -1 && url.getPort() != 443) || (!tracker && !gateway)
            || (!"GET".equals(method) && !"POST".equals(method)))
            throw error("endpoint");
    }

    private static Map<String, String> defaults(long time) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("dfid", "-");
        params.put("mid", "");
        params.put("uuid", "-");
        params.put("appid", "3116");
        params.put("clientver", "11440");
        params.put("clienttime", String.valueOf(time));
        return params;
    }

    private Map<String, String> deviceDefaults(long time) {
        Map<String, String> params = defaults(time);
        params.put("mid", device.mid);
        return params;
    }

    private static void session(Map<String, String> params, KugouApi.Session session) {
        params.put("userid", session.userId);
        params.put("token", session.token);
    }

    private static Map<String, String> headers(Map<String, String> params, long time) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", "Android15-1070-11083-46-0-DiscoveryDRADProtocol-wifi");
        headers.put("dfid", params.get("dfid"));
        headers.put("mid", params.get("mid"));
        headers.put("clienttime", String.valueOf(time));
        headers.put("kg-rc", "1");
        headers.put("kg-thash", "5d816a0");
        headers.put("kg-rec", "1");
        headers.put("kg-rf", "B9EDA08A64250DEFFBCADDEE00F8F25F");
        return headers;
    }

    private String randomString(int length) {
        StringBuilder value = new StringBuilder(length);
        for (int i = 0; i < length; i++) value.append(RANDOM_CHARS.charAt(random.nextInt(RANDOM_CHARS.length())));
        return value.toString();
    }

    private static String key(String hash, String mid, String userId, String appId) {
        return md5(hash + KEY_SALT + appId + mid + userId);
    }

    private static void requireSuccess(JSONObject root) throws IOException {
        if (root == null) throw error("protocol");
        if (root.has("ssaCode") || root.has("ssa_code")) throw error("risk");
        String status = scalar(root, "status");
        String errorCode = scalar(root, "error_code");
        if (!"1".equals(status) || (!errorCode.isEmpty() && !"0".equals(errorCode)))
            throw error("permission");
    }

    private static JSONObject data(JSONObject root) throws IOException {
        JSONObject data = root.optJSONObject("data");
        if (data == null) throw error("protocol");
        return data;
    }

    private static JSONArray resources(JSONObject root) throws IOException {
        Object data = root.opt("data");
        if (data instanceof JSONArray) return (JSONArray) data;
        if (data instanceof JSONObject) {
            JSONObject row = (JSONObject) data;
            Object resources = row.opt("resource");
            if (resources == null) resources = row.opt("resources");
            if (resources instanceof JSONArray) return (JSONArray) resources;
            Object nested = row.opt("data");
            if (nested instanceof JSONArray) return (JSONArray) nested;
        }
        throw error("protocol");
    }

    private static String authString(JSONObject row) throws IOException {
        Object value = row.opt("auth");
        if (!(value instanceof String)) throw error("protocol");
        if (((String) value).isEmpty()) throw error("permission");
        return (String) value;
    }

    private static String optionalNonnegative(JSONObject row, String key, String fallback) throws IOException {
        if (!row.has(key) || row.isNull(key)) return fallback;
        String value = scalar(row, key);
        if (!value.matches("(?:0|[1-9][0-9]{0,17})")) throw error("protocol");
        return value;
    }

    private static String positiveOrNull(Object value) {
        String text = scalarValue(value);
        return text.matches("[1-9][0-9]{0,15}") ? text : null;
    }

    private static String lowerHash(Object value) {
        String text = scalarValue(value).toLowerCase(Locale.ROOT);
        return text.matches("[a-f0-9]{32}") ? text : null;
    }

    private static String scalar(JSONObject row, String key) {
        return scalarValue(row.opt(key));
    }

    private static String scalarValue(Object value) {
        return value instanceof String || value instanceof Number ? String.valueOf(value) : "";
    }

    private static void positive(String value) throws IOException {
        if (!value.matches("[1-9][0-9]{0,15}")) throw error("identity");
    }

    private static String md5(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(32);
            for (byte part : digest)
                result.append(Character.forDigit((part >>> 4) & 0xf, 16))
                    .append(Character.forDigit(part & 0xf, 16));
            return result.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String query(Map<String, String> params) {
        StringBuilder value = new StringBuilder();
        for (Map.Entry<String, String> entry : params.entrySet()) {
            if (value.length() > 0) value.append('&');
            value.append(encode(entry.getKey())).append('=').append(encode(entry.getValue()));
        }
        return value.toString();
    }

    private static String encode(String value) {
        try {
            return URLEncoder.encode(value, "UTF-8");
        } catch (java.io.UnsupportedEncodingException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static long now() {
        return System.currentTimeMillis() / 1000;
    }

    private static KugouApi.ApiException error(String code) {
        return new KugouApi.ApiException(code, "Kugou authorized audio unavailable");
    }

    private static JSONObject executeHttps(Request request) throws IOException {
        validateEndpoint(request.method, request.endpoint);
        HttpURLConnection connection = null;
        try {
            URL url = new URL(request.endpoint + "?" + query(request.params));
            connection = (HttpURLConnection) url.openConnection();
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(TIMEOUT_MS);
            connection.setReadTimeout(TIMEOUT_MS);
            connection.setUseCaches(false);
            connection.setRequestMethod(request.method);
            connection.setRequestProperty("Accept", "application/json");
            connection.setRequestProperty("Accept-Encoding", "identity");
            for (Map.Entry<String, String> header : request.headers.entrySet())
                connection.setRequestProperty(header.getKey(), header.getValue());
            if (request.body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] body = request.body.getBytes(StandardCharsets.UTF_8);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(body);
                }
            }
            int status = connection.getResponseCode();
            String risk = connection.getHeaderField("ssa-code");
            if (risk != null && !risk.isEmpty() && !"0".equals(risk)) throw error("risk");
            if (status >= 300 && status < 400) throw error("redirect");
            if (status < 200 || status >= 300)
                throw new KugouApi.ApiException(status == 429 ? "rate" : "http",
                    "Kugou authorized endpoint rejected the request", "http_status_" + status);
            if (connection.getContentLengthLong() > RESPONSE_LIMIT) throw error("protocol");
            try (InputStream in = connection.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw error("cancelled");
                    if (count > RESPONSE_LIMIT - out.size()) throw error("protocol");
                    out.write(buffer, 0, count);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } catch (KugouApi.ApiException safe) {
            throw safe;
        } catch (java.net.SocketTimeoutException timeout) {
            throw error("timeout");
        } catch (IOException network) {
            throw new KugouApi.ApiException("network", "Kugou authorized endpoint unavailable");
        } catch (org.json.JSONException malformed) {
            throw error("protocol");
        } finally {
            if (connection != null) connection.disconnect();
        }
    }
}
