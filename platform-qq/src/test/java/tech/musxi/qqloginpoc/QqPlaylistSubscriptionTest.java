package tech.musxi.qqloginpoc;
import org.json.*;
import org.junit.Test;
import static org.junit.Assert.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Fixture coverage for whole-playlist subscription on the QQ platform. The protocol shape under
 * test is the official y.qq.com script: module music.musicasset.PlaylistFavWrite with methods
 * FavPlaylist/CancelFavPlaylist and param {uin:<own numeric uin>, v_playlistId:[<public tid>]}. */
public class QqPlaylistSubscriptionTest {
    private static final String OTHER_TID="77";
    private static QqMusicApi.Session session() throws Exception {return QqMusicApi.Session.fromJson(new JSONObject(
        "{\"credential\":{\"musicid\":12345,\"musickey\":\"fixture-key\",\"encryptUin\":\"fixture-euin\",\"loginType\":2},\"nickname\":\"Fixture\"}"));}
    private static JSONObject created(String rows) throws Exception {
        return new JSONObject("{\"bFinish\":1,\"v_playlist\":["+rows+"]}");
    }
    private static JSONObject favorites(int total,boolean more,String... rows) throws Exception {
        JSONArray list=new JSONArray();for(String row:rows){
            JSONObject item=new JSONObject(row);
            if(!item.has("songnum"))item.put("songnum",0);
            list.put(item);
        }
        return new JSONObject().put("hide",0).put("total",total).put("hasmore",more?1:0).put("v_list",list);
    }
    private static JSONObject publicDetail(String tid,int dirId,Object creatorId) throws Exception {
        JSONObject info=new JSONObject().put("tid",tid).put("dirid",dirId).put("title","Other Public")
            .put("logo","https://y.qq.com/fixture-cover.jpg");
        if(creatorId!=null)info.put("creator",new JSONObject().put("musicid",creatorId).put("nick","Other"));
        return new JSONObject().put("subcode",0).put("dirinfo",info).put("total_song_num",12);
    }
    private static final class Fixture implements QqMusicApi.Transport {
        final ArrayDeque<JSONObject> data=new ArrayDeque<>();final List<QqMusicApi.Request> requests=new ArrayList<>();
        final QqMusicApi api=new QqMusicApi(this);
        public QqMusicApi.Response execute(QqMusicApi.Request request) throws IOException {
            requests.add(request);
            try{return new QqMusicApi.Response(200,new JSONObject().put("code",0).put("req_0",
                new JSONObject().put("code",0).put("data",data.remove())).toString().getBytes(StandardCharsets.UTF_8),Collections.emptyMap());}
            catch(JSONException|NoSuchElementException e){throw new IOException();}
        }
        JSONObject param(int n) throws Exception{return new JSONObject(requests.get(n).body).getJSONObject("req_0").getJSONObject("param");}
        JSONObject req(int n) throws Exception{return new JSONObject(requests.get(n).body).getJSONObject("req_0");}
    }
    private interface Checked {void run() throws Exception;}
    private static void rejects(String code,Checked work) throws Exception {
        try{work.run();fail("Expected fixed failure");}
        catch(QqMusicApi.ApiException ex){assertEquals(code,ex.code);assertFalse(ex.getMessage().contains("fixture-key"));}
    }

    @Test public void followedPlaylistIsReportedFromCompleteFavoriteEnumeration() throws Exception {
        Fixture f=new Fixture();
        f.data.add(created(""));
        f.data.add(favorites(1,false,"{\"tid\":77,\"dirid\":5,\"title\":\"Stale title\",\"songnum\":12}"));
        f.data.add(publicDetail(OTHER_TID,5,67890));
        QqLibraryApi.Subscription state=new QqLibraryApi(f.api).playlistSubscription(session(),"77_5");
        assertTrue(state.followed);
        assertEquals("77_5",state.playlist.id);
        assertEquals("Other Public",state.playlist.name);
        assertEquals(12,state.playlist.trackCount);
        assertEquals(QqLibraryApi.Playlist.Kind.COLLECTED,state.playlist.kind);
        assertFalse(state.playlist.writable);assertFalse(state.playlist.liked);
        assertEquals(3,f.requests.size());
        assertEquals("fixture-euin",f.param(1).getString("uin"));
        assertEquals(77,f.param(2).getLong("disstid"));assertEquals(5,f.param(2).getInt("dirid"));
    }
    @Test public void unfollowedPublicPlaylistIsSafeToFollowWithOneWhitelistedWrite() throws Exception {
        Fixture f=new Fixture();
        f.data.add(created(""));
        f.data.add(favorites(0,false));
        f.data.add(publicDetail(OTHER_TID,5,67890));
        f.data.add(new JSONObject());
        new QqLibraryApi(f.api).setPlaylistFollowed(session(),"77_5",true);
        assertEquals(4,f.requests.size());
        assertEquals("music.musicasset.PlaylistFavWrite",f.req(3).getString("module"));
        assertEquals("FavPlaylist",f.req(3).getString("method"));
        JSONObject param=f.param(3);
        assertEquals(2,param.length());
        assertEquals("12345",param.getString("uin"));
        assertEquals(1,param.getJSONArray("v_playlistId").length());
        assertEquals(77,param.getJSONArray("v_playlistId").getLong(0));
    }
    @Test public void cancelUsesCancelFavPlaylistAndMatchingStateSkipsTheWrite() throws Exception {
        Fixture f=new Fixture();
        f.data.add(created(""));
        f.data.add(favorites(1,false,"{\"tid\":77,\"dirid\":5,\"title\":\"Mine now\"}"));
        f.data.add(publicDetail(OTHER_TID,5,67890));
        f.data.add(new JSONObject());
        new QqLibraryApi(f.api).setPlaylistFollowed(session(),"77_5",false);
        assertEquals(4,f.requests.size());
        assertEquals("CancelFavPlaylist",f.req(3).getString("method"));
        assertEquals(77,f.param(3).getJSONArray("v_playlistId").getLong(0));

        Fixture idempotent=new Fixture();
        idempotent.data.add(created(""));
        idempotent.data.add(favorites(1,false,"{\"tid\":77,\"dirid\":5,\"title\":\"Mine now\"}"));
        idempotent.data.add(publicDetail(OTHER_TID,5,67890));
        new QqLibraryApi(idempotent.api).setPlaylistFollowed(session(),"77_5",true);
        assertEquals(3,idempotent.requests.size());

        Fixture absent=new Fixture();
        absent.data.add(created(""));
        absent.data.add(favorites(0,false));
        absent.data.add(publicDetail(OTHER_TID,5,67890));
        new QqLibraryApi(absent.api).setPlaylistFollowed(session(),"77_5",false);
        assertEquals(3,absent.requests.size());
    }
    @Test public void ownLikedCreatedAndSystemListsAreRefusedBeforeAnyWrite() throws Exception {
        Fixture liked=new Fixture();
        rejects("permission",()->new QqLibraryApi(liked.api).playlistSubscription(session(),"0_201"));
        rejects("permission",()->new QqLibraryApi(liked.api).setPlaylistFollowed(session(),"0_201",true));
        assertTrue(liked.requests.isEmpty());

        Fixture own=new Fixture();
        own.data.add(created("{\"tid\":77,\"dirId\":0,\"dirName\":\"Mine\",\"songNum\":1,\"invalid\":0}"));
        rejects("permission",()->new QqLibraryApi(own.api).setPlaylistFollowed(session(),"77_0",true));
        assertEquals(1,own.requests.size());

        Fixture unfinished=new Fixture();
        unfinished.data.add(new JSONObject().put("bFinish",0).put("v_playlist",new JSONArray()));
        rejects("incomplete",()->new QqLibraryApi(unfinished.api).playlistSubscription(session(),"77_5"));
        assertEquals(1,unfinished.requests.size());
    }
    @Test public void creatorIdentityOfTheTargetIsRequiredAndMustBeAnotherAccount() throws Exception {
        Fixture self=new Fixture();
        self.data.add(created(""));
        self.data.add(favorites(0,false));
        self.data.add(publicDetail(OTHER_TID,5,12345));
        rejects("permission",()->new QqLibraryApi(self.api).playlistSubscription(session(),"77_5"));
        assertEquals(3,self.requests.size());

        Fixture missing=new Fixture();
        missing.data.add(created(""));
        missing.data.add(favorites(0,false));
        missing.data.add(publicDetail(OTHER_TID,5,null));
        rejects("identity",()->new QqLibraryApi(missing.api).setPlaylistFollowed(session(),"77_5",true));
        assertEquals(3,missing.requests.size());

        for(int dirId:new int[]{201,205,206}){
            Fixture system=new Fixture();
            system.data.add(created(""));
            system.data.add(favorites(0,false));
            system.data.add(publicDetail(OTHER_TID,dirId,67890));
            rejects("identity",()->new QqLibraryApi(system.api).playlistSubscription(session(),OTHER_TID+"_"+dirId));
            assertEquals(3,system.requests.size());
        }

        Fixture drifted=new Fixture();
        drifted.data.add(created(""));
        drifted.data.add(favorites(0,false));
        drifted.data.add(publicDetail("78",5,67890));
        rejects("identity",()->new QqLibraryApi(drifted.api).playlistSubscription(session(),"77_5"));
    }
    @Test public void hiddenNumericCreatorRequiresMatchingForeignEncryptedIdentity() throws Exception {
        Fixture foreign=new Fixture();
        foreign.data.add(created(""));foreign.data.add(favorites(0,false));
        JSONObject detail=publicDetail(OTHER_TID,31,0);
        detail.getJSONObject("dirinfo").put("encrypt_uin","foreign/euin+==").getJSONObject("creator")
            .put("encrypt_uin","foreign/euin+==");
        foreign.data.add(detail);
        assertFalse(new QqLibraryApi(foreign.api).playlistSubscription(session(),"77_31").followed);
        for(String encrypted:new String[]{"fixture-euin","", "bad\nidentity"," ","foreign identity","\u00a0"}){
            Fixture refused=new Fixture();refused.data.add(created(""));refused.data.add(favorites(0,false));
            JSONObject row=publicDetail(OTHER_TID,31,0);
            row.getJSONObject("dirinfo").put("encrypt_uin",encrypted).getJSONObject("creator").put("encrypt_uin",encrypted);
            refused.data.add(row);
            rejects(encrypted.equals("fixture-euin")?"permission":"identity",()->new QqLibraryApi(refused.api)
                .playlistSubscription(session(),"77_31"));
        }
        Fixture conflict=new Fixture();conflict.data.add(created(""));conflict.data.add(favorites(0,false));
        JSONObject row=publicDetail(OTHER_TID,31,0);
        row.getJSONObject("dirinfo").put("encrypt_uin","different").getJSONObject("creator").put("encrypt_uin","foreign");
        conflict.data.add(row);
        rejects("identity",()->new QqLibraryApi(conflict.api).playlistSubscription(session(),"77_31"));
    }
    @Test public void favoriteEnumerationRefusesIncompleteDuplicateOrTruncatedPages() throws Exception {
        Fixture truncated=new Fixture();
        truncated.data.add(created(""));
        truncated.data.add(favorites(2,true,"{\"tid\":77,\"dirid\":5}"));
        truncated.data.add(favorites(2,false));
        rejects("incomplete",()->new QqLibraryApi(truncated.api).playlistSubscription(session(),"77_5"));
        assertEquals(3,truncated.requests.size());

        Fixture hidden=new Fixture();
        hidden.data.add(created(""));
        hidden.data.add(new JSONObject().put("hide",1));
        rejects("permission",()->new QqLibraryApi(hidden.api).playlistSubscription(session(),"77_5"));

        Fixture failed=new Fixture();
        failed.data.add(created(""));
        failed.data.add(new JSONObject().put("hide",0).put("total",0).put("hasmore",0)
            .put("v_list",new JSONArray()).put("v_failTids",new JSONArray().put(77)));
        rejects("incomplete",()->new QqLibraryApi(failed.api).playlistSubscription(session(),"77_5"));

        Fixture duplicate=new Fixture();
        duplicate.data.add(created(""));
        duplicate.data.add(favorites(2,true,"{\"tid\":77,\"dirid\":5}"));
        duplicate.data.add(favorites(2,false,"{\"tid\":77,\"dirid\":5}"));
        rejects("incomplete",()->new QqLibraryApi(duplicate.api).playlistSubscription(session(),"77_5"));

        Fixture drift=new Fixture();
        drift.data.add(created(""));
        drift.data.add(favorites(2,true,"{\"tid\":77,\"dirid\":5}"));
        drift.data.add(favorites(3,false,"{\"tid\":78,\"dirid\":5}"));
        rejects("incomplete",()->new QqLibraryApi(drift.api).playlistSubscription(session(),"78_5"));

        Fixture oversized=new Fixture();
        oversized.data.add(created(""));
        oversized.data.add(new JSONObject().put("hide",0).put("total",20_001).put("hasmore",0)
            .put("v_list",new JSONArray()));
        rejects("limit",()->new QqLibraryApi(oversized.api).playlistSubscription(session(),"77_5"));
    }
    @Test public void accountChangeStopsBeforeOrAfterTheSingleWriteWithoutRetry() throws Exception {
        Fixture before=new Fixture();
        rejects("account_changed",()->new QqLibraryApi(before.api,()->false).playlistSubscription(session(),"77_5"));
        rejects("account_changed",()->new QqLibraryApi(before.api,()->false).setPlaylistFollowed(session(),"77_5",true));
        assertTrue(before.requests.isEmpty());

        Fixture after=new Fixture();
        after.data.add(created(""));
        after.data.add(favorites(0,false));
        after.data.add(publicDetail(OTHER_TID,5,67890));
        after.data.add(new JSONObject());
        // Reads pass; the checkpoint after the single write sees the changed account.
        rejects("account_changed",()->new QqLibraryApi(after.api,()->after.requests.size()<4)
            .setPlaylistFollowed(session(),"77_5",true));
        assertEquals(4,after.requests.size());
        assertEquals("FavPlaylist",after.req(3).getString("method"));
    }
    @Test public void protectedTransportAcceptsOnlyTheSingleIdFollowShape() throws Exception {
        Fixture f=new Fixture();
        f.data.add(new JSONObject());
        f.api.library(session(),"music.musicasset.PlaylistFavWrite","FavPlaylist",
            new JSONObject("{\"uin\":\"12345\",\"v_playlistId\":[77]}"));
        assertEquals("music.musicasset.PlaylistFavWrite",f.req(0).getString("module"));

        for(String param:new String[]{
            "{\"uin\":\"999\",\"v_playlistId\":[77]}",
            "{\"uin\":12345,\"v_playlistId\":[77]}",
            "{\"uin\":\"12345\",\"v_playlistId\":[77,78]}",
            "{\"uin\":\"12345\",\"v_playlistId\":[]}",
            "{\"uin\":\"12345\",\"v_playlistId\":[0]}",
            "{\"uin\":\"12345\",\"v_playlistId\":[\"77\"]}",
            "{\"uin\":\"12345\",\"v_playlistId\":[77.5]}",
            "{\"uin\":\"12345\",\"v_playlistId\":[[77]]}",
            "{\"uin\":\"12345\",\"v_playlistId\":[77],\"extra\":1}",
            "{\"uin\":\"12345\"}",
            "{\"v_playlistId\":[77]}"})
            rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistFavWrite","FavPlaylist",new JSONObject(param)));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.PlaylistFavWrite","FavSinger",
            new JSONObject("{\"uin\":\"12345\",\"v_playlistId\":[77]}")));
        rejects("endpoint",()->f.api.library(session(),"music.musicasset.AlbumFavWrite","FavPlaylist",
            new JSONObject("{\"uin\":\"12345\",\"v_playlistId\":[77]}")));
        rejects("endpoint",()->f.api.library(null,"music.musicasset.PlaylistFavWrite","FavPlaylist",
            new JSONObject("{\"uin\":\"12345\",\"v_playlistId\":[77]}")));
        assertEquals(1,f.requests.size());
    }
    @Test public void playlistIdFormatStillFailsClosedBeforeTransport() throws Exception {
        Fixture f=new Fixture();
        for(String id:new String[]{null,"","77","_5","77_5_1","077_5","9007199254740992_5"}){
            rejects("identity",()->new QqLibraryApi(f.api).playlistSubscription(session(),id));
        }
        rejects("protocol",()->new QqLibraryApi(f.api).playlistSubscription(session(),"77_"));
        rejects("identity",()->new QqLibraryApi(f.api).playlistSubscription(null,"77_5"));
        assertTrue(f.requests.isEmpty());
    }
}
