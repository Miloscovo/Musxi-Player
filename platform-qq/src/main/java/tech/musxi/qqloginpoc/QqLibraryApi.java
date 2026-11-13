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

/** Native adapter of QQMusicApi 0.8.1 modules/{user,songlist,song,lyric}.py,
 * commit 3fc57f02e4eddb68a069bba4e0e12a7893f2a814, GPL-3.0-or-later.
 * Credentials and signed URLs never become library DTOs. */
public final class QqLibraryApi {
    public static final class Playlist {
        public enum Kind { LIKED, CREATED, COLLECTED, OTHER }
        public final String id, name, coverUrl; public final int trackCount;
        public final boolean liked, writable;
        public final Kind kind;
        Playlist(String id,String name,int count){this(id,name,count,null,false,false,Kind.OTHER);}
        Playlist(String id,String name,int count,String coverUrl){this(id,name,count,coverUrl,false,false,Kind.OTHER);}
        Playlist(String id,String name,int count,String coverUrl,boolean liked,boolean writable){
            this(id,name,count,coverUrl,liked,writable,
                liked ? Kind.LIKED : writable ? Kind.CREATED : Kind.OTHER);
        }
        Playlist(String id,String name,int count,String coverUrl,boolean liked,boolean writable,Kind kind){
            this.id=id;this.name=name;trackCount=count;this.coverUrl=coverUrl;this.liked=liked;this.writable=writable;
            this.kind=kind;
        }
    }
    public static final class Song {
        public final String id,name,album,coverUrl,albumId; public final List<String> artists,artistIds; public final long durationMs;
        final String mediaMid; final int type; final long numericId;
        Song(String id,String name,List<String> artists,String album,long duration,String mediaMid,int type){
            this(id,name,artists,album,duration,mediaMid,type,null);
        }
        Song(String id,String name,List<String> artists,String album,long duration,String mediaMid,int type,String coverUrl){
            this(id,0,name,artists,album,duration,mediaMid,type,coverUrl);
        }
        Song(String id,long numericId,String name,List<String> artists,String album,long duration,String mediaMid,int type,String coverUrl){
            this(id,numericId,name,artists,album,duration,mediaMid,type,coverUrl,
                Collections.emptyList(),null);
        }
        Song(String id,long numericId,String name,List<String> artists,String album,long duration,String mediaMid,int type,
            String coverUrl,List<String> artistIds,String albumId){
            this.id=id;this.name=name;this.artists=Collections.unmodifiableList(new ArrayList<>(artists));this.album=album;
            durationMs=duration;this.mediaMid=mediaMid;this.type=type;this.numericId=numericId;this.coverUrl=coverUrl;
            this.artistIds=Collections.unmodifiableList(new ArrayList<>(artistIds));this.albumId=albumId;
        }
    }
    public static final class Page<T> {
        public final List<T> items; public final int nextOffset,total;
        Page(List<T> items,int next,int total){this.items=Collections.unmodifiableList(new ArrayList<>(items));nextOffset=next;this.total=total;}
    }
    /** Whole-list subscription state. A followed playlist stays read-only for songs and metadata. */
    public static final class Subscription {
        public final Playlist playlist;
        public final boolean followed;
        Subscription(Playlist playlist,boolean followed){this.playlist=playlist;this.followed=followed;}
    }
    public static final class Audio {
        public final String url, qualityId; public final long expiresAtElapsedMs;
        Audio(String url,String qualityId,long expires){this.url=url;this.qualityId=qualityId;expiresAtElapsedMs=expires;}
        @Override public String toString(){return "Audio(redacted)";}
    }
    public static final class AudioQuality {
        public final String id, name;
        AudioQuality(String id,String name){this.id=id;this.name=name;}
    }
    public enum TranslationStatus { PRESENT, ABSENT, INVALID }
    public static final class LyricsDocument {
        public final String original,translation;
        public final TranslationStatus translationStatus;
        LyricsDocument(String original,String translation,TranslationStatus translationStatus){
            this.original=original;this.translation=translation;this.translationStatus=translationStatus;
        }
        @Override public String toString(){return "LyricsDocument{redacted}";}
    }
    interface AudioProbe { void check(String url) throws IOException; }
    static final String ARTIST_TRACKS_MODULE="musichall.song_list_server";
    static final String ARTIST_TRACKS_METHOD="GetSingerSongList";
    static final String ALBUM_TRACKS_MODULE="music.musichallAlbum.AlbumSongList";
    static final String ALBUM_TRACKS_METHOD="GetAlbumSongList";
    /** Pinned 0.8.1 modules/recommend.py recommend.get_guess_recommend: a credentialed
     * personal-radio read. The pinned source has no separate daily-recommendation method, so
     * no daily cadence is claimed and no anonymous chart feed is substituted. */
    static final String PERSONAL_RECOMMEND_MODULE="music.radioProxy.MbTrackRadioSvr";
    static final String PERSONAL_RECOMMEND_METHOD="get_radio_track";
    static final int PERSONAL_RECOMMEND_NUM=30;
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
        favoriteLists(session,lists);
        List<Playlist> all=new ArrayList<>(lists.values());
        if(offset>all.size())throw error("incomplete");
        int end=Math.min(offset+limit,all.size());
        return new Page<>(all.subList(offset,end),end<all.size()?end:-1,all.size());
    }
    /** Whole bounded CgiGetPlaylistFavInfo enumeration of this account's followed playlists.
     * Truncation, page-count drift and failed tids fail instead of yielding a partial set, because a
     * partial set would silently misreport an existing subscription as absent. */
    private void favoriteLists(QqMusicApi.Session session,LinkedHashMap<String,Playlist> lists) throws IOException {
        String euin=api.encryptedUin(session);
        if(euin.isEmpty())throw error("identity");
        int favOffset=0; Integer expectedTotal=null; HashSet<String> seenTids=new HashSet<>();
        while(true){
            JSONObject fav=call(session,"music.musicasset.PlaylistFavRead","CgiGetPlaylistFavInfo",obj("uin",euin,"offset",favOffset,"size",100));
            if(flag(fav,"hide",false))throw error("permission");
            if(fav.has("v_failTids") && array(fav,"v_failTids").length()!=0)throw error("incomplete");
            JSONArray items=array(fav,"v_list");if(items.length()>100)throw error("protocol");
            int total=number(fav.opt("total"),20_000);
            if(expectedTotal!=null && expectedTotal!=total)throw error("incomplete");expectedTotal=total;
            boolean more=flag(fav,"hasmore",true);
            if(favOffset+items.length()>total || (more && (items.length()==0 || favOffset+items.length()>=total)) || (!more && favOffset+items.length()!=total))throw error("incomplete");
            // One followed public tid may not repeat: a duplicate cannot be distinguished from a
            // shifting page window, so the whole enumeration is refused.
            for(int i=0;i<items.length();i++){
                JSONObject row=items.optJSONObject(i);if(row==null)throw error("protocol");
                if(!seenTids.add(numeric(first(row,"tid","id","dissid"),true)))throw error("incomplete");
            }
            appendLists(items,lists,false);
            if(lists.size()>20_000)throw error("limit");
            if(!more)break;
            favOffset+=items.length();if(favOffset>20_000)throw error("limit");
        }
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
                image(first(row,"cover_url_big","cover_url_medium","cover_url_small","picurl","picUrl","logo","cover","coverUrl")),
                false,created && !systemDir(dir),created ? Playlist.Kind.CREATED : Playlist.Kind.COLLECTED);
            lists.putIfAbsent(id,list);
        }
    }
    /** Current-account lists that the server identifies as owned, plus the liked-songs alias.
     * Pinned QQMusicApi 0.8.1 modules/songlist.py exposes these through GetPlaylistByUin, whose rows
     * carry the real tid and dirId. Device evidence shows ordinary created lists at dirId 1 and 2
     * while 201 also appears in the same snapshot, so writability comes from the server row and never
     * from assuming an ordinary list has dirId 0. */
    public List<Playlist> writablePlaylists(QqMusicApi.Session session) throws IOException {
        if(session==null)throw error("identity");
        JSONObject created=call(session,"music.musicasset.PlaylistBaseRead","GetPlaylistByUin",obj("uin",session.musicId));
        if(!flag(created,"bFinish",true))throw error("incomplete");
        JSONArray rows=array(created,"v_playlist");
        if(rows.length()>20_000)throw error("limit");
        LinkedHashMap<String,Playlist> lists=new LinkedHashMap<>();
        appendLists(rows,lists,true);
        List<Playlist> writable=new ArrayList<>();
        for(Playlist value:lists.values())if(ordinaryOwned(value))writable.add(value);
        Page<Song> liked=tracks(session,"0_201",0,1);
        if(liked.total>20_000)throw error("limit");
        writable.add(0,new Playlist("0_201","我喜欢",liked.total,null,true,true));
        return Collections.unmodifiableList(writable);
    }
    /** Add one same-platform track. The liked alias uses the existing verified favorite write.
     * Other ids are revalidated against GetPlaylistByUin before AddSonglist is attempted. */
    public void addToPlaylist(QqMusicApi.Session session,String playlistId,String songMid) throws IOException {
        if(session==null)throw error("identity");
        String[] parts=playlistParts(playlistId);
        if(parts[0].equals("0") && parts[1].equals("201")){
            setLiked(session,songMid,true);
            return;
        }
        requireOwnedCreated(session,playlistId,parts);
        Song song=song(session,songMid);
        if(song.numericId<=0)throw error("identity");
        writeSong(session,"AddSonglist",parts,song);
    }
    /** Remove one same-platform track from a playlist this account owns. The liked alias keeps
     * its separate 201 favorite write; other ids are revalidated against GetPlaylistByUin before
     * DelSonglist is attempted, so a collected list can never be mutated by guessing its tid. */
    public void removeFromPlaylist(QqMusicApi.Session session,String playlistId,String songMid) throws IOException {
        if(session==null)throw error("identity");
        String[] parts=playlistParts(playlistId);
        if(parts[0].equals("0") && parts[1].equals("201")){
            setLiked(session,songMid,false);
            return;
        }
        requireOwnedCreated(session,playlistId,parts);
        Song song=song(session,songMid);
        if(song.numericId<=0)throw error("identity");
        writeSong(session,"DelSonglist",parts,song);
    }
    /** Complete ordered mid snapshot of one playlist, read through bounded paged CgiGetDiss.
     * Any truncation, duplicate or shifting total fails instead of returning a partial list. */
    public List<String> playlistTrackIds(QqMusicApi.Session session,String playlistId) throws IOException {
        if(session==null)throw error("identity");
        playlistParts(playlistId);
        ArrayList<String> ids=new ArrayList<>();HashSet<String> seen=new HashSet<>();
        int offset=0,expectedTotal=-1;
        while(true){
            Page<Song> page=tracks(session,playlistId,offset,100);
            if(expectedTotal<0)expectedTotal=page.total;
            else if(expectedTotal!=page.total)throw error("incomplete");
            if(page.total>20_000)throw error("limit");
            if(page.items.isEmpty() && expectedTotal!=0)throw error("incomplete");
            for(Song item:page.items){if(!seen.add(item.id))throw error("incomplete");ids.add(item.id);}
            if(page.nextOffset<0){
                if(ids.size()!=expectedTotal)throw error("incomplete");
                return Collections.unmodifiableList(ids);
            }
            if(page.nextOffset!=offset+page.items.size())throw error("protocol");
            offset=page.nextOffset;
        }
    }
    private void requireOwnedCreated(QqMusicApi.Session session,String playlistId,String[] parts) throws IOException {
        if(parts[0].equals("0") || systemDir(Integer.parseInt(parts[1])))throw error("permission");
        JSONObject created=call(session,"music.musicasset.PlaylistBaseRead","GetPlaylistByUin",obj("uin",session.musicId));
        if(!flag(created,"bFinish",true))throw error("incomplete");
        JSONArray rows=array(created,"v_playlist");
        if(rows.length()>20_000)throw error("limit");
        LinkedHashMap<String,Playlist> lists=new LinkedHashMap<>();
        appendLists(rows,lists,true);
        Playlist owned=lists.get(playlistId);
        if(owned==null || !ordinaryOwned(owned) || !playlistTid(owned.id).equals(parts[0]))throw error("permission");
    }
    /** Subscription state of one whole playlist, keyed by its public tid.
     * The liked alias and every list the server reports as created by this account are refused, and
     * the target is always revalidated through CgiGetDiss as a public playlist whose explicit
     * creator.musicid is another account. The followed flag comes from a complete
     * CgiGetPlaylistFavInfo enumeration matched on the public tid, never on the tid_dir string, so a
     * changed dirId cannot fake "not followed". */
    public Subscription playlistSubscription(QqMusicApi.Session session,String playlistId) throws IOException {
        if(session==null)throw error("identity");
        String[] parts=playlistParts(playlistId);
        if(parts[0].equals("0"))throw error("permission");
        LinkedHashMap<String,Playlist> created=createdLists(session);
        for(Playlist list:created.values())if(playlistTid(list.id).equals(parts[0]))throw error("permission");
        LinkedHashMap<String,Playlist> favorites=new LinkedHashMap<>();
        favoriteLists(session,favorites);
        Playlist target=publicPlaylist(session,parts);
        return new Subscription(target,favoriteByTid(favorites,parts[0])!=null);
    }
    /** Explicit desired subscription. Permission and current state are read first, an already
     * matching state is a no-op, and the single write is not retried. */
    public void setPlaylistFollowed(QqMusicApi.Session session,String playlistId,boolean followed) throws IOException {
        if(session==null)throw error("identity");
        Subscription before=playlistSubscription(session,playlistId);
        if(before.followed==followed)return;
        String tid=playlistTid(before.playlist.id);
        JSONArray ids=new JSONArray().put(Long.parseLong(tid));
        JSONObject param=obj("uin",session.musicId,"v_playlistId",ids);
        checkpoint();
        JSONObject result=call(session,"music.musicasset.PlaylistFavWrite",
            followed?"FavPlaylist":"CancelFavPlaylist",param);
        // The official script accepts the reply on req_0 code 0 alone and ignores data.result;
        // a retCode that is present must still be an explicit success.
        if(result.has("retCode") && !successCode(result))throw error("permission");
    }
    private LinkedHashMap<String,Playlist> createdLists(QqMusicApi.Session session) throws IOException {
        return createdLists(session,null);
    }
    /** @param invalid when non-null, receives the tid_dirId pairs the server marked invalid, which
     * are excluded from the returned owned set but still belong to this account's raw snapshot. */
    private LinkedHashMap<String,Playlist> createdLists(QqMusicApi.Session session,Set<String> invalid) throws IOException {
        JSONObject created=call(session,"music.musicasset.PlaylistBaseRead","GetPlaylistByUin",obj("uin",session.musicId));
        if(!flag(created,"bFinish",true))throw error("incomplete");
        JSONArray rows=array(created,"v_playlist");
        if(rows.length()>20_000)throw error("limit");
        if(invalid!=null)for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)throw error("protocol");
            if(flag(row,"invalid",false))invalid.add(numeric(first(row,"tid","id","dissid"),true)+"_"
                +number(first(row,"dirId","dirid"),1_000_000));
        }
        LinkedHashMap<String,Playlist> lists=new LinkedHashMap<>();
        appendLists(rows,lists,true);
        return lists;
    }
    static Playlist favoriteByTid(Map<String,Playlist> favorites,String tid) throws IOException {
        Playlist match=null;
        for(Playlist list:favorites.values()){
            if(!playlistTid(list.id).equals(tid))continue;
            // Duplicate public tids cannot be resolved to one subscription state.
            if(match!=null)throw error("incomplete");
            match=list;
        }
        return match;
    }
    static String playlistTid(String id) throws IOException {return playlistParts(id)[0];}
    /** Identity check for a playlist this account has not followed: CgiGetDiss must echo the public
     * tid and name an explicit creator.musicid belonging to another account (pinned QQMusicApi 0.8.1
     * models/songlist.py maps dirinfo -> SonglistInfo.creator: {musicid,nick,headurl,encrypt_uin}).
     * A missing creator is refused rather than guessed from name or owner-like fields. */
    private Playlist publicPlaylist(QqMusicApi.Session session,String[] parts) throws IOException {
        JSONObject param=obj("disstid",Long.parseLong(parts[0]),"dirid",Integer.parseInt(parts[1]),"tag",true,
            "song_begin",0,"song_num",1,"userinfo",true,"orderlist",false,"onlysonglist",false);
        JSONObject data=call(session,"music.srfDissInfo.DissInfo","CgiGetDiss",param);
        if(data.has("subcode") && number(data.opt("subcode"),Integer.MAX_VALUE)!=0)throw error("permission");
        JSONObject info=data.optJSONObject("dirinfo");if(info==null)throw error("protocol");
        Object echoed=first(info,"tid","id","dissid");
        if(echoed==null)throw error("identity");
        if(!numeric(echoed,true).equals(parts[0]))throw error("identity");
        int dirId=number(first(info,"dirid","dirId"),1_000_000);
        // 201 我喜欢, 205 Qzone, 206 本地上传 are the own-account directories the official script
        // singles out (common.js gives 205/206 the tid equal to the directory). A public playlist of
        // another account must never resolve into one of them. Other directories above 200 are
        // ordinary public lists: a real public tid was observed at dirid 203, so this check stays
        // limited to the three named system directories.
        if(dirId==201 || dirId==205 || dirId==206)throw error("identity");
        JSONObject creator=info.optJSONObject("creator");
        if(creator==null)throw error("identity");
        // Public rows observed on device carry creator.musicid=0 together with the same bounded
        // encrypted uin in creator and dirinfo. musicid is compared numerically when it is a real
        // account; otherwise that opaque value is the only identity and must simply differ from this
        // session. A missing creator, missing identity or conflicting echo is still refused.
        String creatorEncrypted=encryptedUin(creator,"encrypt_uin","encryptUin");
        String infoEncrypted=encryptedUin(info,"encrypt_uin","encryptUin");
        if(creatorEncrypted!=null && infoEncrypted!=null && !creatorEncrypted.equals(infoEncrypted))
            throw error("identity");
        String foreignEncrypted=creatorEncrypted!=null?creatorEncrypted:infoEncrypted;
        String ownEncrypted=api.encryptedUin(session);
        Object rawCreator=first(creator,"musicid");
        String creatorId=rawCreator==null?null:numeric(rawCreator,true);
        if(creatorId!=null && !creatorId.equals("0")){
            if(creatorId.equals(session.musicId))throw error("permission");
            if(foreignEncrypted!=null && !ownEncrypted.isEmpty() && foreignEncrypted.equals(ownEncrypted))
                throw error("permission");
        }else{
            if(foreignEncrypted==null || ownEncrypted.isEmpty())throw error("identity");
            if(foreignEncrypted.equals(ownEncrypted))throw error("permission");
        }
        int total=number(data.opt("total_song_num"),20_000);
        return new Playlist(parts[0]+"_"+dirId,
            clean(first(info,"title","dissname","name","dirName"),"未命名歌单"),total,
            image(first(info,"logo","cover_url_big","cover_url_medium","cover_url_small","picurl","picUrl","cover")),
            false,false,Playlist.Kind.COLLECTED);
    }
    private void writeSong(QqMusicApi.Session session,String method,String[] parts,Song song) throws IOException {
        JSONArray info=new JSONArray().put(obj("songId",song.numericId,"songType",song.type));
        JSONObject param=obj("dirId",Integer.parseInt(parts[1]),"tid",Long.parseLong(parts[0]),
            "bFmtUtf8",true,"v_songInfo",info);
        JSONObject result=call(session,"music.musicasset.PlaylistDetailWrite",method,param);
        if(!successCode(result))throw error("permission");
    }
    /** Create one empty playlist through the pinned AddPlaylist call, then locate the created list in
     * the server's own writable set by its returned tid/dirId. The echo name is never used as the key,
     * because the server appends a timestamp to duplicate names. */
    public Playlist createPlaylist(QqMusicApi.Session session,String name) throws IOException {
        if(session==null)throw error("identity");
        String dirName=playlistName(name);
        JSONObject result=call(session,"music.musicasset.PlaylistBaseWrite","AddPlaylist",obj("dirName",dirName));
        if(!successCode(result))throw error("permission");
        // Pinned model CreateDeleteSonglistResp reads $.result.{tid,dirId,dirName}; the created list is
        // then located by that tid in the server-owned set, never by the possibly-renamed echo name.
        JSONObject echo=result.optJSONObject("result");if(echo==null)throw error("protocol");
        String tid=numeric(first(echo,"tid"),false);
        int dirId=number(first(echo,"dirId","dirid"),1_000_000);
        // The server assigns the directory; device evidence gives ordinary lists dirId 1 and 2.
        // Only an account system directory contradicts AddPlaylist and is refused here.
        if(systemDir(dirId))throw error("protocol");
        String id=tid+"_"+dirId;
        for(Playlist list:writablePlaylists(session))if(list.id.equals(id))return list;
        throw error("incomplete");
    }
    /** Official web editor compatibility: preserve every non-title field of its mask=95 request.
     * Missing metadata is not an empty value. Only the account's freshly enumerated tid/dirId pair
     * may be edited, and both the title and preserved fields must be read back afterwards. */
    public void renamePlaylist(QqMusicApi.Session session,String playlistId,String name) throws IOException {
        if(session==null)throw error("identity");
        String title=playlistName(name);
        // Official handleEditConfirm uses JavaScript String.length, i.e. UTF-16 code units.
        if(title.length()>20)throw error("request");
        String[] parts=playlistParts(playlistId);
        requireRenameOwner(session,playlistId,parts);
        EditMetadata before=editMetadata(session,parts);
        if(title.equals(before.title))return;
        JSONObject param=obj("dirId",Integer.parseInt(parts[1]),"mask",95,"dirNewName",title,
            "dirNewDesc",before.description,"dirNewPicUrl",before.cover,"dirNewPicUrl2",before.cover2,
            "dirNewtaglist",before.tags,"dirShow",before.visibility);
        // Official common.js checks req_0.code, not data.retCode. If an explicit retCode exists it
        // must also be successful. No retry: even a lost response may follow a successful edit.
        IOException writeFailure=null;
        try {
            JSONObject response=call(session,"music.musicasset.PlaylistBaseWrite","EditPlaylist",param);
            if(response.has("retCode") && !successCode(response))throw error("permission");
        } catch(IOException failure) { writeFailure=failure; }
        requireRenameOwner(session,playlistId,parts);
        EditMetadata after=editMetadata(session,parts);
        if(!before.sameNonTitle(after))throw error("not_confirmed");
        if(!title.equals(after.title)) {
            if(writeFailure!=null)throw writeFailure;
            throw error("not_confirmed");
        }
    }
    private void requireRenameOwner(QqMusicApi.Session session,String id,String[] parts) throws IOException {
        int directory=Integer.parseInt(parts[1]);
        if(parts[0].equals("0") || directory==0 || systemDir(directory))throw error("permission");
        LinkedHashMap<String,Playlist> owned=createdLists(session);
        Playlist target=owned.get(id);
        if(!ordinaryOwned(target))throw error("permission");
        // EditPlaylist addresses dirId alone. Never resolve two different tids to that one write.
        for(Playlist list:owned.values())
            if(!list.id.equals(id) && playlistParts(list.id)[1].equals(parts[1]))throw error("identity");
    }
    private EditMetadata editMetadata(QqMusicApi.Session session,String[] parts) throws IOException {
        JSONObject data=call(session,"music.srfDissInfo.aiDissInfo","uniform_get_Dissinfo",
            obj("dirid",Integer.parseInt(parts[1]),"optype",3,"tag",1,"userinfo",0,"pic_dpi",300));
        JSONObject info=data.optJSONObject("dirinfo");if(info==null)throw error("incomplete");
        boolean echoed=false;
        for(String key:new String[]{"id","tid","dissid"})if(info.has(key)) {
            if(!numeric(info.opt(key),false).equals(parts[0]))throw error("identity");
            echoed=true;
        }
        if(!echoed || !info.has("dirid"))throw error("incomplete");
        if(number(info.opt("dirid"),1_000_000)!=Integer.parseInt(parts[1]))throw error("identity");
        if(info.has("dirId") && number(info.opt("dirId"),1_000_000)!=Integer.parseInt(parts[1]))throw error("identity");
        String title=editString(info,"title",8192), description=editString(info,"desc",2000),
            cover=editString(info,"picurl",8192), cover2=editString(info,"picurl2",8192);
        if(!info.has("dir_show"))throw error("incomplete");
        int visibility=number(info.opt("dir_show"),2);
        JSONArray tags=info.optJSONArray("tag");if(tags==null)throw error("incomplete");
        if(tags.length()>100)throw error("limit");
        StringJoiner joined=new StringJoiner(",");Set<Integer> seen=new HashSet<>();
        for(int i=0;i<tags.length();i++) {
            JSONObject tag=tags.optJSONObject(i);if(tag==null || !tag.has("id"))throw error("incomplete");
            int tagId=number(tag.opt("id"),1_000_000);
            if(!seen.add(tagId))throw error("incomplete");
            joined.add(String.valueOf(tagId));
        }
        return new EditMetadata(title,description,cover,cover2,joined.toString(),visibility);
    }
    private static String editString(JSONObject row,String key,int maximum) throws IOException {
        Object value=row.opt(key);if(!(value instanceof String))throw error("incomplete");
        if(((String)value).length()>maximum)throw error("limit");
        return (String)value;
    }
    private static final class EditMetadata {
        final String title,description,cover,cover2,tags;final int visibility;
        EditMetadata(String title,String description,String cover,String cover2,String tags,int visibility) {
            this.title=title;this.description=description;this.cover=cover;this.cover2=cover2;
            this.tags=tags;this.visibility=visibility;
        }
        boolean sameNonTitle(EditMetadata other) {
            return description.equals(other.description) && cover.equals(other.cover) &&
                cover2.equals(other.cover2) && tags.equals(other.tags) && visibility==other.visibility;
        }
    }
    static String playlistName(String name) throws IOException {
        if(name==null)throw error("request");
        String trimmed=name.trim();
        // Local request bound only; the pinned adapter documents no platform length rule.
        if(trimmed.isEmpty() || trimmed.codePointCount(0,trimmed.length())>80 || name.codePoints().anyMatch(cp -> Character.isISOControl(cp) || (cp>=0xD800 && cp<=0xDFFF)))
            throw error("request");
        return trimmed;
    }
    /** Fresh-read resolution of one whole-directory delete target, built only from the two complete
     * enumerations this adapter already trusts. The whole GetPlaylistByUin snapshot of the account is
     * used instead of the writable subset, so a tid only the account system directories expose is a
     * permission failure rather than "absent", and a row the snapshot carries but marks invalid stays
     * a permission failure too. A pair the snapshot does not report at all is then looked up in the
     * complete CgiGetPlaylistFavInfo enumeration: a followed list is a protected target (permission),
     * and anything else is neither owned nor followed by this account, so null is returned without
     * writing. No extra detail read and no response-code guessing is involved. */
    public Playlist deletablePlaylist(QqMusicApi.Session session,String playlistId) throws IOException {
        if(session==null)throw error("identity");
        String[] parts=playlistParts(playlistId);
        int directory=Integer.parseInt(parts[1]);
        if(parts[0].equals("0") || directory==0 || systemDir(directory))throw error("permission");
        Set<String> invalid=new HashSet<>();
        LinkedHashMap<String,Playlist> owned=createdLists(session,invalid);
        Playlist target=owned.get(playlistId);
        if(target==null){
            // The server reported this exact pair but excluded it from the usable snapshot.
            if(invalid.contains(playlistId))throw error("permission");
            LinkedHashMap<String,Playlist> favorites=new LinkedHashMap<>();
            favoriteLists(session,favorites);
            // A followed list keeps its own tid: it is somebody else's playlist, never a delete target.
            if(favoriteByTid(favorites,parts[0])!=null)throw error("permission");
            return null;
        }
        if(!ordinaryOwned(target))throw error("permission");
        // DelPlaylist addresses dirId alone. Never resolve two different tids to that one write.
        for(Playlist list:owned.values())
            if(!list.id.equals(playlistId) && playlistParts(list.id)[1].equals(parts[1]))throw error("identity");
        return target;
    }
    /** Delete one whole ordinary created playlist through the pinned songlist.delete call
     * (PlaylistBaseWrite/DelPlaylist with exactly one dirId). The target is revalidated from a fresh
     * snapshot immediately before the single request: an absent pair returns without writing, and
     * nothing is retried or read back here because the caller owns post-write confirmation, including
     * after a lost response. DelSonglist only removes songs and is never used for a directory. */
    public void deletePlaylist(QqMusicApi.Session session,String playlistId) throws IOException {
        if(session==null)throw error("identity");
        String[] parts=playlistParts(playlistId);
        if(deletablePlaylist(session,playlistId)==null)return;
        checkpoint();
        JSONObject result=api.deletePlaylist(session,Integer.parseInt(parts[1]));
        checkpoint();
        if(!successCode(result))throw error("permission");
    }
    public Page<Song> tracks(QqMusicApi.Session session,String playlist,int offset,int limit) throws IOException {
        bounds(offset,limit);String[] parts=playlistParts(playlist);
        JSONObject param=obj("disstid",Long.parseLong(parts[0]),"dirid",Integer.parseInt(parts[1]),"tag",true,
            "song_begin",offset,"song_num",limit,"userinfo",true,"orderlist",true,"onlysonglist",false);
        if(parts[0].equals("0"))put(param,"enc_host_uin",api.encryptedUin(session));
        return parseTracks(call(session,"music.srfDissInfo.DissInfo","CgiGetDiss",param),playlist,offset,limit);
    }
    /** SDK 0.8.1 singer.get_songs_list, whose response wraps each row in songList[].songInfo. */
    public Page<Song> artistTracks(QqMusicApi.Session session,String artist,int offset,int limit) throws IOException {
        if(session==null)throw error("identity");
        JSONObject param=artistTracksParam(artist,offset,limit);
        return parseArtistTracks(call(session,ARTIST_TRACKS_MODULE,ARTIST_TRACKS_METHOD,param),artist,offset,limit);
    }
    /** SDK 0.8.1 album.get_song, whose response wraps each row in songList[].songInfo. */
    public Page<Song> albumTracks(QqMusicApi.Session session,String album,int offset,int limit) throws IOException {
        if(session==null)throw error("identity");
        JSONObject param=albumTracksParam(album,offset,limit);
        return parseAlbumTracks(call(session,ALBUM_TRACKS_MODULE,ALBUM_TRACKS_METHOD,param),album,offset,limit);
    }
    static JSONObject artistTracksParam(String artist,int offset,int limit) throws IOException {
        requireMid(artist);
        bounds(offset,limit);
        return obj("singerMid",artist,"order",1,"number",limit,"begin",offset);
    }
    static JSONObject albumTracksParam(String album,int offset,int limit) throws IOException {
        requireMid(album);
        bounds(offset,limit);
        return obj("albumMid",album,"begin",offset,"num",limit);
    }
    static Page<Song> parseArtistTracks(JSONObject data,String artist,int offset,int limit) throws IOException {
        return parseCollectionTracks(data,artist,"singerMid",offset,limit);
    }
    static Page<Song> parseAlbumTracks(JSONObject data,String album,int offset,int limit) throws IOException {
        return parseCollectionTracks(data,album,"albumMid",offset,limit);
    }
    private static Page<Song> parseCollectionTracks(JSONObject data,String expected,String identityField,
        int offset,int limit) throws IOException {
        bounds(offset,limit);requireMid(expected);
        if(data==null)throw error("protocol");
        if(data.has("subcode") && number(data.opt("subcode"),Integer.MAX_VALUE)!=0)throw error("permission");
        String echoed=text(data.opt(identityField));if(!expected.equals(echoed))throw error("identity");
        int total=number(data.opt("totalNum"),20_000);
        JSONArray wrappers=data.optJSONArray("songList");
        if(wrappers==null){if(total==0)wrappers=new JSONArray();else throw error("protocol");}
        if(wrappers.length()>limit)throw error("protocol");
        int end=offset+wrappers.length();boolean more=end<total;
        if(end>total || (more && (wrappers.length()==0 || wrappers.length()<limit)) || (!more && end!=total))
            throw error("incomplete");
        List<Song> songs=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<wrappers.length();i++){
            JSONObject wrapper=wrappers.optJSONObject(i),row=wrapper==null?null:wrapper.optJSONObject("songInfo");
            if(row==null)throw error("protocol");
            Song item=song(row);if(!ids.add(item.id))throw error("identity");songs.add(item);
        }
        return new Page<>(songs,more?end:-1,total);
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
        List<String> artists=new ArrayList<>(),artistIds=new ArrayList<>();boolean allArtistIds=true;JSONArray singer=row.optJSONArray("singer");
        if(singer!=null){if(singer.length()>100)throw error("limit");for(int i=0;i<singer.length();i++){
            JSONObject artist=singer.optJSONObject(i);if(artist==null)throw error("protocol");
            artists.add(clean(artist.opt("name"),"未知歌手"));
            String artistId=optionalEntityMid(artist.opt("mid"));
            if(artistId==null)allArtistIds=false;else if(allArtistIds)artistIds.add(artistId);
        }}
        if(!allArtistIds)artistIds.clear();
        long numericId=row.has("id")?Long.parseLong(numeric(row.opt("id"),false)):0;
        String albumId=album==null?null:optionalEntityMid(album.opt("mid"));
        return new Song(mid,numericId,clean(first(row,"name","title"),"暂不可用歌曲"),artists,album==null?"":clean(album.opt("name"),""),
            (long)number(row.opt("interval"),604_800)*1000,media,row.has("type")?number(row.opt("type"),100):0,albumCover(row,album),
            artistIds,albumId);
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
    /** Pinned 0.8.1 recommend.get_guess_recommend, whose response unfolds data.tracks[] in server
     * order. The pinned source exposes no daily-recommendation method, so this is the account's
     * personal-radio read: no date, no daily refresh promise, and no anonymous hot-list fallback. */
    public List<Song> personalRecommendations(QqMusicApi.Session session) throws IOException {
        if(session==null)throw error("identity");
        return parsePersonalRecommendations(call(session,PERSONAL_RECOMMEND_MODULE,PERSONAL_RECOMMEND_METHOD,
            personalRecommendationsParam()));
    }
    static JSONObject personalRecommendationsParam() throws IOException {
        return obj("id",99,"num",PERSONAL_RECOMMEND_NUM,"from",0,"scene",0,"song_ids",new JSONArray());
    }
    static List<Song> parsePersonalRecommendations(JSONObject data) throws IOException {
        if(data==null)throw error("protocol");
        Object ret=data.opt("retCode");
        if(ret!=null && ret!=JSONObject.NULL && !"0".equals(text(ret)))throw error("permission");
        JSONArray rows=data.optJSONArray("tracks");
        if(rows==null)throw error("protocol");
        if(rows.length()>PERSONAL_RECOMMEND_NUM)throw error("limit");
        List<Song> songs=new ArrayList<>();Set<String> ids=new HashSet<>();
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.optJSONObject(i);if(row==null)throw error("protocol");
            Song item=song(row);if(!ids.add(item.id))throw error("identity");songs.add(item);
        }
        return Collections.unmodifiableList(songs);
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
        if(!successCode(result))throw error("permission");
    }
    public String lyrics(QqMusicApi.Session session,String songMid) throws IOException {
        return lyricsDocument(session,songMid).original;
    }
    public LyricsDocument lyricsDocument(QqMusicApi.Session session,String songMid) throws IOException {
        if(session==null)throw error("identity");
        requireMid(songMid);
        JSONObject response=call(session,"music.musichallSong.PlayLyricInfo","GetPlayLyricInfo",
            obj("crypt",0,"lrc_t",0,"qrc",0,"qrc_t",0,"roma",0,"roma_t",0,
                "trans",1,"trans_t",0,"needSingingAnnotations",false,"type",1,"songMid",songMid));
        return parseLyricsDocument(response);
    }
    /** Optional online QRC lookup. Failures never replace the separately fetched original/translation. */
    public QrcLyricsDecoder.Document lyricsWords(QqMusicApi.Session session,String songMid) throws IOException {
        if(session==null)throw error("identity");
        requireMid(songMid);
        try {
            JSONObject response=call(session,"music.musichallSong.PlayLyricInfo","GetPlayLyricInfo",
                obj("crypt",1,"lrc_t",0,"qrc",1,"qrc_t",0,"roma",0,"roma_t",0,
                    "trans",0,"trans_t",0,"needSingingAnnotations",false,"type",1,"songMid",songMid));
            Object lyric=response.opt("lyric");
            if(lyric==null || lyric==JSONObject.NULL || "".equals(lyric))
                return QrcLyricsDecoder.unavailable(QrcLyricsDecoder.Status.ABSENT);
            if(!(lyric instanceof String))return QrcLyricsDecoder.unavailable(QrcLyricsDecoder.Status.INVALID);
            return QrcLyricsDecoder.decode((String)lyric);
        }catch(java.net.SocketTimeoutException failure){
            return QrcLyricsDecoder.unavailable(QrcLyricsDecoder.Status.TIMEOUT);
        }catch(QqMusicApi.ApiException failure){
            if(Arrays.asList("account_changed","identity","expired","cancelled").contains(failure.code))throw failure;
            if("network".equals(failure.code))return QrcLyricsDecoder.unavailable(QrcLyricsDecoder.Status.NETWORK);
            if("timeout".equals(failure.code))return QrcLyricsDecoder.unavailable(QrcLyricsDecoder.Status.TIMEOUT);
            return QrcLyricsDecoder.unavailable(QrcLyricsDecoder.Status.PLATFORM_ERROR);
        }catch(IOException failure){
            return QrcLyricsDecoder.unavailable(QrcLyricsDecoder.Status.NETWORK);
        }
    }
    static LyricsDocument parseLyricsDocument(JSONObject response) throws IOException {
        Object value=response.opt("lyric");
        if(value==null || value==JSONObject.NULL || "".equals(value))
            return new LyricsDocument("",null,TranslationStatus.ABSENT);
        if(!(value instanceof String))throw error("protocol");
        String original=parseLyrics((String)value);
        Object translated=response.opt("trans");
        if(translated==null || translated==JSONObject.NULL)
            return new LyricsDocument(original,null,TranslationStatus.ABSENT);
        if(!(translated instanceof String))return new LyricsDocument(original,null,TranslationStatus.INVALID);
        String raw=(String)translated;
        // Bound the encoded representation before decoding; a 256KiB LRC needs <350KiB Base64.
        if(raw.length()>350*1024 || !validTranslationText(raw,350*1024))
            return new LyricsDocument(original,null,TranslationStatus.INVALID);
        try {
            if(looksHexCipher(raw))throw error("protocol");
            String text=raw;
            if(base64Alphabet(raw)){
                // Short, unpadded ASCII may be ordinary unsynced text (for example "test").
                String decoded=decodeLyricBase64(raw,raw.endsWith("=") || raw.length()>256*1024);
                if(decoded!=null)text=decoded;
            }
            if(!validTranslationText(text,256*1024))throw error("limit");
            if(isBlank(text))return new LyricsDocument(original,null,TranslationStatus.ABSENT);
            return new LyricsDocument(original,text,TranslationStatus.PRESENT);
        }catch(IOException invalid){
            return new LyricsDocument(original,null,TranslationStatus.INVALID);
        }
    }
    private static boolean isBlank(String value){
        return value.codePoints().allMatch(c->Character.isWhitespace(c)||Character.isSpaceChar(c));
    }
    private static boolean validTranslationText(String value,int maximumBytes){
        int bytes=0;
        for(int i=0;i<value.length();){
            char c=value.charAt(i++);
            if(Character.isHighSurrogate(c)){
                if(i>=value.length() || !Character.isLowSurrogate(value.charAt(i++)))return false;
                bytes+=4;
            }else{
                if(Character.isLowSurrogate(c) || Character.isISOControl(c) && c!='\n' && c!='\r' && c!='\t')return false;
                bytes+=c<0x80?1:c<0x800?2:3;
            }
            if(bytes>maximumBytes)return false;
        }
        return true;
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
        return requestAudio(session,mid,song,now,"qq:standard",true);
    }
    public Audio audio(QqMusicApi.Session session,String mid,long now,String qualityId) throws IOException {
        Song song=song(session,mid);
        return requestAudio(session,mid,song,now,qualityId,true);
    }
    public List<AudioQuality> qualities(QqMusicApi.Session session,String mid,long now) throws IOException {
        Song song=song(session,mid);
        List<AudioQuality> out=new ArrayList<>();
        for(String id:new String[]{"qq:standard","qq:high","qq:lossless"}) {
            try {
                requestAudio(session,mid,song,now,id,true);
                out.add(new AudioQuality(id,qualityName(id)));
            } catch(QqMusicApi.ApiException unavailable) {
                if(!Arrays.asList("permission","membership","copyright","preview","unavailable","network","source_expired").contains(unavailable.code))throw unavailable;
            }
        }
        return Collections.unmodifiableList(out);
    }
    private Audio requestAudio(QqMusicApi.Session session,String mid,Song song,long now,String qualityId,boolean probeAudio) throws IOException {
        QualitySpec quality=quality(qualityId);
        String filename=quality.prefix+(song.mediaMid.isEmpty()?mid+mid:song.mediaMid)+quality.extension;
        String guid=Long.toString(1_000_000_000L+new java.security.SecureRandom().nextInt(1_000_000_000));
        JSONObject vkey=call(session,"music.vkey.GetVkey","UrlGetVkey",obj("uin",session.musicId,"filename",new JSONArray().put(filename),
            "guid",guid,
            "songmid",new JSONArray().put(mid),"songtype",new JSONArray().put(song.type),"ctx",0));
        audioPath(vkey,mid,filename);
        JSONObject dispatch=call(session,"music.audioCdnDispatch.cdnDispatch","GetCdnDispatch",obj("guid",guid,"uid","0","use_new_domain",1,"use_ipv6",1));
        List<String> urls=audioCandidates(vkey,dispatch,mid,filename);
        if(!probeAudio) {
            if(now<0 || now>Long.MAX_VALUE-60_000)throw error("protocol");
            return new Audio(urls.get(0),qualityId,now+60_000);
        }
        long probeDeadline=System.nanoTime()+12_000_000_000L;
        for(String url:urls){checkpoint();if(System.nanoTime()>probeDeadline)break;
            try{probe.check(url);checkpoint();if(now<0 || now>Long.MAX_VALUE-60_000)throw error("protocol");return new Audio(url,qualityId,now+60_000);}
            catch(QqMusicApi.ApiException failure){if(!failure.code.equals("network") && !failure.code.equals("source_expired"))throw failure;}
            catch(IOException unavailable){ /* Bounded HTTPS candidates only. */ }
        }
        throw error("network");
    }
    private static final class QualitySpec {
        final String prefix, extension;
        QualitySpec(String prefix,String extension){this.prefix=prefix;this.extension=extension;}
    }
    private static QualitySpec quality(String id) throws IOException {
        switch(id) {
            case "qq:standard": return new QualitySpec("M500",".mp3");
            case "qq:high": return new QualitySpec("M800",".mp3");
            case "qq:lossless": return new QualitySpec("F000",".flac");
            default: throw error("request");
        }
    }
    private static String qualityName(String id) {
        switch(id) {
            case "qq:standard": return "标准音质";
            case "qq:high": return "高品音质";
            case "qq:lossless": return "无损音质";
            default: return "";
        }
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
            Object value=row.opt(key);if(value!=null && value!=JSONObject.NULL && !Boolean.FALSE.equals(value) && (Boolean.TRUE.equals(value) || (!text(value).isEmpty() && !text(value).equals("0"))))throw diagnostic("preview","preview_audio");
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
        if(id==null)throw error("identity");
        String[] parts=id.split("_",-1);if(parts.length!=2)throw error("identity");
        numeric(parts[0],true);int dir=number(parts[1],1_000_000);
        if(parts[0].equals("0") && dir!=201)throw error("identity");return parts;
    }
    /** 201..209 are the account system directories (201 liked, 205 Qzone, 206 uploads). The official
     * script routes dirid >200 and <210 separately from ordinary lists, so no write is ever attempted on
     * them and they are never listed as ordinary writable playlists. */
    static boolean systemDir(int dirId){return dirId>=201 && dirId<=209;}
    /** A created row of this account whose directory is not a system directory. appendLists already
     * marks created rows as Kind.CREATED, so writability is derived here from the server row's
     * tid/dirId pair. System entries stay readable but never become ordinary write targets. */
    static boolean ordinaryOwned(Playlist value) throws IOException {
        if(value==null || value.liked || value.kind!=Playlist.Kind.CREATED || value.id==null)return false;
        return !systemDir(Integer.parseInt(playlistParts(value.id)[1]));
    }
    /** Bounded opaque account identity echoed by public playlist rows: a non-empty String of at most
     * 256 characters without control characters. Missing means absent, never a guessed value. */
    private static String encryptedUin(JSONObject value,String...names) throws IOException {
        for(String name:names){
            Object raw=value.opt(name);
            if(raw==null || raw==JSONObject.NULL || "".equals(raw))continue;
            if(!(raw instanceof String))throw error("identity");
            String uin=(String)raw;
            if(uin.trim().isEmpty() || uin.length()>256 || uin.codePoints().anyMatch(cp ->
                Character.isISOControl(cp) || Character.isWhitespace(cp) || Character.isSpaceChar(cp) ||
                (cp>=0xD800 && cp<=0xDFFF)))throw error("identity");
            return uin;
        }
        return null;
    }
    static void requireMid(String mid) throws IOException {if(mid==null || !mid.matches("[A-Za-z0-9]{14}"))throw error("identity");}
    private static String optionalEntityMid(Object value) throws IOException {
        String mid=text(value).trim();return mid.matches("[A-Za-z0-9]{14}")?mid:null;
    }
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
    private static boolean successCode(JSONObject value) throws IOException {
        Object ret=value.opt("retCode");if(!(ret instanceof Number))throw error("protocol");
        String raw=String.valueOf(ret);if(!raw.matches("(?:0|80092)"))throw error("permission");
        return "0".equals(raw);
    }
    private static Object first(JSONObject value,String...names){for(String name:names){Object v=value.opt(name);if(v!=null && v!=JSONObject.NULL && !"".equals(v))return v;}return null;}
    private static String text(Object value){return value instanceof String || value instanceof Number?String.valueOf(value):"";}
    private static String clean(Object value,String fallback){String s=text(value).replaceAll("[\\p{Cc}\\p{Cf}]","").trim();return s.isEmpty()?fallback:s.substring(0,Math.min(s.length(),240));}
    private static JSONObject obj(Object...pairs) throws IOException {JSONObject result=new JSONObject();try{for(int i=0;i<pairs.length;i+=2)result.put((String)pairs[i],pairs[i+1]);return result;}catch(org.json.JSONException invalid){throw error("protocol");}}
    private static void put(JSONObject value,String name,Object item) throws IOException {try{value.put(name,item);}catch(org.json.JSONException invalid){throw error("protocol");}}
    private static QqMusicApi.ApiException diagnostic(String code,String detail){return new QqMusicApi.ApiException(code,"QQ music operation unavailable",detail);}
    private static QqMusicApi.ApiException error(String code){return new QqMusicApi.ApiException(code,"QQ music operation unavailable");}
}
