#pragma once
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>

namespace musxi::lyrics {
constexpr std::size_t MaxOnlineLyricsBytes=1024*1024;
struct CacheEntry {
    std::string text;
    bool none=false;
    bool fresh=false;
};
// Keys identify a platform recording, not a playlist row or a display title.
std::string onlineKey(const std::string& id,const std::string& hash);
std::optional<CacheEntry> readCache(const std::filesystem::path& directory,const std::string& key,std::int64_t now);
bool writeCache(const std::filesystem::path& directory,const std::string& key,const CacheEntry& entry,std::int64_t now);
}
