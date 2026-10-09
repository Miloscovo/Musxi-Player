#include "../src/lyrics/lyrics.hpp"
#include <iostream>
#include <stdexcept>
#include <string>
void require(bool ok,const char* message) {if(!ok) throw std::runtime_error(message);}
using namespace musxi::lyrics;
// Core-only parser contract: portable C++17, no Windows, FFmpeg or network.
int main() {
    try {
    auto lrc=parse("[ti:Song]\r\n[ar:Artist]\r\n[00:01.50]First\r\n[00:03.123][00:10.2]Repeat\r\n[00:05:40]Colon fraction\r\n");
    require(lrc.kind==Kind::Synced && lrc.lines.size()==4,"synced line count");
    require(lrc.lines[0].timeMs==1500 && lrc.lines[0].text=="First","two-digit fraction");
    require(lrc.lines[1].timeMs==3123 && lrc.lines[1].text=="Repeat","three-digit fraction");
    require(lrc.lines[2].timeMs==5400 && lrc.lines[2].text=="Colon fraction","colon fraction");
    require(lrc.lines[3].timeMs==10200 && lrc.lines[3].text=="Repeat","multiple timestamps or sorting");
    auto offset=parse("[offset:+500]\n[00:01.00]Early\n[00:00.20]Clamped\n");
    require(offset.lines[0].timeMs==0 && offset.lines[1].timeMs==500,"positive offset");
    require(parse("[offset:-250]\n[00:01.00]Late").lines[0].timeMs==1250,"negative offset");
    auto spacer=parse("[00:01.00]Intro\n[00:02.00]\n[00:03.00]Verse");
    require(spacer.lines.size()==3 && spacer.lines[1].text.empty(),"blank spacer line dropped");
    require(parse("[00:01.00]\n[00:02.00]").kind==Kind::None,"timestamps without text treated as lyrics");
    auto enhanced=parse("[00:01.00]<00:01.00>Word <00:01.50>by <00:02.00>word");
    require(enhanced.lines[0].text=="Word by word","enhanced LRC word tags kept");
    auto krc=parse("[id:$00000000]\n[language:eyJjb250ZW50IjpbXX0=]\n[1200,2000]<0,400,0>Hel<400,600,0>lo\n[3500,900]<0,900,0>World");
    require(krc.kind==Kind::Synced && krc.lines.size()==2,"KRC line count");
    require(krc.lines[0].timeMs==1200 && krc.lines[0].text=="Hello" && krc.lines[1].timeMs==3500,"KRC line timing");
    auto plain=parse("Line one\n\n[Chorus]\nLine two");
    require(plain.kind==Kind::Plain && plain.lines.size()==3 && plain.lines[1].text=="[Chorus]","plain text or literal brackets");
    require(parse("[00:99.00]Bad\n[ab:cd]x").kind!=Kind::Synced,"invalid timestamp accepted");
    require(parse("").kind==Kind::None && parse("[ti:Only]\n[ar:Tags]").kind==Kind::None,"metadata-only lyrics");
    std::string utf8="\xEF\xBB\xBF[00:01.00]\xE4\xBD\xA0\xE5\xA5\xBD";
    require(normalizeText(utf8) && utf8=="[00:01.00]\xE4\xBD\xA0\xE5\xA5\xBD","UTF-8 BOM");
    std::string utf16("\xFF\xFE[\0" "0\0" "]\0" "`O}Y",12);
    require(normalizeText(utf16) && utf16=="[0]\xE4\xBD\xA0\xE5\xA5\xBD","UTF-16LE BOM");
    std::string utf16be("\xFE\xFF\xD8\x3D\xDE\x00",6);
    require(normalizeText(utf16be) && utf16be=="\xF0\x9F\x98\x80","UTF-16BE surrogate pair");
    std::string gbk="[00:01.00]\xC4\xE3\xBA\xC3";
    require(!normalizeText(gbk),"GBK bytes reported as UTF-8");
    std::string overlong="\xC0\xAF",truncated="\xE4\xBD";
    require(!normalizeText(overlong) && !normalizeText(truncated),"invalid UTF-8 accepted");
    const std::string sidecar="[00:01.00]From file",embedded="[00:01.00]From tag",empty="[ti:none]";
    require(chooseLocal(&sidecar,&embedded).origin==Origin::File,"sidecar priority");
    auto fallback=chooseLocal(&empty,&embedded);
    require(fallback.origin==Origin::Embedded && fallback.lyrics.lines[0].text=="From tag","unusable sidecar should fall back");
    require(chooseLocal(nullptr,nullptr).origin==Origin::None && chooseLocal(nullptr,nullptr).lyrics.kind==Kind::None,"missing lyrics");
    std::string many;for(int i=0;i<6000;++i)many+="[00:01.00]x\n";
    require(parse(many).lines.size()==5000,"line limit");
    std::cout<<"PASS lyrics parser and local priority\n";
    } catch(const std::exception& error) {std::cerr<<"FAIL "<<error.what()<<'\n';return 1;}
}
