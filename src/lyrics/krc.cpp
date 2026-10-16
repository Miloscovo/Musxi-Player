#include "krc.hpp"
#include "lyrics.hpp"
#include "../../third_party/json.hpp"
#include <cctype>
#include <cstdio>
#include <optional>
#include <stdexcept>
#include <vector>

namespace musxi::lyrics {
namespace {
constexpr std::size_t MaxRows=5000,MaxLanguageBytes=256*1024,MaxRowBytes=16*1024;
std::string_view trim(std::string_view text) {
    while(!text.empty() && std::isspace(static_cast<unsigned char>(text.front())))text.remove_prefix(1);
    while(!text.empty() && std::isspace(static_cast<unsigned char>(text.back())))text.remove_suffix(1);
    return text;
}
std::optional<std::int64_t> number(std::string_view text) {
    if(text.empty() || text.size()>9)return {};
    std::int64_t value=0;
    for(const auto c:text) {if(c<'0' || c>'9')return {};value=value*10+c-'0';}
    return value;
}
int base64Digit(char c) {
    if(c>='A' && c<='Z')return c-'A';
    if(c>='a' && c<='z')return c-'a'+26;
    if(c>='0' && c<='9')return c-'0'+52;
    return c=='+'?62:c=='/'?63:-1;
}
std::string decode64(std::string_view input) {
    if(input.empty() || input.size()>((MaxLanguageBytes+2)/3)*4 || input.size()%4)
        throw std::runtime_error("KRC language size");
    std::string output;output.reserve(input.size()/4*3);
    for(std::size_t i=0;i<input.size();i+=4) {
        const auto a=base64Digit(input[i]),b=base64Digit(input[i+1]);
        const bool padC=input[i+2]=='=',padD=input[i+3]=='=';
        const auto c=padC?0:base64Digit(input[i+2]),d=padD?0:base64Digit(input[i+3]);
        if(a<0 || b<0 || c<0 || d<0 || (padC && !padD) ||
            ((padC || padD) && i+4!=input.size()) || (padC && (b&15)) || (!padC && padD && (c&3)))
            throw std::runtime_error("KRC language encoding");
        output+=static_cast<char>((a<<2)|(b>>4));
        if(!padC)output+=static_cast<char>((b<<4)|(c>>2));
        if(!padD)output+=static_cast<char>((c<<6)|d);
    }
    if(output.size()>MaxLanguageBytes || !normalizeText(output) || output.find('\0')!=std::string::npos)
        throw std::runtime_error("KRC language text");
    return output;
}
std::string timestamp(std::int64_t ms) {
    char value[32];
    std::snprintf(value,sizeof(value),"[%02lld:%02lld.%03lld]",
        static_cast<long long>(ms/60000),static_cast<long long>(ms/1000%60),static_cast<long long>(ms%1000));
    return value;
}
struct Row {std::int64_t time;std::string text;};
}
KrcLyrics extractKrc(std::string_view text) {
    KrcLyrics result;
    if(text.size()>MaxOnlineLyricsBytes)return result;
    auto normalized=std::string(text);
    if(!normalizeText(normalized) || normalized.find('\0')!=std::string::npos)return result;
    text=normalized;
    std::vector<Row> rows;std::string language,offset;bool seenLanguage=false,duplicateLanguage=false,invalidRows=false;
    for(std::size_t start=0;start<text.size();) {
        auto end=text.find('\n',start);if(end==std::string_view::npos)end=text.size();
        const auto line=trim(text.substr(start,end-start));start=end+1;
        if(line.size()>10 && line.substr(0,10)=="[language:") {
            if(seenLanguage)duplicateLanguage=true;
            seenLanguage=true;
            language=std::string(line.substr(10));
            if(language.empty() || language.back()!=']') {duplicateLanguage=true;continue;}
            language.pop_back();continue;
        }
        if(line.size()>8 && line.substr(0,8)=="[offset:") {offset=std::string(line)+"\n";continue;}
        if(line.empty() || line.front()!='[')continue;
        const auto close=line.find(']');if(close==std::string_view::npos) {invalidRows=true;continue;}
        const auto header=line.substr(1,close-1);
        const auto comma=header.find(',');
        if(header.empty() || header.front()<'0' || header.front()>'9')continue;
        if(comma==std::string_view::npos)continue; // Other metadata does not occupy a lyric row.
        const auto time=number(header.substr(0,comma)),duration=number(header.substr(comma+1));
        if(!time || !duration || *time>=60000000 || rows.size()>=MaxRows) {invalidRows=true;continue;}
        // Use the existing word-marker removal; retain the row even when it is blank.
        const auto parsed=parse(std::string(line));
        if(parsed.lines.size()>1) {invalidRows=true;continue;}
        rows.push_back({*time,parsed.lines.empty()?std::string{}:parsed.lines.front().text});
    }
    if(invalidRows || rows.empty())return result;
    result.original=offset;
    for(const auto& row:rows)result.original+=timestamp(row.time)+row.text+"\n";
    if(result.original.size()>MaxOnlineLyricsBytes || parse(result.original).kind!=Kind::Synced) {
        result.original.clear();return result;
    }
    try {
        if(duplicateLanguage)throw std::runtime_error("KRC duplicate language");
        if(!seenLanguage) {result.state=Translation::None;result.reason="not_provided";return result;}
        using Json=nlohmann::json;
        const auto metadata=Json::parse(decode64(language),[](int depth,Json::parse_event_t,Json&) {
            if(depth>16)throw std::runtime_error("KRC language depth");return true;
        });
        if(metadata.contains("version") && metadata.at("version").is_number_integer() && metadata.at("version")!=1) {
            result.reason="unsupported_format";return result;
        }
        if(!metadata.at("version").is_number_integer() || metadata.at("version")!=1 ||
            !metadata.at("content").is_array() || metadata.at("content").size()>16)
            throw std::runtime_error("KRC language schema");
        const Json* selected=nullptr;
        for(const auto& block:metadata.at("content")) {
            if(!block.at("type").is_number_integer() || !block.at("language").is_number_integer())
                throw std::runtime_error("KRC language type");
            const auto& type=block.at("type");const auto& target=block.at("language");
            if(type==0)continue; // Word-level romanization is not a translation.
            if(type!=1 || target!=0) {result.reason="unsupported_format";return result;}
            if(selected)throw std::runtime_error("KRC ambiguous translation");
            selected=&block;
        }
        if(!selected) {result.state=Translation::None;result.reason="not_provided";return result;}
        const auto& translated=selected->at("lyricContent");
        if(!translated.is_array() || translated.size()!=rows.size())throw std::runtime_error("KRC row count");
        result.translation=offset;bool available=false;
        for(std::size_t i=0;i<rows.size();++i) {
            const auto& fields=translated.at(i);
            if(!fields.is_array() || fields.size()!=1 || !fields.at(0).is_string())throw std::runtime_error("KRC row schema");
            const auto value=fields.at(0).get<std::string>();
            if(value.size()>MaxRowBytes || value.find_first_of("\r\n\0",0,3)!=std::string::npos)
                throw std::runtime_error("KRC translation row");
            const auto translatedText=std::string(trim(value));
            if(!translatedText.empty()) {
                const auto literal=parse("[00:00.000]"+translatedText);
                if(literal.lines.size()!=1 || literal.lines[0].timeMs!=0 || literal.lines[0].text!=translatedText)
                    throw std::runtime_error("KRC translation is not a literal row");
            }
            // Empty slots remain placeholders so duplicate timestamps cannot shift a translation.
            const auto& original=rows[i].text;
            const bool usable=!original.empty() && !translatedText.empty() && translatedText!="//" && translatedText!=original;
            result.translation+=timestamp(rows[i].time)+(original.empty()?"":usable?translatedText:"//")+"\n";
            available|=usable;
        }
        if(result.translation.size()>MaxOnlineLyricsBytes)throw std::runtime_error("KRC translation size");
        if(!available) {result.translation.clear();result.state=Translation::None;result.reason="not_provided";return result;}
        if(judgeTranslation(result.original,result.translation)!=Translation::Available)throw std::runtime_error("KRC translation alignment");
        result.state=Translation::Available;
        result.reason.clear();
    }catch(...) {result.translation.clear();result.state=Translation::Unknown;}
    return result;
}
}
