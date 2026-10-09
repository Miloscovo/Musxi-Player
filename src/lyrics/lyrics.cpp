#include "lyrics.hpp"
#include "../cxx17_guard.hpp"
#include <algorithm>
#include <cctype>
#include <optional>

namespace musxi::lyrics {
namespace {
constexpr std::size_t MaxLines=5000;
void appendUtf8(std::string& out,std::uint32_t code) {
    if(code<0x80)out+=static_cast<char>(code);
    else if(code<0x800) {out+=static_cast<char>(0xC0|(code>>6));out+=static_cast<char>(0x80|(code&0x3F));}
    else if(code<0x10000) {out+=static_cast<char>(0xE0|(code>>12));out+=static_cast<char>(0x80|((code>>6)&0x3F));out+=static_cast<char>(0x80|(code&0x3F));}
    else {out+=static_cast<char>(0xF0|(code>>18));out+=static_cast<char>(0x80|((code>>12)&0x3F));
        out+=static_cast<char>(0x80|((code>>6)&0x3F));out+=static_cast<char>(0x80|(code&0x3F));}
}
std::string fromUtf16(std::string_view bytes,bool little) {
    std::string out;
    const auto unit=[&](std::size_t i) {
        const auto a=static_cast<unsigned char>(bytes[i]),b=static_cast<unsigned char>(bytes[i+1]);
        return static_cast<std::uint32_t>(little?(a|(b<<8)):((a<<8)|b));
    };
    for(std::size_t i=0;i+1<bytes.size();i+=2) {
        auto code=unit(i);
        if(code>=0xD800 && code<0xDC00 && i+3<bytes.size()) {
            const auto low=unit(i+2);
            if(low>=0xDC00 && low<0xE000) {code=0x10000+((code-0xD800)<<10)+(low-0xDC00);i+=2;}
            else code=0xFFFD;
        } else if(code>=0xD800 && code<0xE000)code=0xFFFD;
        appendUtf8(out,code);
    }
    return out;
}
bool validUtf8(std::string_view s) {
    for(std::size_t i=0;i<s.size();) {
        const auto c=static_cast<unsigned char>(s[i]);
        std::size_t extra=c<0x80?0:(c>>5)==0x6?1:(c>>4)==0xE?2:(c>>3)==0x1E?3:4;
        if(extra==4 || (extra && (c<0xC2 || c>0xF4 || i+extra>=s.size())))return false;
        std::uint32_t code=extra==0?c:extra==1?(c&0x1F):extra==2?(c&0x0F):(c&0x07);
        for(std::size_t k=1;k<=extra;++k) {
            const auto next=static_cast<unsigned char>(s[i+k]);
            if((next&0xC0)!=0x80)return false;
            code=(code<<6)|(next&0x3F);
        }
        if((extra==2 && (code<0x800 || (code>=0xD800 && code<0xE000))) || (extra==3 && (code<0x10000 || code>0x10FFFF)))return false;
        i+=extra+1;
    }
    return true;
}
std::string_view trim(std::string_view s) {
    while(!s.empty() && std::isspace(static_cast<unsigned char>(s.front())))s.remove_prefix(1);
    while(!s.empty() && std::isspace(static_cast<unsigned char>(s.back())))s.remove_suffix(1);
    return s;
}
std::optional<std::int64_t> number(std::string_view s,std::size_t maxDigits) {
    if(s.empty() || s.size()>maxDigits)return std::nullopt;
    std::int64_t value=0;
    for(const char c:s) {if(c<'0' || c>'9')return std::nullopt;value=value*10+(c-'0');}
    return value;
}
// [mm:ss], [mm:ss.x], [mm:ss.xx], [mm:ss.xxx] and the rarer [mm:ss:xx].
std::optional<std::int64_t> timestamp(std::string_view tag) {
    const auto colon=tag.find(':');
    if(colon==std::string_view::npos)return std::nullopt;
    const auto minutes=number(tag.substr(0,colon),3);
    auto rest=tag.substr(colon+1);
    const auto dot=rest.find_first_of(".:");
    const auto seconds=number(rest.substr(0,dot),2);
    if(!minutes || !seconds || *seconds>=60)return std::nullopt;
    std::int64_t fraction=0;
    if(dot!=std::string_view::npos) {
        const auto digits=rest.substr(dot+1);
        const auto value=number(digits,3);
        if(!value)return std::nullopt;
        fraction=digits.size()==1?*value*100:digits.size()==2?*value*10:*value;
    }
    return (*minutes*60+*seconds)*1000+fraction;
}
// KRC line header: [startMs,durationMs].
std::optional<std::int64_t> krcLine(std::string_view tag) {
    const auto comma=tag.find(',');
    if(comma==std::string_view::npos)return std::nullopt;
    const auto start=number(tag.substr(0,comma),9),duration=number(tag.substr(comma+1),9);
    if(!start || !duration)return std::nullopt;
    return start;
}
// Removes enhanced LRC <mm:ss.xx> and KRC <offset,duration,0> word markers.
std::string stripWordTags(std::string_view text) {
    std::string out;
    for(std::size_t i=0;i<text.size();) {
        if(text[i]=='<') {
            const auto close=text.find('>',i);
            if(close!=std::string_view::npos) {
                const auto inner=text.substr(i+1,close-i-1);
                if(!inner.empty() && inner.find_first_not_of("0123456789:.,")==std::string_view::npos) {i=close+1;continue;}
            }
        }
        out+=text[i++];
    }
    return std::string(trim(out));
}
}
const char* kindName(Kind kind) {return kind==Kind::Synced?"synced":kind==Kind::Plain?"plain":"none";}
const char* originName(Origin origin) {return origin==Origin::File?"lrc-file":origin==Origin::Embedded?"embedded":"";}
bool normalizeText(std::string& bytes) {
    if(bytes.size()>=3 && bytes.compare(0,3,"\xEF\xBB\xBF")==0) {bytes.erase(0,3);return validUtf8(bytes);}
    if(bytes.size()>=2 && (bytes.compare(0,2,"\xFF\xFE")==0 || bytes.compare(0,2,"\xFE\xFF")==0)) {
        bytes=fromUtf16(std::string_view(bytes).substr(2),bytes[0]=='\xFF');return true;
    }
    return validUtf8(bytes);
}
Lyrics parse(std::string_view text) {
    std::vector<Line> timed;std::vector<std::string> plain;std::int64_t offset=0;
    for(std::size_t start=0;start<text.size() && timed.size()<MaxLines;) {
        auto end=text.find('\n',start);if(end==std::string_view::npos)end=text.size();
        auto line=trim(text.substr(start,end-start));start=end+1;
        std::vector<std::int64_t> times;bool tagged=false;
        while(!line.empty() && line.front()=='[') {
            const auto close=line.find(']');
            if(close==std::string_view::npos)break;
            const auto tag=trim(line.substr(1,close-1));
            if(auto time=timestamp(tag))times.push_back(*time);
            else if(auto krc=krcLine(tag))times.push_back(*krc);
            else if(tag.size()>7 && tag.substr(0,7)=="offset:") {
                auto value=trim(tag.substr(7));const bool negative=!value.empty() && value.front()=='-';
                if(!value.empty() && (value.front()=='-' || value.front()=='+'))value.remove_prefix(1);
                if(auto parsed=number(value,7))offset=negative?-*parsed:*parsed;
            } else if(tag.find(':')==std::string_view::npos)break; // Literal bracketed lyric text.
            tagged=true;line=trim(line.substr(close+1));
        }
        auto content=stripWordTags(line);
        if(!times.empty())for(const auto time:times)timed.push_back({time,content});
        else if(!tagged && !content.empty() && plain.size()<MaxLines)plain.push_back(std::move(content));
    }
    Lyrics result;
    if(std::any_of(timed.begin(),timed.end(),[](const Line& line){return !line.text.empty();})) {
        // A positive LRC offset shows lyrics earlier.
        for(auto& line:timed)line.timeMs=std::max<std::int64_t>(0,line.timeMs-offset);
        std::stable_sort(timed.begin(),timed.end(),[](const Line& a,const Line& b){return a.timeMs<b.timeMs;});
        result.kind=Kind::Synced;result.lines=std::move(timed);
    } else if(!plain.empty()) {
        result.kind=Kind::Plain;
        for(auto& row:plain)result.lines.push_back({0,std::move(row)});
    }
    return result;
}
Selected chooseLocal(const std::string* sidecar,const std::string* embedded) {
    if(sidecar) {auto parsed=parse(*sidecar);if(parsed.kind!=Kind::None)return {Origin::File,std::move(parsed)};}
    if(embedded) {auto parsed=parse(*embedded);if(parsed.kind!=Kind::None)return {Origin::Embedded,std::move(parsed)};}
    return {};
}
}
