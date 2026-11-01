package tech.musxi.loginpoc;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import static org.junit.Assert.*;

public class KugouLibraryApiTest {
    @Test public void favoritesUseOnlyDesignatedEditableListAndCurrentAccount() throws Exception {
        List<KugouApi.Request> calls=new ArrayList<>();
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)), r -> {
            calls.add(r);
            return r.endpoint.endsWith("/get_all_list") ? json("{status:1,data:{total:2,info:[{listid:12,name:'Any',is_def:0,is_edit:1,count:1},{listid:13,name:'Untrusted name',is_def:2,is_edit:1,count:1}]}}")
                : json("{status:1,data:{total:1,info:[{fileid:99,hash:'"+HASH+"',album_id:12,album_audio_id:34,name:'Fixture'}]}}");
        }));
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        assertEquals(Collections.singletonList(HASH+"_12_34"),library.likedIds(session,()->true));
        assertEquals("13",new JSONObject(calls.get(1).body).getString("listid"));
        rejects("account_changed",()->library.likedIds(session,()->false));
        int count=calls.size();
        library.setLiked(session,HASH+"_12_34","Fixture",0,true,()->true);
        assertEquals(count,calls.size()); // A synchronized snapshot needs no pre-write list scan.
    }
    @Test public void favoriteMutationsSignStructuredDataAndRefuseUnknownDeleteIds() throws Exception {
        List<KugouApi.Request> calls=new ArrayList<>();
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)), r -> {
            calls.add(r);
            if(r.endpoint.endsWith("/get_all_list"))return json("{status:1,data:{total:1,info:[{listid:13,is_def:2,is_edit:1,count:0}]}}");
            if(r.endpoint.endsWith("/get_list_all_file"))return json("{status:1,data:{total:0,info:[]}}");
            return json("{status:1}");
        }));
        library.setLiked(session,HASH+"_12_34","Fixture |, name",1000,true,()->true);
        KugouApi.Request write=calls.get(2);
        assertTrue(write.endpoint.endsWith("/cloudlist.service/v6/add_song"));
        assertFalse(write.headers.containsKey("x-router"));
        JSONObject body=new JSONObject(write.body);
        assertEquals("Fixture |, name",body.getJSONArray("data").getJSONObject(0).getString("name"));
        assertEquals("13",body.getString("listid"));
        assertEquals("gztx",write.params.get("last_area"));
        Map<String,String> params=new LinkedHashMap<>(write.params);
        String signature=params.remove("signature");
        assertEquals(KugouApi.androidSignature(params,write.body),signature);
        KugouApi.requireEndpoint(new URL(write.endpoint));
        rejects("endpoint",()->KugouApi.requireEndpoint(new URL("https://gateway.kugou.com/v4/delete_lists")));
    }
    @Test public void sourceSentinelsDoNotBlockOwnFavoritesOrLegacyLists() throws Exception {
        KugouLibraryApi.Page<KugouLibraryApi.Playlist> page=KugouLibraryApi.parsePlaylists(json(
            "{status:1,data:{total:4,info:["+
            "{listid:11,is_edit:1,is_def:2,list_create_gid:0},"+
            "{listid:12,is_edit:1,is_def:0,list_create_gid:''},"+
            "{listid:13,is_edit:0,is_def:0,list_create_gid:0},"+
            "{listid:14,is_edit:0,is_def:0,list_create_gid:''}]}}"),0,100);
        assertEquals(4,page.items.size());
        assertTrue(page.items.get(0).favorite);
        for(KugouLibraryApi.Playlist item:page.items)assertNull(item.sourceGlobalId);
    }
    @Test public void deletingFavoriteUsesOnlyDeleteEndpointAndActualFileId() throws Exception {
        List<KugouApi.Request> calls=new ArrayList<>();
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)), r -> {
            calls.add(r);
            if(r.endpoint.endsWith("/get_all_list"))
                return json("{status:1,data:{total:1,info:[{listid:13,name:'Favorite',is_def:2,is_edit:1,count:1}]}}");
            if(r.endpoint.endsWith("/get_list_all_file"))
                return json("{status:1,data:{total:1,info:[{fileid:99,hash:'"+HASH+"',album_id:12,album_audio_id:34,name:'Fixture'}]}}");
            return json("{status:1}");
        }));
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        library.setLiked(session,HASH+"_12_34","Fixture",0,false,()->true);

        assertEquals(3,calls.size());
        KugouApi.Request delete=calls.get(2);
        assertEquals("https://gateway.kugou.com/v4/delete_songs",delete.endpoint);
        assertEquals(Collections.singletonList(delete.endpoint),
            calls.stream().filter(r->r.endpoint.endsWith("/delete_songs")||r.endpoint.endsWith("/add_song"))
                .map(r->r.endpoint).collect(java.util.stream.Collectors.toList()));
        org.json.JSONArray data=new JSONObject(delete.body).getJSONArray("data");
        assertEquals(1,data.length());
        JSONObject file=data.getJSONObject(0);
        assertEquals(1,file.length());
        assertEquals(99L,file.getLong("fileid"));
    }
    @Test public void deletingFavoriteWithoutPositiveFileIdNeverSendsMutation() throws Exception {
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        for(String fileId:List.of("null","0")) {
            List<KugouApi.Request> calls=new ArrayList<>();
            KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)), r -> {
                calls.add(r);
                if(r.endpoint.endsWith("/get_all_list"))
                    return json("{status:1,data:{total:1,info:[{listid:13,is_def:2,is_edit:1,count:1}]}}");
                if(r.endpoint.endsWith("/get_list_all_file"))
                    return json("{status:1,data:{total:1,info:[{fileid:"+fileId+",hash:'"+HASH+"',album_id:12,album_audio_id:34,name:'Fixture'}]}}");
                return json("{status:1}");
            }));
            rejects("identity",()->library.setLiked(session,HASH+"_12_34","Fixture",0,false,()->true));
            assertEquals(2,calls.size());
            assertFalse(calls.stream().anyMatch(r->r.endpoint.endsWith("/delete_songs")||r.endpoint.endsWith("/add_song")));
        }
    }
    @Test public void favoriteIdentityRejectsAmbiguityAndNameGuessing() throws Exception {
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        for(String[] fixture:new String[][]{
            {"{status:1,data:{total:2,info:[{listid:12,name:'Favorite',is_def:2,is_edit:1,count:0},{listid:13,name:'收藏',is_def:2,is_edit:1,count:0}]}}","identity"},
            {"{status:1,data:{total:1,info:[{listid:12,name:'收藏',is_def:0,is_edit:1,count:0}]}}","unavailable"}
        }) {
            List<KugouApi.Request> calls=new ArrayList<>();
            KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)), r -> {
                calls.add(r);
                if(r.endpoint.endsWith("/get_all_list"))return json(fixture[0]);
                return json("{status:1,data:{total:0,info:[]}}");
            }));
            rejects(fixture[1],()->library.likedIds(session,()->true));
            assertEquals(1,calls.size());
            assertTrue(calls.get(0).endpoint.endsWith("/get_all_list"));
        }
    }
    @Test public void lyricsUseActualHashAndSignedLrcDownloadWithBoundedDecoding() throws Exception {
        List<KugouApi.Request> requests = new ArrayList<>();
        String lrc = "[00:01.00]Fixture";
        String encoded = Base64.getEncoder().encodeToString(lrc.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KugouApi api = new KugouApi(new KugouApi.Device("b".repeat(32)), request -> {
            requests.add(request);
            return request.endpoint.endsWith("/search") ? json("{status:200,candidates:[{id:'1',accesskey:'fixture'}]}")
                : json("{status:200,content:'" + encoded + "'}");
        });
        assertEquals(lrc, new KugouLibraryApi(api).lyrics(new KugouApi.Session("fixture-token", "123", "Fixture"), HASH + "_12_34"));
        assertEquals(2, requests.size());
        assertEquals("https://lyrics.kugou.com/search", requests.get(0).endpoint);
        assertEquals(HASH, requests.get(0).params.get("hash"));
        assertEquals("34", requests.get(0).params.get("album_audio_id"));
        assertEquals("1005", requests.get(0).params.get("appid"));
        assertEquals("20489", requests.get(0).params.get("clientver"));
        Map<String,String> searchParams = new LinkedHashMap<>(requests.get(0).params);
        String searchSignature = searchParams.remove("signature");
        assertEquals(KugouApi.androidSignature(searchParams, null), searchSignature);
        assertFalse(requests.get(0).params.containsKey("token"));
        assertEquals("", requests.get(0).params.get("keyword"));
        KugouApi.Request download = requests.get(1);
        assertEquals("lrc", download.params.get("fmt"));
        assertFalse(download.params.containsKey("token"));
        assertFalse(download.params.containsKey("userid"));
        Map<String,String> params = new LinkedHashMap<>(download.params);
        String signature = params.remove("signature");
        assertEquals(KugouApi.androidSignature(params, null), signature);
        KugouApi.requireEndpoint(new URL(download.endpoint));
        rejects("endpoint", () -> KugouApi.requireEndpoint(new URL("https://lyrics.kugou.com/other")));
        assertEquals("", KugouLibraryApi.parseLyrics(json("{content:''}")));
        rejects("protocol", () -> KugouLibraryApi.parseLyrics(json("{content:'!invalid!'}")));
        rejects("protocol", () -> KugouLibraryApi.parseLyrics(json("{content:'/w=='}")));
        rejects("limit", () -> KugouLibraryApi.parseLyrics(json("{content:'" + "a".repeat(700*1024+1) + "'}")));
    }
    @Test public void lyricsWhitelistKeepsOnlyExactOfficialHttpsRoutes() throws Exception {
        KugouApi.requireEndpoint(new URL("https://lyrics.kugou.com/search"));
        KugouApi.requireEndpoint(new URL("https://lyrics.kugou.com/download"));
        for (String target : Arrays.asList("https://lyrics.kugou.com/v1/search",
                "http://lyrics.kugou.com/search", "https://lyrics.kugou.com.other/search",
                "https://lyrics.kugou.com/search/", "https://user@lyrics.kugou.com/search",
                "https://lyrics.kugou.com:444/search", "https://lyrics.kugou.com/search#fragment")) {
            rejects("endpoint", () -> KugouApi.requireEndpoint(new URL(target)));
        }
    }
    @Test public void lyricsOnlyTreatSuccessfulEmptyCandidatesAsNoLyrics() throws Exception {
        for (String response : Arrays.asList("{status:200,candidates:[]}",
                "{status:404,candidates:[]}", "{status:200}",
                "{status:200,candidates:[{id:'1',accesskey:'invalid key'}]}")) {
            List<KugouApi.Request> requests = new ArrayList<>();
            KugouLibraryApi library = new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)), r -> {
                requests.add(r); return json(response);
            }));
            KugouApi.Session session = new KugouApi.Session("fixture-token", "123", "Fixture");
            if (response.equals("{status:200,candidates:[]}")) assertEquals("", library.lyrics(session, HASH + "_12_34"));
            else rejects(response.contains("404") ? "unavailable" : "protocol", () -> library.lyrics(session, HASH + "_12_34"));
            assertEquals(1, requests.size()); // No download or guessed keyword fallback.
        }
    }
    @Test public void lyricsPropagateEmptyResponseFailureWithoutRetryOrInventedEmptyLyrics() throws Exception {
        int[] requests = {0};
        KugouLibraryApi library = new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)), r -> {
            requests[0]++;
            throw new KugouApi.ApiException("protocol", "Fixed failure", "lyrics_search_json_empty");
        }));
        try {
            library.lyrics(new KugouApi.Session("fixture-token", "123", "Fixture"), HASH + "_12_34");
            fail("Expected fixed failure");
        } catch (KugouApi.ApiException e) {
            assertEquals("protocol", e.code);
            assertEquals("lyrics_search_json_empty", e.identityDiagnostic);
        }
        assertEquals(1, requests[0]);
    }
    private static final String HASH="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
    @Test public void originalLyricsStopAfterLrcAndRedactCandidateCredentials() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        String original="[00:01.00]Fixture";
        String encoded=Base64.getEncoder().encodeToString(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r);
            return r.endpoint.endsWith("/search")
                ?json("{status:200,candidates:[{id:'5',accesskey:'private-looking-access'}]}")
                :json("{status:200,content:'"+encoded+"'}");
        }));
        KugouLibraryApi.LyricsOriginal lyrics=library.lyricsOriginal(
            new KugouApi.Session("fixture-token","123","Fixture"),HASH+"_12_34");
        assertEquals(original,lyrics.original);
        assertEquals(2,requests.size());
        assertTrue(requests.get(0).endpoint.endsWith("/search"));
        assertEquals("lrc",requests.get(1).params.get("fmt"));
        assertEquals("LyricsOriginal(redacted)",lyrics.toString());
        assertFalse(lyrics.toString().contains(original));
        assertFalse(lyrics.toString().contains("private-looking-access"));
        assertFalse(lyrics.toString().contains("fixture-token"));
    }
    @Test public void translationReusesBoundCandidateAcrossLibraryFacadesWithoutSearching() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        String original="[00:01.00]Fixture";
        String encoded=Base64.getEncoder().encodeToString(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r);
            if(r.endpoint.endsWith("/search"))
                return json("{status:200,candidates:[{id:'5',accesskey:'fixture'},{id:'6',accesskey:'unused'}]}");
            return json("{status:200,content:'"+encoded+"'}");
        });
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        KugouLibraryApi.LyricsOriginal lyrics=new KugouLibraryApi(api).lyricsOriginal(session,HASH+"_12_34");
        KugouLibraryApi.LyricsDocument document=new KugouLibraryApi(api).lyricsTranslation(session,lyrics);
        assertEquals(original,document.original);
        assertEquals(3,requests.size());
        assertEquals(1,requests.stream().filter(r->r.endpoint.endsWith("/search")).count());
        assertEquals("5",requests.get(2).params.get("id"));
        assertEquals("fixture",requests.get(2).params.get("accesskey"));
        assertEquals("krc",requests.get(2).params.get("fmt"));
    }
    @Test public void translationFailureKeepsOriginalWithoutRetry() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        String original="[00:01.00]Fixture";
        String encoded=Base64.getEncoder().encodeToString(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r);
            if(r.endpoint.endsWith("/search"))
                return json("{status:200,candidates:[{id:'5',accesskey:'fixture'}]}");
            if("krc".equals(r.params.get("fmt")))
                throw new KugouApi.ApiException("network","Fixed failure");
            return json("{status:200,content:'"+encoded+"'}");
        }));
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        KugouLibraryApi.LyricsOriginal lyrics=library.lyricsOriginal(session,HASH+"_12_34");
        KugouLibraryApi.LyricsDocument document=library.lyricsTranslation(session,lyrics);
        assertEquals(original,document.original);
        assertEquals(KugouLibraryApi.TranslationStatus.NETWORK,document.translationStatus);
        assertEquals(3,requests.size());
    }
    @Test public void emptySearchProducesCandidateFreeOriginalWithoutTranslationDownload() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r);return json("{status:200,candidates:[]}");
        }));
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        KugouLibraryApi.LyricsOriginal lyrics=library.lyricsOriginal(session,HASH+"_12_34");
        assertEquals("",lyrics.original);
        assertEquals(1,requests.size());
        KugouLibraryApi.LyricsDocument document=library.lyricsTranslation(session,lyrics);
        assertEquals("",document.original);
        assertEquals(KugouLibraryApi.TranslationStatus.ABSENT,document.translationStatus);
        assertEquals(1,requests.size());
    }
    @Test public void originalHandleRejectsCrossSessionAndCrossApiUseBeforeTransport() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r);
            if(r.endpoint.endsWith("/search"))
                return json("{status:200,candidates:[{id:'5',accesskey:'fixture'}]}");
            return json("{status:200,content:''}");
        });
        KugouLibraryApi library=new KugouLibraryApi(api);
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        KugouLibraryApi.LyricsOriginal lyrics=library.lyricsOriginal(session,HASH+"_12_34");
        rejects("identity",()->library.lyricsTranslation(
            new KugouApi.Session("fixture-token","123","Fixture"),lyrics));
        assertEquals(2,requests.size());
        int[] otherCalls={0};
        KugouApi otherApi=new KugouApi(new KugouApi.Device("c".repeat(32)),r->{
            otherCalls[0]++;return json("{status:200,content:'unexpected'}");
        });
        rejects("identity",()->new KugouLibraryApi(otherApi).lyricsTranslation(session,lyrics));
        assertEquals(0,otherCalls[0]);
    }
    @Test public void translationReusesExactlyOneCandidateAndRetainsOriginalOnInvalidKrc() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        String original="[00:01.00]Fixture";
        String encoded=Base64.getEncoder().encodeToString(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r);
            if(r.endpoint.endsWith("/search"))return json("{status:200,candidates:[{id:'5',accesskey:'fixture'},{id:'6',accesskey:'unused'}]}");
            return json("{status:200,content:'"+("lrc".equals(r.params.get("fmt"))?encoded:"invalid")+"'}");
        }));
        KugouLibraryApi.LyricsDocument document=library.lyricsDocument(new KugouApi.Session("fixture","123","Fixture"),HASH+"_12_34");
        assertEquals(original,document.original);assertEquals(KugouLibraryApi.TranslationStatus.INVALID,document.translationStatus);
        assertEquals(3,requests.size());
        for(int i=1;i<3;i++) {
            assertEquals("5",requests.get(i).params.get("id"));assertEquals("fixture",requests.get(i).params.get("accesskey"));
            Map<String,String> params=new LinkedHashMap<>(requests.get(i).params);String signature=params.remove("signature");
            assertEquals(KugouApi.androidSignature(params,null),signature);
            assertFalse(params.containsKey("token"));
        }
        assertEquals("lrc",requests.get(1).params.get("fmt"));assertEquals("krc",requests.get(2).params.get("fmt"));
        assertFalse(document.toString().contains(original));
    }
    @Test public void optionalKrcTransportFailuresNeverRejectSuccessfulLrcOrRetry() throws Exception {
        for(String category:Arrays.asList("network","timeout","protocol","unavailable")) {
            int[] calls={0};String original="[00:01]Fixture";
            String encoded=Base64.getEncoder().encodeToString(original.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
                calls[0]++;
                if(r.endpoint.endsWith("/search"))return json("{status:200,candidates:[{id:'1',accesskey:'fixture'}]}");
                if("krc".equals(r.params.get("fmt")))throw new KugouApi.ApiException(category,"Fixed failure");
                return json("{status:200,content:'"+encoded+"'}");
            }));
            KugouLibraryApi.LyricsDocument doc=library.lyricsDocument(new KugouApi.Session("fixture","123","Fixture"),HASH+"_12_34");
            assertEquals(original,doc.original);assertEquals(3,calls[0]);
            assertEquals(category.equals("protocol")?KugouLibraryApi.TranslationStatus.INVALID:
                category.equals("unavailable")?KugouLibraryApi.TranslationStatus.PLATFORM_ERROR:
                KugouLibraryApi.TranslationStatus.valueOf(category.toUpperCase(Locale.ROOT)),doc.translationStatus);
        }
    }
    @Test public void absentLyricsDoNotAttemptOptionalDownloadAndFailedLrcStillFails() throws Exception {
        for(String response:Arrays.asList("{status:200,candidates:[]}","{status:200,candidates:[{id:'1',accesskey:'fixture'}]}")) {
            int[] calls={0};
            KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
                calls[0]++;
                return r.endpoint.endsWith("/search")?json(response):json("{status:200,content:''}");
            }));
            assertEquals("",library.lyricsDocument(new KugouApi.Session("fixture","123","Fixture"),HASH+"_12_34").original);
            assertEquals(response.contains("candidates:[]")?1:2,calls[0]);
        }
        int[] calls={0};
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            calls[0]++;
            return r.endpoint.endsWith("/search")?json("{status:200,candidates:[{id:'1',accesskey:'fixture'}]}"):json("{status:200,content:'!invalid!'}");
        }));
        rejects("protocol",()->library.lyricsDocument(new KugouApi.Session("fixture","123","Fixture"),HASH+"_12_34"));
        assertEquals(2,calls[0]);
    }
    private JSONObject json(String s) {try{return new JSONObject(s);}catch(org.json.JSONException e){throw new AssertionError(e);}}
    private void rejects(String code,Action action) throws Exception {
        try {action.run();fail("Expected fixed failure");}catch(KugouApi.ApiException e){assertEquals(code,e.code);assertNull(e.getCause());}
    }
    private interface Action {void run() throws Exception;}
    @Test public void privateRequestsAreSignedWithSessionAndCorrectRouter() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)), r->{requests.add(r);return json("{status:1,data:{info:[],total:0}}");});
        KugouLibraryApi library=new KugouLibraryApi(api);KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        library.playlists(session,0,30);library.tracks(session,"12",0,30);
        for(KugouApi.Request request:requests) {
            assertEquals("cloudlist.service.kugou.com",request.headers.get("x-router"));
            assertEquals("fixture-token",request.params.get("token"));
            Map<String,String> params=new LinkedHashMap<>(request.params);String signature=params.remove("signature");
            assertEquals(KugouApi.androidSignature(params,request.body),signature);
            assertEquals("123",new JSONObject(request.body).getString("userid"));
        }
        assertEquals("12",new JSONObject(requests.get(1).body).getString("listid"));
    }
    /** Fake get_list_all_file over [count] rows stored oldest first; row i is named "S<i>". */
    private KugouLibraryApi oldestFirstList(int count,List<Integer> pagesRead) {
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)),r->{ try {
            JSONObject body=new JSONObject(r.body);int size=body.getInt("pagesize"),page=body.getInt("page");
            pagesRead.add(page*1000+size);
            org.json.JSONArray rows=new org.json.JSONArray();
            for(int i=(page-1)*size;i<Math.min(count,page*size);i++) {
                String hash=String.format("%032x",i+1);
                rows.put(new JSONObject().put("hash",hash).put("album_id",1).put("album_audio_id",i+1).put("name","A - S"+i));
            }
            return new JSONObject().put("status",1).put("data",new JSONObject().put("info",rows).put("count",count));
        } catch(org.json.JSONException e) { throw new IOException(e); } });
        return new KugouLibraryApi(api);
    }
    private static List<String> names(KugouLibraryApi.Page<KugouLibraryApi.Track> page) {
        List<String> out=new ArrayList<>();for(KugouLibraryApi.Track t:page.items)out.add(t.name);return out;
    }
    @Test public void newestFirstReadsTheEndOfTheListAndReverses() throws Exception {
        List<Integer> reads=new ArrayList<>();
        KugouLibraryApi library=oldestFirstList(75,reads);KugouApi.Session session=new KugouApi.Session("t","1","n");
        KugouLibraryApi.Page<KugouLibraryApi.Track> first=library.tracksNewestFirst(session,"9",0,30);
        assertEquals(30,first.items.size());assertEquals("S74",first.items.get(0).name);assertEquals("S45",first.items.get(29).name);
        assertEquals(30,first.nextOffset);assertEquals(75,first.total);
        KugouLibraryApi.Page<KugouLibraryApi.Track> second=library.tracksNewestFirst(session,"9",30,30);
        assertEquals("S44",second.items.get(0).name);assertEquals("S15",second.items.get(29).name);assertEquals(60,second.nextOffset);
        KugouLibraryApi.Page<KugouLibraryApi.Track> last=library.tracksNewestFirst(session,"9",60,30);
        assertEquals(List.of("S14","S13","S12","S11","S10","S9","S8","S7","S6","S5","S4","S3","S2","S1","S0"),names(last));
        assertEquals(-1,last.nextOffset);
    }
    @Test public void newestFirstHandlesExactAndEmptyLists() throws Exception {
        KugouApi.Session session=new KugouApi.Session("t","1","n");
        KugouLibraryApi.Page<KugouLibraryApi.Track> exact=oldestFirstList(30,new ArrayList<>()).tracksNewestFirst(session,"9",0,30);
        assertEquals(30,exact.items.size());assertEquals("S29",exact.items.get(0).name);assertEquals(-1,exact.nextOffset);
        KugouLibraryApi.Page<KugouLibraryApi.Track> empty=oldestFirstList(0,new ArrayList<>()).tracksNewestFirst(session,"9",0,30);
        assertTrue(empty.items.isEmpty());assertEquals(-1,empty.nextOffset);
    }
    @Test public void completionFromThirtyRowPrefixEqualsFullReadAndReverseFor755Songs() throws Exception {
        KugouApi.Session session=new KugouApi.Session("t","1","n");
        KugouLibraryApi library=oldestFirstList(755,new ArrayList<>());
        List<String> expected=new ArrayList<>();
        for(int offset=0;;) {
            KugouLibraryApi.Page<KugouLibraryApi.Track> page=library.tracks(session,"9",offset,100);
            expected.addAll(names(page));
            if(page.nextOffset<0)break;
            offset=page.nextOffset;
        }
        Collections.reverse(expected);
        for(int prefixSize:new int[]{30,60,120,750}) {
            List<String> prefix=new ArrayList<>();
            for(int offset=0;offset<prefixSize;offset+=30)prefix.addAll(names(library.tracksNewestFirst(session,"9",offset,30)));
            int selected=prefixSize/2, start=selected/30*30, end=Math.min(start+30,prefix.size());
            List<String> queue=new ArrayList<>(prefix.subList(start,end));
            String playing=queue.get(selected-start);
            queue.addAll(0,prefix.subList(0,start));
            queue.addAll(prefix.subList(end,prefix.size()));
            int loaded=prefix.size();
            while(loaded<755) {
                int aligned=loaded/100*100;
                List<String> page=names(library.tracksNewestFirst(session,"9",aligned,100));
                List<String> added=page.subList(loaded-aligned,page.size());
                queue.addAll(added);loaded+=added.size();
            }
            assertEquals(expected,queue);
            assertEquals(playing,queue.get(selected));
        }
    }
    @Test public void newestFirstRejectsAListThatChangesMidRead() throws Exception {
        int[] count={75};
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)),r->{ try {
            JSONObject body=new JSONObject(r.body);int size=body.getInt("pagesize"),page=body.getInt("page");
            int now=count[0];if(size>1)count[0]=76;
            org.json.JSONArray rows=new org.json.JSONArray();
            for(int i=(page-1)*size;i<Math.min(now,page*size);i++)
                rows.put(new JSONObject().put("hash",String.format("%032x",i+1)).put("album_id",1).put("album_audio_id",i+1).put("name","A - S"+i));
            return new JSONObject().put("status",1).put("data",new JSONObject().put("info",rows).put("count",now));
        } catch(org.json.JSONException e) { throw new IOException(e); } });
        KugouApi.Session session=new KugouApi.Session("t","1","n");
        rejects("incomplete",()->new KugouLibraryApi(api).tracksNewestFirst(session,"9",0,30));
    }
    @Test public void playlistPaginationAndExplicitEmptyFinalPage() throws Exception {
        KugouLibraryApi.Page<KugouLibraryApi.Playlist> p=KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:[{listid:1,name:'Fixture',count:3}],total:2}}"),0,1);
        assertEquals(1,p.nextOffset);assertEquals(2,p.total);assertEquals(3,p.items.get(0).trackCount);
        assertEquals(-1,KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:null}}"),30,30).nextOffset);
        rejects("incomplete",()->KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:null}}"),0,30));
        assertTrue(KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:null,total:0}}"),0,30).items.isEmpty());
        assertEquals(30,KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:[{listid:1,count:3}]}}"),0,30).nextOffset);
        rejects("protocol",()->KugouLibraryApi.parsePlaylists(json("{status:1,data:{}}"),0,30));
        rejects("incomplete",()->KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:[],total:2}}"),0,30));
        rejects("incomplete",()->KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:[{listid:1,count:0}],total:2}}"),0,30));
    }
    @Test public void collectedPlaylistsKeepReadOnlyIdentityAndOnlyUseSourceGidForOthers() throws Exception {
        KugouLibraryApi.Page<KugouLibraryApi.Playlist> page=KugouLibraryApi.parsePlaylists(json(
            "{status:1,data:{total:4,info:["+
            "{listid:10,name:'Own',count:1,is_edit:1,is_def:0,list_create_gid:777},"+
            "{listid:11,name:'Favorite',count:1,is_edit:1,is_def:2},"+
            "{listid:12,name:'Other',count:2,is_edit:0,is_def:0,list_create_gid:'9001'},"+
            "{listid:13,name:'Legacy',count:1,is_edit:0,is_def:0,list_create_gid:null}]}}"),0,4);
        KugouLibraryApi.Playlist own=page.items.get(0),favorite=page.items.get(1);
        KugouLibraryApi.Playlist other=page.items.get(2),legacy=page.items.get(3);
        assertTrue(own.editable);assertFalse(own.readOnly);assertNull(own.sourceGlobalId);
        assertEquals(KugouLibraryApi.Playlist.Kind.CREATED,own.kind);
        assertTrue(favorite.favorite);assertTrue(favorite.editable);assertFalse(favorite.readOnly);
        assertEquals(KugouLibraryApi.Playlist.Kind.LIKED,favorite.kind);
        assertEquals("9001",other.sourceGlobalId);assertFalse(other.editable);assertTrue(other.readOnly);
        assertEquals(KugouLibraryApi.Playlist.Kind.COLLECTED,other.kind);
        assertNull(legacy.sourceGlobalId);assertFalse(legacy.editable);assertTrue(legacy.readOnly);
        assertEquals(KugouLibraryApi.Playlist.Kind.OTHER,legacy.kind);
    }
    @Test public void sourcePlaylistDetailUsesFixedEndpointAndKeepsPublishedOrder() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        String lists="{status:1,data:{total:2,info:["+
            "{listid:10,name:'Own',count:1,is_edit:1,is_def:0},"+
            "{listid:12,name:'Other',count:60,is_edit:0,is_def:0,list_create_gid:9001}]}}";
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r);
            if(r.endpoint.endsWith("/get_all_list"))return json(lists);
            try {
            org.json.JSONArray rows=new org.json.JSONArray();
            for(int i=0;i<30;i++)rows.put(new JSONObject().put("hash",String.format("%032x",i+1))
                .put("album_id",1).put("album_audio_id",100+i).put("name","Source "+i));
            if(r.endpoint.endsWith("/get_other_list_file_nofilt"))
                return new JSONObject().put("status",1).put("data",new JSONObject().put("total",60).put("info",rows));
            return json("{status:1,data:{total:1,info:[{hash:'"+HASH+"',album_id:1,album_audio_id:1,name:'Own song'}]}}");
            } catch(org.json.JSONException malformed) { throw new IOException(malformed); }
        }));
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        List<KugouLibraryApi.Playlist> playlists=library.playlists(session,0,30).items;
        KugouLibraryApi.Page<KugouLibraryApi.Track> source=library.tracksNewestFirst(session,playlists.get(1),30,30);
        assertEquals("Source 0",source.items.get(0).name);
        assertEquals("Source 29",source.items.get(29).name);
        assertEquals(-1,source.nextOffset);assertEquals(60,source.total);
        KugouApi.Request detail=requests.get(1);
        assertEquals("https://gateway.kugou.com/pubsongs/v2/get_other_list_file_nofilt",detail.endpoint);
        assertFalse(detail.headers.containsKey("x-router"));assertNull(detail.body);
        assertEquals("9001",detail.params.get("global_collection_id"));
        assertEquals("30",detail.params.get("begin_idx"));assertEquals("30",detail.params.get("pagesize"));
        assertEquals("1",detail.params.get("plat"));assertEquals("1",detail.params.get("type"));
        assertEquals("1",detail.params.get("mode"));assertEquals("1",detail.params.get("personal_switch"));
        assertEquals("1",detail.params.get("area_code"));
        assertEquals("abtags,hot_cmt,popularization",detail.params.get("extend_fields"));
        Map<String,String> signed=new LinkedHashMap<>(detail.params);String signature=signed.remove("signature");
        assertEquals(KugouApi.androidSignature(signed,null),signature);
        KugouApi.requireEndpoint(new URL(detail.endpoint));

        library.tracks(session,playlists.get(0),0,30);
        KugouApi.Request own=requests.get(2);
        assertEquals("https://gateway.kugou.com/v4/get_list_all_file",own.endpoint);
        assertEquals("10",new JSONObject(own.body).getString("listid"));
    }
    @Test public void sourcePlaylistShortPageIsIncomplete() throws Exception {
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            try {
            org.json.JSONArray rows=new org.json.JSONArray();
            for(int i=0;i<29;i++)rows.put(new JSONObject().put("hash",String.format("%032x",i+1))
                .put("album_id",1).put("album_audio_id",100+i).put("name","Source "+i));
            return new JSONObject().put("status",1).put("data",new JSONObject().put("total",60).put("info",rows));
            } catch(org.json.JSONException malformed) { throw new IOException(malformed); }
        });
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        rejects("incomplete",()->new KugouLibraryApi(api).tracksNewestFirst(session,"12","9001",0,30));
    }
    @Test public void trackUsesHashAndBothAlbumIdsWithoutConflatingFileId() throws Exception {
        JSONObject body=json("{status:1,data:{info:[{fileid:999,album_id:12,album_audio_id:34,filename:'Artist - Title.mp3',audio_info:{hash:'"+HASH.toUpperCase()+"',timelen:120000}}],total:1}}");
        KugouLibraryApi.Track t=KugouLibraryApi.parseTracks(body,0,30).items.get(0);
        assertEquals(HASH+"_12_34",t.id);assertEquals("Title",t.name);assertEquals(List.of("Artist"),t.artists);assertEquals(120000,t.durationMs);
        rejects("identity",()->KugouLibraryApi.parseTracks(json("{status:1,data:{info:[{hash:'wrong'}],total:1}}"),0,30));
    }
    private JSONObject searchRow() {
        return json("{FileHash:'"+HASH.toUpperCase()+"',AlbumID:'12',MixSongID:'34',FileName:'Artist - Title.mp3',"+
            "SingerName:'<em>Artist</em>',AlbumName:'Album &amp; More',Duration:123,fileid:999}");
    }
    private JSONObject searchBody(org.json.JSONArray rows) {
        try {
            return new JSONObject().put("status",1).put("data",new JSONObject().put("lists",rows).put("total",100));
        } catch(org.json.JSONException invalidFixture) {
            throw new AssertionError(invalidFixture);
        }
    }
    @Test public void searchUsesOfficialFirstPageRouterAndSessionSignatureWithoutAudio() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r); return searchBody(new org.json.JSONArray().put(searchRow()));
        });
        KugouLibraryApi library=new KugouLibraryApi(api,url->{fail("Search must never probe audio");});
        List<KugouLibraryApi.Song> songs=library.search(new KugouApi.Session("fixture-token","123","Fixture")," Title & Artist ");
        assertEquals(1,songs.size());assertEquals(1,requests.size());
        KugouApi.Request request=requests.get(0);
        assertEquals("https://gateway.kugou.com/v3/search/song",request.endpoint);
        assertEquals("complexsearch.kugou.com",request.headers.get("x-router"));assertNull(request.body);
        assertEquals("1",request.params.get("page"));assertEquals("10",request.params.get("pagesize"));
        assertEquals("Title & Artist",request.params.get("keyword"));
        assertEquals("AndroidFilter",request.params.get("platform"));
        assertEquals("0",request.params.get("albumhide"));assertEquals("1",request.params.get("iscorrection"));
        assertEquals("0",request.params.get("nocollect"));
        assertEquals("123",request.params.get("userid"));assertEquals("fixture-token",request.params.get("token"));
        assertFalse(request.params.containsKey("key"));
        Map<String,String> params=new LinkedHashMap<>(request.params);String signature=params.remove("signature");
        assertEquals(KugouApi.androidSignature(params,null),signature);
    }
    @Test public void searchParsesOfficialIdentityMetadataAndImmutableDtos() throws Exception {
        List<KugouLibraryApi.Song> songs=KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(searchRow())));
        KugouLibraryApi.Song song=songs.get(0);
        assertEquals(HASH+"_12_34",song.id);assertEquals("Title",song.name);
        assertEquals(List.of("Artist"),song.artists);assertEquals("Album & More",song.album);assertEquals(123000,song.durationMs);
        try { songs.clear();fail("Mutable result"); }catch(UnsupportedOperationException expected) { }
        try { song.artists.clear();fail("Mutable artists"); }catch(UnsupportedOperationException expected) { }
        JSONObject explicit=searchRow().put("OriSongName","<em>Title</em>").put("Suffix","(Live)");
        assertEquals("Title (Live)",KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(explicit))).get(0).name);
        JSONObject lowercase=json("{hash:'"+HASH+"',album_id:12,album_audio_id:34,filename:'Artist - Title.flac',timelen:123000}");
        song=KugouLibraryApi.parseSearch(json("{status:1,data:{info:["+lowercase+"]}}" )).get(0);
        assertEquals(HASH+"_12_34",song.id);assertEquals("Title",song.name);assertEquals(123000,song.durationMs);
        assertEquals(List.of("Artist"),song.artists);
    }
    @Test public void searchValidatesKeywordBoundariesBeforeAnyTransport() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r);return searchBody(new org.json.JSONArray());
        }));
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        rejects("protocol",()->library.search(session,null));
        for(String keyword:Arrays.asList("","   ","a".repeat(129),"a\nb","a\tb","a\u007fb","a\u0085b","a\u0000b"))
            rejects("protocol",()->library.search(session,keyword));
        assertTrue(requests.isEmpty());
        assertTrue(library.search(session,"歌").isEmpty());
        assertTrue(library.search(session,"歌".repeat(128)).isEmpty());assertEquals(2,requests.size());
    }
    @Test public void searchBoundsRowsAndRejectsAmbiguousOrMalformedIdentities() throws Exception {
        org.json.JSONArray ten=new org.json.JSONArray();
        for(int i=0;i<10;i++)ten.put(searchRow().put("MixSongID",i));
        assertEquals(10,KugouLibraryApi.parseSearch(searchBody(ten)).size());
        ten.put(searchRow().put("MixSongID",10));rejects("limit",()->KugouLibraryApi.parseSearch(searchBody(ten)));
        rejects("identity",()->KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(searchRow()).put(searchRow()))));
        for(String field:List.of("FileHash","AlbumID","MixSongID")) {
            JSONObject bad=searchRow().put(field,"wrong");
            rejects("identity",()->KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(bad))));
        }
        JSONObject unsafeId=searchRow().put("AlbumID","9007199254740992");
        rejects("identity",()->KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(unsafeId))));
        rejects("protocol",()->KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(1))));
        rejects("protocol",()->KugouLibraryApi.parseSearch(json("{status:1,data:{}}")));
        JSONObject noName=searchRow().put("FileName","");
        rejects("protocol",()->KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(noName))));
        JSONObject suffixOnly=searchRow().put("OriSongName",".mp3").put("Suffix","(Live)");
        rejects("protocol",()->KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(suffixOnly))));
        JSONObject noIds=searchRow();noIds.remove("AlbumID");noIds.remove("MixSongID");
        assertEquals(HASH+"_0_0",KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(noIds))).get(0).id);
    }
    @Test public void searchRejectsDurationOverflowFailuresAndRiskWithoutRawData() throws Exception {
        JSONObject longSong=searchRow().put("Duration",86401);
        rejects("limit",()->KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(longSong))));
        JSONObject negative=searchRow().put("Duration",-1);
        rejects("protocol",()->KugouLibraryApi.parseSearch(searchBody(new org.json.JSONArray().put(negative))));
        rejects("platform",()->KugouLibraryApi.parseSearch(json("{status:0,error_code:1,data:{lists:[]}}")));
        rejects("risk",()->KugouLibraryApi.parseSearch(json("{status:1,ssaCode:'fixture',data:{lists:[]}}")));
        assertTrue(KugouLibraryApi.parseSearch(json("{status:1,data:{lists:[]}}" )).isEmpty());
        KugouApi.requireEndpoint(new URL("https://gateway.kugou.com/v3/search/song"));
        rejects("endpoint",()->KugouApi.requireEndpoint(new URL("https://gateway.kugou.com/v3/search/special")));
        rejects("endpoint",()->KugouApi.requireEndpoint(new URL("https://complexsearch.kugou.com/v3/search/song")));
        rejects("endpoint",()->KugouApi.requireEndpoint(new URL("http://gateway.kugou.com/v3/search/song")));
    }
    private JSONObject searchPage(int offset,int count,Integer total) throws Exception {
        org.json.JSONArray rows=new org.json.JSONArray();
        for(int i=0;i<count;i++)rows.put(searchRow().put("MixSongID",offset+i+1));
        JSONObject data=new JSONObject().put("lists",rows).put("page",offset/30+1).put("pagesize",30);
        if(total!=null)data.put("total",total);
        return new JSONObject().put("status",1).put("data",data);
    }
    @Test public void pagedSearchUsesServerPageAndLimitWithoutAudioOrInvalidRequests() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();JSONObject fixture=searchPage(30,2,32);
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)),r->{requests.add(r);return fixture;});
        KugouLibraryApi library=new KugouLibraryApi(api,url->{fail("Search must not probe audio");});
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        KugouLibraryApi.Page<KugouLibraryApi.Song> page=library.search(session,"Song",30,30);
        assertEquals("2",requests.get(0).params.get("page"));assertEquals("30",requests.get(0).params.get("pagesize"));
        assertEquals(32,page.total);assertEquals(-1,page.nextOffset);assertEquals(HASH+"_12_31",page.items.get(0).id);
        for(int offset:new int[]{-30,1,20_010})rejects("limit",()->library.search(session,"Song",offset,30));
        rejects("limit",()->library.search(session,"Song",0,0));assertEquals(1,requests.size());
    }
    @Test public void pagedSearchPreservesUnknownTotalsAndChecksContinuationAndBounds() throws Exception {
        assertEquals(30,KugouLibraryApi.parseSearch(searchPage(0,30,100_000),0,30).nextOffset);
        KugouLibraryApi.Page<KugouLibraryApi.Song> unknown=KugouLibraryApi.parseSearch(searchPage(30,1,null),30,30);
        assertEquals(-1,unknown.total);assertEquals(60,unknown.nextOffset);
        assertEquals(-1,KugouLibraryApi.parseSearch(searchPage(60,0,null),60,30).nextOffset);
        JSONObject finalPage=searchPage(30,1,null);finalPage.getJSONObject("data").put("hasmore",0);
        assertEquals(-1,KugouLibraryApi.parseSearch(finalPage,30,30).nextOffset);
        rejects("incomplete",()->KugouLibraryApi.parseSearch(searchPage(30,2,100),30,30));
        rejects("incomplete",()->KugouLibraryApi.parseSearch(searchPage(30,2,31),30,30));
        rejects("incomplete",()->KugouLibraryApi.parseSearch(searchPage(30,0,100),30,30));
        rejects("limit",()->KugouLibraryApi.parseSearch(searchPage(0,31,100),0,30));
        JSONObject repeated=searchPage(30,30,100);repeated.getJSONObject("data").put("page",1);
        rejects("protocol",()->KugouLibraryApi.parseSearch(repeated,30,30));
        assertEquals(-1,KugouLibraryApi.parseSearch(searchPage(19_980,20,20_000),19_980,30).nextOffset);
        rejects("limit",()->KugouLibraryApi.parseSearch(searchPage(19_980,30,100_000),19_980,30));
        rejects("limit",()->KugouLibraryApi.parseSearch(searchPage(19_980,1,100_000),19_980,30));
        rejects("limit",()->KugouLibraryApi.parseSearch(searchPage(19_980,21,20_001),19_980,30));
        rejects("limit",()->KugouLibraryApi.parseSearch(searchPage(19_980,1,null),19_980,30));
    }
    @Test public void artworkParsesPublicCoverFieldsAndSizeTemplateWithoutUsingAudioUrls() throws Exception {
        JSONObject row=searchRow().put("Image","http://imge.kugou.com/stdmusic/{size}/album.jpg");
        JSONObject body=searchBody(new org.json.JSONArray().put(row));
        assertEquals("https://imge.kugou.com/stdmusic/400/album.jpg",KugouLibraryApi.parseSearch(body).get(0).coverUrl);
        for(String url:List.of("https://imge.kugou.com/a?key=fixture","https://imge.kugou.com/a#f","https://user@imge.kugou.com/a",
                "https://imge.kugou.com:443/a","https://imge.kugou.com.evil/a","https://fsandroid.tx.kugou.com/a.mp3")){
            row.put("Image",url);assertNull(KugouLibraryApi.parseSearch(body).get(0).coverUrl);
        }
        row.remove("Image");row.put("url","https://imge.kugou.com/not-an-image");
        assertNull(KugouLibraryApi.parseSearch(body).get(0).coverUrl);
        JSONObject tracks=json("{status:1,data:{info:[{hash:'"+HASH+"',filename:'Song',album_info:{cover:'http://imgessl.kugou.com/album.jpg'}}],total:1}}");
        assertEquals("https://imgessl.kugou.com/album.jpg",KugouLibraryApi.parseTracks(tracks,0,30).items.get(0).coverUrl);
        JSONObject playlists=json("{status:1,data:{info:[{listid:1,name:'List',pic:'http://imge.kugou.com/list.jpg'}],total:1}}");
        assertEquals("https://imge.kugou.com/list.jpg",KugouLibraryApi.parsePlaylists(playlists,0,30).items.get(0).coverUrl);
        assertNull(new KugouLibraryApi.Song("id","Song","Album",List.of(),0).coverUrl);
        assertNull(new KugouLibraryApi.Track("id","Song","Album",List.of(),0).coverUrl);
    }
    private JSONObject audio() {return json("{status:1,hash:'"+HASH+"',url:['http://fsandroid.tx.kugou.com/song.mp3?sign=fixture'],bitRate:128029,extName:'mp3'}");}
    @Test public void standardAudioUpgradesHttpsWithBoundedLease() throws Exception {
        KugouLibraryApi.Audio a=KugouLibraryApi.parseAudio(audio(),HASH,10);
        assertTrue(a.url.startsWith("https://"));assertEquals(60010,a.expiresAtElapsedMs);assertEquals("Audio(redacted)",a.toString());
        assertEquals("kugou:128",a.qualityId);
    }
    @Test public void higherQualitiesRequireMatchingFullAudioBitrateAndFormat() throws Exception {
        assertEquals("kugou:320",KugouLibraryApi.parseAudio(audio().put("bitRate",320000),HASH,0,"kugou:320").qualityId);
        assertEquals("kugou:flac",KugouLibraryApi.parseAudio(audio().put("bitRate",900000).put("extName","flac"),HASH,0,"kugou:flac").qualityId);
        rejects("unavailable",()->KugouLibraryApi.parseAudio(audio(),HASH,0,"kugou:320"));
        rejects("unavailable",()->KugouLibraryApi.parseAudio(audio().put("bitRate",128000),HASH,0,"kugou:flac"));
    }
    @Test public void onlyOfficialReturnedCandidatesCanFailOverWithTlsValidation() throws Exception {
        JSONObject body=audio().put("backupUrl",new org.json.JSONArray().put("https://evil.test/never-fetch").put("https://fsandroid.wx.kugou.com/backup.mp3"));
        KugouLibraryApi.Audio a=KugouLibraryApi.parseAudio(body,HASH,0);
        List<String> visited=new ArrayList<>();
        KugouLibraryApi.Audio selected=KugouLibraryApi.selectAudio(a,url->{
            visited.add(url);if(visited.size()==1)throw new javax.net.ssl.SSLPeerUnverifiedException("fixture");
        });
        assertEquals(2,visited.size());assertTrue(selected.url.endsWith("/backup.mp3"));assertFalse(visited.stream().anyMatch(v->v.contains("evil.test")));
        rejects("network",()->KugouLibraryApi.selectAudio(a,url->{throw new javax.net.ssl.SSLException("fixture");}));
        rejects("source_expired",()->KugouLibraryApi.selectAudio(a,url->{throw new KugouApi.ApiException("source_expired","fixture");}));
        rejects("rate",()->KugouLibraryApi.selectAudio(a,url->{throw new KugouApi.ApiException("rate","fixture");}));
    }
    @Test public void acceptsStandardFileEchoedForHigherQualityPlaylistHash() throws Exception {
        String standard="B".repeat(32);
        KugouLibraryApi.Audio a=KugouLibraryApi.parseAudio(audio().put("hash",standard).put("std_hash",standard.toLowerCase()),HASH,0);
        assertTrue(a.url.startsWith("https://"));
    }
    @Test public void rejectsTrialAndWrongHashAndWrongQuality() throws Exception {
        rejects("preview",()->KugouLibraryApi.parseAudio(audio().put("freeTrialInfo",new JSONObject()),HASH,0));
        rejects("preview",()->KugouLibraryApi.parseAudio(audio().put("is_free_part",1),HASH,0));
        rejects("preview",()->KugouLibraryApi.parseAudio(audio().put("is_free_part",true),HASH,0));
        rejects("preview",()->KugouLibraryApi.parseAudio(audio().put("is_free_part","unknown"),HASH,0));
        rejects("preview",()->KugouLibraryApi.parseAudio(audio().put("hash_offset",json("{end_ms:30000}")),HASH,0));
        rejects("identity",()->KugouLibraryApi.parseAudio(audio().put("hash","b".repeat(32)),HASH,0));
        rejects("identity",()->KugouLibraryApi.parseAudio(audio().put("hash","b".repeat(32)).put("std_hash","c".repeat(32)),HASH,0));
        rejects("unavailable",()->KugouLibraryApi.parseAudio(audio().put("bitRate",320000),HASH,0));
        rejects("unavailable",()->KugouLibraryApi.parseAudio(audio().put("extName","flac"),HASH,0));
    }
    @Test public void httpFallbackRequiresOriginalResponseAndExactKnownAudioHost() throws Exception {
        JSONObject body=audio().put("url",new org.json.JSONArray().put("http://fs.youthandroid2.kugou.com/song.mp3?sign=fixture"))
            .put("backupUrl",new org.json.JSONArray().put("http://fs.youthandroid2.kugou.com/another.mp3"));
        KugouLibraryApi.Audio a=KugouLibraryApi.parseAudio(body,HASH,0);
        List<String> visited=new ArrayList<>();
        KugouLibraryApi.Audio selected=KugouLibraryApi.selectAudio(a,url->{visited.add(url);if(url.startsWith("https:"))throw new javax.net.ssl.SSLPeerUnverifiedException("fixture");});
        assertEquals(List.of("https://fs.youthandroid2.kugou.com/song.mp3?sign=fixture","http://fs.youthandroid2.kugou.com/song.mp3?sign=fixture"),visited);
        assertTrue(selected.url.startsWith("http:"));
        // An HTTPS-only response must never synthesize a cleartext address.
        body.put("url",new org.json.JSONArray().put("https://fs.youthandroid2.kugou.com/song.mp3"));
        body.remove("backupUrl");
        a=KugouLibraryApi.parseAudio(body,HASH,0);
        assertEquals(1,a.candidates.size());assertTrue(a.candidates.get(0).startsWith("https:"));
    }
    @Test public void audioUsesLiteKeyAndSignatureWithoutEagerBackupRequests() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)),r->{requests.add(r);return audio();});
        List<String> probes=new ArrayList<>();
        KugouLibraryApi library=new KugouLibraryApi(api,probes::add);
        library.audio(new KugouApi.Session("fixture-token","123","Fixture"),HASH+"_12_34",0);
        KugouApi.Request request=requests.get(0);
        assertEquals("trackercdn.kugou.com",request.headers.get("x-router"));assertNull(request.body);
        assertEquals("128",request.params.get("quality"));assertEquals("0",request.params.get("IsFreePart"));
        assertEquals("3116",request.params.get("appid"));assertEquals("11430",request.params.get("clientver"));
        String keyInput=HASH+"185672dd44712f60bb1736df5a377e82"+"3116"+"b".repeat(32)+"123";
        byte[] digest=java.security.MessageDigest.getInstance("MD5").digest(keyInput.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertEquals(java.util.HexFormat.of().formatHex(digest),request.params.get("key"));
        Map<String,String> params=new LinkedHashMap<>(request.params);String signature=params.remove("signature");
        assertEquals(KugouApi.androidSignature(params,null),signature);assertEquals(1,probes.size());
    }
    @Test public void permissionSurvivesEveryAuthorizedAudioFailure() throws Exception {
        List<IOException> failures=List.of(
            new KugouApi.ApiException("identity","fixture"),
            new KugouApi.ApiException("protocol","fixture"),
            new KugouApi.ApiException("risk","fixture"),
            new KugouApi.ApiException("rate","fixture"),
            new KugouApi.ApiException("expired","fixture"),
            new java.net.SocketTimeoutException("fixture"),
            new IOException("fixture"));
        List<String> codes=List.of("identity","protocol","risk","rate","expired","timeout","network");
        for(int index=0;index<failures.size();index++) {
            IOException failure=failures.get(index);
            KugouApi.Device device=new KugouApi.Device("b".repeat(32));
            int[] authorizedRequests={0};
            KugouLibraryApi library=new KugouLibraryApi(
                new KugouApi(device,r->{throw new KugouApi.ApiException("permission","fixture");}),
                url->{},new KugouAuthorizedAudio(device,r->{authorizedRequests[0]++;throw failure;}));
            try {
                library.audio(new KugouApi.Session("fixture-token","123","Fixture"),HASH+"_12_34",0,
                    "kugou:320",true);
                fail("Expected the confirmed permission denial");
            } catch(KugouApi.ApiException expected) {
                assertEquals("permission",expected.code);
                KugouApi.ApiException suppressed=(KugouApi.ApiException)expected.getSuppressed()[0];
                assertEquals(codes.get(index),suppressed.code);
                assertFalse(suppressed.getMessage().contains("fixture"));
            }
            assertEquals(1,authorizedRequests[0]);
        }
    }
    @Test public void disabledAuthorizedAudioSkipsAuthorizationAndKeepsFreePlayback() throws Exception {
        KugouApi.Device device=new KugouApi.Device("b".repeat(32));
        List<String> qualities=new ArrayList<>();
        int[] authorizedRequests={0};
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device,r->{
            qualities.add(r.params.get("quality"));
            if("128".equals(r.params.get("quality")))return audio();
            throw new KugouApi.ApiException("permission","fixture");
        }),url->{},new KugouAuthorizedAudio(device,r->{
            authorizedRequests[0]++;throw new AssertionError("Authorized request must stay disabled");
        }));
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        assertEquals("kugou:128",
            library.audio(session,HASH+"_12_34",0,"kugou:128",false).qualityId);
        try {
            library.audio(session,HASH+"_12_34",0,"kugou:320",false);
            fail("Expected the basic permission denial");
        } catch(KugouApi.ApiException expected) {
            assertEquals("permission",expected.code);assertEquals(0,expected.getSuppressed().length);
        }
        try {
            library.audio(session,HASH+"_12_34",0,"kugou:high",false);
            fail("Expected high quality to require permission");
        } catch(KugouApi.ApiException expected) {
            assertEquals("permission",expected.code);
        }
        List<KugouLibraryApi.AudioQuality> menu=library.qualities(session,HASH+"_12_34",0,false);
        assertEquals(1,menu.size());assertEquals("kugou:128",menu.get(0).id);
        assertEquals(0,authorizedRequests[0]);
        assertFalse(qualities.contains("high"));
    }
    @Test public void qualitiesProbeEachExactQualityAndExactAudioReturnsActualId() throws Exception {
        List<KugouApi.Request> requests=new ArrayList<>();
        KugouApi api=new KugouApi(new KugouApi.Device("b".repeat(32)),r->{
            requests.add(r);
            switch(r.params.get("quality")) {
                case "128": return audio();
                case "320":
                    try { return audio().put("bitRate",320000); }
                    catch(org.json.JSONException failure) { throw new IOException(failure); }
                default: return audio();
            }
        });
        List<String> probes=new ArrayList<>();
        KugouLibraryApi library=new KugouLibraryApi(api,probes::add);
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        List<KugouLibraryApi.AudioQuality> qualities=library.qualities(session,HASH+"_12_34",0);
        assertEquals(Arrays.asList("kugou:128","kugou:320"),
            Arrays.asList(qualities.get(0).id,qualities.get(1).id));
        assertEquals(2,probes.size());
        try{qualities.clear();fail("Quality options must be read-only");}catch(UnsupportedOperationException expected){}
        KugouLibraryApi.Audio audio=library.audio(session,HASH+"_12_34",0,"kugou:320");
        assertEquals("kugou:320",audio.qualityId);
        assertEquals("320",requests.get(requests.size()-1).params.get("quality"));
        assertEquals(3,probes.size());
    }
    @Test public void optionalHighProbeFailuresKeepUsableQualitiesAndExplicitPlaybackStillFails() throws Exception {
        for(String code:List.of("protocol","risk","rate","timeout","http","expired")) {
            KugouApi.Device device=new KugouApi.Device("b".repeat(32));
            KugouApi.ApiException denial=new KugouApi.ApiException(code,"fixture");
            KugouApi api=new KugouApi(device,r->{
                JSONObject response=audio();
                try { if("320".equals(r.params.get("quality")))response.put("bitRate",320000); }
                catch(org.json.JSONException malformed){throw new IOException(malformed);}
                return response;
            });
            KugouAuthorizedAudio authorized=new KugouAuthorizedAudio(device,r->{throw denial;});
            KugouLibraryApi library=new KugouLibraryApi(api,url->{},authorized);
            KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
            List<KugouLibraryApi.AudioQuality> choices=library.qualities(session,HASH+"_12_34",0);
            assertEquals(code,2,choices.size());
            assertEquals("kugou:128",choices.get(0).id);
            assertEquals("kugou:320",choices.get(1).id);
            try {library.audio(session,HASH+"_12_34",0,"kugou:high");fail(code);}
            catch(KugouApi.ApiException expected){assertSame(denial,expected);}
        }
    }
    @Test public void optionalHighTransportFailureDoesNotBreakTheMenu() throws Exception {
        KugouApi.Device device=new KugouApi.Device("b".repeat(32));
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device,r->audio()),url->{},
            new KugouAuthorizedAudio(device,r->{throw new java.net.SocketTimeoutException();}));
        assertEquals(1,library.qualities(new KugouApi.Session("fixture-token","123","Fixture"),HASH+"_12_34",0).size());
    }
    @Test public void rejectedStandardVariantKeepsAnIndependentlyVerifiedHigherVariant() throws Exception {
        KugouApi.Device device=new KugouApi.Device("b".repeat(32));
        KugouApi api=new KugouApi(device,r->{
            if(!"320".equals(r.params.get("quality")))throw new KugouApi.ApiException("identity","fixture");
            try{return audio().put("bitRate",320000);}
            catch(org.json.JSONException failure){throw new IOException(failure);}
        });
        KugouLibraryApi library=new KugouLibraryApi(api,url->{},
            new KugouAuthorizedAudio(device,r->{throw new KugouApi.ApiException("protocol","fixture");}));
        KugouApi.Session session=new KugouApi.Session("fixture-token","123","Fixture");
        List<KugouLibraryApi.AudioQuality> choices=library.qualities(session,HASH+"_12_34",0);
        assertEquals(1,choices.size());assertEquals("kugou:320",choices.get(0).id);
        rejects("identity",()->library.audio(session,HASH+"_12_34",0,"kugou:128"));
    }
    @Test public void noVerifiedVariantPreservesTheOriginalFailure() throws Exception {
        KugouApi.Device device=new KugouApi.Device("b".repeat(32));
        KugouApi.ApiException failure=new KugouApi.ApiException("identity","fixture");
        KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device,r->{throw failure;}),url->{},
            new KugouAuthorizedAudio(device,r->{throw new java.net.SocketTimeoutException();}));
        try { library.qualities(new KugouApi.Session("fixture-token","123","Fixture"),HASH+"_12_34",0);fail(); }
        catch(KugouApi.ApiException expected){assertSame(failure,expected);}
    }
    @Test public void accountRiskStopsFurtherProbesAndKeepsAlreadyVerifiedChoices() throws Exception {
        for(String code:List.of("risk","rate","expired")) {
            KugouApi.Device device=new KugouApi.Device("b".repeat(32));
            List<String> requested=new ArrayList<>();
            KugouLibraryApi library=new KugouLibraryApi(new KugouApi(device,r->{
                requested.add(r.params.get("quality"));
                if("128".equals(r.params.get("quality")))return audio();
                throw new KugouApi.ApiException(code,"fixture");
            }),url->{},new KugouAuthorizedAudio(device,r->{fail("Do not sign another probe");return null;}));
            List<KugouLibraryApi.AudioQuality> choices=library.qualities(new KugouApi.Session("fixture-token","123","Fixture"),HASH+"_12_34",0);
            assertEquals(1,choices.size());assertEquals(List.of("128","320"),requested);
        }
    }
    @Test public void fixedEndpointsAndCdnAuthorityRejectForeignTargets() throws Exception {
        KugouApi.requireEndpoint(new URL("https://gateway.kugou.com/v5/url"));
        rejects("endpoint",()->KugouApi.requireEndpoint(new URL("https://gateway.kugou.com/arbitrary")));
        for(String value:List.of("https://kugou.com.evil.test/a","https://evil.test@fsandroid.tx.kugou.com/a","https://fsandroid.tx.kugou.com:0443/a","https://fsandroid.tx.kugou.com/a#fragment"))
            rejects("endpoint",()->KugouLibraryApi.officialUrl(value));
    }
    @Test public void failedAndRiskResponsesNeverBecomeSuccessfulEmptyLibraries() throws Exception {
        rejects("platform",()->KugouLibraryApi.parsePlaylists(json("{status:0,error_code:1,data:{info:[]}}"),0,30));
        rejects("risk",()->KugouLibraryApi.parsePlaylists(json("{status:1,ssaCode:'fixture',data:{info:[]}}"),0,30));
    }
}
