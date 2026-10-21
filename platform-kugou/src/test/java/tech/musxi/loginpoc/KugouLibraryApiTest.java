package tech.musxi.loginpoc;

import org.json.JSONObject;
import org.junit.Test;
import java.io.IOException;
import java.net.URL;
import java.util.*;
import static org.junit.Assert.*;

public class KugouLibraryApiTest {
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
