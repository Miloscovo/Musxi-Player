#include "match.hpp"
#include "cache.hpp"
#include <fstream>
#include <iomanip>
#include <sstream>
#ifdef _WIN32
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#endif

namespace musxi::lyrics {
namespace {
namespace fs=std::filesystem;
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
    try {
        const auto path=matchPath(directory,identity);
        if(!fs::is_regular_file(path) || fs::file_size(path)>MaxOnlineLyricsBytes*6+65536)return {};
        std::ifstream stream(path,std::ios::binary);const auto record=MatchJson::parse(stream);
        if(record.at("version")!=1 || record.at("identity")!=identity)return {};
        auto selection=record.at("selection");validate(selection);return selection;
    }catch(...){return {};}
}
void saveMatch(const fs::path& directory,const MatchJson& identity,const MatchJson& selection) {
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
         out<<MatchJson{{"version",1},{"identity",identity},{"selection",selection}}.dump();out.close();
         if(!out)throw std::runtime_error("无法保存歌词，请检查存储空间");}
#ifdef _WIN32
        if(!MoveFileExW(temporary.c_str(),target.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("无法保存歌词");
#else
        fs::rename(temporary,target);
#endif
    }catch(...){std::error_code error;fs::remove(temporary,error);throw;}
}
void removeMatch(const fs::path& directory,const MatchJson& identity) {
    const auto path=matchPath(directory,identity);
    if(!fs::exists(path))return;
    std::ifstream in(path);const auto record=MatchJson::parse(in);in.close();
    if(record.at("identity").at("path")!=identity.at("path"))throw std::runtime_error("歌词记录冲突");
    fs::remove(path);
}
Lyrics matchLyrics(const MatchJson& selection) {
    validate(selection);auto lyrics=parse(selection.at("text").get<std::string>());
    const auto offset=selection.at("offsetMs").get<std::int64_t>();
    // Positive user offset means later, independent of the LRC header's convention.
    if(lyrics.kind==Kind::Synced)for(auto& line:lyrics.lines)line.timeMs=std::max<std::int64_t>(0,line.timeMs+offset);
    return lyrics;
}
}
