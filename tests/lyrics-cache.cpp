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
        for(int i=0;i<260;++i)require(writeCache(directory,"qq:"+std::to_string(i),{"",true},now),"bounded cache write");
        std::size_t count=0;for(const auto& file:fs::directory_iterator(directory))if(file.path().extension()==".json")++count;
        require(count<=256,"cache entry cap");
        fs::remove_all(directory);std::cout<<"Lyrics cache contracts passed\n";return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';fs::remove_all(directory);return 1;}
}
