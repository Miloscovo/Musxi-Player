// Native-owned restart state. Only track metadata and a profile-relative cache reference
// are stored here; credentials remain in the existing protected session store.
Json playbackTrack(const Json& row) {
    if(!row.is_object())throw std::runtime_error("Invalid playback track");
    Json result=Json::object();
    for(const auto* key:{"id","name","artist","album","cover","hash","fileId","fileName","albumId","audioId"}) {
        const auto value=row.value(key,std::string{});
        if(value.size()>4096)throw std::runtime_error("Invalid playback metadata");
        result[key]=value;
    }
    if(result["id"].get<std::string>().empty())throw std::runtime_error("Missing playback ID");
    const auto duration=row.value("duration",0.0);
    if(!std::isfinite(duration) || duration<0 || duration>UINT32_MAX)throw std::runtime_error("Invalid duration");
    result["duration"]=duration;return result;
}
fs::path playbackCachePath(const fs::path& profile,const std::string& relative) {
    if(relative.empty())return {};
    const auto suffix=fs::u8path(relative);
    if(suffix.is_absolute() || suffix.has_root_name())throw std::runtime_error("Invalid cache reference");
    const auto root=fs::weakly_canonical(profile/L"cloud-cache");
    const auto file=fs::weakly_canonical(profile/suffix);
    const auto key=localPathKey(root)+L"\\";
    if(localPathKey(file).rfind(key,0)!=0)throw std::runtime_error("Cache outside profile");
    return file;
}
bool savePlaybackHistory(const fs::path& file) {
    auto temporary=file;temporary+=L".tmp";
    try {
        const auto audio=audioBackend().snapshot();
        Json queue=Json::array();for(const auto& row:cloudQueue)queue.push_back(playbackTrack(row));
        Json current=nullptr;std::string cache;
        if(audio.opened && currentPlaybackTrack.is_object() && currentPlaybackTrack.value("id","")==audioTrackId) {
            current=playbackTrack(currentPlaybackTrack);
            if(!cloudTempPath.empty()) {
                cache=fs::relative(fs::path(cloudTempPath),file.parent_path()).u8string();
                (void)playbackCachePath(file.parent_path(),cache);
            }
        }
        const auto bytes=Json{{"version",1},{"queue",queue},{"current",current},{"cache",cache},
            {"positionMs",audio.positionMs},{"mode",playbackOrder},{"quality",currentQuality},{"volume",audio.volumePercent}}.dump();
        if(bytes.size()>8*1024*1024 || queue.size()>10000)throw std::runtime_error("Playback record too large");
        {std::ofstream stream(temporary,std::ios::binary);stream<<bytes;stream.close();if(!stream)throw std::runtime_error("Playback write failed");}
        if(!MoveFileExW(temporary.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("Playback replace failed");
        retainedPlaybackCache=cache.empty()?L"":cloudTempPath;
        collectAudioCaches();return true;
    } catch(...) {
        DeleteFileW(temporary.c_str());notice(L"播放记录保存失败，请检查本机存储空间");return false;
    }
}
bool restorePlaybackHistory(const fs::path& file) {
    try {
        if(!fs::exists(file))return false;
        if(fs::file_size(file)>8*1024*1024)throw std::runtime_error("Playback record too large");
        std::ifstream stream(file,std::ios::binary);Json saved;stream>>saved;
        if(saved.at("version")!=1 || !saved.at("queue").is_array() || saved["queue"].size()>10000)throw std::runtime_error("Invalid playback record");
        Json queue=Json::array();std::set<std::string> ids;
        for(const auto& row:saved["queue"]) {auto track=playbackTrack(row);if(!ids.insert(track.at("id").get<std::string>()).second)throw std::runtime_error("Duplicate playback ID");queue.push_back(track);}
        const auto mode=saved.at("mode").get<std::string>(),quality=saved.at("quality").get<std::string>();
        if(mode!="sequential" && mode!="random" && mode!="repeat-one")throw std::runtime_error("Invalid playback mode");
        if(!quality.empty() && quality!="128" && quality!="320" && quality!="flac")throw std::runtime_error("Invalid playback quality");
        const auto position=saved.at("positionMs").get<std::int64_t>();
        const auto volume=saved.at("volume").get<int>();
        if(position<0 || position>UINT32_MAX || volume<0 || volume>100)throw std::runtime_error("Invalid playback position");
        const auto current=saved.at("current").is_null()?Json(nullptr):playbackTrack(saved.at("current"));
        auto path=playbackCachePath(file.parent_path(),saved.at("cache").get<std::string>());
        int localIndex=-1;
        if(current.is_object() && current.value("id","").rfind("local:",0)==0) {
            path.clear();for(int i=0;i<(int)localTracks.size();++i)if(localTracks[i].at("id")==current.at("id")) {
                localIndex=i;path=fs::u8path(localTracks[i].at("path").get<std::string>());break;
            }
        }
        cloudQueue=std::move(queue);playbackOrder=mode;currentQuality=quality;currentPlaybackTrack=current;
        cloudCurrent=-1;audioBackend().setVolume(volume);
        if(current.is_null())return true;
        cloudNowTitle=cloud::toWide(current.value("name",""));cloudNowArtist=cloud::toWide(current.value("artist",""));cloudNowCover=current.value("cover","");
        if(path.empty() || !fs::is_regular_file(path)) {notice(L"上次播放的音频已丢失，请从播放列表重新播放");return true;}
        localActive=localIndex>=0;localCurrent=localIndex;
        audioTrackId=current.at("id").get<std::string>();
        if(!localActive)retainedPlaybackCache=cloudTempPath=path.wstring();
        for(int i=0;i<(int)cloudQueue.size();++i)if(cloudQueue[i].at("id")==current.at("id")){cloudCurrent=i;break;}
        libraryPlayTrackId=audioTrackId;libraryPlayGeneration=++cloudGeneration;libraryPlayPaused=true;
        libraryOperationKind="audio";libraryOperationStatus="pending";libraryOperationError.clear();
        const auto loaded=audioBackend().load(path.wstring());
        if(loaded)audioBackend().seek(static_cast<std::uint32_t>(position));
        else {libraryOperationStatus="failed";libraryOperationError="无法恢复上次播放的音频";}
        syncAudioView();return true;
    } catch(...) {notice(L"播放记录读取失败，已跳过损坏的记录");return false;}
}
