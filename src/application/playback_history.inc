// Native-owned restart state. Only track metadata and a profile-relative cache reference
// are stored here; credentials remain in the existing protected session store.
Json playbackTrack(const Json& row,bool source=false) {
    if(!row.is_object())throw std::runtime_error("Invalid playback track");
    Json result=Json::object();
    for(const auto* key:{"id","name","artist","album","cover","hash","fileId","fileName","albumId","audioId","platform","serviceId","mediaMid"}) {
        const auto value=row.value(key,std::string{});
        if(value.size()>4096)throw std::runtime_error("Invalid playback metadata");
        result[key]=value;
    }
    if(result["id"].get<std::string>().empty())throw std::runtime_error("Missing playback ID");
    const auto duration=row.value("duration",0.0);
    if(!std::isfinite(duration) || duration<0 || duration>UINT32_MAX)throw std::runtime_error("Invalid duration");
    result["duration"]=duration;
    if(row.contains("sources")) {
        if(source || !row["sources"].is_array() || row["sources"].size()>3)throw std::runtime_error("Invalid playback sources");
        result["sources"]=Json::array();
        for(const auto& candidate:row["sources"])result["sources"].push_back(playbackTrack(candidate,true));
    }
    if(!source && result["id"].get<std::string>().rfind("local:",0)!=0) {
        const auto platformOf=[](const std::string& id){return id.rfind("netease:",0)==0?"netease":id.rfind("qq:",0)==0?"qq":"kugou";};
        std::set<std::string> platforms{platformOf(result["id"].get<std::string>())};
        for(const auto& candidate:result.value("sources",Json::array()))platforms.insert(platformOf(candidate.at("id").get<std::string>()));
        result["platforms"]=Json::array();
        for(const auto* platform:{"netease","kugou","qq"})if(platforms.count(platform))result["platforms"].push_back(platform);
    }
    return result;
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
bool sameRecentTrack(const Json& a,const Json& b) {
    const auto id=a.value("id","");
    if(id==b.value("id",""))return true;
    if(id.rfind("local:",0)==0 || b.value("id","").rfind("local:",0)==0 || a.value("name","").empty())return false;
    for(const auto* field:{"name","artist","album"}) {
        const auto trim=[](const std::string& value) {
            auto text=cloud::toWide(value);
            const auto first=text.find_first_not_of(L" \t\r\n\v\f"),last=text.find_last_not_of(L" \t\r\n\v\f");
            return first==std::wstring::npos?std::wstring{}:text.substr(first,last-first+1);
        };
        const auto left=trim(a.value(field,"")),right=trim(b.value(field,""));
        if(left.empty() && right.empty())continue;
        if(CompareStringOrdinal(left.data(),static_cast<int>(left.size()),right.data(),static_cast<int>(right.size()),TRUE)!=CSTR_EQUAL)return false;
    }
    return true;
}
void prependRecentTrack(Json& recent,const Json& row) {
    auto track=playbackTrack(row);
    const auto id=track.at("id").get<std::string>();
    track["platform"]=id.rfind("local:",0)==0?"":id.rfind("netease:",0)==0?"netease":id.rfind("qq:",0)==0?"qq":"kugou";
    for(const auto& item:recent)if(sameRecentTrack(track,item) && id.rfind("local:",0)!=0) {
        Json sources=Json::array();std::set<std::string> platforms;
        const auto add=[&](const Json& candidate) {
            const auto sourceId=candidate.value("id","");
            const std::string platform=sourceId.rfind("netease:",0)==0?"netease":sourceId.rfind("qq:",0)==0?"qq":"kugou";
            if(platforms.insert(platform).second){auto source=candidate;source.erase("sources");sources.push_back(playbackTrack(source,true));}
        };
        add(track);for(const auto& source:track.value("sources",Json::array()))add(source);
        add(item);for(const auto& source:item.value("sources",Json::array()))add(source);
        track["sources"]=sources;track=playbackTrack(track);
    }
    recent.erase(std::remove_if(recent.begin(),recent.end(),[&](const Json& item){return sameRecentTrack(track,item);}),recent.end());
    recent.insert(recent.begin(),track);
    if(recent.size()>500)recent.erase(recent.begin()+500,recent.end());
}
void recordRecentTrack(const Json& row) {
    prependRecentTrack(recentTracks,row);
    playbackSaveAt=0;
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
        const auto bytes=Json{{"version",1},{"queue",queue},{"recent",recentTracks},{"current",current},{"cache",cache},
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
        const auto savedRecent=saved.value("recent",Json::array());
        if(!savedRecent.is_array() || savedRecent.size()>500)throw std::runtime_error("Invalid recent playback record");
        Json recent=Json::array();ids.clear();
        for(auto row=savedRecent.rbegin();row!=savedRecent.rend();++row) {auto track=playbackTrack(*row);if(!ids.insert(track.at("id").get<std::string>()).second)throw std::runtime_error("Duplicate recent ID");prependRecentTrack(recent,track);}
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
        recentTracks=std::move(recent);cloudQueue=std::move(queue);playbackOrder=mode;currentQuality=quality;currentPlaybackTrack=current;
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
