// Application-owned lyrics lookup. File reads and tag probing run off the
// Application thread; the UI polls with the same {source,id} it requested.
std::string lyricsTrackId;
Json lyricsResult=nullptr; // Last completed reply for lyricsTrackId.
std::future<Json> lyricsFuture;
std::atomic_bool lyricsCancelled{false};
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
    lyricsCancelled=true;
    if(lyricsFuture.valid()) {try {lyricsFuture.get();} catch(...) {}}
    lyricsCancelled=false;lyricsTrackId.clear();lyricsResult=nullptr;
}
void lyricsTick() {
    if(!lyricsFuture.valid() || lyricsFuture.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready)return;
    Json result;
    try {result=lyricsFuture.get();} catch(...) {}
    // A cancelled lookup may be partial; never cache it.
    if(!lyricsCancelled.load() && result.is_object() && result.value("id","")==lyricsTrackId)lyricsResult=std::move(result);
    else {lyricsResult=nullptr;lyricsTrackId.clear();}
    lyricsCancelled=false;
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
        if(lyricsResult.is_object()) {auto reply=lyricsResult;reply["source"]=source;return reply;}
        if(lyricsFuture.valid())return pending;
    }
    // One lookup at a time: cancel a stale song and let the caller poll again; never block here.
    if(lyricsFuture.valid()) {lyricsCancelled=true;return pending;}
    lyricsTrackId=id;lyricsResult=nullptr;
    if(id.rfind("local:",0)!=0) {
        // Online lyrics arrive in a later change through a dedicated bridge.
        lyricsResult=lyricsReply(source,id,id.rfind("netease:",0)==0?"netease":id.rfind("qq:",0)==0?"qq":"kugou","",{});
        return lyricsResult;
    }
    const auto* row=findLocalTrack(id);
    if(!row) {lyricsTrackId.clear();throw std::out_of_range("本地歌曲不存在");}
    lyricsFuture=std::async(std::launch::async,[path=fs::u8path(row->at("path").get<std::string>()),source,id] {
        return readLocalLyrics(path,source,id);
    });
    return pending;
}
