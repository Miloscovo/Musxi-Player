#include "../src/lyrics/match.hpp"
#include <fstream>
#include <chrono>
#include <iostream>
using namespace musxi::lyrics;
namespace fs=std::filesystem;
void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
int main() {
    const auto root=fs::temp_directory_path()/("musxi-match-store-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        fs::create_directories(root);const auto audio=root/"song.mp3",directory=root/"bindings";
        {std::ofstream out(audio);out<<"audio";}
        const auto identity=fileIdentity(audio);
        const MatchJson selection={{"platform","qq"},{"track",{{"id","qq:abc"},{"name","Song"}}},{"text","[offset:500]\n[00:01]One\n[00:02]Two"},{"offsetMs",1000}};
        saveMatch(directory,identity,selection);
        require(readMatch(directory,identity)==selection,"persistent selection lost");
        auto lines=matchLyrics(selection);require(lines.lines[0].timeMs==1500 && lines.lines[1].timeMs==2500,"LRC and user offset wrong");
        auto early=selection;early["offsetMs"]=-1000;lines=matchLyrics(early);
        require(lines.lines[0].timeMs==0 && lines.lines[1].timeMs==500,"negative offset not clamped");
        auto reset=selection;reset["offsetMs"]=0;require(matchLyrics(reset).lines[0].timeMs==500,"offset accumulated");
        auto plain=selection;plain["text"]="Plain lyrics";require(matchLyrics(plain).lines[0].timeMs==0,"plain text shifted");
        auto invalid=selection;invalid["offsetMs"]=600001;bool rejected=false;
        try{saveMatch(directory,identity,invalid);}catch(...){rejected=true;}require(rejected,"invalid offset saved");
        const auto moved=root/"renamed.mp3";fs::rename(audio,moved);
        require(!readMatch(directory,fileIdentity(moved)),"moved file silently matched");fs::rename(moved,audio);
        {std::ofstream out(audio);out<<"different audio";}
        require(!readMatch(directory,fileIdentity(audio)),"replacement inherited lyrics");
        rejected=false;try{saveMatch(directory,identity,selection);}catch(...){rejected=true;}require(rejected,"stale file identity saved");
        removeMatch(directory,fileIdentity(audio));require(!readMatch(directory,identity),"restore auto did not delete binding");
        const auto current=fileIdentity(audio);saveMatch(directory,current,selection);
        const auto recordPath=fs::directory_iterator(directory)->path();
        {std::ofstream out(recordPath);out<<"{truncated";}
        require(!readMatch(directory,current),"corrupt binding was read");
        removeMatch(directory,current);require(!fs::exists(recordPath),"corrupt binding could not be reset");
        saveMatch(directory,current,selection);
        auto other=current;other["path"]="another-song.mp3";
        {std::ofstream out(recordPath);out<<MatchJson{{"identity",other}}.dump();}
        rejected=false;try{removeMatch(directory,current);}catch(...){rejected=true;}
        require(rejected && fs::exists(recordPath),"reset removed another song's record");
        fs::remove_all(root);std::cout<<"Match storage passed\n";return 0;
    }catch(const std::exception& e){fs::remove_all(root);std::cerr<<e.what()<<'\n';return 1;}
}
