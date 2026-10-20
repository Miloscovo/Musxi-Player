package tech.musxi.neteaseloginpoc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Phone-local read-only API, adapted from the pinned MIT vendor modules. Call off the UI thread.
 * song_url_v1 explicitly selects the vendor's supported EAPI override, not default XEAPI.
 * No alternate source, decryption of protected media, or permission bypass is implemented.
 */
public final class NeteaseLibraryApi {
    private final NeteaseApi api;
    public NeteaseLibraryApi(NeteaseApi api) { this.api = api; }

    public static final class Page<T> {
        public final List<T> items;
        public final int nextOffset; // -1 means complete
        public final int total; // -1 means unknown
        Page(List<T> items, int nextOffset, int total) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.nextOffset = nextOffset; this.total = total;
        }
    }
    public static final class Playlist {
        public final String id, name, coverUrl;
        public final int trackCount;
        Playlist(String id, String name, String coverUrl, int trackCount) {
            this.id = id; this.name = name; this.coverUrl = coverUrl; this.trackCount = trackCount;
        }
    }
    public static final class Track {
        public final String id, name, album;
        public final List<String> artists;
        public final long durationMs;
        public final boolean available;
        Track(String id, String name, String album, List<String> artists, long durationMs, boolean available) {
            this.id = id; this.name = name; this.album = album;
            this.artists = Collections.unmodifiableList(new ArrayList<>(artists));
            this.durationMs = durationMs; this.available = available;
        }
    }
    /** An expiring capability; intentionally has no revealing toString or serialization method. */
    public static final class Audio {
        public final String id, url;
        public final int bitrate;
        public final long expiresAtElapsedMs;
        Audio(String id, String url, int bitrate, long expiresAtElapsedMs) {
            this.id = id; this.url = url; this.bitrate = bitrate; this.expiresAtElapsedMs = expiresAtElapsedMs;
        }
    }

    public Page<Playlist> playlists(NeteaseApi.Session session, int offset, int limit) throws IOException {
        pageBounds(offset, limit);
        JSONObject body = api.authenticatedCall(NeteaseApi.USER_PLAYLIST,
                json("uid", session.userId, "limit", limit, "offset", offset, "includeVideo", true), session);
        return parsePlaylists(body, offset, limit);
    }

    public Page<Track> tracks(NeteaseApi.Session session, String playlistId, int offset, int limit) throws IOException {
        pageBounds(offset, limit); id(playlistId);
        // Fetch the ID order, then request just this page's song details. Avoid a huge track payload.
        List<String> ids = trackIds(session, playlistId);
        if (offset >= ids.size()) return new Page<>(Collections.emptyList(), -1, ids.size());
        int end = Math.min(ids.size(), offset + limit);
        List<String> selected = ids.subList(offset, end);
        JSONArray c = new JSONArray();
        for (String value : selected) c.put(json("id", Long.parseLong(value)));
        JSONObject songs = api.authenticatedCall(NeteaseApi.SONG_DETAIL, json("c", c.toString()), session);
        return new Page<>(parseTracks(songs, selected), end < ids.size() ? end : -1, ids.size());
    }

    /** Full ordered ID snapshot without eagerly requesting every song's details or stream. */
    public List<String> trackIds(NeteaseApi.Session session, String playlistId) throws IOException {
        id(playlistId);
        JSONObject detail = api.authenticatedCall(NeteaseApi.PLAYLIST_DETAIL,
                json("id", playlistId, "n", 0, "s", 0), session);
        return Collections.unmodifiableList(new ArrayList<>(parseTrackIds(detail, playlistId)));
    }

    public Track song(NeteaseApi.Session session, String trackId) throws IOException {
        id(trackId);
        JSONArray c = new JSONArray().put(json("id", Long.parseLong(trackId)));
        JSONObject songs = api.authenticatedCall(NeteaseApi.SONG_DETAIL, json("c", c.toString()), session);
        Track track = parseTracks(songs, Collections.singletonList(trackId)).get(0);
        if (!track.available) throw error("unavailable");
        return track;
    }

    /** First song-search page only; metadata never resolves a stream or enters a persistent cache.
     * Pinned module/cloudsearch.js: /api/cloudsearch/pc, using the existing EAPI transport.
     */
    public List<Track> search(NeteaseApi.Session session, String keywords) throws IOException {
        if (keywords == null) throw error("request");
        String query = keywords.replaceAll("[\\p{Cc}]", "").trim();
        if (query.isEmpty() || query.length() > 128) throw error("request");
        JSONObject body = api.authenticatedCall(NeteaseApi.SEARCH,
                json("s", query, "type", 1, "limit", 10, "offset", 0, "total", true), session);
        return parseSearch(body);
    }

    public Audio audio(NeteaseApi.Session session, String trackId, long nowElapsedMs) throws IOException {
        id(trackId);
        JSONObject body = api.authenticatedCall(NeteaseApi.SONG_URL,
                json("ids", "[" + trackId + "]", "level", "standard", "encodeType", "flac"), session);
        return parseAudio(body, trackId, nowElapsedMs);
    }

    static Page<Playlist> parsePlaylists(JSONObject body, int offset, int limit) throws IOException {
        pageBounds(offset, limit);
        JSONArray rows = body.optJSONArray("playlist");
        if (rows == null || rows.length() > limit || !(body.opt("more") instanceof Boolean)) throw error("protocol");
        List<Playlist> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject p = rows.optJSONObject(i);
            if (p == null) throw error("protocol");
            String key = id(p.opt("id"));
            if (!seen.add(key)) throw error("protocol");
            out.add(new Playlist(key, name(p.opt("name"), "未命名歌单"), image(p.opt("coverImgUrl")),
                    (int) nonNegative(p.opt("trackCount"), 1_000_000)));
        }
        boolean more = body.optBoolean("more");
        if (more && rows.length() == 0) throw error("protocol");
        return new Page<>(out, more ? offset + rows.length() : -1, -1);
    }

    static List<String> parseTrackIds(JSONObject body, String playlistId) throws IOException {
        JSONObject p = body.optJSONObject("playlist");
        if (p == null || !id(p.opt("id")).equals(id(playlistId))) throw error("identity");
        JSONArray rows = p.optJSONArray("trackIds");
        if (rows == null || rows.length() > 20_000) throw error("limit");
        long count = nonNegative(p.opt("trackCount"), 1_000_000);
        if (count != rows.length()) throw error("incomplete");
        List<String> ids = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw error("protocol");
            String key = id(row.opt("id"));
            if (!seen.add(key)) throw error("protocol");
            ids.add(key);
        }
        return ids;
    }

    static List<Track> parseTracks(JSONObject body, List<String> selected) throws IOException {
        JSONArray rows = body.optJSONArray("songs");
        if (rows == null || rows.length() > selected.size()) throw error("protocol");
        Map<String,Track> out = new LinkedHashMap<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw error("protocol");
            String key = id(row.opt("id"));
            if (!selected.contains(key) || out.containsKey(key)) throw error("identity");
            List<String> artists = new ArrayList<>();
            JSONArray ar = row.optJSONArray("ar");
            if (ar == null || ar.length() > 100) throw error("protocol");
            for (int j = 0; j < ar.length(); j++) {
                JSONObject artist = ar.optJSONObject(j);
                if (artist == null) throw error("protocol");
                artists.add(name(artist.opt("name"), "未知歌手"));
            }
            JSONObject album = row.optJSONObject("al");
            if (album == null) throw error("protocol");
            out.put(key, new Track(key, name(row.opt("name"), "未命名歌曲"), name(album.opt("name"), "未知专辑"),
                    artists, nonNegative(row.opt("dt"), 86_400_000), true));
        }
        List<Track> ordered = new ArrayList<>();
        for (String key : selected) ordered.add(out.containsKey(key) ? out.get(key)
                : new Track(key, "暂不可用歌曲", "", Collections.emptyList(), 0, false));
        return ordered;
    }

    static List<Track> parseSearch(JSONObject body) throws IOException {
        JSONObject result = body.optJSONObject("result");
        if (result == null) throw error("protocol");
        long count = nonNegative(result.opt("songCount"), Integer.MAX_VALUE);
        // An empty result may omit songs; positive counts must include song rows.
        if (!result.has("songs") && count == 0) return Collections.emptyList();
        JSONArray rows = result.optJSONArray("songs");
        if (rows == null) throw error("protocol");
        if (rows.length() > 10) throw error("limit");
        if (rows.length() > count || (rows.length() == 0 && count > 0)) throw error("protocol");
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw error("protocol");
            selected.add(id(row.opt("id")));
        }
        return Collections.unmodifiableList(parseTracks(result, selected));
    }

    static Audio parseAudio(JSONObject body, String trackId, long nowElapsedMs) throws IOException {
        id(trackId);
        JSONArray rows = body.optJSONArray("data");
        if (rows == null || rows.length() != 1) throw error("protocol");
        JSONObject row = rows.optJSONObject(0);
        if (row == null || !id(row.opt("id")).equals(trackId)) throw error("identity");
        if (row.has("freeTrialInfo") && !row.isNull("freeTrialInfo")) throw error("permission");
        if (row.optInt("code", -1) != 200 || row.isNull("url")) throw error("unavailable");
        String url = mediaUrl(row.opt("url"));
        int bitrate = (int) nonNegative(row.opt("br"), 10_000_000);
        if (bitrate == 0) throw error("unavailable");
        long expires = nonNegative(row.opt("expi"), 86_400);
        if (expires == 0 || nowElapsedMs < 0 || nowElapsedMs > Long.MAX_VALUE - expires * 1000L) throw error("protocol");
        return new Audio(trackId, url, bitrate, nowElapsedMs + expires * 1000L);
    }

    private static String mediaUrl(Object value) throws IOException {
        if (!(value instanceof String) || ((String)value).length() > 8192) throw error("protocol");
        try {
            URI u = new URI((String)value);
            String host = u.getHost();
            if (host != null) host = host.toLowerCase(java.util.Locale.ROOT);
            String authority = u.getRawAuthority();
            if (authority != null) authority = authority.toLowerCase(java.util.Locale.ROOT);
            if (host == null || u.getRawUserInfo() != null || u.getFragment() != null
                    || (u.getPort() != -1 && u.getPort() != 443)
                    || (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme()))
                    || !(host.endsWith(".music.126.net") || host.endsWith(".music.163.com"))
                    || !(host.equals(authority) || (host + ":443").equals(authority))) throw error("endpoint");
            for (String label : host.split("\\.", -1))
                if (!label.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?")) throw error("endpoint");
            // The platform sometimes returns HTTP CDN URLs. Only upgrade to TLS, never allow cleartext.
            return "http".equals(u.getScheme()) ? "https" + ((String)value).substring(4) : (String)value;
        } catch (java.net.URISyntaxException invalid) { throw error("protocol"); }
    }
    private static String image(Object value) {
        if (!(value instanceof String)) return null;
        try { return mediaUrl(value); } catch (IOException ignored) { return null; }
    }
    private static String id(Object value) throws IOException {
        if (!(value instanceof String || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger)) throw error("protocol");
        String s = value.toString();
        if (!s.matches("[1-9][0-9]{0,18}")) throw error("protocol");
        try { if (Long.parseLong(s) <= 0) throw error("protocol"); } catch (NumberFormatException invalid) { throw error("protocol"); }
        return s;
    }
    private static long nonNegative(Object value, long max) throws IOException {
        if (!(value instanceof Integer || value instanceof Long)) throw error("protocol");
        long n = ((Number)value).longValue();
        if (n < 0 || n > max) throw error("protocol");
        return n;
    }
    private static String name(Object value, String fallback) throws IOException {
        if (!(value instanceof String)) throw error("protocol");
        String s = ((String)value).replaceAll("[\\p{Cntrl}]", " ").trim();
        return s.isEmpty() ? fallback : s.substring(0, Math.min(500, s.length()));
    }
    private static void pageBounds(int offset, int limit) throws IOException {
        if (offset < 0 || offset > 100_000 || limit < 1 || limit > 100) throw error("request");
    }
    private static JSONObject json(Object... values) throws IOException {
        JSONObject out = new JSONObject();
        try { for (int i = 0; i < values.length; i += 2) out.put((String)values[i], values[i + 1]); }
        catch (JSONException invalid) { throw error("protocol"); }
        return out;
    }
    private static NeteaseApi.ApiException error(String code) {
        // Fixed messages only. No upstream body, URL, cookie, account ID or JSON exception cause.
        return new NeteaseApi.ApiException(code, "网易云音乐库请求未完成（" + code + "）");
    }
}
