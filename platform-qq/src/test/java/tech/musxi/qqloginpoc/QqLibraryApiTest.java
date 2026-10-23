package tech.musxi.qqloginpoc;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class QqLibraryApiTest {
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
    private static JSONObject search(int count) throws Exception{
        JSONArray rows=new JSONArray();for(int i=0;i<count;i++)rows.put(song());
        return new JSONObject().put("body",new JSONObject().put("item_song",rows)).put("meta",
            new JSONObject().put("perpage",10).put("nextpage",-1).put("sum",count));
    }
    private static String filename(){return "M500"+MEDIA+".mp3";}
    private static JSONObject vkey() throws Exception {return new JSONObject().put("midurlinfo",new JSONArray().put(new JSONObject().put("songmid",MID).put("filename",filename()).put("result",0).put("ekey","").put("purl",filename()+"?vkey=fixture-url-key")));}
    private static JSONObject dispatch() throws Exception{return new JSONObject().put("sip",new JSONArray().put("http://dl.stream.qqmusic.qq.com/").put("https://isure.stream.qqmusic.qq.com/"));}

    @Test public void createdAndFavoriteListsMergeBeforeSlicingAndNeverLoseContinuation() throws Exception {
        Fixture f=new Fixture();
        f.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":0,\"dirName\":\"Created\",\"songNum\":1,\"invalid\":0}]}"));
        f.data.add(json("{\"total\":2,\"hasmore\":1,\"hide\":0,\"v_list\":[{\"tid\":10,\"dirid\":0,\"title\":\"Fav\",\"songnum\":2}]}"));
        f.data.add(json("{\"total\":2,\"hasmore\":0,\"hide\":0,\"v_list\":[{\"tid\":9,\"dirid\":0,\"title\":\"Duplicate\",\"songnum\":1}]}"));
        QqLibraryApi.Page<QqLibraryApi.Playlist> result=new QqLibraryApi(f.api).playlists(session(),0,1);
        assertEquals(2,result.total);assertEquals(1,result.nextOffset);assertEquals("9_0",result.items.get(0).id);
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
    @Test public void trackOrderCountsIdentityAndMissingStructuresAreChecked() throws Exception {
        QqLibraryApi.Page<QqLibraryApi.Song> result=QqLibraryApi.parseTracks(tracks(2,true),"9_0",0,30);
        assertEquals(1,result.nextOffset);assertEquals(MID,result.items.get(0).id);assertEquals(123000,result.items.get(0).durationMs);
        rejects("incomplete",()->QqLibraryApi.parseTracks(tracks(2,false),"9_0",0,30));
        rejects("identity",()->QqLibraryApi.parseTracks(tracks(1,false),"10_0",0,30));
        rejects("protocol",()->QqLibraryApi.parseTracks(new JSONObject(),"9_0",0,30));
        JSONObject missing=tracks(1,false);missing.getJSONArray("songlist").getJSONObject(0).remove("mid");
        rejects("identity",()->QqLibraryApi.parseTracks(missing,"9_0",0,30));
    }
    @Test public void protectedApiSurfaceRejectsWritesAndCancellationBeforeRequests() throws Exception {
        Fixture f=new Fixture();
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistBaseWrite","AddPlaylist",new JSONObject()));
        rejects("account_changed",()->new QqLibraryApi(f.api,()->false).playlists(session(),0,30));
        assertTrue(f.requests.isEmpty());
    }
    @Test public void audioRequestsExactStandardFileAndProbesOnlyHttpsWithoutExposingUrlInDto() throws Exception {
        Fixture f=new Fixture();f.data.add(new JSONObject().put("track_info",song()));f.data.add(vkey());f.data.add(dispatch());
        List<String> probed=new ArrayList<>();
        QqLibraryApi.Audio audio=new QqLibraryApi(f.api,()->true,url->{probed.add(url);}).audio(session(),MID,1000);
        assertEquals(61000,audio.expiresAtElapsedMs);assertEquals("Audio(redacted)",audio.toString());
        assertTrue(audio.url.startsWith("https://dl.stream.qqmusic.qq.com/"));assertEquals(1,probed.size());
        assertEquals(filename(),f.param(1).getJSONArray("filename").getString(0));
        assertEquals(MID,f.param(1).getJSONArray("songmid").getString(0));
        assertEquals("12345",f.param(1).getString("uin"));
    }
    @Test public void wrongSongFileEncryptedAndPreviewOutputsCannotBecomeAudio() throws Exception {
        JSONObject wrong=vkey();wrong.getJSONArray("midurlinfo").getJSONObject(0).put("songmid","009abcDEF01234");
        rejects("identity",()->QqLibraryApi.audioCandidates(wrong,dispatch(),MID,filename()));
        for(String field:new String[]{"filename","ekey","trybegin","purl"}){
            JSONObject data=vkey();JSONObject row=data.getJSONArray("midurlinfo").getJSONObject(0);
            row.put(field,field.equals("trybegin")?1:field.equals("purl")?"RS02other.mp3?vkey=fixture":"mismatch");
            rejects("permission",()->QqLibraryApi.audioCandidates(data,dispatch(),MID,filename()));
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
        rejects("permission",()->QqLibraryApi.audioCandidates(preview,dispatch(),MID,filename()));
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
        assertNull(new QqLibraryApi.Playlist("9_0","List",0).coverUrl);
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
