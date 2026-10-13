#include "match.hpp"
#include "cache.hpp"
#include <fstream>
#include <iomanip>
#include <sstream>
#include <mutex>
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace musxi::lyrics {
namespace {
namespace fs=std::filesystem;
std::recursive_mutex matchStoreMutex;
fs::path matchPath(const fs::path& directory,const MatchJson& identity) {
    std::uint64_t hash=14695981039346656037ull;
    for(unsigned char c:identity.at("path").get<std::string>()){hash^=c;hash*=1099511628211ull;}
    std::ostringstream name;name<<std::hex<<std::setw(16)<<std::setfill('0')<<hash<<".json";
    return directory/name.str();
}
void validate(const MatchJson& selection) {
    const auto text=selection.at("text").get<std::string>();auto normalized=text;
    const auto offset=selection.at("offsetMs").get<std::int64_t>();
    const auto& track=selection.at("track");
    const auto key=onlineKey(track.at("id").get<std::string>(),track.value("hash",""));
    if(key.empty() || selection.at("platform")!=key.substr(0,key.find(':')) ||
        text.size()>MaxOnlineLyricsBytes || text.find('\0')!=std::string::npos || !normalizeText(normalized) || normalized!=text ||
        offset < -MaxMatchOffset || offset > MaxMatchOffset || parse(text).kind==Kind::None)
        throw std::runtime_error("Invalid lyrics selection");
    // Version-1 snapshots have no translation fields; that means "not asked yet".
    const auto state=selection.value("translationState",std::string{"unknown"});
    const auto translation=selection.value("translation",std::string{});
    if(state=="available") {
        auto copy=translation;
        if(translation.size()>MaxOnlineLyricsBytes || translation.find('\0')!=std::string::npos || !normalizeText(copy) || copy!=translation ||
            judgeTranslation(text,translation)!=Translation::Available)throw std::runtime_error("Invalid lyrics translation");
    } else if((state!="unknown" && state!="none") || !translation.empty())throw std::runtime_error("Invalid lyrics translation");
}
}
MatchJson fileIdentity(const fs::path& audio) {
    auto path=fs::weakly_canonical(audio);
    if(!fs::is_regular_file(path))throw std::runtime_error("本地文件不存在或无法读取");
#ifdef _WIN32
    auto wide=path.wstring();CharLowerBuffW(wide.data(),static_cast<DWORD>(wide.size()));path=fs::path(wide);
#endif
    return {{"path",path.u8string()},{"size",fs::file_size(path)},{"modified",fs::last_write_time(path).time_since_epoch().count()}};
}
std::optional<MatchJson> readMatch(const fs::path& directory,const MatchJson& identity) {
    std::lock_guard<std::recursive_mutex> lock(matchStoreMutex);
    try {
        const auto path=matchPath(directory,identity);
        if(!fs::is_regular_file(path) || fs::file_size(path)>MaxOnlineLyricsBytes*12+65536)return {};
        std::ifstream stream(path,std::ios::binary);const auto record=MatchJson::parse(stream);
        if((record.at("version")!=1 && record.at("version")!=2) || record.at("identity")!=identity)return {};
        auto selection=record.at("selection");
        try {validate(selection);} catch(...) {
            // Translation damage must not discard the user's usable original.
            selection.erase("translationState");selection.erase("translation");validate(selection);
        }
        return selection;
    }catch(...){return {};}
}
void saveMatch(const fs::path& directory,const MatchJson& identity,const MatchJson& selection) {
    std::lock_guard<std::recursive_mutex> lock(matchStoreMutex);
    validate(selection);
    if(fileIdentity(fs::u8path(identity.at("path").get<std::string>()))!=identity)throw std::runtime_error("文件已变化，请重新匹配");
    fs::create_directories(directory);const auto target=matchPath(directory,identity);
    // Full path check also protects against an extremely unlikely filename hash collision.
    if(fs::exists(target)) {
        std::ifstream in(target);auto old=MatchJson::parse(in,nullptr,false);
        if(old.is_object() && old.contains("identity") && old["identity"].value("path","")!=identity.at("path"))
            throw std::runtime_error("歌词记录冲突");
    }
    auto temporary=target;temporary+=".tmp";
    try {
        {std::ofstream out(temporary,std::ios::binary|std::ios::trunc);
         out<<MatchJson{{"version",2},{"identity",identity},{"selection",selection}}.dump();out.close();
         if(!out)throw std::runtime_error("无法保存歌词，请检查存储空间");}
#ifdef _WIN32
        if(!MoveFileExW(temporary.c_str(),target.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("无法保存歌词");
#else
        fs::rename(temporary,target);
#endif
    }catch(...){std::error_code error;fs::remove(temporary,error);throw;}
}
void removeMatch(const fs::path& directory,const MatchJson& identity) {
    std::lock_guard<std::recursive_mutex> lock(matchStoreMutex);
    const auto path=matchPath(directory,identity);
    if(!fs::exists(path))return;
    std::ifstream in(path);if(!in)throw std::runtime_error("无法读取歌词记录");
    const auto record=MatchJson::parse(in,nullptr,false);in.close();
    if(record.is_object() && record.contains("identity") && record["identity"].is_object() &&
        record["identity"].contains("path") && record["identity"]["path"].is_string() &&
        record["identity"]["path"]!=identity.at("path"))throw std::runtime_error("歌词记录冲突");
    fs::remove(path);
}
bool updateMatch(const fs::path& directory,const MatchJson& identity,const MatchJson& expected,const MatchJson& selection) {
    std::lock_guard<std::recursive_mutex> lock(matchStoreMutex);
    if(readMatch(directory,identity)!=expected)return false;
    saveMatch(directory,identity,selection);return true;
}
Lyrics matchLyrics(const MatchJson& selection) {
    validate(selection);
    // Positive user offset means later, independent of the LRC header's convention;
    // it moves original and translation together after alignment.
    return parse(selection.at("text").get<std::string>(),selection.value("translation",std::string{}),
        selection.at("offsetMs").get<std::int64_t>()).lyrics;
}
}
