#include "../src/application/application.cpp"
#include <iostream>
void require(bool value,const char* text){if(!value)throw std::runtime_error(text);}
template<class F> musxi::PlayerState waitState(F predicate) {
    const auto until=GetTickCount64()+8000;
    while(GetTickCount64()<until) {
        pollAudio();playerService().publish();const auto s=musxi::applicationPlayerState();
        if(s.phase=="failed")throw std::runtime_error(s.error);
        if(predicate(s))return s;Sleep(5);
    }
    throw std::runtime_error("Application audio wait timed out");
}
int main(int argc,char** argv) {
    try {
        require(argc==2,"fixture directory required");const fs::path folder=argv[1];
        hostHooks.connectCloud=false;
        audioBackend().setVolume(10);
        int events=0;musxi::setApplicationPlayerEvents([&](const char*,const musxi::PlayerState&){++events;});
        const auto before=GetTickCount64();
        require(bool(loadAndPlayAudio((folder/"source.wav").wstring(),"first")),"load accepted");
        require(GetTickCount64()-before<100,"load blocked Application thread");
        auto s=waitState([](auto s){return s.playing && s.positionMs>50;});
        require(s.trackId=="first" && events>0,"native state/event identity");
        musxi::applicationPlayerCommand(musxi::PlayerCommand::Pause);
        waitState([](auto s){return !s.pending && !s.playing;});
        for(unsigned target:{400,1800,250,1100})musxi::applicationPlayerCommand(musxi::PlayerCommand::Seek,target);
        s=waitState([](auto s){return !s.pending && s.positionMs==1100;});
        require(!s.playing && !s.requestedPlaying,"latest seek lost paused intent");
        musxi::applicationPlayerCommand(musxi::PlayerCommand::Resume);
        waitState([](auto s){return s.playing;});
        require(bool(loadAndPlayAudio((folder/"long.wma").wstring(),"old")),"long load");
        audioBackend().seek(170000);
        require(bool(loadAndPlayAudio((folder/"source.flac").wstring(),"latest")),"replacement load");
        audioBackend().pause();
        s=waitState([](auto s){return !s.pending && s.trackId=="latest";});
        require(!s.playing,"old resume overwrote latest pause");
        closeAudio();
        // Exercise cloud download completion, with no login or network request.
        const auto cache=folder/"application-cache.wav";fs::copy_file(folder/"source.wav",cache,fs::copy_options::overwrite_existing);
        cloudQueue=Json::array({{{"id","cloud-test"},{"name","Fixture"}}});
        libraryPlayTrackId="cloud-test";libraryPlayGeneration=requestGeneration=++cloudGeneration;
        libraryOperationKind=cloudOperation="audio";libraryOperationStatus="pending";
        std::promise<Json> downloaded;cloudFuture=downloaded.get_future();
        downloaded.set_value({{"ok",true},{"data",{{"path",utf8(cache.wstring())},{"track",{{"id","cloud-test"},{"name","Fixture"},{"artist","Test"},{"cover",""}}}}}});
        cloudTick();require(libraryOperationStatus=="pending" || musxi::applicationPlayerState().playing,"cloud accepted request reported as playback completion");
        waitState([](auto s){return s.playing && libraryOperationStatus=="completed";});
        const auto queueGeneration=audioBackend().snapshot().generation;
        require(musxi::applicationLibrary("library.queueRemove","{\"id\":\"cloud-test\"}").code==0,"cloud queue removal failed");
        require(audioBackend().snapshot().generation==queueGeneration && musxi::applicationPlayerState().playing,"cloud queue removal interrupted playback");
        require(!Json::parse(musxi::applicationLibrary("library.getState","{}").json)["now"]["name"].get<std::string>().empty(),"removing current cloud song cleared now-playing information");
        closeAudio();const auto deadline=GetTickCount64()+2000;
        while(fs::exists(cache) && GetTickCount64()<deadline){pollAudio();Sleep(5);}
        require(!fs::exists(cache),"released cloud cache was not collected");
        cloudCurrent=-1;cloudQueue=Json::array();libraryOperationStatus="idle";
        const auto brokenCache=folder/"application-broken-cache.wav";
        {std::ofstream broken(brokenCache,std::ios::binary);broken<<"not audio";}
        cloudQueue=Json::array({{{"id","cloud-broken"},{"name","Broken fixture"}}});
        libraryPlayTrackId="cloud-broken";libraryPlayGeneration=requestGeneration=++cloudGeneration;
        libraryOperationKind=cloudOperation="audio";libraryOperationStatus="pending";
        std::promise<Json> failedDownload;cloudFuture=failedDownload.get_future();
        failedDownload.set_value({{"ok",true},{"data",{{"path",utf8(brokenCache.wstring())},
            {"track",{{"id","cloud-broken"},{"name","Broken fixture"},{"artist","Test"},{"cover",""}}}}}});
        cloudTick();require(libraryOperationStatus=="pending","accepted corrupt cloud audio marked successful");
        const auto failureDeadline=GetTickCount64()+2000;
        while(libraryOperationStatus=="pending" && GetTickCount64()<failureDeadline){pollAudio();Sleep(5);}
        require(libraryOperationStatus=="failed" && !libraryOperationError.empty(),"cloud decode failure not reported");
        fs::copy_file(folder/"source.wav",brokenCache,fs::copy_options::overwrite_existing);
        musxi::applicationPlayerCommand(musxi::PlayerCommand::Resume);
        waitState([](auto s){return s.playing && libraryOperationStatus=="completed";});
        closeAudio();
        const auto releaseDeadline=GetTickCount64()+2000;
        while(fs::exists(brokenCache) && GetTickCount64()<releaseDeadline){pollAudio();Sleep(5);}
        require(!fs::exists(brokenCache),"failed cloud cache was not released");
        cloudCurrent=-1;cloudQueue=Json::array();libraryOperationStatus="idle";
        const auto missing=folder/"missing.wav";fs::remove(missing);
        loadAndPlayAudio(missing.wstring(),"missing");
        const auto failedUntil=GetTickCount64()+2000;
        while(musxi::applicationPlayerState().phase!="failed" && GetTickCount64()<failedUntil){pollAudio();Sleep(5);}
        require(musxi::applicationPlayerState().phase=="failed","async failure missing");
        fs::copy_file(folder/"source.wav",missing);
        musxi::applicationPlayerCommand(musxi::PlayerCommand::Resume);
        waitState([](auto s){return s.playing && s.trackId=="missing";});
        closeAudio();
        const auto unloadDeadline=GetTickCount64()+2000;
        while(!audioBackend().sourceReleased(missing.wstring()) && GetTickCount64()<unloadDeadline)Sleep(5);
        require(audioBackend().sourceReleased(missing.wstring()),"retry source still held after close");
        fs::remove(missing);
        loadAndPlayAudio((folder/"source.wav").wstring(),"recovered");waitState([](auto s){return s.playing;});
        musxi::applicationPlayerCommand(musxi::PlayerCommand::Pause);
        musxi::applicationPlayerCommand(musxi::PlayerCommand::Seek,200);
        waitState([](auto s){return !s.pending && !s.playing && s.positionMs>=190;});
        cloudConnected=true;localActive=false;
        require(musxi::applicationLibrary("library.setQuality","{\"id\":\"stale\",\"quality\":\"320\"}").code==409,"stale quality selection accepted");
        require(musxi::applicationLibrary("library.setQuality","{\"id\":\"recovered\",\"quality\":\"invalid\"}").code==400,"invalid quality accepted");
        require(musxi::applicationLibrary("library.setQuality","{\"id\":\"recovered\",\"quality\":\"320\"}").code==409,"unconfirmed quality accepted");
        const auto qualityCache=folder/"quality-cache.wav";fs::copy_file(folder/"source.wav",qualityCache,fs::copy_options::overwrite_existing);
        libraryQualityChange=true;libraryPlayPaused=true;libraryPlayTrackId="recovered";
        libraryPlayGeneration=requestGeneration=++cloudGeneration;libraryOperationStatus="pending";libraryOperationKind=cloudOperation="audio";
        std::promise<Json> qualityDownload;cloudFuture=qualityDownload.get_future();
        pollAudio();require(libraryOperationStatus=="pending","quality download falsely marked complete before its response");
        qualityDownload.set_value({{"ok",true},{"data",{{"path",utf8(qualityCache.wstring())},{"quality","320"},
            {"track",{{"id","recovered"},{"name","Fixture"},{"artist","Test"},{"cover",""}}}}}});
        cloudTick();
        waitState([](auto s){return !s.pending && !s.playing && s.positionMs>=190 && s.positionMs<=220 && libraryOperationStatus=="completed";});
        require(currentQuality=="320" && audioTrackId=="recovered","quality switch lost song identity or quality");
        cloudConnected=true;localView=false;cloudPlaylistId="queue-playlist";
        cloudTracks=Json::array({{{"id","playlist-first"},{"name","First"}},{{"id","playlist-second"},{"name","Second"}}});
        cloudQueue=Json::array({cloudTracks[1]});libraryNextPlay=nullptr;
        require(musxi::applicationLibrary("library.playPlaylist","{\"id\":\"queue-playlist\"}").code==0,"cloud play-all rejected");
        require(cloudQueue.size()==2 && cloudQueue[0]["id"]=="playlist-second" && cloudQueue[1]["id"]=="playlist-first" && libraryNextPlay["id"]=="playlist-first" && libraryNextPlay["source"]=="queue","cloud play-all did not preserve/deduplicate the queue or schedule its first song");
        libraryNextPlay=nullptr;
        const auto profile=folder/("history-check-"+std::to_string(GetCurrentProcessId()));
        fs::create_directories(profile/"cloud-cache");
        const auto retained=profile/"cloud-cache"/"saved.wav";
        fs::copy_file(folder/"source.wav",retained,fs::copy_options::overwrite_existing);
        closeAudio();cloudTempPath=retained.wstring();currentPlaybackTrack={{"id","saved"},{"name","Saved"},{"hash",std::string(32,'a')}};
        libraryOperationStatus="idle";loadAndPlayAudio(cloudTempPath,"saved");
        waitState([](auto s){return s.playing;});audioBackend().pause();audioBackend().seek(400);
        waitState([](auto s){return !s.pending && !s.playing && s.positionMs==400;});
        audioBackend().resume();waitState([](auto s){return s.playing && s.positionMs>410;});
        cloudQueue=Json::array({currentPlaybackTrack});currentQuality="flac";playbackOrder="random";
        const auto history=profile/"playback-history.json";
        require(savePlaybackHistory(history),"online history save failed");
        Json savedHistory;{std::ifstream stream(history);stream>>savedHistory;}
        const auto savedPosition=savedHistory.at("positionMs").get<std::uint32_t>();
        closeAudio();audioBackendStorage().reset();collectAudioCaches(true);
        require(fs::exists(retained),"shutdown deleted persisted online audio");
        cloudQueue=Json::array();currentPlaybackTrack=nullptr;currentQuality.clear();
        require(restorePlaybackHistory(history),"online history restore failed");
        s=waitState([&](auto s){return !s.pending && s.positionMs==savedPosition;});
        require(s.trackId=="saved" && !s.playing && !s.requestedPlaying && currentQuality=="flac" && playbackOrder=="random" && cloudQueue.size()==1,"online restart lost identity/quality/queue/pause");
        Json unsafe;{std::ifstream stream(history);stream>>unsafe;}
        unsafe["cache"]="../source.wav";{std::ofstream stream(history);stream<<unsafe;}
        require(!restorePlaybackHistory(history) && audioTrackId=="saved","profile escape accepted or mutated playback");
        closeAudio();audioBackendStorage().reset();currentPlaybackTrack=nullptr;
        require(savePlaybackHistory(history),"cleared playback history save failed");collectAudioCaches(true);
        require(!fs::exists(retained),"replaced history leaked old online cache");fs::remove_all(profile);
        std::cout<<"PASS asynchronous Application commands, latest seek/track, pause intent, native events and cache release\n";
        return 0;
    }catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<'\n';closeAudio();audioBackendStorage().reset();collectAudioCaches(true);return 1;}
}
