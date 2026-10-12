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
    for(const auto& line:value.lines)lines.push_back({{"timeMs",line.timeMs},{"text",line.text}});
    return {{"status","ready"},{"source",source},{"id",id},{"platform",platform},{"origin",origin},
        {"kind",musxi::lyrics::kindName(value.kind)},{"lines",std::move(lines)}};
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
void stopLyrics() {
    lyricsCancelled=true;lyricsBridge.cancel();++lyricsGeneration;
    if(lyricsFuture.valid()) {try {lyricsFuture.get();} catch(...) {}}
    lyricsBridge.stop();
    lyricsCancelled=false;lyricsTrackId.clear();lyricsResult=nullptr;
}
void cancelLyrics() {
    lyricsCancelled=true;lyricsBridge.cancel();++lyricsGeneration;
    lyricsTrackId.clear();lyricsResult=nullptr;
}
void lyricsTick() {
    if(lyricsFuture.valid() && lyricsPlaybackId!=audioTrackId && !lyricsCancelled.load())cancelLyrics();
    if(!lyricsFuture.valid() || lyricsFuture.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready)return;
    Json result;
    try {result=lyricsFuture.get();} catch(...) {}
    lyricsBridge.stop();
    // A cancelled lookup may be partial; never cache it.
    if(!lyricsCancelled.load() && result.is_object() && result.value("id","")==lyricsTrackId &&
        result.value("generation",std::uint64_t{})==lyricsGeneration) {
        result.erase("generation");lyricsResult=std::move(result);lyricsResultUntil=GetTickCount64()+60000;
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
Json readOnlineLyrics(const Json& request,const fs::path& directory,const std::string& key,bool serviceReady) {
    using namespace musxi::lyrics;
    const auto source=request.at("source").get<std::string>(),id=request.at("id").get<std::string>();
    const auto platform=request.at("platform").get<std::string>();
    const auto cached=readCache(directory,key,lyricsNow());
    const auto reply=[&](const CacheEntry& value,const char* origin) {return lyricsReply(source,id,platform,origin,parse(value.text));};
    if(cached && cached->fresh)return reply(*cached,"cache");
    try {
        if(lyricsCancelled.load() || !serviceReady)throw std::runtime_error("lyrics unavailable");
        const auto response=lyricsBridge.call(request,15000);
        if(lyricsCancelled.load() || !response.value("ok",false))throw std::runtime_error("lyrics request");
        const auto& data=response.at("data");const auto outcome=data.at("outcome").get<std::string>();
        CacheEntry entry;
        if(outcome=="none")entry.none=true;
        else if(outcome=="found" && data.at("format")=="lrc") {
            entry.text=data.at("text").get<std::string>();
            if(entry.text.size()>MaxOnlineLyricsBytes || !normalizeText(entry.text) || entry.text.find('\0')!=std::string::npos ||
                parse(entry.text).kind==Kind::None)throw std::runtime_error("lyrics data");
        } else throw std::runtime_error("lyrics outcome");
        if(lyricsCancelled.load())throw std::runtime_error("lyrics cancelled");
        writeCache(directory,key,entry,lyricsNow());
        return reply(entry,"online");
    }catch(...) {
        if(!lyricsCancelled.load() && cached && !cached->none)return reply(*cached,"cache");
        return Json{{"id",id},{"error",true}};
    }
}
const Json* findLocalTrack(const std::string& id) {
    for(const auto& row:localTracks)if(row.value("id","")==id)return &row;
    return nullptr;
}
// Returns a reply for the requested song, starting a lookup when needed.
Json requestLyrics(const std::string& source,const std::string& id) {
    const auto pending=Json{{"status","pending"},{"source",source},{"id",id},{"platform",""},{"origin",""},{"kind","none"},{"lines",Json::array()}};
    lyricsTick();
    if(lyricsTrackId==id && !lyricsCancelled.load()) {
        if(lyricsResult.is_object() && GetTickCount64()<lyricsResultUntil) {
            if(lyricsResult.value("error",false)) {
                lyricsResult=nullptr;lyricsTrackId.clear();
                throw std::runtime_error("歌词获取失败，请检查网络后重试");
            }
            auto reply=lyricsResult;reply["source"]=source;return reply;
        }
        if(lyricsFuture.valid())return pending;
    }
    // One lookup at a time: cancel a stale song and let the caller poll again; never block here.
    if(lyricsFuture.valid()) {if(!lyricsCancelled.load())cancelLyrics();return pending;}
    lyricsCancelled=false;lyricsTrackId=id;lyricsResult=nullptr;lyricsPlaybackId=audioTrackId;
    const auto generation=++lyricsGeneration;
    if(id.rfind("local:",0)!=0) {
        const auto* track=findLyricsTrack(id);
        if(!track)throw std::out_of_range("歌曲信息不可用");
        const auto key=musxi::lyrics::onlineKey(id,track->value("hash",""));
        if(key.empty())throw std::invalid_argument("歌曲缺少有效标识");
        const auto platform=key.substr(0,key.find(':'));
        const auto saved=cloud::readSession();Json session=nullptr;
        if(saved.is_object())session=saved.value("platform","")=="multi"?saved.value("accounts",Json::object()).value(platform,Json(nullptr)):
            platform=="kugou"?saved:Json(nullptr);
        const auto profile=cloud::dataDir();
        const Json request={{"op","lyrics"},{"source",source},{"id",id},{"platform",platform},{"track",playbackTrack(*track)},
            {"profileDir",profile.u8string()},{"session",session}};
        std::wstring error;const bool ready=lyricsBridge.start(error,L"lyrics.cjs");
        lyricsFuture=std::async(std::launch::async,[request,directory=profile/L"lyrics-cache",key,ready,generation] {
            auto result=readOnlineLyrics(request,directory,key,ready);result["generation"]=generation;return result;
        });
        return pending;
    }
    const auto* row=findLocalTrack(id);
    if(!row) {lyricsTrackId.clear();throw std::out_of_range("本地歌曲不存在");}
    lyricsFuture=std::async(std::launch::async,[path=fs::u8path(row->at("path").get<std::string>()),source,id,generation] {
        auto result=readLocalLyrics(path,source,id);result["generation"]=generation;return result;
    });
    return pending;
}
