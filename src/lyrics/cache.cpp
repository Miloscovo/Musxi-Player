#include "cache.hpp"
#include "lyrics.hpp"
#include "../../third_party/json.hpp"
#include <algorithm>
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
using Json=nlohmann::json;
constexpr std::uintmax_t MaxCacheBytes=16*1024*1024;
constexpr std::size_t MaxEntries=256;
bool identifier(const std::string& s,bool digits=false) {
    return !s.empty() && s.size()<=128 && std::all_of(s.begin(),s.end(),[digits](unsigned char c) {
        return (c>='0' && c<='9') || (!digits && ((c>='a' && c<='z') || (c>='A' && c<='Z')));
    });
}
fs::path cachePath(const fs::path& directory,const std::string& key) {
    // Compact portable filename. The full key is checked on read, so a hash collision is a miss.
    std::uint64_t hash=14695981039346656037ull;
    for(unsigned char c:key){hash^=c;hash*=1099511628211ull;}
    std::ostringstream name;name<<std::hex<<std::setw(16)<<std::setfill('0')<<hash<<".json";
    return directory/name.str();
}
bool valid(const CacheEntry& entry) {
    if(entry.none)return entry.text.empty();
    auto text=entry.text;
    return !text.empty() && text.size()<=MaxOnlineLyricsBytes && normalizeText(text) &&
        text.find('\0')==std::string::npos && parse(text).kind!=Kind::None;
}
void trim(const fs::path& directory) {
    std::vector<fs::directory_entry> files;std::uintmax_t bytes=0;
    for(const auto& file:fs::directory_iterator(directory))if(file.is_regular_file() && file.path().extension()==".json") {
        files.push_back(file);bytes+=file.file_size();
    }
    std::sort(files.begin(),files.end(),[](const auto& a,const auto& b){return a.last_write_time()<b.last_write_time();});
    auto count=files.size();
    for(const auto& file:files) {
        if(count<=MaxEntries && bytes<=MaxCacheBytes)break;
        const auto size=file.file_size();if(fs::remove(file)){bytes-=size;--count;}
    }
}
}
std::string onlineKey(const std::string& id,const std::string& hash) {
    if(id.rfind("netease:",0)==0)return identifier(id.substr(8),true)?id:std::string{};
    if(id.rfind("qq:",0)==0)return identifier(id.substr(3))?id:std::string{};
    if(id.rfind("local:",0)==0 || hash.size()!=32)return {};
    auto lower=hash;
    for(auto& c:lower) {
        if(c>='A' && c<='F')c=static_cast<char>(c-'A'+'a');
        if(!((c>='0' && c<='9') || (c>='a' && c<='f')))return {};
    }
    return "kugou:"+lower;
}
std::optional<CacheEntry> readCache(const fs::path& directory,const std::string& key,std::int64_t now) {
    try {
        if(key.empty())return {};
        const auto path=cachePath(directory,key);
        if(!fs::is_regular_file(path) || fs::file_size(path)>MaxOnlineLyricsBytes*6+4096)return {};
        std::ifstream input(path,std::ios::binary);const auto json=Json::parse(input);
        if(json.at("version")!=1 || json.at("key")!=key || json.at("format")!="lrc")return {};
        const auto saved=json.at("savedAt").get<std::int64_t>();
        if(saved<0 || saved>now)return {};
        CacheEntry entry{json.at("text").get<std::string>(),json.at("none").get<bool>(),false};
        if(!valid(entry))return {};
        entry.fresh=now-saved<(entry.none?86400ll:30ll*86400);
        return entry;
    }catch(...){return {};}
}
bool writeCache(const fs::path& directory,const std::string& key,const CacheEntry& entry,std::int64_t now) {
    fs::path temporary;
    try {
        if(key.empty() || now<0 || !valid(entry))return false;
        fs::create_directories(directory);const auto target=cachePath(directory,key);
        temporary=target;temporary+=".tmp";
        const auto data=Json{{"version",1},{"key",key},{"format","lrc"},{"savedAt",now},{"none",entry.none},{"text",entry.text}}.dump();
        {std::ofstream output(temporary,std::ios::binary|std::ios::trunc);output<<data;output.close();if(!output)throw std::runtime_error("cache write");}
#ifdef _WIN32
        if(!MoveFileExW(temporary.c_str(),target.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("cache replace");
#else
        fs::rename(temporary,target);
#endif
        trim(directory);return true;
    }catch(...) {std::error_code error;if(!temporary.empty())fs::remove(temporary,error);return false;}
}
}
