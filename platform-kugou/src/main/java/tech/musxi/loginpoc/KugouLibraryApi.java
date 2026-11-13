package tech.musxi.loginpoc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.util.*;

/** Device-local adapter of the pinned MIT KuGouMusicApi modules.
 * user_playlist, playlist_track_all_new, search, song_url and util/helper/request.
 * Copyright (c) 2023 MakcRe. No alternate audio or permission bypass.
 */
public final class KugouLibraryApi {
    private final KugouApi api;
    private final AudioProbe audioProbe;
    private final KugouAuthorizedAudio authorizedAudio;
    private final AudioInspector audioInspector;
    private KugouApi.Session favoriteSession;
    private FavoriteList cachedFavorite;
    private FavoriteMutation pendingFavorite;
    private long favoriteSnapshotAtNanos;
    private static final Set<String> HTTP_AUDIO_HOSTS = new HashSet<>(Arrays.asList("fs.youthandroid2.kugou.com", "fs.youthandroid.kugou.com"));
    public KugouLibraryApi(KugouApi api) {
        this(api,KugouLibraryApi::probeAudio,api.authorizedAudio(),KugouLibraryApi::inspectFlacAudio);
    }
    public KugouLibraryApi(KugouApi api,KugouAuthorizedAudio authorizedAudio) {
        this(api,KugouLibraryApi::probeAudio,authorizedAudio,KugouLibraryApi::inspectFlacAudio);
    }
    KugouLibraryApi(KugouApi api, AudioProbe audioProbe) {
        this(api,audioProbe,null,KugouLibraryApi::inspectFlacAudio);
    }
    KugouLibraryApi(KugouApi api,AudioProbe audioProbe,KugouAuthorizedAudio authorizedAudio) {
        this(api,audioProbe,authorizedAudio,KugouLibraryApi::inspectFlacAudio);
    }
    KugouLibraryApi(KugouApi api,AudioProbe audioProbe,KugouAuthorizedAudio authorizedAudio,
            AudioInspector audioInspector) {
        this.api=Objects.requireNonNull(api);
        this.audioProbe=Objects.requireNonNull(audioProbe);
        this.authorizedAudio=authorizedAudio;
        this.audioInspector=Objects.requireNonNull(audioInspector);
    }
    public static final class Page<T> {
        public final List<T> items; public final int nextOffset, total;
        Page(List<T> items, int nextOffset, int total) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items)); this.nextOffset = nextOffset; this.total = total;
        }
    }
    public static final class Playlist {
        public enum Kind { LIKED, CREATED, COLLECTED, OTHER }
        public final String id, name, coverUrl; public final int trackCount;
        public final String sourceGlobalId;
        public final boolean favorite, editable, readOnly, systemDefault;
        public final Kind kind;
        private final EditMetadata editMetadata;
        private final boolean ordinaryOwned;
        Playlist(String id, String name, String coverUrl, int count) { this(id,name,coverUrl,count,false); }
        Playlist(String id, String name, String coverUrl, int count, boolean favorite) {
            this(id,name,coverUrl,count,favorite,true,null);
        }
        Playlist(String id, String name, String coverUrl, int count, boolean favorite, boolean editable,
            String sourceGlobalId) {
            this(id,name,coverUrl,count,favorite,editable,sourceGlobalId,null,false,false);
        }
        private Playlist(String id, String name, String coverUrl, int count, boolean favorite, boolean editable,
            String sourceGlobalId, EditMetadata editMetadata, boolean systemDefault, boolean ordinaryOwned) {
            this.editMetadata=editMetadata;
            this.ordinaryOwned=ordinaryOwned;
            this.systemDefault=systemDefault;
            this.id=id; this.name=name; this.coverUrl=coverUrl; trackCount=count; this.favorite=favorite;
            this.sourceGlobalId=sourceGlobalId; this.editable=editable; this.readOnly=!editable;
            this.kind=favorite ? Kind.LIKED : editable ? Kind.CREATED :
                sourceGlobalId != null ? Kind.COLLECTED : Kind.OTHER;
        }
        /**
         * Whether this row carries everything an ordinary-list rename needs: the list is the
         * current user's own editable, non-favorite, non-system-default list and the account list
         * supplied the server's edit metadata (total_ver, sort, tags, intro). is_edit=1 alone is
         * not enough. Read-only: it never changes what renamePlaylist actually revalidates.
         */
        public boolean canRename() { return editable && !favorite && !systemDefault && editMetadata!=null; }
        /** Explicit is_edit=1 / is_def=0 is required; missing or future system flags fail closed. */
        public boolean canDelete() { return ordinaryOwned && editable && !favorite && !systemDefault && kind==Kind.CREATED; }
    }
    public static class Song {
        public final String id, name, album, coverUrl; public final List<String> artists; public final long durationMs;
        public final List<String> artistIds;
        public final String albumId;
        Song(String id,String name,String album,List<String> artists,long durationMs) {
            this(id,name,album,artists,durationMs,null);
        }
        Song(String id,String name,String album,List<String> artists,long durationMs,String coverUrl) {
            this(id,name,album,artists,durationMs,coverUrl,Collections.emptyList());
        }
        Song(String id,String name,String album,List<String> artists,long durationMs,String coverUrl,List<String> artistIds) {
            this.id=id; this.name=name; this.album=album; this.artists=Collections.unmodifiableList(new ArrayList<>(artists)); this.durationMs=durationMs;
            this.coverUrl=coverUrl;
            this.artistIds=Collections.unmodifiableList(new ArrayList<>(artistIds));
            String[] parts=id.split("_");this.albumId=parts.length==3 && !"0".equals(parts[1])?parts[1]:null;
        }
    }
    public Page<Track> artistTracks(KugouApi.Session session,String id,int offset,int limit) throws IOException {
        return collectionTracks(session,id,offset,limit,true);
    }
    /**
     * Current account's personal daily recommendation, from the standard-profile
     * POST /everyday_song_recommend (KuGouMusicApi a5a98013 module/recommend_songs.js).
     * Provider order is preserved, the whole batch is returned (at most 200 rows) and an
     * explicitly empty song_list is an empty success. A malformed body is never a success,
     * and no other recommendation surface is substituted for the personal daily batch.
     */
    public List<Track> dailyRecommendations(KugouApi.Session session) throws IOException {
        if(session==null)throw error("credentials");
        return parseDailyRecommendations(api.dailyRecommendCall(session));
    }
    static List<Track> parseDailyRecommendations(JSONObject root) throws IOException {
        JSONObject data=success(root);
        if(!(data.opt("song_list") instanceof JSONArray))throw error("protocol");
        JSONArray list=data.optJSONArray("song_list");
        if(list.length()>200)throw error("limit");
        List<Track> tracks=new ArrayList<>();
        Set<String> seen=new HashSet<>();
        for(int i=0;i<list.length();i++) {
            JSONObject row=list.optJSONObject(i);if(row==null)throw error("protocol");
            String hash=text(row.opt("hash")).toLowerCase(Locale.ROOT);
            if(!hash.matches("[a-f0-9]{32}"))throw error("identity");
            Object rawAlbum=first(row,"album_id");
            String album=numeric(rawAlbum==null?"0":rawAlbum,true);
            Object rawAudioId=first(row,"mixsongid","album_audio_id");
            String songId=hash+"_"+album+"_"+numeric(rawAudioId==null?"0":rawAudioId,true);
            if(!seen.add(songId))throw error("identity");
            String name=clean(first(row,"songname","ori_audio_name","audio_name","name","filename"),"");
            name=name.replaceFirst("(?i)\\.(mp3|flac|wav|wma|aac|m4a|ape|ogg)$","").trim();
            if(name.isEmpty())throw error("protocol");
            Object seconds=first(row,"time_length","timelength","timelen","duration");
            long duration=nonnegative(seconds==null?0:seconds,86_400)*1000;
            Artists contributors=dailySingers(row);
            tracks.add(new Track(songId,name,clean(first(row,"album_name","albumname"),""),
                contributors.names,duration,dailyCover(row),contributors.ids));
        }
        return Collections.unmodifiableList(tracks);
    }
    /** singerinfo is the only ordered contributor array in this response; never split author_name. */
    private static Artists dailySingers(JSONObject row) {
        JSONArray list=row.optJSONArray("singerinfo");
        if(list!=null && list.length()>0 && list.length()<=100) {
            List<String> names=new ArrayList<>(), ids=new ArrayList<>();
            boolean valid=true;
            for(int i=0;i<list.length();i++) {
                JSONObject item=list.optJSONObject(i);if(item==null){valid=false;break;}
                String name=clean(item.opt("name"),"");
                if(name.isEmpty()){valid=false;break;}
                names.add(name);ids.add(optionalArtistId(first(item,"id","author_id")));
            }
            if(valid) {
                if(ids.contains(null))ids.clear();
                return new Artists(names,ids);
            }
        }
        String fallback=clean(first(row,"author_name","singername"),"");
        return new Artists(fallback.isEmpty()?Collections.emptyList():Collections.singletonList(fallback),Collections.emptyList());
    }
    private static String dailyCover(JSONObject row) {
        Object value=first(row,"sizable_cover");
        if(value==null) {
            JSONObject media=row.optJSONObject("trans_param");
            if(media!=null)value=first(media,"union_cover");
        }
        if(value==null)return cover(row);
        JSONObject requested=new JSONObject();
        try { requested.put("cover",value); } catch(JSONException malformed) { return null; }
        return cover(requested);
    }
    public Page<Track> albumTracks(KugouApi.Session session,String id,int offset,int limit) throws IOException {
        return collectionTracks(session,id,offset,limit,false);
    }
    private Page<Track> collectionTracks(KugouApi.Session session,String id,int offset,int limit,boolean artist) throws IOException {
        if(offset<0 || offset>19_980 || limit<1 || limit>30 || offset%limit!=0)throw error("request");
        return parseCollectionTracks(api.collectionCall(session,artist,id,offset/limit+1,limit),id,offset,limit,artist);
    }
    static Page<Track> parseCollectionTracks(JSONObject root,String id,int offset,int limit,boolean artist) throws IOException {
        if(root==null || !id.matches("[1-9][0-9]{0,18}"))throw error("identity");
        successAudio(root);
        if(!"1".equals(text(root.opt("status"))))throw error("protocol");
        JSONObject data=root.optJSONObject("data");
        JSONArray list=artist?root.optJSONArray("data"):data==null?null:data.optJSONArray("songs");
        if(list==null)throw error("protocol");
        if(list.length()>limit)throw error("limit");
        int count=total(artist?root:data);
        if(count<0)throw error("protocol");
        List<Track> tracks=new ArrayList<>();
        Set<String> seen=new HashSet<>();
            for(int i=0;i<list.length();i++) {
                JSONObject row=list.optJSONObject(i);if(row==null)throw error("protocol");
                JSONObject base=artist?row:row.optJSONObject("base");
                JSONObject audio=artist?row:row.optJSONObject("audio_info");
                if(base==null || audio==null)throw error("protocol");
                String hash=text(audio.opt("hash")).toLowerCase(Locale.ROOT);
                if(!hash.matches("[a-f0-9]{32}"))throw error("identity");
                String album=numeric(base.opt("album_id"),true);
                String audioId=numeric(base.opt("album_audio_id"),false);
                if(!artist && !id.equals(album))throw error("identity");
                String songId=hash+"_"+album+"_"+audioId;
                if(!seen.add(songId))throw error("identity");
                String name=clean(base.opt("audio_name"),"");if(name.isEmpty())throw error("protocol");
                List<String> names=new ArrayList<>(), ids=new ArrayList<>();
                JSONArray authors=row.optJSONArray("authors");
                if(authors!=null && authors.length()>0) {
                    if(authors.length()>100)throw error("limit");
                    for(int n=0;n<authors.length();n++) {
                        JSONObject author=authors.optJSONObject(n);if(author==null)throw error("protocol");
                        String authorName=clean(author.opt("author_name"),"");if(authorName.isEmpty())throw error("protocol");
                        names.add(authorName);ids.add(optionalArtistId(author.opt("author_id")));
                    }
                    if(ids.contains(null))ids.clear();
                } else {
                    String authorName=clean(base.opt("author_name"),"");
                    if(!authorName.isEmpty())names.add(authorName);
                }
                JSONObject albumInfo=row.optJSONObject("album_info");
                String albumName=clean(artist?row.opt("album_name"):albumInfo==null?null:albumInfo.opt("album_name"),"");
                long duration=nonnegative(audio.opt(artist?"timelength":"duration"),86_400_000);
                tracks.add(new Track(songId,name,albumName,names,duration,cover(row),ids));
            }
        return new Page<>(tracks,next(offset,tracks.size(),count,limit),count);
    }
    private static String optionalArtistId(Object raw) {
        try{return raw==null || raw==JSONObject.NULL?null:numeric(raw,false);}catch(IOException invalid){return null;}
    }
    private static final class Artists {
        final List<String> names, ids;
        Artists(List<String> names,List<String> ids){this.names=names;this.ids=ids;}
    }
    /** Only use an explicit ordered author array; never split or guess joined display names. */
    private static Artists artists(JSONObject row,String fallback) {
        JSONArray list=row.optJSONArray("Singers");boolean search=list!=null;
        if(list==null)list=row.optJSONArray("authors");
        if(list!=null && list.length()>0 && list.length()<=100) {
            List<String> names=new ArrayList<>(), ids=new ArrayList<>();
            boolean valid=true;
            for(int i=0;i<list.length();i++) {
                JSONObject item=list.optJSONObject(i);if(item==null){valid=false;break;}
                String name=searchText(item.opt(search?"name":"author_name"));
                if(name.isEmpty()){valid=false;break;}
                names.add(name);ids.add(optionalArtistId(item.opt(search?"id":"author_id")));
            }
            if(valid){if(ids.contains(null))ids.clear();return new Artists(names,ids);}
        }
        return new Artists(fallback.isEmpty()?Collections.emptyList():Collections.singletonList(fallback),Collections.emptyList());
    }
    public static final class Track extends Song {
        String fileId;
        Track(String id,String name,String album,List<String> artists,long durationMs) { super(id,name,album,artists,durationMs); }
        Track(String id,String name,String album,List<String> artists,long durationMs,String coverUrl) { super(id,name,album,artists,durationMs,coverUrl); }
        Track(String id,String name,String album,List<String> artists,long durationMs,String coverUrl,List<String> artistIds) { super(id,name,album,artists,durationMs,coverUrl,artistIds); }
    }
    public static final class Audio {
        public final String url, qualityId; public final long expiresAtElapsedMs;
        final List<String> candidates;
        final long expectedDurationMs;
        Audio(String url,String qualityId,long expiresAtElapsedMs,List<String> candidates) {
            this(url,qualityId,expiresAtElapsedMs,candidates,0);
        }
        Audio(String url,String qualityId,long expiresAtElapsedMs,List<String> candidates,long expectedDurationMs) {
            this.url=url; this.qualityId=qualityId; this.expiresAtElapsedMs=expiresAtElapsedMs;
            this.candidates=Collections.unmodifiableList(new ArrayList<>(candidates));
            this.expectedDurationMs=expectedDurationMs;
        }
        @Override public String toString() { return "Audio(redacted)"; }
    }
    static final class FlacInfo {
        final int sampleRate,bitsPerSample,channels;
        final long totalSamples,durationMs;
        FlacInfo(int sampleRate,int bitsPerSample,int channels,long totalSamples) {
            this.sampleRate=sampleRate;this.bitsPerSample=bitsPerSample;this.channels=channels;
            this.totalSamples=totalSamples;this.durationMs=sampleRate==0?0:totalSamples*1000L/sampleRate;
        }
    }
    public static final class AudioQuality {
        public final String id, name;
        AudioQuality(String id,String name) { this.id=id; this.name=name; }
    }
    public Page<Playlist> playlists(KugouApi.Session session,int offset,int limit) throws IOException {
        return playlists(session,offset,limit,false);
    }
    private Page<Playlist> playlists(KugouApi.Session session,int offset,int limit,boolean strict) throws IOException {
        bounds(offset,limit);
        String body = "{\"userid\":"+JSONObject.quote(session.userId)+",\"token\":"+JSONObject.quote(session.token)+
            ",\"total_ver\":979,\"type\":2,\"page\":"+(offset/limit+1)+",\"pagesize\":"+limit+"}";
        JSONObject root=api.libraryCall("/v7/get_all_list",session,map("plat","1"),body,false);
        if(strict) {
            JSONObject data=success(root);
            if(!(data.opt("info") instanceof JSONArray) && !(data.opt("lists") instanceof JSONArray) &&
                !(offset==0 && total(data)==0))throw error("incomplete");
        }
        return parsePlaylists(root,offset,limit);
    }
    public Page<Track> tracks(KugouApi.Session session,String playlistId,int offset,int limit) throws IOException {
        return tracks(session,playlistId,null,offset,limit);
    }
    /** Only a Playlist parsed from the account list can pair a source GID with its detail list ID. */
    public Page<Track> tracks(KugouApi.Session session,Playlist playlist,int offset,int limit) throws IOException {
        if(playlist==null)throw error("identity");
        return tracks(session,playlist.id,playlist.sourceGlobalId,offset,limit);
    }
    /** Uses the original collection endpoint only for a source GID learned from the current account list. */
    public Page<Track> tracks(KugouApi.Session session,String playlistId,String sourceGlobalId,int offset,int limit) throws IOException {
        bounds(offset,limit); numeric(playlistId,false);
        if(sourceGlobalId!=null) {
            identifier(sourceGlobalId);
            Map<String,String> params=map("global_collection_id",sourceGlobalId,"begin_idx",String.valueOf(offset),
                "pagesize",String.valueOf(limit),"plat","1","type","1","mode","1","personal_switch","1",
                "area_code","1","extend_fields","abtags,hot_cmt,popularization");
            return parseTracks(api.libraryCall("/pubsongs/v2/get_other_list_file_nofilt",session,params,null,false),offset,limit);
        }
        String body="{\"listid\":"+JSONObject.quote(playlistId)+",\"userid\":"+JSONObject.quote(session.userId)+
            ",\"area_code\":1,\"show_relate_goods\":0,\"pagesize\":"+limit+",\"allplatform\":1,\"show_cover\":1,\"type\":0,\"token\":"+
            JSONObject.quote(session.token)+",\"page\":"+(offset/limit+1)+"}";
        return parseTracks(api.libraryCall("/v4/get_list_all_file",session,Collections.emptyMap(),body,false),offset,limit);
    }
    /**
     * Playlist page counted from the newest addition, as the Kugou app shows it.
     *
     * get_list_all_file has no order parameter and returns oldest first, so "the newest 30" is the
     * end of the list. A one-row probe learns the total; the logical window is then read from the
     * one or two API pages that cover it and reversed. Logical offsets stay 0, limit, 2*limit...
     * so callers and caches keep their contract. The total is re-checked on every API page; a list
     * that changes between requests is reported as incomplete rather than shown out of order.
     */
    public Page<Track> tracksNewestFirst(KugouApi.Session session,String playlistId,int offset,int limit) throws IOException {
        return tracksNewestFirst(session,playlistId,null,offset,limit);
    }
    public Page<Track> tracksNewestFirst(KugouApi.Session session,Playlist playlist,int offset,int limit) throws IOException {
        if(playlist==null)throw error("identity");
        return tracksNewestFirst(session,playlist.id,playlist.sourceGlobalId,offset,limit);
    }
    /** Source playlists retain their published order; only editable cloud lists use newest-first windows. */
    public Page<Track> tracksNewestFirst(KugouApi.Session session,String playlistId,String sourceGlobalId,int offset,int limit) throws IOException {
        bounds(offset,limit); numeric(playlistId,false);
        if(sourceGlobalId!=null)return tracks(session,playlistId,sourceGlobalId,offset,limit);
        int total=tracks(session,playlistId,null,0,1).total;
        if(total<0)throw error("incomplete");
        if(offset>=total) { if(offset>0 && total>0 && offset>total)throw error("incomplete"); return new Page<>(Collections.emptyList(),-1,total); }
        int end=total-offset, start=Math.max(0,end-limit);
        List<Track> window=new ArrayList<>(end-start);
        for(int apiOffset=(start/limit)*limit; apiOffset<end; apiOffset+=limit) {
            Page<Track> page=tracks(session,playlistId,null,apiOffset,limit);
            if(page.total!=total)throw error("incomplete");
            for(int i=0;i<page.items.size();i++) {
                int position=apiOffset+i;
                if(position>=start && position<end)window.add(page.items.get(i));
            }
        }
        if(window.size()!=end-start)throw error("incomplete");
        Collections.reverse(window);
        return new Page<>(window,start==0?-1:offset+limit,total);
    }
    /** A complete, bounded snapshot of the platform-designated editable favorite list. */
    public synchronized List<String> likedIds(KugouApi.Session session, java.util.function.BooleanSupplier current) throws IOException {
        FavoriteList favorite = favoriteList(session, current);
        favoriteSession=session; cachedFavorite=favorite; pendingFavorite=null; favoriteSnapshotAtNanos=System.nanoTime();
        List<String> ids = new ArrayList<>();
        for (Track track : favorite.tracks) ids.add(track.id);
        return Collections.unmodifiableList(ids);
    }
    private static final class FavoriteList {
        final String id; final List<Track> tracks;
        FavoriteList(String id,List<Track> tracks) { this.id=id;this.tracks=tracks; }
    }
    private static final class FavoriteMutation {
        final String songId; final boolean liked; final FavoriteList expected; final int position;
        FavoriteMutation(String songId,boolean liked,FavoriteList expected,int position) {
            this.songId=songId;this.liked=liked;this.expected=expected;this.position=position;
        }
    }
    private FavoriteList knownFavorite(KugouApi.Session session,java.util.function.BooleanSupplier current) throws IOException {
        favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
        if(favoriteSession!=session || cachedFavorite==null || System.nanoTime()-favoriteSnapshotAtNanos>300_000_000_000L) {
            cachedFavorite=favoriteList(session,current); favoriteSession=session; pendingFavorite=null;
            favoriteSnapshotAtNanos=System.nanoTime();
        }
        return cachedFavorite;
    }

    /** Confirm the changed window and total, rather than downloading every favorite again.
     * The pinned API has no per-song membership query. Any divergent page fails closed and
     * the browser reconciles the complete collection; absence in an arbitrary page is never enough. */
    public synchronized boolean verifyLiked(KugouApi.Session session,String songId,boolean liked,
        java.util.function.BooleanSupplier current) throws IOException {
        FavoriteMutation change=pendingFavorite;
        if(favoriteSession!=session || change==null || !change.songId.equals(songId) || change.liked!=liked)
            throw error("incomplete");
        pendingFavorite=null;
        try {
            favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
            List<Track> expected=change.expected.tracks;
            int offset=Math.min(change.position/100*100,Math.max(0,(expected.size()-1)/100*100));
            Page<Track> page=tracks(session,change.expected.id,offset,100);
            favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
            if(page.total!=expected.size() || page.items.size()!=Math.min(100,expected.size()-offset))throw error("incomplete");
            for(int i=0;i<page.items.size();i++)
                if(!page.items.get(i).id.equals(expected.get(offset+i).id))throw error("incomplete");
            List<Track> confirmed=new ArrayList<>(expected);
            for(int i=0;i<page.items.size();i++)confirmed.set(offset+i,page.items.get(i));
            cachedFavorite=new FavoriteList(change.expected.id,confirmed);
            return liked;
        } catch(IOException | RuntimeException failure) { cachedFavorite=null;throw failure; }
    }
    private FavoriteList favoriteList(KugouApi.Session session, java.util.function.BooleanSupplier current) throws IOException {
        long deadline = System.nanoTime() + 60_000_000_000L;
        String favorite = null; int offset=0; Integer expected=null;
        Set<String> lists=new HashSet<>();
        while (true) {
            favoriteCurrent(current,deadline);
            Page<Playlist> page=playlists(session,offset,100);
            if(page.total>=0) { if(expected!=null && expected!=page.total)throw error("incomplete");expected=page.total; }
            for(Playlist p:page.items) {
                if(!lists.add(p.id) || lists.size()>20_000)throw error("incomplete");
                if(p.favorite) { if(favorite!=null)throw error("identity");favorite=p.id; }
            }
            if(page.nextOffset<0)break;
            if(page.nextOffset<=offset || page.nextOffset>20_000)throw error("limit");
            offset=page.nextOffset;
        }
        if(expected!=null && expected!=lists.size())throw error("incomplete");
        if(favorite==null)throw error("unavailable");
        List<Track> result=new ArrayList<>(); Set<String> keys=new HashSet<>(); offset=0;expected=null;
        while(true) {
            favoriteCurrent(current,deadline);
            Page<Track> page=tracks(session,favorite,offset,100);
            if(page.total>=0) { if(expected!=null && expected!=page.total)throw error("incomplete");expected=page.total; }
            for(Track t:page.items) { if(!keys.add(t.id) || result.size()>=20_000)throw error("incomplete");result.add(t); }
            if(page.nextOffset<0)break;
            if(page.nextOffset<=offset || page.nextOffset>20_000)throw error("limit");offset=page.nextOffset;
        }
        if(expected!=null && expected!=result.size())throw error("incomplete");
        favoriteCurrent(current,deadline);
        return new FavoriteList(favorite,result);
    }
    private static void favoriteCurrent(java.util.function.BooleanSupplier current,long deadline) throws IOException {
        if(!current.getAsBoolean() || Thread.currentThread().isInterrupted())throw error("account_changed");
        if(System.nanoTime()>deadline)throw error("timeout");
    }
    /** One explicit favorite mutation, never an arbitrary user-supplied playlist target. */
    public synchronized void setLiked(KugouApi.Session session,String songId,String name,long durationMs,boolean liked,
        java.util.function.BooleanSupplier current) throws IOException {
        String[] parts=songId.split("_",-1);
        if(parts.length!=3 || !parts[0].matches("[a-f0-9]{32}"))throw error("identity");
        numeric(parts[1],true);numeric(parts[2],true);
        if(name==null || name.isEmpty() || name.length()>4096 || durationMs<0 || durationMs>86_400_000)throw error("protocol");
        FavoriteList favorite=knownFavorite(session,current);
        List<Track> matches=new ArrayList<>();
        for(Track t:favorite.tracks) if(t.id.equals(songId))matches.add(t);
        int position=matches.isEmpty()?favorite.tracks.size():favorite.tracks.indexOf(matches.get(0));
        List<Track> expected=new ArrayList<>(favorite.tracks);
        if(liked && matches.isEmpty())expected.add(new Track(songId,name,"",Collections.emptyList(),durationMs));
        if(!liked)expected.removeAll(matches);
        FavoriteMutation planned=new FavoriteMutation(songId,liked,new FavoriteList(favorite.id,expected),position);
        if(liked==!matches.isEmpty()) { pendingFavorite=planned;return; }
        if(!current.getAsBoolean())throw error("account_changed");
        try {
            JSONObject body=new JSONObject().put("userid",session.userId).put("token",session.token)
                .put("listid",favorite.id).put("list_ver",0).put("type",0);
            String path; Map<String,String> query=Collections.emptyMap();
            if(liked) {
                JSONObject resource=new JSONObject().put("number",1).put("name",name).put("hash",parts[0])
                    .put("size",0).put("sort",0).put("timelen",durationMs).put("bitrate",0)
                    .put("album_id",Long.parseLong(parts[1])).put("mixsongid",Long.parseLong(parts[2]));
                body.put("slow_upload",1).put("scene","false;null").put("data",new JSONArray().put(resource));
                path="/cloudlist.service/v6/add_song";
                query=map("last_time",String.valueOf(System.currentTimeMillis()/1000),"last_area","gztx");
            } else {
                JSONArray ids=new JSONArray();
                for(Track t:matches)ids.put(new JSONObject().put("fileid",Long.parseLong(numeric(t.fileId,false))));
                body.put("data",ids);path="/v4/delete_songs";
            }
            JSONObject response=api.libraryCall(path,session,query,body.toString(),false);
            successAudio(response);
            if(!Arrays.asList("1","200").contains(text(response.opt("status"))))throw error("protocol");
            favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
            pendingFavorite=planned;
        } catch(org.json.JSONException malformed) { cachedFavorite=null;pendingFavorite=null;throw error("protocol"); }
        catch(IOException | RuntimeException failure) { cachedFavorite=null;pendingFavorite=null;throw failure; }
    }

    /** Revalidate ownership from the server before an explicit menu write. */
    public List<Playlist> writablePlaylists(KugouApi.Session session,java.util.function.BooleanSupplier current) throws IOException {
        List<Playlist> result=new ArrayList<>(); int offset=0;
        do {
            favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
            Page<Playlist> page=playlists(session,offset,100);
            for(Playlist value:page.items)if(value.editable)result.add(value);
            if(result.size()>20_000)throw error("limit");
            if(page.nextOffset<0)break;
            if(page.nextOffset<=offset || page.nextOffset>20_000)throw error("limit");
            offset=page.nextOffset;
        } while(true);
        favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
        return Collections.unmodifiableList(result);
    }

    public void addToPlaylist(KugouApi.Session session,String playlistId,String songId,String name,long durationMs,
        java.util.function.BooleanSupplier current) throws IOException {
        numeric(playlistId,false);
        Playlist target=null;
        for(Playlist p:writablePlaylists(session,current))if(p.id.equals(playlistId))target=p;
        if(target==null)throw error("permission");
        String[] parts=songId.split("_",-1);
        if(parts.length!=3 || !parts[0].matches("[a-f0-9]{32}"))throw error("identity");
        numeric(parts[1],true);numeric(parts[2],true);
        if(name==null || name.isEmpty() || name.length()>4096 || durationMs<0 || durationMs>86_400_000)throw error("protocol");
        favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
        try {
            JSONObject resource=new JSONObject().put("number",1).put("name",name).put("hash",parts[0])
                .put("size",0).put("sort",0).put("timelen",durationMs).put("bitrate",0)
                .put("album_id",Long.parseLong(parts[1])).put("mixsongid",Long.parseLong(parts[2]));
            JSONObject body=new JSONObject().put("userid",session.userId).put("token",session.token)
                .put("listid",playlistId).put("list_ver",0).put("type",0).put("slow_upload",1)
                .put("scene","false;null").put("data",new JSONArray().put(resource));
            JSONObject response=api.libraryCall("/cloudlist.service/v6/add_song",session,
                map("last_time",String.valueOf(System.currentTimeMillis()/1000),"last_area","gztx"),body.toString(),false);
            successAudio(response);
            if(!Arrays.asList("1","200").contains(text(response.opt("status"))))throw error("protocol");
            favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
        } catch(org.json.JSONException malformed) { throw error("protocol"); }
    }
    /** One playlist row proved by the current account list; an arbitrary caller id never becomes a write target. */
    public Playlist accountPlaylist(KugouApi.Session session,String playlistId,
        java.util.function.BooleanSupplier current) throws IOException {
        Playlist target=currentAccountPlaylist(session,playlistId,current);
        if(target==null)throw error("unavailable");
        return target;
    }

    /**
     * The account row with exactly this ID from a complete, bounded, fresh list read, or null when
     * the account really has no such row. A duplicated page, a drifting total or a page that cannot
     * advance fails closed: absence is only ever proven by an exhaustive walk.
     */
    private Playlist currentAccountPlaylist(KugouApi.Session session,String playlistId,
        java.util.function.BooleanSupplier current) throws IOException {
        numeric(playlistId,false);
        long deadline=System.nanoTime()+60_000_000_000L;
        Playlist target=null; Integer expected=null; int offset=0; Set<String> lists=new HashSet<>();
        while(true) {
            favoriteCurrent(current,deadline);
            Page<Playlist> page=playlists(session,offset,100,true);
            favoriteCurrent(current,deadline);
            if(page.total>=0) {
                if(page.total>20_000 || (expected!=null && expected!=page.total))throw error("incomplete");
                expected=page.total;
            } else if(expected!=null)throw error("incomplete");
            for(Playlist p:page.items) {
                if(!lists.add(p.id) || lists.size()>20_000)throw error("incomplete");
                if(p.id.equals(playlistId))target=p;
            }
            if(page.nextOffset<0)break;
            if(page.items.isEmpty() || page.nextOffset!=offset+100 || page.nextOffset>20_000)throw error("incomplete");
            offset=page.nextOffset;
        }
        if(expected!=null && expected!=lists.size())throw error("incomplete");
        favoriteCurrent(current,deadline);
        return target;
    }

    /**
     * The account row this session may delete, proven by a complete fresh list read, or null when
     * this account holds no row with that exact ID. A row that exists but is not the current user's
     * own ordinary editable list - the favorite list, a Kugou system default list, or a collected
     * read-only list - is refused as permission instead of being reported as absent.
     */
    public Playlist deletablePlaylist(KugouApi.Session session,String playlistId,
        java.util.function.BooleanSupplier current) throws IOException {
        if(session==null || current==null)throw error("credentials");
        Playlist target=currentAccountPlaylist(session,playlistId,current);
        if(target==null)return null;
        if(!target.canDelete())throw error("permission");
        favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
        return target;
    }

    /**
     * Re-proves ordinary ownership from the complete current account list, then sends at most one
     * fixed playlist_del.js request (type=1,total_ver=0), confirmed by the 2026-11-13 device probe.
     * No track removal, empty-list restriction or rename metadata is required. The facade's
     * deletion coordinator always performs another complete read, even if this response is lost.
     */
    public void deletePlaylist(KugouApi.Session session,String playlistId,
        java.util.function.BooleanSupplier current) throws IOException {
        if(session==null || current==null)throw error("credentials");
        if(deletablePlaylist(session,playlistId,current)==null)return;
        JSONObject response=api.deletePlaylist(session,Long.parseLong(playlistId),current);
        success(response);
        if(response.has("error_code") && !"0".equals(text(response.opt("error_code"))))throw error("platform");
        favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
    }

    /**
     * Creates one ordinary empty list, then resolves only the explicit response ID against a fresh
     * complete account list. The 2026-11-13 device probe confirmed data.info.listid; neither title
     * matches nor before/after list differences are a substitute for that ID. A failed write or
     * readback never resends the create request.
     */
    public Playlist createPlaylist(KugouApi.Session session,String name,
        java.util.function.BooleanSupplier current) throws IOException {
        if(session==null || current==null)throw error("credentials");
        String title=name==null?null:name.trim();
        if(title==null || title.isEmpty() || title.codePointCount(0,title.length())>80 ||
            name.codePoints().anyMatch(cp -> Character.isISOControl(cp) || (cp>=0xD800 && cp<=0xDFFF)))
            throw error("request");
        long deadline=System.nanoTime()+60_000_000_000L;
        List<Playlist> before=creationSnapshot(session,current,deadline);
        favoriteCurrent(current,deadline);
        JSONObject response;
        try {
            JSONObject body=new JSONObject().put("userid",session.userId).put("token",session.token)
                .put("total_ver",0).put("name",title).put("type",0).put("source",1)
                .put("is_pri",0).put("list_create_gid","").put("from_shupinmv",0);
            // Fixed request shape from KuGouMusicApi 7ac64e1 module/playlist_add.js.
            response=api.libraryCall("/cloudlist.service/v5/add_list",session,
                map("last_time",String.valueOf(System.currentTimeMillis()/1000),"last_area","gztx"),
                body.toString(),false);
        } catch(JSONException malformed) { throw error("protocol"); }
        favoriteCurrent(current,deadline);
        String id=createdPlaylistId(response);
        for(Playlist previous:before)if(previous.id.equals(id))throw error("identity");
        List<Playlist> after=creationSnapshot(session,current,deadline);
        Playlist confirmed=null;
        for(Playlist candidate:after)if(candidate.id.equals(id))confirmed=candidate;
        if(confirmed==null)throw error("not_confirmed");
        if(!confirmed.editable || confirmed.favorite || confirmed.systemDefault)throw error("permission");
        // List names are display-sanitized by parsePlaylists (including emoji ZWJ/Cf).
        // Compare using the same rule; the response ID, not the title, proves identity.
        if(!clean(title,"未命名歌单").equals(confirmed.name))throw error("not_confirmed");
        favoriteCurrent(current,deadline);
        return confirmed;
    }

    /** Only the observed account-local ID route is accepted; optional ID echo must agree. */
    private static String createdPlaylistId(JSONObject response) throws IOException {
        JSONObject data=success(response);
        if(response.has("error_code") && !"0".equals(text(response.opt("error_code"))))throw error("platform");
        JSONObject info=data.optJSONObject("info");
        if(info==null)throw error("protocol");
        // info is a playlist resource, not an operation envelope: its status is not a success code.
        String id=numeric(info.opt("listid"),false);
        if(info.has("list_create_listid") && !id.equals(numeric(info.opt("list_create_listid"),false)))throw error("identity");
        return id;
    }

    /** Full, stable account snapshot; malformed paging is not evidence of a new or absent ID. */
    private List<Playlist> creationSnapshot(KugouApi.Session session,
        java.util.function.BooleanSupplier current,long deadline) throws IOException {
        List<Playlist> result=new ArrayList<>();
        Set<String> seen=new HashSet<>(); Integer expected=null; int offset=0;
        while(true) {
            favoriteCurrent(current,deadline);
            Page<Playlist> page=playlists(session,offset,100);
            if(page.total>=0) {
                if(expected!=null && expected!=page.total)throw error("incomplete");
                expected=page.total;
            }
            for(Playlist value:page.items) {
                if(!seen.add(value.id) || result.size()>=20_000)throw error("incomplete");
                result.add(value);
            }
            if(page.nextOffset<0)break;
            if(page.nextOffset<=offset || page.nextOffset>20_000)throw error("limit");
            offset=page.nextOffset;
        }
        if(expected!=null && expected!=result.size())throw error("incomplete");
        favoriteCurrent(current,deadline);
        return result;
    }

    private static final class EditMetadata {
        final long totalVersion, sort;
        final String tags, intro;
        EditMetadata(long version,long sort,String tags,String intro) {
            this.totalVersion=version; this.sort=sort; this.tags=tags; this.intro=intro;
        }
    }
    private static EditMetadata editMetadata(JSONObject data,JSONObject row) {
        if(!(row.opt("tags") instanceof String) || !(row.opt("intro") instanceof String) || !row.has("sort"))return null;
        Object version=data.has("total_ver")?data.opt("total_ver"):row.opt("total_ver");
        try {
            String tags=(String)row.opt("tags"), intro=(String)row.opt("intro");
            if(tags.length()>4096 || intro.length()>16384)return null;
            return new EditMetadata(nonnegative(version,9_007_199_254_740_991L),
                nonnegative(row.opt("sort"),9_007_199_254_740_991L),tags,intro);
        } catch(IOException invalid) { return null; }
    }

    /** Rename only a server-owned ordinary list. Missing metadata never becomes empty defaults. */
    public void renamePlaylist(KugouApi.Session session,String playlistId,String name,
        java.util.function.BooleanSupplier current) throws IOException {
        if(name==null || name.trim().isEmpty() || name.codePointCount(0,name.length())>80 ||
            name.codePoints().anyMatch(cp -> Character.isISOControl(cp) || (cp>=0xD800 && cp<=0xDFFF)))throw error("request");
        String title=name.trim();
        Playlist before=accountPlaylist(session,playlistId,current);
        if(!before.editable || before.favorite || before.systemDefault)throw error("permission");
        if(title.equals(before.name))return;
        EditMetadata metadata=before.editMetadata;
        if(metadata==null)throw error("incomplete");
        favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
        try {
            JSONObject data=new JSONObject().put("listid",Long.parseLong(before.id)).put("type",0)
                .put("total_ver",metadata.totalVersion).put("sort",metadata.sort)
                .put("tags",metadata.tags).put("intro",metadata.intro).put("name",title);
            JSONObject response=api.renamePlaylist(session,data);
            successAudio(response);
            if(!Arrays.asList("1","200").contains(text(response.opt("status"))))throw error("protocol");
        } catch(org.json.JSONException invalid) { throw error("protocol"); }
        favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
        Playlist after=accountPlaylist(session,playlistId,current);
        if(!after.editable || after.favorite || after.systemDefault)throw error("permission");
        if(!title.equals(after.name))throw error("not_confirmed");
        EditMetadata confirmed=after.editMetadata;
        if(confirmed==null || confirmed.sort!=metadata.sort || !confirmed.tags.equals(metadata.tags) ||
            !confirmed.intro.equals(metadata.intro))throw error("not_confirmed");
        favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
    }

    /**
     * A complete, ordered snapshot of one playlist named by the current account list.
     * Every page is re-checked against the same total, duplicates fail closed, and an
     * interrupted or changed account never returns a partial list. Collected lists keep
     * their published source route; own lists use the stored order behind tracks(offset).
     */
    public List<String> playlistTrackIds(KugouApi.Session session,String playlistId,
        java.util.function.BooleanSupplier current) throws IOException {
        Playlist playlist=accountPlaylist(session,playlistId,current);
        long deadline=System.nanoTime()+60_000_000_000L;
        List<String> ids=new ArrayList<>(); Set<String> seen=new HashSet<>(); Integer expected=null; int offset=0;
        while(true) {
            favoriteCurrent(current,deadline);
            Page<Track> page=tracks(session,playlist.id,playlist.sourceGlobalId,offset,100);
            if(page.total>=0) { if(expected!=null && expected!=page.total)throw error("incomplete");expected=page.total; }
            // Every page must fill its declared window; a short page before the stated total is a truncation.
            if(expected!=null && page.items.size()!=Math.min(100,expected-offset))throw error("incomplete");
            for(Track track:page.items) {
                if(!seen.add(track.id) || ids.size()>=20_000)throw error("incomplete");
                ids.add(track.id);
            }
            if(page.nextOffset<0)break;
            if(page.nextOffset<=offset || page.nextOffset>20_000)throw error("limit");
            offset=page.nextOffset;
        }
        if(expected!=null && expected!=ids.size())throw error("incomplete");
        favoriteCurrent(current,deadline);
        return Collections.unmodifiableList(ids);
    }

    /**
     * Remove one song from one of the current account's own editable, non-favorite lists.
     * Ownership comes from a fresh server list read, the delete id is that row's real
     * fileid, and the favorite list keeps its separate setLiked path.
     */
    public void removeFromPlaylist(KugouApi.Session session,String playlistId,String songId,
        java.util.function.BooleanSupplier current) throws IOException {
        numeric(playlistId,false);
        String[] parts=songId.split("_",-1);
        if(parts.length!=3 || !parts[0].matches("[a-f0-9]{32}"))throw error("identity");
        numeric(parts[1],true);numeric(parts[2],true);
        Playlist target=accountPlaylist(session,playlistId,current);
        // is_edit=1 and is_def!=2: a read-only collected list or the favorite list is never a target here.
        if(!target.editable || target.favorite)throw error("permission");
        long deadline=System.nanoTime()+60_000_000_000L;
        List<Track> matches=new ArrayList<>(); int offset=0; Integer expected=null; int read=0;
        while(true) {
            favoriteCurrent(current,deadline);
            Page<Track> page=tracks(session,target.id,offset,100);
            if(page.total>=0) { if(expected!=null && expected!=page.total)throw error("incomplete");expected=page.total; }
            if(expected!=null && page.items.size()!=Math.min(100,expected-offset))throw error("incomplete");
            read+=page.items.size();
            for(Track track:page.items)if(track.id.equals(songId))matches.add(track);
            if(page.nextOffset<0)break;
            if(page.nextOffset<=offset || page.nextOffset>20_000)throw error("limit");
            offset=page.nextOffset;
        }
        // Never delete from a list whose scan did not account for every row it declared.
        if(expected!=null && expected!=read)throw error("incomplete");
        if(matches.isEmpty())throw error("unavailable");
        if(matches.size()!=1)throw error("identity");
        String fileId=matches.get(0).fileId;
        if(fileId==null || "0".equals(fileId))throw error("identity");
        favoriteCurrent(current,deadline);
        try {
            JSONObject body=new JSONObject().put("listid",target.id).put("userid",session.userId)
                .put("type",0).put("token",session.token).put("list_ver",0)
                .put("data",new JSONArray().put(new JSONObject().put("fileid",Long.parseLong(fileId))));
            JSONObject response=api.libraryCall("/v4/delete_songs",session,Collections.emptyMap(),body.toString(),false);
            successAudio(response);
            if(!Arrays.asList("1","200").contains(text(response.opt("status"))))throw error("protocol");
            favoriteCurrent(current,System.nanoTime()+60_000_000_000L);
        } catch(org.json.JSONException malformed) { throw error("protocol"); }
    }
    public enum TranslationStatus { PRESENT, ABSENT, INVALID, UNSUPPORTED, NETWORK, TIMEOUT, PLATFORM_ERROR }
    public static final class LyricsOriginal {
        public final String original;
        private final KugouApi ownerApi;
        private final KugouApi.Session ownerSession;
        private final String candidateId;
        private final String candidateAccessKey;
        private LyricsOriginal(String original,KugouApi ownerApi,KugouApi.Session ownerSession,
                String candidateId,String candidateAccessKey) {
            this.original=original;
            this.ownerApi=ownerApi;
            this.ownerSession=ownerSession;
            this.candidateId=candidateId;
            this.candidateAccessKey=candidateAccessKey;
        }
        @Override public String toString() { return "LyricsOriginal(redacted)"; }
    }
    public static final class LyricsDocument {
        public final String original;
        public final List<KrcLyricsDecoder.Row> rows;
        public final TranslationStatus translationStatus;
        public final String diagnostic;
        LyricsDocument(String original,List<KrcLyricsDecoder.Row> rows,TranslationStatus status) {
            this(original,rows,status,"NONE");
        }
        LyricsDocument(String original,List<KrcLyricsDecoder.Row> rows,TranslationStatus status,String diagnostic) {
            this.original=original;this.rows=rows;this.translationStatus=status;this.diagnostic=diagnostic;
        }
        @Override public String toString() { return "LyricsDocument(redacted)"; }
    }
    /** Compatibility entry point: original LRC only, without an optional KRC request. */
    public String lyrics(KugouApi.Session session, String songKey) throws IOException {
        return lyricsOriginal(session,songKey).original;
    }
    /** Resolves and downloads the original LRC once; translation can reuse this bound handle. */
    public LyricsOriginal lyricsOriginal(KugouApi.Session session,String songKey) throws IOException {
        if (session == null || songKey == null) throw error("identity");
        String[] parts = songKey.split("_", -1);
        if (parts.length != 3 || !parts[0].matches("[a-f0-9]{32}")) throw error("identity");
        numeric(parts[1], true); numeric(parts[2], true);
        JSONObject search = api.lyricsCall("/search", map("hash", parts[0], "album_audio_id", parts[2],
            "keyword", "", "duration", "0", "lrctxt", "1", "man", "no"));
        lyricStatus(search, "lyrics_search_status");
        JSONArray rows = search.optJSONArray("candidates");
        if (rows == null || rows.length() > 100) throw lyricError("lyrics_search_rows");
        if (rows.length() == 0) return new LyricsOriginal("",api,session,null,null);
        JSONObject candidate = rows.optJSONObject(0);
        if (candidate == null) throw lyricError("lyrics_search_candidate");
        String id = numeric(candidate.opt("id"), false);
        Object access = candidate.opt("accesskey");
        if (!(access instanceof String) || !((String)access).matches("[A-Za-z0-9_-]{1,256}"))
            throw lyricError("lyrics_search_key");
        JSONObject downloaded = api.lyricsCall("/download", map("ver", "1", "client", "android", "id", id,
            "accesskey", (String)access, "fmt", "lrc", "charset", "utf8"));
        lyricStatus(downloaded, "lyrics_download_status");
        return new LyricsOriginal(parseLyrics(downloaded),api,session,id,(String)access);
    }
    public LyricsDocument lyricsDocument(KugouApi.Session session,String songKey) throws IOException {
        return lyricsTranslation(session,lyricsOriginal(session,songKey));
    }
    /** Downloads only the KRC bound by the original stage; optional failure keeps the LRC. */
    public LyricsDocument lyricsTranslation(KugouApi.Session session,LyricsOriginal original) throws IOException {
        if (session == null || original == null || session != original.ownerSession || api != original.ownerApi)
            throw error("identity");
        if (original.original.trim().isEmpty() || original.candidateId == null || original.candidateAccessKey == null)
            return new LyricsDocument(original.original,Collections.emptyList(),TranslationStatus.ABSENT);
        try {
            JSONObject krc=api.lyricsCall("/download",map("ver","1","client","android","id",original.candidateId,
                "accesskey",original.candidateAccessKey,"fmt","krc","charset","utf8"));
            lyricStatus(krc,"lyrics_download_status");
            Object content=krc.opt("content");
            KrcLyricsDecoder.Document decoded=KrcLyricsDecoder.decode(content instanceof String?(String)content:null);
            return new LyricsDocument(original.original,decoded.rows,
                TranslationStatus.valueOf(decoded.status.name()),decoded.diagnostic);
        } catch(java.net.SocketTimeoutException timeout) {
            return new LyricsDocument(original.original,Collections.emptyList(),TranslationStatus.TIMEOUT);
        } catch(KugouApi.ApiException failure) {
            TranslationStatus status="timeout".equals(failure.code)?TranslationStatus.TIMEOUT:
                "network".equals(failure.code)?TranslationStatus.NETWORK:
                "protocol".equals(failure.code) || "limit".equals(failure.code)?TranslationStatus.INVALID:TranslationStatus.PLATFORM_ERROR;
            return new LyricsDocument(original.original,Collections.emptyList(),status);
        } catch(IOException network) {
            return new LyricsDocument(original.original,Collections.emptyList(),TranslationStatus.NETWORK);
        }
    }
    private static void lyricStatus(JSONObject root, String diagnostic) throws IOException {
        if (root == null || !(root.opt("status") instanceof Number)) throw lyricError(diagnostic);
        if (root.optInt("status") != 200) throw error("unavailable");
    }
    static String parseLyrics(JSONObject root) throws IOException {
        Object value = root.opt("content");
        if (value == null || value == JSONObject.NULL || "".equals(value)) return "";
        if (!(value instanceof String)) throw lyricError("lyrics_content_type");
        String encoded = (String)value;
        if (encoded.length() > 700 * 1024) throw error("limit");
        try {
            byte[] bytes = java.util.Base64.getDecoder().decode(encoded);
            if (bytes.length > 512 * 1024) throw error("limit");
            return java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (IllegalArgumentException | java.nio.charset.CharacterCodingException invalid) {
            throw lyricError("lyrics_content_encoding");
        }
    }

    private static KugouApi.ApiException lyricError(String diagnostic) {
        return new KugouApi.ApiException("protocol", "Kugou lyrics request unavailable", diagnostic);
    }

    /** Only the first official search page; this never resolves or probes audio. */
    public List<Song> search(KugouApi.Session session,String keywords) throws IOException {
        return parseSearch(searchResponse(session,keywords,0,10));
    }
    public Page<Song> search(KugouApi.Session session,String keywords,int offset,int limit) throws IOException {
        searchBounds(offset,limit);
        return parseSearch(searchResponse(session,keywords,offset,limit),offset,limit);
    }
    private JSONObject searchResponse(KugouApi.Session session,String keywords,int offset,int limit) throws IOException {
        if(keywords==null || keywords.length()<1 || keywords.length()>128 ||
            keywords.codePoints().anyMatch(Character::isISOControl) || keywords.trim().isEmpty())throw error("protocol");
        Map<String,String> params=map("albumhide","0","iscorrection","1","keyword",keywords.trim(),
            "nocollect","0","page",Integer.toString(offset/limit+1),"pagesize",Integer.toString(limit),"platform","AndroidFilter");
        return api.libraryCall("/v3/search/song",session,params,null,false);
    }
    static List<Song> parseSearch(JSONObject root) throws IOException {
        return searchSongs(success(root),10);
    }
    static Page<Song> parseSearch(JSONObject root,int offset,int limit) throws IOException {
        searchBounds(offset,limit);
        JSONObject data=success(root);
        for(String field:new String[]{"page","page_num"})
            if(data.has(field) && nonnegative(data.opt(field),Integer.MAX_VALUE)!=offset/limit+1)throw error("protocol");
        if(data.has("pagesize") && nonnegative(data.opt("pagesize"),Integer.MAX_VALUE)!=limit)throw error("protocol");
        List<Song> songs=searchSongs(data,limit);
        Object count=first(data,"total","total_count");
        int total=count==null?-1:(int)nonnegative(count,Integer.MAX_VALUE);
        int end=offset+songs.size();
        boolean more=total>=0?end<total:!songs.isEmpty();
        Object flag=first(data,"hasmore","has_more");
        if(flag!=null){
            String text=text(flag);
            boolean hasMore=Boolean.TRUE.equals(flag) || text.equals("1");
            if(!hasMore && !Boolean.FALSE.equals(flag) && !text.equals("0"))throw error("protocol");
            if(total>=0 && more!=hasMore)throw error("incomplete");more=hasMore;
        }
        if(end>20_000 || (more && offset+limit>19_980))throw error("limit");
        if((total>=0 && ((songs.size()>0 && end>total) || (songs.size()<limit && end<total))) ||
            (more && songs.isEmpty()))throw error("incomplete");
        return new Page<>(songs,more?offset+limit:-1,total);
    }
    private static void searchBounds(int offset,int limit) throws IOException {
        if(offset<0 || offset>19_980 || limit<1 || limit>100 || offset%limit!=0)throw error("limit");
    }
    private static List<Song> searchSongs(JSONObject data,int limit) throws IOException {
        JSONArray rows=rows(data,"lists","info");
        if(rows.length()>limit)throw error("limit");
        List<Song> out=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if(row==null)throw error("protocol");
            String hash=text(first(row,"FileHash","hash")).toLowerCase(Locale.ROOT);
            if(!hash.matches("[a-f0-9]{32}"))throw error("identity");
            Object album=first(row,"AlbumID","album_id");
            Object audioId=first(row,"MixSongID","mixsongid","album_audio_id");
            String id=hash+"_"+numeric(album==null?"0":album,true)+"_"+numeric(audioId==null?"0":audioId,true);
            if(!ids.add(id))throw error("identity");
            String artist=searchText(first(row,"SingerName","singername","author_name","singer"));
            String explicit=searchText(first(row,"OriSongName","songname","song_name","SongName"));
            String name=explicit.isEmpty()?searchText(first(row,"FileName","filename","name")):explicit;
            name=name.replaceFirst("(?i)\\.(mp3|flac|wav|wma|aac|m4a|ape|ogg)$","").trim();
            int divider=name.indexOf(" - ");
            if(divider>0 && ((explicit.isEmpty() && artist.isEmpty()) ||
                (!artist.isEmpty() && normalizedArtist(name.substring(0,divider)).equals(normalizedArtist(artist))))) {
                if(artist.isEmpty())artist=name.substring(0,divider).trim(); name=name.substring(divider+3).trim();
            }
            if(name.isEmpty())throw error("protocol");
            String suffix=searchText(row.opt("Suffix"));
            if(!explicit.isEmpty() && !suffix.isEmpty() && !name.contains(suffix))name+=" "+suffix;
            Object seconds=first(row,"Duration","duration");
            Object millis=first(row,"timelen");
            long duration=seconds!=null?nonnegative(seconds,86_400)*1000:nonnegative(millis==null?0:millis,86_400_000);
            Artists authors=artists(row,artist);
            out.add(new Song(id,clean(name,""),searchText(first(row,"AlbumName","album_name","albumname","album")),
                authors.names,duration,cover(row),authors.ids));
        }
        return Collections.unmodifiableList(out);
    }
    private static String searchText(Object value) {
        return clean(text(value).replaceAll("<[^>]*>","").replace("&amp;","&"),"");
    }
    private static String normalizedArtist(String value) {
        return value.toLowerCase(Locale.ROOT).replaceAll("[\\s,、/&，]+","");
    }
    public Audio audio(KugouApi.Session session,String songId,long nowElapsedMs) throws IOException {
        return audio(session,songId,nowElapsedMs,"kugou:128",true);
    }
    public Audio audio(KugouApi.Session session,String songId,long nowElapsedMs,String qualityId) throws IOException {
        return audio(session,songId,nowElapsedMs,qualityId,true);
    }
    public Audio audio(KugouApi.Session session,String songId,long nowElapsedMs,String qualityId,
            boolean allowAuthorized) throws IOException {
        if("kugou:high".equals(qualityId)) {
            if(!allowAuthorized)throw error("permission");
            return authorizedAudio(session,songId,nowElapsedMs,qualityId);
        }
        try {
            return selectAudio(requestAudio(session,songId,nowElapsedMs,qualityId),audioProbe);
        } catch(KugouApi.ApiException denied) {
            if(!"permission".equals(denied.code))throw denied;
            if(!allowAuthorized)throw denied;
            try {
                return authorizedAudio(session,songId,nowElapsedMs,qualityId);
            } catch(IOException authorizationFailure) {
                // The unauthenticated endpoint already established the decisive denial.
                // Do not replace it with a later authorization-chain transport/schema failure.
                if(authorizationFailure!=denied)denied.addSuppressed(sanitizedAudioFailure(authorizationFailure));
                throw denied;
            }
        }
    }
    private static KugouApi.ApiException sanitizedAudioFailure(IOException failure) {
        String code=failure instanceof KugouApi.ApiException
            ? ((KugouApi.ApiException)failure).code
            : failure instanceof java.net.SocketTimeoutException ? "timeout" : "network";
        return new KugouApi.ApiException(code,"Kugou authorized audio unavailable");
    }
    public List<AudioQuality> qualities(KugouApi.Session session,String songId,long nowElapsedMs) throws IOException {
        return qualities(session,songId,nowElapsedMs,true);
    }
    public List<AudioQuality> qualities(KugouApi.Session session,String songId,long nowElapsedMs,
            boolean allowAuthorized) throws IOException {
        List<AudioQuality> out=new ArrayList<>();
        IOException firstFailure=null;
        for(String id:Arrays.asList("kugou:128","kugou:320","kugou:flac","kugou:high")) {
            try {
                audio(session,songId,nowElapsedMs,id,allowAuthorized);
                out.add(new AudioQuality(id,qualityName(id)));
            } catch(IOException unavailable) {
                // Optional authorized Hi-Res discovery must never take down the usable menu.
                // Explicit playback still reports the original error from audio().
                if("kugou:high".equals(id))continue;
                if(unavailable instanceof KugouApi.ApiException &&
                        Arrays.asList("risk","rate","expired").contains(((KugouApi.ApiException)unavailable).code)) {
                    // Do not issue more account-signed requests after a blocking account response.
                    if(out.isEmpty())throw unavailable;
                    break;
                }
                if(!(unavailable instanceof KugouApi.ApiException) ||
                        !Arrays.asList("permission","preview","unavailable","network","source_expired")
                            .contains(((KugouApi.ApiException)unavailable).code)) {
                    // A rejected variant cannot invalidate independently verified choices.
                    // Keep the original failure if no usable variant can be established.
                    if(firstFailure==null)firstFailure=unavailable;
                }
            }
        }
        if(out.isEmpty() && firstFailure!=null)throw firstFailure;
        return Collections.unmodifiableList(out);
    }
    private Audio requestAudio(KugouApi.Session session,String songId,long nowElapsedMs,String qualityId) throws IOException {
        String[] parts=songId.split("_",-1);
        if(parts.length!=3 || !parts[0].matches("[a-f0-9]{32}")) throw error("identity");
        numeric(parts[1],true); numeric(parts[2],true);
        String quality=qualityParameter(qualityId);
        Map<String,String> params=map("album_id",parts[1],"area_code","1","hash",parts[0],"ssa_flag","is_fromtrack",
            "version","11430","page_id","967177915","quality",quality,"album_audio_id",parts[2],"behavior","play",
            "pid","411","cmd","26","pidversion","3001","IsFreePart","0","ppage_id","356753938,823673182,967485191",
            "cdnBackup","1","module","","clientver","11430");
        return parseAudio(api.libraryCall("/v5/url",session,params,null,true),parts[0],nowElapsedMs,qualityId);
    }
    private static String qualityParameter(String id) throws IOException {
        switch(id) {
            case "kugou:128": return "128";
            case "kugou:320": return "320";
            case "kugou:flac": return "flac";
            case "kugou:high": return "high";
            default: throw error("request");
        }
    }
    private static String qualityName(String id) {
        switch(id) {
            case "kugou:128": return "标准音质";
            case "kugou:320": return "高品音质";
            case "kugou:flac": return "无损音质";
            case "kugou:high": return "Hi-Res无损音质";
            default: return "";
        }
    }
    private Audio authorizedAudio(KugouApi.Session session,String songId,long nowElapsedMs,String qualityId) throws IOException {
        if(authorizedAudio==null)throw error("permission");
        KugouAuthorizedAudio.Resolved resolved=authorizedAudio.resolve(session,songId,qualityId);
        Audio audio=parseAuthorizedAudio(resolved.body,resolved.hash,nowElapsedMs,qualityId);
        return "kugou:high".equals(qualityId)
            ? selectHiResAudio(audio,audioInspector)
            : selectAudio(audio,audioProbe);
    }
    static Page<Playlist> parsePlaylists(JSONObject root,int offset,int limit) throws IOException {
        JSONObject data=success(root);
        // A null page with no explicit zero count is not proof of an empty account library.
        if(offset==0 && !(data.opt("info") instanceof JSONArray) &&
            !(data.opt("lists") instanceof JSONArray) &&
            ((data.has("info") && data.isNull("info")) || (data.has("lists") && data.isNull("lists"))) &&
            total(data)!=0)throw error("incomplete");
        JSONArray rows=rows(data,"info","lists");
        if(rows.length()>limit) throw error("limit");
        List<Playlist> out=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if(row==null)throw error("protocol");
            String id=numeric(row.opt("listid"),false); if(!ids.add(id))throw error("identity");
            Object count=first(row,"count","songcount","song_count");
            int tracks=count==null?0:(int)nonnegative(count,1_000_000);
            boolean editable="1".equals(text(row.opt("is_edit")));
            boolean favorite=editable && "2".equals(text(row.opt("is_def")));
            // Own/default lists can carry a zero or empty source sentinel. Only collected
            // lists need a source route; never reject unrelated lists for that sentinel.
            String sourceGlobalId=!editable && !"2".equals(text(row.opt("is_def"))) && present(row,"list_create_gid")
                ? identifier(row.opt("list_create_gid")) : null;
            out.add(new Playlist(id,clean(row.opt("name"),"未命名歌单"),cover(row),tracks,favorite,editable,sourceGlobalId,editMetadata(data,row),
                "1".equals(text(row.opt("is_def"))),editable && "0".equals(text(row.opt("is_def")))));
        }
        int total=total(data); return new Page<>(out,next(offset,rows.length(),total,limit),total);
    }
    static Page<Track> parseTracks(JSONObject root,int offset,int limit) throws IOException {
        JSONObject data=success(root); JSONArray rows=rows(data,"info","songs","lists");
        if(rows.length()>limit)throw error("limit");
        List<Track> out=new ArrayList<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if(row==null)throw error("protocol");
            JSONObject audio=row.optJSONObject("audio_info"); if(audio==null)audio=row;
            Object rawHash=first(audio,"hash","FileHash"); if(rawHash==null)rawHash=first(row,"hash","FileHash");
            String hash=text(rawHash).toLowerCase(Locale.ROOT);
            if(!hash.matches("[a-f0-9]{32}"))throw error("identity");
            Object album=first(row,"album_id","AlbumID"); if(album==null)album=first(audio,"album_id");
            Object audioId=first(row,"album_audio_id","mixsongid","MixSongID"); if(audioId==null)audioId=first(audio,"album_audio_id");
            String id=hash+"_"+numeric(album==null?"0":album,true)+"_"+numeric(audioId==null?"0":audioId,true);
            String artist=clean(first(row,"SingerName","singername","author_name","singer"),"");
            String name=clean(first(row,"OriSongName","songname","song_name","SongName","filename","FileName","name"),"");
            if(name.isEmpty())name=clean(audio.opt("name"),"");
            name=name.replaceFirst("(?i)\\.(mp3|flac|wav|wma|aac|m4a|ape|ogg)$","");
            int divider=name.indexOf(" - ");
            if(divider>0 && (artist.isEmpty() || name.substring(0,divider).equalsIgnoreCase(artist))) {
                if(artist.isEmpty())artist=name.substring(0,divider); name=name.substring(divider+3);
            }
            name=name.trim(); if(name.isEmpty())throw error("protocol");
            Object time=first(row,"timelen"); if(time==null)time=first(audio,"timelen");
            Object seconds=first(row,"duration");
            long duration=time!=null?nonnegative(time,86_400_000):nonnegative(seconds==null?0:seconds,86_400)*1000;
            Artists authors=artists(row,artist);
            Track track=new Track(id,name,clean(first(row,"AlbumName","album_name","albumname","album"),""),
                authors.names,duration,cover(row),authors.ids);
            Object fileId=first(row,"fileid","file_id");
            track.fileId=fileId==null?null:numeric(fileId,true);
            out.add(track);
        }
        int total=total(data); return new Page<>(out,next(offset,rows.length(),total,limit),total);
    }
    static Audio parseAudio(JSONObject root,String hash,long now) throws IOException {
        return parseAudio(root,hash,now,"kugou:128");
    }
    static Audio parseAudio(JSONObject root,String hash,long now,String qualityId) throws IOException {
        if("kugou:high".equals(qualityId))throw error("unavailable");
        if(root==null)throw error("protocol");
        qualityParameter(qualityId);
        JSONObject data=root.optJSONObject("data"); if(data==null)data=root;
        if(Arrays.asList("2","3").contains(text(data.opt("status"))))throw error("permission");
        successAudio(root);
        String echoed=text(data.opt("hash"));
        // The basic endpoint may echo the standard hash for a higher-quality playlist row.
        boolean standardVariant=echoed.matches("(?i)[a-f0-9]{32}") && echoed.equalsIgnoreCase(text(data.opt("std_hash")));
        if(!echoed.isEmpty() && !hash.equalsIgnoreCase(echoed) && !standardVariant)throw error("identity");
        return parseAudioPayload(data,hash,now,qualityId,true);
    }
    static Audio parseAuthorizedAudio(JSONObject root,String hash,long now,String qualityId) throws IOException {
        if(root==null)throw error("protocol");
        qualityParameter(qualityId);
        if(!"1".equals(text(root.opt("status"))))throw error("protocol");
        Object envelope=root.opt("data");
        JSONObject data=envelope instanceof JSONObject ? (JSONObject)envelope : root;
        successAudio(root);
        String echoed=text(data.opt("hash"));
        if(!hash.equalsIgnoreCase(echoed))throw error("identity");
        return parseAudioPayload(data,hash,now,qualityId,false);
    }
    private static Audio parseAudioPayload(JSONObject data,String hash,long now,String qualityId,boolean allowHttpFallback) throws IOException {
        if(present(data,"freeTrialInfo") || present(data,"free_trial_info") || trialFlag(data,"is_free_part") || trialFlag(data,"IsFreePart") || trialFlag(data,"free_part"))throw error("preview");
        JSONObject range=data.optJSONObject("hash_offset");
        if(range!=null && (positive(range,"end_ms") || positive(range,"start_ms")))throw error("preview");
        Object rate=first(data,"bitRate","bitrate","bit_rate");
        long bitrate=nonnegative(rate==null?0:rate,100_000_000);
        String extension=text(first(data,"extName","extname","ext_name"));
        boolean matches=qualityId.equals("kugou:128")
                ? (bitrate==128 || Math.round(bitrate/1000.0)==128) && !"flac".equalsIgnoreCase(extension)
                : qualityId.equals("kugou:320")
                ? (bitrate==320 || Math.round(bitrate/1000.0)==320) && !"flac".equalsIgnoreCase(extension)
                : "flac".equalsIgnoreCase(extension) && ("kugou:flac".equals(qualityId) ? bitrate>320_000 : bitrate>0);
        if(!matches)throw error("unavailable");
        LinkedHashSet<String> candidates=new LinkedHashSet<>();
        Set<String> candidateHosts=new HashSet<>();
        Set<String> httpHosts=new HashSet<>();
        LinkedHashSet<String> originalHttp=new LinkedHashSet<>();
        for(String key:Arrays.asList("url","backupUrl")) {
            Object values=data.opt(key);
            if(values instanceof String)values=new JSONArray().put(values);
            if(!(values instanceof JSONArray))continue;
            JSONArray list=(JSONArray)values;
            for(int i=0;i<Math.min(list.length(),8) && candidates.size()<3;i++) {
                Object value=list.opt(i);
                if(value instanceof String && !((String)value).isEmpty()) {
                    try {
                        String candidate=officialUrl((String)value);
                        String host=URI.create(candidate).getHost().toLowerCase(Locale.ROOT);
                        if(candidateHosts.add(host))candidates.add(candidate);
                    } catch(KugouApi.ApiException invalid) { /* Reject that candidate; never fetch it. */ }
                }
            }
        }
        if(candidates.isEmpty())throw error("unavailable");
        if(allowHttpFallback && ("kugou:128".equals(qualityId) || "kugou:320".equals(qualityId))) {
            for(String key:Arrays.asList("url","backupUrl")) {
                Object values=data.opt(key);
                if(values instanceof String)values=new JSONArray().put(values);
                if(!(values instanceof JSONArray))continue;
                for(int i=0;i<Math.min(((JSONArray)values).length(),8);i++) {
                    Object value=((JSONArray)values).opt(i);
                    if(!(value instanceof String))continue;
                    try {
                        URI original=URI.create((String)value);
                        if("http".equals(original.getScheme()) && HTTP_AUDIO_HOSTS.contains(original.getHost()) &&
                            original.getRawUserInfo()==null && original.getRawFragment()==null && original.getPort()==-1 &&
                            original.getRawAuthority().equals(original.getHost()) && httpHosts.add(original.getHost()))originalHttp.add((String)value);
                    } catch(Exception invalid) { /* Never synthesize a fallback from malformed input. */ }
                }
            }
            candidates.addAll(originalHttp);
        }
        // No documented lifetime field is assumed. A conservative 60s in-memory lease
        // limits reuse; CDN expiry still uses the player's single refresh budget.
        if(now<0 || now>Long.MAX_VALUE-60_000)throw error("protocol");
        return new Audio(candidates.iterator().next(),qualityId,now+60_000,new ArrayList<>(candidates),
            expectedDuration(data));
    }
    interface AudioProbe { void check(String url) throws IOException; }
    interface AudioInspector { void check(String url,long expectedDurationMs) throws IOException; }
    static Audio selectAudio(Audio audio,AudioProbe probe) throws IOException {
        long deadline=System.nanoTime()+12_000_000_000L;
        IOException failure=null;
        List<String> diagnostics=new ArrayList<>();
        for(String candidate:audio.candidates) {
            if(System.nanoTime()>=deadline)break;
            try {probe.check(candidate);return new Audio(candidate,audio.qualityId,audio.expiresAtElapsedMs,Collections.singletonList(candidate),audio.expectedDurationMs);}
            catch(IOException rejected) {
                failure=rejected;
                String kind=rejected instanceof javax.net.ssl.SSLPeerUnverifiedException?"ssl_peer":rejected instanceof javax.net.ssl.SSLException?"ssl":
                    rejected instanceof java.net.UnknownHostException?"dns":rejected instanceof java.net.SocketTimeoutException?"timeout":"io";
                diagnostics.add(kind);
            }
        }
        if(failure instanceof KugouApi.ApiException)throw failure;
        throw new KugouApi.ApiException("network","Kugou audio source unavailable","cdn:"+String.join(",",diagnostics));
    }
    static Audio selectHiResAudio(Audio audio,AudioInspector inspect) throws IOException {
        long deadline=System.nanoTime()+12_000_000_000L;
        IOException failure=null;
        List<String> diagnostics=new ArrayList<>();
        for(String candidate:audio.candidates) {
            if(System.nanoTime()>=deadline)break;
            try {
                inspect.check(candidate,audio.expectedDurationMs);
                return new Audio(candidate,audio.qualityId,audio.expiresAtElapsedMs,
                    Collections.singletonList(candidate),audio.expectedDurationMs);
            } catch(IOException rejected) {
                failure=rejected;
                diagnostics.add(rejected instanceof KugouApi.ApiException
                    ? ((KugouApi.ApiException) rejected).code : "io");
            }
        }
        if(failure instanceof KugouApi.ApiException)throw failure;
        throw new KugouApi.ApiException("network","Kugou Hi-Res audio unavailable",
            "cdn:"+String.join(",",diagnostics));
    }
    static FlacInfo parseFlacInfo(byte[] header,long expectedDurationMs) throws IOException {
        if(header==null || header.length<42 || header[0]!='f' || header[1]!='L' ||
            header[2]!='a' || header[3]!='C')throw error("unavailable");
        int type=header[4]&0x7f;
        int length=((header[5]&0xff)<<16)|((header[6]&0xff)<<8)|(header[7]&0xff);
        if(type!=0 || length!=34)throw error("unavailable");
        long packed=0;
        for(int i=18;i<26;i++)packed=(packed<<8)|(header[i]&0xffL);
        int sampleRate=(int)((packed>>>44)&0xfffff);
        int channels=(int)((packed>>>41)&7)+1;
        int bits=(int)((packed>>>36)&0x1f)+1;
        long totalSamples=packed&0xfffffffffL;
        if(sampleRate<=0 || channels<=0 || bits<=0)throw error("unavailable");
        if(bits<=16 && sampleRate<=48_000)throw error("unavailable");
        if(expectedDurationMs>0) {
            if(totalSamples<=0)throw error("unavailable");
            long actualDurationMs=totalSamples*1000L/sampleRate;
            long tolerance=Math.max(5_000L,(long)Math.ceil(expectedDurationMs*0.05));
            if(Math.abs(actualDurationMs-expectedDurationMs)>tolerance)throw error("unavailable");
        }
        return new FlacInfo(sampleRate,bits,channels,totalSamples);
    }
    private static long expectedDuration(JSONObject data) throws IOException {
        Object value=first(data,"duration_ms","durationMs","timelen","duration");
        if(value==null)return 0;
        long duration=nonnegative(value,86_400_000L);
        if(data.has("duration") && !data.has("duration_ms") && !data.has("durationMs")
            && !data.has("timelen") && duration<=86_400L)duration*=1000L;
        return duration;
    }
    private static void inspectFlacAudio(String url,long expectedDurationMs) throws IOException {
        java.net.HttpURLConnection connection=null;
        try {
            officialUrl(url);
            connection=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
            connection.setUseCaches(false);connection.setRequestProperty("Range","bytes=0-41");
            connection.setRequestProperty("Accept-Encoding","identity");
            int status=connection.getResponseCode();
            if(status!=200 && status!=206)throw error(status==401 || status==403 || status==404 ||
                status==410 || status==416 ? "source_expired" : status==429 ? "rate" : "network");
            officialUrl(connection.getURL().toString());
            byte[] header=new byte[42];
            int offset=0;
            try(InputStream in=connection.getInputStream()) {
                while(offset<header.length) {
                    int count=in.read(header,offset,header.length-offset);
                    if(count<0)break;
                    offset+=count;
                }
            }
            if(offset<header.length)throw error("unavailable");
            parseFlacInfo(header,expectedDurationMs);
        } catch(IOException rejected) {
            throw rejected;
        } finally {
            if(connection!=null)connection.disconnect();
        }
    }
    private static void probeAudio(String url) throws IOException {
        java.net.HttpURLConnection connection=null;
        try {
            URI target=URI.create(url);
            if("http".equals(target.getScheme())) {
                if(!HTTP_AUDIO_HOSTS.contains(target.getHost()) || target.getPort()!=-1 || target.getRawUserInfo()!=null || target.getRawFragment()!=null || !target.getRawAuthority().equals(target.getHost()))throw error("endpoint");
            } else officialUrl(url);
            connection=(java.net.HttpURLConnection)new java.net.URL(url).openConnection();
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(3000);connection.setReadTimeout(3000);
            connection.setUseCaches(false);connection.setRequestProperty("Range","bytes=0-0");connection.setRequestProperty("Accept-Encoding","identity");
            int status=connection.getResponseCode();
            if(status!=200 && status!=206)throw error(status==401 || status==403 || status==404 || status==410 ? "source_expired" : status==429 ? "rate" : "network");
        } finally {if(connection!=null)connection.disconnect();}
    }
    private static void successAudio(JSONObject root) throws IOException {
        if(present(root,"ssaCode") || present(root,"ssa_code"))throw error("risk");
        String status=text(root.opt("status")); String code=text(root.opt("error_code"));
        if((!status.isEmpty() && !status.equals("1") && !status.equals("200")) || (!code.isEmpty() && !code.equals("0")))throw error("platform");
    }
    private static JSONObject success(JSONObject root) throws IOException {
        if(root==null)throw error("protocol"); successAudio(root);
        if(!Arrays.asList("1","200").contains(text(root.opt("status"))))throw error("protocol");
        JSONObject data=root.optJSONObject("data"); if(data==null)throw error("protocol"); return data;
    }
    private static JSONArray rows(JSONObject data,String... keys) throws IOException {
        for(String key:keys)if(data.opt(key) instanceof JSONArray)return data.optJSONArray(key);
        for(String key:keys)if(data.has(key) && data.isNull(key))return new JSONArray();
        throw error("protocol");
    }
    private static int total(JSONObject data) throws IOException {
        Object value=first(data,"total","count"); return value==null?-1:(int)nonnegative(value,1_000_000);
    }
    private static String cover(JSONObject row) {
        Object value=first(row,"coverUrl","cover_url","cover","imgurl","img_url","pic","picurl","Pic","Image");
        JSONObject album=row.optJSONObject("album_info");
        if(value==null && album!=null)value=first(album,"cover","imgurl","img_url");
        if(!(value instanceof String))return null;
        String url=((String)value).replace("{size}","400");
        try{URI uri=new URI(url);String host=uri.getHost();
            if(host==null || !(Arrays.asList("imge.kugou.com","imgessl.kugou.com").contains(host.toLowerCase(Locale.ROOT)) || host.toLowerCase(Locale.ROOT).matches("[a-z0-9-]+\\.kgimg\\.com")) ||
                url.length()>2048 || url.codePoints().anyMatch(c->Character.isWhitespace(c) || Character.isISOControl(c)) ||
                !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getRawUserInfo()!=null ||
                uri.getPort()!=-1 || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !host.equalsIgnoreCase(uri.getRawAuthority()) ||
                uri.getRawPath()==null || uri.getRawPath().length()<=1 || !uri.normalize().getRawPath().equals(uri.getRawPath()))return null;
            return "https://"+host.toLowerCase(Locale.ROOT)+uri.getRawPath();
        }catch(Exception invalid){return null;}
    }
    private static int next(int offset,int length,int total,int limit) throws IOException {
        if(total>=0 && offset+length>total)throw error("incomplete");
        if(length==0) { if(total>offset)throw error("incomplete"); return -1; }
        // nextOffset is a continuation for the page-number API. With no explicit
        // total, even a short page needs one more request to establish completion.
        if(total>=0 && length<limit && offset+length<total)throw error("incomplete");
        return total>=0 && offset+length==total?-1:offset+limit;
    }
    private static void bounds(int offset,int limit) throws IOException {
        if(limit<1 || limit>100 || offset<0 || offset>20_000 || offset%limit!=0)throw error("limit");
    }
    private static Map<String,String> map(String... pairs) {
        Map<String,String> out=new LinkedHashMap<>(); for(int i=0;i<pairs.length;i+=2)out.put(pairs[i],pairs[i+1]);return out;
    }
    private static Object first(JSONObject row,String...keys) {
        for(String key:keys) { Object v=row.opt(key);if(v!=null && v!=JSONObject.NULL && !"".equals(v))return v; }return null;
    }
    private static String text(Object value) {return value instanceof String || value instanceof Number?String.valueOf(value):"";}
    private static String clean(Object value,String fallback) {
        String s=text(value).replaceAll("[\\p{Cc}\\p{Cf}]","").trim();return s.isEmpty()?fallback:s.substring(0,Math.min(s.length(),240));
    }
    private static String numeric(Object value,boolean zero) throws IOException {
        String s=text(value); if(!s.matches(zero?"(?:0|[1-9][0-9]{0,15})":"[1-9][0-9]{0,15}"))throw error("identity");
        if(new java.math.BigInteger(s).compareTo(java.math.BigInteger.valueOf(9_007_199_254_740_991L))>0)throw error("identity");return s;
    }
    private static String identifier(Object value) throws IOException {
        String s=text(value);
        if(!s.matches("[1-9][0-9]{0,31}"))throw error("identity");
        return s;
    }
    private static long nonnegative(Object value,long max) throws IOException {
        String s=text(value);if(!s.matches("(?:0|[1-9][0-9]{0,17})"))throw error("protocol");
        long n;try {n=Long.parseLong(s);}catch(NumberFormatException e){throw error("protocol");}
        if(n>max)throw error("limit");return n;
    }
    private static boolean present(JSONObject row,String key) {return row.has(key) && !row.isNull(key) && !"".equals(row.opt(key)) && !"0".equals(text(row.opt(key)));}
    private static boolean positive(JSONObject row,String key) {try{return Double.parseDouble(text(row.opt(key)))>0;}catch(Exception e){return false;}}
    private static boolean trialFlag(JSONObject row,String key) {
        if(!row.has(key) || row.isNull(key) || Boolean.FALSE.equals(row.opt(key)))return false;
        try {return Double.parseDouble(text(row.opt(key)))!=0;}catch(Exception malformed){return true;}
    }
    static String officialUrl(String value) throws IOException {
        try {
            if(value.startsWith("http://"))value="https://"+value.substring(7);
            URI uri=new URI(value);String host=uri.getHost();
            if(value.length()>8192 || value.chars().anyMatch(Character::isISOControl) || !"https".equals(uri.getScheme()) || host==null || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null)throw error("endpoint");
            host=host.toLowerCase(Locale.ROOT);
            if(!(host.equals("kugou.com") || host.endsWith(".kugou.com")) || (uri.getPort()!=-1 && uri.getPort()!=443))throw error("endpoint");
            if(!uri.getRawAuthority().equalsIgnoreCase(host) && !uri.getRawAuthority().equalsIgnoreCase(host+":443"))throw error("endpoint");
            return value;
        }catch(KugouApi.ApiException e){throw e;}catch(Exception e){throw error("endpoint");}
    }
    private static KugouApi.ApiException error(String code) {return new KugouApi.ApiException(code,"Kugou library request unavailable");}
}
