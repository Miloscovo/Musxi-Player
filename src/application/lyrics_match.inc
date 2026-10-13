// A separate worker/bridge for the matching dialog; never mutates playback/search lists.
cloud::Bridge matchBridge;
std::future<Json> matchFuture;
std::atomic_bool matchCancelled{false};
std::uint64_t matchSequence=0;
std::string matchSession,matchJob,matchLocalId,matchPlaybackId,matchOperation;
Json matchIdentity,matchCandidates=Json::array(),matchSelection,matchResult;
bool matchHasMore=false;
void cancelMatch() {
    matchCancelled=true;matchBridge.cancel();matchSession.clear();matchCandidates=Json::array();matchSelection=nullptr;
}
void matchTick() {
    if(!matchSession.empty() && matchPlaybackId!=audioTrackId)cancelMatch();
    if(!matchFuture.valid() || matchFuture.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready)return;
    Json result;
    try{result=matchFuture.get();}catch(...){result={{"error","歌词匹配失败，请重试"}};}
    matchBridge.stop();
    if(matchCancelled.load())return;
    matchResult=result;
    if(result.contains("error"))return;
    if(matchOperation=="begin") {matchIdentity=result.at("identity");matchSelection=result.at("selection");}
    if(matchOperation=="search") {matchCandidates=result.at("tracks");matchHasMore=result.at("hasMore");matchSelection=nullptr;}
    if(matchOperation=="preview")matchSelection=result.at("selection");
    if(matchOperation=="save" || matchOperation=="reset") {matchSelection=result.at("selection");translationAttempted.erase(matchLocalId);cancelLyrics();}
}
void stopMatch() {
    cancelMatch();if(matchFuture.valid())try{matchFuture.get();}catch(...){}
    matchBridge.stop();
}
Json matchPreview(const Json& selection,const std::string& id) {
    if(!selection.is_object())return nullptr;
    auto result=lyricsReply("local",id,"local","matched",musxi::lyrics::matchLyrics(selection));
    result["offsetMs"]=selection.at("offsetMs");
    result["translation"]=translationReply(selection.value("translationState","unknown"),result);return result;
}
Json matchPublic() {
    Json result={{"status",matchFuture.valid()?"pending":"ready"},{"session",matchSession},{"job",matchJob},{"id",matchLocalId}};
    if(matchFuture.valid())return result;
    if(matchResult.contains("error"))throw std::runtime_error(matchResult.at("error").get<std::string>());
    Json candidates=Json::array();
    for(size_t i=0;i<matchCandidates.size();++i) {
        const auto& row=matchCandidates[i];
        candidates.push_back({{"key",std::to_string(i)},{"name",row.value("name","")},{"artist",row.value("artist","")},
            {"album",row.value("album","")},{"duration",row.value("duration",0.0)},{"platform",row.value("platform","")}});
    }
    result["candidates"]=candidates;result["hasMore"]=matchHasMore;
    result["preview"]=matchPreview(matchSelection,matchLocalId);
    result["selectedName"]=matchSelection.is_object()?matchSelection.at("track").value("name",""):"";
    result["platform"]=matchSelection.is_object()?matchSelection.value("platform",""):"";
    if(matchOperation=="begin")result["suggestion"]=matchResult.at("suggestion");
    return result;
}
Json requestMatch(const Json& params) {
    matchTick();
    const auto action=params.at("action").get<std::string>(),id=params.at("id").get<std::string>();
    if(id.size()>1024 || id.rfind("local:",0)!=0)throw std::invalid_argument("请选择本地歌曲");
    const auto* row=findLocalTrack(id);if(!row)throw std::invalid_argument("本地歌曲不存在");
    const auto session=params.value("session",std::string{});
    if(action=="cancel") {
        if(session==matchSession && id==matchLocalId)cancelMatch();
        return {{"status","cancelled"}};
    }
    if(action=="begin") {
        cancelMatch();matchTick();
        if(matchFuture.valid())return {{"status","busy"}};
        matchSession=std::to_string(++matchSequence);matchLocalId=id;matchPlaybackId=audioTrackId;
        matchIdentity=nullptr;matchSelection=nullptr;matchCandidates=Json::array();matchHasMore=false;
    } else if(session.empty() || session!=matchSession || id!=matchLocalId)throw std::invalid_argument("匹配会话已过期，请重新打开");
    if(action=="poll") {
        if(params.at("job")!=matchJob)throw std::invalid_argument("匹配请求已过期");
        return matchPublic();
    }
    if(matchFuture.valid())return {{"status","busy"}};
    if(action=="adjust") {
        if(!matchSelection.is_object() || !params.at("offsetMs").is_number_integer())throw std::invalid_argument("请先选择歌词");
        auto selection=matchSelection;selection["offsetMs"]=params.at("offsetMs");
        (void)musxi::lyrics::matchLyrics(selection);matchSelection=selection;
        matchOperation=action;matchResult=Json::object();return matchPublic();
    }
    if(action!="begin" && action!="search" && action!="preview" && action!="save" && action!="reset")throw std::invalid_argument("未知匹配操作");
    if(action!="begin" && !matchIdentity.is_object())throw std::invalid_argument("请重新打开匹配窗口");
    const auto audio=fs::u8path(row->at("path").get<std::string>()),profile=cloud::dataDir();
    const auto identity=matchIdentity;const auto duration=row->value("duration",0.0);
    Json request,selection=matchSelection;
    if(action=="search") {
        const auto platform=params.at("platform").get<std::string>(),keywords=params.at("keywords").get<std::string>();
        const auto page=params.at("page").get<int>();
        if((platform!="netease" && platform!="qq" && platform!="kugou") || keywords.empty() || keywords.size()>480 || page<1 || page>10)
            throw std::invalid_argument("搜索条件无效");
        request={{"op","lyrics_search"},{"platform",platform},{"keywords",keywords},{"page",page},{"profileDir",profile.u8string()}};
    }
    if(action=="preview") {
        const auto key=params.at("candidate").get<std::string>();
        if(key.empty() || key.size()>3 || key.find_first_not_of("0123456789")!=std::string::npos)throw std::invalid_argument("候选已过期");
        const auto index=std::stoul(key);if(index>=matchCandidates.size())throw std::invalid_argument("候选已过期");
        const auto& track=matchCandidates[index];
        request={{"op","lyrics"},{"id",track.at("id")},{"platform",track.at("platform")},{"track",track},{"profileDir",profile.u8string()}};
        if(track.at("platform")=="kugou")request["includeTranslation"]=true;
        const auto saved=cloud::readSession();const auto platform=track.at("platform").get<std::string>();
        request["session"]=saved.is_object()?(saved.value("platform","")=="multi"?saved.value("accounts",Json::object()).value(platform,Json(nullptr)):
            platform=="kugou"?saved:Json(nullptr)):Json(nullptr);
    }
    if(action=="save") {
        if(!selection.is_object())throw std::invalid_argument("请先预览有歌词的候选");
        if(!params.at("offsetMs").is_number_integer())throw std::invalid_argument("时间校正必须为整数毫秒");
        const auto offset=params.at("offsetMs").get<std::int64_t>();
        if(offset < -musxi::lyrics::MaxMatchOffset || offset > musxi::lyrics::MaxMatchOffset)throw std::invalid_argument("时间校正范围为前后 600 秒");
        selection["offsetMs"]=offset;
    }
    if(action=="search" || action=="preview")matchSelection=nullptr;
    matchCancelled=false;matchOperation=action;matchJob=std::to_string(++matchSequence);matchResult=nullptr;
    bool ready=true;
    if(action=="search" || action=="preview") {std::wstring error;ready=matchBridge.start(error,L"lyrics.cjs");}
    matchFuture=std::async(std::launch::async,[action,audio,profile,identity,duration,request,selection,ready]() mutable ->Json {
        try {
            const auto actual=musxi::lyrics::fileIdentity(audio);
            if(action!="begin" && actual!=identity)throw std::runtime_error("本地文件已变化，请重新打开匹配窗口");
            if(matchCancelled.load())throw std::runtime_error("匹配已取消");
            const auto directory=profile/L"lyrics-matches";
            if(action=="begin") {
                musxi::LocalSongTags tags;
                try{tags=musxi::FfmpegDecoder::songTags(audio.wstring(),&matchCancelled);}catch(...){}
                if(tags.title.empty())tags.title=audio.stem().u8string();
                auto saved=musxi::lyrics::readMatch(directory,actual);
                return {{"identity",actual},{"selection",saved?*saved:Json(nullptr)},
                    {"suggestion",{{"title",tags.title},{"artist",tags.artist},{"album",tags.album},{"duration",duration}}}};
            }
            if(action=="save" || action=="reset") {
                if(matchCancelled.load())throw std::runtime_error("匹配已取消");
                if(action=="save")musxi::lyrics::saveMatch(directory,actual,selection);
                else {musxi::lyrics::removeMatch(directory,actual);selection=nullptr;}
                return {{"selection",selection}};
            }
            if(!ready)throw std::runtime_error("歌词服务不可用，请检查运行环境");
            const auto response=matchBridge.call(request,15000);
            if(matchCancelled.load() || !response.value("ok",false))throw std::runtime_error("请求失败，请检查网络后重试");
            const auto& data=response.at("data");
            if(action=="search") {
                Json tracks=Json::array();
                if(!data.at("tracks").is_array() || data.at("tracks").size()>30)throw std::runtime_error("搜索结果无效");
                for(const auto& candidate:data.at("tracks")) {
                    auto track=playbackTrack(candidate);const auto key=musxi::lyrics::onlineKey(track.value("id",""),track.value("hash",""));
                    if(key.empty() || key.substr(0,key.find(':'))!=request.at("platform"))continue;
                    track["platform"]=request.at("platform");track.erase("sources");tracks.push_back(track);
                }
                return {{"tracks",tracks},{"hasMore",data.value("hasMore",false)}};
            }
            if(data.at("outcome")=="none")return {{"selection",nullptr}};
            if(data.at("outcome")!="found" || data.at("format")!="lrc")throw std::runtime_error("歌词格式无效");
            auto text=data.at("text").get<std::string>();
            if(text.size()>musxi::lyrics::MaxOnlineLyricsBytes || !musxi::lyrics::normalizeText(text))throw std::runtime_error("歌词内容无效");
            // The snapshot keeps the translation of this exact version alongside its original.
            musxi::lyrics::CacheEntry entry;entry.text=text;attachTranslation(entry,data);
            selection={{"text",text},{"offsetMs",0},{"platform",request.at("platform")},{"track",request.at("track")},
                {"translationState",musxi::lyrics::translationName(entry.translationState)},{"translation",entry.translation}};
            (void)musxi::lyrics::matchLyrics(selection);
            return {{"selection",selection}};
        }catch(const std::exception& error){return {{"error",error.what()}};}
    });
    return matchPublic();
}
