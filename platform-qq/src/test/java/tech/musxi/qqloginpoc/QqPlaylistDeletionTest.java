package tech.musxi.qqloginpoc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Offline protocol coverage for deleting one whole self-created QQ playlist. The pinned adapter
 * (QQMusicApi 0.8.1, commit 3fc57f02e4eddb68a069bba4e0e12a7893f2a814, modules/songlist.py) exposes
 * songlist.delete as music.musicasset.PlaylistBaseWrite / DelPlaylist with exactly {dirId};
 * DelSonglist belongs to PlaylistDetailWrite and only removes songs, so it is never a directory
 * delete. No live account, network write or device is involved here. */
public class QqPlaylistDeletionTest {
    private static final String CREATE = "music.musicasset.PlaylistBaseWrite";

    @Test public void ordinaryDirectoryIsDeletedWithTheSinglePinnedCall() throws Exception {
        for(int dir:new int[]{1,2}){
            Fixture f=new Fixture();
            f.data.add(snapshot("{\"tid\":9,\"dirId\":"+dir+",\"dirName\":\"Mine\",\"songNum\":3,\"invalid\":0}"));
            QqLibraryApi.Playlist target=new QqLibraryApi(f.api).deletablePlaylist(session(),"9_"+dir);
            assertNotNull(target);
            assertEquals("9_"+dir,target.id);
            assertEquals("Mine",target.name);
            assertEquals(3,target.trackCount);
            assertEquals(QqLibraryApi.Playlist.Kind.CREATED,target.kind);
            assertTrue(target.writable);
            assertEquals(1,f.requests.size());

            Fixture delete=new Fixture();
            delete.data.add(snapshot("{\"tid\":9,\"dirId\":"+dir+",\"dirName\":\"Mine\",\"songNum\":3,\"invalid\":0}"));
            delete.data.add(new JSONObject().put("retCode",0));
            new QqLibraryApi(delete.api).deletePlaylist(session(),"9_"+dir);
            assertEquals(2,delete.requests.size());
            JSONObject operation=delete.operation(1);
            assertEquals(CREATE,operation.getString("module"));
            assertEquals("DelPlaylist",operation.getString("method"));
            JSONObject param=delete.param(1);
            assertEquals(1,param.length());
            assertEquals(dir,param.getInt("dirId"));
            assertEquals(0,delete.count("DelSonglist"));
            assertTrue(delete.count("DelPlaylist")==1);
            // The delete request reuses the bounded authenticated CGI envelope and never leaks the
            // music key outside its own comm/Cookie fields.
            JSONObject comm=new JSONObject(delete.requests.get(1).body).getJSONObject("comm");
            assertEquals("12345",comm.getString("uin"));
            assertEquals("fixture-key",comm.getString("authst"));
            assertTrue(delete.requests.get(1).headers.get("Cookie").contains("qm_keyst=fixture-key"));
            assertEquals("https://u.y.qq.com/cgi-bin/musicu.fcg",delete.requests.get(1).endpoint);
        }
    }

    @Test public void everythingExceptAnOrdinaryOwnedRowIsRefusedWithoutWriting() throws Exception {
        // The liked alias, directory 0 and the 201..209 account system directories are rejected by
        // the id guard before any transport call.
        Set<String> fixed=new LinkedHashSet<>(Arrays.asList(
            "0_201","9_0","9_201","9_202","9_203","9_204","9_205","9_206","9_207","9_208","9_209"));
        for(String id:fixed){
            Fixture f=new Fixture();
            rejects("permission",()->new QqLibraryApi(f.api).deletablePlaylist(session(),id));
            rejects("permission",()->new QqLibraryApi(f.api).deletePlaylist(session(),id));
            assertTrue(id,f.requests.isEmpty());
        }
        // Malformed ids are refused as identity errors, still without a request.
        Map<String,String> malformed=new LinkedHashMap<>();
        malformed.put("9","identity");malformed.put("9_1_2","identity");malformed.put("_1","identity");
        malformed.put("abc_1","identity");malformed.put("9_-1","protocol");malformed.put("09_1","identity");
        malformed.put("9_1000001","limit");
        for(Map.Entry<String,String> bad:malformed.entrySet()){
            Fixture f=new Fixture();
            rejects(bad.getValue(),()->new QqLibraryApi(f.api).deletePlaylist(session(),bad.getKey()));
            assertTrue(bad.getKey(),f.requests.isEmpty());
        }
    }

    @Test public void followedPlaylistOfAnotherAccountIsAProtectedTarget() throws Exception {
        // Not in this account's created snapshot, but present in the complete followed enumeration,
        // so the id names a live list somebody else owns: permission, never "absent".
        Fixture f=new Fixture();
        f.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0}"));
        f.data.add(favorites("{\"tid\":77,\"dirid\":5,\"title\":\"Other Public\"}"));
        rejects("permission",()->new QqLibraryApi(f.api).deletePlaylist(session(),"77_5"));
        assertEquals(2,f.requests.size());
        assertEquals(0,f.count("DelPlaylist"));
        assertEquals("CgiGetPlaylistFavInfo",f.operation(1).getString("method"));
        assertEquals("fixture-euin",f.param(1).getString("uin"));

        // The same protection applies to a followed list the read reports under another directory.
        Fixture drifted=new Fixture();
        drifted.data.add(snapshot(""));
        drifted.data.add(favorites("{\"tid\":99,\"dirid\":5,\"title\":\"Other Public\"}"));
        rejects("permission",()->new QqLibraryApi(drifted.api).deletablePlaylist(session(),"99_4"));
        assertEquals(0,drifted.count("DelPlaylist"));
    }

    @Test public void idAbsentFromTheCompleteSnapshotIsNullAndNeverWrites() throws Exception {
        // The full GetPlaylistByUin enumeration is authoritative; a tid that only exists in a part of
        // the library must not be confused with a writable subset. Both complete enumerations are
        // consulted, then the id is reported as not deletable without any write.
        Fixture f=new Fixture();
        f.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0},"
            +"{\"tid\":11,\"dirId\":201,\"dirName\":\"Liked echo\",\"songNum\":4,\"invalid\":0}"));
        f.data.add(favorites());
        assertNull(new QqLibraryApi(f.api).deletablePlaylist(session(),"99_4"));
        assertEquals(2,f.requests.size());
        assertEquals(0,f.count("DelPlaylist"));

        Fixture delete=new Fixture();
        delete.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0}"));
        delete.data.add(favorites());
        new QqLibraryApi(delete.api).deletePlaylist(session(),"99_4");
        assertEquals(2,delete.requests.size());
        assertEquals(0,delete.count("DelPlaylist"));

        // A row the account snapshot reports but marks invalid is still this account's data the
        // server refused to expose: protected, not "absent", and no second enumeration is needed.
        Fixture hidden=new Fixture();
        hidden.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Hidden\",\"songNum\":0,\"invalid\":1}"));
        rejects("permission",()->new QqLibraryApi(hidden.api).deletablePlaylist(session(),"9_1"));
        assertEquals(0,hidden.count("DelPlaylist"));
        assertEquals(1,hidden.requests.size());

        // An unusable followed enumeration fails closed instead of reporting "absent".
        Fixture broken=new Fixture();
        broken.data.add(snapshot(""));
        broken.data.add(new JSONObject().put("total",0).put("hasmore",0).put("v_failTids",new JSONArray().put(77)));
        rejects("incomplete",()->new QqLibraryApi(broken.api).deletePlaylist(session(),"99_4"));
        assertEquals(0,broken.count("DelPlaylist"));
    }

    @Test public void twoTidsOnOneDirectoryAreAnIdentityConflict() throws Exception {
        Fixture f=new Fixture();
        f.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0},"
            +"{\"tid\":10,\"dirId\":1,\"dirName\":\"Other\",\"songNum\":0,\"invalid\":0}"));
        rejects("identity",()->new QqLibraryApi(f.api).deletablePlaylist(session(),"9_1"));
        assertEquals(1,f.requests.size());
        Fixture delete=new Fixture();
        delete.data.add(snapshot("{\"tid\":9,\"dirId\":2,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0},"
            +"{\"tid\":10,\"dirId\":2,\"dirName\":\"Other\",\"songNum\":0,\"invalid\":0}"));
        rejects("identity",()->new QqLibraryApi(delete.api).deletePlaylist(session(),"9_2"));
        assertEquals(0,delete.count("DelPlaylist"));
    }

    @Test public void incompletelyEnumeratedOwnershipNeverBecomesADelete() throws Exception {
        for(JSONObject page:new JSONObject[]{new JSONObject().put("v_playlist",new JSONArray()),
            new JSONObject().put("bFinish",0).put("v_playlist",new JSONArray()),
            new JSONObject().put("bFinish",1)}){
            Fixture f=new Fixture();f.data.add(page);
            rejects(page.has("v_playlist")&&"0".equals(String.valueOf(page.opt("bFinish")))?"incomplete":"protocol",
                ()->new QqLibraryApi(f.api).deletePlaylist(session(),"9_1"));
            assertEquals(0,f.count("DelPlaylist"));
        }
    }

    @Test public void lostWriteResponseIsNeverRetriedAndNothingIsReadBack() throws Exception {
        Fixture f=new Fixture();
        f.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0}"));
        f.data.add(new IOException("private transport detail"));
        rejects("network",()->new QqLibraryApi(f.api).deletePlaylist(session(),"9_1"));
        assertEquals(2,f.requests.size());
        assertEquals(1,f.count("DelPlaylist"));

        // A rejected write is one request and one fixed category; the caller reads back afterwards.
        Fixture denied=new Fixture();
        denied.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0}"));
        denied.data.add(new JSONObject().put("retCode",80092));
        rejects("permission",()->new QqLibraryApi(denied.api).deletePlaylist(session(),"9_1"));
        assertEquals(1,denied.count("DelPlaylist"));
        assertEquals(2,denied.requests.size());
        Fixture noEcho=new Fixture();
        noEcho.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0}"));
        noEcho.data.add(new JSONObject());
        rejects("protocol",()->new QqLibraryApi(noEcho.api).deletePlaylist(session(),"9_1"));
        assertEquals(1,noEcho.count("DelPlaylist"));
    }

    @Test public void accountChangeAndMissingSessionStopBeforeAnyRequest() throws Exception {
        Fixture f=new Fixture();
        f.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0}"));
        rejects("account_changed",()->new QqLibraryApi(f.api,()->false).deletePlaylist(session(),"9_1"));
        assertTrue(f.requests.isEmpty());
        Fixture guard=new Fixture();
        guard.data.add(snapshot("{\"tid\":9,\"dirId\":1,\"dirName\":\"Mine\",\"songNum\":0,\"invalid\":0}"));
        rejects("account_changed",()->new QqLibraryApi(guard.api,()->guard.requests.size()<1).deletePlaylist(session(),"9_1"));
        assertEquals(0,guard.count("DelPlaylist"));
        Fixture none=new Fixture();
        rejects("identity",()->new QqLibraryApi(none.api).deletePlaylist(null,"9_1"));
        rejects("identity",()->new QqLibraryApi(none.api).deletablePlaylist(null,"9_1"));
        assertTrue(none.requests.isEmpty());
    }

    @Test public void onlyTheTypedDeleteEntryPointCanReachTheSingleDirectoryDeleteShape() throws Exception {
        Fixture f=new Fixture();
        for(int dir:new int[]{1,2,1_000_000}){
            f.data.add(new JSONObject().put("retCode",0));
            JSONObject result=f.api.deletePlaylist(session(),dir);
            assertEquals(0,result.getInt("retCode"));
            JSONObject operation=f.operation(f.requests.size()-1);
            assertEquals(CREATE,operation.getString("module"));
            assertEquals("DelPlaylist",operation.getString("method"));
            JSONObject param=f.param(f.requests.size()-1);
            assertEquals(1,param.length());
            assertEquals(dir,param.getInt("dirId"));
        }
        assertEquals(3,f.requests.size());
        int before=f.requests.size();
        for(int bad:new int[]{0,-1,201,205,206,209,1_000_001}){
            rejects("endpoint",()->f.api.deletePlaylist(session(),bad));
        }
        rejects("endpoint",()->f.api.deletePlaylist(null,1));
        assertEquals(before,f.requests.size());
        // The generic whitelist keeps refusing DelPlaylist, so no hand-built payload and no
        // DelSonglist song-removal shape can stand in for deleting a directory.
        rejects("endpoint",()->f.api.library(session(),CREATE,"DelPlaylist",new JSONObject().put("dirId",1)));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelSonglist",
            new JSONObject().put("dirId",1)));
        rejects("endpoint",()->f.api.library(session(),CREATE,"DelSonglist",new JSONObject().put("dirId",1)));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistDetailWrite","DelPlaylist",
            new JSONObject().put("dirId",1)));
        assertEquals(before,f.requests.size());
    }

    private static JSONObject snapshot(String rows) throws Exception {
        return new JSONObject().put("bFinish",1).put("v_playlist",new JSONArray("["+rows+"]"));
    }
    /** Complete followed-playlist enumeration; an empty row set is a valid complete answer. */
    private static JSONObject favorites(String... rows) throws Exception {
        JSONArray list=new JSONArray();
        for(String row:rows){
            JSONObject item=new JSONObject(row);
            if(!item.has("songnum"))item.put("songnum",0);
            list.put(item);
        }
        return new JSONObject().put("hide",0).put("total",list.length()).put("hasmore",0).put("v_list",list);
    }
    private static QqMusicApi.Session session() throws Exception {
        return QqMusicApi.Session.fromJson(new JSONObject(
            "{\"credential\":{\"musicid\":12345,\"musickey\":\"fixture-key\",\"encryptUin\":\"fixture-euin\",\"loginType\":2},\"nickname\":\"Fixture\"}"));
    }
    private interface Checked {void run() throws Exception;}
    private static void rejects(String code,Checked work) throws Exception {
        try{work.run();fail("Expected fixed failure");}
        catch(QqMusicApi.ApiException ex){assertEquals(code,ex.code);assertFalse(ex.getMessage().contains("fixture-key"));}
    }
    private static final class Fixture implements QqMusicApi.Transport {
        final ArrayDeque<Object> data=new ArrayDeque<>();
        final List<QqMusicApi.Request> requests=new ArrayList<>();
        final QqMusicApi api=new QqMusicApi(this);
        public QqMusicApi.Response execute(QqMusicApi.Request request) throws IOException {
            requests.add(request);
            Object next=data.remove();
            if(next instanceof IOException)throw (IOException)next;
            try {
                return new QqMusicApi.Response(200,new JSONObject().put("code",0).put("req_0",new JSONObject()
                    .put("code",0).put("data",next)).toString().getBytes(StandardCharsets.UTF_8),Collections.emptyMap());
            } catch(JSONException|NoSuchElementException invalid) { throw new IOException("Invalid test fixture",invalid); }
        }
        JSONObject operation(int index) throws Exception {return new JSONObject(requests.get(index).body).getJSONObject("req_0");}
        JSONObject param(int index) throws Exception {return operation(index).getJSONObject("param");}
        int count(String method) throws Exception {
            int found=0;
            for(int i=0;i<requests.size();i++)if(operation(i).getString("method").equals(method))found++;
            return found;
        }
    }
}
