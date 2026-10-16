#include "../src/lyrics/cache.hpp"
#include "../third_party/json.hpp"
#include <chrono>
#include <fstream>
#include <iostream>
#include <stdexcept>
using namespace musxi::lyrics;
namespace fs=std::filesystem;
void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
int main() {
    const auto directory=fs::temp_directory_path()/("musxi-lyrics-cache-"+std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    try {
        const std::string hash(32,'a');
        require(onlineKey("list1:track",hash)==onlineKey("list2:track",hash),"playlist IDs split recording cache");
        require(onlineKey("netease:123","")!=onlineKey("qq:123",""),"platform collision");
        require(onlineKey("local:123",hash).empty() && onlineKey("netease:../x","").empty(),"invalid identity accepted");
        const auto key=onlineKey("netease:123","");const std::int64_t now=1800000000;
        require(!readCache(directory,key,now),"missing cache hit");
        require(writeCache(directory,key,{"[00:01.00]Hello",false},now),"cache write");
        auto value=readCache(directory,key,now+1);
        require(value && value->fresh && !value->none && value->text=="[00:01.00]Hello","cache restart read");
        value=readCache(directory,key,now+31*86400);
        require(value && !value->fresh && !value->none,"stale lyrics unavailable for offline fallback");
        require(!readCache(directory,key,now-1),"future cache accepted");
        require(writeCache(directory,key,{"",true},now),"atomic replace with explicit none");
        value=readCache(directory,key,now+86401);require(value && value->none && !value->fresh,"negative TTL");
        require(!writeCache(directory,key,{std::string(MaxOnlineLyricsBytes+1,'x'),false},now),"oversize accepted");
        require(!writeCache(directory,key,{"",false},now),"empty success cached as none");
        for(const auto& file:fs::directory_iterator(directory))if(file.path().extension()==".json") {
            nlohmann::json json;{std::ifstream input(file.path());input>>json;}
            json["key"]="netease:other";{std::ofstream out(file.path());out<<json;}
        }
        require(!readCache(directory,key,now),"mismatched key accepted");
        for(const auto& file:fs::directory_iterator(directory)){std::ofstream out(file.path());out<<"broken";}
        require(!readCache(directory,key,now),"corrupt cache accepted");
        // v0.4 wrote version 1 without translation fields: still readable, translation unknown.
        {std::ofstream out(fs::directory_iterator(directory)->path());
         out<<nlohmann::json{{"version",1},{"key",key},{"format","lrc"},{"savedAt",now},{"none",false},{"text","[00:01.00]Hello"}}.dump();}
        value=readCache(directory,key,now+1);
        require(value && value->fresh && value->translationState==Translation::Unknown && !value->translationFresh,"v0.4 cache lost on upgrade");
        CacheEntry translated;translated.text="[00:01.00]Hello\n[00:02.00]World";translated.translationState=Translation::Available;
        translated.translation="[00:01.00]你好\n[00:02.00]世界";
        require(writeCache(directory,key,translated,now),"translated cache write");
        value=readCache(directory,key,now+31*86400);
        require(value && value->translation==translated.translation && value->translationFresh,"translation not persisted");
        const auto stored=fs::directory_iterator(directory)->path();
        nlohmann::json damaged;{std::ifstream in(stored);in>>damaged;}
        damaged["translation"]=23;{std::ofstream out(stored);out<<damaged;}
        value=readCache(directory,key,now+1);
        require(value && value->text==translated.text && value->translationState==Translation::Unknown,"damaged translation discarded cached original");
        CacheEntry none;none.text=translated.text;none.translationState=Translation::None;
        require(writeCache(directory,key,none,now),"none translation write");
        require(readCache(directory,key,now+86400)->translationFresh && !readCache(directory,key,now+8*86400)->translationFresh,"translation recheck period");
        CacheEntry mismatch=translated;mismatch.translation="[00:09.00]别的版本\n[00:12.00]不对齐";
        require(!writeCache(directory,key,mismatch,now),"misaligned translation cached as available");
        CacheEntry stray=none;stray.translation="[00:01.00]你好";
        require(!writeCache(directory,key,stray,now),"translation text without available state");
        require(judgeTranslation(translated.text,"[00:01.00]你好\n[00:02.00]世界\n[00:30.00]多余")==Translation::None,"two of three rows accepted");
        require(judgeTranslation(translated.text,"[00:01.00]你好\n[00:02.00]世界")==Translation::Available,"aligned translation rejected");
        require(writeCache(directory,key,none,now),"fresh none for independent clocks");
        const auto beforeRefresh=*readCache(directory,key,now+6*86400);
        auto refreshed=beforeRefresh;refreshed.translationState=Translation::Available;refreshed.translation=translated.translation;
        require(updateCacheTranslation(directory,key,beforeRefresh,refreshed,now+6*86400),"translation compare-update");
        const auto afterRefresh=readCache(directory,key,now+31*86400);
        require(afterRefresh && !afterRefresh->fresh && afterRefresh->savedAt==now &&
            afterRefresh->translationCheckedAt==now+6*86400 && afterRefresh->translation==translated.translation,
            "translation refresh renewed original lifetime or lost independent time");
        require(!updateCacheTranslation(directory,key,beforeRefresh,refreshed,now+6*86400),"stale translation writer replaced newer cache");
        auto changed=*afterRefresh;changed.text="[00:01.00]Changed\n[00:02.00]Words";
        require(!updateCacheTranslation(directory,key,*afterRefresh,changed,now+31*86400),"translation updater replaced original");
        {nlohmann::json record;{std::ifstream in(stored);in>>record;}record["translationCheckedAt"]="broken";
         std::ofstream out(stored);out<<record;}
        require(readCache(directory,key,now+1)->text==none.text,"bad optional clock discarded original");
        for(int i=0;i<260;++i)require(writeCache(directory,"qq:"+std::to_string(i),{"",true},now),"bounded cache write");
        std::size_t count=0;for(const auto& file:fs::directory_iterator(directory))if(file.path().extension()==".json")++count;
        require(count<=256,"cache entry cap");
        fs::remove_all(directory);std::cout<<"Lyrics cache contracts passed\n";return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';fs::remove_all(directory);return 1;}
}
