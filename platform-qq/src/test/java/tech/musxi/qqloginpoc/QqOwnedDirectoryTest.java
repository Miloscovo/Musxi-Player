package tech.musxi.qqloginpoc;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Ordinary created playlists take the directory the server assigns. Device evidence shows dirId 1
 * and 2 next to the 201 system entry in one GetPlaylistByUin snapshot, so writability follows the
 * returned tid_dirId pair rather than an assumed dirId 0. */
public class QqOwnedDirectoryTest {
    private static final String MID="003abcDEF01234";
    @Test public void readOnlySystemRowsNeverAdvertiseOrdinaryWritePermission() throws Exception {
        LinkedHashMap<String,QqLibraryApi.Playlist> result=new LinkedHashMap<>();
        QqLibraryApi.appendLists(new JSONArray("[{tid:12,dirId:205,dirName:'System',songNum:0},"
            +"{tid:9,dirId:1,dirName:'Ordinary',songNum:0}]"),result,true);
        assertFalse(result.get("12_205").writable);
        assertTrue(result.get("9_1").writable);
    }
    @Test public void writableUsesServerDirectoryAndSkipsSystemDirectories() throws Exception {
        Fixture f=new Fixture();
        f.data.add(json("{\"bFinish\":1,\"v_playlist\":["
            +"{\"tid\":9,\"dirId\":1,\"dirName\":\"One\",\"songNum\":1,\"invalid\":0},"
            +"{\"tid\":10,\"dirId\":2,\"dirName\":\"Two\",\"songNum\":0,\"invalid\":0},"
            +"{\"tid\":11,\"dirId\":201,\"dirName\":\"Liked echo\",\"songNum\":5,\"invalid\":0},"
            +"{\"tid\":12,\"dirId\":205,\"dirName\":\"Qzone\",\"songNum\":0,\"invalid\":0},"
            +"{\"tid\":13,\"dirId\":209,\"dirName\":\"System\",\"songNum\":0,\"invalid\":0},"
            +"{\"tid\":14,\"dirId\":1,\"dirName\":\"Hidden\",\"songNum\":0,\"invalid\":1}]}"));
        f.data.add(emptyPage());
        List<QqLibraryApi.Playlist> result=new QqLibraryApi(f.api).writablePlaylists(session());
        assertEquals(Arrays.asList("0_201","9_1","10_2"),ids(result));
        assertEquals(QqLibraryApi.Playlist.Kind.LIKED,result.get(0).kind);
        assertEquals(QqLibraryApi.Playlist.Kind.CREATED,result.get(1).kind);
        assertTrue(result.get(1).writable);
    }
    @Test public void ownedWriteUsesDirectoryFromTheFreshSnapshot() throws Exception {
        for(int dirId:new int[]{1,2}){
            Fixture f=new Fixture();
            f.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":"+dirId
                +",\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0}]}"));
            f.data.add(new JSONObject().put("track_info",song().put("id",99)));
            f.data.add(new JSONObject().put("retCode",0));
            new QqLibraryApi(f.api).addToPlaylist(session(),"9_"+dirId,MID);
            JSONObject params=f.param(2);
            assertEquals(dirId,params.getInt("dirId"));assertEquals(9,params.getLong("tid"));
            assertTrue(params.getBoolean("bFmtUtf8"));
            assertEquals(2,params.getJSONArray("v_songInfo").getJSONObject(0).length());
            assertEquals("AddSonglist",
                new JSONObject(f.requests.get(2).body).getJSONObject("req_0").getString("method"));
        }
    }
    @Test public void forgedOrSystemRowsAreRefusedAfterTheFreshRead() throws Exception {
        // A row the server marks invalid is data the snapshot already refused; reusing its dirId is
        // not ownership.
        Fixture invalid=new Fixture();
        invalid.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":1,\"dirName\":\"Hidden\","
            +"\"songNum\":0,\"invalid\":1}]}"));
        rejects("permission",()->new QqLibraryApi(invalid.api).addToPlaylist(session(),"9_1",MID));
        assertEquals(1,invalid.requests.size());

        // The same tid announced under a system directory is still a system directory.
        Fixture system=new Fixture();
        system.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":201,\"dirName\":\"Liked\","
            +"\"songNum\":0,\"invalid\":0}]}"));
        rejects("permission",()->new QqLibraryApi(system.api).addToPlaylist(session(),"9_201",MID));
        assertEquals(0,system.requests.size());

        // System directories and the liked alias never reach AddSonglist, even before transport.
        for(String id:new String[]{"9_205","9_206","9_209","0_201"}){
            Fixture f=new Fixture();
            if(id.equals("0_201")) assertTrue(f.requests.isEmpty());
            else rejects("permission",()->new QqLibraryApi(f.api).addToPlaylist(session(),id,MID));
            if(!id.equals("0_201"))assertTrue(id,f.requests.isEmpty());
        }
        Fixture alias=new Fixture();
        rejects("identity",()->new QqLibraryApi(alias.api).addToPlaylist(session(),"0_1",MID));
        assertTrue(alias.requests.isEmpty());
    }
    @Test public void createUsesTheReturnedDirectoryAndRequiresAFreshMatch() throws Exception {
        for(int dirId:new int[]{1,2}){
            Fixture f=new Fixture();
            f.data.add(json("{\"retCode\":0,\"result\":{\"tid\":9,\"dirId\":"+dirId+",\"dirName\":\"Mine\"}}"));
            f.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":"+dirId
                +",\"dirName\":\"Mine 20261106\",\"songNum\":0,\"invalid\":0}]}"));
            f.data.add(emptyPage());
            QqLibraryApi.Playlist created=new QqLibraryApi(f.api).createPlaylist(session(),"Mine");
            assertEquals("9_"+dirId,created.id);
            assertEquals("Mine 20261106",created.name);
            assertEquals(QqLibraryApi.Playlist.Kind.CREATED,created.kind);
            assertTrue(created.writable);
        }
        // The echo is located by tid/dirId, never by the possibly-renamed name.
        Fixture renamed=new Fixture();
        renamed.data.add(json("{\"retCode\":0,\"result\":{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\"}}"));
        renamed.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":1,\"dirName\":\"Other\","
            +"\"songNum\":0,\"invalid\":0}]}"));
        renamed.data.add(emptyPage());
        assertEquals("Other",new QqLibraryApi(renamed.api).createPlaylist(session(),"Mine").name);

        // A create echo outside the ordinary range cannot be trusted.
        for(int dirId:new int[]{201,205,209,1_000_001}){
            Fixture bad=new Fixture();
            bad.data.add(json("{\"retCode\":0,\"result\":{\"tid\":9,\"dirId\":"+dirId+"}}"));
            rejects(dirId>1_000_000?"limit":"protocol",()->new QqLibraryApi(bad.api).createPlaylist(session(),"Mine"));
            assertEquals(1,bad.requests.size());
        }
        // The fresh snapshot is authoritative: a tid_dirId the server did not return is not the list.
        Fixture drift=new Fixture();
        drift.data.add(json("{\"retCode\":0,\"result\":{\"tid\":9,\"dirId\":2}}"));
        drift.data.add(json("{\"bFinish\":1,\"v_playlist\":[{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\","
            +"\"songNum\":0,\"invalid\":0}]}"));
        drift.data.add(emptyPage());
        rejects("incomplete",()->new QqLibraryApi(drift.api).createPlaylist(session(),"Mine"));
    }
    @Test public void transportAcceptsOnlyOrdinaryOneSongWrites() throws Exception {
        Fixture f=new Fixture();
        for(int dirId:new int[]{0,1,2,1_000_000}){
            f.data.add(new JSONObject().put("retCode",0));
            f.api.library(session(),"music.musicasset.PlaylistDetailWrite","AddSonglist",
                write(dirId,9,new JSONObject().put("songId",99).put("songType",0)));
        }
        assertEquals(4,f.requests.size());
        int before=f.requests.size();
        for(int dirId:new int[]{201,205,206,209,1_000_001}){
            rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","AddSonglist",
                write(dirId,9,new JSONObject().put("songId",99).put("songType",0))));
        }
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","AddSonglist",
            write(1,9,new JSONObject().put("songId",99).put("songType",0).put("extra",1))));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","AddSonglist",
            new JSONObject().put("dirId",1).put("tid",9).put("bFmtUtf8",true)
                .put("v_songInfo",new JSONArray().put(new JSONObject().put("songId",99).put("songType",0))
                    .put(new JSONObject().put("songId",98).put("songType",0)))));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","AddSonglist",
            new JSONObject().put("dirId",1).put("tid",9).put("bFmtUtf8",true).put("v_songInfo",
                new JSONArray().put(new JSONObject().put("songId",99).put("songType",0))).put("extra",1)));
        assertEquals(before,f.requests.size());
    }
    private static JSONObject write(int dirId,int tid,JSONObject song) throws Exception {
        return new JSONObject().put("dirId",dirId).put("tid",tid).put("bFmtUtf8",true)
            .put("v_songInfo",new JSONArray().put(song));
    }
    private static List<String> ids(List<QqLibraryApi.Playlist> lists){
        List<String> ids=new ArrayList<>();for(QqLibraryApi.Playlist list:lists)ids.add(list.id);return ids;
    }
    private static JSONObject emptyPage() throws Exception {
        return json("{\"dirinfo\":{\"tid\":0,\"dirid\":201},\"songlist\":[],"
            +"\"total_song_num\":0,\"hasmore\":0,\"songlist_size\":0}");
    }
    private static JSONObject json(String value) throws Exception { return new JSONObject(value); }
    private static QqMusicApi.Session session() throws Exception {return QqMusicApi.Session.fromJson(json(
        "{\"credential\":{\"musicid\":12345,\"musickey\":\"fixture-key\",\"encryptUin\":\"fixture-euin\",\"loginType\":2},\"nickname\":\"Fixture\"}"));}
    private interface Checked {void run() throws Exception;}
    private static void rejects(String code,Checked work) throws Exception {
        try{work.run();fail("Expected fixed failure");}
        catch(QqMusicApi.ApiException ex){assertEquals(code,ex.code);assertFalse(ex.getMessage().contains("fixture-key"));}
    }
    private static class Fixture implements QqMusicApi.Transport {
        final ArrayDeque<JSONObject> data=new ArrayDeque<>();final List<QqMusicApi.Request> requests=new ArrayList<>();
        final QqMusicApi api=new QqMusicApi(this);
        public QqMusicApi.Response execute(QqMusicApi.Request request) throws IOException {
            requests.add(request);
            try{return new QqMusicApi.Response(200,new JSONObject().put("code",0)
                .put("req_0",new JSONObject().put("code",0).put("data",data.remove()))
                .toString().getBytes(StandardCharsets.UTF_8),Collections.emptyMap());}
            catch(JSONException e){throw new IOException();}
        }
        JSONObject param(int n) throws Exception {
            return new JSONObject(requests.get(n).body).getJSONObject("req_0").getJSONObject("param");
        }
    }
    private static JSONObject song() throws Exception {
        return json("{\"mid\":\""+MID+"\",\"name\":\"Song\",\"interval\":123,\"type\":0,"
            +"\"singer\":[{\"name\":\"Artist\"}],\"album\":{\"name\":\"Album\"},"
            +"\"file\":{\"media_mid\":\"001abcDEF01234001abcDEF01234\"}}");
    }
}
