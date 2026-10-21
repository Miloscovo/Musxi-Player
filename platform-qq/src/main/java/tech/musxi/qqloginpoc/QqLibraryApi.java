package tech.musxi.qqloginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.net.URI;
import java.net.HttpURLConnection;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.BooleanSupplier;

/** Read-only native adapter of QQMusicApi 0.8.1 modules/{user,songlist,song,lyric}.py,
 * commit 3fc57f02e4eddb68a069bba4e0e12a7893f2a814, GPL-3.0-or-later.
 * Credentials and signed URLs never become library DTOs. */
public final class QqLibraryApi {
    public static final class Playlist {
        public final String id, name, coverUrl; public final int trackCount;
        Playlist(String id,String name,int count){this(id,name,count,null);}
        Playlist(String id,String name,int count,String coverUrl){this.id=id;this.name=name;trackCount=count;this.coverUrl=coverUrl;}
    }
    public static final class Song {
        public final String id,name,album,coverUrl; public final List<String> artists; public final long durationMs;
        final String mediaMid; final int type; final long numericId;
        Song(String id,String name,List<String> artists,String album,long duration,String mediaMid,int type){
            this(id,name,artists,album,duration,mediaMid,type,null);
        }
        Song(String id,String name,List<String> artists,String album,long duration,String mediaMid,int type,String coverUrl){
            this(id,0,name,artists,album,duration,mediaMid,type,coverUrl);
        }
        Song(String id,long numericId,String name,List<String> artists,String album,long duration,String mediaMid,int type,String coverUrl){
            this.id=id;this.name=name;this.artists=Collections.unmodifiableList(new ArrayList<>(artists));this.album=album;
            durationMs=duration;this.mediaMid=mediaMid;this.type=type;this.numericId=numericId;this.coverUrl=coverUrl;
        }
    }
    public static final class Page<T> {
        public final List<T> items; public final int nextOffset,total;
        Page(List<T> items,int next,int total){this.items=Collections.unmodifiableList(new ArrayList<>(items));nextOffset=next;this.total=total;}
    }
    public static final class Audio {
        public final String url; public final long expiresAtElapsedMs;
        Audio(String url,long expires){this.url=url;expiresAtElapsedMs=expires;}
        @Override public String toString(){return "Audio(redacted)";}
    }
    interface AudioProbe { void check(String url) throws IOException; }
    private final QqMusicApi api; private final BooleanSupplier valid; private final AudioProbe probe;
    private final long deadline=System.nanoTime()+60_000_000_000L;
    public QqLibraryApi(QqMusicApi api){this(api,()->true);}
    public QqLibraryApi(QqMusicApi api,BooleanSupplier valid){this(api,valid,QqLibraryApi::probeAudio);}
    QqLibraryApi(QqMusicApi api,BooleanSupplier valid,AudioProbe probe){this.api=api;this.valid=valid;this.probe=probe;}
    private void checkpoint() throws IOException {
        if(!valid.getAsBoolean() || Thread.currentThread().isInterrupted())throw error("account_changed");
        if(System.nanoTime()>deadline)throw error("timeout");
    }
    private JSONObject call(QqMusicApi.Session session,String module,String method,JSONObject param) throws IOException {
        checkpoint(); JSONObject result=api.library(session,module,method,param);checkpoint();return result;
    }
    /** Created lists are an atomic endpoint; favorite lists have their own continuation.
     * Build one bounded snapshot before slicing, so merging cannot lose favorites. */
    public Page<Playlist> playlists(QqMusicApi.Session session,int offset,int limit) throws IOException {
        bounds(offset,limit);
        JSONObject created=call(session,"music.musicasset.PlaylistBaseRead","GetPlaylistByUin",obj("uin",session.musicId));
        if(!flag(created,"bFinish",true))throw error("incomplete");
        JSONArray rows=array(created,"v_playlist");
        if(rows.length()>20_000)throw error("limit");
        LinkedHashMap<String,Playlist> lists=new LinkedHashMap<>();
        appendLists(rows,lists,true);
        String euin=api.encryptedUin(session);
        if(euin.isEmpty())throw error("identity");
        int favOffset=0; Integer expectedTotal=null;
        while(true){
            JSONObject fav=call(session,"music.musicasset.PlaylistFavRead","CgiGetPlaylistFavInfo",obj("uin",euin,"offset",favOffset,"size",100));
            if(flag(fav,"hide",false))throw error("permission");
            if(fav.has("v_failTids") && array(fav,"v_failTids").length()!=0)throw error("incomplete");
            JSONArray items=array(fav,"v_list");if(items.length()>100)throw error("protocol");
            int total=number(fav.opt("total"),20_000);
            if(expectedTotal!=null && expectedTotal!=total)throw error("incomplete");expectedTotal=total;
            boolean more=flag(fav,"hasmore",true);
            if(favOffset+items.length()>total || (more && (items.length()==0 || favOffset+items.length()>=total)) || (!more && favOffset+items.length()!=total))throw error("incomplete");
            appendLists(items,lists,false);
            if(lists.size()>20_000)throw error("limit");
            if(!more)break;
            favOffset+=items.length();if(favOffset>20_000)throw error("limit");
        }
        List<Playlist> all=new ArrayList<>(lists.values());
        if(offset>all.size())throw error("incomplete");
        int end=Math.min(offset+limit,all.size());
        return new Page<>(all.subList(offset,end),end<all.size()?end:-1,all.size());
    }
    static void appendLists(JSONArray rows,LinkedHashMap<String,Playlist> lists,boolean created) throws IOException {
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)throw error("protocol");
            if(created && flag(row,"invalid",false))continue;
            String tid=numeric(first(row,"tid","id","dissid"),true);
            int dir=number(first(row,"dirId","dirid"),1_000_000);
            if(tid.equals("0") && dir!=201)throw error("identity");
            String id=tid+"_"+dir;playlistParts(id);
            Playlist list=new Playlist(id,clean(first(row,"title","dirName","dissname","name"),"未命名歌单"),number(first(row,"songNum","songnum","song_cnt"),1_000_000),
                image(first(row,"cover_url_big","cover_url_medium","cover_url_small","picurl","picUrl","logo","cover","coverUrl")));
            lists.putIfAbsent(id,list);
        }
    }
    public Page<Song> tracks(QqMusicApi.Session session,String playlist,int offset,int limit) throws IOException {
        bounds(offset,limit);String[] parts=playlistParts(playlist);
        JSONObject param=obj("disstid",Long.parseLong(parts[0]),"dirid",Integer.parseInt(parts[1]),"tag",true,
            "song_begin",offset,"song_num",limit,"userinfo",true,"orderlist",true,"onlysonglist",false);
        if(parts[0].equals("0"))put(param,"enc_host_uin",api.encryptedUin(session));
        return parseTracks(call(session,"music.srfDissInfo.DissInfo","CgiGetDiss",param),playlist,offset,limit);
    }
    static Page<Song> parseTracks(JSONObject data,String playlist,int offset,int limit) throws IOException {
        bounds(offset,limit);String[] expected=playlistParts(playlist);
        if(data.has("subcode") && number(data.opt("subcode"),Integer.MAX_VALUE)!=0)throw error("permission");
        JSONObject info=data.optJSONObject("dirinfo");if(info==null)throw error("protocol");
        Object echoed=first(info,"id","tid","dissid");
        // tid=0 is QQ's own-likes alias; its response may echo the real backing TID.
        if(echoed!=null && !expected[0].equals("0") && !numeric(echoed,true).equals(expected[0]))throw error("identity");
        Object dir=first(info,"dirid","dirId");
        if(dir!=null && number(dir,1_000_000)!=Integer.parseInt(expected[1]))throw error("identity");
        JSONArray rows=array(data,"songlist");if(rows.length()>limit)throw error("protocol");
        if(data.has("songlist_size") && number(data.opt("songlist_size"),20_000)!=rows.length())throw error("incomplete");
        int total=number(data.opt("total_song_num"),20_000);
        boolean more=data.has("hasmore")?flag(data,"hasmore",true):offset+rows.length()<total;
        if(offset+rows.length()>total || (more && rows.length()==0) || (!more && offset+rows.length()!=total) || (more && offset+rows.length()>=total))throw error("incomplete");
        List<Song> items=new ArrayList<>();
        for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row==null)throw error("protocol");items.add(song(row));}
        return new Page<>(items,more?offset+items.size():-1,total);
    }
    static Song song(JSONObject row) throws IOException {
        String mid=text(row.opt("mid"));requireMid(mid);
        JSONObject album=row.optJSONObject("album"),file=row.optJSONObject("file");
        String media=file==null?"":text(file.opt("media_mid"));
        if(!media.isEmpty() && !media.matches("[A-Za-z0-9]{14,64}"))throw error("identity");
        List<String> artists=new ArrayList<>();JSONArray singer=row.optJSONArray("singer");
        if(singer!=null){if(singer.length()>100)throw error("limit");for(int i=0;i<singer.length();i++){
            JSONObject artist=singer.optJSONObject(i);if(artist==null)throw error("protocol");artists.add(clean(artist.opt("name"),"未知歌手"));}}
        long numericId=row.has("id")?Long.parseLong(numeric(row.opt("id"),false)):0;
        return new Song(mid,numericId,clean(first(row,"name","title"),"暂不可用歌曲"),artists,album==null?"":clean(album.opt("name"),""),
            (long)number(row.opt("interval"),604_800)*1000,media,row.has("type")?number(row.opt("type"),100):0,albumCover(row,album));
    }
    public List<Song> search(QqMusicApi.Session session,String keywords) throws IOException {
        return parseSearch(searchResponse(session,keywords,0,10));
    }
    public Page<Song> search(QqMusicApi.Session session,String keywords,int offset,int limit) throws IOException {
        searchBounds(offset,limit);
        return parseSearch(searchResponse(session,keywords,offset,limit),offset,limit);
    }
    private JSONObject searchResponse(QqMusicApi.Session session,String keywords,int offset,int limit) throws IOException {
        if(session==null)throw error("identity");
        if(keywords==null)throw error("request");
        String query=keywords.trim();
        if(query.isEmpty() || query.codePoints().anyMatch(Character::isISOControl) || query.codePointCount(0,query.length())>120)
            throw error("request");
        String searchid=Long.toString(new java.security.SecureRandom().nextLong() & Long.MAX_VALUE);
        JSONObject param=obj("query",query,"search_type",0,"num_per_page",limit,"page_num",offset/limit+1,
            "highlight",false,"grp",true,"selectors",new JSONObject(),"vec_selectors",new JSONArray(),"searchid",searchid);
        return call(session,"music.search.SearchCgiService","DoSearchForQQMusicMobile",param);
    }
    static List<Song> parseSearch(JSONObject data) throws IOException {
        JSONObject body=data.optJSONObject("body");if(body==null)throw error("protocol");
        JSONArray rows=array(body,"item_song");if(rows.length()>10)throw error("limit");
        JSONObject meta=data.optJSONObject("meta");if(meta==null)throw error("protocol");
        if(number(meta.opt("perpage"),Integer.MAX_VALUE)!=10)throw error("protocol");
        String next=text(meta.opt("nextpage"));
        if(!next.equals("-1") && !next.equals("2"))throw error("protocol");
        int total=number(meta.opt("sum"),Integer.MAX_VALUE);
        if(total<rows.length())throw error("incomplete");
        List<Song> songs=new ArrayList<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)throw error("protocol");
            songs.add(song(row));
        }
        return Collections.unmodifiableList(songs);
    }
    static Page<Song> parseSearch(JSONObject data,int offset,int limit) throws IOException {
        searchBounds(offset,limit);
        JSONObject body=data.optJSONObject("body"),meta=data.optJSONObject("meta");
        if(body==null || meta==null)throw error("protocol");
        JSONArray rows=array(body,"item_song");if(rows.length()>limit)throw error("limit");
        if(number(meta.opt("perpage"),Integer.MAX_VALUE)!=limit)throw error("protocol");
        int page=offset/limit+1;
        String next=text(meta.opt("nextpage"));
        if(!next.equals("-1") && !next.equals(Integer.toString(page+1)))throw error("protocol");
        for(String field:new String[]{"curpage","page_num"})
            if(meta.has(field) && number(meta.opt(field),Integer.MAX_VALUE)!=page)throw error("protocol");
        int total=meta.has("sum")?number(meta.opt("sum"),Integer.MAX_VALUE):-1;
        int end=offset+rows.length();boolean more=!next.equals("-1");
        if(end>20_000 || (more && offset+limit>19_980))throw error("limit");
        if((more && rows.length()==0) || (more && rows.length()<limit) ||
            (total>=0 && ((rows.length()>0 && end>total) || (more && end>=total) || (!more && end<total))))throw error("incomplete");
        List<Song> songs=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)throw error("protocol");
            Song item=song(row);if(!ids.add(item.id))throw error("identity");songs.add(item);
        }
        return new Page<>(songs,more?offset+limit:-1,total);
    }
    private static void searchBounds(int offset,int limit) throws IOException {
        if(offset<0 || offset>19_980 || limit<1 || limit>100 || offset%limit!=0)throw error("limit");
    }
    private static String albumCover(JSONObject row,JSONObject album) {
        String cover=album==null?null:image(first(album,"picUrl","picurl","cover_url","cover"));
        if(cover==null)cover=image(first(row,"album_pic","albumPic","cover_url","picurl"));
        if(cover!=null)return cover;
        String mid=text(album==null?null:first(album,"mid","pmid"));
        if(mid.isEmpty())mid=text(first(row,"albummid","albumMID"));
        return mid.matches("[A-Za-z0-9]{14}")?"https://y.gtimg.cn/music/photo_new/T002R300x300M000"+mid+".jpg":null;
    }
    private static String image(Object value) {
        if(!(value instanceof String))return null;String url=(String)value;
        try{URI uri=new URI(url);String host=uri.getHost();
            if(host==null || !Arrays.asList("y.gtimg.cn","qpic.y.qq.com","i.gtimg.cn").contains(host.toLowerCase(Locale.ROOT)) ||
                url.length()>2048 || url.codePoints().anyMatch(c->Character.isWhitespace(c) || Character.isISOControl(c)) ||
                !("https".equals(uri.getScheme()) || "http".equals(uri.getScheme())) || uri.getRawUserInfo()!=null ||
                uri.getPort()!=-1 || uri.getRawQuery()!=null || uri.getRawFragment()!=null || !host.equalsIgnoreCase(uri.getRawAuthority()) ||
                uri.getRawPath()==null || uri.getRawPath().length()<=1 || !uri.normalize().getRawPath().equals(uri.getRawPath()))return null;
            return "https://"+host.toLowerCase(Locale.ROOT)+uri.getRawPath();
        }catch(Exception invalid){return null;}
    }
    public Song song(QqMusicApi.Session session,String mid) throws IOException {
        if(session==null)throw error("identity");
        requireMid(mid);
        JSONObject detail=call(session,"music.pf_song_detail_svr","get_song_detail_yqq",obj("song_mid",mid));
        JSONObject track=detail.optJSONObject("track_info");if(track==null)throw error("protocol");
        Song song=song(track);if(!mid.equals(song.id))throw error("identity");
        return song;
    }
    public List<String> likedIds(QqMusicApi.Session session) throws IOException {
        if(session==null)throw error("identity");
        ArrayList<String> ids=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        int offset=0;int expectedTotal=-1;
        while(true){
            Page<Song> page=tracks(session,"0_201",offset,100);
            if(expectedTotal<0)expectedTotal=page.total;
            else if(expectedTotal!=page.total)throw error("incomplete");
            if(page.total>20_000)throw error("limit");
            if(page.items.isEmpty() && expectedTotal!=0)throw error("incomplete");
            for(Song item:page.items){if(!seen.add(item.id))throw error("incomplete");ids.add(item.id);}
            if(ids.size()>20_000)throw error("limit");
            if(page.nextOffset<0){
                if(ids.size()!=expectedTotal)throw error("incomplete");
                return Collections.unmodifiableList(ids);
            }
            if(page.nextOffset!=offset+page.items.size())throw error("protocol");
            offset=page.nextOffset;
        }
    }
    public void setLiked(QqMusicApi.Session session,String songMid,boolean liked) throws IOException {
        if(session==null)throw error("identity");
        requireMid(songMid);
        JSONObject detail=call(session,"music.pf_song_detail_svr","get_song_detail_yqq",obj("song_mid",songMid));
        JSONObject track=detail.optJSONObject("track_info");if(track==null)throw error("protocol");
        Song song=song(track);if(!songMid.equals(song.id) || song.numericId<=0 || !track.has("type"))throw error("identity");
        JSONArray info=new JSONArray().put(obj("songId",song.numericId,"songType",song.type));
        JSONObject param=obj("dirId",201,"tid",0,"bFmtUtf8",true,"v_songInfo",info);
        JSONObject result=call(session,"music.musicasset.PlaylistDetailWrite",liked?"AddSonglist":"DelSonglist",param);
        Object ret=result.opt("retCode");
        if(!(ret instanceof Number))throw error("protocol");
        String raw=String.valueOf(ret);
        if(!raw.matches("(?:0|80092)"))throw error("permission");
        int code=Integer.parseInt(raw);
        if(code==80092)throw error("permission");
    }
    public String lyrics(QqMusicApi.Session session,String songMid) throws IOException {
        if(session==null)throw error("identity");
        requireMid(songMid);
        JSONObject response=call(session,"music.musichallSong.PlayLyricInfo","GetPlayLyricInfo",
            obj("crypt",0,"lrc_t",0,"qrc",0,"qrc_t",0,"roma",0,"roma_t",0,
                "trans",0,"trans_t",0,"needSingingAnnotations",false,"type",1,"songMid",songMid));
        Object value=response.opt("lyric");
        if(value==null || value==JSONObject.NULL || "".equals(value))return "";
        if(!(value instanceof String))throw error("protocol");
        return parseLyrics((String)value);
    }
    static String parseLyrics(String value) throws IOException {
        if(value==null)throw error("protocol");
        if(value.length()>700*1024)throw error("limit");
        int bytes=value.getBytes(StandardCharsets.UTF_8).length;
        if(bytes>700*1024 || bytes>512*1024 && !base64Alphabet(value))throw error("limit");
        if(looksHexCipher(value))throw error("protocol");
        if(bytes<=512*1024){
            String decoded=decodeLyricBase64(value,false);
            return decoded!=null?decoded:value;
        }
        if(bytes>700*1024)throw error("limit");
        String decoded=decodeLyricBase64(value,true);
        if(decoded==null)throw error("limit");
        return decoded;
    }
    private static String decodeLyricBase64(String value,boolean required) throws IOException {
        if(!base64Alphabet(value)) {
            if(required)throw error("protocol");
            return null;
        }
        byte[] decoded;
        try{decoded=Base64.getDecoder().decode(value);}
        catch(IllegalArgumentException invalid){
            if(required || value.endsWith("="))throw error("protocol");
            return null;
        }
        if(decoded.length>512*1024)throw error("limit");
        String text;
        try{
            text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(decoded)).toString();
        }catch(CharacterCodingException invalid){
            if(required)throw error("protocol");
            return null;
        }
        if(text.startsWith("\uFEFF"))text=text.substring(1);
        if(!timestampedLrc(text)){
            if(required)throw error("protocol");
            return null;
        }
        return text;
    }
    private static boolean base64Alphabet(String value) {
        if(value.length()<4 || value.length()%4==1)return false;
        int padding=0;
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c=='='){padding++;if(padding>2)return false;}
            else if(padding!=0 || !(c>='A'&&c<='Z' || c>='a'&&c<='z' || c>='0'&&c<='9' || c=='+' || c=='/'))return false;
        }
        return true;
    }
    private static boolean timestampedLrc(String value) {
        return java.util.regex.Pattern.compile("(?m)^\\s*\\[[0-9]{1,3}:[0-5][0-9](?:[.:][0-9]{1,3})?\\]")
            .matcher(value).find();
    }
    private static boolean looksHexCipher(String value) {
        String text=value.trim();int digits=0;
        if(text.startsWith("0x") || text.startsWith("0X"))text=text.substring(2);
        for(int i=0;i<text.length();i++){
            char c=text.charAt(i);
            if(Character.isWhitespace(c))continue;
            if(!(c>='0'&&c<='9' || c>='a'&&c<='f' || c>='A'&&c<='F'))return false;
            digits++;
        }
        return digits>=32 && digits%2==0;
    }
    public Audio audio(QqMusicApi.Session session,String mid,long now) throws IOException {
        Song song=song(session,mid);
        String filename="M500"+(song.mediaMid.isEmpty()?mid+mid:song.mediaMid)+".mp3";
        String guid=Long.toString(1_000_000_000L+new java.security.SecureRandom().nextInt(1_000_000_000));
        JSONObject vkey=call(session,"music.vkey.GetVkey","UrlGetVkey",obj("uin",session.musicId,"filename",new JSONArray().put(filename),
            "guid",guid,
            "songmid",new JSONArray().put(mid),"songtype",new JSONArray().put(song.type),"ctx",0));
        audioPath(vkey,mid,filename);
        JSONObject dispatch=call(session,"music.audioCdnDispatch.cdnDispatch","GetCdnDispatch",obj("guid",guid,"uid","0","use_new_domain",1,"use_ipv6",1));
        List<String> urls=audioCandidates(vkey,dispatch,mid,filename);
        long probeDeadline=System.nanoTime()+12_000_000_000L;
        for(String url:urls){checkpoint();if(System.nanoTime()>probeDeadline)break;
            try{probe.check(url);checkpoint();if(now<0 || now>Long.MAX_VALUE-60_000)throw error("protocol");return new Audio(url,now+60_000);}
            catch(QqMusicApi.ApiException failure){if(!failure.code.equals("network") && !failure.code.equals("source_expired"))throw failure;}
            catch(IOException unavailable){ /* Bounded HTTPS candidates only. */ }
        }
        throw error("network");
    }
    private static String audioPath(JSONObject vkey,String mid,String filename) throws IOException {
        JSONArray rows=array(vkey,"midurlinfo");if(rows.length()!=1)throw error("protocol");
        JSONObject row=rows.optJSONObject(0);if(row==null)throw error("protocol");
        if(!mid.equals(text(row.opt("songmid"))))throw error("identity");
        int result=number(row.opt("result"),Integer.MAX_VALUE);
        if(result!=0)throw diagnostic("permission",result==104003?"vkey_denied_104003":result==104004?"vkey_denied_104004":result==104013?"vkey_denied_104013":"vkey_denied_other");
        if(!filename.equals(text(row.opt("filename"))))throw diagnostic("permission","filename_mismatch");
        if(!text(row.opt("ekey")).isEmpty())throw diagnostic("permission","encrypted_audio");
        for(String key:new String[]{"trybegin","try_begin","tryend","try_end","is_preview"}){
            Object value=row.opt(key);if(value!=null && value!=JSONObject.NULL && !Boolean.FALSE.equals(value) && (Boolean.TRUE.equals(value) || (!text(value).isEmpty() && !text(value).equals("0"))))throw diagnostic("permission","preview_audio");
        }
        String purl=text(row.opt("purl"));
        try {URI relative=new URI(purl);if(relative.isAbsolute() || relative.getRawAuthority()!=null || relative.getRawFragment()!=null ||
            !(filename.equals(relative.getRawPath()) || ("/"+filename).equals(relative.getRawPath())))throw diagnostic("permission","purl_path_mismatch");}
        catch(java.net.URISyntaxException invalid){throw error("endpoint");}
        return purl;
    }
    static List<String> audioCandidates(JSONObject vkey,JSONObject dispatch,String mid,String filename) throws IOException {
        String purl=audioPath(vkey,mid,filename);
        JSONArray sip=array(dispatch,"sip");LinkedHashMap<String,String> urls=new LinkedHashMap<>();
        for(int i=0;i<Math.min(sip.length(),12) && urls.size()<3;i++){
            String base=text(sip.opt(i));if(base.startsWith("http://"))base="https://"+base.substring(7);
            try {officialUrl(base);URI uri=URI.create(base);if(uri.getRawQuery()!=null || !base.endsWith("/"))continue;
                String url=officialUrl(base+(purl.startsWith("/")?purl.substring(1):purl));urls.putIfAbsent(uri.getHost(),url);
            }catch(IOException | IllegalArgumentException invalid){ /* Reject this untrusted candidate. */ }
        }
        if(urls.isEmpty())throw error("unavailable");return new ArrayList<>(urls.values());
    }
    static String officialUrl(String value) throws IOException {
        try {URI uri=new URI(value);String host=uri.getHost();
            if(value.length()>8192 || value.chars().anyMatch(Character::isISOControl) || !"https".equals(uri.getScheme()) || uri.isOpaque() || host==null || uri.getRawUserInfo()!=null || uri.getRawFragment()!=null || (uri.getPort()!=-1 && uri.getPort()!=443))throw error("endpoint");
            host=host.toLowerCase(Locale.ROOT);
            if(!(host.equals("stream.qqmusic.qq.com") || host.endsWith(".stream.qqmusic.qq.com") || host.equals("aqqmusic.tc.qq.com")) ||
                !host.matches("[a-z0-9]+(?:[.-][a-z0-9]+)*") || !(uri.getRawAuthority().equalsIgnoreCase(host) || uri.getRawAuthority().equalsIgnoreCase(host+":443")))throw error("endpoint");
            return value;
        }catch(java.net.URISyntaxException invalid){throw error("endpoint");}
    }
    private static void probeAudio(String url) throws IOException {
        officialUrl(url);HttpURLConnection connection=null;
        try{connection=(HttpURLConnection)new java.net.URL(url).openConnection();connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(3000);connection.setReadTimeout(3000);connection.setUseCaches(false);
            connection.setRequestProperty("Range","bytes=0-0");connection.setRequestProperty("Accept-Encoding","identity");
            int status=connection.getResponseCode();if(status!=200 && status!=206)throw error(status==401 || status==403 || status==404 || status==410?"source_expired":status==429?"rate":"network");
        }finally{if(connection!=null)connection.disconnect();}
    }
    static String[] playlistParts(String id) throws IOException {
        String[] parts=id.split("_",-1);if(parts.length!=2)throw error("identity");
        numeric(parts[0],true);int dir=number(parts[1],1_000_000);
        if(parts[0].equals("0") && dir!=201)throw error("identity");return parts;
    }
    static void requireMid(String mid) throws IOException {if(mid==null || !mid.matches("[A-Za-z0-9]{14}"))throw error("identity");}
    private static void bounds(int offset,int limit) throws IOException {if(offset<0 || offset>20_000 || limit<1 || limit>100)throw error("limit");}
    private static JSONArray array(JSONObject value,String name) throws IOException {JSONArray rows=value.optJSONArray(name);if(rows==null)throw error("protocol");return rows;}
    private static boolean flag(JSONObject value,String name,boolean required) throws IOException {
        Object flag=value.opt(name);if(flag==null || flag==JSONObject.NULL){if(required)throw error("protocol");return false;}
        if(Boolean.TRUE.equals(flag) || text(flag).equals("1"))return true;
        if(Boolean.FALSE.equals(flag) || text(flag).equals("0"))return false;throw error("protocol");
    }
    private static int number(Object value,int max) throws IOException {String raw=text(value);if(!raw.matches("(?:0|[1-9][0-9]{0,9})"))throw error("protocol");
        long n=Long.parseLong(raw);if(n>max)throw error("limit");return (int)n;}
    private static String numeric(Object value,boolean zero) throws IOException {String raw=text(value);if(!raw.matches(zero?"(?:0|[1-9][0-9]{0,15})":"[1-9][0-9]{0,15}") || Long.parseLong(raw)>9_007_199_254_740_991L)throw error("identity");return raw;}
    private static Object first(JSONObject value,String...names){for(String name:names){Object v=value.opt(name);if(v!=null && v!=JSONObject.NULL && !"".equals(v))return v;}return null;}
    private static String text(Object value){return value instanceof String || value instanceof Number?String.valueOf(value):"";}
    private static String clean(Object value,String fallback){String s=text(value).replaceAll("[\\p{Cc}\\p{Cf}]","").trim();return s.isEmpty()?fallback:s.substring(0,Math.min(s.length(),240));}
    private static JSONObject obj(Object...pairs) throws IOException {JSONObject result=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}catch(org.json.JSONException invalid){throw error("protocol");}}
    private static void put(JSONObject value,String name,Object item) throws IOException {try{value.put(name,item);}catch(org.json.JSONException invalid){throw error("protocol");}}
    private static QqMusicApi.ApiException diagnostic(String code,String detail){return new QqMusicApi.ApiException(code,"QQ music operation unavailable",detail);}
    private static QqMusicApi.ApiException error(String code){return new QqMusicApi.ApiException(code,"QQ music operation unavailable");}
}
