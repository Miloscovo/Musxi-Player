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
    // Translation alignment, shaped after real NetEase tlyric / QQ trans payloads.
    auto pair=parse("[00:00.00] 作词 : A\n[00:01.430]First\n[00:03.00]\n[00:05.20]Second\n[00:07.00]Third",
        "[by:someone]\n[00:01.43]第一\n[00:03.00]\n[00:05.200]第二\n[00:07.00]//\n[00:09.00]");
    require(pair.lyrics.kind==Kind::Synced && pair.lyrics.lines.size()==5,"translation changed original lines");
    require(pair.lyrics.lines[1].translation=="第一" && pair.lyrics.lines[3].translation=="第二","2/3-digit fraction alignment");
    require(pair.lyrics.lines[0].translation.empty() && pair.lyrics.lines[2].translation.empty() && pair.lyrics.lines[4].translation.empty(),
        "credit, spacer or // placeholder shown as translation");
    require(pair.candidates==2 && pair.matched==2,"translation counts");
    auto copied=parse("[00:00.10]Piano: X\n[00:02.00]Hello","[00:00.10]Piano: X\n[00:02.00]你好");
    require(copied.lyrics.lines[0].translation.empty() && copied.candidates==1 && copied.matched==1,"copied credit counted as translation");
    auto repeated=parse("[00:02.00][00:10.00]Chorus\n[00:02.00]Echo","[00:02.00]副歌\n[00:02.00]回声\n[00:10.00]副歌");
    require(repeated.lyrics.lines[0].translation=="副歌" && repeated.lyrics.lines[1].translation=="回声" &&
        repeated.lyrics.lines[2].translation=="副歌" && repeated.matched==3,"duplicate timestamps pair in file order");
    auto duplicateCredit=parse("[00:01]Credit\n[00:01]Hello","[00:01]Credit\n[00:01]你好");
    require(duplicateCredit.lyrics.lines[0].translation.empty() && duplicateCredit.lyrics.lines[1].translation=="你好","copied credit did not consume duplicate timestamp slot");
    auto duplicatePlaceholder=parse("[00:01]Untranslated\n[00:01]Hello","[00:01]//\n[00:01]你好");
    require(duplicatePlaceholder.lyrics.lines[0].translation.empty() && duplicatePlaceholder.lyrics.lines[1].translation=="你好","placeholder did not consume duplicate timestamp slot");
    auto far=parse("[00:01.00]One\n[00:04.00]Two","[00:01.20]一\n[00:04.00]二");
    require(far.lyrics.lines[0].translation.empty() && far.candidates==2 && far.matched==1,"translation outside window attached");
    auto offsets=parse("[offset:2000]\n[00:00.50]A\n[00:01.00]B\n[00:03.00]C","[offset:2000]\n[00:00.50]甲\n[00:01.00]乙\n[00:03.00]丙");
    require(offsets.lyrics.lines[0].timeMs==0 && offsets.lyrics.lines[1].timeMs==0 && offsets.lyrics.lines[2].timeMs==1000,"offset or clamp");
    require(offsets.lyrics.lines[0].translation=="甲" && offsets.lyrics.lines[1].translation=="乙","alignment ran after clamping");
    auto separate=parse("[00:02.00]A","[offset:-1000]\n[00:01.00]甲");
    require(separate.lyrics.lines[0].translation=="甲","translation [offset:] not applied on its own text");
    auto shifted=parse("[00:01.00]A\n[00:02.00]B","[00:01.00]甲\n[00:02.00]乙",-1500);
    require(shifted.lyrics.lines[0].timeMs==0 && shifted.lyrics.lines[1].timeMs==500 && shifted.lyrics.lines[0].translation=="甲" &&
        shifted.lyrics.lines[1].translation=="乙","user shift before alignment");
    auto plainTranslation=parse("[00:01.00]A\n[00:02.00]B","甲\n乙");
    require(plainTranslation.lyrics.lines[0].translation.empty() && plainTranslation.matched==0,"plain translation glued by line index");
    auto plainOriginal=parse("A\nB","[00:01.00]甲");
    require(plainOriginal.lyrics.kind==Kind::Plain && plainOriginal.lyrics.lines[0].translation.empty(),"plain original translated");
    require(parse("[00:01.00]A","").lyrics.lines[0].translation.empty(),"empty translation");
    std::cout<<"PASS lyrics parser and local priority\n";
    } catch(const std::exception& error) {std::cerr<<"FAIL "<<error.what()<<'\n';return 1;}
}
