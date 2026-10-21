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
        assertEquals(count+2,calls.size()); // Already liked: no mutation.
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
    @Test public void playlistPaginationAndExplicitEmptyFinalPage() throws Exception {
        KugouLibraryApi.Page<KugouLibraryApi.Playlist> p=KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:[{listid:1,name:'Fixture',count:3}],total:2}}"),0,1);
        assertEquals(1,p.nextOffset);assertEquals(2,p.total);assertEquals(3,p.items.get(0).trackCount);
        assertEquals(-1,KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:null}}"),30,30).nextOffset);
        assertEquals(30,KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:[{listid:1,count:3}]}}"),0,30).nextOffset);
        rejects("protocol",()->KugouLibraryApi.parsePlaylists(json("{status:1,data:{}}"),0,30));
        rejects("incomplete",()->KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:[],total:2}}"),0,30));
        rejects("incomplete",()->KugouLibraryApi.parsePlaylists(json("{status:1,data:{info:[{listid:1,count:0}],total:2}}"),0,30));
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
    @Test public void rejectsTrialAndWrongHashAndWrongQuality() throws Exception {
        rejects("permission",()->KugouLibraryApi.parseAudio(audio().put("freeTrialInfo",new JSONObject()),HASH,0));
        rejects("permission",()->KugouLibraryApi.parseAudio(audio().put("is_free_part",1),HASH,0));
        rejects("permission",()->KugouLibraryApi.parseAudio(audio().put("is_free_part",true),HASH,0));
        rejects("permission",()->KugouLibraryApi.parseAudio(audio().put("is_free_part","unknown"),HASH,0));
        rejects("permission",()->KugouLibraryApi.parseAudio(audio().put("hash_offset",json("{end_ms:30000}")),HASH,0));
        rejects("identity",()->KugouLibraryApi.parseAudio(audio().put("hash","b".repeat(32)),HASH,0));
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
