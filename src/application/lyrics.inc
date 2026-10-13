// Application-owned lyrics lookup. File reads and tag probing run off the
// Application thread; the UI polls with the same {source,id} it requested.
std::string lyricsTrackId;
Json lyricsResult=nullptr; // Last completed reply for lyricsTrackId.
std::future<Json> lyricsFuture;
std::atomic_bool lyricsCancelled{false};
cloud::Bridge lyricsBridge;
std::uint64_t lyricsGeneration=0;
std::string lyricsPlaybackId;
ULONGLONG lyricsResultUntil=0;
Json lyricsReply(const std::string& source,const std::string& id,const std::string& platform,
    const char* origin,const musxi::lyrics::Lyrics& value) {
    Json lines=Json::array();
    bool translated=false;
    for(const auto& line:value.lines) {
        Json row={{"timeMs",line.timeMs},{"text",line.text}};
        if(!line.translation.empty()) {row["translation"]=line.translation;translated=true;}
        lines.push_back(std::move(row));
    }
    // "unknown" is never shown to the user as "no translation"; callers refine it.
    return {{"status","ready"},{"source",source},{"id",id},{"platform",platform},{"origin",origin},
        {"kind",musxi::lyrics::kindName(value.kind)},{"lines",std::move(lines)},{"translation",translated?"available":"none"}};
}
// Translation state of a selection/cache entry, as shown to the UI.
std::string translationReply(const std::string& state,const Json& reply) {
    return reply.at("translation")=="available"?"available":state=="unknown"?"unknown":"none";
}
// Sidecar files above this size are not lyrics; refuse instead of loading them.
constexpr std::uintmax_t MaxLyricsFile=1024*1024;
std::string lyricsText(std::string bytes) {
    if(musxi::lyrics::normalizeText(bytes))return bytes;
    // Legacy Chinese .lrc files are usually GBK regardless of the system locale,
    // so try GB18030 (a GBK superset) strictly before the ANSI code page.
    for(const UINT page:{54936u,static_cast<UINT>(CP_ACP)}) {
        const DWORD flags=page==CP_ACP?0:MB_ERR_INVALID_CHARS;
        const int size=MultiByteToWideChar(page,flags,bytes.data(),static_cast<int>(bytes.size()),nullptr,0);
        if(size<=0)continue;
        std::wstring wide(static_cast<size_t>(size),L'\0');
        MultiByteToWideChar(page,flags,bytes.data(),static_cast<int>(bytes.size()),wide.data(),size);
        return utf8(wide);
    }
    return {};
}
Json readLocalLyrics(const fs::path& audio,const std::string& source,const std::string& id) {
    try {
        const auto identity=musxi::lyrics::fileIdentity(audio);
        const auto selected=musxi::lyrics::readMatch(cloud::dataDir()/L"lyrics-matches",identity);
        if(selected) {
            auto reply=lyricsReply(source,id,"local","matched",musxi::lyrics::matchLyrics(*selected));
            reply["offsetMs"]=selected->at("offsetMs");
            reply["translation"]=translationReply(selected->value("translationState","unknown"),reply);
            reply["translationRetryable"]=reply["translation"]=="unknown" && selected->value("platform","")!="kugou";
            // v0.4 snapshots: fetch the translation for the saved recording later.
            if(reply["translation"]=="unknown" && selected->value("platform","")!="kugou") {
                reply["translationRefresh"]=true;reply["translationFileIdentity"]=identity;
            }
            return reply;
        }
    }catch(...) {} // Missing files still follow the existing local lookup path.
    std::optional<std::string> sidecar,embedded;
    auto file=audio;file.replace_extension(L".lrc");std::error_code error;
    if(fs::is_regular_file(file,error) && fs::file_size(file,error)<=MaxLyricsFile && !error) {
        std::ifstream stream(file,std::ios::binary);
        std::string bytes((std::istreambuf_iterator<char>(stream)),std::istreambuf_iterator<char>());
        if(stream.good() || stream.eof())sidecar=lyricsText(std::move(bytes));
    }
    if(!lyricsCancelled.load())try {
        auto tag=musxi::FfmpegDecoder::embeddedLyrics(audio.wstring(),&lyricsCancelled);
        if(!tag.empty())embedded=lyricsText(std::move(tag));
    } catch(...) {} // A missing or unreadable file simply has no embedded lyrics.
    const auto chosen=musxi::lyrics::chooseLocal(sidecar?&*sidecar:nullptr,embedded?&*embedded:nullptr);
    return lyricsReply(source,id,"local",musxi::lyrics::originName(chosen.origin),chosen.lyrics);
}
// Translation refresh: shown lyrics never wait for it. One background job fills
// a missing translation (v0.4 cache or snapshot) and the UI keeps polling.
std::future<Json> translationFuture;
std::atomic_bool translationCancelled{false};
cloud::Bridge translationBridge;
std::string translationTrackId;
std::uint64_t translationGeneration=0;
Json lyricsRefresh=nullptr; // What the current lookup would refresh: online request or local path.
std::set<std::string> translationAttempted; // Automatic attempts are bounded; explicit retry clears the song.
const wchar_t* translationService=L"lyrics.cjs"; // Tests substitute an offline fixture.
void cancelTranslation() {
    if(!translationTrackId.empty())translationAttempted.erase(translationTrackId);
    translationCancelled=true;translationBridge.cancel();translationTrackId.clear();
    if(lyricsResult.is_object())lyricsResult.erase("translationPending");
}
void stopLyrics() {
    lyricsCancelled=true;lyricsBridge.cancel();++lyricsGeneration;
    if(lyricsFuture.valid()) {try {lyricsFuture.get();} catch(...) {}}
    lyricsBridge.stop();
    cancelTranslation();
    if(translationFuture.valid()) {try {translationFuture.get();} catch(...) {}}
    translationBridge.stop();translationCancelled=false;
    lyricsCancelled=false;lyricsTrackId.clear();lyricsResult=nullptr;
}
void cancelLyrics() {
    lyricsCancelled=true;lyricsBridge.cancel();++lyricsGeneration;
    lyricsTrackId.clear();lyricsResult=nullptr;
    if(!translationTrackId.empty())cancelTranslation();
}
Json refreshTranslation(const Json& refresh);
void translationTick() {
    if(!translationFuture.valid() || translationFuture.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready)return;
    Json result;
    try {result=translationFuture.get();} catch(...) {}
    translationBridge.stop();
    const bool current=!translationCancelled.load() && !translationTrackId.empty() && translationTrackId==lyricsTrackId &&
        lyricsResult.is_object() && result.is_object() && result.value("id","")==translationTrackId;
    if(current && translationGeneration==lyricsGeneration && result.value("status","")=="ready") {
        result["source"]=lyricsResult.at("source");lyricsResult=std::move(result);lyricsResultUntil=GetTickCount64()+60000;
    } else if(lyricsResult.is_object())lyricsResult.erase("translationPending");
    translationTrackId.clear();translationCancelled=false;
}
void startTranslation() {
    if(!lyricsResult.is_object() || !lyricsResult.value("translationRefresh",false))return;
    const auto id=lyricsResult.value("id","");
    if(lyricsResult.contains("translationFileIdentity")) {
        lyricsRefresh["identity"]=lyricsResult.at("translationFileIdentity");lyricsResult.erase("translationFileIdentity");
    }
    // A cancelled worker must drain before another refresh starts. Keep the intent.
    if(translationFuture.valid()) {lyricsResult["translationPending"]=true;return;}
    lyricsResult.erase("translationRefresh");
    if(!lyricsRefresh.is_object() || !translationAttempted.insert(id).second)return;
    translationCancelled=false;translationTrackId=id;lyricsResult["translationPending"]=true;
    translationGeneration=lyricsGeneration;
    // Bridge lifetime stays on the Application thread, like the other bridges.
    std::wstring error;(void)translationBridge.start(error,translationService);
    translationFuture=std::async(std::launch::async,[refresh=std::move(lyricsRefresh)] {return refreshTranslation(refresh);});
    lyricsRefresh=nullptr;
}
void lyricsTick() {
    if((lyricsFuture.valid() || !translationTrackId.empty()) && lyricsPlaybackId!=audioTrackId && !lyricsCancelled.load())cancelLyrics();
    translationTick();
    startTranslation();
    if(!lyricsFuture.valid() || lyricsFuture.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready)return;
    Json result;
    try {result=lyricsFuture.get();} catch(...) {}
    lyricsBridge.stop();
    // A cancelled lookup may be partial; never cache it.
    if(!lyricsCancelled.load() && result.is_object() && result.value("id","")==lyricsTrackId &&
        result.value("generation",std::uint64_t{})==lyricsGeneration) {
        result.erase("generation");lyricsResult=std::move(result);lyricsResultUntil=GetTickCount64()+60000;
        startTranslation();
    }
    else {lyricsResult=nullptr;lyricsTrackId.clear();}
    lyricsCancelled=false;
}
std::int64_t lyricsNow() {
    return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();
}
const Json* findLyricsTrack(const std::string& id) {
    if(currentPlaybackTrack.is_object() && currentPlaybackTrack.value("id","")==id)return &currentPlaybackTrack;
    for(const auto* list:{&cloudQueue,&recentTracks,&searchTracks,&cloudTracks})for(const auto& row:*list) {
        if(row.value("id","")==id)return &row;
        if(row.contains("sources"))for(const auto& candidate:row["sources"])if(candidate.value("id","")==id)return &candidate;
    }
    return nullptr;
}
// Service translation fields are optional; anything unusable leaves the original intact.
void attachTranslation(musxi::lyrics::CacheEntry& entry,const Json& data) {
    using namespace musxi::lyrics;
    try {
    const auto state=data.value("translationState",std::string{"unknown"});
    if(state=="none") {entry.translationState=Translation::None;return;}
    if(state!="available" || !data.contains("translation") || !data.at("translation").is_string())return;
    auto text=data.at("translation").get<std::string>();
    if(text.size()>MaxOnlineLyricsBytes || !normalizeText(text) || text.find('\0')!=std::string::npos)return;
    // An incompatible translation is unusable, not evidence the platform has none.
    if(judgeTranslation(entry.text,text)==Translation::Available) {
        entry.translationState=Translation::Available;entry.translation=std::move(text);
    }
    } catch(...) {entry.translationState=Translation::Unknown;entry.translation.clear();}
}
// Validates one service reply; throws so callers keep their cached copy.
musxi::lyrics::CacheEntry lyricsEntry(const Json& data,const std::optional<musxi::lyrics::CacheEntry>& cached) {
    using namespace musxi::lyrics;
    const auto outcome=data.at("outcome").get<std::string>();
    CacheEntry entry;
    if(outcome=="none") {entry.none=true;return entry;}
    if(outcome!="found" || data.at("format")!="lrc")throw std::runtime_error("lyrics outcome");
    entry.text=data.at("text").get<std::string>();
    if(entry.text.size()>MaxOnlineLyricsBytes || !normalizeText(entry.text) || entry.text.find('\0')!=std::string::npos ||
        parse(entry.text).kind==Kind::None)throw std::runtime_error("lyrics data");
    attachTranslation(entry,data);
    // A transient translation failure keeps a translation already verified for the same original.
    if(entry.translationState==Translation::Unknown && cached && cached->text==entry.text &&
        cached->translationState==Translation::Available) {entry.translationState=cached->translationState;entry.translation=cached->translation;}
    return entry;
}
Json readOnlineLyrics(const Json& request,const fs::path& directory,const std::string& key,bool serviceReady) {
    using namespace musxi::lyrics;
    const auto source=request.at("source").get<std::string>(),id=request.at("id").get<std::string>();
    const auto platform=request.at("platform").get<std::string>();
    const auto cached=readCache(directory,key,lyricsNow());
    const auto reply=[&](const CacheEntry& value,const char* origin) {
        auto result=lyricsReply(source,id,platform,origin,parse(value.text,value.translation).lyrics);
        result["translation"]=translationReply(translationName(value.translationState),result);return result;
    };
    // Fresh originals show at once; a missing translation is fetched afterwards by the
    // translation worker, so upgrading from v0.4 never delays the lyrics page.
    if(cached && cached->fresh) {
        auto result=reply(*cached,"cache");
        // Kugou translations are not fetched yet, so they stay unknown without a refresh.
        if(!cached->none && !cached->translationFresh && platform!="kugou")result["translationRefresh"]=true;
        return result;
    }
    try {
        if(lyricsCancelled.load() || !serviceReady)throw std::runtime_error("lyrics unavailable");
        const auto response=lyricsBridge.call(request,15000);
        if(lyricsCancelled.load() || !response.value("ok",false))throw std::runtime_error("lyrics request");
        auto entry=lyricsEntry(response.at("data"),cached);
        if(lyricsCancelled.load())throw std::runtime_error("lyrics cancelled");
        writeCache(directory,key,entry,lyricsNow());
        return reply(entry,"online");
    }catch(...) {
        if(!lyricsCancelled.load() && cached && !cached->none)return reply(*cached,"cache");
        return Json{{"id",id},{"error",true}};
    }
}
Json lyricsSession(const Json& saved,const std::string& platform) {
    if(!saved.is_object())return nullptr;
    return saved.value("platform","")=="multi"?saved.value("accounts",Json::object()).value(platform,Json(nullptr)):
        platform=="kugou"?saved:Json(nullptr);
}
// Worker body. Online entries may take the newer original (they are a cache);
// a user-confirmed snapshot keeps its own original and only gains a translation
// that aligns with it, so the chosen version never changes silently.
Json refreshTranslation(const Json& refresh) {
    using namespace musxi::lyrics;
    const auto id=refresh.at("id").get<std::string>(),source=refresh.at("source").get<std::string>();
    const Json failed={{"id",id},{"status","failed"}};
    try {
        if(refresh.contains("request")) {
            const auto& request=refresh.at("request");const auto directory=fs::u8path(refresh.at("directory").get<std::string>());
            const auto key=refresh.at("key").get<std::string>();
            const auto cached=readCache(directory,key,lyricsNow());
            if(!translationBridge.running() || translationCancelled.load())return failed;
            const auto response=translationBridge.call(request,15000);
            if(translationCancelled.load() || !response.value("ok",false))return failed;
            auto entry=lyricsEntry(response.at("data"),cached);
            // A failed translation must not be cached as "none"; keep the old entry for a retry.
            if(entry.none || entry.translationState==Translation::Unknown || translationCancelled.load())return failed;
            writeCache(directory,key,entry,lyricsNow());
            auto result=lyricsReply(source,id,request.at("platform").get<std::string>(),"online",parse(entry.text,entry.translation).lyrics);
            result["translation"]=translationReply(translationName(entry.translationState),result);return result;
        }
        const auto audio=fs::u8path(refresh.at("path").get<std::string>());const auto directory=cloud::dataDir()/L"lyrics-matches";
        const auto identity=fileIdentity(audio);const auto selected=readMatch(directory,identity);
        if(refresh.contains("identity") && refresh.at("identity")!=identity)return failed;
        if(!selected || selected->value("translationState","unknown")!="unknown")return failed;
        const auto& track=selected->at("track");const auto platform=selected->at("platform").get<std::string>();
        if(platform=="kugou")return failed;
        const Json request={{"op","lyrics"},{"id",track.at("id")},{"platform",platform},{"track",track},
            {"profileDir",cloud::dataDir().u8string()},{"session",lyricsSession(cloud::readSession(),platform)}};
        if(translationCancelled.load() || !translationBridge.running())return failed;
        const auto response=translationBridge.call(request,15000);
        if(translationCancelled.load() || !response.value("ok",false))return failed;
        const auto& data=response.at("data");auto selection=*selected;
        if(data.at("outcome")=="none")return failed; // The platform lost the lyrics; keep the snapshot as is.
        auto entry=lyricsEntry(data,std::nullopt);
        if(entry.translationState==Translation::Unknown)return failed;
        // Translation aligned against the user's saved original, not the newer server text.
        if(entry.translationState==Translation::Available &&
            judgeTranslation(selection.at("text").get<std::string>(),entry.translation)!=Translation::Available)return failed;
        selection["translationState"]=translationName(entry.translationState);
        selection["translation"]=entry.translationState==Translation::Available?entry.translation:std::string{};
        // Never overwrite a choice the user changed while this request was running.
        if(translationCancelled.load() || !updateMatch(directory,identity,*selected,selection))return failed;
        auto result=lyricsReply(source,id,"local","matched",matchLyrics(selection));
        result["offsetMs"]=selection.at("offsetMs");
        result["translation"]=translationReply(selection.at("translationState").get<std::string>(),result);return result;
    }catch(...) {return failed;}
}
const Json* findLocalTrack(const std::string& id) {
    for(const auto& row:localTracks)if(row.value("id","")==id)return &row;
    return nullptr;
}
// Returns a reply for the requested song, starting a lookup when needed.
Json requestLyrics(const std::string& source,const std::string& id,bool retryTranslation=false) {
    const auto pending=Json{{"status","pending"},{"source",source},{"id",id},{"platform",""},{"origin",""},{"kind","none"},{"lines",Json::array()}};
    lyricsTick();
    if(retryTranslation) {translationAttempted.erase(id);cancelLyrics();}
    if(lyricsTrackId==id && !lyricsCancelled.load()) {
        if(lyricsResult.is_object() && GetTickCount64()<lyricsResultUntil) {
            if(lyricsResult.value("error",false)) {
                lyricsResult=nullptr;lyricsTrackId.clear();
                throw std::runtime_error("歌词获取失败，请检查网络后重试");
            }
            auto reply=lyricsResult;reply["source"]=source;reply.erase("translationRefresh");reply.erase("translationFileIdentity");return reply;
        }
        if(lyricsFuture.valid())return pending;
    }
    // One lookup at a time: cancel a stale song and let the caller poll again; never block here.
    if(lyricsFuture.valid()) {if(!lyricsCancelled.load())cancelLyrics();return pending;}
    if(!translationTrackId.empty())cancelTranslation();
    lyricsCancelled=false;lyricsTrackId=id;lyricsResult=nullptr;lyricsPlaybackId=audioTrackId;lyricsRefresh=nullptr;
    const auto generation=++lyricsGeneration;
    if(id.rfind("local:",0)!=0) {
        const auto* track=findLyricsTrack(id);
        if(!track)throw std::out_of_range("歌曲信息不可用");
        const auto key=musxi::lyrics::onlineKey(id,track->value("hash",""));
        if(key.empty())throw std::invalid_argument("歌曲缺少有效标识");
        const auto platform=key.substr(0,key.find(':'));
        const auto session=lyricsSession(cloud::readSession(),platform);
        const auto profile=cloud::dataDir();
        const Json request={{"op","lyrics"},{"source",source},{"id",id},{"platform",platform},{"track",playbackTrack(*track)},
            {"profileDir",profile.u8string()},{"session",session}};
        lyricsRefresh={{"id",id},{"source",source},{"request",request},{"directory",(profile/L"lyrics-cache").u8string()},{"key",key}};
        std::wstring error;const bool ready=lyricsBridge.start(error,L"lyrics.cjs");
        lyricsFuture=std::async(std::launch::async,[request,directory=profile/L"lyrics-cache",key,ready,generation] {
            auto result=readOnlineLyrics(request,directory,key,ready);result["generation"]=generation;return result;
        });
        return pending;
    }
    const auto* row=findLocalTrack(id);
    if(!row) {lyricsTrackId.clear();throw std::out_of_range("本地歌曲不存在");}
    lyricsRefresh={{"id",id},{"source",source},{"path",row->at("path")}};
    lyricsFuture=std::async(std::launch::async,[path=fs::u8path(row->at("path").get<std::string>()),source,id,generation] {
        auto result=readLocalLyrics(path,source,id);result["generation"]=generation;return result;
    });
    return pending;
}
