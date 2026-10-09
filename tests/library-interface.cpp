#include "../src/application/application.cpp"
#include <iostream>
#include <stdexcept>
void require(bool value,const char* message) {if(!value) throw std::runtime_error(message);}
class SwitchAudio final:public musxi::IAudioBackend {
public:
    musxi::AudioState state;
    bool failNext=false;
    musxi::AudioResult load(const std::wstring&) override {state.opened=true;state.pending=true;state.phase="loading";return {};}
    musxi::AudioResult play() override {state.requestedPlaying=state.playing=true;return {};}
    musxi::AudioResult pause() override {state.requestedPlaying=state.playing=false;return {};}
    musxi::AudioResult resume() override {return play();}
    musxi::AudioResult stop() override {return pause();}
    musxi::AudioResult seek(std::uint32_t position) override {state.positionMs=position;return {};}
    musxi::AudioResult setVolume(int value) override {state.volumePercent=value;return {};}
    musxi::AudioResult unload() override {state={};return {};}
    musxi::AudioState snapshot() const override {return state;}
    void poll() override {state.pending=false;state.phase=failNext?"failed":state.requestedPlaying?"playing":"paused";failNext=false;}
    std::vector<musxi::AudioEvent> takeEvents() override {return {};}
};
int main() try {
    const auto productionData=cloud::dataDir();
    cloud::testProfile=true;
    require(cloud::dataDir()!=productionData && cloud::dataDir().filename()==L"MusxiPlayer-Test","test profile shares production storage");
    cloud::testProfile=false;
    (void)playerService();
    auto invoke=[](const char* name,const Json& p=Json::object()) {return musxi::applicationLibrary(name,p.dump());};
    require(invoke("library.getPlaybackSettings",{{"extra",true}}).code==400,"extra playback settings parameters accepted");
    require(invoke("library.setPlaybackSettings",{{"defaultQuality","invalid"},{"outputDevice",""}}).code==400,"invalid default quality accepted");
    require(invoke("library.setPlaybackSettings",{{"defaultQuality","128"},{"outputDevice",42}}).code==400,"non-string output device accepted");
    const auto settingsFile=fs::temp_directory_path()/L"musxi-playback-settings-test"/L"settings.json";
    require(savePlaybackSettings(settingsFile,"flac","saved-output"),"settings save failed");
    loadPlaybackSettings(settingsFile);
    require(defaultAudioQuality=="flac" && preferredOutputDevice=="saved-output","settings did not restore after restart");
    {std::ofstream file(settingsFile);file<<"{\"defaultQuality\":\"invalid\",\"outputDevice\":\"bad\"}";}
    loadPlaybackSettings(settingsFile);
    require(defaultAudioQuality=="flac" && preferredOutputDevice=="saved-output","invalid settings replaced valid preferences");
    fs::remove(settingsFile);defaultAudioQuality="128";preferredOutputDevice.clear();
    cloudTracks=Json::array();
    for(int i=0;i<63;++i) cloudTracks.push_back({{"id",std::to_string(i)},{"name","song"},{"token","PRIVATE"},{"path","PRIVATE"}});
    cloudTracks[50]["album"]="Sample Album";
    cloudPlaylists=Json::array({{{"id","p"},{"name","playlist"},{"cookie","PRIVATE"},{"favorite",true}}});
    auto snapshot=invoke("library.getState",{{"page",2}});
    require(snapshot.code==0 && snapshot.json.find("PRIVATE")==std::string::npos,"snapshot leaked internal fields");
    auto state=Json::parse(snapshot.json);
    require(state["playlists"][0]["favorite"]==true,"favorite playlist flag missing from public rows");
    require(state["tracks"].size()==13 && state["trackCount"]==63,"library pagination");
    require(state["tracks"][0]["album"]=="Sample Album" && state["tracks"][1]["album"]=="","album title missing from public rows");
    searchTracks=Json::array();
    for(int i=0;i<120;++i)searchTracks.push_back({{"id",std::to_string(i)},{"name","search song"},{"platforms",Json::array({"netease","qq"})},{"sources",Json::array({{{"id","qq:source"},{"token","PRIVATE"}}})}});
    auto searched=Json::parse(invoke("library.getState").json);
    require(searched["search"].size()==120 && searched["search"][0]["platforms"].size()==2,"accumulated search rows or tags truncated");
    require(!searched["search"][0].contains("sources"),"search exposed internal source metadata");
    auto restored=playbackTrack({{"id","original"},{"sources",Json::array({{{"id","qq:song"},{"platform","qq"}}})}});
    require(restored["sources"][0]["id"]=="qq:song","restart lost search playback sources");
    require(restored["platforms"]==Json::array({"kugou","qq"}),"restart did not reconstruct supported platform tags");
    bool rejectedSources=false;
    try {playbackTrack({{"id","original"},{"sources",Json::array({{{"id","qq:song"},{"sources",Json::array()}}})}});}catch(...) {rejectedSources=true;}
    require(rejectedSources,"nested playback sources were accepted");
    searchTracks=Json::array();
    require(invoke("library.getState",{{"page",0}}).code==400,"invalid page accepted");
    require(invoke("library.getState",{{"page",1.5}}).code==400,"fractional page accepted");
    require(invoke("library.search",{{"keywords"," "},{"page",1}}).code==400,"blank search accepted");
    require(invoke("library.sync").code==409,"unauthenticated sync accepted");
    require(invoke("library.play",{{"source","library"},{"id","0"}}).code==409,"unauthenticated play accepted");
    require(invoke("library.image",{{"url","https://example.com/private"}}).code==403,"arbitrary image fetch accepted");
    cloudConnected=true;
    require(invoke("library.play",{{"source","library"},{"id","missing"}}).code==404,"unknown track accepted");
    auto first=invoke("library.play",{{"source","library"},{"id","0"}});
    require(first.code==0 && libraryOperationStatus=="pending" && !opened,"play acceptance claimed playback success");
    const auto generation=cloudGeneration;
    auto second=invoke("library.play",{{"source","library"},{"id","1"}});
    require(second.code==0 && first.json!=second.json && cloudGeneration>generation && libraryNextPlay["id"]=="1","latest selection did not supersede");
    require(invoke("library.cancel").code==0 && libraryNextPlay.is_null() && libraryOperationStatus=="cancelled","play cancellation");
    cloudTracks[0]["cover"]="https://example.com/known";
    require(invoke("library.image",{{"url","https://example.com/known"}}).code==0 && webWantedCovers.size()==1,"known cover was not queued");
    auto complete=[&](const std::string& operation,const Json& response) {
        libraryOperationKind=cloudOperation=operation;libraryOperationStatus="pending";
        std::promise<Json> p;cloudFuture=p.get_future();p.set_value(response);cloudTick();
    };
    complete("search",{{"ok",true},{"data",{{"tracks",Json::array()},{"keywords","needle"},{"page",2},{"total",0},{"hasMore",false}}}});
    require(libraryOperationStatus=="completed" && searchQuery=="needle" && searchPage==2,"search completion not applied");
    complete("search",{{"ok",false},{"error","offline"}});
    require(libraryOperationStatus=="failed" && !libraryOperationError.empty(),"search failure not reported");
    complete("tracks",{{"ok",true},{"data",Json::object()}});
    require(libraryOperationStatus=="failed","malformed response reported success");
    libraryMenuRequested=true;
    complete("song_menu",{{"ok",true},{"data",{{"id","0"},{"liked",true},{"canFavorite",true},{"playlists",Json::array()}}}});
    require(libraryMenu.value("liked",false) && !libraryMenuRequested,"web song menu did not receive response");
    libraryMenu["providers"]=Json::array({{{"platform","qq"},{"id","qq:song"},{"liked",true},{"canFavorite",true},{"token","PRIVATE"},{"playlists",Json::array({{{"id","qq:list"},{"editable",true},{"cookie","PRIVATE"}}})}}});
    const auto menuSnapshot=invoke("library.getState");
    require(menuSnapshot.json.find("PRIVATE")==std::string::npos,"provider menu leaked internal data");
    require(Json::parse(menuSnapshot.json)["menu"]["providers"][0]["liked"]==true,"provider favorite state lost");
    require(invoke("library.favorite",{{"id","0"},{"enabled",false},{"platform","netease"}}).code==409,"unsupported favorite platform accepted");
    require(invoke("library.add",{{"id","0"},{"playlistId","other"},{"platform","qq"}}).code==409,"unlisted platform playlist accepted");
    libraryPlayGeneration=requestGeneration=cloudGeneration;
    complete("audio",{{"ok",false},{"error","download failed"}});
    require(libraryOperationStatus=="failed" && !opened,"failed download completed playback");
    require(invoke("library.setPlatform",{{"id","0"},{"platform","invalid"}}).code==400,"invalid source platform accepted");
    require(invoke("library.setPlatform",{{"id","0"},{"platform","qq"}}).code==409,"closed player source switch accepted");
    auto backend=std::make_unique<SwitchAudio>();auto* switched=backend.get();audioBackendStorage()=std::move(backend);
    const auto seed=[&] {
        switched->state={};switched->state.opened=true;switched->state.positionMs=42000;
        opened=true;audioTrackId="original";cloudTempPath=L"original-cache";currentQuality="320";
        currentPlaybackTrack={{"id","original"},{"name","Song"},{"artist","Artist"},{"album","Album"}};
        currentPlaybackTrack["sources"]=Json::array({currentPlaybackTrack});
        cloudQueue=Json::array({currentPlaybackTrack});libraryPlayTrackId="original";
        libraryPlayPaused=true;libraryQualityChange=libraryPlatformChange=true;libraryPlayGeneration=requestGeneration=cloudGeneration;
    };
    const auto audioReply=Json{{"ok",true},{"data",{{"path","new-cache"},{"quality","128"},{"requestedId","original"},{"track",{{"id","qq:song"},{"name","Song"},{"artist","Artist"},{"album","Album"},{"platform","qq"}}}}}};
    seed();complete("audio",audioReply);
    require(audioTrackId=="qq:song" && switched->state.positionMs==42000 && !switched->state.requestedPlaying,"source switch lost position or paused state");
    require(Json::parse(invoke("library.getState").json)["now"]["platform"]=="qq","playback badge not actual platform");
    require(Json::parse(invoke("library.getState").json)["now"]["platforms"]==Json::array({"kugou","qq"}),"source menu did not use matched platforms");
    pollAudio();require(platformRollback.path.empty() && libraryOperationStatus=="completed","successful switch did not release rollback state");
    seed();complete("audio",audioReply);switched->failNext=true;pollAudio();
    require(audioTrackId=="original" && currentQuality=="320" && switched->state.positionMs==42000 && !switched->state.requestedPlaying,"decoder failure did not restore original source");
    require(cloudQueue[0]["id"]=="original" && libraryOperationStatus=="failed","rollback lost queue or error status");
    pollAudio();
    cloud::testProfile=true;
    Json aggregate={{"playlist",{{"id","musxi:cloud-favorites"},{"name","云端收藏整合"}}},{"playlists",Json::array({{{"id","qq:liked"},{"editable",true}}})}};
    aggregate["tracks"]=Json::array({{{"id","merged"},{"name","Song"},{"platforms",Json::array({"netease","qq"})},{"sources",Json::array({{{"id","netease:song"}},{{"id","qq:song"}}})}}});
    aggregate["favoritesCount"]=std::size_t{1};
    complete("tracks",{{"ok",true},{"data",aggregate}});
    const auto aggregateSnapshot=Json::parse(invoke("library.getState").json);
    require(cloudPlaylistId=="musxi:cloud-favorites" && cloudPlaylists[0]["id"]=="qq:liked","aggregate playlist completion lost real playlists");
    require(aggregateSnapshot["favoritesCount"]==1,"aggregate count missing from library state");
    require(aggregateSnapshot["tracks"][0]["platforms"].size()==2 && !aggregateSnapshot["tracks"][0].contains("sources"),"aggregate tags or redaction failed");
    auto updated=aggregate;updated["tracks"]=Json::array();updated["message"]="Updated";
    complete("song_update",{{"ok",true},{"data",updated}});
    require(cloudPlaylistId=="musxi:cloud-favorites" && cloudTracks.empty(),"favorite edit did not refresh aggregate view");
    complete("tracks",{{"ok",true},{"data",aggregate}});
    complete("logout",{{"ok",true},{"data",{{"platform","netease"},{"playlists",Json::array()},{"profile",{{"name","QQ"}}}}}});
    require(cloudPlaylistId.empty() && cloudTracks.empty(),"logout retained departed provider favorites");
    recentTracks=Json::array();recentRecordedGeneration=0;playbackSaveAt=GetTickCount64()+5000;
    seed();libraryQualityChange=false;switched->state.playing=switched->state.requestedPlaying=true;
    ++cloudGeneration;pollAudio();require(recentTracks.empty(),"old audio recorded while new request pending");
    libraryPlayGeneration=cloudGeneration;pollAudio();
    require(recentTracks.size()==1 && recentTracks[0]["id"]=="original","successful playback not recorded");
    require(playbackSaveAt==0,"new recent history did not schedule immediate persistence");
    recordRecentTrack({{"id","qq:other"},{"name","Other"},{"path","PRIVATE"},{"cookie","PRIVATE"}});
    ++cloudGeneration;libraryPlayGeneration=cloudGeneration;libraryQualityChange=true;libraryPlatformChange=false;pollAudio();
    require(recentTracks[0]["id"]=="qq:other","quality reload reordered recent history");
    ++cloudGeneration;libraryPlayGeneration=cloudGeneration;libraryQualityChange=false;switched->failNext=true;pollAudio();
    require(recentTracks[0]["id"]=="qq:other","failed decoder added recent history");
    const auto recentSnapshot=invoke("library.getState");
    require(recentSnapshot.json.find("PRIVATE")==std::string::npos && Json::parse(recentSnapshot.json)["recent"].size()==2,"recent metadata leaked or missing");
    const auto queueBefore=cloudQueue;const auto playingBefore=audioTrackId;
    require(invoke("library.recentRemove",{{"id","original"}}).code==0 && recentTracks.size()==1,"recent removal failed");
    require(cloudQueue==queueBefore && audioTrackId==playingBefore,"recent removal changed playback queue");
    recentTracks=Json::array();
    recordRecentTrack({{"id","netease:merged"},{"name"," Same Song "},{"artist","Artist"},{"album","Album"}});
    recordRecentTrack({{"id","qq:merged"},{"name","same song"},{"artist","artist"},{"album","album"}});
    require(recentTracks.size()==1 && recentTracks[0]["id"]=="qq:merged" && recentTracks[0]["platforms"]==Json::array({"netease","qq"}),"platform switch duplicated recent song or lost sources");
    recordRecentTrack({{"id","qq:other-album"},{"name","same song"},{"artist","artist"},{"album","Other Album"}});
    recordRecentTrack({{"id","local:same"},{"name","same song"},{"artist","artist"},{"album","album"}});
    require(recentTracks.size()==3,"different album or local track merged into online history");
    recentTracks=Json::array();
    for(int i=0;i<510;++i)recordRecentTrack({{"id","qq:"+std::to_string(i)},{"name","Recent "+std::to_string(i)}});
    recordRecentTrack({{"id","qq:505"},{"name","Replay"},{"sources",Json::array({{{"id","netease:same"}},{{"id","original"}},{{"id","qq:505"}}})}});
    require(recentTracks.size()==500 && recentTracks[0]["id"]=="qq:505","recent limit or move-to-front failed");
    const auto recentFile=cloud::dataDir()/L"recent-history-test.json";
    switched->state.opened=false;cloudTempPath.clear();
    require(savePlaybackHistory(recentFile),"recent persistence save failed");recentTracks=Json::array();
    require(restorePlaybackHistory(recentFile) && recentTracks.size()==500 && recentTracks[0]["id"]=="qq:505","recent restart restore failed");
    require(Json::parse(invoke("library.getState").json)["recent"][0]["platforms"]==Json::array({"netease","kugou","qq"}),"recent restart lost multi-platform tags");
    Json duplicated;{std::ifstream stream(recentFile);stream>>duplicated;}
    auto duplicate=duplicated["recent"][0];duplicate["id"]="netease:older";duplicate.erase("sources");
    duplicated["recent"].erase(duplicated["recent"].end()-1);duplicated["recent"].push_back(duplicate);
    {std::ofstream stream(recentFile);stream<<duplicated;}
    require(restorePlaybackHistory(recentFile) && recentTracks.size()==499 && recentTracks[0]["id"]=="qq:505","old cross-platform duplicates not merged on restore");
    Json legacy;{std::ifstream stream(recentFile);stream>>legacy;}legacy.erase("recent");{std::ofstream stream(recentFile);stream<<legacy;}
    require(restorePlaybackHistory(recentFile) && recentTracks.empty(),"legacy playback file incompatible with recent history");
    require(invoke("library.recentClear").code==0 && recentTracks.empty(),"recent clear failed");
    require(invoke("library.play",{{"source","recent"},{"id","missing"}}).code==404,"unknown recent song accepted");
    std::filesystem::remove(recentFile);
    {
    // Lyrics: parameter validation, local sidecar priority, async polling and stale-song isolation.
    require(invoke("library.lyrics",{{"source","library"}}).code==400,"lyrics accepted missing id");
    require(invoke("library.lyrics",{{"source","nowhere"},{"id","0"}}).code==400,"lyrics accepted unknown source");
    require(invoke("library.lyrics",{{"source","library"},{"id","missing"}}).code==404,"lyrics accepted unknown song");
    cloudTracks=Json::array({{{"id","netease:lyric"},{"name","Online"}}});
    auto online=Json::parse(invoke("library.lyrics",{{"source","library"},{"id","netease:lyric"}}).json);
    require(online["status"]=="ready" && online["kind"]=="none" && online["platform"]=="netease" && online["id"]=="netease:lyric","online lyrics placeholder");
    const auto lyricsFolder=cloud::dataDir()/(L"lyrics-check-"+std::to_wstring(GetCurrentProcessId()));
    fs::create_directories(lyricsFolder);
    {std::ofstream audio(lyricsFolder/L"歌曲.mp3",std::ios::binary);audio<<"not audio";}
    {std::ofstream lrc(lyricsFolder/L"歌曲.lrc",std::ios::binary);lrc<<"\xEF\xBB\xBF[00:01.00]\xE4\xBD\xA0\xE5\xA5\xBD\n[00:00.50]First";}
    {std::ofstream gbk(lyricsFolder/L"旧歌.lrc",std::ios::binary);gbk<<"[00:02.00]\xC4\xE3\xBA\xC3";}
    {std::ofstream audio(lyricsFolder/L"旧歌.mp3",std::ios::binary);audio<<"not audio";}
    localTracks=Json::array({{{"id","local:1"},{"path",(lyricsFolder/L"歌曲.mp3").u8string()},{"name","歌曲.mp3"}},
        {{"id","local:2"},{"path",(lyricsFolder/L"旧歌.mp3").u8string()},{"name","旧歌.mp3"}},
        {{"id","local:3"},{"path",(lyricsFolder/L"没有歌词.mp3").u8string()},{"name","没有歌词.mp3"}}});
    require(invoke("library.lyrics",{{"source","local"},{"id","local:9"}}).code==404,"lyrics accepted unknown local song");
    const auto pollLyrics=[&](const char* id) {
        const auto until=GetTickCount64()+5000;
        while(GetTickCount64()<until) {
            auto reply=invoke("library.lyrics",{{"source","local"},{"id",id}});
            require(reply.code==0,"local lyrics request failed");
            auto value=Json::parse(reply.json);
            require(value["id"]==id && value["source"]=="local","lyrics reply belongs to another song");
            if(value["status"]=="ready")return value;
            Sleep(5);
        }
        throw std::runtime_error("local lyrics never completed");
    };
    auto local=pollLyrics("local:1");
    require(local["kind"]=="synced" && local["origin"]=="lrc-file" && local["platform"]=="local","sidecar lyrics");
    require(local["lines"].size()==2 && local["lines"][0]["timeMs"]==500 && local["lines"][1]["text"]=="\xE4\xBD\xA0\xE5\xA5\xBD","sidecar lines or UTF-8 BOM");
    require(local.dump().find(lyricsFolder.u8string())==std::string::npos,"lyrics reply exposed a private path");
    auto gbkLyrics=pollLyrics("local:2");
    require(gbkLyrics["kind"]=="synced" && gbkLyrics["lines"][0]["text"]=="\xE4\xBD\xA0\xE5\xA5\xBD","GBK sidecar not decoded");
    auto none=pollLyrics("local:3");
    require(none["kind"]=="none" && none["origin"]=="" && none["lines"].empty(),"missing local lyrics");
    // Switching songs mid-lookup must never surface the previous song's lines.
    auto cached=Json::parse(invoke("library.lyrics",{{"source","local"},{"id","local:1"}}).json);
    require(cached["id"]=="local:1","cached lyrics lost");
    auto nextSong=pollLyrics("local:2");
    require(nextSong["lines"][0]["timeMs"]==2000,"stale lyrics after switching songs");
    stopLyrics();fs::remove_all(lyricsFolder);localTracks=Json::array();
    }
    std::cout<<"PASS library boundary, pagination, operation lifecycle and redaction\n";
} catch(const std::exception& error) {std::cerr<<error.what()<<'\n';return 1;}
