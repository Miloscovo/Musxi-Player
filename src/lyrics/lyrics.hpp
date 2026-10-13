#pragma once
#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace musxi::lyrics {
// Portable C++17 boundary: no Windows, FFmpeg, network or application state.
enum class Kind { None, Plain, Synced };
struct Line { std::int64_t timeMs=0; std::string text; std::string translation; };
struct Lyrics { Kind kind=Kind::None; std::vector<Line> lines; };
const char* kindName(Kind kind);
// Converts BOM-marked UTF-8/UTF-16 to UTF-8 in place. Returns false when the
// bytes are not valid UTF-8 so the caller can apply a platform code page.
bool normalizeText(std::string& bytes);
// Accepts LRC (multiple/enhanced timestamps, [offset:]) and decoded KRC text.
// KRC word timings are reduced to line timing in this phase.
Lyrics parse(std::string_view utf8);
// Attaches a platform translation to synced original lines by timestamp, after
// each text's own [offset:] and before clamping, never by line index. shiftMs
// (user correction, positive = later) is applied after alignment.
// candidates counts translated rows; matched counts those attached to a line.
struct Translated { Lyrics lyrics; std::size_t candidates=0,matched=0; };
Translated parse(std::string_view originalUtf8,std::string_view translationUtf8,std::int64_t shiftMs=0);
// Local priority: a usable sidecar .lrc corrects embedded tags.
enum class Origin { None, File, Embedded };
struct Selected { Origin origin=Origin::None; Lyrics lyrics; };
Selected chooseLocal(const std::string* sidecarUtf8,const std::string* embeddedUtf8);
const char* originName(Origin origin);
}
