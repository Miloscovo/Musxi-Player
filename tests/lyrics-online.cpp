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
#ifdef MUSXI_TEST_LYRICS_IPC
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
#endif
        stopLyrics();fs::remove_all(directory);std::cout<<"Online lyrics lifecycle passed\n";return 0;
    }catch(const std::exception& e){stopLyrics();fs::remove_all(directory);std::cerr<<e.what()<<'\n';return 1;}
}
