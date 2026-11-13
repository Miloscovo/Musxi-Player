package tech.musxi.qqloginpoc;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class QqLibraryApiTest {
    @Test public void optionalQrcUsesIndependentRequestAndDoesNotReplaceOrdinaryLyrics() throws Exception {
        Fixture f=new Fixture();
        f.data.add(new JSONObject().put("lyric","[00:01.00]Hi").put("trans","[00:01.00]你好"));
        f.data.add(new JSONObject().put("lyric","[1000,500]H(1000,200)i(1200,300)"));
        QqLibraryApi api=new QqLibraryApi(f.api);
        QqLibraryApi.LyricsDocument original=api.lyricsDocument(session(),MID);
        QrcLyricsDecoder.Document words=api.lyricsWords(session(),MID);
        assertEquals("[00:01.00]Hi",original.original);
        assertEquals(QqLibraryApi.TranslationStatus.PRESENT,original.translationStatus);
        assertEquals(QrcLyricsDecoder.Status.PRESENT,words.status);
        assertEquals(0,f.param(0).getInt("qrc"));assertEquals(0,f.param(0).getInt("crypt"));
        assertEquals(1,f.param(1).getInt("qrc"));assertEquals(1,f.param(1).getInt("crypt"));
        assertEquals(0,f.param(1).getInt("trans"));assertEquals(MID,f.param(1).getString("songMid"));
    }
    @Test public void optionalQrcDistinguishesInvalidAbsentAndTransportFailure() throws Exception {
        for(Object value:new Object[]{42,"bad ciphertext",JSONObject.NULL,""}) {
            Fixture f=new Fixture();f.data.add(new JSONObject().put("lyric",value));
            QrcLyricsDecoder.Status expected=value==JSONObject.NULL || "".equals(value)?
                QrcLyricsDecoder.Status.ABSENT:QrcLyricsDecoder.Status.INVALID;
            assertEquals(expected,new QqLibraryApi(f.api).lyricsWords(session(),MID).status);
        }
        QqMusicApi api=new QqMusicApi(request->{throw new java.net.SocketTimeoutException();});
        // The existing transport normalizes socket IO failures into the safe network category.
        assertEquals(QrcLyricsDecoder.Status.NETWORK,new QqLibraryApi(api).lyricsWords(session(),MID).status);
        QqMusicApi network=new QqMusicApi(request->{throw new IOException();});
        assertEquals(QrcLyricsDecoder.Status.NETWORK,new QqLibraryApi(network).lyricsWords(session(),MID).status);
        Fixture cancelled=new Fixture();
        rejects("account_changed",()->new QqLibraryApi(cancelled.api,()->false).lyricsWords(session(),MID));
        assertTrue(cancelled.requests.isEmpty());
    }
    @Test public void favoriteReadsAcceptOwnAliasAndWritesOnly201WithResolvedNumericSong() throws Exception {
        Fixture f=new Fixture();
        JSONObject page=tracks(1,false);page.getJSONObject("dirinfo").put("dirid",201);
        f.data.add(page);
        assertEquals(Collections.singletonList(MID),new QqLibraryApi(f.api).likedIds(session()));
        assertEquals(0,f.param(0).getLong("disstid"));assertEquals(201,f.param(0).getInt("dirid"));
        assertEquals("fixture-euin",f.param(0).getString("enc_host_uin"));
        for(boolean liked:new boolean[]{true,false}) {
            Fixture write=new Fixture();write.data.add(new JSONObject().put("track_info",song().put("id",99).put("type",0)));
            write.data.add(new JSONObject().put("retCode",0));
            new QqLibraryApi(write.api).setLiked(session(),MID,liked);
            assertEquals(2,write.requests.size());
            JSONObject params=write.param(1);
            assertEquals(201,params.getInt("dirId"));assertEquals(0,params.getInt("tid"));
            assertTrue(params.getBoolean("bFmtUtf8"));
            assertEquals(99,params.getJSONArray("v_songInfo").getJSONObject(0).getInt("songId"));
            JSONObject request=new JSONObject(write.requests.get(1).body).getJSONObject("req_0");
            assertEquals(liked?"AddSonglist":"DelSonglist",request.getString("method"));
            JSONObject comm=new JSONObject(write.requests.get(1).body).getJSONObject("comm");
            assertEquals("11",comm.getString("ct"));assertEquals("20090008",comm.getString("cv"));
            assertEquals("qqmusic",comm.getString("tmeAppID"));
            assertFalse(comm.has("platform"));
            assertEquals("24",new JSONObject(write.requests.get(0).body).getJSONObject("comm").getString("ct"));
        }
    }
    @Test public void favoriteWritesRejectOtherListsAndUnknownResponseWithoutRetry() throws Exception {
        Fixture f=new Fixture();
        JSONObject valid=new JSONObject().put("dirId",201).put("tid",0).put("bFmtUtf8",true)
            .put("v_songInfo",new JSONArray().put(new JSONObject().put("songId",99).put("songType",0)));
        for(String field:new String[]{"dirId","tid","bFmtUtf8"}) {
            JSONObject invalid=new JSONObject(valid.toString()).put(field,field.equals("bFmtUtf8")?false:1);
            rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","AddSonglist",invalid));
        }
        assertTrue(f.requests.isEmpty());
        f.data.add(new JSONObject().put("track_info",song().put("id",99).put("type",0)));
        f.data.add(new JSONObject());
        rejects("protocol",()->new QqLibraryApi(f.api).setLiked(session(),MID,true));
        assertEquals(2,f.requests.size());
    }
    @Test public void favoriteSyncRejectsDuplicateMidAndChangingTotal() throws Exception {
        Fixture f=new Fixture();
        JSONObject first=tracks(2,true);first.getJSONObject("dirinfo").put("dirid",201);
        JSONObject second=tracks(2,false);second.getJSONObject("dirinfo").put("dirid",201);
        f.data.add(first);f.data.add(second);
        rejects("incomplete",()->new QqLibraryApi(f.api).likedIds(session()));
        Fixture changed=new Fixture();changed.data.add(first);
        JSONObject changedPage=tracks(3,true);changedPage.getJSONObject("dirinfo").put("dirid",201);
        changed.data.add(changedPage);
        rejects("incomplete",()->new QqLibraryApi(changed.api).likedIds(session()));
    }
    private static final String MID="003abcDEF01234", MEDIA="001abcDEF01234001abcDEF01234";
    private static JSONObject json(String value) throws Exception { return new JSONObject(value); }
    private static QqMusicApi.Session session() throws Exception {return QqMusicApi.Session.fromJson(json(
        "{\"credential\":{\"musicid\":12345,\"musickey\":\"fixture-key\",\"encryptUin\":\"fixture-euin\",\"loginType\":2},\"nickname\":\"Fixture\"}"));}
    private interface Checked {void run() throws Exception;}
    private static void rejects(String code,Checked work) throws Exception {try{work.run();fail("Expected fixed failure");}catch(QqMusicApi.ApiException ex){assertEquals(code,ex.code);assertFalse(ex.getMessage().contains("fixture-key"));}}
    private static class Fixture implements QqMusicApi.Transport {
        final ArrayDeque<JSONObject> data=new ArrayDeque<>();final List<QqMusicApi.Request> requests=new ArrayList<>();
        final QqMusicApi api=new QqMusicApi(this);
        public QqMusicApi.Response execute(QqMusicApi.Request request) throws IOException {
            requests.add(request);
            try{return new QqMusicApi.Response(200,new JSONObject().put("code",0).put("req_0",new JSONObject().put("code",0).put("data",data.remove())).toString().getBytes(StandardCharsets.UTF_8),Collections.emptyMap());}
            catch(JSONException e){throw new IOException();}
        }
        JSONObject param(int n) throws Exception{return new JSONObject(requests.get(n).body).getJSONObject("req_0").getJSONObject("param");}
    }
    private static JSONObject song() throws Exception{return json("{\"mid\":\""+MID+"\",\"name\":\"Song\",\"interval\":123,\"type\":0,\"singer\":[{\"name\":\"Artist\"}],\"album\":{\"name\":\"Album\"},\"file\":{\"media_mid\":\""+MEDIA+"\"}}");}
    private static JSONObject tracks(int total,boolean more) throws Exception{return new JSONObject().put("dirinfo",json("{\"tid\":9,\"dirid\":0}")).put("songlist",new JSONArray().put(song())).put("total_song_num",total).put("hasmore",more?1:0).put("songlist_size",1);}
    private static String trackMid(int index){return String.format("001%011d",index);}
    private static JSONObject song(String mid) throws Exception{return song().put("mid",mid);}
    private static JSONObject pageOf(int tid,int dirid,int total,boolean more,int count,int start) throws Exception{
        JSONArray rows=new JSONArray();
        for(int i=0;i<count;i++)rows.put(song(trackMid(start+i)));
        return new JSONObject().put("dirinfo",new JSONObject().put("tid",tid).put("dirid",dirid))
            .put("songlist",rows).put("total_song_num",total).put("hasmore",more?1:0).put("songlist_size",count);
    }
    private static JSONObject search(int count) throws Exception{
        JSONArray rows=new JSONArray();for(int i=0;i<count;i++)rows.put(song());
        return new JSONObject().put("body",new JSONObject().put("item_song",rows)).put("meta",
            new JSONObject().put("perpage",10).put("nextpage",-1).put("sum",count));
    }
    private static String filename(String prefix,String extension){return prefix+MEDIA+extension;}
    private static String filename(){return filename("M500",".mp3");}
    private static JSONObject vkey(String filename) throws Exception {return new JSONObject().put("midurlinfo",new JSONArray().put(new JSONObject().put("songmid",MID).put("filename",filename).put("result",0).put("ekey","").put("purl",filename+"?vkey=fixture-url-key")));}
    private static JSONObject vkey() throws Exception{return vkey(filename());}
    private static JSONObject dispatch() throws Exception{return new JSONObject().put("sip",new JSONArray().put("http://dl.stream.qqmusic.qq.com/").put("https://isure.stream.qqmusic.qq.com/"));}

    @Test public void createdAndFavoriteListsMergeBeforeSlicingAndNeverLoseContinuation() throws Exception {
        Fixture f=new Fixture();
        f.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":0,\"dirName\":\"Created\",\"songNum\":1,\"invalid\":0}]}"));
        f.data.add(json("{\"total\":2,\"hasmore\":1,\"hide\":0,\"v_list\":[{\"tid\":10,\"dirid\":0,\"title\":\"Fav\",\"songnum\":2}]}"));
        f.data.add(json("{\"total\":2,\"hasmore\":0,\"hide\":0,\"v_list\":[{\"tid\":9,\"dirid\":0,\"title\":\"Duplicate\",\"songnum\":1}]}"));
        QqLibraryApi.Page<QqLibraryApi.Playlist> result=new QqLibraryApi(f.api).playlists(session(),0,1);
        assertEquals(2,result.total);assertEquals(1,result.nextOffset);assertEquals("9_0",result.items.get(0).id);
        assertEquals(QqLibraryApi.Playlist.Kind.CREATED,result.items.get(0).kind);
        assertEquals(1,f.param(2).getInt("offset"));
        assertEquals("fixture-euin",f.param(1).getString("uin"));assertEquals(4*1024*1024,f.requests.get(0).limit);
        assertTrue(new JSONObject(f.requests.get(0).body).getJSONObject("comm").has("authst"));
    }
    @Test public void unfinishedHiddenOrTruncatedListsFailInsteadOfEmptySuccess() throws Exception {
        Fixture f=new Fixture();f.data.add(json("{\"bFinish\":0,\"v_playlist\":[]}"));
        rejects("incomplete",()->new QqLibraryApi(f.api).playlists(session(),0,30));
        Fixture hidden=new Fixture();hidden.data.add(json("{\"bFinish\":1,\"v_playlist\":[]}"));hidden.data.add(json("{\"hide\":1}"));
        rejects("permission",()->new QqLibraryApi(hidden.api).playlists(session(),0,30));
        Fixture truncated=new Fixture();truncated.data.add(json("{\"bFinish\":1,\"v_playlist\":[]}"));truncated.data.add(json("{\"hide\":0,\"v_list\":[],\"total\":1,\"hasmore\":0}"));
        rejects("incomplete",()->new QqLibraryApi(truncated.api).playlists(session(),0,30));
    }
    @Test public void likedPlaylistKeepsDir201AndBindsEncryptedOwner() throws Exception {
        assertArrayEquals(new String[]{"0","201"},QqLibraryApi.playlistParts("0_201"));
        rejects("identity",()->QqLibraryApi.playlistParts("0_0"));
        rejects("identity",()->QqLibraryApi.playlistParts("01_201"));
        rejects("identity",()->QqLibraryApi.playlistParts("9007199254740992_0"));
        Fixture f=new Fixture();JSONObject data=tracks(1,false);data.getJSONObject("dirinfo").put("tid",99).put("dirid",201);f.data.add(data);
        assertEquals(1,new QqLibraryApi(f.api).tracks(session(),"0_201",0,30).total);
        assertEquals("fixture-euin",f.param(0).getString("enc_host_uin"));
    }
    @Test public void writablePlaylistsUseServerCreatedListsAndLikedAlias() throws Exception {
        Fixture f=new Fixture();
        f.data.add(json("{\"bFinish\":1,\"v_playlist\":["
            +"{\"tid\":9,\"dirId\":0,\"dirName\":\"Mine\",\"songNum\":2,\"invalid\":0},"
            +"{\"tid\":11,\"dirId\":0,\"dirName\":\"Hidden\",\"songNum\":3,\"invalid\":1}]}"));
        f.data.add(json("{\"dirinfo\":{\"tid\":9,\"dirid\":201},\"songlist\":[],"
            +"\"total_song_num\":0,\"hasmore\":0,\"songlist_size\":0}"));
        List<QqLibraryApi.Playlist> result=new QqLibraryApi(f.api).writablePlaylists(session());
        assertEquals(2,result.size());
        assertEquals("0_201",result.get(0).id);assertTrue(result.get(0).liked);assertTrue(result.get(0).writable);
        assertEquals(QqLibraryApi.Playlist.Kind.LIKED,result.get(0).kind);
        assertEquals("9_0",result.get(1).id);assertFalse(result.get(1).liked);assertTrue(result.get(1).writable);
        assertEquals(QqLibraryApi.Playlist.Kind.CREATED,result.get(1).kind);
        assertEquals(12345,f.param(0).getLong("uin"));
        try{result.clear();fail("Writable playlists must be read-only");}catch(UnsupportedOperationException expected){}
    }
    @Test public void addToLikedUsesVerifiedFavoriteAndOwnedCustomWriteUsesPinnedPayload() throws Exception {
        Fixture liked=new Fixture();
        liked.data.add(new JSONObject().put("track_info",song().put("id",99)));
        liked.data.add(new JSONObject().put("retCode",0));
        new QqLibraryApi(liked.api).addToPlaylist(session(),"0_201",MID);
        assertEquals(201,liked.param(1).getInt("dirId"));assertEquals(0,liked.param(1).getLong("tid"));
        assertEquals("AddSonglist",new JSONObject(liked.requests.get(1).body).getJSONObject("req_0").getString("method"));

        Fixture custom=new Fixture();
        custom.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":0,\"dirName\":\"Mine\",\"songNum\":1,\"invalid\":0}]}"));
        custom.data.add(new JSONObject().put("track_info",song().put("id",99)));
        custom.data.add(new JSONObject().put("retCode",0));
        new QqLibraryApi(custom.api).addToPlaylist(session(),"9_0",MID);
        assertEquals(3,custom.requests.size());
        JSONObject params=custom.param(2);
        assertEquals(0,params.getInt("dirId"));assertEquals(9,params.getLong("tid"));
        assertEquals("AddSonglist",new JSONObject(custom.requests.get(2).body).getJSONObject("req_0").getString("method"));
        Fixture denied=new Fixture();denied.data.add(json("{bFinish:1,v_playlist:[]}"));
        rejects("permission",()->new QqLibraryApi(denied.api).addToPlaylist(session(),"8_0",MID));
    }
    @Test public void trackOrderCountsIdentityAndMissingStructuresAreChecked() throws Exception {
        QqLibraryApi.Page<QqLibraryApi.Song> result=QqLibraryApi.parseTracks(tracks(2,true),"9_0",0,30);
        assertEquals(1,result.nextOffset);assertEquals(MID,result.items.get(0).id);assertEquals(123000,result.items.get(0).durationMs);
        rejects("incomplete",()->QqLibraryApi.parseTracks(tracks(2,false),"9_0",0,30));
        rejects("identity",()->QqLibraryApi.parseTracks(tracks(1,false),"10_0",0,30));
        rejects("protocol",()->QqLibraryApi.parseTracks(new JSONObject(),"9_0",0,30));
        JSONObject missing=tracks(1,false);missing.getJSONArray("songlist").getJSONObject(0).remove("mid");
        rejects("identity",()->QqLibraryApi.parseTracks(missing,"9_0",0,30));
    }
    @Test public void removeFromLikedUsesDelSonglist201AndOwnedCustomUsesVerifiedDel() throws Exception {
        Fixture liked=new Fixture();
        liked.data.add(new JSONObject().put("track_info",song().put("id",99)));
        liked.data.add(new JSONObject().put("retCode",0));
        new QqLibraryApi(liked.api).removeFromPlaylist(session(),"0_201",MID);
        assertEquals(2,liked.requests.size());
        assertEquals(201,liked.param(1).getInt("dirId"));assertEquals(0,liked.param(1).getLong("tid"));
        assertTrue(liked.param(1).getBoolean("bFmtUtf8"));
        assertEquals("DelSonglist",new JSONObject(liked.requests.get(1).body).getJSONObject("req_0").getString("method"));
        assertEquals("11",new JSONObject(liked.requests.get(1).body).getJSONObject("comm").getString("ct"));

        Fixture custom=new Fixture();
        custom.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":0,\"dirName\":\"Mine\",\"songNum\":1,\"invalid\":0}]}"));
        custom.data.add(new JSONObject().put("track_info",song().put("id",99)));
        custom.data.add(new JSONObject().put("retCode",0));
        new QqLibraryApi(custom.api).removeFromPlaylist(session(),"9_0",MID);
        assertEquals(3,custom.requests.size());
        assertEquals(12345,custom.param(0).getLong("uin"));
        JSONObject params=custom.param(2);
        assertEquals(0,params.getInt("dirId"));assertEquals(9,params.getLong("tid"));
        assertTrue(params.getBoolean("bFmtUtf8"));
        assertEquals("DelSonglist",new JSONObject(custom.requests.get(2).body).getJSONObject("req_0").getString("method"));
        JSONObject info=params.getJSONArray("v_songInfo").getJSONObject(0);
        assertEquals(2,info.length());assertEquals(99,info.getInt("songId"));assertEquals(0,info.getInt("songType"));
    }
    @Test public void removeRejectsNonOwnedListsAndBadMidsBeforeAnyWrite() throws Exception {
        for(String id:new String[]{"9_201","9_205","9_209"}){
            Fixture f=new Fixture();
            rejects("permission",()->new QqLibraryApi(f.api).removeFromPlaylist(session(),id,MID));
            assertTrue(id+" must stop before transport: "+f.requests.size(),f.requests.isEmpty());
        }
        Fixture alias=new Fixture();
        rejects("identity",()->new QqLibraryApi(alias.api).removeFromPlaylist(session(),"0_0",MID));
        assertTrue(alias.requests.isEmpty());
        Fixture collected=new Fixture();
        collected.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":11,\"dirId\":0,\"dirName\":\"Hidden\",\"songNum\":1,\"invalid\":1}]}"));
        rejects("permission",()->new QqLibraryApi(collected.api).removeFromPlaylist(session(),"11_0",MID));
        assertEquals(1,collected.requests.size());
        Fixture absent=new Fixture();
        absent.data.add(json("{\"bFinish\":1,\"v_playlist\":[]}"));
        rejects("permission",()->new QqLibraryApi(absent.api).removeFromPlaylist(session(),"9_0",MID));
        assertEquals(1,absent.requests.size());
        Fixture unfinished=new Fixture();
        unfinished.data.add(json("{\"bFinish\":0,\"v_playlist\":[]}"));
        rejects("incomplete",()->new QqLibraryApi(unfinished.api).removeFromPlaylist(session(),"9_0",MID));
        assertEquals(1,unfinished.requests.size());
        Fixture badMid=new Fixture();
        badMid.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":0,\"dirName\":\"Mine\",\"songNum\":1,\"invalid\":0}]}"));
        rejects("identity",()->new QqLibraryApi(badMid.api).removeFromPlaylist(session(),"9_0","not-a-mid"));
        assertEquals(1,badMid.requests.size());
        Fixture noNumericId=new Fixture();
        noNumericId.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":0,\"dirName\":\"Mine\",\"songNum\":1,\"invalid\":0}]}"));
        noNumericId.data.add(new JSONObject().put("track_info",song()));
        rejects("identity",()->new QqLibraryApi(noNumericId.api).removeFromPlaylist(session(),"9_0",MID));
        assertEquals(2,noNumericId.requests.size());
        Fixture denied=new Fixture();
        denied.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":0,\"dirName\":\"Mine\",\"songNum\":1,\"invalid\":0}]}"));
        denied.data.add(new JSONObject().put("track_info",song().put("id",99)));
        denied.data.add(new JSONObject().put("retCode",80092));
        rejects("permission",()->new QqLibraryApi(denied.api).removeFromPlaylist(session(),"9_0",MID));
        assertEquals(3,denied.requests.size());
    }
    @Test public void playlistTrackIdsReturnsCompleteOrderedSnapshotAcrossPages() throws Exception {
        Fixture f=new Fixture();
        f.data.add(pageOf(9,0,150,true,100,0));
        f.data.add(pageOf(9,0,150,false,50,100));
        List<String> ids=new QqLibraryApi(f.api).playlistTrackIds(session(),"9_0");
        assertEquals(150,ids.size());
        assertEquals(trackMid(0),ids.get(0));assertEquals(trackMid(99),ids.get(99));
        assertEquals(trackMid(100),ids.get(100));assertEquals(trackMid(149),ids.get(149));
        assertEquals(100,f.param(0).getInt("song_num"));assertEquals(0,f.param(0).getInt("song_begin"));
        assertEquals(100,f.param(1).getInt("song_begin"));assertEquals(9,f.param(0).getLong("disstid"));
        try{ids.clear();fail("Snapshot must be read-only");}catch(UnsupportedOperationException expected){}

        Fixture liked=new Fixture();liked.data.add(pageOf(0,201,0,false,0,0));
        assertTrue(new QqLibraryApi(liked.api).playlistTrackIds(session(),"0_201").isEmpty());
        assertEquals("fixture-euin",liked.param(0).getString("enc_host_uin"));
        Fixture empty=new Fixture();empty.data.add(pageOf(9,0,0,false,0,0));
        assertTrue(new QqLibraryApi(empty.api).playlistTrackIds(session(),"9_0").isEmpty());
    }
    @Test public void playlistTrackIdsFailsClosedOnDriftDuplicatesAndBounds() throws Exception {
        Fixture drift=new Fixture();
        drift.data.add(pageOf(9,0,200,true,100,0));
        drift.data.add(pageOf(9,0,201,true,100,100));
        rejects("incomplete",()->new QqLibraryApi(drift.api).playlistTrackIds(session(),"9_0"));

        Fixture duplicate=new Fixture();
        duplicate.data.add(pageOf(9,0,200,true,100,0));
        duplicate.data.add(pageOf(9,0,200,false,100,0));
        rejects("incomplete",()->new QqLibraryApi(duplicate.api).playlistTrackIds(session(),"9_0"));

        Fixture shortPage=new Fixture();
        shortPage.data.add(pageOf(9,0,200,true,100,0));
        shortPage.data.add(pageOf(9,0,200,false,50,100));
        rejects("incomplete",()->new QqLibraryApi(shortPage.api).playlistTrackIds(session(),"9_0"));

        Fixture oversized=new Fixture();oversized.data.add(pageOf(9,0,20_001,true,100,0));
        rejects("limit",()->new QqLibraryApi(oversized.api).playlistTrackIds(session(),"9_0"));

        Fixture holes=new Fixture();holes.data.add(pageOf(9,0,1,false,0,0));
        rejects("incomplete",()->new QqLibraryApi(holes.api).playlistTrackIds(session(),"9_0"));

        Fixture identity=new Fixture();identity.data.add(pageOf(10,0,0,false,0,0));
        rejects("identity",()->new QqLibraryApi(identity.api).playlistTrackIds(session(),"9_0"));

        Fixture f=new Fixture();
        rejects("identity",()->new QqLibraryApi(f.api).playlistTrackIds(session(),"9_0_1"));
        rejects("identity",()->new QqLibraryApi(f.api).playlistTrackIds(null,"9_0"));
        assertTrue(f.requests.isEmpty());
    }
    @Test public void createPlaylistUsesPinnedAddPlaylistAndRelocatesByReturnedTid() throws Exception {
        Fixture f=new Fixture();
        f.data.add(json("{\"retCode\":0,\"result\":{\"tid\":9,\"dirId\":0,\"dirName\":\"Mine\"}}"));
        f.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":0,\"dirName\":\"Mine 20261106\",\"songNum\":0,\"invalid\":0}]}"));
        f.data.add(pageOf(0,201,0,false,0,0));
        QqLibraryApi.Playlist created=new QqLibraryApi(f.api).createPlaylist(session(),"  Mine  ");
        assertEquals("9_0",created.id);assertEquals("Mine 20261106",created.name);
        assertEquals(QqLibraryApi.Playlist.Kind.CREATED,created.kind);assertFalse(created.liked);assertTrue(created.writable);
        JSONObject request=json(f.requests.get(0).body).getJSONObject("req_0");
        assertEquals("music.musicasset.PlaylistBaseWrite",request.getString("module"));
        assertEquals("AddPlaylist",request.getString("method"));
        assertEquals("{\"dirName\":\"Mine\"}",request.getJSONObject("param").toString());
        assertEquals("24",json(f.requests.get(0).body).getJSONObject("comm").getString("ct"));

        Fixture empty=new Fixture();
        for(String invalid:new String[]{null,"","   ","line\nbreak",String.join("",Collections.nCopies(81,"x"))})
            rejects("request",()->new QqLibraryApi(empty.api).createPlaylist(session(),invalid));
        rejects("identity",()->new QqLibraryApi(empty.api).createPlaylist(null,"Mine"));
        assertTrue(empty.requests.isEmpty());
    }
    @Test public void createPlaylistFailsClosedOnDeniedOrUnlocatableResults() throws Exception {
        Fixture denied=new Fixture();denied.data.add(json("{\"retCode\":80092}"));
        rejects("permission",()->new QqLibraryApi(denied.api).createPlaylist(session(),"Mine"));
        Fixture noResult=new Fixture();noResult.data.add(json("{\"retCode\":0}"));
        rejects("protocol",()->new QqLibraryApi(noResult.api).createPlaylist(session(),"Mine"));
        Fixture badDir=new Fixture();badDir.data.add(json("{\"retCode\":0,\"result\":{\"tid\":9,\"dirId\":201}}"));
        rejects("protocol",()->new QqLibraryApi(badDir.api).createPlaylist(session(),"Mine"));
        Fixture badTid=new Fixture();badTid.data.add(json("{\"retCode\":0,\"result\":{\"tid\":0,\"dirId\":0}}"));
        rejects("identity",()->new QqLibraryApi(badTid.api).createPlaylist(session(),"Mine"));
        Fixture missing=new Fixture();
        missing.data.add(json("{\"retCode\":0,\"result\":{\"tid\":9,\"dirId\":0}}"));
        missing.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":10,\"dirId\":0,\"dirName\":\"Other\",\"songNum\":0,\"invalid\":0}]}"));
        missing.data.add(pageOf(0,201,0,false,0,0));
        rejects("incomplete",()->new QqLibraryApi(missing.api).createPlaylist(session(),"Mine"));
        assertEquals(3,missing.requests.size());
        Fixture bad=new Fixture();
        rejects("endpoint",()->bad.api.library(session(),"music.musicasset.PlaylistBaseWrite","AddPlaylist",
            json("{\"dirName\":\"Mine\",\"dirId\":1}")));
        rejects("endpoint",()->bad.api.library(session(),"music.musicasset.PlaylistBaseWrite","AddPlaylist",
            json("{\"dirName\":\"  \"}")));
        rejects("endpoint",()->bad.api.library(session(),"music.musicasset.PlaylistBaseWrite","DelPlaylist",
            json("{\"dirId\":9}")));
        rejects("endpoint",()->bad.api.library(session(),"music.musicasset.PlaylistBaseWrite","AddPlaylist",
            json("{\"dirName\":\"Mine\\u0001\"}")));
        assertTrue(bad.requests.isEmpty());
    }
    @Test public void protectedSurfaceAllowsOnlyPinnedOneSongWriteShapes() throws Exception {
        Fixture f=new Fixture();
        JSONObject owned=json("{\"dirId\":0,\"tid\":9,\"bFmtUtf8\":true,\"v_songInfo\":[{\"songId\":99,\"songType\":0}]}");
        f.data.add(new JSONObject().put("retCode",0));
        f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",owned);
        assertEquals("DelSonglist",new JSONObject(f.requests.get(0).body).getJSONObject("req_0").getString("method"));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            json("{\"dirId\":0,\"tid\":0,\"bFmtUtf8\":true,\"v_songInfo\":[{\"songId\":99,\"songType\":0}]}")));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            json("{\"dirId\":201,\"tid\":9,\"bFmtUtf8\":true,\"v_songInfo\":[{\"songId\":99,\"songType\":0}]}")));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            json("{\"dirId\":205,\"tid\":9,\"bFmtUtf8\":true,\"v_songInfo\":[{\"songId\":99,\"songType\":0}]}")));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            json("{\"dirId\":209,\"tid\":9,\"bFmtUtf8\":true,\"v_songInfo\":[{\"songId\":99,\"songType\":0}]}")));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            json("{\"dirId\":1000001,\"tid\":9,\"bFmtUtf8\":true,\"v_songInfo\":[{\"songId\":99,\"songType\":0}]}")));
        f.data.add(new JSONObject().put("retCode",0));
        f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            json("{\"dirId\":5,\"tid\":9,\"bFmtUtf8\":true,\"v_songInfo\":[{\"songId\":99,\"songType\":0}]}"));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            json("{\"dirId\":0,\"tid\":9,\"bFmtUtf8\":false,\"v_songInfo\":[{\"songId\":99,\"songType\":0}]}")));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            json("{\"dirId\":0,\"tid\":9,\"bFmtUtf8\":true,\"v_songInfo\":[{\"songId\":99,\"songType\":0},{\"songId\":98,\"songType\":0}]}")));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            json("{\"dirId\":0,\"tid\":9,\"bFmtUtf8\":true,\"v_songInfo\":[{\"songId\":99,\"songType\":0,\"extra\":1}]}")));
        assertEquals(2,f.requests.size());
    }
    @Test public void protectedApiSurfaceRejectsWritesAndCancellationBeforeRequests() throws Exception {
        Fixture f=new Fixture();
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistBaseWrite","MovePlaylist",new JSONObject()));
        rejects("account_changed",()->new QqLibraryApi(f.api,()->false).playlists(session(),0,30));
        assertTrue(f.requests.isEmpty());
    }
    @Test public void audioRequestsExactStandardFileAndProbesOnlyHttpsWithoutExposingUrlInDto() throws Exception {
        Fixture f=new Fixture();f.data.add(new JSONObject().put("track_info",song()));f.data.add(vkey());f.data.add(dispatch());
        List<String> probed=new ArrayList<>();
        QqLibraryApi.Audio audio=new QqLibraryApi(f.api,()->true,url->{probed.add(url);}).audio(session(),MID,1000);
        assertEquals(61000,audio.expiresAtElapsedMs);assertEquals("Audio(redacted)",audio.toString());
        assertTrue(audio.url.startsWith("https://dl.stream.qqmusic.qq.com/"));assertEquals(1,probed.size());
        assertEquals("qq:standard",audio.qualityId);
        assertEquals(filename(),f.param(1).getJSONArray("filename").getString(0));
        assertEquals(MID,f.param(1).getJSONArray("songmid").getString(0));
        assertEquals("12345",f.param(1).getString("uin"));
    }
    @Test public void qualitiesOnlyListFilesThatPassExactAudioProbe() throws Exception {
        String high=filename("M800",".mp3"),lossless=filename("F000",".flac");
        Fixture f=new Fixture();f.data.add(new JSONObject().put("track_info",song()));
        f.data.add(vkey());f.data.add(dispatch());
        f.data.add(vkey(high));f.data.add(dispatch());
        f.data.add(vkey(lossless));f.data.add(dispatch());
        QqLibraryApi library=new QqLibraryApi(f.api,()->true,url->{
            if(url.contains(high))throw new IOException("fixture");
        });
        List<QqLibraryApi.AudioQuality> qualities=library.qualities(session(),MID,1000);
        assertEquals(Arrays.asList("qq:standard","qq:lossless"),
            Arrays.asList(qualities.get(0).id,qualities.get(1).id));
        try{qualities.clear();fail("Quality options must be read-only");}catch(UnsupportedOperationException expected){}
        assertEquals(high,f.param(3).getJSONArray("filename").getString(0));
        assertEquals(lossless,f.param(5).getJSONArray("filename").getString(0));

        Fixture exact=new Fixture();exact.data.add(new JSONObject().put("track_info",song()));
        exact.data.add(vkey(high));exact.data.add(dispatch());
        QqLibraryApi.Audio audio=new QqLibraryApi(exact.api,()->true,url->{}).audio(session(),MID,1000,"qq:high");
        assertEquals("qq:high",audio.qualityId);
        assertEquals(high,exact.param(1).getJSONArray("filename").getString(0));
    }
    @Test public void qualitiesReturnEmptyWhenNoExactFileIsConfirmed() throws Exception {
        Fixture f=new Fixture();f.data.add(new JSONObject().put("track_info",song()));
        for(String file:new String[]{filename(),filename("M800",".mp3"),filename("F000",".flac")}) {
            JSONObject denied=vkey(file);
            denied.getJSONArray("midurlinfo").getJSONObject(0).put("result",104003);
            f.data.add(denied);
        }
        assertTrue(new QqLibraryApi(f.api,()->true,url->{fail("Denied files must not probe");})
            .qualities(session(),MID,1000).isEmpty());
    }
    @Test public void wrongSongFileEncryptedAndPreviewOutputsCannotBecomeAudio() throws Exception {
        JSONObject wrong=vkey();wrong.getJSONArray("midurlinfo").getJSONObject(0).put("songmid","009abcDEF01234");
        rejects("identity",()->QqLibraryApi.audioCandidates(wrong,dispatch(),MID,filename()));
        for(String field:new String[]{"filename","ekey","trybegin","purl"}){
            JSONObject data=vkey();JSONObject row=data.getJSONArray("midurlinfo").getJSONObject(0);
            row.put(field,field.equals("trybegin")?1:field.equals("purl")?"RS02other.mp3?vkey=fixture":"mismatch");
            rejects(field.equals("trybegin")?"preview":"permission",()->QqLibraryApi.audioCandidates(data,dispatch(),MID,filename()));
        }
        JSONObject denied=vkey();denied.getJSONArray("midurlinfo").getJSONObject(0).put("result",104003);
        rejects("permission",()->QqLibraryApi.audioCandidates(denied,dispatch(),MID,filename()));
    }
    @Test public void endpointsAndParserAmbiguitiesFailClosedWithoutHttpFallback() throws Exception {
        for(String url:new String[]{"http://dl.stream.qqmusic.qq.com/a","https://dl.stream.qqmusic.qq.com.evil.test/a",
            "https://evil@dl.stream.qqmusic.qq.com/a","https://dl.stream.qqmusic.qq.com:0443/a",
            "https://dl.stream.qqmusic.qq.com:/a","https://dl.stream.qqmusic.qq.com/a#fragment","https://u.y.qq.com/a",
            "https://m.music.126.net/a","https://fs.youthandroid.kugou.com/a"})rejects("endpoint",()->QqLibraryApi.officialUrl(url));
        JSONObject evil=new JSONObject().put("sip",new JSONArray().put("https://evil.test/"));
        rejects("unavailable",()->QqLibraryApi.audioCandidates(vkey(),evil,MID,filename()));
        assertEquals("https://aqqmusic.tc.qq.com/a",QqLibraryApi.officialUrl("https://aqqmusic.tc.qq.com/a"));
    }
    @Test public void failedFavoriteIdsBooleanPreviewAndRejectedSourceStopBeforeCdnRequest() throws Exception {
        Fixture failed=new Fixture();failed.data.add(json("{\"bFinish\":1,\"v_playlist\":[]}"));
        failed.data.add(json("{\"hide\":0,\"v_failTids\":[123],\"v_list\":[],\"total\":0,\"hasmore\":0}"));
        rejects("incomplete",()->new QqLibraryApi(failed.api).playlists(session(),0,30));
        JSONObject preview=vkey();preview.getJSONArray("midurlinfo").getJSONObject(0).put("is_preview",true);
        rejects("preview",()->QqLibraryApi.audioCandidates(preview,dispatch(),MID,filename()));
        Fixture denied=new Fixture();denied.data.add(new JSONObject().put("track_info",song()));
        JSONObject deniedData=vkey();deniedData.getJSONArray("midurlinfo").getJSONObject(0).put("result",104003);denied.data.add(deniedData);
        rejects("permission",()->new QqLibraryApi(denied.api,()->true,url->{fail("Denied audio must not probe");}).audio(session(),MID,1000));
        assertEquals(2,denied.requests.size());
    }
    @Test public void searchUsesFixedFirstPageAndReturnsReadOnlySongSummaries() throws Exception {
        Fixture f=new Fixture();f.data.add(search(10));
        List<QqLibraryApi.Song> result=new QqLibraryApi(f.api).search(session(),"fixture song");
        assertEquals(10,result.size());assertEquals(MID,result.get(0).id);assertEquals("Song",result.get(0).name);
        assertEquals("Artist",result.get(0).artists.get(0));assertEquals("Album",result.get(0).album);
        assertEquals(123000,result.get(0).durationMs);assertEquals(MEDIA,result.get(0).mediaMid);
        JSONObject request=json(f.requests.get(0).body).getJSONObject("req_0");
        assertEquals("music.search.SearchCgiService",request.getString("module"));
        assertEquals("DoSearchForQQMusicMobile",request.getString("method"));
        JSONObject param=request.getJSONObject("param");
        assertEquals("fixture song",param.getString("query"));assertFalse(param.has("keywords"));assertEquals(0,param.getInt("search_type"));
        assertEquals(10,param.getInt("num_per_page"));assertEquals(1,param.getInt("page_num"));
        assertFalse(param.getBoolean("highlight"));assertTrue(param.getBoolean("grp"));
        assertEquals(0,param.getJSONObject("selectors").length());assertEquals(0,param.getJSONArray("vec_selectors").length());
        assertTrue(param.getString("searchid").matches("[0-9]{1,19}"));
        assertEquals(4*1024*1024,f.requests.get(0).limit);
        try{result.clear();fail("Search result must be read-only");}catch(UnsupportedOperationException expected){}
    }
    @Test public void searchRejectsInvalidKeywordsAndOversizedRowsBeforeParsingSongs() throws Exception {
        rejects("request",()->new QqLibraryApi(new Fixture().api).search(session(),null));
        rejects("request",()->new QqLibraryApi(new Fixture().api).search(session()," \t "));
        rejects("request",()->new QqLibraryApi(new Fixture().api).search(session(),"fixture\u0007query"));
        rejects("request",()->new QqLibraryApi(new Fixture().api).search(session(),
            String.join("",Collections.nCopies(121,"x"))));
        Fixture boundary=new Fixture();boundary.data.add(search(0));
        assertEquals(0,new QqLibraryApi(boundary.api).search(session(),
            String.join("",Collections.nCopies(120,"x"))).size());
        Fixture oversized=new Fixture();oversized.data.add(search(11));
        rejects("limit",()->new QqLibraryApi(oversized.api).search(session(),"fixture song"));
        assertTrue(oversized.requests.size()==1);
    }
    @Test public void searchValidatesRequiredStructureMetadataAndSongRows() throws Exception {
        rejects("protocol",()->QqLibraryApi.parseSearch(new JSONObject()));
        JSONObject noMeta=search(1);noMeta.remove("meta");
        rejects("protocol",()->QqLibraryApi.parseSearch(noMeta));
        JSONObject noSongs=search(1);noSongs.getJSONObject("body").remove("item_song");
        rejects("protocol",()->QqLibraryApi.parseSearch(noSongs));
        for(String field:new String[]{"perpage","nextpage","sum"}){
            JSONObject bad=search(1);JSONObject meta=bad.getJSONObject("meta");
            meta.put(field,field.equals("perpage")?11:field.equals("nextpage")?3:0);
            rejects(field.equals("sum")?"incomplete":"protocol",()->QqLibraryApi.parseSearch(bad));
        }
        JSONObject malformed=search(1);malformed.getJSONObject("body").getJSONArray("item_song").put(0,"not-an-object");
        rejects("protocol",()->QqLibraryApi.parseSearch(malformed));
    }
    private static JSONObject searchPage(int offset,int count,Integer total,boolean more) throws Exception {
        JSONArray rows=new JSONArray();
        for(int i=0;i<count;i++)rows.put(song().put("mid",String.format(Locale.ROOT,"%014d",offset+i+1)));
        JSONObject meta=new JSONObject().put("perpage",30).put("curpage",offset/30+1).put("nextpage",more?offset/30+2:-1);
        if(total!=null)meta.put("sum",total);
        return new JSONObject().put("body",new JSONObject().put("item_song",rows)).put("meta",meta);
    }
    @Test public void pagedSearchUsesRealPageNumbersAndValidatesBoundsBeforeTransport() throws Exception {
        Fixture f=new Fixture();f.data.add(searchPage(30,2,32,false));
        QqLibraryApi library=new QqLibraryApi(f.api);
        QqLibraryApi.Page<QqLibraryApi.Song> page=library.search(session(),"Song",30,30);
        assertEquals(2,f.param(0).getInt("page_num"));assertEquals(30,f.param(0).getInt("num_per_page"));
        assertEquals(32,page.total);assertEquals(-1,page.nextOffset);assertEquals("00000000000031",page.items.get(0).id);
        for(int offset:new int[]{-30,1,20_010})rejects("limit",()->library.search(session(),"Song",offset,30));
        rejects("limit",()->library.search(session(),"Song",0,0));assertEquals(1,f.requests.size());
    }
    @Test public void pagedSearchChecksContinuationTotalsAndTheTwentyThousandBound() throws Exception {
        assertEquals(30,QqLibraryApi.parseSearch(searchPage(0,30,100_000,true),0,30).nextOffset);
        QqLibraryApi.Page<QqLibraryApi.Song> unknown=QqLibraryApi.parseSearch(searchPage(30,2,null,false),30,30);
        assertEquals(-1,unknown.total);assertEquals(-1,unknown.nextOffset);
        assertEquals(60,QqLibraryApi.parseSearch(searchPage(30,30,null,true),30,30).nextOffset);
        rejects("incomplete",()->QqLibraryApi.parseSearch(searchPage(30,2,100,false),30,30));
        rejects("incomplete",()->QqLibraryApi.parseSearch(searchPage(30,2,32,true),30,30));
        rejects("incomplete",()->QqLibraryApi.parseSearch(searchPage(30,0,100,true),30,30));
        rejects("limit",()->QqLibraryApi.parseSearch(searchPage(0,31,100,true),0,30));
        JSONObject repeated=searchPage(30,30,100,true);repeated.getJSONObject("meta").put("nextpage",2);
        rejects("protocol",()->QqLibraryApi.parseSearch(repeated,30,30));
        JSONObject firstPage=searchPage(30,30,100,true);firstPage.getJSONObject("meta").put("curpage",1);
        rejects("protocol",()->QqLibraryApi.parseSearch(firstPage,30,30));
        assertEquals(-1,QqLibraryApi.parseSearch(searchPage(19_980,20,20_000,false),19_980,30).nextOffset);
        rejects("limit",()->QqLibraryApi.parseSearch(searchPage(19_980,30,100_000,true),19_980,30));
        rejects("limit",()->QqLibraryApi.parseSearch(searchPage(19_980,1,100_000,true),19_980,30));
        rejects("limit",()->QqLibraryApi.parseSearch(searchPage(19_980,21,20_001,false),19_980,30));
    }
    @Test public void artworkParsesOfficialFieldsOrValidAlbumMidWithoutAudioRequests() throws Exception {
        JSONObject row=song();row.getJSONObject("album").put("mid",MID);
        assertEquals("https://y.gtimg.cn/music/photo_new/T002R300x300M000"+MID+".jpg",QqLibraryApi.song(row).coverUrl);
        row.getJSONObject("album").put("mid","bad").put("picUrl","http://y.gtimg.cn/album.jpg");
        assertEquals("https://y.gtimg.cn/album.jpg",QqLibraryApi.song(row).coverUrl);
        for(String url:new String[]{"https://y.gtimg.cn/a?key=fixture","https://y.gtimg.cn/a#f","https://user@y.gtimg.cn/a",
                "https://y.gtimg.cn:443/a","https://y.gtimg.cn.evil/a","https://dl.stream.qqmusic.qq.com/a.mp3"}){
            row.getJSONObject("album").put("picUrl",url);assertNull(QqLibraryApi.song(row).coverUrl);
        }
        assertNull(new QqLibraryApi.Song(MID,"Song",Collections.emptyList(),"",0,"",0).coverUrl);
        LinkedHashMap<String,QqLibraryApi.Playlist> lists=new LinkedHashMap<>();
        QqLibraryApi.appendLists(new JSONArray().put(json("{tid:9,dirId:0,title:'List',songNum:1,cover_url_big:'http://qpic.y.qq.com/list.jpg'}")),lists,true);
        assertEquals("https://qpic.y.qq.com/list.jpg",lists.get("9_0").coverUrl);
        assertEquals(QqLibraryApi.Playlist.Kind.CREATED,lists.get("9_0").kind);
        QqLibraryApi.appendLists(new JSONArray().put(json("{tid:10,dirId:0,title:'Collected',songNum:1}")),lists,false);
        assertEquals(QqLibraryApi.Playlist.Kind.COLLECTED,lists.get("10_0").kind);
        assertNull(new QqLibraryApi.Playlist("9_0","List",0).coverUrl);
    }
    @Test public void legacyOptionalEntityMidsBecomeMissingWithoutWeakeningSongMid() throws Exception {
        JSONObject row=song();
        JSONObject artist=row.getJSONArray("singer").getJSONObject(0);
        for(Object invalid:new Object[]{null,"",0,JSONObject.NULL,"bad"}){
            if(invalid==null)artist.remove("mid");else artist.put("mid",invalid);
            JSONObject album=row.getJSONObject("album");
            if(invalid==null)album.remove("mid");else album.put("mid",invalid);
            QqLibraryApi.Song parsed=QqLibraryApi.song(row);
            assertEquals(MID,parsed.id);
            assertTrue(parsed.artistIds.isEmpty());
            assertNull(parsed.albumId);
        }
        artist.put("mid",MID);
        row.getJSONObject("album").put("mid",MID);
        QqLibraryApi.Song valid=QqLibraryApi.song(row);
        assertEquals(Collections.singletonList(MID),valid.artistIds);
        assertEquals(MID,valid.albumId);

        JSONObject invalidSong=song().put("mid",0);
        rejects("identity",()->QqLibraryApi.song(invalidSong));
        JSONObject wrongArtist=new JSONObject().put("singerMid","009abcDEF01234").put("totalNum",0);
        rejects("identity",()->QqLibraryApi.parseArtistTracks(wrongArtist,MID,0,30));
        JSONObject wrongAlbum=new JSONObject().put("albumMid","009abcDEF01234").put("totalNum",0);
        rejects("identity",()->QqLibraryApi.parseAlbumTracks(wrongAlbum,MID,0,30));
    }
    @Test public void detailUsesFixedReadOnlyEndpointAndRejectsMidMismatch() throws Exception {
        Fixture f=new Fixture();f.data.add(new JSONObject().put("track_info",song()));
        QqLibraryApi.Song result=new QqLibraryApi(f.api).song(session(),MID);
        assertEquals(MID,result.id);assertEquals("Song",result.name);
        JSONObject request=json(f.requests.get(0).body).getJSONObject("req_0");
        assertEquals("music.pf_song_detail_svr",request.getString("module"));
        assertEquals("get_song_detail_yqq",request.getString("method"));
        assertEquals(MID,request.getJSONObject("param").getString("song_mid"));
        Fixture mismatch=new Fixture();JSONObject wrong=song();wrong.put("mid","009abcDEF01234");
        mismatch.data.add(new JSONObject().put("track_info",wrong));
        rejects("identity",()->new QqLibraryApi(mismatch.api).song(session(),MID));
    }
    @Test public void lyricsUseFixedPlainCgiAndDecodeOnlyStandardBase64Lrc() throws Exception {
        Fixture f=new Fixture();String lrc="[00:01.00]Hello\n[00:02.00]世界";
        String translation="[00:01.00]你好\n[00:02.00]世界";
        JSONObject response=new JSONObject().put("lyric",lrc).put("trans",translation);
        f.data.add(new JSONObject(response.toString()));f.data.add(response);
        QqLibraryApi.LyricsDocument document=new QqLibraryApi(f.api).lyricsDocument(session(),MID);
        assertEquals(lrc,document.original);assertEquals(translation,document.translation);
        assertEquals(QqLibraryApi.TranslationStatus.PRESENT,document.translationStatus);
        assertEquals(lrc,new QqLibraryApi(f.api).lyrics(session(),MID));
        assertEquals(2,f.requests.size());
        JSONObject request=new JSONObject(f.requests.get(0).body).getJSONObject("req_0");
        assertEquals("music.musichallSong.PlayLyricInfo",request.getString("module"));
        assertEquals("GetPlayLyricInfo",request.getString("method"));
        JSONObject param=request.getJSONObject("param");
        assertEquals(0,param.getInt("crypt"));assertEquals(0,param.getInt("lrc_t"));
        assertEquals(0,param.getInt("qrc"));assertEquals(0,param.getInt("qrc_t"));
        assertEquals(0,param.getInt("roma"));assertEquals(0,param.getInt("roma_t"));
        assertEquals(1,param.getInt("trans"));assertEquals(0,param.getInt("trans_t"));
        assertEquals(false,param.getBoolean("needSingingAnnotations"));
        assertEquals(1,param.getInt("type"));assertEquals(MID,param.getString("songMid"));
        String encoded=Base64.getEncoder().encodeToString(lrc.getBytes(StandardCharsets.UTF_8));
        assertEquals(lrc,QqLibraryApi.parseLyrics(encoded));
        assertEquals("plain words",QqLibraryApi.parseLyrics("plain words"));
    }
    @Test public void lyricsDocumentTreatsMissingNullAndBlankTranslationAsAbsent() throws Exception {
        String lrc="[00:01.00]Original";
        QqLibraryApi.LyricsDocument missing=QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",lrc));
        assertEquals(lrc,missing.original);assertNull(missing.translation);
        assertEquals(QqLibraryApi.TranslationStatus.ABSENT,missing.translationStatus);
        for(Object blank:new Object[]{JSONObject.NULL,""," \t\n"}) {
            QqLibraryApi.LyricsDocument document=QqLibraryApi.parseLyricsDocument(
                new JSONObject().put("lyric",lrc).put("trans",blank));
            assertEquals(lrc,document.original);assertNull(document.translation);
            assertEquals(QqLibraryApi.TranslationStatus.ABSENT,document.translationStatus);
        }
    }
    @Test public void lyricsDocumentAcceptsUnicodePlainAndStandardBase64Translation() throws Exception {
        String lrc="[00:01.00]Original";
        String plain="[00:01.00]译文\uD83C\uDFB5";
        QqLibraryApi.LyricsDocument passive=QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",lrc).put("trans",plain));
        assertEquals(plain,passive.translation);
        assertEquals(QqLibraryApi.TranslationStatus.PRESENT,passive.translationStatus);
        String encoded=Base64.getEncoder().encodeToString(plain.getBytes(StandardCharsets.UTF_8));
        QqLibraryApi.LyricsDocument active=QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",lrc).put("trans",encoded));
        assertEquals(plain,active.translation);
        assertEquals(QqLibraryApi.TranslationStatus.PRESENT,active.translationStatus);
        QqLibraryApi.LyricsDocument unsynced=QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",lrc).put("trans","test"));
        assertEquals("test",unsynced.translation);
        assertEquals(QqLibraryApi.TranslationStatus.PRESENT,unsynced.translationStatus);
    }
    @Test public void invalidTranslationDegradesIndependentlyOfValidOriginal() throws Exception {
        String lrc="[00:01.00]Original";
        String invalidUtf8=Base64.getEncoder().encodeToString(new byte[]{(byte)0xff});
        String qrc=Base64.getEncoder().encodeToString(
            "[QrcInfos][QrcLyric][00:01.00]translation".getBytes(StandardCharsets.UTF_8));
        for(Object invalid:new Object[]{7,invalidUtf8,"AAAA==",
                "0123456789abcdef0123456789abcdef","[00:01.00]bad"+(char)0+"text",
                "[00:01.00]bad"+(char)0xD800+"text",qrc}) {
            QqLibraryApi.LyricsDocument document=QqLibraryApi.parseLyricsDocument(
                new JSONObject().put("lyric",lrc).put("trans",invalid));
            assertEquals(lrc,document.original);assertNull(document.translation);
            assertEquals(QqLibraryApi.TranslationStatus.INVALID,document.translationStatus);
        }
    }
    @Test public void translationDecodedUtf8LimitIsExact() throws Exception {
        String lrc="[00:01.00]Original";
        String prefix="[00:01.00]";
        String exact=prefix+String.join("",Collections.nCopies(256*1024-prefix.length(),"x"));
        String exactEncoded=Base64.getEncoder().encodeToString(exact.getBytes(StandardCharsets.UTF_8));
        QqLibraryApi.LyricsDocument accepted=QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",lrc).put("trans",exactEncoded));
        assertEquals(exact,accepted.translation);
        assertEquals(QqLibraryApi.TranslationStatus.PRESENT,accepted.translationStatus);
        String oversized=prefix+String.join("",Collections.nCopies(256*1024-prefix.length()+1,"x"));
        String oversizedEncoded=Base64.getEncoder().encodeToString(oversized.getBytes(StandardCharsets.UTF_8));
        QqLibraryApi.LyricsDocument rejected=QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",lrc).put("trans",oversizedEncoded));
        assertEquals(lrc,rejected.original);assertNull(rejected.translation);
        assertEquals(QqLibraryApi.TranslationStatus.INVALID,rejected.translationStatus);
    }
    @Test public void blankTranslationStillHonorsDecodedLimitAndEncodedInputIsBounded() throws Exception {
        String lrc="[00:01.00]Original";
        String blank=String.join("",Collections.nCopies(256*1024," "));
        QqLibraryApi.LyricsDocument exact=QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",lrc).put("trans",blank));
        assertEquals(QqLibraryApi.TranslationStatus.ABSENT,exact.translationStatus);
        for(String invalid:new String[]{blank+" ",String.join("",Collections.nCopies(350*1024+1,"A"))}){
            QqLibraryApi.LyricsDocument document=QqLibraryApi.parseLyricsDocument(
                new JSONObject().put("lyric",lrc).put("trans",invalid));
            assertEquals(lrc,document.original);assertNull(document.translation);
            assertEquals(QqLibraryApi.TranslationStatus.INVALID,document.translationStatus);
        }
    }
    @Test public void lyricsDocumentDoesNotHideInvalidOriginal() throws Exception {
        String validTranslation="[00:01.00]Translation";
        rejects("protocol",()->QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",7).put("trans",validTranslation)));
        rejects("limit",()->QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",String.join("",Collections.nCopies(512*1024+1,"x")))
                .put("trans",validTranslation)));
    }
    @Test public void lyricsDocumentToStringRedactsBothLyrics() throws Exception {
        String original="[00:01.00]secret original";
        String translation="[00:01.00]secret translation";
        QqLibraryApi.LyricsDocument document=QqLibraryApi.parseLyricsDocument(
            new JSONObject().put("lyric",original).put("trans",translation));
        String rendered=document.toString();
        assertFalse(rendered.contains(original));assertFalse(rendered.contains(translation));
        assertTrue(rendered.contains("redacted"));
    }
    @Test public void lyricsReturnEmptyAndRejectInvalidOrOversizedContent() throws Exception {
        for(Object empty:new Object[]{"",JSONObject.NULL}){
            Fixture f=new Fixture();f.data.add(new JSONObject().put("lyric",empty));
            assertEquals("",new QqLibraryApi(f.api).lyrics(session(),MID));
        }
        Fixture invalid=new Fixture();invalid.data.add(new JSONObject().put("lyric",7));
        rejects("protocol",()->new QqLibraryApi(invalid.api).lyrics(session(),MID));
        rejects("protocol",()->QqLibraryApi.parseLyrics("0123456789abcdef0123456789abcdef"));
        rejects("limit",()->QqLibraryApi.parseLyrics(String.join("",Collections.nCopies(512*1024+1,"x"))));
        String lrc="[00:00.00]"+String.join("",Collections.nCopies(512*1024,"x"));
        String encoded=Base64.getEncoder().encodeToString(lrc.getBytes(StandardCharsets.UTF_8));
        rejects("limit",()->QqLibraryApi.parseLyrics(encoded));
        rejects("protocol",()->QqLibraryApi.parseLyrics(String.join("",Collections.nCopies(512*1024+4,"A"))));
        rejects("limit",()->QqLibraryApi.parseLyrics(String.join("",Collections.nCopies(700*1024+1,"A"))));
        String exactLyric="[00:00.00]"+String.join("",Collections.nCopies(512*1024-10,"x"));
        String exactEncoded=Base64.getEncoder().encodeToString(exactLyric.getBytes(StandardCharsets.UTF_8));
        assertEquals(exactLyric,QqLibraryApi.parseLyrics(exactEncoded));
    }

}
