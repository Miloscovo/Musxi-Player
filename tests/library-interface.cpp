#include "../src/application/application.cpp"
#include <iostream>
#include <stdexcept>
void require(bool value,const char* message) {if(!value) throw std::runtime_error(message);}
int main() {
    const auto productionData=cloud::dataDir();
    cloud::testProfile=true;
    require(cloud::dataDir()!=productionData && cloud::dataDir().filename()==L"MusxiPlayer-Test","test profile shares production storage");
    cloud::testProfile=false;
    (void)playerService();
    auto invoke=[](const char* name,const Json& p=Json::object()) {return musxi::applicationLibrary(name,p.dump());};
    cloudTracks=Json::array();
    for(int i=0;i<63;++i) cloudTracks.push_back({{"id",std::to_string(i)},{"name","song"},{"token","PRIVATE"},{"path","PRIVATE"}});
    cloudTracks[50]["album"]="Sample Album";
    cloudPlaylists=Json::array({{{"id","p"},{"name","playlist"},{"cookie","PRIVATE"}}});
    auto snapshot=invoke("library.getState",{{"page",2}});
    require(snapshot.code==0 && snapshot.json.find("PRIVATE")==std::string::npos,"snapshot leaked internal fields");
    auto state=Json::parse(snapshot.json);
    require(state["tracks"].size()==13 && state["trackCount"]==63,"library pagination");
    require(state["tracks"][0]["album"]=="Sample Album" && state["tracks"][1]["album"]=="","album title missing from public rows");
    require(invoke("library.getState",{{"page",0}}).code==400,"invalid page accepted");
    require(invoke("library.getState",{{"page",1.5}}).code==400,"fractional page accepted");
    require(invoke("library.search",{{"keywords"," "},{"page",1}}).code==400,"blank search accepted");
    require(invoke("library.sync").code==409,"unauthenticated sync accepted");
    require(invoke("library.play",{{"source","library"},{"id","0"}}).code==409,"unauthenticated play accepted");
    require(invoke("library.image",{{"url","https://example.com/private"}}).code==403,"arbitrary image fetch accepted");
    cloudConnected=true;
    require(invoke("library.play",{{"source","library"},{"id","missing"}}).code==404,"unknown track accepted");
    auto first=invoke("library.play",{{"source","library"},{"id","0"}});
    require(first.code==0 && libraryOperationStatus=="pending" && !opened,"play acceptance claimed playback success");
    const auto generation=cloudGeneration;
    auto second=invoke("library.play",{{"source","library"},{"id","1"}});
    require(second.code==0 && first.json!=second.json && cloudGeneration>generation && libraryNextPlay["id"]=="1","latest selection did not supersede");
    require(invoke("library.cancel").code==0 && libraryNextPlay.is_null() && libraryOperationStatus=="cancelled","play cancellation");
    cloudTracks[0]["cover"]="https://example.com/known";
    require(invoke("library.image",{{"url","https://example.com/known"}}).code==0 && webWantedCovers.size()==1,"known cover was not queued");
    auto complete=[&](const std::string& operation,const Json& response) {
        libraryOperationKind=cloudOperation=operation;libraryOperationStatus="pending";
        std::promise<Json> p;cloudFuture=p.get_future();p.set_value(response);cloudTick();
    };
    complete("search",{{"ok",true},{"data",{{"tracks",Json::array()},{"keywords","needle"},{"page",2},{"total",0},{"hasMore",false}}}});
    require(libraryOperationStatus=="completed" && searchQuery=="needle" && searchPage==2,"search completion not applied");
    complete("search",{{"ok",false},{"error","offline"}});
    require(libraryOperationStatus=="failed" && !libraryOperationError.empty(),"search failure not reported");
    complete("tracks",{{"ok",true},{"data",Json::object()}});
    require(libraryOperationStatus=="failed","malformed response reported success");
    libraryMenuRequested=true;
    complete("song_menu",{{"ok",true},{"data",{{"id","0"},{"liked",true},{"canFavorite",true},{"playlists",Json::array()}}}});
    require(libraryMenu.value("liked",false) && !libraryMenuRequested,"web song menu did not receive response");
    libraryPlayGeneration=requestGeneration=cloudGeneration;
    complete("audio",{{"ok",false},{"error","download failed"}});
    require(libraryOperationStatus=="failed" && !opened,"failed download completed playback");
    std::cout<<"PASS library boundary, pagination, operation lifecycle and redaction\n";
}
