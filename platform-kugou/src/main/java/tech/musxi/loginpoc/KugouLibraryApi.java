package tech.musxi.loginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.util.*;

/** Device-local read-only adapter of the pinned MIT KuGouMusicApi modules.
 * user_playlist, playlist_track_all_new, search, song_url and util/helper/request.
 * Copyright (c) 2023 MakcRe. No alternate audio or permission bypass.
 */
public final class KugouLibraryApi {
    private final KugouApi api;
    private final AudioProbe audioProbe;
    private static final Set<String> HTTP_AUDIO_HOSTS = new HashSet<>(Arrays.asList("fs.youthandroid2.kugou.com", "fs.youthandroid.kugou.com"));
    public KugouLibraryApi(KugouApi api) { this(api, KugouLibraryApi::probeAudio); }
    KugouLibraryApi(KugouApi api, AudioProbe audioProbe) { this.api=Objects.requireNonNull(api); this.audioProbe=Objects.requireNonNull(audioProbe); }
    public static final class Page<T> {
        public final List<T> items; public final int nextOffset, total;
        Page(List<T> items, int nextOffset, int total) {
            this.items = Collections.unmodifiableList(new ArrayList<>(items)); this.nextOffset = nextOffset; this.total = total;
        }
    }
    public static final class Playlist {
        public final String id, name, coverUrl; public final int trackCount;
        Playlist(String id, String name, String coverUrl, int count) { this.id=id; this.name=name; this.coverUrl=coverUrl; trackCount=count; }
    }
    public static class Song {
        public final String id, name, album; public final List<String> artists; public final long durationMs;
        Song(String id,String name,String album,List<String> artists,long durationMs) {
            this.id=id; this.name=name; this.album=album; this.artists=Collections.unmodifiableList(new ArrayList<>(artists)); this.durationMs=durationMs;
        }
    }
    public static final class Track extends Song {
        Track(String id,String name,String album,List<String> artists,long durationMs) { super(id,name,album,artists,durationMs); }
    }
    public static final class Audio {
        public final String url; public final long expiresAtElapsedMs;
        final List<String> candidates;
        Audio(String url,long expiresAtElapsedMs,List<String> candidates) { this.url=url; this.expiresAtElapsedMs=expiresAtElapsedMs; this.candidates=Collections.unmodifiableList(new ArrayList<>(candidates)); }
        @Override public String toString() { return "Audio(redacted)"; }
    }
    public Page<Playlist> playlists(KugouApi.Session session,int offset,int limit) throws IOException {
        bounds(offset,limit);
        String body = "{\"userid\":"+JSONObject.quote(session.userId)+",\"token\":"+JSONObject.quote(session.token)+
            ",\"total_ver\":979,\"type\":2,\"page\":"+(offset/limit+1)+",\"pagesize\":"+limit+"}";
        JSONObject root=api.libraryCall("/v7/get_all_list",session,map("plat","1"),body,false);
        return parsePlaylists(root,offset,limit);
    }
    public Page<Track> tracks(KugouApi.Session session,String playlistId,int offset,int limit) throws IOException {
        bounds(offset,limit); numeric(playlistId,false);
        String body="{\"listid\":"+JSONObject.quote(playlistId)+",\"userid\":"+JSONObject.quote(session.userId)+
            ",\"area_code\":1,\"show_relate_goods\":0,\"pagesize\":"+limit+",\"allplatform\":1,\"show_cover\":1,\"type\":0,\"token\":"+
            JSONObject.quote(session.token)+",\"page\":"+(offset/limit+1)+"}";
        return parseTracks(api.libraryCall("/v4/get_list_all_file",session,Collections.emptyMap(),body,false),offset,limit);
    }
    /** Only the first official search page; this never resolves or probes audio. */
    public List<Song> search(KugouApi.Session session,String keywords) throws IOException {
        if(keywords==null || keywords.length()<1 || keywords.length()>128 ||
            keywords.codePoints().anyMatch(Character::isISOControl) || keywords.trim().isEmpty())throw error("protocol");
        Map<String,String> params=map("albumhide","0","iscorrection","1","keyword",keywords.trim(),
            "nocollect","0","page","1","pagesize","10","platform","AndroidFilter");
        return parseSearch(api.libraryCall("/v3/search/song",session,params,null,false));
    }
    static List<Song> parseSearch(JSONObject root) throws IOException {
        JSONObject data=success(root); JSONArray rows=rows(data,"lists","info");
        if(rows.length()>10)throw error("limit");
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
            out.add(new Song(id,clean(name,""),searchText(first(row,"AlbumName","album_name","albumname","album")),
                artist.isEmpty()?Collections.emptyList():Collections.singletonList(artist),duration));
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
        String[] parts=songId.split("_",-1);
        if(parts.length!=3 || !parts[0].matches("[a-f0-9]{32}")) throw error("identity");
        numeric(parts[1],true); numeric(parts[2],true);
        Map<String,String> params=map("album_id",parts[1],"area_code","1","hash",parts[0],"ssa_flag","is_fromtrack",
            "version","11430","page_id","967177915","quality","128","album_audio_id",parts[2],"behavior","play",
            "pid","411","cmd","26","pidversion","3001","IsFreePart","0","ppage_id","356753938,823673182,967485191",
            "cdnBackup","1","module","","clientver","11430");
        return selectAudio(parseAudio(api.libraryCall("/v5/url",session,params,null,true),parts[0],nowElapsedMs),audioProbe);
    }
    static Page<Playlist> parsePlaylists(JSONObject root,int offset,int limit) throws IOException {
        JSONObject data=success(root); JSONArray rows=rows(data,"info","lists");
        if(rows.length()>limit) throw error("limit");
        List<Playlist> out=new ArrayList<>(); Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++) {
            JSONObject row=rows.optJSONObject(i); if(row==null)throw error("protocol");
            String id=numeric(row.opt("listid"),false); if(!ids.add(id))throw error("identity");
            Object count=first(row,"count","songcount","song_count");
            int tracks=count==null?0:(int)nonnegative(count,1_000_000);
            out.add(new Playlist(id,clean(row.opt("name"),"未命名歌单"),null,tracks));
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
            out.add(new Track(id,name,clean(first(row,"AlbumName","album_name","albumname","album"),""),
                artist.isEmpty()?Collections.emptyList():Collections.singletonList(artist),duration));
        }
        int total=total(data); return new Page<>(out,next(offset,rows.length(),total,limit),total);
    }
    static Audio parseAudio(JSONObject root,String hash,long now) throws IOException {
        if(root==null)throw error("protocol");
        JSONObject data=root.optJSONObject("data"); if(data==null)data=root;
        if(Arrays.asList("2","3").contains(text(data.opt("status"))))throw error("permission");
        successAudio(root);
        String echoed=text(data.opt("hash"));
        if(!echoed.isEmpty() && !hash.equalsIgnoreCase(echoed))throw error("identity");
        if(present(data,"freeTrialInfo") || present(data,"free_trial_info") || trialFlag(data,"is_free_part") || trialFlag(data,"IsFreePart") || trialFlag(data,"free_part"))throw error("permission");
        JSONObject range=data.optJSONObject("hash_offset");
        if(range!=null && (positive(range,"end_ms") || positive(range,"start_ms")))throw error("permission");
        Object rate=first(data,"bitRate","bitrate","bit_rate");
        long bitrate=nonnegative(rate==null?0:rate,100_000_000);
        if(bitrate!=128 && Math.round(bitrate/1000.0)!=128)throw error("unavailable");
        if("flac".equalsIgnoreCase(text(first(data,"extName","extname","ext_name"))))throw error("unavailable");
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
                        URI original=URI.create((String)value);
                        if("http".equals(original.getScheme()) && HTTP_AUDIO_HOSTS.contains(original.getHost()) &&
                            original.getRawUserInfo()==null && original.getRawFragment()==null && original.getPort()==-1 &&
                            original.getRawAuthority().equals(original.getHost()) && httpHosts.add(original.getHost()))originalHttp.add((String)value);
                    } catch(KugouApi.ApiException invalid) { /* Reject that candidate; never fetch it. */ }
                }
            }
        }
        if(candidates.isEmpty())throw error("unavailable");
        candidates.addAll(originalHttp);
        // No documented lifetime field is assumed. A conservative 60s in-memory lease
        // limits reuse; CDN expiry still uses the player's single refresh budget.
        if(now<0 || now>Long.MAX_VALUE-60_000)throw error("protocol");
        return new Audio(candidates.iterator().next(),now+60_000,new ArrayList<>(candidates));
    }
    interface AudioProbe { void check(String url) throws IOException; }
    static Audio selectAudio(Audio audio,AudioProbe probe) throws IOException {
        long deadline=System.nanoTime()+12_000_000_000L;
        IOException failure=null;
        List<String> diagnostics=new ArrayList<>();
        for(String candidate:audio.candidates) {
            if(System.nanoTime()>=deadline)break;
            try {probe.check(candidate);return new Audio(candidate,audio.expiresAtElapsedMs,Collections.singletonList(candidate));}
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
