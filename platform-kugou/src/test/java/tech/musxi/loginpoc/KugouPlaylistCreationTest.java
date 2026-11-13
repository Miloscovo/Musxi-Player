package tech.musxi.loginpoc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static org.junit.Assert.*;

/**
 * Creation of a brand new ordinary cloud list.
 *
 * The device probe (2026-11) returned root status success / error_code 0 with a numeric
 * data.info.listid, and data.info.list_create_listid echoed the same value. That ID was later
 * found in the same account's fresh list snapshot, so the response ID is the only identity the
 * caller may trust: root.listid, data.listid, a GID or a same-name neighbour is never a substitute.
 */
public class KugouPlaylistCreationTest {
    private static final String TOKEN="fixture-token";
    private static final KugouApi.Session SESSION=new KugouApi.Session(TOKEN,"123","Fixture");
    private static final long NEW_ID=900001L;
    private static final String CREATE="/cloudlist.service/v5/add_list";
    private static final String LIST="/v7/get_all_list";

    @Test public void creationReturnsOnlyTheNewIdConfirmedByAFreshListRead() throws Exception {
        Account account=new Account().with("11","通勤",3).with("12","新歌单",0);
        KugouLibraryApi.Playlist created=new KugouLibraryApi(new KugouApi(device(),account))
            .createPlaylist(SESSION,"  新歌单  ",()->true);

        assertEquals("900001",created.id);
        assertEquals("新歌单",created.name);
        assertEquals(0,created.trackCount);
        assertTrue(created.editable);
        assertFalse(created.readOnly);
        assertFalse(created.favorite);
        assertFalse(created.systemDefault);
        assertEquals(KugouLibraryApi.Playlist.Kind.CREATED,created.kind);
        assertEquals(1,posts(account.calls));
        assertTrue("a complete pre-write read comes first",
            account.calls.get(0).endpoint.endsWith(LIST));
        assertTrue("success is only reported after a post-write list read",
            account.calls.get(account.calls.size()-1).endpoint.endsWith(LIST));
        assertEquals("新歌单",payload(onlyPost(account.calls)).getString("name"));
    }

    @Test public void readBackUsesTheSameDisplaySanitizationForEmojiNames() throws Exception {
        Account account=new Account();
        String name="家庭👨\u200D👩\u200D👧";
        KugouLibraryApi.Playlist created=new KugouLibraryApi(new KugouApi(device(),account))
            .createPlaylist(SESSION,name,()->true);
        assertEquals(NEW_ID+"",created.id);
        assertEquals("家庭👨👩👧",created.name);
        assertEquals(name,payload(onlyPost(account.calls)).getString("name"));
        assertEquals(1,posts(account.calls));
    }

    @Test public void infoStatusIsAResourceFieldNotAnOperationCode() throws Exception {
        // data.info is the playlist resource; the probe never recorded its status as a write outcome.
        Account account=new Account();
        account.addResponse=json("{status:1,error_code:0,data:{info:{status:0,listid:900001}}}");
        KugouLibraryApi.Playlist created=new KugouLibraryApi(new KugouApi(device(),account))
            .createPlaylist(SESSION,"新歌单",()->true);
        assertEquals(NEW_ID+"",created.id);
        assertEquals("新歌单",created.name);
        assertEquals(1,posts(account.calls));
    }

    @Test public void sameNameNeighbourIsNeverAdoptedAsTheCreatedId() throws Exception {
        // The account already owns an empty list with exactly the requested name.
        Account account=new Account().with("11","新歌单",0);
        KugouLibraryApi.Playlist created=new KugouLibraryApi(new KugouApi(device(),account))
            .createPlaylist(SESSION,"新歌单",()->true);

        assertEquals(NEW_ID+"",created.id);
        assertNotEquals("the pre-existing same-name list must not be returned","11",created.id);
        assertEquals("新歌单",created.name);
        assertEquals("the duplicate-name list stays untouched",2,account.rows.size());
        assertEquals(1,posts(account.calls));
    }

    @Test public void creationSendsOneFixedAddListPostWithAndroidSignature() throws Exception {
        Account account=new Account();
        new KugouLibraryApi(new KugouApi(device(),account)).createPlaylist(SESSION,"  歌单  ",()->true);
        KugouApi.Request post=onlyPost(account.calls);

        assertEquals("https://gateway.kugou.com"+CREATE,post.endpoint);
        KugouApi.requireEndpoint(new URL(post.endpoint));
        assertFalse("add_list rejects the gateway router override",post.headers.containsKey("x-router"));
        assertNull("the create body travels as plain signed JSON",post.wireBody);
        assertEquals("123",post.params.get("userid"));
        assertEquals(TOKEN,post.params.get("token"));
        assertEquals("gztx",post.params.get("last_area"));
        assertTrue(post.params.get("last_time"),post.params.get("last_time").matches("[0-9]{1,12}"));
        Map<String,String> signed=new LinkedHashMap<>(post.params);
        String signature=signed.remove("signature");
        assertEquals(KugouApi.androidSignature(signed,post.body),signature);

        JSONObject body=payload(post);
        assertEquals(0,body.getInt("total_ver"));
        assertEquals("歌单",body.getString("name"));
        assertEquals(0,body.getInt("type"));
        assertEquals(1,body.getInt("source"));
        assertEquals(0,body.getInt("is_pri"));
        assertEquals("",body.getString("list_create_gid"));
        assertEquals(0,body.getInt("from_shupinmv"));
        assertEquals("123",String.valueOf(body.get("userid")));
    }

    @Test public void invalidNamesAreRejectedBeforeAnyTransport() throws Exception {
        Account account=new Account();
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));
        List<String> rejected=new ArrayList<>();
        rejected.add(null);
        rejected.add("");
        rejected.add("   ");
        rejected.add("\u0000");
        rejected.add("a\u0007b");
        rejected.add("\uD800");
        rejected.add("ok\uDC00");
        rejected.add("字".repeat(81));
        rejected.add("😀".repeat(81));
        for(String name:rejected)
            rejects("request",()->library.createPlaylist(SESSION,name,()->true));
        assertTrue("no malformed name may reach the transport",account.calls.isEmpty());

        // 80 code points is the inclusive ceiling, including surrogate pairs.
        String longest="字".repeat(80);
        KugouLibraryApi.Playlist created=library.createPlaylist(SESSION,"  "+longest+"  ",()->true);
        assertEquals(NEW_ID+"",created.id);
        assertEquals(longest,payload(onlyPost(account.calls)).getString("name"));
    }

    @Test public void responseIdentityMustBeANumberInsideDataInfoOnly() throws Exception {
        Object[][] cases={
            {json("{status:1,error_code:0,data:{info:{status:1}}}"),"identity","missing listid"},
            {json("{status:1,error_code:0,listid:900001,data:{info:{status:1}}}"),"identity","root listid"},
            {json("{status:1,error_code:0,data:{listid:900001,info:{status:1}}}"),"identity","data listid"},
            {json("{status:1,error_code:0,data:{info:{listid:0}}}"),"identity","zero listid"},
            {json("{status:1,error_code:0,data:{info:{listid:-5}}}"),"identity","negative listid"},
            {json("{status:1,error_code:0,data:{info:{listid:900001.5}}}"),"identity","fractional listid"},
            {json("{status:1,error_code:0,data:{info:{listid:'900001a'}}}"),"identity","non numeric listid"},
            {json("{status:1,error_code:0,data:{info:{listid:9007199254740992}}}"),"identity","listid over 2^53"},
            {json("{status:1,error_code:0,data:{info:{listid:900001,list_create_listid:900002}}}"),
                "identity","conflicting create id"},
            {json("{status:1,error_code:0,data:{info:{listid:900001,list_create_listid:'abc'}}}"),
                "identity","malformed create id"},
            {json("{status:1,error_code:0,data:{}}"),"protocol","missing info"},
            {json("{status:1,error_code:0}"),"protocol","missing data"}
        };
        for(Object[] fixture:cases) {
            Account account=new Account();
            account.addResponse=(JSONObject)fixture[0];
            KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));
            rejects((String)fixture[1],()->library.createPlaylist(SESSION,"新歌单",()->true));
            assertEquals("exactly one POST for "+fixture[2],1,posts(account.calls));
        }
    }

    @Test public void responseIdThatAlreadyExistedIsRejectedAsIdentity() throws Exception {
        Account account=new Account().with("900001","老歌单",2);
        account.appendOnCreate=false;
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));
        rejects("identity",()->library.createPlaylist(SESSION,"新歌单",()->true));
        assertEquals("a rejected identity is never retried",1,posts(account.calls));
    }

    @Test public void readBackMustProveAnEditableOrdinaryListWithTheSameTitle() throws Exception {
        Map<String,java.util.function.Function<String,JSONObject>> shapes=new LinkedHashMap<>();
        shapes.put("read only",name->Account.row(NEW_ID+"",name,"0","0",0));
        shapes.put("system default",name->Account.row(NEW_ID+"",name,"1","1",0));
        shapes.put("favorite",name->Account.row(NEW_ID+"",name,"1","2",0));
        for(Map.Entry<String,java.util.function.Function<String,JSONObject>> shape:shapes.entrySet()) {
            Account account=new Account();
            account.createdShape=shape.getValue();
            KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));
            rejects("permission",()->library.createPlaylist(SESSION,"新歌单",()->true));
            assertEquals("exactly one POST for "+shape.getKey(),1,posts(account.calls));
        }
        Account renamed=new Account();
        renamed.createdShape=name->Account.row(NEW_ID+"","别的名字","1","0",0);
        KugouLibraryApi mismatched=new KugouLibraryApi(new KugouApi(device(),renamed));
        rejects("not_confirmed",()->mismatched.createPlaylist(SESSION,"新歌单",()->true));
        assertEquals(1,posts(renamed.calls));

        Account missing=new Account();
        missing.appendOnCreate=false;
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),missing));
        rejects("not_confirmed",()->library.createPlaylist(SESSION,"新歌单",()->true));
        assertEquals(1,posts(missing.calls));
    }

    @Test public void accountChangeStopsTheFlowWithoutASecondPost() throws Exception {
        Account cancelled=new Account();
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),cancelled));
        rejects("account_changed",()->library.createPlaylist(SESSION,"新歌单",()->false));
        assertEquals("nothing is written for a stale account",0,posts(cancelled.calls));

        Account switched=new Account();
        KugouLibraryApi writing=new KugouLibraryApi(new KugouApi(device(),switched));
        rejects("account_changed",
            ()->writing.createPlaylist(SESSION,"新歌单",()->posts(switched.calls)==0));
        assertEquals("an account switch after the write never repeats it",1,posts(switched.calls));
    }

    @Test public void lostWriteResponseIsReportedOnceAndNeverResent() throws Exception {
        Account account=new Account();
        account.addFailure=new IOException("fixture socket closed");
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));
        try {
            library.createPlaylist(SESSION,"新歌单",()->true);
            fail("Expected the lost response to surface");
        } catch(IOException expected) { assertNotNull(expected); }
        assertEquals("a lost response is not retried",1,posts(account.calls));
    }

    @Test public void unreadableReadBackPagesNeverConfirmOrRepeatTheWrite() throws Exception {
        Account shortRead=new Account();
        shortRead.phantomRowsAfterWrite=1;
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),shortRead));
        rejects("incomplete",()->library.createPlaylist(SESSION,"新歌单",()->true));
        assertEquals(1,posts(shortRead.calls));

        Account repeating=new Account();
        for(int i=1;i<=150;i++)repeating.with(String.valueOf(1000+i),"L"+i,0);
        repeating.repeatAfterWrite=1;
        KugouLibraryApi second=new KugouLibraryApi(new KugouApi(device(),repeating));
        rejects("incomplete",()->second.createPlaylist(SESSION,"新歌单",()->true));
        assertEquals("a duplicated read-back page never triggers a second write",1,posts(repeating.calls));
    }

    @Test public void unreadablePreWriteListSendsNoCreateRequest() throws Exception {
        Account platformFailure=new Account();
        platformFailure.listResponse=json("{status:0,error_code:1,data:{}}");
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),platformFailure));
        rejects("platform",()->library.createPlaylist(SESSION,"新歌单",()->true));
        assertTrue("no write without a complete pre-write list",platformFailure.calls.stream()
            .noneMatch(r->r.endpoint.endsWith("/add_list")));

        Account drifting=new Account();
        drifting.phantomRowsBeforeWrite=1;
        KugouLibraryApi second=new KugouLibraryApi(new KugouApi(device(),drifting));
        rejects("incomplete",()->second.createPlaylist(SESSION,"新歌单",()->true));
        assertEquals(0,posts(drifting.calls));
    }

    @Test public void platformRejectionNeverConfirmsCreation() throws Exception {
        Account rejected=new Account();
        rejected.addResponse=json("{status:1,error_code:2,data:{info:{listid:900001}}}");
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),rejected));
        rejects("platform",()->library.createPlaylist(SESSION,"新歌单",()->true));
        assertEquals(1,posts(rejected.calls));

        Account refused=new Account();
        refused.addResponse=json("{status:0,data:{}}");
        KugouLibraryApi second=new KugouLibraryApi(new KugouApi(device(),refused));
        rejects("platform",()->second.createPlaylist(SESSION,"新歌单",()->true));
        assertEquals(1,posts(refused.calls));
    }

    /** Fake account library; every list page is served from [rows] in server order. */
    private static final class Account implements KugouApi.Transport {
        final List<KugouApi.Request> calls=new ArrayList<>();
        final List<JSONObject> rows=new ArrayList<>();
        JSONObject addResponse=writeOk(NEW_ID,true);
        JSONObject listResponse;
        IOException addFailure;
        int phantomRowsBeforeWrite;
        int phantomRowsAfterWrite;
        int repeatAfterWrite;
        boolean appendOnCreate=true;
        java.util.function.Function<String,JSONObject> createdShape=
            name->row(NEW_ID+"",name,"1","0",0);

        Account with(String id,String name,int count) {
            rows.add(row(id,name,"1","0",count));
            return this;
        }

        static JSONObject row(String id,String name,String isEdit,String isDef,int count) {
            try {
                return new JSONObject().put("listid",id).put("name",name).put("is_edit",isEdit)
                    .put("is_def",isDef).put("count",count);
            } catch(JSONException invalidFixture) { throw new AssertionError(invalidFixture); }
        }

        @Override public JSONObject execute(KugouApi.Request request) throws IOException {
            calls.add(request);
            try {
                if(request.endpoint.endsWith(LIST))return listPage(request);
                if(request.endpoint.endsWith(CREATE))return createResult(request);
            } catch(JSONException invalidFixture) { throw new AssertionError(invalidFixture); }
            throw new AssertionError("unexpected endpoint: "+request.endpoint);
        }

        private int writes() { return posts(calls); }

        private JSONObject listPage(KugouApi.Request request) throws JSONException {
            if(listResponse!=null)return new JSONObject(listResponse.toString());
            JSONObject body=new JSONObject(request.body);
            int page=body.getInt("page"), size=body.getInt("pagesize");
            boolean wrote=writes()>0;
            if(wrote && repeatAfterWrite>0 && page>=repeatAfterWrite)page=repeatAfterWrite;
            int from=Math.min((page-1)*size,rows.size()), to=Math.min(from+size,rows.size());
            JSONArray info=new JSONArray();
            for(int i=from;i<to;i++)info.put(rows.get(i));
            int total=rows.size()+(wrote?phantomRowsAfterWrite:phantomRowsBeforeWrite);
            return new JSONObject().put("status",1)
                .put("data",new JSONObject().put("total",total).put("info",info));
        }

        private JSONObject createResult(KugouApi.Request request) throws IOException,JSONException {
            if(addFailure!=null)throw addFailure;
            String name=new JSONObject(request.body).optString("name","");
            if(appendOnCreate)rows.add(createdShape.apply(name));
            return new JSONObject(addResponse.toString());
        }
    }

    private static JSONObject writeOk(long listid,boolean echoCreateId) {
        try {
            JSONObject info=new JSONObject().put("status",1).put("listid",listid);
            if(echoCreateId)info.put("list_create_listid",listid);
            return new JSONObject().put("status",1).put("error_code",0)
                .put("data",new JSONObject().put("info",info));
        } catch(org.json.JSONException impossible) { throw new AssertionError(impossible); }
    }

    private static KugouApi.Device device() { return new KugouApi.Device("b".repeat(32)); }

    private static JSONObject json(String text) {
        try { return new JSONObject(text); }
        catch(org.json.JSONException malformed) { throw new AssertionError(malformed); }
    }

    private static JSONObject payload(KugouApi.Request post) throws Exception {
        assertNotNull("the create request must carry a JSON body",post.body);
        return new JSONObject(post.body);
    }

    private static int posts(List<KugouApi.Request> calls) {
        int count=0;
        for(KugouApi.Request request:calls)if(request.endpoint.endsWith("/add_list"))count++;
        return count;
    }

    private static KugouApi.Request onlyPost(List<KugouApi.Request> calls) {
        List<KugouApi.Request> matches=new ArrayList<>();
        for(KugouApi.Request request:calls)if(request.endpoint.endsWith(CREATE))matches.add(request);
        assertEquals("expected exactly one add_list call: "+calls.size()+" calls",1,matches.size());
        return matches.get(0);
    }

    private interface Action { void run() throws Exception; }

    private static void rejects(String code,Action action) throws Exception {
        try {action.run();fail("Expected fixed failure "+code);}
        catch(KugouApi.ApiException failure) {
            assertEquals(code,failure.code);
            assertNull(failure.getCause());
        }
    }

}
