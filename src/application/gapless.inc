// One prepared successor; the output clock, not download completion, changes now-playing.
struct GaplessPreparation {
    std::uint64_t token=0,sourceGeneration=0;
    int generation=0;
    std::string currentId,requestedId,fingerprint;
    Json track=nullptr,data=nullptr;
    std::wstring path;
    bool remote=false,started=false,prepared=false,failed=false;
    ULONGLONG pollAt=0;
} gapless;
std::uint64_t gaplessSequence=0,gaplessCloudToken=0;
std::set<std::uint64_t> gaplessCancelled;
std::vector<GaplessPreparation> gaplessRetired;
fs::path gaplessCacheDir(){return cloud::dataDir()/L"cloud-cache"/std::to_wstring(GetCurrentProcessId())/L"prepared";}
void collectStoppedPreparations() {
    std::error_code error;
    for(fs::directory_iterator it(gaplessCacheDir(),error),end;!error && it!=end;it.increment(error))
        if(it->is_regular_file(error))retiredAudioCaches.push_back(it->path().wstring());
    collectAudioCaches();
}

std::string gaplessFingerprint() {
    auto ids=Json::array();for(const auto& row:cloudQueue)ids.push_back(row.value("id",""));
    return Json{{"queue",ids},{"order",playbackOrder},{"quality",defaultAudioQuality},
        {"device",preferredOutputDevice}}.dump();
}
void gaplessApply(const GaplessPreparation& next,bool pending=false) {
    if(!cloudTempPath.empty() && cloudTempPath!=next.path)retiredAudioCaches.push_back(cloudTempPath);
    const bool local=next.track.value("id","").rfind("local:",0)==0;
    cloudTempPath=local?L"":next.path;
    currentPlaybackTrack=next.track;
    audioTrackId=next.track.value("id","");
    if(next.requestedId!=audioTrackId) {
        for(auto& row:cloudQueue)if(row.value("id","")==next.requestedId)row=next.track;
        std::set<std::string> ids;
        cloudQueue.erase(std::remove_if(cloudQueue.begin(),cloudQueue.end(),[&](const Json& row){return !ids.insert(row.value("id","")).second;}),cloudQueue.end());
    }
    cloudCurrent=-1;
    for(int i=0;i<(int)cloudQueue.size();++i)if(cloudQueue[i].value("id","")==audioTrackId){cloudCurrent=i;break;}
    localActive=local;localCurrent=-1;
    if(local)for(int i=0;i<(int)localTracks.size();++i)if(localTracks[i].value("id","")==audioTrackId){localCurrent=i;break;}
    currentQuality=local?"":next.data.value("quality",currentQuality);
    libraryNowMenu=libraryMenu=Json::object();libraryQualities=Json::object();libraryMenuRequested=false;
    libraryQualityChange=libraryPlatformChange=false;
    ++cloudGeneration;++libraryOperationId;libraryPlayGeneration=cloudGeneration;libraryPlayTrackId=audioTrackId;
    libraryOperationKind="audio";libraryOperationStatus=pending?"pending":"completed";libraryOperationError.clear();
    libraryNextPlay=nullptr;cloudAutoNext=false;cancelUnplayableSkip();
    libraryPlayPaused=!audioBackend().snapshot().requestedPlaying;
    cloudNowTitle=cloud::toWide(next.track.value("name",""));cloudNowArtist=cloud::toWide(next.track.value("artist",""));
    cloudNowCover=next.track.value("cover","");
    cloudStatus=(libraryPlayPaused?L"已暂停 · ":L"正在播放 · ")+cloudNowTitle;
}
void gaplessAdopt() {
    if(!gapless.token || !gapless.prepared || audioBackend().snapshot().transitionToken!=gapless.token)return;
    const auto next=gapless;gapless={};gaplessApply(next);
}
void cancelGapless() {
    gaplessAdopt();
    if(!gapless.token)return;
    audioBackend().cancelNext();
    gaplessAdopt();
    if(!gapless.token)return;
    if(gapless.prepared)gaplessRetired.push_back(gapless);
    if(gapless.remote && gapless.started && gapless.data.is_null())gaplessCancelled.insert(gapless.token);
    if(gapless.remote && !gapless.path.empty() && gapless.path!=cloudTempPath)retiredAudioCaches.push_back(gapless.path);
    gapless={};
}
bool gaplessValid(const musxi::AudioState& audio) {
    return gapless.generation==cloudGeneration && gapless.sourceGeneration==audio.generation &&
        gapless.currentId==audioTrackId && gapless.fingerprint==gaplessFingerprint();
}
void gaplessPrepareOutput() {
    if(gapless.path.empty() || gapless.prepared || gapless.failed)return;
    const auto result=audioBackend().prepareNext(gapless.path,gapless.token);
    if(result)gapless.prepared=true;else gapless.failed=true;
}
void gaplessTick() {
    gaplessAdopt();
    const auto audio=audioBackend().snapshot();
    for(auto it=gaplessRetired.begin();it!=gaplessRetired.end();) {
        if(audio.transitionToken==it->token && audio.generation==it->sourceGeneration &&
           cloudGeneration==it->generation && libraryNextPlay.is_null()) {
            gaplessApply(*it);it=gaplessRetired.erase(it);
        } else if(audio.generation!=it->sourceGeneration || audio.transitionToken>it->token || audioBackend().sourceReleased(it->path))it=gaplessRetired.erase(it);
        else ++it;
    }
    if(gapless.token && !gaplessValid(audio))cancelGapless();
    if(gapless.token) {gaplessPrepareOutput();return;}
    if(applicationClosing || !audio.opened || !audio.playing || audio.pending || audio.phase=="failed" ||
       !libraryNextPlay.is_null() || libraryQualityChange || libraryPlatformChange || unplayableSkipAt ||
       !currentPlaybackTrack.is_object() || currentPlaybackTrack.value("id","")!=audioTrackId)return;
    Json next;
    if(playbackOrder=="repeat-one")next=currentPlaybackTrack;
    else {
        const auto current=std::find_if(cloudQueue.begin(),cloudQueue.end(),[&](const Json& row){return row.value("id","")==audioTrackId;});
        if(current==cloudQueue.end())return;
        const int index=nextQueueIndex(1,(int)std::distance(cloudQueue.begin(),current));
        if(index<0)return;next=cloudQueue[index];
    }
    gapless.token=++gaplessSequence;gapless.sourceGeneration=audio.generation;gapless.generation=cloudGeneration;
    gapless.currentId=audioTrackId;gapless.track=next;gapless.requestedId=next.value("id","");gapless.fingerprint=gaplessFingerprint();
    if(next.value("id","").rfind("local:",0)==0) {
        const auto found=std::find_if(localTracks.begin(),localTracks.end(),[&](const Json& row){return row.value("id","")==gapless.requestedId;});
        if(found!=localTracks.end())gapless.path=fs::u8path(found->value("path","")).wstring();
        else gapless.failed=true;
        gapless.data=Json::object();
    } else if(gapless.requestedId==audioTrackId && !cloudTempPath.empty()) {
        gapless.path=cloudTempPath;gapless.data={{"quality",currentQuality}};
    } else {
        gapless.remote=true;
        if(cloudInitialized && !cloudConnected)gapless.failed=true;
    }
    gaplessPrepareOutput();
}
void gaplessCloudReply(const std::string& op,const Json& result) {
    const auto token=gaplessCloudToken;
    if(result.value("fatal",false)) {cloudBridge.stop();cloudInitialized=false;cloudConnected=false;}
    try {
        const auto data=result.value("data",Json::object());
        const bool active=gapless.token==token && gaplessValid(audioBackend().snapshot());
        if(op=="audio_prepared" && result.value("ok",false) && data.value("status","")=="ready") {
            const auto audio=data.at("data");const auto path=cloud::toWide(audio.at("path").get<std::string>());
            if(!active) {if(!path.empty())retiredAudioCaches.push_back(path);return;}
            if(path.empty() || !audio.at("track").is_object())throw std::runtime_error("Invalid prepared audio");
            gapless.data=audio;gapless.path=path;gapless.track=audio.at("track");gaplessPrepareOutput();
        } else if(active && (!result.value("ok",false) || data.value("status","")=="failed"))gapless.failed=true;
    } catch(...) {if(gapless.token==token)gapless.failed=true;}
}
void gaplessCloudTick() {
    if(applicationClosing || cloudBusy() || !libraryNextPlay.is_null() || cloudAutoNext || unplayableSkipAt)return;
    if(!gaplessCancelled.empty()) {
        gaplessCloudToken=*gaplessCancelled.begin();gaplessCancelled.erase(gaplessCancelled.begin());
        cloudRequest({{"op","audio_cancel"},{"token",std::to_string(gaplessCloudToken)}});return;
    }
    if(!gapless.token || !gapless.remote || gapless.failed || !gapless.data.is_null() || !cloudInitialized)return;
    gaplessCloudToken=gapless.token;
    if(!gapless.started) {
        gapless.started=true;
        auto dir=gaplessCacheDir();
        cloudRequest({{"op","audio_prepare"},{"token",std::to_string(gapless.token)},{"id",gapless.requestedId},
            {"track",playbackTrack(gapless.track)},{"sources",gapless.track.value("sources",Json::array())},
            {"cacheDir",dir.u8string()},{"defaultQuality",defaultAudioQuality}});
    } else if(GetTickCount64()>=gapless.pollAt) {
        gapless.pollAt=GetTickCount64()+500;
        cloudRequest({{"op","audio_prepared"},{"token",std::to_string(gapless.token)}});
    }
}
bool gaplessFallback() {
    if(!gapless.token || !gaplessValid(audioBackend().snapshot()))return false;
    const auto next=gapless;cancelGapless();
    if(playbackOrder=="repeat-one")return false;
    if(!next.path.empty() && next.data.is_object()) {
        // Keep the claimed file alive while the normal load path takes ownership.
        retiredAudioCaches.erase(std::remove(retiredAudioCaches.begin(),retiredAudioCaches.end(),next.path),retiredAudioCaches.end());
        if(!closeAudio())return true;
        const auto loaded=loadAndPlayAudio(next.path,next.track.value("id",""));
        gaplessApply(next,true);
        if(!loaded && !scheduleFailedTrackSkip(next.requestedId,0))notice(L"音频播放失败，请重试");
        return true;
    }
    if(playbackOrder=="repeat-one")return false;
    if(next.requestedId.rfind("local:",0)==0) {
        for(int i=0;i<(int)localTracks.size();++i)if(localTracks[i].value("id","")==next.requestedId){playLocal(i);return true;}
    } else if(cloudConnected) {schedulePlayback({{"source","queue"},{"id",next.requestedId}});return true;}
    return false;
}
