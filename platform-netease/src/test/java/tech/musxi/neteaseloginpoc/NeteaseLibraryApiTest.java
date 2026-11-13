package tech.musxi.neteaseloginpoc;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import java.net.URL;
import java.util.Arrays;
import java.util.Collections;
import static org.junit.Assert.*;

public class NeteaseLibraryApiTest {
    @Test public void timedLyricsUsesPinnedV1PayloadAndMatchingYrcTranslation() throws Exception {
        final java.util.List<NeteaseApi.Request> requests=new java.util.ArrayList<>();
        NeteaseApi api=new NeteaseApi(request->{requests.add(request);return new NeteaseApi.Response(jUnchecked(
            "{code:200,lrc:{lyric:'[00:01.000]Legacy'},tlyric:{lyric:'[00:01.000]旧译文'},"
            +"yrc:{lyric:'[1500,500](1500,500,0)Native'},ytlrc:{lyric:'[00:01.500]新译文'}}"),Collections.emptyMap());});
        NeteaseApi.Session session=NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret'}}"));
        NeteaseLibraryApi.LyricsDocument doc=new NeteaseLibraryApi(api).lyricsTimedDocument(session,"12345");
        assertEquals(1,requests.size());assertEquals(NeteaseApi.LYRIC_NEW,requests.get(0).endpoint);
        JSONObject payload=eapiPayload(requests.get(0),"/api/song/lyric/v1");
        assertEquals("12345",payload.getString("id"));assertFalse(payload.getBoolean("cp"));
        for(String field:new String[]{"tv","lv","rv","kv","yv","ytv","yrv"})assertEquals(0,payload.getInt(field));
        assertFalse(payload.has("_nmclfl"));
        assertTrue(doc.original.contains("[00:01.500]Native"));assertEquals("[00:01.500]新译文",doc.translation);
        assertEquals(YrcLyricsDecoder.Status.PRESENT,doc.wordTimings.status);assertEquals(1,doc.timingRevision);
        assertEquals("LyricsDocument{redacted}",doc.toString());
    }

    @Test public void absentOrMalformedYrcUsesOrdinaryTimelineAndNeverWrongTranslation() throws Exception {
        JSONObject base=j("{code:200,lrc:{lyric:'[00:01.000]Legacy'},tlyric:{lyric:'[00:01.000]旧译文'},ytlrc:{lyric:'[00:09.000]另一轨'}}");
        for(Object bad:new Object[]{JSONObject.NULL,42,j("{lyric:42}"),j("{lyric:'[1000,100](0,100,0)bad'}")}){
            JSONObject input=new JSONObject(base.toString()).put("yrc",bad);
            NeteaseLibraryApi.LyricsDocument doc=NeteaseLibraryApi.parseTimedLyricsDocument(input);
            assertEquals("[00:01.000]Legacy",doc.original);assertEquals("[00:01.000]旧译文",doc.translation);
            assertEquals(bad==JSONObject.NULL?1:0,doc.timingRevision);
        }
        JSONObject valid=new JSONObject(base.toString()).put("yrc",j("{lyric:'[1500,500](1500,500,0)Native'}"));
        valid.remove("ytlrc");
        NeteaseLibraryApi.LyricsDocument doc=NeteaseLibraryApi.parseTimedLyricsDocument(valid);
        assertEquals(NeteaseLibraryApi.TranslationStatus.ABSENT,doc.translationStatus);assertNull(doc.translation);
        valid.put("ytlrc",j("{lyric:42}"));
        doc=NeteaseLibraryApi.parseTimedLyricsDocument(valid);
        assertEquals(NeteaseLibraryApi.TranslationStatus.INVALID,doc.translationStatus);
        assertEquals(YrcLyricsDecoder.Status.PRESENT,doc.wordTimings.status);
    }

    @Test public void timedEndpointFailureFallsBackButExpiredIdentityAndCancellationDoNotRetry() throws Exception {
        final java.util.List<NeteaseApi.Request> requests=new java.util.ArrayList<>();
        NeteaseApi api=new NeteaseApi(request->{requests.add(request);return new NeteaseApi.Response(jUnchecked(
            request.endpoint.equals(NeteaseApi.LYRIC_NEW)?"{code:500}":"{code:200,lrc:{lyric:'[00:01.000]Fallback'}}"),Collections.emptyMap());});
        NeteaseApi.Session session=NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret'}}"));
        NeteaseLibraryApi.LyricsDocument doc=new NeteaseLibraryApi(api).lyricsTimedDocument(session,"12345");
        assertEquals("[00:01.000]Fallback",doc.original);assertEquals(0,doc.timingRevision);
        assertEquals(YrcLyricsDecoder.Status.PLATFORM_ERROR,doc.wordTimings.status);assertEquals(2,requests.size());
        requests.clear();
        NeteaseApi expired=new NeteaseApi(request->{requests.add(request);return new NeteaseApi.Response(jUnchecked("{code:301}"),Collections.emptyMap());});
        fails("expired",()->new NeteaseLibraryApi(expired).lyricsTimedDocument(session,"12345"));
        assertEquals(1,requests.size());requests.clear();
        fails("account_changed",()->new NeteaseLibraryApi(expired,()->false).lyricsTimedDocument(session,"12345"));
        assertTrue(requests.isEmpty());
    }

    @Test public void nativeYrcCanStandWithoutLegacyLrcAndNoLyricsFlagsStillWin() throws Exception {
        JSONObject body=j("{code:200,yrc:{lyric:'[1000,100](1000,100,0)Hi'}}");
        assertEquals(YrcLyricsDecoder.Status.PRESENT,NeteaseLibraryApi.parseTimedLyricsDocument(body).wordTimings.status);
        body.put("nolyric",true);
        assertEquals("",NeteaseLibraryApi.parseTimedLyricsDocument(body).original);
        body.put("nolyric","true");fails("protocol",()->NeteaseLibraryApi.parseTimedLyricsDocument(body));
    }
    interface Checked { void run() throws Exception; }
    private static JSONObject j(String text) throws Exception { return new JSONObject(text); }
    private static void fails(String code, Checked block) throws Exception {
        try { block.run(); fail("Expected safe failure"); }
        catch (NeteaseApi.ApiException failure) {
            assertEquals(code, failure.code);
            assertFalse(failure.getMessage().contains("secret"));
            assertNull(failure.getCause());
        }
    }

    @Test public void playlistPaginationIsBoundedAndMakesProgress() throws Exception {
        JSONObject body = j("{playlist:[{id:42,name:'List',trackCount:2}],more:true}");
        NeteaseLibraryApi.Page<NeteaseLibraryApi.Playlist> page = NeteaseLibraryApi.parsePlaylists(body, 30, 30);
        assertEquals(31, page.nextOffset);
        assertEquals("42", page.items.get(0).id);
        fails("protocol", () -> NeteaseLibraryApi.parsePlaylists(j("{playlist:[],more:true}"), 0, 30));
        fails("request", () -> NeteaseLibraryApi.parsePlaylists(body, -1, 30));
        fails("request", () -> NeteaseLibraryApi.parsePlaylists(body, 0, 101));
        fails("protocol", () -> NeteaseLibraryApi.parsePlaylists(j("{playlist:[],more:'false'}"), 0, 30));
        fails("protocol", () -> NeteaseLibraryApi.parsePlaylists(j("{playlist:[{id:1.5,name:'Bad',trackCount:0}],more:false}"), 0, 30));
    }

    @Test public void regularPlaylistFixturesClassifyLikedCreatedCollectedAndUnknown() throws Exception {
        JSONObject body = j("{playlist:["
            +"{id:41,name:'External',trackCount:3,specialType:0,creator:{userId:8}},"
            +"{id:42,name:'Mine',trackCount:2,specialType:0,creator:{userId:7}},"
            +"{id:43,name:'Liked',trackCount:1,specialType:5,creator:{userId:7}},"
            +"{id:44,name:'Unknown',trackCount:0}],more:false}");
        java.util.List<NeteaseLibraryApi.Playlist> lists =
            NeteaseLibraryApi.parsePlaylists(body, 0, 30, "7").items;
        assertEquals(NeteaseLibraryApi.Playlist.Kind.COLLECTED, lists.get(0).kind);
        assertEquals(NeteaseLibraryApi.Playlist.Kind.CREATED, lists.get(1).kind);
        assertEquals(NeteaseLibraryApi.Playlist.Kind.LIKED, lists.get(2).kind);
        assertEquals(NeteaseLibraryApi.Playlist.Kind.OTHER, lists.get(3).kind);
    }

    @Test public void writablePlaylistsKeepOnlySessionCreatorAndRequireLikedSongs() throws Exception {
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        JSONObject page = j("{code:200,playlist:["
            +"{id:41,name:'External',trackCount:3,specialType:0,creator:{userId:8}},"
            +"{id:42,name:'Mine',trackCount:2,specialType:0,creator:{userId:7}},"
            +"{id:43,name:'Liked',trackCount:1,specialType:5,creator:{userId:7}}],more:false}");
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(page, Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));
        java.util.List<NeteaseLibraryApi.Playlist> lists =
                new NeteaseLibraryApi(api).writablePlaylists(session);
        assertEquals(Arrays.asList("42", "43"),
                Arrays.asList(lists.get(0).id, lists.get(1).id));
        assertTrue(lists.get(1).liked); assertTrue(lists.get(1).writable);
        assertEquals(NeteaseApi.USER_PLAYLIST, requests.get(0).endpoint);
        // USER_PLAYLIST uses the pinned WEAPI transport; custom playlist writes use EAPI.
        assertTrue(requests.get(0).body.contains("encSecKey="));
        try { lists.clear(); fail("Writable playlists must be read-only"); }
        catch (UnsupportedOperationException expected) { }
        fails("protocol", () -> NeteaseLibraryApi.parseWritablePlaylistPage(
                j("{playlist:[{id:43,name:'Liked',trackCount:1,specialType:5,creator:{userId:7}}]}"), "7"));
    }

    @Test public void addToPlaylistUsesLikedPathAndPinnedCustomEndpoint() throws Exception {
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        final int[] call = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request); call[0]++;
            if (call[0] == 1) return new NeteaseApi.Response(
                    jUnchecked("{code:200,playlist:{id:43,specialType:5,creator:{userId:7}}}"),
                    Collections.emptyMap());
            return new NeteaseApi.Response(jUnchecked("{code:200}"), Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        library.addToPlaylist(session, "43", "123");
        assertEquals(2, requests.size());
        assertEquals(NeteaseApi.PLAYLIST_DETAIL, requests.get(0).endpoint);
        assertEquals(NeteaseApi.SET_LIKE, requests.get(1).endpoint);
        assertEquals("123", eapiPayload(requests.get(1), "/api/radio/like").getString("trackId"));

        final java.util.List<NeteaseApi.Request> customRequests = new java.util.ArrayList<>();
        final int[] detailCalls = {0};
        NeteaseApi custom = new NeteaseApi(request -> {
            customRequests.add(request); detailCalls[0]++;
            return new NeteaseApi.Response(jUnchecked(detailCalls[0] == 1
                    ? "{code:200,playlist:{id:42,specialType:0,creator:{userId:7}}}"
                    : "{code:200}"), Collections.emptyMap());
        });
        new NeteaseLibraryApi(custom).addToPlaylist(session, "42", "123");
        assertEquals(2, detailCalls[0]);
        assertEquals(NeteaseApi.PLAYLIST_TRACKS, customRequests.get(1).endpoint);
        NeteaseApi.requireEndpoint(new URL(customRequests.get(1).endpoint));
        JSONObject add = eapiPayload(customRequests.get(1), "/api/playlist/manipulate/tracks");
        assertEquals("add", add.getString("op"));
        assertEquals("42", add.getString("pid"));
        assertEquals("[\"123\"]", add.getString("trackIds"));
        assertEquals("true", add.getString("imme"));

        NeteaseApi foreign = new NeteaseApi(request -> new NeteaseApi.Response(
                jUnchecked("{code:200,playlist:{id:42,specialType:0,creator:{userId:8}}}"),
                Collections.emptyMap()));
        fails("permission", () -> new NeteaseLibraryApi(foreign).addToPlaylist(session, "42", "123"));
    }

    @Test public void removeFromPlaylistUsesLikedPathAndPinnedCustomDelete() throws Exception {
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));

        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        final int[] call = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request); call[0]++;
            if (call[0] == 1) return new NeteaseApi.Response(
                    jUnchecked("{code:200,playlist:{id:43,specialType:5,creator:{userId:7}}}"),
                    Collections.emptyMap());
            return new NeteaseApi.Response(jUnchecked("{code:200}"), Collections.emptyMap());
        });
        new NeteaseLibraryApi(api).removeFromPlaylist(session, "43", "123");
        assertEquals(2, requests.size());
        assertEquals(NeteaseApi.PLAYLIST_DETAIL, requests.get(0).endpoint);
        assertEquals(NeteaseApi.SET_LIKE, requests.get(1).endpoint);
        NeteaseApi.requireEndpoint(new URL(requests.get(1).endpoint));
        JSONObject unliked = eapiPayload(requests.get(1), "/api/radio/like");
        assertEquals("123", unliked.getString("trackId"));
        assertFalse(unliked.getBoolean("like"));

        final java.util.List<NeteaseApi.Request> customRequests = new java.util.ArrayList<>();
        final int[] detailCalls = {0};
        NeteaseApi custom = new NeteaseApi(request -> {
            customRequests.add(request); detailCalls[0]++;
            return new NeteaseApi.Response(jUnchecked(detailCalls[0] == 1
                    ? "{code:200,playlist:{id:42,specialType:0,creator:{userId:7}}}"
                    : "{code:200}"), Collections.emptyMap());
        });
        new NeteaseLibraryApi(custom).removeFromPlaylist(session, "42", "123");
        assertEquals(2, detailCalls[0]);
        assertEquals(NeteaseApi.PLAYLIST_TRACKS, customRequests.get(1).endpoint);
        NeteaseApi.requireEndpoint(new URL(customRequests.get(1).endpoint));
        JSONObject delete = eapiPayload(customRequests.get(1), "/api/playlist/manipulate/tracks");
        assertEquals("del", delete.getString("op"));
        assertEquals("42", delete.getString("pid"));
        assertEquals("[\"123\"]", delete.getString("trackIds"));
        assertEquals("true", delete.getString("imme"));

        NeteaseApi foreign = new NeteaseApi(request -> new NeteaseApi.Response(
                jUnchecked("{code:200,playlist:{id:42,specialType:0,creator:{userId:8}}}"),
                Collections.emptyMap()));
        fails("permission", () -> new NeteaseLibraryApi(foreign).removeFromPlaylist(session, "42", "123"));

        NeteaseApi missing = new NeteaseApi(request -> new NeteaseApi.Response(
                jUnchecked("{code:200,playlist:{id:41,specialType:0,creator:{userId:7}}}"),
                Collections.emptyMap()));
        fails("identity", () -> new NeteaseLibraryApi(missing).removeFromPlaylist(session, "42", "123"));

        for (String bad : new String[]{null, "", "0", "01", "-1", "1.5", "9223372036854775808"}) {
            fails("protocol", () -> new NeteaseLibraryApi(foreign).removeFromPlaylist(session, bad, "123"));
            fails("protocol", () -> new NeteaseLibraryApi(foreign).removeFromPlaylist(session, "42", bad));
        }

        NeteaseApi rejected = new NeteaseApi(request -> new NeteaseApi.Response(
                jUnchecked("{code:500}"), Collections.emptyMap()));
        fails("platform", () -> new NeteaseLibraryApi(rejected).removeFromPlaylist(session, "42", "123"));
    }

    @Test public void removeFromPlaylistRechecksCurrentAccountBeforeTheWrite() throws Exception {
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        final boolean[] current = {true};
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            // The account changes while the detail response is in flight.
            current[0] = false;
            return new NeteaseApi.Response(
                    jUnchecked("{code:200,playlist:{id:42,specialType:0,creator:{userId:7}}}"),
                    Collections.emptyMap());
        });
        fails("account_changed", () -> new NeteaseLibraryApi(api, () -> current[0])
                .removeFromPlaylist(session, "42", "123"));
        assertEquals(1, requests.size());
        assertEquals(NeteaseApi.PLAYLIST_DETAIL, requests.get(0).endpoint);

        // An already-replaced account never reaches the transport at all.
        final java.util.List<NeteaseApi.Request> untouched = new java.util.ArrayList<>();
        NeteaseApi idle = new NeteaseApi(request -> {
            untouched.add(request);
            return new NeteaseApi.Response(jUnchecked("{code:200}"), Collections.emptyMap());
        });
        fails("account_changed", () -> new NeteaseLibraryApi(idle, () -> false)
                .removeFromPlaylist(session, "42", "123"));
        assertTrue(untouched.isEmpty());
        fails("credentials", () -> new NeteaseLibraryApi(idle).removeFromPlaylist(null, "42", "123"));
    }

    @Test public void createPlaylistUsesPinnedWeapiThenReadsBackTheOwner() throws Exception {
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        final int[] call = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request); call[0]++;
            if (call[0] == 1) return new NeteaseApi.Response(
                    jUnchecked("{code:200,id:99,playlist:{id:99}}"), Collections.emptyMap());
            return new NeteaseApi.Response(
                    jUnchecked("{code:200,playlist:{id:99,name:'N',specialType:0,creator:{userId:7}}}"),
                    Collections.emptyMap());
        });
        assertEquals("99", new NeteaseLibraryApi(api).createPlaylist(session, "  我的歌单  "));
        assertEquals(2, requests.size());

        // Create itself is the pinned WEAPI profile, not EAPI. WEAPI uses a per-request random
        // secret, so the exact body fields are asserted on the payload builder instead.
        assertEquals(NeteaseApi.PLAYLIST_CREATE, requests.get(0).endpoint);
        NeteaseApi.requireEndpoint(new URL(requests.get(0).endpoint));
        assertTrue(requests.get(0).body.contains("encSecKey="));
        JSONObject create = NeteaseLibraryApi.createPlaylistPayload("  我的歌单  ");
        assertEquals("我的歌单", create.getString("name"));
        assertEquals("0", create.getString("privacy"));
        assertEquals("NORMAL", create.getString("type"));

        // The owner is confirmed through detail by the returned id, never by name.
        assertEquals(NeteaseApi.PLAYLIST_DETAIL, requests.get(1).endpoint);
        assertEquals("99", eapiPayload(requests.get(1), "/api/v6/playlist/detail").getString("id"));

        NeteaseApi foreign = new NeteaseApi(request -> new NeteaseApi.Response(
                jUnchecked("{code:200,playlist:{id:99,specialType:0,creator:{userId:8}}}"),
                Collections.emptyMap()));
        fails("permission", () -> new NeteaseLibraryApi(foreign).createPlaylist(session, "N"));

        NeteaseApi missingId = new NeteaseApi(request -> new NeteaseApi.Response(
                jUnchecked("{code:200}"), Collections.emptyMap()));
        fails("protocol", () -> new NeteaseLibraryApi(missingId).createPlaylist(session, "N"));

        NeteaseLibraryApi library = new NeteaseLibraryApi(foreign);
        fails("request", () -> library.createPlaylist(session, ""));
        fails("request", () -> library.createPlaylist(session, "   "));
        fails("request", () -> library.createPlaylist(session, null));
        fails("request", () -> library.createPlaylist(session, repeat("歌", 101)));
        fails("request", () -> library.createPlaylist(session, "bad\u0007name"));
        fails("request", () -> library.createPlaylist(session, "bad\nname"));
        assertEquals(repeat("歌", 100),
                NeteaseLibraryApi.createPlaylistPayload(repeat("歌", 100)).getString("name"));
        fails("credentials", () -> library.createPlaylist(null, "N"));
    }

    @Test public void renamePlaylistUsesEapiRejectsLikedAndReadsBackTheName() throws Exception {
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        final int[] call = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request); call[0]++;
            if (call[0] == 1) return new NeteaseApi.Response(
                    jUnchecked("{code:200,playlist:{id:42,name:'Old',specialType:0,creator:{userId:7}}}"),
                    Collections.emptyMap());
            if (call[0] == 2) return new NeteaseApi.Response(
                    jUnchecked("{code:200}"), Collections.emptyMap());
            return new NeteaseApi.Response(
                    jUnchecked("{code:200,playlist:{id:42,name:'New',specialType:0,creator:{userId:7}}}"),
                    Collections.emptyMap());
        });
        new NeteaseLibraryApi(api).renamePlaylist(session, "42", "New");
        assertEquals(3, requests.size());
        assertEquals(NeteaseApi.PLAYLIST_DETAIL, requests.get(0).endpoint);

        // The pinned rename module passes no profile, so the write is EAPI here.
        assertEquals(NeteaseApi.PLAYLIST_RENAME, requests.get(1).endpoint);
        NeteaseApi.requireEndpoint(new URL(requests.get(1).endpoint));
        assertFalse(requests.get(1).body.contains("encSecKey="));
        JSONObject rename = eapiPayload(requests.get(1), "/api/playlist/update/name");
        assertEquals("42", rename.getString("id"));
        assertEquals("New", rename.getString("name"));
        assertEquals(NeteaseApi.PLAYLIST_DETAIL, requests.get(2).endpoint);

        // Liked songs are never renamed.
        NeteaseApi liked = new NeteaseApi(request -> new NeteaseApi.Response(
                jUnchecked("{code:200,playlist:{id:43,name:'Liked',specialType:5,creator:{userId:7}}}"),
                Collections.emptyMap()));
        fails("permission", () -> new NeteaseLibraryApi(liked).renamePlaylist(session, "43", "New"));

        NeteaseApi foreign = new NeteaseApi(request -> new NeteaseApi.Response(
                jUnchecked("{code:200,playlist:{id:42,specialType:0,creator:{userId:8}}}"),
                Collections.emptyMap()));
        fails("permission", () -> new NeteaseLibraryApi(foreign).renamePlaylist(session, "42", "New"));

        // A write the server does not reflect is a protocol failure, not a silent success.
        final int[] stale = {0};
        NeteaseApi unchanged = new NeteaseApi(request -> {
            stale[0]++;
            return new NeteaseApi.Response(stale[0] == 2
                    ? jUnchecked("{code:200}")
                    : jUnchecked("{code:200,playlist:{id:42,name:'Old',specialType:0,creator:{userId:7}}}"),
                    Collections.emptyMap());
        });
        fails("protocol", () -> new NeteaseLibraryApi(unchanged).renamePlaylist(session, "42", "New"));

        NeteaseLibraryApi library = new NeteaseLibraryApi(foreign);
        fails("request", () -> library.renamePlaylist(session, "42", ""));
        fails("request", () -> library.renamePlaylist(session, "42", repeat("歌", 101)));
        fails("request", () -> library.renamePlaylist(session, "42", "bad\u0000name"));
        fails("protocol", () -> library.renamePlaylist(session, "0", "New"));
        fails("credentials", () -> library.renamePlaylist(null, "42", "New"));
    }

    @Test public void playlistWritesStopWhenTheAccountChangesMidCall() throws Exception {
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));

        // Create: the account switches as the create response lands, so the owner read-back
        // must not run against a replaced account.
        final java.util.List<NeteaseApi.Request> createRequests = new java.util.ArrayList<>();
        final boolean[] current = {true};
        NeteaseApi createApi = new NeteaseApi(request -> {
            createRequests.add(request);
            current[0] = false;
            return new NeteaseApi.Response(jUnchecked("{code:200,id:99}"), Collections.emptyMap());
        });
        fails("account_changed", () -> new NeteaseLibraryApi(createApi, () -> current[0])
                .createPlaylist(session, "N"));
        assertEquals(1, createRequests.size());
        assertEquals(NeteaseApi.PLAYLIST_CREATE, createRequests.get(0).endpoint);

        // Rename: the account switches after ownership is confirmed, before the write.
        final java.util.List<NeteaseApi.Request> renameRequests = new java.util.ArrayList<>();
        final boolean[] renameCurrent = {true};
        NeteaseApi renameApi = new NeteaseApi(request -> {
            renameRequests.add(request);
            renameCurrent[0] = false;
            return new NeteaseApi.Response(
                    jUnchecked("{code:200,playlist:{id:42,name:'Old',specialType:0,creator:{userId:7}}}"),
                    Collections.emptyMap());
        });
        fails("account_changed", () -> new NeteaseLibraryApi(renameApi, () -> renameCurrent[0])
                .renamePlaylist(session, "42", "New"));
        assertEquals(1, renameRequests.size());
        assertEquals(NeteaseApi.PLAYLIST_DETAIL, renameRequests.get(0).endpoint);

        // An already-replaced account never reaches the transport at all.
        final java.util.List<NeteaseApi.Request> untouched = new java.util.ArrayList<>();
        NeteaseApi idle = new NeteaseApi(request -> {
            untouched.add(request);
            return new NeteaseApi.Response(jUnchecked("{code:200,id:99}"), Collections.emptyMap());
        });
        fails("account_changed", () -> new NeteaseLibraryApi(idle, () -> false)
                .createPlaylist(session, "N"));
        fails("account_changed", () -> new NeteaseLibraryApi(idle, () -> false)
                .renamePlaylist(session, "42", "New"));
        assertTrue(untouched.isEmpty());
    }

    private static String repeat(String value, int count) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < count; i++) out.append(value);
        return out.toString();
    }

    @Test public void playlistIdsRequireCompleteMatchingIdentity() throws Exception {
        JSONObject body = j("{playlist:{id:42,trackCount:2,trackIds:[{id:1},{id:2}]}}");
        assertEquals(Arrays.asList("1", "2"), NeteaseLibraryApi.parseTrackIds(body,"42"));
        fails("identity", () -> NeteaseLibraryApi.parseTrackIds(body, "43"));
        fails("incomplete", () -> NeteaseLibraryApi.parseTrackIds(j("{playlist:{id:42,trackCount:2,trackIds:[{id:1}]}}"),"42"));
        fails("protocol", () -> NeteaseLibraryApi.parseTrackIds(j("{playlist:{id:42,trackCount:2,trackIds:[{id:1},{id:1}]}}"),"42"));
    }

    @Test public void songDetailsPreserveRequestedOrderAndMissingRows() throws Exception {
        JSONObject body = j("{songs:[{id:2,name:'Second',dt:2000,ar:[{name:'Artist'}],al:{name:'Album'}}]}");
        java.util.List<NeteaseLibraryApi.Track> tracks = NeteaseLibraryApi.parseTracks(body, Arrays.asList("1","2"));
        assertEquals("1",tracks.get(0).id);
        assertFalse(tracks.get(0).available);
        assertEquals("2",tracks.get(1).id);
        assertTrue(tracks.get(1).available);
        fails("identity", () -> NeteaseLibraryApi.parseTracks(body, Collections.singletonList("3")));
    }

    @Test public void legacySongNavigationIdsAreOptionalWithoutWeakeningSongIdentity() throws Exception {
        JSONObject legacy = j("{songs:[{id:2,name:'Legacy',dt:2000,"
                + "ar:[{id:0,name:'Artist'}],al:{id:0,name:'Album'}}]}");
        NeteaseLibraryApi.Track parsed = NeteaseLibraryApi.parseTracks(
                legacy, Collections.singletonList("2")).get(0);
        assertTrue(parsed.artistIds.isEmpty());
        assertNull(parsed.albumId);

        JSONObject partial = j("{songs:[{id:3,name:'Partial',dt:2000,"
                + "ar:[{id:11,name:'Artist'},{name:'Unknown'}],al:{name:'Album'}}]}");
        NeteaseLibraryApi.Track missing = NeteaseLibraryApi.parseTracks(
                partial, Collections.singletonList("3")).get(0);
        assertTrue(missing.artistIds.isEmpty());
        assertNull(missing.albumId);

        JSONObject valid = j("{songs:[{id:4,name:'Valid',dt:2000,"
                + "ar:[{id:12,name:'Artist'}],al:{id:13,name:'Album'}}]}");
        NeteaseLibraryApi.Track retained = NeteaseLibraryApi.parseTracks(
                valid, Collections.singletonList("4")).get(0);
        assertEquals(Collections.singletonList("12"), retained.artistIds);
        assertEquals("13", retained.albumId);

        JSONObject invalidKey = j("{songs:[{id:0,name:'Bad key',dt:2000,ar:[],al:{name:'Album'}}]}");
        fails("protocol", () -> NeteaseLibraryApi.parseTracks(
                invalidKey, Collections.singletonList("2")));

        JSONObject wrongArtist = j("{more:false,total:1,songs:[{id:5,name:'Song',dt:2000,"
                + "ar:[{id:13,name:'Other'}],al:{name:'Album'}}]}");
        fails("identity", () -> NeteaseLibraryApi.parseArtistTracks(wrongArtist, "12", 0, 30));
        JSONObject wrongAlbum = j("{album:{id:13},songs:[{id:6,name:'Song',dt:2000,"
                + "ar:[],al:{id:14,name:'Other'}}]}");
        fails("identity", () -> NeteaseLibraryApi.parseAlbumTracks(wrongAlbum, "13", 0, 30));
    }

    @Test public void searchUsesFixedFirstPageAndReturnsMetadataInSearchOrder() throws Exception {
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        JSONObject fixture = j("{code:200,result:{songCount:12,songs:["
                + "{id:2,name:'Second',dt:2000,ar:[{name:'Artist'}],al:{name:'Album'}},"
                + "{id:1,name:'First',dt:1000,ar:[],al:{name:'Other'}}]}}");
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(fixture, Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        java.util.List<NeteaseLibraryApi.Track> tracks = new NeteaseLibraryApi(api).search(session, "  中\u0001文\n  ");
        assertEquals(1, requests.size());
        NeteaseApi.Request request = requests.get(0);
        assertEquals(NeteaseApi.SEARCH, request.endpoint);
        NeteaseApi.requireEndpoint(new URL(request.endpoint));
        JSONObject payload = searchPayload(request);
        assertEquals("中文", payload.getString("s"));
        assertEquals(1, payload.getInt("type"));
        assertEquals(10, payload.getInt("limit"));
        assertEquals(0, payload.getInt("offset"));
        assertTrue(payload.getBoolean("total"));
        assertFalse(payload.getBoolean("e_r"));
        assertEquals(2, tracks.size());
        assertEquals("2", tracks.get(0).id);
        assertEquals("Second", tracks.get(0).name);
        assertEquals(Collections.singletonList("Artist"), tracks.get(0).artists);
        assertEquals("Album", tracks.get(0).album);
        assertEquals(2000, tracks.get(0).durationMs);
        assertEquals("1", tracks.get(1).id);
        try { tracks.clear(); fail("Must be immutable"); } catch (UnsupportedOperationException expected) { }
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(request.endpoint + "?s=secret")));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL("https://interfacepc.music.163.com/eapi/cloudsearch/other")));
    }

    @Test public void searchRejectsMalformedAndOversizedResultsAndAcceptsEmptyResult() throws Exception {
        assertTrue(NeteaseLibraryApi.parseSearch(j("{result:{songCount:0}}")).isEmpty());
        assertTrue(NeteaseLibraryApi.parseSearch(j("{result:{songCount:0,songs:[]}}")).isEmpty());
        for (String text : new String[]{"{}", "{result:[]}", "{result:{songCount:'0'}}",
                "{result:{songCount:1}}", "{result:{songCount:1,songs:[]}}",
                "{result:{songCount:0,songs:null}}", "{result:{songCount:1,songs:[null]}}",
                "{result:{songCount:1,songs:[{id:1,name:'secret',dt:1000,ar:[],al:null}]}}"})
            fails("protocol", () -> NeteaseLibraryApi.parseSearch(j(text)));
        JSONArray rows = new JSONArray();
        for (int i = 1; i <= 10; i++) rows.put(j("{id:" + i + ",name:'Song',dt:1000,ar:[],al:{name:'Album'}}"));
        JSONObject result = new JSONObject().put("songCount", 100).put("songs", rows);
        JSONObject body = new JSONObject().put("result", result);
        assertEquals(10, NeteaseLibraryApi.parseSearch(body).size());
        rows.put(rows.getJSONObject(0));
        fails("limit", () -> NeteaseLibraryApi.parseSearch(body));
        JSONArray duplicates = new JSONArray().put(rows.getJSONObject(0)).put(rows.getJSONObject(0));
        result.put("songs", duplicates);
        fails("identity", () -> NeteaseLibraryApi.parseSearch(body));
    }

    @Test public void searchBoundsAreCheckedBeforeTransportAndSingleSongNeverRequestsAudio() throws Exception {
        final java.util.List<String> endpoints = new java.util.ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            endpoints.add(request.endpoint);
            return new NeteaseApi.Response(jUnchecked(request.endpoint.equals(NeteaseApi.SEARCH)
                    ? "{code:200,result:{songCount:0}}"
                    : "{code:200,songs:[{id:1,name:'Song',dt:1000,ar:[],al:{name:'Album'}}]}"), Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        char[] chars = new char[128]; Arrays.fill(chars, 'x');
        String max = new String(chars);
        for (String query : new String[]{null, "", " \t\n\u0085", max + "x"})
            fails("request", () -> library.search(session, query));
        assertTrue(endpoints.isEmpty());
        assertTrue(library.search(session, max).isEmpty());
        NeteaseLibraryApi.Track track = library.song(session, "1");
        assertEquals("1", track.id);
        assertEquals("Song", track.name);
        assertEquals(Arrays.asList(NeteaseApi.SEARCH, NeteaseApi.SONG_DETAIL), endpoints);
    }

    private static JSONObject searchPayload(NeteaseApi.Request request) throws Exception {
        return eapiPayload(request, "/api/cloudsearch/pc");
    }

    private static JSONObject confirmedAudio(NeteaseApi.Request request) throws java.io.IOException {
        try {
            JSONObject payload=eapiPayload(request, "/api/song/enhance/player/url/v1");
            String level=payload.getString("level");
            switch(level) {
                case "standard": return audioAt(128000,false,level);
                case "higher": return audioAt(192000,false,level);
                case "exhigh": return audioAt(192000,false,level);
                case "lossless": return audioAt(999000,false,level);
                case "hires": {
                    JSONObject body=audioAt(4616000,false,level);
                    body.getJSONArray("data").getJSONObject(0)
                        .put("sampleRate",96000).put("bitDepth",24);
                    return body;
                }
                default: throw new AssertionError("Unexpected level");
            }
        } catch (Exception failure) { throw new java.io.IOException(failure); }
    }

    private static JSONObject exactHigherAudio(NeteaseApi.Request request) throws java.io.IOException {
        try {
            JSONObject payload=eapiPayload(request, "/api/song/enhance/player/url/v1");
            assertEquals("higher",payload.getString("level"));
            assertEquals("flac",payload.getString("encodeType"));
            return audioAt(192000,false,"higher");
        } catch (Exception failure) { throw new java.io.IOException(failure); }
    }

    private static String requestedLevel(NeteaseApi.Request request) throws java.io.IOException {
        try {
            return eapiPayload(request, "/api/song/enhance/player/url/v1").getString("level");
        } catch (Exception failure) { throw new java.io.IOException(failure); }
    }

    private static JSONObject eapiPayload(NeteaseApi.Request request, String uri) throws Exception {
        String hex = java.net.URLDecoder.decode(request.body.substring("params=".length()), "UTF-8");
        byte[] encrypted = new byte[hex.length() / 2];
        for (int i = 0; i < encrypted.length; i++) encrypted[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/ECB/PKCS5Padding");
        cipher.init(javax.crypto.Cipher.DECRYPT_MODE, new javax.crypto.spec.SecretKeySpec(
                "e82ckenh8dichen8".getBytes(java.nio.charset.StandardCharsets.US_ASCII), "AES"));
        String[] payload = new String(cipher.doFinal(encrypted), java.nio.charset.StandardCharsets.UTF_8).split("-36cd479b6b5-", -1);
        assertEquals(uri, payload[0]);
        return j(payload[1]);
    }

    private static JSONObject audio() throws Exception {
        return j("{data:[{id:1,code:200,url:'https://m7.music.126.net/path/secret.mp3',br:128000,expi:120,freeTrialInfo:null}]}");
    }
    private static JSONObject audioAt(int bitrate,boolean trial,String level) throws Exception {
        JSONObject body=audio();
        body.put("code",200);
        JSONObject row=body.getJSONArray("data").getJSONObject(0)
            .put("br",bitrate).put("type",bitrate>320000?"flac":"mp3").put("level",level);
        if(trial)row.put("freeTrialInfo",new JSONObject());
        return body;
    }
    @Test public void audioIsIdentityBoundAndRejectsTrialAndSpoofedHosts() throws Exception {
        NeteaseLibraryApi.Audio result = NeteaseLibraryApi.parseAudio(audio(),"1",1000);
        assertEquals(121000,result.expiresAtElapsedMs);
        assertEquals("netease:standard",result.qualityId);
        JSONObject lossless=audio();lossless.getJSONArray("data").getJSONObject(0)
            .put("br",999000).put("type","flac").put("level","lossless");
        assertEquals("netease:lossless",NeteaseLibraryApi.parseAudio(lossless,"1",1000,"netease:lossless").qualityId);
        fails("unavailable", () -> NeteaseLibraryApi.parseAudio(lossless,"1",1000,"netease:standard"));
        JSONObject mislabeled=audioAt(400000,false,"lossless");mislabeled.getJSONArray("data").getJSONObject(0).put("type","mp3");
        fails("unavailable", () -> NeteaseLibraryApi.parseAudio(mislabeled,"1",1000,"netease:lossless"));
        fails("identity", () -> NeteaseLibraryApi.parseAudio(audio(),"2",1000));
        JSONObject trial=audio();trial.getJSONArray("data").getJSONObject(0).put("freeTrialInfo",new JSONObject());
        fails("preview", () -> NeteaseLibraryApi.parseAudio(trial,"1",1000));
        for (String url : new String[]{"https://m7.music.126.net.evil.test/a", "https://user@music.126.net/a", "file:///a", "https://127.0.0.1/a", "https://m7.music.126.net:8080/a", "https://m7.music.126.net:/a", "https://m7.music.126.net:0443/a"}) {
            JSONObject bad=audio();bad.getJSONArray("data").getJSONObject(0).put("url",url);
            fails("endpoint", () -> NeteaseLibraryApi.parseAudio(bad,"1",1000));
        }
        JSONObject cleartext=audio();cleartext.getJSONArray("data").getJSONObject(0).put("url","http://m7.music.126.net/path");
        assertEquals("https://m7.music.126.net/path",NeteaseLibraryApi.parseAudio(cleartext,"1",0).url);
        JSONObject unavailable=audio();unavailable.getJSONArray("data").getJSONObject(0).put("url",JSONObject.NULL);
        fails("unavailable", () -> NeteaseLibraryApi.parseAudio(unavailable,"1",1000));
    }
    @Test public void qualitiesUseOnlyConfirmedCompleteAudioForThisTrack() throws Exception {
        final int[] calls={0};
        NeteaseApi api=new NeteaseApi(request -> {
            calls[0]++;
            return new NeteaseApi.Response(confirmedAudio(request),Collections.emptyMap());
        });
        NeteaseApi.Session session=NeteaseApi.Session.fromJson(j(
            "{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        java.util.List<NeteaseLibraryApi.AudioQuality> qualities=
            new NeteaseLibraryApi(api).qualities(session,"1",1000);
        assertEquals(5,calls[0]);
        assertEquals(Arrays.asList("netease:standard","netease:higher","netease:exhigh","netease:lossless","netease:hires"),
            Arrays.asList(qualities.get(0).id,qualities.get(1).id,qualities.get(2).id,qualities.get(3).id,qualities.get(4).id));
        assertEquals(5,qualities.size());
        try{qualities.clear();fail("Quality options must be read-only");}catch(UnsupportedOperationException expected){}
    }
    @Test public void qualitiesDoNotDowngradeANetworkFailure() throws Exception {
        final int[] calls={0};
        NeteaseApi api=new NeteaseApi(request -> {
            calls[0]++;
            if("higher".equals(requestedLevel(request)))
                throw new java.io.IOException("fixture");
            return new NeteaseApi.Response(confirmedAudio(request),Collections.emptyMap());
        });
        NeteaseApi.Session session=NeteaseApi.Session.fromJson(j(
            "{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        fails("network", () -> new NeteaseLibraryApi(api).qualities(session,"1",1000));
        assertEquals(2,calls[0]);
    }
    @Test public void hiresRequiresItsActualLevelAndSamplingEvidence() throws Exception {
        JSONObject hires=audioAt(4616000,false,"hires");
        hires.getJSONArray("data").getJSONObject(0)
            .put("sampleRate",96000).put("bitDepth",24);
        NeteaseLibraryApi.Audio result=
            NeteaseLibraryApi.parseAudio(hires,"1",1000,"netease:hires");
        assertEquals("netease:hires",result.qualityId);
        assertEquals("netease:hires",NeteaseLibraryApi.parseAudio(
            audioAt(4616000,false,"hires"),"1",1000,"netease:hires").qualityId);

        JSONObject ordinaryLossless=audioAt(999000,false,"lossless");
        ordinaryLossless.getJSONArray("data").getJSONObject(0)
            .put("sampleRate",44100).put("bitDepth",16);
        NeteaseLibraryApi.Audio downgraded=
            NeteaseLibraryApi.parseAudio(ordinaryLossless,"1",1000,"netease:hires");
        assertEquals("netease:lossless",downgraded.qualityId);

        JSONObject downsampled=audioAt(2308000,false,"hires");
        downsampled.getJSONArray("data").getJSONObject(0)
            .put("sampleRate",44100).put("bitDepth",16);
        assertEquals("netease:lossless",
            NeteaseLibraryApi.parseAudio(downsampled,"1",1000,"netease:hires").qualityId);

        JSONObject plainFlac=audio();plainFlac.getJSONArray("data").getJSONObject(0)
            .put("br",4616000).put("type","flac");
        assertEquals("netease:lossless",
            NeteaseLibraryApi.parseAudio(plainFlac,"1",1000,"netease:lossless").qualityId);

        fails("unavailable", () -> NeteaseLibraryApi.parseAudio(
            hires,"1",1000,"netease:lossless"));
    }
    @Test public void hiresRequestUsesV1LevelAndRejectsMislabeledFormats() throws Exception {
        final java.util.List<NeteaseApi.Request> requests=new java.util.ArrayList<>();
        NeteaseApi api=new NeteaseApi(request -> {
            try {
                requests.add(request);
                JSONObject payload=eapiPayload(request, "/api/song/enhance/player/url/v1");
                assertEquals("hires",payload.getString("level"));
                assertEquals("flac",payload.getString("encodeType"));
                return new NeteaseApi.Response(
                    audioAt(4616000,false,"hires"),Collections.emptyMap());
            } catch (Exception failure) {
                throw new java.io.IOException(failure);
            }
        });
        NeteaseApi.Session session=NeteaseApi.Session.fromJson(j(
            "{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        NeteaseLibraryApi.Audio audio=
            new NeteaseLibraryApi(api).audio(session,"1",1000,"netease:hires");
        assertEquals("netease:hires",audio.qualityId);
        assertEquals(1,requests.size());

        JSONObject mp3=audioAt(320000,false,"hires");
        mp3.getJSONArray("data").getJSONObject(0)
            .put("sampleRate",96000).put("bitDepth",24);
        fails("unavailable", () -> NeteaseLibraryApi.parseAudio(
            mp3,"1",1000,"netease:hires"));
    }
    @Test public void exactAudioRequestReturnsPlatformActualQualityId() throws Exception {
        final java.util.List<NeteaseApi.Request> requests=new java.util.ArrayList<>();
        NeteaseApi api=new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(exactHigherAudio(request),Collections.emptyMap());
        });
        NeteaseApi.Session session=NeteaseApi.Session.fromJson(j(
            "{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        NeteaseLibraryApi.Audio audio=
            new NeteaseLibraryApi(api).audio(session,"1",1000,"netease:higher");
        assertEquals("netease:higher",audio.qualityId);
        assertEquals(1,requests.size());
    }

    @Test public void authenticatedReadOnlyCallsUseFixedTransportAndHideCredentials() throws Exception {
        final String[] endpoint={null};final String[] cookie={null};
        final JSONObject fixture=j("{code:200,playlist:[],more:false}");
        NeteaseApi api=new NeteaseApi(request -> {
            endpoint[0]=request.endpoint;cookie[0]=request.headers.get("Cookie");
            return new NeteaseApi.Response(fixture,Collections.emptyMap());
        });
        NeteaseApi.Session session=NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'private-secret',__csrf:'csrf'}}"));
        assertTrue(new NeteaseLibraryApi(api).playlists(session,0,30).items.isEmpty());
        assertEquals(NeteaseApi.USER_PLAYLIST,endpoint[0]);
        assertTrue(cookie[0].contains("MUSIC_U=private-secret"));
        fails("endpoint", () -> api.authenticatedCall(NeteaseApi.STATUS,new JSONObject(),session));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL("https://music.163.com/weapi/user/playlist?redirect=secret")));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL("https://evil.test/weapi/user/playlist")));
    }

    @Test public void fullQueueIdsAreNotLimitedToVisibleSongPageOrEagerStreams() throws Exception {
        JSONArray ids = new JSONArray();
        for (int i = 1; i <= 101; i++) ids.put(new JSONObject().put("id", i));
        JSONObject detail = new JSONObject().put("code", 200).put("playlist",
                new JSONObject().put("id", 42).put("trackCount", 101).put("trackIds", ids));
        final int[] calls = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            calls[0]++;
            assertEquals(NeteaseApi.PLAYLIST_DETAIL, request.endpoint);
            return new NeteaseApi.Response(detail, Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        java.util.List<String> snapshot = new NeteaseLibraryApi(api).trackIds(session, "42");
        assertEquals(101, snapshot.size());
        assertEquals("101", snapshot.get(100));
        assertEquals(1, calls[0]);
        try { snapshot.clear(); fail("Must be immutable"); } catch (UnsupportedOperationException expected) { }
    }

    @Test public void selectedSongDetailsRejectMissingSongBeforeAudioResolution() throws Exception {
        final int[] calls = {0};
        NeteaseApi api = new NeteaseApi(request -> {
            calls[0]++;
            assertEquals(NeteaseApi.SONG_DETAIL, request.endpoint);
            return new NeteaseApi.Response(jUnchecked("{code:200,songs:[]}"), Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        fails("unavailable", () -> new NeteaseLibraryApi(api).song(session, "1"));
        assertEquals(1, calls[0]);
    }
    private static JSONObject searchPage(int offset, int count, int total) throws Exception {
        JSONArray rows = new JSONArray();
        for (int i = 1; i <= count; i++) rows.put(j("{id:" + (offset + i) +
                ",name:'Song',dt:1000,ar:[],al:{name:'Album',picUrl:'http://p1.music.126.net/public.jpg'}}"));
        return new JSONObject().put("code", 200).put("result", new JSONObject().put("songCount", total).put("songs", rows));
    }

    @Test public void pagedSearchSendsOffsetAndLimitAndPreservesFinalTotal() throws Exception {
        java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        JSONObject fixture = searchPage(30, 2, 32);
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request); return new NeteaseApi.Response(fixture, Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        NeteaseLibraryApi.Page<NeteaseLibraryApi.Track> page = library.search(session, "Song", 30, 30);
        JSONObject payload = searchPayload(requests.get(0));
        assertEquals(30, payload.getInt("offset")); assertEquals(30, payload.getInt("limit"));
        assertEquals(32, page.total); assertEquals(-1, page.nextOffset); assertEquals("31", page.items.get(0).id);
        assertEquals("https://p1.music.126.net/public.jpg", page.items.get(0).coverUrl);
        for (int offset : new int[]{-30, 1, 20_010}) fails("limit", () -> library.search(session, "Song", offset, 30));
        fails("limit", () -> library.search(session, "Song", 0, 0));
        assertEquals(1, requests.size());
    }

    @Test public void pagedSearchValidatesCountsAndNeverFakesCompletionAtTheBound() throws Exception {
        assertEquals(30, NeteaseLibraryApi.parseSearch(searchPage(0, 30, 100_000), 0, 30).nextOffset);
        assertEquals(-1, NeteaseLibraryApi.parseSearch(searchPage(30, 0, 30), 30, 30).nextOffset);
        fails("incomplete", () -> NeteaseLibraryApi.parseSearch(searchPage(30, 1, 100), 30, 30));
        fails("incomplete", () -> NeteaseLibraryApi.parseSearch(searchPage(30, 2, 31), 30, 30));
        fails("incomplete", () -> NeteaseLibraryApi.parseSearch(searchPage(30, 0, 31), 30, 30));
        fails("limit", () -> NeteaseLibraryApi.parseSearch(searchPage(0, 31, 100), 0, 30));
        assertEquals(-1, NeteaseLibraryApi.parseSearch(searchPage(19_980, 20, 20_000), 19_980, 30).nextOffset);
        fails("limit", () -> NeteaseLibraryApi.parseSearch(searchPage(19_980, 30, 100_000), 19_980, 30));
        fails("limit", () -> NeteaseLibraryApi.parseSearch(searchPage(19_980, 1, 100_000), 19_980, 30));
        fails("limit", () -> NeteaseLibraryApi.parseSearch(searchPage(19_980, 21, 20_001), 19_980, 30));
    }

    @Test public void artworkUsesOnlyPublicImageFieldsAndKeepsOldConstructors() throws Exception {
        JSONObject body = searchPage(0, 1, 1);
        JSONObject album = body.getJSONObject("result").getJSONArray("songs").getJSONObject(0).getJSONObject("al");
        for (String url : new String[]{"https://p1.music.126.net/a?token=fixture", "https://p1.music.126.net/a#f",
                "https://user@p1.music.126.net/a", "https://p1.music.126.net:443/a", "https://p1.music.126.net.evil/a",
                "https://m7.music.126.net/audio.mp3"}) {
            album.put("picUrl", url);
            assertNull(NeteaseLibraryApi.parseSearch(body, 0, 30).items.get(0).coverUrl);
        }
        album.remove("picUrl");
        assertNull(NeteaseLibraryApi.parseSearch(body, 0, 30).items.get(0).coverUrl);
        assertNull(new NeteaseLibraryApi.Track("1", "Song", "Album", Collections.emptyList(), 0, true).coverUrl);
        JSONObject playlist = j("{playlist:[{id:1,name:'List',trackCount:0,coverImgUrl:'http://p9.music.126.net/list.jpg'}],more:false}");
        assertEquals("https://p9.music.126.net/list.jpg", NeteaseLibraryApi.parsePlaylists(playlist, 0, 30).items.get(0).coverUrl);
    }

    @Test public void lyricsDocumentUsesOneStrictEapiRequestAndLegacyLyricsCompatibility() throws Exception {
        final String standard = "[00:01.00]First\n[00:02.50]Second";
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(jUnchecked("{code:200,lrc:{lyric:'" +
                    standard.replace("\n", "\\n") + "'}}"), Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'fixture',__csrf:'csrf'}}"));
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        NeteaseLibraryApi.LyricsDocument document = library.lyricsDocument(session, "12345");
        assertEquals(standard, document.original);
        assertNull(document.translation);
        assertEquals(NeteaseLibraryApi.TranslationStatus.ABSENT, document.translationStatus);
        assertEquals(1, requests.size());
        requireLyricPayload(requests.get(0));
        requests.clear();
        assertEquals(standard, library.lyrics(session, "12345"));
        assertEquals(1, requests.size());
        requireLyricPayload(requests.get(0));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(NeteaseApi.LYRIC + "?id=private")));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(
                "https://interfacepc.music.163.com/eapi/song/lyric/other")));

        assertEquals("", NeteaseLibraryApi.parseLyrics(j("{code:200,nolyric:true}")));
        assertEquals("", NeteaseLibraryApi.parseLyrics(j("{code:200,uncollected:true}")));
        assertEquals("", NeteaseLibraryApi.parseLyrics(j("{code:200,lrc:{lyric:''}}")));
        for (String malformed : new String[]{"{code:201,lrc:{lyric:'secret'}}",
                "{code:200,nolyric:'true'}", "{code:200,lrc:{lyric:3}}",
                "{code:200,lrc:'secret'}", "{code:200}"})
            fails("protocol", () -> NeteaseLibraryApi.parseLyrics(j(malformed)));
        char[] oversized = new char[512 * 1024 + 1];
        Arrays.fill(oversized, 'x');
        JSONObject tooLarge = new JSONObject().put("code", 200)
                .put("lrc", new JSONObject().put("lyric", new String(oversized)));
        fails("limit", () -> NeteaseLibraryApi.parseLyrics(tooLarge));
    }

    @Test public void lyricsDocumentAcceptsValidTranslationWithoutAlignment() throws Exception {
        String original = "[00:01.00]Original\n[00:02.00]Text";
        String translation = "[00:01.00]译文\n[00:02.00]文字";
        NeteaseLibraryApi.LyricsDocument document = NeteaseLibraryApi.parseLyricsDocument(
                new JSONObject().put("code", 200)
                        .put("lrc", new JSONObject().put("lyric", original))
                        .put("tlyric", new JSONObject().put("lyric", translation)));
        assertEquals(original, document.original);
        assertEquals(translation, document.translation);
        assertEquals(NeteaseLibraryApi.TranslationStatus.PRESENT, document.translationStatus);
    }

    @Test public void lyricsDocumentTreatsMissingNullEmptyAndBlankTranslationAsAbsent() throws Exception {
        String original = "[00:01.00]Original";
        JSONObject[] bodies = {
                new JSONObject().put("code", 200).put("lrc", new JSONObject().put("lyric", original)),
                new JSONObject().put("code", 200).put("lrc", new JSONObject().put("lyric", original))
                        .put("tlyric", JSONObject.NULL),
                new JSONObject().put("code", 200).put("lrc", new JSONObject().put("lyric", original))
                        .put("tlyric", new JSONObject()),
                new JSONObject().put("code", 200).put("lrc", new JSONObject().put("lyric", original))
                        .put("tlyric", new JSONObject().put("lyric", JSONObject.NULL)),
                new JSONObject().put("code", 200).put("lrc", new JSONObject().put("lyric", original))
                        .put("tlyric", new JSONObject().put("lyric", " \n\t\u3000"))
        };
        for (JSONObject body : bodies) {
            NeteaseLibraryApi.LyricsDocument document = NeteaseLibraryApi.parseLyricsDocument(body);
            assertEquals(original, document.original);
            assertNull(document.translation);
            assertEquals(NeteaseLibraryApi.TranslationStatus.ABSENT, document.translationStatus);
        }
        NeteaseLibraryApi.LyricsDocument noLyrics = NeteaseLibraryApi.parseLyricsDocument(
                new JSONObject().put("code", 200).put("nolyric", true)
                        .put("tlyric", new JSONObject().put("lyric", "translation")));
        assertEquals("", noLyrics.original);
        assertNull(noLyrics.translation);
        assertEquals(NeteaseLibraryApi.TranslationStatus.ABSENT, noLyrics.translationStatus);
    }

    @Test public void lyricsDocumentDegradesInvalidTranslationButKeepsValidOriginal() throws Exception {
        String original = "[00:01.00]Original";
        char[] oversized = new char[256 * 1024 + 1];
        Arrays.fill(oversized, 'x');
        Object[] translations = {
                "not-an-object", new JSONArray().put("translation"),
                new JSONObject().put("lyric", 3),
                new JSONObject().put("lyric", "line" + (char) 0 + "text"),
                new JSONObject().put("lyric", "line" + (char) 1 + "text"),
                new JSONObject().put("lyric", String.valueOf((char) 0xd800)),
                new JSONObject().put("lyric", String.valueOf((char) 0xdc00)),
                new JSONObject().put("lyric", new String(oversized))
        };
        for (Object translation : translations) {
            NeteaseLibraryApi.LyricsDocument document = NeteaseLibraryApi.parseLyricsDocument(
                    new JSONObject().put("code", 200)
                            .put("lrc", new JSONObject().put("lyric", original))
                            .put("tlyric", translation));
            assertEquals(original, document.original);
            assertNull(document.translation);
            assertEquals(NeteaseLibraryApi.TranslationStatus.INVALID, document.translationStatus);
        }
    }

    @Test public void lyricsDocumentRejectsBrokenOrOversizedOriginalDespiteTranslation() throws Exception {
        JSONObject validTranslation = new JSONObject().put("lyric", "translation");
        fails("protocol", () -> NeteaseLibraryApi.parseLyricsDocument(j("{code:200,lrc:'secret'}")));
        fails("protocol", () -> NeteaseLibraryApi.parseLyricsDocument(
                new JSONObject().put("code", 200).put("lrc", new JSONObject().put("lyric", 3))
                        .put("tlyric", validTranslation)));
        char[] oversized = new char[512 * 1024 + 1];
        Arrays.fill(oversized, 'x');
        fails("limit", () -> NeteaseLibraryApi.parseLyricsDocument(
                new JSONObject().put("code", 200)
                        .put("lrc", new JSONObject().put("lyric", new String(oversized)))
                        .put("tlyric", validTranslation)));
    }

    @Test public void lyricsDocumentToStringIsFixedAndRedacted() throws Exception {
        String original = "private original";
        String translation = "private translation";
        NeteaseLibraryApi.LyricsDocument document = NeteaseLibraryApi.parseLyricsDocument(
                new JSONObject().put("code", 200)
                        .put("lrc", new JSONObject().put("lyric", original))
                        .put("tlyric", new JSONObject().put("lyric", translation)));
        assertEquals("LyricsDocument{redacted}", document.toString());
        assertFalse(document.toString().contains(original));
        assertFalse(document.toString().contains(translation));
    }

    @Test public void likedIdsUsesSessionUidAndStrictBoundedUniqueParsing() throws Exception {
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        JSONObject fixture = j("{code:200,ids:[1,'2',9007199254740991]}");
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(fixture, Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));
        java.util.List<String> ids = new NeteaseLibraryApi(api).likedIds(session);
        assertEquals(Arrays.asList("1", "2", "9007199254740991"), ids);
        assertEquals(1, requests.size());
        NeteaseApi.Request request = requests.get(0);
        assertEquals(NeteaseApi.LIKED_IDS, request.endpoint);
        NeteaseApi.requireEndpoint(new URL(request.endpoint));
        JSONObject payload = eapiPayload(request, "/api/song/like/get");
        assertEquals("7", payload.getString("uid"));
        assertFalse(payload.getBoolean("e_r"));
        try { ids.clear(); fail("Must be immutable"); } catch (UnsupportedOperationException expected) { }
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(NeteaseApi.LIKED_IDS + "?uid=8")));

        assertEquals(Collections.emptyList(), NeteaseLibraryApi.parseLikedIds(j("{code:200,ids:[]}")));
        for (String malformed : new String[]{"{}", "{code:200}", "{code:200,ids:null}",
                "{code:200,ids:[0]}", "{code:200,ids:[-1]}", "{code:200,ids:[1.5]}",
                "{code:200,ids:['01']}", "{code:200,ids:[1,1]}"})
            fails("protocol", () -> NeteaseLibraryApi.parseLikedIds(j(malformed)));
        fails("protocol", () -> NeteaseLibraryApi.parseLikedIds(j("{code:201,ids:[1]}")));
        JSONArray tooMany = new JSONArray();
        for (int i = 1; i <= 20_001; i++) tooMany.put(i);
        fails("limit", () -> NeteaseLibraryApi.parseLikedIds(
                new JSONObject().put("code", 200).put("ids", tooMany)));
    }

    @Test public void setLikedSubmitsExactPinnedPayloadOnceAndRequiresSuccess() throws Exception {
        final java.util.List<NeteaseApi.Request> requests = new java.util.ArrayList<>();
        NeteaseApi api = new NeteaseApi(request -> {
            requests.add(request);
            return new NeteaseApi.Response(jUnchecked("{code:200}"), Collections.emptyMap());
        });
        NeteaseApi.Session session = NeteaseApi.Session.fromJson(
                j("{userId:'7',nickname:'User',cookies:{MUSIC_U:'secret',__csrf:'csrf'}}"));
        NeteaseLibraryApi library = new NeteaseLibraryApi(api);
        library.setLiked(session, "123", true);
        library.setLiked(session, "456", false);
        assertEquals(2, requests.size());
        assertEquals(NeteaseApi.SET_LIKE, requests.get(0).endpoint);
        NeteaseApi.requireEndpoint(new URL(requests.get(0).endpoint));
        JSONObject liked = eapiPayload(requests.get(0), "/api/radio/like");
        assertEquals("itembased", liked.getString("alg"));
        assertEquals("123", liked.getString("trackId"));
        assertTrue(liked.getBoolean("like"));
        assertEquals("3", liked.getString("time"));
        assertFalse(liked.getBoolean("e_r"));
        JSONObject unliked = eapiPayload(requests.get(1), "/api/radio/like");
        assertEquals("456", unliked.getString("trackId"));
        assertFalse(unliked.getBoolean("like"));
        fails("endpoint", () -> NeteaseApi.requireEndpoint(new URL(NeteaseApi.SET_LIKE + "?id=1")));
        for (String bad : new String[]{null, "", "0", "01", "-1", "1.5", "9223372036854775808"})
            fails("protocol", () -> library.setLiked(session, bad, true));

        NeteaseApi rejected = new NeteaseApi(request ->
                new NeteaseApi.Response(jUnchecked("{code:500}"), Collections.emptyMap()));
        fails("platform", () -> new NeteaseLibraryApi(rejected).setLiked(session, "1", true));
    }

    private static JSONObject jUnchecked(String text) {
        try { return new JSONObject(text); } catch (org.json.JSONException e) { throw new AssertionError(e); }
    }
    private static void requireLyricPayload(NeteaseApi.Request request) throws Exception {
        assertEquals(NeteaseApi.LYRIC, request.endpoint);
        JSONObject payload = eapiPayload(request, "/api/song/lyric");
        assertEquals("12345", payload.getString("id"));
        assertEquals(-1, payload.getInt("tv"));
        assertEquals(-1, payload.getInt("lv"));
        assertEquals(-1, payload.getInt("rv"));
        assertEquals(-1, payload.getInt("kv"));
        assertEquals(1, payload.getInt("_nmclfl"));
    }
}
