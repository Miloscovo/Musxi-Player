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
std::int64_t lyricsNow();
std::string lookupReason(const Json& value,const char* field="reason",const char* fallback="internal_error") {
    static const std::set<std::string> reasons{"not_provided","network","timeout","platform_error","invalid_data",
        "unsupported_format","version_mismatch","not_bound","not_synced","no_lyrics","internal_error"};
    const auto found=value.find(field);
    return found!=value.end() && found->is_string() && reasons.count(found->get<std::string>())?found->get<std::string>():fallback;
}
void translationLookup(Json& reply,const char* status,const std::string& reason={},bool canRefresh=true) {
    reply["translationLookup"]={{"status",status},{"canRefresh",canRefresh}};
    if(!reason.empty())reply["translationLookup"]["reason"]=reason;
    if(std::string(status)=="pending")reply["translationPending"]=true;
    else reply.erase("translationPending");
    // Kept for old UIs, including their formerly omitted KuGou retry path.
    reply["translationRetryable"]=canRefresh;
}
void translationSupport(Json& reply) {
    if(reply.at("kind")=="none")translationLookup(reply,"unsupported","no_lyrics",false);
    else if(reply.at("kind")!="synced")translationLookup(reply,"unsupported","not_synced",false);
    else if(reply.at("platform")=="local" && reply.at("origin")!="matched")translationLookup(reply,"unsupported","not_bound",false);
    else translationLookup(reply,"idle");
}
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
    Json reply={{"status","ready"},{"source",source},{"id",id},{"platform",platform},{"origin",origin},
        {"kind",musxi::lyrics::kindName(value.kind)},{"lines",std::move(lines)},{"translation",translated?"available":"none"}};
    translationSupport(reply);return reply;
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
Json readLocalLyrics(const fs::path& audio,const std::string& source,const std::string& id,bool forceTranslation=false) {
    try {
        const auto identity=musxi::lyrics::fileIdentity(audio);
        const auto selected=musxi::lyrics::readMatch(cloud::dataDir()/L"lyrics-matches",identity);
        if(selected) {
            auto reply=lyricsReply(source,id,"local","matched",musxi::lyrics::matchLyrics(*selected));
            reply["offsetMs"]=selected->at("offsetMs");
            reply["translation"]=translationReply(selected->value("translationState","unknown"),reply);
            reply["translationFileIdentity"]=identity;reply["translationSelection"]=*selected;
            const auto checked=selected->find("translationCheckedAt");
            const bool fresh=checked!=selected->end() && checked->is_number_integer() && *checked>0 && *checked<=lyricsNow() &&
                lyricsNow()-checked->get<std::int64_t>()<7ll*86400;
            if(reply["kind"]=="synced" && (forceTranslation || reply["translation"]=="unknown" || (reply["translation"]=="none" && !fresh)))
                reply["translationRefresh"]=true;
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
// Retain failures across the 60-second result cache, scoped to the actual lyrics/binding.
std::map<std::string,std::pair<std::string,Json>> translationOutcomes;
std::string translationBasis;
std::string translationManualId; // Retains a click while an old worker drains.
const wchar_t* translationService=L"lyrics.cjs"; // Tests substitute an offline fixture.
void cancelTranslation() {
    if(!translationTrackId.empty())translationAttempted.erase(translationTrackId);
    translationCancelled=true;translationBridge.cancel();translationTrackId.clear();
    if(lyricsResult.is_object() && lyricsResult.value("translationPending",false))translationSupport(lyricsResult);
}
void stopLyrics() {
    lyricsCancelled=true;lyricsBridge.cancel();++lyricsGeneration;
    if(lyricsFuture.valid()) {try {lyricsFuture.get();} catch(...) {}}
    lyricsBridge.stop();
    cancelTranslation();
    if(translationFuture.valid()) {try {translationFuture.get();} catch(...) {}}
    translationBridge.stop();translationCancelled=false;
    lyricsCancelled=false;lyricsTrackId.clear();lyricsResult=nullptr;
    translationAttempted.clear();translationOutcomes.clear();translationManualId.clear();
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
    } else if(current && translationGeneration==lyricsGeneration) {
        const bool supported=lyricsResult.value("translationRetryable",false);
        translationLookup(lyricsResult,"failed",lookupReason(result),supported);
    }
    if(current && translationGeneration==lyricsGeneration && lyricsResult.contains("translationLookup")) {
        if(translationOutcomes.size()>=256)translationOutcomes.erase(translationOutcomes.begin());
        translationOutcomes[translationTrackId]={translationBasis,lyricsResult.at("translationLookup")};
    }
    translationTrackId.clear();translationCancelled=false;
}
void startTranslation() {
    if(!lyricsResult.is_object())return;
    const auto id=lyricsResult.value("id","");
    const bool newContext=lyricsResult.contains("translationFileIdentity") || lyricsResult.contains("translationOriginal") ||
        lyricsResult.contains("translationSelection");
    if(!newContext && !lyricsResult.value("translationRefresh",false))return;
    // Save refresh context even when no automatic lookup is necessary: a later
    // manual check of available/none must not reload or clear visible lyrics.
    if(lyricsResult.contains("translationFileIdentity")) {
        lyricsRefresh["identity"]=lyricsResult.at("translationFileIdentity");lyricsResult.erase("translationFileIdentity");
    }
    for(const auto* field:{"translationSelection","translationOriginal","translationSavedAt"})if(lyricsResult.contains(field)) {
        lyricsRefresh[field]=lyricsResult.at(field);lyricsResult.erase(field);
    }
    // Only build the comparison token when a result arrives or a task starts,
    // never on every Application tick while displaying a settled lyric page.
    if(lyricsResult.value("translationRefresh",false) && translationFuture.valid()) {translationLookup(lyricsResult,"pending");return;}
    const auto context=lyricsRefresh.is_object()?lyricsRefresh:Json::object();
    const auto basisText=lyricsResult.value("lines",Json::array()).dump()+context.value("identity",Json(nullptr)).dump()+
        context.value("translationSelection",Json(nullptr)).dump();
    // Diagnostic reuse only; never used to authorize a save or establish version identity.
    const auto basis=std::to_string(std::hash<std::string>{}(basisText))+":"+std::to_string(basisText.size());
    if(!lyricsResult.value("translationRefresh",false)) {
        const auto remembered=translationOutcomes.find(id);
        if(remembered!=translationOutcomes.end() && remembered->second.first==basis &&
            lyricsResult.value("translationLookup",Json::object()).value("status","")=="idle")
            lyricsResult["translationLookup"]=remembered->second.second;
        return;
    }
    lyricsResult.erase("translationRefresh");
    if(!lyricsRefresh.is_object() || !lyricsResult.value("translationRetryable",false))return;
    translationBasis=basis;
    const auto previous=translationOutcomes.find(id);
    if(previous!=translationOutcomes.end() && previous->second.first!=translationBasis) {
        translationOutcomes.erase(previous);translationAttempted.erase(id);
    }
    if(translationAttempted.size()>=256 && !translationAttempted.count(id)) {
        translationOutcomes.erase(*translationAttempted.begin());translationAttempted.erase(translationAttempted.begin());
    }
    if(!translationAttempted.insert(id).second) {
        translationSupport(lyricsResult);
        const auto remembered=translationOutcomes.find(id);
        if(remembered!=translationOutcomes.end())lyricsResult["translationLookup"]=remembered->second.second;
        return;
    }
    translationCancelled=false;translationTrackId=id;translationLookup(lyricsResult,"pending");
    translationGeneration=lyricsGeneration;
    // Bridge lifetime stays on the Application thread, like the other bridges.
    std::wstring error;(void)translationBridge.start(error,translationService);
    translationFuture=std::async(std::launch::async,[refresh=lyricsRefresh] {return refreshTranslation(refresh);});
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
        if(translationManualId==lyricsTrackId) {
            if(lyricsResult.value("translationRetryable",false))lyricsResult["translationRefresh"]=true;
            translationAttempted.erase(lyricsTrackId);translationOutcomes.erase(lyricsTrackId);translationManualId.clear();
        }
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
    if(data.contains("krc")) {
        auto extracted=extractKrc(data.at("krc").get<std::string>());
        if(!retimeKrc(extracted,entry.text)) {entry.translationReason="version_mismatch";return;}
        entry.translationState=extracted.state;entry.translation=extracted.translation;entry.translationReason=extracted.reason;return;
    }
    const auto state=data.value("translationState",std::string{"unknown"});
    if(state=="none") {entry.translationState=Translation::None;entry.translationReason="not_provided";return;}
    entry.translationReason=lookupReason(data,"translationReason","invalid_data");
    if(state!="available" || !data.contains("translation") || !data.at("translation").is_string())return;
    auto text=data.at("translation").get<std::string>();
    if(text.size()>MaxOnlineLyricsBytes || !normalizeText(text) || text.find('\0')!=std::string::npos)return;
    // An incompatible translation is unusable, not evidence the platform has none.
    if(judgeTranslation(entry.text,text)==Translation::Available) {
        entry.translationState=Translation::Available;entry.translation=std::move(text);entry.translationReason.clear();
    } else {
        const auto aligned=parse(entry.text,text);
        entry.translationReason=aligned.candidates==0?"not_provided":"version_mismatch";
        if(aligned.lyrics.kind==Kind::Synced && parse(text).kind==Kind::Synced && aligned.candidates==0)entry.translationState=Translation::None;
        else if(aligned.candidates==0)entry.translationReason="invalid_data";
    }
    } catch(...) {entry.translationState=Translation::Unknown;entry.translation.clear();entry.translationReason="invalid_data";}
}
// Validates one service reply; throws so callers keep their cached copy.
musxi::lyrics::CacheEntry lyricsEntry(const Json& data,const std::optional<musxi::lyrics::CacheEntry>& cached) {
    using namespace musxi::lyrics;
    const auto outcome=data.at("outcome").get<std::string>();
    CacheEntry entry;
    if(outcome=="none") {entry.none=true;return entry;}
    if(outcome!="found" || (data.at("format")!="lrc" && data.at("format")!="krc"))throw std::runtime_error("lyrics outcome");
    entry.text=data.at("text").get<std::string>();
    if(data.at("format")=="krc") {
        const auto extracted=extractKrc(entry.text);
        entry.text=extracted.original;entry.translation=extracted.translation;entry.translationState=extracted.state;entry.translationReason=extracted.reason;
    }
    if(entry.text.size()>MaxOnlineLyricsBytes || !normalizeText(entry.text) || entry.text.find('\0')!=std::string::npos ||
        parse(entry.text).kind==Kind::None)throw std::runtime_error("lyrics data");
    if(data.at("format")=="lrc")attachTranslation(entry,data);
    // A transient translation failure keeps a translation already verified for the same original.
    if(entry.translationState!=Translation::Available && cached && cached->text==entry.text &&
        cached->translationState==Translation::Available) {entry.translationState=cached->translationState;entry.translation=cached->translation;}
    return entry;
}
Json readOnlineLyrics(const Json& request,const fs::path& directory,const std::string& key,bool serviceReady,bool forceTranslation=false) {
    using namespace musxi::lyrics;
    const auto source=request.at("source").get<std::string>(),id=request.at("id").get<std::string>();
    const auto platform=request.at("platform").get<std::string>();
    const auto cached=readCache(directory,key,lyricsNow());
    const auto reply=[&](const CacheEntry& value,const char* origin) {
        auto result=lyricsReply(source,id,platform,origin,parse(value.text,value.translation).lyrics);
        result["translation"]=translationReply(translationName(value.translationState),result);
        result["translationOriginal"]=value.text;result["translationSavedAt"]=value.savedAt;
        return result;
    };
    // Fresh originals show at once; a missing translation is fetched afterwards by the
    // translation worker, so upgrading from v0.4 never delays the lyrics page.
    if(cached && (cached->fresh || (forceTranslation && !cached->none))) {
        auto result=reply(*cached,"cache");
        if(result["kind"]=="synced" && (forceTranslation || !cached->translationFresh))result["translationRefresh"]=true;
        return result;
    }
    std::string fetchFailure="internal_error";
    try {
        if(lyricsCancelled.load() || !serviceReady)throw std::runtime_error("lyrics unavailable");
        const auto response=lyricsBridge.call(request,15000);
        if(lyricsCancelled.load() || !response.value("ok",false)) {
            fetchFailure=lookupReason(response);throw std::runtime_error("lyrics request");
        }
        fetchFailure="invalid_data";
        auto entry=lyricsEntry(response.at("data"),cached);
        if(lyricsCancelled.load())throw std::runtime_error("lyrics cancelled");
        const auto acquired=lyricsNow();entry.savedAt=acquired;entry.translationCheckedAt=acquired;
        writeCache(directory,key,entry,acquired);
        auto result=reply(entry,"online");
        if(result["kind"]=="synced" && platform!="kugou")
            translationLookup(result,entry.translationState==Translation::Unknown ||
                (!entry.translationReason.empty() && entry.translationReason!="not_provided")?"failed":"succeeded",entry.translationReason);
        // A new KuGou LRC shows first, then the independent worker downloads KRC.
        if(platform=="kugou" && result["kind"]=="synced" && entry.translationState==Translation::Unknown)result["translationRefresh"]=true;
        return result;
    }catch(...) {
        if(!lyricsCancelled.load() && cached && !cached->none) {
            auto result=reply(*cached,"cache");
            if(result["kind"]=="synced")translationLookup(result,"failed",fetchFailure);
            if(forceTranslation && result["kind"]=="synced")result["translationRefresh"]=true;
            return result;
        }
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
    const auto failed=[&](const std::string& reason) {return Json{{"id",id},{"status","failed"},{"reason",reason}};};
    try {
        const bool online=refresh.contains("request");
        Json request,selection,identity;
        fs::path directory;
        std::optional<CacheEntry> cached;
        std::string key,original;
        if(online) {
            request=refresh.at("request");directory=fs::u8path(refresh.at("directory").get<std::string>());
            key=refresh.at("key").get<std::string>();cached=readCache(directory,key,lyricsNow());
            if(!cached || cached->none || cached->text!=refresh.at("translationOriginal") ||
                cached->savedAt!=refresh.at("translationSavedAt"))return failed("version_mismatch");
            original=cached->text;
        } else {
            const auto audio=fs::u8path(refresh.at("path").get<std::string>());
            directory=cloud::dataDir()/L"lyrics-matches";identity=fileIdentity(audio);
            if(!refresh.contains("identity") || refresh.at("identity")!=identity)return failed("version_mismatch");
            const auto selected=readMatch(directory,identity);
            if(!selected || !refresh.contains("translationSelection") || *selected!=refresh.at("translationSelection"))return failed("version_mismatch");
            selection=*selected;original=selection.at("text").get<std::string>();
            const auto platform=selection.at("platform").get<std::string>();const auto& track=selection.at("track");
            request={{"op","lyrics"},{"id",track.at("id")},{"platform",platform},{"track",track},
                {"profileDir",cloud::dataDir().u8string()},{"session",lyricsSession(cloud::readSession(),platform)}};
        }
        if(request.at("platform")=="kugou")request["translationOnly"]=true;
        if(translationCancelled.load() || !translationBridge.running())return failed("internal_error");
        const auto response=translationBridge.call(request,15000);
        if(translationCancelled.load())return failed("internal_error");
        if(!response.value("ok",false))return failed(lookupReason(response));
        const auto& data=response.at("data");
        if(data.at("outcome")=="none")return failed("no_lyrics");
        auto fetched=lyricsEntry(data,std::nullopt);
        // All three platforms must prove original compatibility, even when the
        // newly returned translation happens to use the same timestamps.
        if(!sameOriginal(original,fetched.text))return failed("version_mismatch");
        if(request.at("platform")=="kugou") {
            KrcLyrics extracted{fetched.text,fetched.translation,fetched.translationState};
            if(!retimeKrc(extracted,original))return failed("version_mismatch");
            fetched.translation=std::move(extracted.translation);
        }
        if(fetched.translationState==Translation::Unknown)return failed(fetched.translationReason.empty()?"invalid_data":fetched.translationReason);
        if(fetched.translationState==Translation::Available && judgeTranslation(original,fetched.translation)!=Translation::Available)
            return failed("version_mismatch");
        if(translationCancelled.load())return failed("internal_error");
        const auto checked=lyricsNow();
        Json result;
        if(online) {
            auto updated=*cached;
            if(fetched.translationState==Translation::Available || updated.translationState!=Translation::Available) {
                updated.translationState=fetched.translationState;updated.translation=fetched.translation;
            }
            if(!updateCacheTranslation(directory,key,*cached,updated,checked))return failed("internal_error");
            result=lyricsReply(source,id,request.at("platform").get<std::string>(),"cache",parse(updated.text,updated.translation).lyrics);
            result["translation"]=translationReply(translationName(updated.translationState),result);
            result["translationOriginal"]=updated.text;result["translationSavedAt"]=updated.savedAt;
        } else {
            auto updated=selection;
            if(fetched.translationState==Translation::Available || selection.value("translationState","unknown")!="available") {
                updated["translationState"]=translationName(fetched.translationState);updated["translation"]=fetched.translation;
            }
            updated["translationCheckedAt"]=checked;
            if(!updateMatch(directory,identity,selection,updated))return failed("version_mismatch");
            result=lyricsReply(source,id,"local","matched",matchLyrics(updated));result["offsetMs"]=updated.at("offsetMs");
            result["translation"]=translationReply(updated.at("translationState").get<std::string>(),result);
            result["translationFileIdentity"]=identity;result["translationSelection"]=updated;
        }
        translationLookup(result,"succeeded",fetched.translationState==Translation::None?"not_provided":"");
        return result;
    }catch(...) {return failed("invalid_data");}
}
const Json* findLocalTrack(const std::string& id) {
    for(const auto& row:localTracks)if(row.value("id","")==id)return &row;
    return nullptr;
}
// Returns a reply for the requested song, starting a lookup when needed.
Json requestLyrics(const std::string& source,const std::string& id,bool retryTranslation=false) {
    const auto pending=Json{{"status","pending"},{"source",source},{"id",id},{"platform",""},{"origin",""},{"kind","none"},{"lines",Json::array()}};
    lyricsTick();
    if(!translationManualId.empty() && translationManualId!=id)translationManualId.clear();
    if(retryTranslation)translationManualId=id;
    if(lyricsTrackId==id && !lyricsCancelled.load()) {
        if(lyricsResult.is_object() && (GetTickCount64()<lyricsResultUntil || lyricsResult.value("translationPending",false) || retryTranslation)) {
            if(lyricsResult.value("error",false)) {
                lyricsResult=nullptr;lyricsTrackId.clear();
                throw std::runtime_error("歌词获取失败，请检查网络后重试");
            }
            if(retryTranslation) {
                translationManualId.clear();
                if(lyricsResult.value("translationRetryable",false) && !lyricsResult.value("translationPending",false)) {
                    translationAttempted.erase(id);translationOutcomes.erase(id);lyricsResult["translationRefresh"]=true;startTranslation();
                }
            }
            auto reply=lyricsResult;reply["source"]=source;
            for(const auto* field:{"translationRefresh","translationFileIdentity","translationSelection","translationOriginal","translationSavedAt"})reply.erase(field);
            return reply;
        }
        if(lyricsFuture.valid())return pending;
    }
    // One lookup at a time: cancel a stale song and let the caller poll again; never block here.
    if(lyricsFuture.valid()) {if(!lyricsCancelled.load())cancelLyrics();return pending;}
    if(!translationTrackId.empty())cancelTranslation();
    lyricsCancelled=false;lyricsTrackId=id;lyricsResult=nullptr;lyricsPlaybackId=audioTrackId;lyricsRefresh=nullptr;
    const bool forceTranslation=translationManualId==id;translationManualId.clear();
    if(forceTranslation) {translationAttempted.erase(id);translationOutcomes.erase(id);}
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
        lyricsFuture=std::async(std::launch::async,[request,directory=profile/L"lyrics-cache",key,ready,generation,forceTranslation] {
            auto result=readOnlineLyrics(request,directory,key,ready,forceTranslation);result["generation"]=generation;return result;
        });
        return pending;
    }
    const auto* row=findLocalTrack(id);
    if(!row) {lyricsTrackId.clear();throw std::out_of_range("本地歌曲不存在");}
    lyricsRefresh={{"id",id},{"source",source},{"path",row->at("path")}};
    lyricsFuture=std::async(std::launch::async,[path=fs::u8path(row->at("path").get<std::string>()),source,id,generation,forceTranslation] {
        auto result=readLocalLyrics(path,source,id,forceTranslation);result["generation"]=generation;return result;
    });
    return pending;
}
