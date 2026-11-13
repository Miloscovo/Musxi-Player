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

/** Phone-local library API, adapted from the pinned MIT vendor modules. Call off the UI thread.
 * song_url_v1 explicitly selects the vendor's supported EAPI override, not default XEAPI.
 * No alternate source, decryption of protected media, or permission bypass is implemented.
 */
public final class NeteaseLibraryApi {
    private final NeteaseApi api;
    private final java.util.function.BooleanSupplier current;
    public NeteaseLibraryApi(NeteaseApi api) { this(api,()->true); }
    public NeteaseLibraryApi(NeteaseApi api,java.util.function.BooleanSupplier current) { this.api=api;this.current=current; }
    private void requireCurrent() throws IOException { if(!current.getAsBoolean() || Thread.currentThread().isInterrupted())throw error("account_changed"); }

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
        public enum Kind { LIKED, CREATED, COLLECTED, OTHER }
        public final String id, name, coverUrl;
        public final int trackCount;
        public final boolean liked, writable;
        public final Kind kind;
        Playlist(String id, String name, String coverUrl, int trackCount) {
            this(id, name, coverUrl, trackCount, false, false, Kind.OTHER);
        }
        Playlist(String id, String name, String coverUrl, int trackCount, boolean liked, boolean writable) {
            this(id, name, coverUrl, trackCount, liked, writable,
                    liked ? Kind.LIKED : writable ? Kind.CREATED : Kind.OTHER);
        }
        Playlist(String id, String name, String coverUrl, int trackCount, boolean liked, boolean writable, Kind kind) {
            this.id = id; this.name = name; this.coverUrl = coverUrl; this.trackCount = trackCount;
            this.liked = liked; this.writable = writable;
            this.kind = kind;
        }
    }
    public static final class Track {
        public final String id, name, album, coverUrl;
        public final List<String> artists;
        public final List<String> artistIds;
        public final String albumId;
        public final long durationMs;
        public final boolean available;
        Track(String id, String name, String album, List<String> artists, long durationMs, boolean available) {
            this(id, name, album, artists, durationMs, available, null, Collections.emptyList(), null);
        }
        Track(String id, String name, String album, List<String> artists, long durationMs, boolean available, String coverUrl) {
            this(id, name, album, artists, durationMs, available, coverUrl, Collections.emptyList(), null);
        }
        Track(String id, String name, String album, List<String> artists, long durationMs, boolean available,
              String coverUrl, List<String> artistIds, String albumId) {
            this.id = id; this.name = name; this.album = album;
            this.coverUrl = coverUrl;
            this.artists = Collections.unmodifiableList(new ArrayList<>(artists));
            this.artistIds = Collections.unmodifiableList(new ArrayList<>(artistIds));
            this.albumId = albumId;
            this.durationMs = durationMs; this.available = available;
        }
    }
    /** An expiring capability; intentionally has no revealing toString or serialization method. */
    public static final class Audio {
        public final String id, url, qualityId;
        public final int bitrate;
        public final long expiresAtElapsedMs;
        Audio(String id, String url, int bitrate, long expiresAtElapsedMs, String qualityId) {
            this.id = id; this.url = url; this.bitrate = bitrate; this.expiresAtElapsedMs = expiresAtElapsedMs; this.qualityId=qualityId;
        }
    }
    public static final class AudioQuality {
        public final String id, name;
        AudioQuality(String id,String name){this.id=id;this.name=name;}
    }
    public enum TranslationStatus { PRESENT, ABSENT, INVALID }
    public static final class LyricsDocument {
        public final String original;
        public final String translation;
        public final TranslationStatus translationStatus;
        public final YrcLyricsDecoder.Document wordTimings;
        public final int timingRevision;
        LyricsDocument(String original, String translation, TranslationStatus translationStatus) {
            this(original,translation,translationStatus,YrcLyricsDecoder.unavailable(YrcLyricsDecoder.Status.ABSENT),0);
        }
        LyricsDocument(String original, String translation, TranslationStatus translationStatus,
                YrcLyricsDecoder.Document words,int revision) {
            this.original = original;
            this.translation = translation;
            this.translationStatus = translationStatus;
            this.wordTimings=words;this.timingRevision=revision;
        }
        @Override public String toString() { return "LyricsDocument{redacted}"; }
    }

    public Page<Playlist> playlists(NeteaseApi.Session session, int offset, int limit) throws IOException {
        pageBounds(offset, limit);
        JSONObject body = api.authenticatedCall(NeteaseApi.USER_PLAYLIST,
                json("uid", session.userId, "limit", limit, "offset", offset, "includeVideo", true), session);
        return parsePlaylists(body, offset, limit, session.userId);
    }

    /** Current-account playlists whose server creator.userId matches this session.
     * The list includes the account's specialType=5 liked-songs playlist. */
    public List<Playlist> writablePlaylists(NeteaseApi.Session session) throws IOException {
        if (session == null) throw error("credentials");
        LinkedHashMap<String, Playlist> out = new LinkedHashMap<>();
        int offset = 0;
        boolean foundLiked = false;
        while (true) {
            requireCurrent();
            JSONObject body = api.authenticatedCall(NeteaseApi.USER_PLAYLIST,
                    json("uid", session.userId, "limit", 100, "offset", offset, "includeVideo", true), session);
            List<Playlist> page = parseWritablePlaylistPage(body, session.userId);
            JSONArray rows = body.optJSONArray("playlist");
            for (Playlist value : page) {
                if (out.putIfAbsent(value.id, value) != null) throw error("protocol");
                foundLiked |= value.liked;
                if (out.size() > 20_000) throw error("limit");
            }
            boolean more = body.optBoolean("more");
            if (more && rows.length() == 0) throw error("protocol");
            if (!more) break;
            offset += rows.length();
            if (offset > 100_000) throw error("limit");
        }
        if (!foundLiked) throw error("incomplete");
        requireCurrent();
        return Collections.unmodifiableList(new ArrayList<>(out.values()));
    }

    /** Add one Netease song to an owned playlist. Liked songs use the pinned like endpoint;
     * custom lists use the pinned playlist_tracks EAPI payload and are rechecked via detail. */
    public void addToPlaylist(NeteaseApi.Session session, String playlistId, String songId) throws IOException {
        if (session == null) throw error("credentials");
        id(playlistId); id(songId);
        requireCurrent();
        JSONObject detail = api.authenticatedCall(NeteaseApi.PLAYLIST_DETAIL,
                json("id", playlistId, "n", 0, "s", 0), session);
        JSONObject playlist = requireOwnedPlaylist(detail, playlistId, session.userId);
        requireCurrent();
        if (nonNegative(playlist.opt("specialType"), 1_000_000) == 5) {
            setLiked(session, songId, true);
            return;
        }
        JSONArray tracks = new JSONArray().put(songId);
        api.authenticatedCall(NeteaseApi.PLAYLIST_TRACKS,
                json("op", "add", "pid", playlistId, "trackIds", tracks.toString(), "imme", "true"), session);
    }

    /** Remove one Netease song from an owned playlist. Ownership is rechecked on the server
     * response before the write, the account is rechecked again, and the liked-songs list
     * is routed to the pinned like endpoint with like=false. */
    public void removeFromPlaylist(NeteaseApi.Session session, String playlistId, String songId)
            throws IOException {
        if (session == null) throw error("credentials");
        id(playlistId); id(songId);
        requireCurrent();
        JSONObject detail = api.authenticatedCall(NeteaseApi.PLAYLIST_DETAIL,
                json("id", playlistId, "n", 0, "s", 0), session);
        JSONObject playlist = requireOwnedPlaylist(detail, playlistId, session.userId);
        requireCurrent();
        if (nonNegative(playlist.opt("specialType"), 1_000_000) == 5) {
            setLiked(session, songId, false);
            return;
        }
        JSONArray tracks = new JSONArray().put(songId);
        api.authenticatedCall(NeteaseApi.PLAYLIST_TRACKS,
                json("op", "del", "pid", playlistId, "trackIds", tracks.toString(), "imme", "true"), session);
    }

    /** Create one normal playlist for the current account. Pinned module/playlist_create.js
     * uses the WEAPI profile with privacy=0 and type=NORMAL. The new id must come from the
     * response and is then re-read through detail so the server confirms the owner; no
     * name-based lookup is used. */
    public String createPlaylist(NeteaseApi.Session session, String name) throws IOException {
        if (session == null) throw error("credentials");
        JSONObject payload = createPlaylistPayload(name);
        requireCurrent();
        JSONObject created = api.authenticatedCall(NeteaseApi.PLAYLIST_CREATE, payload, session);
        String playlistId = createdPlaylistId(created);
        requireCurrent();
        JSONObject detail = api.authenticatedCall(NeteaseApi.PLAYLIST_DETAIL,
                json("id", playlistId, "n", 0, "s", 0), session);
        requireOwnedPlaylist(detail, playlistId, session.userId);
        // Never hand back an id that was confirmed for an account that is already replaced.
        requireCurrent();
        return playlistId;
    }

    /** Rename one owned normal playlist. Pinned module/playlist_name_update.js passes no
     * explicit profile, and an empty crypto resolves to EAPI while APP_CONF.encrypt stays
     * true, so this write is EAPI. The liked-songs list is rejected, and the new name is
     * confirmed by re-reading detail after the write. */
    public void renamePlaylist(NeteaseApi.Session session, String playlistId, String name)
            throws IOException {
        if (session == null) throw error("credentials");
        id(playlistId);
        JSONObject payload = renamePlaylistPayload(playlistId, name);
        String title = payload.optString("name");
        requireCurrent();
        JSONObject detail = api.authenticatedCall(NeteaseApi.PLAYLIST_DETAIL,
                json("id", playlistId, "n", 0, "s", 0), session);
        JSONObject playlist = requireOwnedPlaylist(detail, playlistId, session.userId);
        if (ownedSpecialType(playlist) != 0) throw error("permission");
        requireCurrent();
        api.authenticatedCall(NeteaseApi.PLAYLIST_RENAME, payload, session);
        requireCurrent();
        JSONObject confirm = api.authenticatedCall(NeteaseApi.PLAYLIST_DETAIL,
                json("id", playlistId, "n", 0, "s", 0), session);
        JSONObject renamed = requireOwnedPlaylist(confirm, playlistId, session.userId);
        if (!title.equals(renamed.opt("name"))) throw error("protocol");
    }

    public static final class Subscription {
        public final Playlist playlist;
        public final boolean followed;
        Subscription(Playlist playlist,boolean followed) { this.playlist=playlist;this.followed=followed; }
    }

    /** Resolve one own normal playlist for deletion from a complete, fresh account listing.
     * Returns null only when the whole paged listing was read and the id is genuinely absent;
     * a truncated read, a missing creator field, or a lost liked-songs sentinel is an error,
     * never "not found". Owned non-normal and foreign targets are rejected as permission. */
    public Playlist deletablePlaylist(NeteaseApi.Session session, String playlistId) throws IOException {
        if (session == null) throw error("credentials");
        String target = id(playlistId);
        requireCurrent();
        List<JSONObject> rows = accountPlaylistRows(session);
        JSONObject match = null;
        boolean foundLiked = false;
        for (JSONObject row : rows) {
            String rowId = id(row.opt("id"));
            JSONObject creator = row.optJSONObject("creator");
            if (creator != null && session.userId.equals(optionalOwnerId(creator))
                    && ownedSpecialType(row) == 5)
                foundLiked = true;
            if (target.equals(rowId)) match = row;
        }
        // Every account listing carries its liked-songs list; without it the read is incomplete.
        if (!foundLiked) throw error("incomplete");
        requireCurrent();
        if (match == null) return null;
        if (!session.userId.equals(requiredCreatorId(match))) throw error("permission");
        // specialType 5 is the liked-songs list and every other non-zero value is a
        // system-managed list; only a plain specialType 0 playlist may be deleted.
        if (ownedSpecialType(match) != 0) throw error("permission");
        return new Playlist(id(match.opt("id")), name(match.opt("name"), "未命名歌单"),
                image(match.opt("coverImgUrl")), (int) nonNegative(match.opt("trackCount"), 1_000_000),
                false, true, Playlist.Kind.CREATED);
    }

    /** Delete one own normal playlist. The target is re-verified against a fresh complete
     * listing immediately before the single pinned WEAPI write; an absent id writes nothing.
     * There is no retry and no post-write read-back, because the caller reconciles afterwards. */
    public void deletePlaylist(NeteaseApi.Session session, String playlistId) throws IOException {
        if (session == null) throw error("credentials");
        String target = id(playlistId);
        JSONObject payload = deletePlaylistPayload(target);
        requireCurrent();
        if (deletablePlaylist(session, target) == null) return;
        requireCurrent();
        api.authenticatedCall(NeteaseApi.PLAYLIST_DELETE, payload, session);
    }

    /** Complete account listing by server paging. Any non-terminal, duplicated or oversized
     * page set is rejected instead of being treated as a shorter account. */
    private List<JSONObject> accountPlaylistRows(NeteaseApi.Session session) throws IOException {
        List<JSONObject> rows = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        int offset = 0;
        while (true) {
            requireCurrent();
            JSONObject body = api.authenticatedCall(NeteaseApi.USER_PLAYLIST,
                    json("uid", session.userId, "limit", 100, "offset", offset, "includeVideo", true), session);
            JSONArray page = body.optJSONArray("playlist");
            if (page == null || page.length() > 100 || !(body.opt("more") instanceof Boolean))
                throw error("protocol");
            for (int i = 0; i < page.length(); i++) {
                JSONObject row = page.optJSONObject(i);
                if (row == null) throw error("protocol");
                if (!seen.add(id(row.opt("id")))) throw error("protocol");
                rows.add(row);
                if (rows.size() > 20_000) throw error("limit");
            }
            if (!body.optBoolean("more")) break;
            if (page.length() == 0) throw error("protocol");
            offset += page.length();
            if (offset > 100_000) throw error("limit");
        }
        requireCurrent();
        return rows;
    }

    private static String requiredCreatorId(JSONObject row) throws IOException {
        JSONObject creator = row.optJSONObject("creator");
        if (creator == null) throw error("incomplete");
        String owner = optionalOwnerId(creator);
        // An unreadable creator on the target cannot prove ownership either way.
        if (owner == null) throw error("incomplete");
        return owner;
    }

    private static String optionalOwnerId(JSONObject creator) {
        try {
            return id(creator.opt("userId"));
        } catch (IOException unknownOwner) {
            return null;
        }
    }

    /** An owned row's system-list marker; a missing marker cannot prove a normal playlist. */
    private static int ownedSpecialType(JSONObject row) throws IOException {
        Object value = row.opt("specialType");
        if (value == null || value == JSONObject.NULL) throw error("incomplete");
        // A malformed or out-of-range marker is a protocol error, never a plain playlist.
        return (int) nonNegative(value, 1_000_000);
    }

    /** Pinned module/playlist_delete.js body: one bracketed id string, no checkToken. */
    static JSONObject deletePlaylistPayload(String playlistId) throws IOException {
        return json("ids", "[" + id(playlistId) + "]");
    }

    /** Read the current session's subscription flag, never infer it from one page or a failed read. */
    public Subscription playlistSubscription(NeteaseApi.Session session,String playlistId) throws IOException {
        if(session==null)throw error("credentials");
        id(playlistId); requireCurrent();
        JSONObject detail=api.authenticatedCall(NeteaseApi.PLAYLIST_DETAIL,json("id",playlistId,"n",0,"s",0),session);
        JSONObject row=detail.optJSONObject("playlist");
        if(row==null || !playlistId.equals(id(row.opt("id"))))throw error("identity");
        JSONObject creator=row.optJSONObject("creator");
        if(creator==null)throw error("incomplete");
        if(session.userId.equals(id(creator.opt("userId"))) || nonNegative(row.opt("specialType"),1_000_000)!=0)throw error("permission");
        if(!(row.opt("subscribed") instanceof Boolean))throw error("incomplete");
        int count=(int)nonNegative(row.opt("trackCount"),1_000_000);
        requireCurrent();
        return new Subscription(new Playlist(playlistId,name(row.opt("name"),"未命名歌单"),image(row.opt("coverImgUrl")),
            count,false,false,Playlist.Kind.COLLECTED),row.optBoolean("subscribed"));
    }
    /** Explicit desired subscription. The facade performs a further read even after a lost response. */
    public void setPlaylistFollowed(NeteaseApi.Session session,String playlistId,boolean followed) throws IOException {
        Subscription before=playlistSubscription(session,playlistId);
        if(before.followed==followed)return;
        requireCurrent();
        api.setPlaylistFollowed(session,playlistId,followed,current);
        requireCurrent();
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

    /** Pinned module/artist_songs.js: /api/v1/artist/songs, preserving server pagination. */
    public Page<Track> artistTracks(NeteaseApi.Session session, String artistId, int offset, int limit)
            throws IOException {
        pageBounds(offset, limit); id(artistId);
        JSONObject body = api.authenticatedCall(NeteaseApi.ARTIST_TRACKS,
                json("id", artistId, "private_cloud", "true", "work_type", 1,
                        "order", "hot", "offset", offset, "limit", limit), session);
        return parseArtistTracks(body, artistId, offset, limit);
    }

    /** Pinned module/album.js: /api/v1/album/{id}; the endpoint returns the complete album. */
    public Page<Track> albumTracks(NeteaseApi.Session session, String albumId, int offset, int limit)
            throws IOException {
        pageBounds(offset, limit); id(albumId);
        JSONObject body = api.authenticatedCall(NeteaseApi.ALBUM_TRACKS + albumId, new JSONObject(), session);
        return parseAlbumTracks(body, albumId, offset, limit);
    }

    /** First song-search page only; metadata never resolves a stream or enters a persistent cache.
     * Pinned module/cloudsearch.js: /api/cloudsearch/pc, using the existing EAPI transport.
     */
    public List<Track> search(NeteaseApi.Session session, String keywords) throws IOException {
        return parseSearch(searchResponse(session, keywords, 0, 10));
    }

    public Page<Track> search(NeteaseApi.Session session, String keywords, int offset, int limit) throws IOException {
        searchBounds(offset, limit);
        return parseSearch(searchResponse(session, keywords, offset, limit), offset, limit);
    }

    private JSONObject searchResponse(NeteaseApi.Session session, String keywords, int offset, int limit) throws IOException {
        if (keywords == null) throw error("request");
        String query = keywords.replaceAll("[\\p{Cc}]", "").trim();
        if (query.isEmpty() || query.length() > 128) throw error("request");
        return api.authenticatedCall(NeteaseApi.SEARCH,
                json("s", query, "type", 1, "limit", limit, "offset", offset, "total", true), session);
    }

    public Audio audio(NeteaseApi.Session session, String trackId, long nowElapsedMs) throws IOException {
        return audio(session,trackId,nowElapsedMs,"netease:standard");
    }

    public Audio audio(NeteaseApi.Session session, String trackId, long nowElapsedMs, String qualityId) throws IOException {
        id(trackId);
        String level=levelForQuality(qualityId);
        JSONObject body = api.authenticatedCall(NeteaseApi.SONG_URL,
                json("ids", "[" + trackId + "]", "level", level, "encodeType", "flac"), session);
        return parseAudio(body, trackId, nowElapsedMs, qualityId);
    }

    public List<AudioQuality> qualities(NeteaseApi.Session session,String trackId,long nowElapsedMs) throws IOException {
        java.util.LinkedHashMap<String,AudioQuality> options=new java.util.LinkedHashMap<>();
        for(String id:new String[]{"netease:standard","netease:higher","netease:exhigh","netease:lossless","netease:hires"}) {
            try {
                Audio audio=audio(session,trackId,nowElapsedMs,id);
                options.putIfAbsent(audio.qualityId,new AudioQuality(audio.qualityId,qualityName(audio.qualityId)));
            } catch(NeteaseApi.ApiException unavailable) {
                if(!java.util.Arrays.asList("permission","membership","copyright","preview","unavailable").contains(unavailable.code))throw unavailable;
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(options.values()));
    }

    private static String levelForQuality(String id) throws IOException {
        switch(id) {
            case "netease:standard": return "standard";
            case "netease:higher": return "higher";
            case "netease:exhigh": return "exhigh";
            case "netease:lossless": return "lossless";
            case "netease:hires": return "hires";
            default: throw error("request");
        }
    }

    private static String qualityIdForBitrate(int bitrate) {
        if(bitrate<=128_000)return "netease:standard";
        if(bitrate<=192_000)return "netease:higher";
        if(bitrate<=320_000)return "netease:exhigh";
        return "netease:lossless";
    }

    private static String qualityIdForResponse(JSONObject row, int bitrate) throws IOException {
        Object value=row.opt("level");
        if(value==null || value==JSONObject.NULL) {
            String legacy=qualityIdForBitrate(bitrate);
            if(legacy.equals("netease:lossless") && !isFlac(row)) throw error("unavailable");
            return legacy;
        }
        if(!(value instanceof String))throw error("protocol");
        String level=((String)value).trim().toLowerCase(java.util.Locale.ROOT);
        switch(level) {
            case "standard": return "netease:standard";
            case "higher": return "netease:higher";
            case "exhigh": return "netease:exhigh";
            case "lossless":
                if(!isFlac(row))throw error("unavailable");
                return "netease:lossless";
            case "hires":
                if(!isFlac(row))throw error("unavailable");
                // The v1 endpoint's level is authoritative. When sampling data is
                // present, require one Hi-Res indicator so ordinary 16-bit/44.1 kHz
                // FLAC cannot be relabelled as Hi-Res.
                return hasConfirmedHiResSampling(row) ? "netease:hires" : "netease:lossless";
            default: throw error("unavailable");
        }
    }

    private static boolean isFlac(JSONObject row) {
        return "flac".equalsIgnoreCase(row.optString("type",""));
    }

    private static boolean hasConfirmedHiResSampling(JSONObject row) throws IOException {
        Long sampleRate=optionalPositiveLong(row,"sampleRate","samplingRate","sample_rate","sr");
        Long bitDepth=optionalPositiveLong(row,"bitDepth","bit_depth","bitsPerSample");
        if(sampleRate==null && bitDepth==null)return true;
        return (sampleRate!=null && sampleRate>48_000) || (bitDepth!=null && bitDepth>16);
    }

    private static Long optionalPositiveLong(JSONObject row, String... keys) throws IOException {
        for(String key:keys) {
            Object value=row.opt(key);
            if(value==null || value==JSONObject.NULL)continue;
            if(!(value instanceof Number))throw error("protocol");
            double asDouble=((Number)value).doubleValue();
            long asLong=((Number)value).longValue();
            if(asDouble!=asLong || asLong<=0)throw error("protocol");
            return asLong;
        }
        return null;
    }

    private static String qualityName(String id) {
        switch(id) {
            case "netease:standard": return "标准音质";
            case "netease:higher": return "较高音质";
            case "netease:exhigh": return "极高音质";
            case "netease:lossless": return "无损音质";
            case "netease:hires": return "Hi-Res 音质";
            default: return "";
        }
    }

    /** Standard LRC only, using the pinned module/lyric.js EAPI request. */
    public String lyrics(NeteaseApi.Session session, String trackId) throws IOException {
        return lyricsDocument(session, trackId).original;
    }

    /** Fetch standard lyrics and an optional translation with one pinned LYRIC request. */
    public LyricsDocument lyricsDocument(NeteaseApi.Session session, String trackId) throws IOException {
        id(trackId);
        JSONObject body = api.authenticatedCall(NeteaseApi.LYRIC,
                json("id", trackId, "tv", -1, "lv", -1, "rv", -1, "kv", -1, "_nmclfl", 1), session);
        return parseLyricsDocument(body);
    }

    /** Native YRC and its matching ytlrc timeline, with ordinary LRC retained as a fallback. */
    public LyricsDocument lyricsTimedDocument(NeteaseApi.Session session,String trackId) throws IOException {
        id(trackId);requireCurrent();
        try {
            JSONObject body=api.authenticatedCall(NeteaseApi.LYRIC_NEW,
                json("id",trackId,"cp",false,"tv",0,"lv",0,"rv",0,"kv",0,"yv",0,"ytv",0,"yrv",0),session);
            requireCurrent();
            return parseTimedLyricsDocument(body);
        }catch(NeteaseApi.ApiException failure){
            if(java.util.Arrays.asList("credentials","expired","identity","account_changed").contains(failure.code))throw failure;
            requireCurrent();
            LyricsDocument fallback=lyricsDocument(session,trackId);
            return withTimings(fallback,YrcLyricsDecoder.unavailable("network".equals(failure.code)?
                YrcLyricsDecoder.Status.NETWORK:YrcLyricsDecoder.Status.PLATFORM_ERROR),0);
        }catch(IOException failure){
            requireCurrent();
            return withTimings(lyricsDocument(session,trackId),YrcLyricsDecoder.unavailable(YrcLyricsDecoder.Status.NETWORK),0);
        }
    }

    static LyricsDocument parseTimedLyricsDocument(JSONObject body) throws IOException {
        if(body==null || NeteaseApi.responseCode(body)!=200)throw error("protocol");
        for(String flag:new String[]{"nolyric","uncollected"})
            if(body.has(flag) && !(body.opt(flag) instanceof Boolean))throw error("protocol");
        if(Boolean.TRUE.equals(body.opt("nolyric")) || Boolean.TRUE.equals(body.opt("uncollected")))
            return withTimings(new LyricsDocument("",null,TranslationStatus.ABSENT),
                YrcLyricsDecoder.unavailable(YrcLyricsDecoder.Status.ABSENT),1);
        Object raw=body.opt("yrc");
        YrcLyricsDecoder.Document words;
        if(raw==null || raw==JSONObject.NULL)words=YrcLyricsDecoder.unavailable(YrcLyricsDecoder.Status.ABSENT);
        else if(!(raw instanceof JSONObject))words=YrcLyricsDecoder.unavailable(YrcLyricsDecoder.Status.INVALID);
        else {
            Object lyric=((JSONObject)raw).opt("lyric");
            words=lyric==null || lyric==JSONObject.NULL?YrcLyricsDecoder.unavailable(YrcLyricsDecoder.Status.ABSENT):
                lyric instanceof String?YrcLyricsDecoder.decode((String)lyric):YrcLyricsDecoder.unavailable(YrcLyricsDecoder.Status.INVALID);
        }
        if(words.status==YrcLyricsDecoder.Status.PRESENT)
            return withTimings(parseTranslation(words.original,body,"ytlrc"),words,1);
        return withTimings(parseLyricsDocument(body),words,words.status==YrcLyricsDecoder.Status.ABSENT?1:0);
    }
    private static LyricsDocument withTimings(LyricsDocument doc,YrcLyricsDecoder.Document words,int revision){
        return new LyricsDocument(doc.original,doc.translation,doc.translationStatus,words,revision);
    }

    /** Read the pinned module/likelist.js EAPI snapshot for the current session account. */
    public List<String> likedIds(NeteaseApi.Session session) throws IOException {
        if (session == null) throw error("credentials");
        JSONObject body = api.authenticatedCall(NeteaseApi.LIKED_IDS, json("uid", session.userId), session);
        return parseLikedIds(body);
    }

    /** Submit one pinned module/like.js EAPI change; callers re-read likedIds to confirm it. */
    public void setLiked(NeteaseApi.Session session, String songId, boolean liked) throws IOException {
        id(songId);
        api.authenticatedCall(NeteaseApi.SET_LIKE,
                json("alg", "itembased", "trackId", songId, "like", liked, "time", "3"), session);
    }

    /** Read the pinned module/recommend_songs.js daily snapshot for the current session account.
     * One read-only WEAPI request; no refresh flag, no dislike or other platform write. */
    public List<Track> dailyRecommendations(NeteaseApi.Session session) throws IOException {
        if (session == null) throw error("credentials");
        requireCurrent();
        // Upstream sends only afresh, which JSON.stringify drops when unset, so no key is sent here.
        JSONObject body = api.authenticatedCall(NeteaseApi.DAILY_RECOMMENDATIONS, new JSONObject(), session);
        requireCurrent();
        return parseDailyRecommendations(body);
    }

    /** data.dailySongs rows carry the same ar/al/dt shape as song_detail rows. */
    static List<Track> parseDailyRecommendations(JSONObject body) throws IOException {
        JSONObject data = body.optJSONObject("data");
        if (data == null) throw error("protocol");
        JSONArray rows = data.optJSONArray("dailySongs");
        if (rows == null) throw error("protocol");
        if (rows.length() > 200) throw error("limit");
        List<String> selected = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw error("protocol");
            String key = id(row.opt("id"));
            if (!seen.add(key)) throw error("identity");
            selected.add(key);
        }
        // Reuse the song_detail row parser by presenting the same songs array shape.
        return Collections.unmodifiableList(parseTracks(json("songs", rows), selected));
    }

    static Page<Playlist> parsePlaylists(JSONObject body, int offset, int limit) throws IOException {
        return parsePlaylists(body, offset, limit, null);
    }

    static Page<Playlist> parsePlaylists(JSONObject body, int offset, int limit, String ownerId) throws IOException {
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
            Playlist.Kind kind = playlistKind(p, ownerId);
            out.add(new Playlist(key, name(p.opt("name"), "未命名歌单"), image(p.opt("coverImgUrl")),
                    (int) nonNegative(p.opt("trackCount"), 1_000_000),
                    kind == Playlist.Kind.LIKED, kind == Playlist.Kind.CREATED, kind));
        }
        boolean more = body.optBoolean("more");
        if (more && rows.length() == 0) throw error("protocol");
        return new Page<>(out, more ? offset + rows.length() : -1, -1);
    }

    private static Playlist.Kind playlistKind(JSONObject row, String ownerId) {
        Object special = row.opt("specialType");
        if (special instanceof Integer || special instanceof Long) {
            long value = ((Number) special).longValue();
            if (value == 5) return Playlist.Kind.LIKED;
        }
        if (ownerId == null) return Playlist.Kind.OTHER;
        JSONObject creator = row.optJSONObject("creator");
        if (creator == null) return Playlist.Kind.OTHER;
        try {
            String creatorId = id(creator.opt("userId"));
            return ownerId.equals(creatorId) ? Playlist.Kind.CREATED : Playlist.Kind.COLLECTED;
        } catch (IOException unknown) {
            return Playlist.Kind.OTHER;
        }
    }

    static List<Playlist> parseWritablePlaylistPage(JSONObject body, String ownerId) throws IOException {
        JSONArray rows = body.optJSONArray("playlist");
        if (rows == null || rows.length() > 100 || !(body.opt("more") instanceof Boolean))
            throw error("protocol");
        id(ownerId);
        List<Playlist> out = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject p = rows.optJSONObject(i);
            if (p == null) throw error("protocol");
            String key = id(p.opt("id"));
            if (!seen.add(key)) throw error("protocol");
            JSONObject creator = p.optJSONObject("creator");
            if (creator == null || !ownerId.equals(id(creator.opt("userId")))) continue;
            int specialType = (int) nonNegative(p.opt("specialType"), 1_000_000);
            boolean liked = specialType == 5;
            out.add(new Playlist(key, name(p.opt("name"), "未命名歌单"),
                    image(p.opt("coverImgUrl")),
                    (int) nonNegative(p.opt("trackCount"), 1_000_000), liked, true));
        }
        return Collections.unmodifiableList(out);
    }

    static JSONObject requireOwnedPlaylist(JSONObject body, String playlistId, String ownerId) throws IOException {
        id(playlistId); id(ownerId);
        JSONObject playlist = body.optJSONObject("playlist");
        if (playlist == null || !playlistId.equals(id(playlist.opt("id")))) throw error("identity");
        JSONObject creator = playlist.optJSONObject("creator");
        if (creator == null || !ownerId.equals(id(creator.opt("userId")))) throw error("permission");
        nonNegative(playlist.opt("specialType"), 1_000_000);
        return playlist;
    }

    /** New playlist id from the pinned create response shape {id} or {playlist:{id}}.
     * The caller still re-reads detail, so a bad id can never stand in for the real list. */
    static String createdPlaylistId(JSONObject body) throws IOException {
        Object value = body.opt("id");
        if (value == null || value == JSONObject.NULL) {
            JSONObject playlist = body.optJSONObject("playlist");
            value = playlist == null ? null : playlist.opt("id");
        }
        if (value == null || value == JSONObject.NULL) throw error("protocol");
        return id(value);
    }

    /** 1..100 code points, no control characters, and never blank after trimming. */
    static String playlistName(String value) throws IOException {
        if (value == null) throw error("request");
        String trimmed = value.trim();
        int codePoints = trimmed.codePointCount(0, trimmed.length());
        if (codePoints < 1 || codePoints > 100) throw error("request");
        for (int i = 0; i < trimmed.length();) {
            int codePoint = trimmed.codePointAt(i);
            if (Character.isISOControl(codePoint)) throw error("request");
            if (Character.isHighSurrogate(trimmed.charAt(i))
                    && (i + 1 >= trimmed.length() || !Character.isLowSurrogate(trimmed.charAt(i + 1))))
                throw error("request");
            if (Character.isLowSurrogate(trimmed.charAt(i))
                    && (i == 0 || !Character.isHighSurrogate(trimmed.charAt(i - 1))))
                throw error("request");
            i += Character.charCount(codePoint);
        }
        return trimmed;
    }

    /** Pinned module/playlist_create.js body: privacy=0 normal list, type=NORMAL. */
    static JSONObject createPlaylistPayload(String name) throws IOException {
        return json("name", playlistName(name), "privacy", "0", "type", "NORMAL");
    }

    /** Pinned module/playlist_name_update.js body: only id and name. */
    static JSONObject renamePlaylistPayload(String playlistId, String name) throws IOException {
        return json("id", id(playlistId), "name", playlistName(name));
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
            List<String> artistIds = new ArrayList<>();
            boolean completeArtistIds = true;
            JSONArray ar = row.optJSONArray("ar");
            if (ar == null || ar.length() > 100) throw error("protocol");
            for (int j = 0; j < ar.length(); j++) {
                JSONObject artist = ar.optJSONObject(j);
                if (artist == null) throw error("protocol");
                artists.add(name(artist.opt("name"), "未知歌手"));
                String artistId = optionalNavigationId(artist.opt("id"));
                if (artistId == null) {
                    completeArtistIds = false;
                } else {
                    artistIds.add(artistId);
                }
            }
            if (!completeArtistIds) artistIds.clear();
            JSONObject album = row.optJSONObject("al");
            if (album == null) throw error("protocol");
            String albumId = optionalNavigationId(album.opt("id"));
            out.put(key, new Track(key, name(row.opt("name"), "未命名歌曲"), name(album.opt("name"), "未知专辑"),
                    artists, nonNegative(row.opt("dt"), 86_400_000), true, image(album.opt("picUrl")),
                    artistIds, albumId));
        }
        List<Track> ordered = new ArrayList<>();
        for (String key : selected) ordered.add(out.containsKey(key) ? out.get(key)
                : new Track(key, "暂不可用歌曲", "", Collections.emptyList(), 0, false));
        return ordered;
    }

    static Page<Track> parseArtistTracks(JSONObject body, String artistId, int offset, int limit)
            throws IOException {
        id(artistId); pageBounds(offset, limit);
        JSONArray rows = body.optJSONArray("songs");
        if (rows == null || rows.length() > limit || !(body.opt("more") instanceof Boolean))
            throw error("protocol");
        int total = (int) nonNegative(body.opt("total"), Integer.MAX_VALUE);
        int end = offset + rows.length();
        boolean more = body.optBoolean("more");
        if (end > total || more != (end < total) || (rows.length() == 0 && offset < total))
            throw error("incomplete");
        List<String> selected = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw error("protocol");
            String key = id(row.opt("id"));
            if (!seen.add(key)) throw error("identity");
            selected.add(key);
        }
        List<Track> items = parseTracks(body, selected);
        for (Track track : items) {
            if (track.artistIds.isEmpty()) throw error("incomplete");
            if (!track.artistIds.contains(artistId)) throw error("identity");
        }
        return new Page<>(items, more ? end : -1, total);
    }

    static Page<Track> parseAlbumTracks(JSONObject body, String albumId, int offset, int limit)
            throws IOException {
        id(albumId); pageBounds(offset, limit);
        JSONObject album = body.optJSONObject("album");
        if (album == null || !id(album.opt("id")).equals(albumId)) throw error("identity");
        JSONArray rows = body.optJSONArray("songs");
        if (rows == null || rows.length() > 100_000) throw error("limit");
        List<String> selected = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw error("protocol");
            String key = id(row.opt("id"));
            if (!seen.add(key)) throw error("identity");
            selected.add(key);
        }
        List<Track> all = parseTracks(body, selected);
        for (Track track : all)
            if (track.albumId != null && !track.albumId.equals(albumId))
                throw error("identity");
        if (offset > all.size()) throw error("incomplete");
        int end = Math.min(all.size(), offset + limit);
        List<Track> items = new ArrayList<>(all.subList(offset, end));
        return new Page<>(items, end < all.size() ? end : -1, all.size());
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

    static Page<Track> parseSearch(JSONObject body, int offset, int limit) throws IOException {
        searchBounds(offset, limit);
        JSONObject result = body.optJSONObject("result");
        if (result == null) throw error("protocol");
        int total = (int) nonNegative(result.opt("songCount"), Integer.MAX_VALUE);
        JSONArray rows = result.optJSONArray("songs");
        if (rows == null && !result.has("songs") && total == 0) rows = new JSONArray();
        if (rows == null) throw error("protocol");
        if (rows.length() > limit) throw error("limit");
        int end = offset + rows.length();
        boolean more = end < total;
        if (end > 20_000 || (more && offset + limit > 19_980)) throw error("limit");
        if ((rows.length() > 0 && end > total) || (rows.length() == 0 && offset < total) ||
                (rows.length() < limit && end < total)) throw error("incomplete");
        List<String> selected = new ArrayList<>();
        for (int i = 0; i < rows.length(); i++) {
            JSONObject row = rows.optJSONObject(i);
            if (row == null) throw error("protocol");
            selected.add(id(row.opt("id")));
        }
        List<Track> items = rows.length() == 0 ? Collections.emptyList() : parseTracks(result, selected);
        return new Page<>(items, more ? offset + limit : -1, total);
    }

    private static void searchBounds(int offset, int limit) throws IOException {
        if (limit < 1 || limit > 100 || offset < 0 || offset > 19_980 || offset % limit != 0) throw error("limit");
    }

    static Audio parseAudio(JSONObject body, String trackId, long nowElapsedMs) throws IOException {
        return parseAudio(body,trackId,nowElapsedMs,"netease:standard");
    }

    static Audio parseAudio(JSONObject body, String trackId, long nowElapsedMs, String requestedQualityId) throws IOException {
        id(trackId);
        levelForQuality(requestedQualityId);
        JSONArray rows = body.optJSONArray("data");
        if (rows == null || rows.length() != 1) throw error("protocol");
        JSONObject row = rows.optJSONObject(0);
        if (row == null || !id(row.opt("id")).equals(trackId)) throw error("identity");
        if (row.has("freeTrialInfo") && !row.isNull("freeTrialInfo")) throw error("preview");
        if (row.optInt("code", -1) != 200 || row.isNull("url")) {
            // fee is only evidence of a membership restriction when no full stream was granted.
            if (row.optInt("fee", 0) == 1 || row.optInt("fee", 0) == 8) throw error("membership");
            throw error("unavailable");
        }
        String url = mediaUrl(row.opt("url"));
        int bitrate = (int) nonNegative(row.opt("br"), 10_000_000);
        if (bitrate == 0) throw error("unavailable");
        String actualQualityId=qualityIdForResponse(row,bitrate);
        List<String> levels=java.util.Arrays.asList("netease:standard","netease:higher","netease:exhigh","netease:lossless","netease:hires");
        if(levels.indexOf(actualQualityId)>levels.indexOf(requestedQualityId))throw error("unavailable");
        long expires = nonNegative(row.opt("expi"), 86_400);
        if (expires == 0 || nowElapsedMs < 0 || nowElapsedMs > Long.MAX_VALUE - expires * 1000L) throw error("protocol");
        return new Audio(trackId, url, bitrate, nowElapsedMs + expires * 1000L, actualQualityId);
    }

    static String parseLyrics(JSONObject body) throws IOException {
        if (body == null || NeteaseApi.responseCode(body) != 200) throw error("protocol");
        for (String flag : new String[]{"nolyric", "uncollected"})
            if (body.has(flag) && !(body.opt(flag) instanceof Boolean)) throw error("protocol");
        boolean flagged = Boolean.TRUE.equals(body.opt("nolyric"))
                || Boolean.TRUE.equals(body.opt("uncollected"));
        JSONObject lrc = body.optJSONObject("lrc");
        if (lrc == null) {
            if (flagged) return "";
            throw error("protocol");
        }
        Object value = lrc.opt("lyric");
        if (!(value instanceof String)) throw error("protocol");
        String lyric = (String)value;
        if (lyric.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > 512 * 1024)
            throw error("limit");
        return flagged || lyric.isEmpty() ? "" : lyric;
    }

    static LyricsDocument parseLyricsDocument(JSONObject body) throws IOException {
        String original = parseLyrics(body);
        return parseTranslation(original,body,"tlyric");
    }

    private static LyricsDocument parseTranslation(String original,JSONObject body,String field) throws IOException {
        if (original.isEmpty()) return new LyricsDocument(original, null, TranslationStatus.ABSENT);
        if (!body.has(field) || body.isNull(field))
            return new LyricsDocument(original, null, TranslationStatus.ABSENT);
        JSONObject translated = body.optJSONObject(field);
        if (translated == null) return new LyricsDocument(original, null, TranslationStatus.INVALID);
        if (!translated.has("lyric") || translated.isNull("lyric"))
            return new LyricsDocument(original, null, TranslationStatus.ABSENT);
        Object value = translated.opt("lyric");
        if (!(value instanceof String)) return new LyricsDocument(original, null, TranslationStatus.INVALID);
        String translation = (String)value;
        if (!validLyricText(translation, 256 * 1024))
            return new LyricsDocument(original, null, TranslationStatus.INVALID);
        if (isBlank(translation)) return new LyricsDocument(original, null, TranslationStatus.ABSENT);
        return new LyricsDocument(original, translation, TranslationStatus.PRESENT);
    }

    private static boolean isBlank(String value) {
        for (int i = 0; i < value.length();) {
            int codePoint = value.codePointAt(i);
            if (!Character.isWhitespace(codePoint) && !Character.isSpaceChar(codePoint)) return false;
            i += Character.charCount(codePoint);
        }
        return true;
    }

    private static boolean validLyricText(String value, int maximumBytes) {
        long bytes = 0;
        for (int i = 0; i < value.length();) {
            char current = value.charAt(i);
            if (Character.isHighSurrogate(current)) {
                if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) return false;
                bytes += 4;
                i += 2;
                continue;
            }
            if (Character.isLowSurrogate(current)) return false;
            if (Character.isISOControl(current) && current != '\n' && current != '\r' && current != '\t')
                return false;
            bytes += current < 0x80 ? 1 : current < 0x800 ? 2 : 3;
            if (bytes > maximumBytes) return false;
            i++;
        }
        return bytes <= maximumBytes;
    }

    static List<String> parseLikedIds(JSONObject body) throws IOException {
        if (body == null || NeteaseApi.responseCode(body) != 200) throw error("protocol");
        JSONArray rows = body.optJSONArray("ids");
        if (rows == null) throw error("protocol");
        if (rows.length() > 20_000) throw error("limit");
        List<String> ids = new ArrayList<>();
        java.util.HashSet<String> seen = new java.util.HashSet<>();
        for (int i = 0; i < rows.length(); i++) {
            String key = id(rows.opt(i));
            if (!seen.add(key)) throw error("protocol");
            ids.add(key);
        }
        return Collections.unmodifiableList(ids);
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
        String url = (String)value;
        try {
            URI uri = new URI(url);
            String host = uri.getHost();
            if (host == null || !host.toLowerCase(java.util.Locale.ROOT).matches("p[1-9]\\.music\\.126\\.net") ||
                    url.length() > 2048 || url.codePoints().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)) ||
                    !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getRawUserInfo() != null ||
                    uri.getPort() != -1 || uri.getRawQuery() != null || uri.getRawFragment() != null ||
                    !host.equalsIgnoreCase(uri.getRawAuthority()) || uri.getRawPath() == null || uri.getRawPath().length() <= 1 ||
                    !uri.normalize().getRawPath().equals(uri.getRawPath())) return null;
            return "https://" + host.toLowerCase(java.util.Locale.ROOT) + uri.getRawPath();
        } catch (Exception ignored) { return null; }
    }
    private static String id(Object value) throws IOException {
        if (!(value instanceof String || value instanceof Integer || value instanceof Long || value instanceof java.math.BigInteger)) throw error("protocol");
        String s = value.toString();
        if (!s.matches("[1-9][0-9]{0,18}")) throw error("protocol");
        try { if (Long.parseLong(s) <= 0) throw error("protocol"); } catch (NumberFormatException invalid) { throw error("protocol"); }
        return s;
    }
    private static String optionalNavigationId(Object value) throws IOException {
        if (value == null || value == JSONObject.NULL) return null;
        try {
            return id(value);
        } catch (IOException invalidNavigation) {
            return null;
        }
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
