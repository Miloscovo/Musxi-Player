#include "../src/application/application.cpp"
#include <iostream>
void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
int main() {
    const auto directory=fs::temp_directory_path()/(L"musxi-online-lyrics-"+std::to_wstring(GetCurrentProcessId()));
    try {
        const Json request={{"source","queue"},{"id","netease:123"},{"platform","netease"}};
        const std::string key="netease:123";
        auto result=readOnlineLyrics(request,directory,key,false);
        require(result.value("error",false),"unavailable service reported no lyrics");
        require(!musxi::lyrics::readCache(directory,key,lyricsNow()),"network failure created negative cache");
        musxi::lyrics::writeCache(directory,key,{"[00:01]Offline",false},lyricsNow()-31ll*86400);
        result=readOnlineLyrics(request,directory,key,false);
        require(result["origin"]=="cache" && result["lines"][0]["text"]=="Offline","stale cache not available offline");
        musxi::lyrics::writeCache(directory,key,{"",true},lyricsNow()-86401);
        require(readOnlineLyrics(request,directory,key,false).value("error",false),"expired negative cache reused");
        musxi::lyrics::writeCache(directory,key,{"",true},lyricsNow());
        result=readOnlineLyrics(request,directory,key,false);
        require(result["kind"]=="none" && result["origin"]=="cache","fresh negative cache ignored");
        // A late first A must not be mistaken for a later A, even with the same ID.
        lyricsTrackId="netease:123";lyricsPlaybackId=audioTrackId;lyricsGeneration=3;
        std::promise<Json> old;
        lyricsFuture=old.get_future();
        auto stale=lyricsReply("queue","netease:123","netease","online",musxi::lyrics::parse("[00:01]OLD"));
        stale["generation"]=1;old.set_value(stale);lyricsTick();
        require(lyricsResult.is_null(),"A-B-A accepted old generation");
        lyricsTrackId="netease:123";lyricsPlaybackId=audioTrackId;
        std::promise<Json> blocked;lyricsFuture=blocked.get_future();
        const auto started=GetTickCount64();
        auto pending=requestLyrics("queue","qq:new");
        require(pending["status"]=="pending" && GetTickCount64()-started<100,"switch blocked Application thread");
        stale["generation"]=lyricsGeneration;blocked.set_value(stale);lyricsTick();
        require(lyricsResult.is_null(),"cancelled result delivered");
        // The actual playing record wins over a merged list row with stale metadata.
        currentPlaybackTrack={{"id","qq:actual"},{"name","Actual"}};
        cloudQueue=Json::array({{{"id","qq:actual"},{"name","Old"}}});
        require(findLyricsTrack("qq:actual")->at("name")=="Actual","lyrics used stale list record");
        auto malformed=lyricsEntry({{"outcome","found"},{"format","lrc"},{"text","[00:01]Original"},{"translationState",23}},std::nullopt);
        require(malformed.text=="[00:01]Original" && malformed.translationState==musxi::lyrics::Translation::Unknown,"malformed translation rejected original");
        // A translation cannot survive actual playback changes or a later generation.
        lyricsCancelled=false;lyricsTrackId="netease:123";lyricsPlaybackId=audioTrackId;
        lyricsResult=lyricsReply("queue",lyricsTrackId,"netease","cache",musxi::lyrics::parse("[00:01]Shown"));
        translationTrackId=lyricsTrackId;translationGeneration=lyricsGeneration;
        translationAttempted.insert(translationTrackId);translationCancelled=false;
        std::promise<Json> lateTranslation;translationFuture=lateTranslation.get_future();
        audioTrackId="qq:switched";lyricsTick();
        require(translationCancelled.load() && translationTrackId.empty() && !translationAttempted.count("netease:123"),"playback switch did not cancel/retry translation");
        lateTranslation.set_value(lyricsReply("queue","netease:123","netease","online",musxi::lyrics::parse("[00:01]Late")));lyricsTick();
        require(lyricsResult.is_null(),"cancelled translation changed current lyrics");
        lyricsTrackId="netease:123";lyricsPlaybackId=audioTrackId;
        lyricsResult=lyricsReply("queue",lyricsTrackId,"netease","cache",musxi::lyrics::parse("[00:01]Current"));
        translationTrackId=lyricsTrackId;translationGeneration=lyricsGeneration-1;translationCancelled=false;
        std::promise<Json> previousGeneration;translationFuture=previousGeneration.get_future();
        previousGeneration.set_value(lyricsReply("queue",lyricsTrackId,"netease","online",musxi::lyrics::parse("[00:01]Old generation")));translationTick();
        require(lyricsResult["lines"][0]["text"]=="Current","old translation generation delivered");
        cancelLyrics();
#ifdef MUSXI_TEST_LYRICS_IPC
        lyricsCancelled=false;
        std::wstring error;cloud::Bridge playback;
        require(playback.start(error,L"lyrics-bridge.cjs") && lyricsBridge.start(error,L"lyrics-bridge.cjs"),"IPC fixture start");
        auto blockedNetwork=std::async(std::launch::async,[]{return lyricsBridge.call({{"op","hang"}},15000);});
        const auto before=GetTickCount64();
        require(playback.call({{"op","lyrics"}},2000).value("ok",false),"lyrics blocked independent playback bridge");
        require(GetTickCount64()-before<2000,"playback waited for lyrics");
        lyricsBridge.cancel();
        require(blockedNetwork.wait_for(std::chrono::seconds(2))==std::future_status::ready,"network cancellation did not finish");
        require(!blockedNetwork.get().value("ok",true),"cancelled IPC succeeded");
        lyricsBridge.stop();playback.stop();
        require(lyricsBridge.start(error,L"lyrics-bridge.cjs"),"IPC restart after cancel");
        require(!lyricsBridge.call({{"op","hang"}},100).value("ok",true),"lyrics deadline ignored");
        lyricsBridge.stop();
        require(lyricsBridge.start(error,L"lyrics-bridge.cjs"),"IPC restart after timeout");
        auto liveRequest=request;liveRequest["op"]="lyrics";
        result=readOnlineLyrics(liveRequest,directory,"netease:456",true);
        require(result["origin"]=="online" && result["lines"][0]["timeMs"]==1000,"IPC response did not reach parser");
        lyricsBridge.stop();
        result=readOnlineLyrics(liveRequest,directory,"netease:456",false);
        require(result["origin"]=="cache" && result["lines"][0]["text"]=="IPC fixture","IPC success not persisted");
        require(result["translation"]=="none" && !result.contains("translationRefresh"),"known absence refreshed again");
        // v0.4 cache entry: the original shows at once and the translation arrives afterwards.
        // Isolated test profile; only the records written here are removed afterwards.
        cloud::testProfile=true;translationService=L"lyrics-bridge.cjs";
        const auto profileCache=cloud::dataDir()/L"lyrics-cache";
        {fs::create_directories(profileCache);musxi::lyrics::CacheEntry seeded;seeded.text="[00:01.00]IPC fixture\n[00:02.00]Second line";
         require(musxi::lyrics::writeCache(profileCache,"netease:789",seeded,lyricsNow()),"seed v0.4-like entry");}
        currentPlaybackTrack={{"id","netease:789"},{"name","Translated"}};audioTrackId="netease:789";
        const auto poll=[&](const std::function<bool(const Json&)>& done) {
            Json value;const auto until=GetTickCount64()+8000;
            do {Sleep(10);value=requestLyrics("queue","netease:789");} while(!done(value) && GetTickCount64()<until);
            return value;
        };
        auto first=poll([](const Json& v){return v["status"]=="ready";});
        require(first["origin"]=="cache" && first["translation"]=="unknown" && first.value("translationPending",false),"cached original waited for translation");
        auto filled=poll([](const Json& v){return v["translation"]!="unknown";});
        require(filled["translation"]=="available" && filled["lines"][0]["translation"]=="IPC 夹具" && !filled.contains("translationPending"),
            "background translation not delivered");
        require(musxi::lyrics::readCache(profileCache,"netease:789",lyricsNow())->translationState==musxi::lyrics::Translation::Available,
            "background translation not cached");
        // A failed automatic attempt leaves the original; explicit retry works without restart.
        cancelLyrics();
        musxi::lyrics::CacheEntry retrySeed;retrySeed.text="[00:01.00]IPC fixture\n[00:02.00]Second line";
        require(musxi::lyrics::writeCache(profileCache,"netease:789",retrySeed,lyricsNow()),"retry seed");
        translationAttempted.insert("netease:789");
        first=poll([](const Json& v){return v["status"]=="ready";});
        require(first["translation"]=="unknown" && !first.value("translationPending",false),"automatic attempts unbounded");
        requestLyrics("queue","netease:789",true);
        filled=poll([](const Json& v){return v.value("translation","")=="available";});
        require(filled["translation"]=="available","explicit translation retry required restart");
        cancelLyrics();
        // A v0.4 user snapshot gains the translation, but keeps the original the user chose.
        const auto root=fs::temp_directory_path()/(L"musxi-translation-snapshot-"+std::to_wstring(GetCurrentProcessId()));
        fs::create_directories(root);const auto audio=root/L"song.mp3";{std::ofstream out(audio);out<<"synthetic";}
        const auto identity=musxi::lyrics::fileIdentity(audio);const auto matches=cloud::dataDir()/L"lyrics-matches";
        const auto snapshot=[&](const char* recording) {
            return Json{{"text","[00:01.00]IPC fixture\n[00:02.00]Second line"},{"offsetMs",500},{"platform","netease"},
                {"track",{{"id",recording},{"name","Chosen"}}}};
        };
        localTracks=Json::array({{{"id","local:5"},{"path",audio.u8string()}}});audioTrackId="local:5";
        const auto local=[&](const std::function<bool(const Json&)>& done) {
            Json value;const auto until=GetTickCount64()+8000;
            do {Sleep(10);value=requestLyrics("local","local:5");} while(!done(value) && GetTickCount64()<until);
            return value;
        };
        musxi::lyrics::saveMatch(matches,identity,snapshot("netease:789"));
        auto chosen=local([](const Json& v){return v["status"]=="ready" && v["translation"]!="unknown";});
        require(chosen["origin"]=="matched" && chosen["lines"][0]["timeMs"]==1500 && chosen["lines"][0]["translation"]=="IPC 夹具",
            "snapshot translation missing or offset not shared");
        require(musxi::lyrics::readMatch(matches,identity)->at("text")==snapshot("netease:789")["text"],"snapshot original replaced");
        cancelLyrics();translationAttempted.clear();
        musxi::lyrics::saveMatch(matches,identity,snapshot("netease:790"));
        local([](const Json& v){return v["status"]=="ready" && !v.value("translationPending",false) && !v.value("translationRefresh",false);});
        Sleep(300);local([](const Json&){return true;});
        const auto kept=musxi::lyrics::readMatch(matches,identity);
        require(kept && kept->value("translationState","unknown")=="unknown" && kept->at("text")==snapshot("netease:790")["text"],
            "newer server original silently replaced the user's choice");
        cancelLyrics();musxi::lyrics::removeMatch(matches,identity);fs::remove_all(root);
        musxi::lyrics::removeCache(profileCache,"netease:789");
        require(!musxi::lyrics::readCache(profileCache,"netease:789",lyricsNow()),"test cache entry left behind");
#endif
        stopLyrics();fs::remove_all(directory);std::cout<<"Online lyrics lifecycle passed\n";return 0;
    }catch(const std::exception& e){stopLyrics();fs::remove_all(directory);std::cerr<<e.what()<<'\n';return 1;}
}
