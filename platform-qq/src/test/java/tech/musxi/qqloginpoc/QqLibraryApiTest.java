package tech.musxi.qqloginpoc;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class QqLibraryApiTest {
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

}
