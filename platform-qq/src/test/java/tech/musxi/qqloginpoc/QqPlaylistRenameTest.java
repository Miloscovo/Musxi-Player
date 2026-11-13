package tech.musxi.qqloginpoc;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;

/** Protocol fixtures prove bounded requests and verification, not live platform acceptance. */
public class QqPlaylistRenameTest {
    @Test public void completeOfficialEditPreservesMetadataAndRealDirectory() throws Exception {
        for(int dir:new int[]{1,2}) {
            Fixture f=roundTrip(dir);
            new QqLibraryApi(f.api).renamePlaylist(session(),"9_"+dir,"  New  ");
            assertEquals(5,f.requests.size());
            JSONObject write=f.param(2);
            assertEquals("EditPlaylist",f.operation(2).getString("method"));
            assertEquals(8,write.length());
            assertEquals(dir,write.getInt("dirId"));assertEquals(95,write.getInt("mask"));
            assertEquals("New",write.getString("dirNewName"));
            assertEquals("Line one\n第二行",write.getString("dirNewDesc"));
            assertEquals("https://y.qq.com/original.jpg",write.getString("dirNewPicUrl"));
            assertEquals("",write.getString("dirNewPicUrl2"));
            assertEquals("12,34",write.getString("dirNewtaglist"));
            assertEquals(2,write.getInt("dirShow")); // A private playlist must remain private.
            JSONObject read=f.param(1);
            assertEquals("uniform_get_Dissinfo",f.operation(1).getString("method"));
            assertEquals(dir,read.getInt("dirid"));assertEquals(3,read.getInt("optype"));
            assertEquals(1,read.getInt("tag"));assertEquals(0,read.getInt("userinfo"));
            assertEquals(300,read.getInt("pic_dpi"));
        }
    }
    @Test public void unchangedTitleDoesNotWrite() throws Exception {
        Fixture f=new Fixture();f.data.add(owned(1));f.data.add(metadata(1,"Same"));
        new QqLibraryApi(f.api).renamePlaylist(session(),"9_1","Same");
        assertEquals(2,f.requests.size());
    }
    @Test public void missingMetadataNeverBecomesEmptyDefaults() throws Exception {
        for(String key:new String[]{"title","desc","picurl","picurl2","tag","dir_show","id","dirid"}) {
            Fixture f=new Fixture();f.data.add(owned(1));
            JSONObject info=metadata(1,"Old");info.getJSONObject("dirinfo").remove(key);f.data.add(info);
            rejects("incomplete",()->new QqLibraryApi(f.api).renamePlaylist(session(),"9_1","New"));
            assertEquals(key,2,f.requests.size());
        }
    }
    @Test public void nullOrMalformedPreservedFieldsAreRefused() throws Exception {
        for(String key:new String[]{"desc","picurl","picurl2","tag"}) {
            Fixture f=new Fixture();f.data.add(owned(1));
            JSONObject info=metadata(1,"Old");info.getJSONObject("dirinfo").put(key,JSONObject.NULL);f.data.add(info);
            rejects("incomplete",()->new QqLibraryApi(f.api).renamePlaylist(session(),"9_1","New"));
            assertEquals(2,f.requests.size());
        }
        Fixture tags=new Fixture();tags.data.add(owned(1));
        JSONObject info=metadata(1,"Old");info.getJSONObject("dirinfo").getJSONArray("tag").put(new JSONObject().put("id",12));
        tags.data.add(info);
        rejects("incomplete",()->new QqLibraryApi(tags.api).renamePlaylist(session(),"9_1","New"));
        assertEquals(2,tags.requests.size());
    }
    @Test public void metadataMustEchoTheExactTarget() throws Exception {
        for(String key:new String[]{"id","tid","dirid","dirId"}) {
            Fixture f=new Fixture();f.data.add(owned(1));
            JSONObject info=metadata(1,"Old");info.getJSONObject("dirinfo").put(key,99);f.data.add(info);
            rejects("identity",()->new QqLibraryApi(f.api).renamePlaylist(session(),"9_1","New"));
            assertEquals(2,f.requests.size());
        }
    }
    @Test public void rejectsNonOwnedSystemAndZeroDirectories() throws Exception {
        for(String id:new String[]{"0_201","9_201","9_205","9_209","9_0"}) {
            Fixture f=new Fixture();
            rejects("permission",()->new QqLibraryApi(f.api).renamePlaylist(session(),id,"New"));
            assertTrue(f.requests.isEmpty());
        }
        Fixture f=new Fixture();f.data.add(owned(2));
        rejects("permission",()->new QqLibraryApi(f.api).renamePlaylist(session(),"9_1","New"));
        assertEquals(1,f.requests.size());
        Fixture ambiguous=new Fixture();JSONObject lists=owned(1);
        lists.getJSONArray("v_playlist").put(new JSONObject().put("tid",10).put("dirId",1).put("title","Other").put("songNum",0));
        ambiguous.data.add(lists);
        rejects("identity",()->new QqLibraryApi(ambiguous.api).renamePlaylist(session(),"9_1","New"));
        assertEquals(1,ambiguous.requests.size());
    }
    @Test public void everyPreservedFieldIsCheckedAfterWriting() throws Exception {
        for(String key:new String[]{"desc","picurl","picurl2","tag","dir_show","title"}) {
            Fixture f=roundTrip(1);JSONObject after=(JSONObject)f.data.removeLast();
            Object changed=key.equals("tag")?new JSONArray():key.equals("dir_show")?1:"Changed";
            after.getJSONObject("dirinfo").put(key,changed);f.data.add(after);
            rejects("not_confirmed",()->new QqLibraryApi(f.api).renamePlaylist(session(),"9_1","New"));
            assertEquals(key,5,f.requests.size());
        }
    }
    @Test public void ownershipLossAfterWriteNeverReportsSuccess() throws Exception {
        Fixture f=new Fixture();f.data.add(owned(1));f.data.add(metadata(1,"Old"));f.data.add(new JSONObject());
        f.data.add(new JSONObject().put("bFinish",1).put("v_playlist",new JSONArray()));
        rejects("permission",()->new QqLibraryApi(f.api).renamePlaylist(session(),"9_1","New"));
        assertEquals(4,f.requests.size());
    }
    @Test public void lostWriteResponseCanOnlySucceedWithFreshConfirmation() throws Exception {
        Fixture f=new Fixture();f.data.add(owned(1));f.data.add(metadata(1,"Old"));
        f.data.add(new IOException("private transport detail"));f.data.add(owned(1));f.data.add(metadata(1,"New"));
        new QqLibraryApi(f.api).renamePlaylist(session(),"9_1","New");
        assertEquals(5,f.requests.size());assertEquals(1,f.editCount());
        Fixture denied=new Fixture();denied.data.add(owned(1));denied.data.add(metadata(1,"Old"));
        denied.data.add(new JSONObject().put("retCode",80092));denied.data.add(owned(1));denied.data.add(metadata(1,"Old"));
        rejects("permission",()->new QqLibraryApi(denied.api).renamePlaylist(session(),"9_1","New"));
        assertEquals(1,denied.editCount());
    }
    @Test public void accountChangeStopsBeforeTheWrite() throws Exception {
        Fixture f=new Fixture();f.data.add(owned(1));f.data.add(metadata(1,"Old"));
        rejects("account_changed",()->new QqLibraryApi(f.api,()->f.requests.size()<2).renamePlaylist(session(),"9_1","New"));
        assertEquals(0,f.editCount());
    }
    @Test public void invalidNamesNeverReachTransport() throws Exception {
        for(String name:new String[]{" ","bad\nname","x".repeat(21),"\uD83C\uDFB5".repeat(11),"bad\uD800"}) {
            Fixture f=new Fixture();
            rejects("request",()->new QqLibraryApi(f.api).renamePlaylist(session(),"9_1",name));
            assertTrue(f.requests.isEmpty());
        }
    }
    @Test public void transportRejectsOtherMasksMissingExtraAndMistypedFields() throws Exception {
        Fixture good=roundTrip(1);new QqLibraryApi(good.api).renamePlaylist(session(),"9_1","New");
        JSONObject original=good.param(2);
        for(String key:new String[]{"dirId","mask","dirNewName","dirNewDesc","dirNewPicUrl","dirNewPicUrl2","dirNewtaglist","dirShow"}) {
            Fixture f=new Fixture();JSONObject missing=new JSONObject(original.toString());missing.remove(key);
            rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistBaseWrite","EditPlaylist",missing));
            assertTrue(f.requests.isEmpty());
        }
        for(String bad:new String[]{"{mask:1}","{mask:95.0}","{dirId:0}","{dirId:201}","{dirId:1.0}",
            "{dirShow:3}","{extra:1}","{dirNewDesc:1}","{dirNewtaglist:'1,,2'}"}) {
            JSONObject patch=new JSONObject(bad), changed=new JSONObject(original.toString());
            for(Iterator<String> keys=patch.keys();keys.hasNext();) {
                String key=keys.next();changed.put(key,patch.get(key));
            }
            Fixture f=new Fixture();
            rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistBaseWrite","EditPlaylist",changed));
            assertTrue(f.requests.isEmpty());
        }
        Fixture f=new Fixture();JSONObject badRead=new JSONObject(good.param(1).toString()).put("optype",1);
        rejects("endpoint",()->f.api.library(session(),"music.srfDissInfo.aiDissInfo","uniform_get_Dissinfo",badRead));
        assertTrue(f.requests.isEmpty());
    }
    private static Fixture roundTrip(int dir) throws Exception {
        Fixture f=new Fixture();f.data.add(owned(dir));f.data.add(metadata(dir,"Old"));f.data.add(new JSONObject());
        f.data.add(owned(dir));f.data.add(metadata(dir,"New"));return f;
    }
    private static JSONObject owned(int dir) throws Exception {
        return new JSONObject().put("bFinish",1).put("v_playlist",new JSONArray().put(new JSONObject()
            .put("tid",9).put("dirId",dir).put("title","Old").put("songNum",0)));
    }
    private static JSONObject metadata(int dir,String title) throws Exception {
        return new JSONObject().put("dirinfo",new JSONObject().put("id",9).put("dirid",dir).put("title",title)
            .put("desc","Line one\n第二行").put("picurl","https://y.qq.com/original.jpg").put("picurl2","")
            .put("tag",new JSONArray().put(new JSONObject().put("id",12)).put(new JSONObject().put("id",34)))
            .put("dir_show",2));
    }
    private static QqMusicApi.Session session() throws Exception {
        return QqMusicApi.Session.fromJson(new JSONObject("{credential:{musicid:12345,musickey:'fixture-secret',encryptUin:'fixture-euin',loginType:2},nickname:'Fixture'}"));
    }
    private interface Checked {void run() throws Exception;}
    private static void rejects(String category,Checked work) throws Exception {
        try {work.run();fail("Expected fixed failure");}
        catch(QqMusicApi.ApiException failure) {assertEquals(category,failure.code);assertFalse(failure.getMessage().contains("fixture-secret"));}
    }
    private static final class Fixture implements QqMusicApi.Transport {
        final ArrayDeque<Object> data=new ArrayDeque<>();final List<QqMusicApi.Request> requests=new ArrayList<>();
        final QqMusicApi api=new QqMusicApi(this);
        public QqMusicApi.Response execute(QqMusicApi.Request request) throws IOException {
            requests.add(request);Object next=data.remove();if(next instanceof IOException)throw (IOException)next;
            try {
                return new QqMusicApi.Response(200,new JSONObject().put("code",0).put("req_0",new JSONObject()
                    .put("code",0).put("data",next)).toString().getBytes(StandardCharsets.UTF_8),Collections.emptyMap());
            } catch(JSONException invalid) { throw new IOException("Invalid test fixture",invalid); }
        }
        JSONObject operation(int index) throws Exception {return new JSONObject(requests.get(index).body).getJSONObject("req_0");}
        JSONObject param(int index) throws Exception {return operation(index).getJSONObject("param");}
        int editCount() throws Exception {int count=0;for(int i=0;i<requests.size();i++)if(operation(i).getString("method").equals("EditPlaylist"))count++;return count;}
    }
}
