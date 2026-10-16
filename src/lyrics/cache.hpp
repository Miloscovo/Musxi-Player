#pragma once
#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>

namespace musxi::lyrics {
constexpr std::size_t MaxOnlineLyricsBytes=1024*1024;
// Unknown: never asked (v0.4 cache, platform without translation support yet).
enum class Translation { Unknown, None, Available };
const char* translationName(Translation state);
struct CacheEntry {
    std::string text;
    bool none=false;
    bool fresh=false;
    bool translationFresh=false; // Unknown is never fresh; None is rechecked weekly.
    Translation translationState=Translation::Unknown;
    std::string translation; // Raw platform text, only when Available.
    std::int64_t savedAt=0; // Original acquisition time; translation refresh must preserve it.
    std::int64_t translationCheckedAt=0;
    std::string translationReason; // Transient diagnostics, never persisted as cache truth.
};
// Judges a platform translation against its original: Available only when
// most translated rows land on an original line.
Translation judgeTranslation(const std::string& text,const std::string& translation,std::int64_t shiftMs=0);
// Keys identify a platform recording, not a playlist row or a display title.
std::string onlineKey(const std::string& id,const std::string& hash);
std::optional<CacheEntry> readCache(const std::filesystem::path& directory,const std::string& key,std::int64_t now);
bool writeCache(const std::filesystem::path& directory,const std::string& key,const CacheEntry& entry,std::int64_t now);
// Compare-and-update under the cache lock: a late refresh cannot replace newer lyrics.
bool updateCacheTranslation(const std::filesystem::path& directory,const std::string& key,const CacheEntry& expected,
    const CacheEntry& replacement,std::int64_t now);
void removeCache(const std::filesystem::path& directory,const std::string& key);
}
