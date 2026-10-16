#include "../src/lyrics/krc.hpp"
#include "../src/lyrics/lyrics.hpp"
#include "../third_party/json.hpp"
#include <fstream>
#include <iostream>
#include <stdexcept>
using namespace musxi::lyrics;
using Json=nlohmann::json;
void require(bool value,const char* message) {if(!value)throw std::runtime_error(message);}
std::string base64(const std::string& text) {
    constexpr char alphabet[]="ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
    std::string out;
    for(std::size_t i=0;i<text.size();i+=3) {
        const auto a=static_cast<unsigned char>(text[i]);
        const auto b=i+1<text.size()?static_cast<unsigned char>(text[i+1]):0;
        const auto c=i+2<text.size()?static_cast<unsigned char>(text[i+2]):0;
        out+=alphabet[a>>2];out+=alphabet[((a&3)<<4)|(b>>4)];
        out+=i+1<text.size()?alphabet[((b&15)<<2)|(c>>6)]:'=';out+=i+2<text.size()?alphabet[c&63]:'=';
    }
    return out;
}
Json block(int type,Json rows,int language=0) {return {{"type",type},{"language",language},{"lyricContent",rows}};}
std::string metadata(Json blocks) {return "[language:"+base64(Json{{"version",1},{"content",blocks}}.dump())+"]\n";}
int main(int argc,char** argv) {
    try {
        if(argc==3) {
            std::ifstream input(argv[1],std::ios::binary),lrcFile(argv[2],std::ios::binary);
            const std::string krc((std::istreambuf_iterator<char>(input)),{}),lrc((std::istreambuf_iterator<char>(lrcFile)),{});
            auto sample=extractKrc(krc);
            require(sample.state==Translation::Available,"live KRC translation unavailable");
            require(sameOriginal(lrc,sample.original),"live KRC/LRC originals differ");
            require(retimeKrc(sample,lrc),"live KRC retiming failed");
            require(judgeTranslation(lrc,sample.translation)==Translation::Available,"live KRC/LRC alignment failed");
            const auto aligned=parse(lrc,sample.translation);
            std::cout<<"Live KRC verified: "<<aligned.matched<<" translations / "<<aligned.lyrics.lines.size()<<" original rows\n";return 0;
        }
        // File order differs from timestamp order, and untranslated/blank rows occupy slots.
        const std::string original="[offset:2000]\n[3000,500]<0,500,0>Third\n[1000,500]<0,500,0>First\n[1000,500]Second\n[1000,500]\n[500,500]Early\n";
        const auto rows=Json::array({Json::array({"第三"}),Json::array({" "}),Json::array({"第二"}),Json::array({""}),Json::array({"早"})});
        const auto raw=metadata(Json::array({block(0,rows),block(1,rows)}))+original;
        auto translated=extractKrc(raw);
        require(translated.state==Translation::Available,"KRC translation not extracted");
        const auto aligned=parse(translated.original,translated.translation,500);
        require(aligned.lyrics.lines[0].text=="Early" && aligned.lyrics.lines[0].translation=="早" && aligned.lyrics.lines[0].timeMs==0,"negative KRC times aligned after clamp");
        require(aligned.lyrics.lines[1].translation.empty() && aligned.lyrics.lines[2].translation=="第二","duplicate placeholder shifted translation");
        require(aligned.lyrics.lines[3].text.empty() && aligned.lyrics.lines[3].translation.empty(),"blank KRC row dropped");
        require(aligned.lyrics.lines[4].translation=="第三" && aligned.lyrics.lines[4].timeMs==1500,"KRC index mapped after sorting or offset lost");
        require(sameOriginal(original,translated.original),"KRC normalized original incompatible");
        require(!sameOriginal("[00:01]Wrong", "[00:01]Right"),"same timeline accepted different original text");
        require(!sameOriginal("[offset:2000]\n[00:01]Same", "[00:00]Same"),"compatibility ran after negative clamp");
        require(sameOriginal("[00:01.23]Same", "[1239,100]Same"),"centisecond LRC rounding rejected");
        require(!sameOriginal("[00:01]Same", "[00:01.101]Same"),"far timing accepted");
        auto closeRows=extractKrc(metadata(Json::array({block(1,Json::array({Json::array({"甲"}),Json::array({"乙"})}))}))+"[1009,10]A\n[1019,10]B");
        const std::string closeOriginal="[00:01.000]A\n[00:01.010]B";
        require(retimeKrc(closeRows,closeOriginal),"close KRC rows not retimed");
        const auto closeAligned=parse(closeOriginal,closeRows.translation);
        require(closeAligned.lyrics.lines[0].translation=="甲" && closeAligned.lyrics.lines[1].translation=="乙","rounded close rows exchanged translations");
        require(extractKrc("[ar:First, Second]\n[1000,1000]Hello").state==Translation::None,"comma in metadata treated as a row");
        const std::string simple="[1000,1000]Hello\n[2000,1000]World";
        const auto goodRows=Json::array({Json::array({"你好"}),Json::array({"世界"})});
        const auto good=metadata(Json::array({block(1,goodRows)}));
        for(const auto& rawNone:{simple,metadata(Json::array())+simple,metadata(Json::array({block(0,goodRows)}))+simple,
            metadata(Json::array({block(1,Json::array({Json::array({""}),Json::array({" "})}))}))+simple,
            metadata(Json::array({block(1,Json::array({Json::array({"Hello"}),Json::array({"World"})}))}))+simple})
            require(extractKrc(rawNone).state==Translation::None,"confirmed absence left unknown or romanization displayed");
        for(const auto& bad:{std::string("[language:]\n"),std::string("[language:???]\n"),std::string("[language:e30=]\n"),good+good,
            metadata(Json::array({block(1,Json::array({Json::array({"一"})}))})),
            metadata(Json::array({block(1,Json::array({Json::array({"一","二"}),Json::array({"二"})}))})),
            metadata(Json::array({block(1,Json::array({Json::array({"一\n[00:02]二"}),Json::array({"二"})}))})),
            metadata(Json::array({block(1,Json::array({Json::array({"[offset:1000]一"}),Json::array({"二"})}))})),
            metadata(Json::array({block(1,goodRows,2)})),metadata(Json::array({block(9,goodRows)})),
            metadata(Json::array({block(1,goodRows),block(1,goodRows)})),
            std::string("[language:")+base64(std::string(256*1024+1,'x'))+"]\n"}) {
            const auto damaged=extractKrc(bad+simple);
            require(damaged.state==Translation::Unknown && !damaged.original.empty() && damaged.translation.empty(),"damaged metadata discarded original or cached absence");
        }
        Json deep="x";for(int i=0;i<20;i++)deep=Json::array({deep});
        require(extractKrc(metadata(Json::array({block(1,deep)}))+simple).state==Translation::Unknown,"metadata depth unbounded");
        require(extractKrc("[language:"+base64(Json{{"version",2},{"content",Json::array()}}.dump())+"]\n"+simple).state==Translation::Unknown,"future version treated as absence");
        require(extractKrc("[language:"+base64(Json{{"version",2},{"content",Json::array()}}.dump())+"]\n"+simple).reason=="unsupported_format","future KRC schema not classified");
        require(extractKrc("[language:bad]\n"+simple).reason=="invalid_data","damaged KRC not classified");
        std::string many;for(int i=0;i<5001;i++)many+="[1000,1000]Hello\n";
        require(extractKrc(many).original.empty(),"KRC row limit not enforced");
        require(extractKrc("[1000,bad]Hello").original.empty(),"invalid KRC timing accepted");
        require(extractKrc(std::string(1024*1024+1,'x')).original.empty(),"KRC text limit not enforced");
        std::cout<<"KRC translation extraction and compatibility passed\n";return 0;
    }catch(const std::exception& error){std::cerr<<error.what()<<'\n';return 1;}
}
