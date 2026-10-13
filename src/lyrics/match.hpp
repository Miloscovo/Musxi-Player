#pragma once
#include "lyrics.hpp"
#include "../../third_party/json.hpp"
#include <filesystem>
#include <optional>

namespace musxi::lyrics {
using MatchJson=nlohmann::json;
constexpr std::int64_t MaxMatchOffset=600000;
MatchJson fileIdentity(const std::filesystem::path& audio);
std::optional<MatchJson> readMatch(const std::filesystem::path& directory,const MatchJson& identity);
void saveMatch(const std::filesystem::path& directory,const MatchJson& identity,const MatchJson& selection);
// Atomic compare-and-update against matching-dialog saves/resets in this process.
bool updateMatch(const std::filesystem::path& directory,const MatchJson& identity,const MatchJson& expected,const MatchJson& selection);
void removeMatch(const std::filesystem::path& directory,const MatchJson& identity);
Lyrics matchLyrics(const MatchJson& selection);
}
