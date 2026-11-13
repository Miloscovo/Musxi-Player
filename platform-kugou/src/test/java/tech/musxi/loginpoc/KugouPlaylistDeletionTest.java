package tech.musxi.loginpoc;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Deletion of an own ordinary cloud list.
 *
 * Fixed playlist_del.js profile (KuGouMusicApi 7ac64e1), confirmed on an ordinary non-empty
 * owned playlist by the 2026-11-13 device probe. Protected, stale or incompletely listed targets
 * must never reach the mutation; a permitted target sends one Base64-text request, never retries.
 */
public class KugouPlaylistDeletionTest {
    private static final String TOKEN="fixture-token";
    private static final KugouApi.Session SESSION=new KugouApi.Session(TOKEN,"123","Fixture");
    private static final String LIST="/v7/get_all_list";
    private static final String DELETE="/v2/delete_list";

    @Test public void canRenameNeedsEditMetadataNotJustAnEditableFlag() throws Exception {
        Account account=new Account()
            .withMetaShape("41","有元数据","1","0",12,"通勤","每周更新")
            .with("42","无元数据",0);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        assertTrue(library.deletablePlaylist(SESSION,"41",()->true).canRename());
        assertFalse("is_edit=1 alone is not rename metadata",
            library.deletablePlaylist(SESSION,"42",()->true).canRename());
    }

    @Test public void canRenameIsFalseForProtectedOrCollectedRows() throws Exception {
        Account account=new Account()
            .withMetaShape("51","我喜欢","1","2",3,"","")
            .withMetaShape("52","默认收藏","1","1",0,"","")
            .withMetaShape("53","别人的歌单","0","0",7,"","");
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        List<KugouLibraryApi.Playlist> rows=library.playlists(SESSION,0,100).items;
        assertEquals(3,rows.size());
        for(KugouLibraryApi.Playlist row:rows)assertFalse(row.name,row.canRename());
        assertNoWrite(account.calls);
    }

    @Test public void deletablePlaylistReturnsTheAccountRowWalksEveryPageAndWritesNothing() throws Exception {
        Account account=new Account().with("21","通勤",4).with("22","目标",9);
        for(int i=0;i<150;i++)account.with(String.valueOf(1000+i),"L"+i,0);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        KugouLibraryApi.Playlist target=library.deletablePlaylist(SESSION,"22",()->true);

        assertNotNull(target);
        assertEquals("22",target.id);
        assertEquals("目标",target.name);
        assertEquals(9,target.trackCount);
        assertTrue(target.editable);
        assertFalse(target.favorite);
        assertFalse(target.systemDefault);
        assertEquals(KugouLibraryApi.Playlist.Kind.CREATED,target.kind);
        assertTrue("the whole account list is read",account.listCalls()>=2);
        assertNoWrite(account.calls);
    }

    @Test public void absentIdIsReportedAsNullOnlyAfterACompleteWalk() throws Exception {
        Account account=new Account().with("21","通勤",4);
        for(int i=0;i<150;i++)account.with(String.valueOf(1000+i),"L"+i,0);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        assertNull(library.deletablePlaylist(SESSION,"22",()->true));
        assertEquals("absence needs every declared page",2,account.listCalls());
        assertNoWrite(account.calls);
    }

    @Test public void protectedRowsArePermissionRatherThanAbsent() throws Exception {
        Account account=new Account()
            .withShape("31","我喜欢","1","2",3)
            .withShape("32","默认收藏","1","1",0)
            .withShape("33","别人的歌单","0","0",7);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        rejects("permission",()->library.deletablePlaylist(SESSION,"31",()->true));
        rejects("permission",()->library.deletablePlaylist(SESSION,"32",()->true));
        rejects("permission",()->library.deletablePlaylist(SESSION,"33",()->true));
        assertNoWrite(account.calls);
    }

    @Test public void nonNumericAndZeroIdsNeverReachTheTransport() throws Exception {
        Account account=new Account().with("21","通勤",4);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        for(String id:new String[]{"abc","0","-1","21 ","1e3","9007199254740992",""})
            rejects("identity",()->library.deletablePlaylist(SESSION,id,()->true));
        assertTrue("no malformed id may be listed",account.calls.isEmpty());

        for(String id:new String[]{"abc","0"})
            rejects("identity",()->library.deletePlaylist(SESSION,id,()->true));
        assertTrue(account.calls.isEmpty());
    }

    @Test public void missingSessionOrGuardIsRejectedBeforeAnyRequest() throws Exception {
        Account account=new Account().with("21","通勤",4);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        rejects("credentials",()->library.deletablePlaylist(null,"21",()->true));
        rejects("credentials",()->library.deletablePlaylist(SESSION,"21",null));
        rejects("credentials",()->library.deletePlaylist(null,"21",()->true));
        rejects("credentials",()->library.deletePlaylist(SESSION,"21",null));
        assertTrue(account.calls.isEmpty());
    }

    @Test public void deleteUsesTheObservedTextProfileForExactlyOneNonEmptyOwnedList() throws Exception {
        Account account=new Account().with("21","通勤",4).with("22","目标",1);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        library.deletePlaylist(SESSION,"22",()->true);

        assertEquals(1,account.writeCalls());
        assertEquals("a full ownership read precedes dispatch",LIST,
            new URL(account.calls.get(0).endpoint).getPath());
        KugouApi.Request post=account.calls.get(account.calls.size()-1);
        assertEquals("https://gateway.kugou.com"+DELETE,post.endpoint);
        KugouApi.requireEndpoint(new URL(post.endpoint));
        assertEquals("cloudlist.service.kugou.com",post.headers.get("x-router"));
        assertEquals("text/plain; charset=utf-8",post.contentType());
        assertNull("not rename's binary wire profile",post.wireBody);
        assertNotNull(post.cloud);
        assertTrue(post.cloud.aes().matches("[0-9a-z]{6}"));
        JSONObject clear=new JSONObject(post.cloud.decode(Base64.getDecoder().decode(post.body)));
        assertEquals(3,clear.length());
        assertEquals(22L,clear.getLong("listid"));
        assertEquals(1,clear.getInt("type"));
        assertEquals(0,clear.getInt("total_ver"));
        assertEquals("3116",post.params.get("appid"));
        assertEquals("11440",post.params.get("clientver"));
        assertEquals(device().mid,post.params.get("mid"));
        assertEquals("-",post.params.get("dfid"));
        assertEquals("-",post.params.get("uuid"));
        assertEquals(SESSION.userId,post.params.get("userid"));
        assertEquals(SESSION.token,post.params.get("token"));
        assertEquals("gztx",post.params.get("last_area"));
        assertEquals(post.params.get("clienttime"),post.params.get("last_time"));
        assertEquals(md5("3116LnT6xpN3khm36zse0QzvmgTZ3waWdRSA11440"+
            post.params.get("clienttime")),post.params.get("key"));
        assertTrue(post.params.get("p").matches("[0-9A-F]{256}"));
        assertEquals(independentSignature(post.params,post.body),post.params.get("signature"));
        assertFalse("credentials never enter the clear mutation body",clear.toString().contains(TOKEN));
        assertEquals("ordinary JSON calls retain their original content type",
            "application/json; charset=utf-8",account.calls.get(0).contentType());
    }

    @Test public void emptyTargetUsesTheSameRequestAndDoesNotNeedRenameMetadata() throws Exception {
        Account account=new Account().with("22","目标",0);
        new KugouLibraryApi(new KugouApi(device(),account)).deletePlaylist(SESSION,"22",()->true);
        assertEquals(1,account.writeCalls());
    }

    @Test public void invalidOrMissingSystemFlagsNeverGrantDeletionPermission() throws Exception {
        for(Object flag:new Object[]{null,JSONObject.NULL,"","unknown",3,false}) {
            Account account=new Account().with("22","目标",1);
            account.rows.get(0).put("is_def",flag);
            KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));
            assertFalse(library.accountPlaylist(SESSION,"22",()->true).canDelete());
            rejects("permission",()->library.deletePlaylist(SESSION,"22",()->true));
            assertNoWrite(account.calls);
        }
    }

    @Test public void transportFailureIsSanitizedByTheFacadeAndNeverRetriedHere() throws Exception {
        Account account=new Account().with("22","目标",1);
        account.writeFailure=new IOException("fixture lost response");
        try {
            new KugouLibraryApi(new KugouApi(device(),account)).deletePlaylist(SESSION,"22",()->true);
            fail("lost response must propagate for reconciliation");
        } catch(IOException expected) { assertEquals("fixture lost response",expected.getMessage()); }
        assertEquals(1,account.writeCalls());
    }

    @Test public void rejectedRiskyOrMalformedWriteResponsesNeverRetryOrClaimSuccess() throws Exception {
        String[][] cases={
            {"{status:0,error_code:1,data:{},msg:'private-fixture'}","platform"},
            {"{status:1,error_code:7,data:{}}","platform"},
            {"{status:1,error_code:null,data:{}}","platform"},
            {"{status:1,ssa_code:1,data:{}}","risk"},
            {"{status:1,data:null}","protocol"},
            {"{data:{}}","protocol"}
        };
        for(String[] fixture:cases) {
            Account account=new Account().with("22","目标",1);
            account.writeResponse=json(fixture[0]);
            rejects(fixture[1],()->new KugouLibraryApi(new KugouApi(device(),account))
                .deletePlaylist(SESSION,"22",()->true));
            assertEquals(1,account.writeCalls());
        }
    }

    @Test public void unknownTotalsContinueByPageWindowUntilAnExplicitEmptyPage() throws Exception {
        Account account=new Account().with("22","目标",1);
        account.omitTotal=true;
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));
        assertNull(library.deletablePlaylist(SESSION,"99",()->true));
        assertEquals(2,account.listCalls());
        assertEquals(2,new JSONObject(account.calls.get(1).body).getInt("page"));
        assertNoWrite(account.calls);
        library.deletePlaylist(SESSION,"22",()->true);
        assertEquals(1,account.writeCalls());
    }

    @Test public void aTargetOnALaterUnknownTotalPageIsNeverMistakenForAbsent() throws Exception {
        Account account=new Account();
        for(int i=0;i<101;i++)account.with(String.valueOf(1000+i),"L"+i,0);
        account.omitTotal=true;
        assertNotNull(new KugouLibraryApi(new KugouApi(device(),account))
            .deletablePlaylist(SESSION,"1100",()->true));
        assertEquals(3,account.listCalls());
    }

    @Test public void aKnownTotalCannotDisappearAndNullUnknownTerminalIsNotAbsence() throws Exception {
        Account disappearing=new Account();
        for(int i=0;i<101;i++)disappearing.with(String.valueOf(1000+i),"L"+i,0);
        disappearing.omitTotalAfterFirst=true;
        rejects("incomplete",()->new KugouLibraryApi(new KugouApi(device(),disappearing))
            .deletePlaylist(SESSION,"1000",()->true));
        assertNoWrite(disappearing.calls);
        Account nullTerminal=new Account().with("21","保留",1);
        nullTerminal.omitTotal=true;nullTerminal.nullTerminal=true;
        rejects("incomplete",()->new KugouLibraryApi(new KugouApi(device(),nullTerminal))
            .deletablePlaylist(SESSION,"22",()->true));
        assertNoWrite(nullTerminal.calls);
    }

    @Test public void accountChangeAfterTheFinalReadPreventsDispatch() throws Exception {
        Account account=new Account().with("22","目标",1);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));
        rejects("account_changed",()->library.deletePlaylist(SESSION,"22",()->account.calls.isEmpty()));
        assertNoWrite(account.calls);
    }

    @Test public void accountChangeAfterDispatchDoesNotRetry() throws Exception {
        Account account=new Account().with("22","目标",1);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));
        rejects("account_changed",()->library.deletePlaylist(SESSION,"22",()->account.writeCalls()==0));
        assertEquals(1,account.writeCalls());
    }

    @Test public void anOversizedDeclaredAccountListCannotAuthorizeDeletion() throws Exception {
        Account account=new Account();
        account.listResponse=json("{status:1,data:{total:20001,info:[{listid:22,is_edit:1,is_def:0,count:1}]}}");
        rejects("incomplete",()->new KugouLibraryApi(new KugouApi(device(),account))
            .deletePlaylist(SESSION,"22",()->true));
        assertNoWrite(account.calls);
    }

    @Test public void lowLevelBoundaryRejectsInvalidIdsMissingGuardAndStaleAccount() throws Exception {
        Account account=new Account();
        KugouApi api=new KugouApi(device(),account);
        for(long id:new long[]{0,-1,9_007_199_254_740_992L})
            rejects("request",()->api.deletePlaylist(SESSION,id,()->true));
        rejects("credentials",()->api.deletePlaylist(null,22,()->true));
        rejects("credentials",()->api.deletePlaylist(SESSION,22,null));
        rejects("account_changed",()->api.deletePlaylist(SESSION,22,()->false));
        assertTrue(account.calls.isEmpty());
    }

    @Test public void deletionEndpointIsExactAndNeverUsesThePlainJsonLibraryRoute() throws Exception {
        Account account=new Account();
        KugouApi api=new KugouApi(device(),account);
        rejects("endpoint",()->api.libraryCall(DELETE,SESSION,new LinkedHashMap<>(),"{}",false));
        for(String target:new String[]{"http://gateway.kugou.com"+DELETE,
            "https://gateway.kugou.com.evil.example"+DELETE,
            "https://gateway.kugou.com:8443"+DELETE,
            "https://user:pass@gateway.kugou.com"+DELETE,
            "https://gateway.kugou.com/v1/delete_list",
            "https://gateway.kugou.com"+DELETE+"#fragment"})
            rejects("endpoint",()->KugouApi.requireEndpoint(new URL(target)));
        assertTrue(account.calls.isEmpty());
    }

    @Test public void deleteRefusesProtectedTargetsAsPermissionWithoutWriting() throws Exception {
        Account account=new Account()
            .withShape("31","我喜欢","1","2",3)
            .withShape("32","默认收藏","1","1",0)
            .withShape("33","别人的歌单","0","0",7);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        rejects("permission",()->library.deletePlaylist(SESSION,"31",()->true));
        rejects("permission",()->library.deletePlaylist(SESSION,"32",()->true));
        rejects("permission",()->library.deletePlaylist(SESSION,"33",()->true));
        assertNoWrite(account.calls);
    }

    @Test public void deleteOfAnAbsentIdReturnsWithoutWriting() throws Exception {
        Account account=new Account().with("21","通勤",4);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),account));

        library.deletePlaylist(SESSION,"22",()->true);

        assertEquals("absence is proved by one complete read",1,account.listCalls());
        assertNoWrite(account.calls);
    }

    @Test public void accountChangeStopsBeforeAnyWrite() throws Exception {
        Account stale=new Account().with("22","目标",0);
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),stale));
        rejects("account_changed",()->library.deletePlaylist(SESSION,"22",()->false));
        assertTrue("a stale account reads nothing",stale.calls.isEmpty());

        Account switched=new Account().with("22","目标",0);
        KugouLibraryApi reading=new KugouLibraryApi(new KugouApi(device(),switched));
        rejects("account_changed",
            ()->reading.deletePlaylist(SESSION,"22",()->switched.calls.isEmpty()));
        assertNoWrite(switched.calls);

        Account loggedOut=new Account().with("22","目标",0);
        KugouLibraryApi second=new KugouLibraryApi(new KugouApi(device(),loggedOut));
        rejects("account_changed",()->second.deletablePlaylist(SESSION,"22",()->false));
        assertTrue(loggedOut.calls.isEmpty());
    }

    @Test public void unreadablePagesNeverBecomeAbsenceOrPermission() throws Exception {
        Account duplicated=new Account().with("21","通勤",4);
        for(int i=0;i<150;i++)duplicated.with(String.valueOf(1000+i),"L"+i,0);
        duplicated.repeatPage=1;
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device(),duplicated));
        rejects("incomplete",()->library.deletablePlaylist(SESSION,"22",()->true));
        assertNoWrite(duplicated.calls);

        Account drifting=new Account().with("21","通勤",4);
        for(int i=0;i<150;i++)drifting.with(String.valueOf(1000+i),"L"+i,0);
        drifting.phantomAfterFirstPage=1;
        KugouLibraryApi second=new KugouLibraryApi(new KugouApi(device(),drifting));
        rejects("incomplete",()->second.deletablePlaylist(SESSION,"22",()->true));
        assertNoWrite(drifting.calls);

        Account refused=new Account();
        refused.listResponse=json("{status:0,error_code:1,data:{}}");
        KugouLibraryApi third=new KugouLibraryApi(new KugouApi(device(),refused));
        rejects("platform",()->third.deletePlaylist(SESSION,"22",()->true));
        assertNoWrite(refused.calls);
    }

    /** Fake account library; a list page is served from [rows] in server order. */
    private static final class Account implements KugouApi.Transport {
        final List<KugouApi.Request> calls=new ArrayList<>();
        final List<JSONObject> rows=new ArrayList<>();
        JSONObject listResponse;
        JSONObject writeResponse=json("{status:1,error_code:0,data:{info:[],total_ver:0}}");
        IOException writeFailure;
        boolean omitTotal,omitTotalAfterFirst,nullTerminal;
        int repeatPage;
        int phantomAfterFirstPage;
        long totalVersion;

        Account with(String id,String name,int count) {
            return withShape(id,name,"1","0",count);
        }

        Account withShape(String id,String name,String isEdit,String isDef,int count) {
            rows.add(row(id,name,isEdit,isDef,count));
            return this;
        }

        /** Same row, plus the edit metadata /v7/get_all_list supplies for a real own list. */
        Account withMetaShape(String id,String name,String isEdit,String isDef,int count,
                String tags,String intro) {
            JSONObject row=row(id,name,isEdit,isDef,count);
            try {
                row.put("sort",0L).put("tags",tags).put("intro",intro);
            } catch(JSONException invalidFixture) { throw new AssertionError(invalidFixture); }
            rows.add(row);
            totalVersion=31;
            return this;
        }

        static JSONObject row(String id,String name,String isEdit,String isDef,int count) {
            try {
                return new JSONObject().put("listid",id).put("name",name).put("is_edit",isEdit)
                    .put("is_def",isDef).put("count",count);
            } catch(JSONException invalidFixture) { throw new AssertionError(invalidFixture); }
        }

        int listCalls() {
            int count=0;
            for(KugouApi.Request request:calls)if(request.endpoint.endsWith(LIST))count++;
            return count;
        }
        int writeCalls() { return calls.size()-listCalls(); }

        @Override public JSONObject execute(KugouApi.Request request) throws IOException {
            calls.add(request);
            if(request.endpoint.endsWith(DELETE)) {
                if(writeFailure!=null)throw writeFailure;
                return writeResponse;
            }
            if(!request.endpoint.endsWith(LIST))
                throw new AssertionError("unexpected write endpoint: "+request.endpoint);
            try {
                return listPage(request);
            } catch(JSONException invalidFixture) { throw new AssertionError(invalidFixture); }
        }

        private JSONObject listPage(KugouApi.Request request) throws JSONException {
            if(listResponse!=null)return new JSONObject(listResponse.toString());
            JSONObject body=new JSONObject(request.body);
            int page=body.getInt("page"), size=body.getInt("pagesize");
            if(repeatPage>0 && page>=repeatPage)page=repeatPage;
            int from=Math.min((page-1)*size,rows.size()), to=Math.min(from+size,rows.size());
            JSONArray info=new JSONArray();
            for(int i=from;i<to;i++)info.put(rows.get(i));
            int total=rows.size()+(page>1?phantomAfterFirstPage:0);
            JSONObject data=new JSONObject().put("total_ver",totalVersion)
                .put("info",nullTerminal && info.length()==0?JSONObject.NULL:info);
            if(!omitTotal && !(omitTotalAfterFirst && page>1))data.put("total",total);
            return new JSONObject().put("status",1).put("data",data);
        }
    }

    private static KugouApi.Device device() { return new KugouApi.Device("b".repeat(32)); }

    private static JSONObject json(String text) {
        try { return new JSONObject(text); }
        catch(JSONException malformed) { throw new AssertionError(malformed); }
    }

    private static void assertNoWrite(List<KugouApi.Request> calls) {
        for(KugouApi.Request request:calls) {
            assertFalse("deletion must not reach a list removal endpoint",
                request.endpoint.endsWith(DELETE));
            assertFalse(request.endpoint.endsWith("/add_list"));
            assertFalse(request.endpoint.endsWith("/modify_list"));
            assertNull("no encrypted cloud body is built",request.wireBody);
        }
    }

    private interface Action { void run() throws Exception; }

    private static String independentSignature(Map<String,String> params,String body) throws Exception {
        StringBuilder value=new StringBuilder("LnT6xpN3khm36zse0QzvmgTZ3waWdRSA");
        params.keySet().stream().filter(key->!key.equals("signature")).sorted()
            .forEach(key->value.append(key).append('=').append(params.get(key)));
        return md5(value.append(body).append("LnT6xpN3khm36zse0QzvmgTZ3waWdRSA").toString());
    }
    private static String md5(String value) throws Exception {
        byte[] hash=MessageDigest.getInstance("MD5").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex=new StringBuilder();
        for(byte b:hash)hex.append(String.format(java.util.Locale.ROOT,"%02x",b));
        return hex.toString();
    }

    private static void rejects(String code,Action action) throws Exception {
        try {action.run();fail("Expected fixed failure "+code);}
        catch(KugouApi.ApiException failure) {
            assertEquals(code,failure.code);
            assertNull(failure.getCause());
            assertFalse(failure.getMessage().contains(TOKEN));
            assertFalse(failure.getMessage().contains("private-fixture"));
        }
    }
}
