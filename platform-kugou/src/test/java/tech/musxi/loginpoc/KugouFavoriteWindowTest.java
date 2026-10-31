package tech.musxi.loginpoc;

import java.io.IOException;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

public class KugouFavoriteWindowTest {
    private static final class Server {
        final List<JSONObject> rows=new ArrayList<>();
        final List<KugouApi.Request> calls=new ArrayList<>();
        boolean failRead;
        Server(int count) throws Exception {
            for(int i=1;i<=count;i++)rows.add(row(i));
        }
        static JSONObject row(int i) throws JSONException {
            return new JSONObject().put("hash",String.format("%032x",i)).put("album_id",1)
                .put("album_audio_id",i).put("fileid",i).put("name","Song "+i);
        }
        JSONObject execute(KugouApi.Request request) throws IOException {
            calls.add(request);
            try {
                JSONObject body=request.body==null?null:new JSONObject(request.body);
                if(request.endpoint.endsWith("/get_all_list"))return new JSONObject("{status:1,data:{total:1,info:[{listid:13,is_edit:1,is_def:2}]}}");
                if(request.endpoint.endsWith("/delete_songs")) {
                    long id=body.getJSONArray("data").getJSONObject(0).getLong("fileid");
                    for(int i=rows.size()-1;i>=0;i--)if(rows.get(i).getLong("fileid")==id)rows.remove(i);
                    return new JSONObject("{status:1}");
                }
                if(request.endpoint.endsWith("/add_song")) {
                    JSONObject item=body.getJSONArray("data").getJSONObject(0);
                    rows.add(new JSONObject(item.toString()).put("album_audio_id",item.get("mixsongid")).put("fileid",9001));
                    return new JSONObject("{status:1}");
                }
                if(!request.endpoint.endsWith("/get_list_all_file"))throw new AssertionError("Unexpected endpoint");
                if(failRead)throw new IOException("offline");
                int limit=body.getInt("pagesize"),offset=(body.getInt("page")-1)*limit;
                JSONArray selected=new JSONArray();
                for(int i=offset;i<Math.min(offset+limit,rows.size());i++)selected.put(rows.get(i));
                return new JSONObject().put("status",1).put("data",new JSONObject().put("total",rows.size()).put("info",selected));
            } catch(JSONException malformed) { throw new IOException(malformed); }
        }
        KugouLibraryApi library() { return new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),this::execute)); }
    }
    private KugouApi.Session session() { return new KugouApi.Session("fixture-token","123","Fixture"); }
    private String key(int i) { return String.format("%032x",i)+"_1_"+i; }

    @Test public void threeRemovalsFrom758UseOnlyOneWriteAndOneWindowEach() throws Exception {
        Server server=new Server(758);KugouLibraryApi library=server.library();KugouApi.Session session=session();
        assertEquals(758,library.likedIds(session,()->true).size());
        server.calls.clear();
        for(int i:new int[]{10,120,758}) {
            library.setLiked(session,key(i),"Song "+i,1000,false,()->true);
            assertFalse(library.verifyLiked(session,key(i),false,()->true));
        }
        assertEquals(6,server.calls.size());assertEquals(755,server.rows.size());
        for(int i=0;i<6;i+=2) {
            assertTrue(server.calls.get(i).endpoint.endsWith("/delete_songs"));
            assertTrue(server.calls.get(i+1).endpoint.endsWith("/get_list_all_file"));
            assertEquals(100,new JSONObject(server.calls.get(i+1).body).getInt("pagesize"));
        }
    }
    @Test public void addVerificationLearnsFileIdForFollowingRemoval() throws Exception {
        Server server=new Server(100);KugouLibraryApi library=server.library();KugouApi.Session session=session();
        library.likedIds(session,()->true);server.calls.clear();
        library.setLiked(session,key(101),"Song 101",1000,true,()->true);
        assertTrue(library.verifyLiked(session,key(101),true,()->true));
        library.setLiked(session,key(101),"Song 101",1000,false,()->true);
        assertEquals(9001,new JSONObject(server.calls.get(2).body).getJSONArray("data").getJSONObject(0).getLong("fileid"));
        assertFalse(library.verifyLiked(session,key(101),false,()->true));
        assertEquals(4,server.calls.size());
    }
    @Test public void divergentWindowNetworkFailureAndOtherSessionsNeverVerify() throws Exception {
        Server server=new Server(150);KugouLibraryApi library=server.library();KugouApi.Session session=session();
        library.likedIds(session,()->true);
        library.setLiked(session,key(10),"Song 10",1000,false,()->true);
        Collections.swap(server.rows,0,1);
        try { library.verifyLiked(session,key(10),false,()->true);fail("Changed order must reconcile"); }
        catch(KugouApi.ApiException expected) { assertEquals("incomplete",expected.code); }
        library.likedIds(session,()->true);
        library.setLiked(session,key(11),"Song 11",1000,false,()->true);server.failRead=true;
        try { library.verifyLiked(session,key(11),false,()->true);fail("Network is not absence"); }
        catch(IOException expected) { }
        try { library.verifyLiked(session(),key(11),false,()->true);fail("Another session must not reuse proof"); }
        catch(KugouApi.ApiException expected) { assertEquals("incomplete",expected.code); }
    }
}
