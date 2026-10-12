#include "../src/application/application.cpp"
#include <iostream>
void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
int main() {
    const auto root=fs::temp_directory_path()/(L"musxi-match-test-"+std::to_wstring(GetCurrentProcessId()));
    Json identity;
    try {
        cloud::testProfile=true;fs::create_directories(root);const auto audio=root/L"Filename Live.mp3";
        {std::ofstream out(audio);out<<"synthetic audio";}
        {std::ofstream out(root/L"Filename Live.lrc");out<<"[00:01]Original sidecar";}
        identity=musxi::lyrics::fileIdentity(audio);
        localTracks=Json::array({{{"id","local:991"},{"path",audio.u8string()},{"duration",185000}}});
        audioTrackId="local:991";
        const auto finish=[&](Json value) {
            const auto until=GetTickCount64()+5000;
            while(value["status"]=="pending" && GetTickCount64()<until) {
                Sleep(5);value=requestMatch({{"action","poll"},{"id","local:991"},{"session",value.at("session")},{"job",value.at("job")}});
            }
            require(value["status"]=="ready","matching timed out");return value;
        };
        auto state=finish(requestMatch({{"action","begin"},{"id","local:991"}}));const auto session=state.at("session");
        require(state["suggestion"]["title"]=="Filename Live","filename fallback removed version");
        require(state.dump().find(audio.u8string())==std::string::npos,"local path leaked");
#ifdef MUSXI_TEST_LYRICS_IPC
        std::wstring error;require(matchBridge.start(error,L"lyrics-bridge.cjs"),"fixture start");
        state=finish(requestMatch({{"action","search"},{"id","local:991"},{"session",session},{"platform","netease"},{"keywords","Fixture"},{"page",1}}));
        require(state["candidates"].size()==1 && !state["candidates"][0].contains("id"),"candidate contract");
        require(matchBridge.start(error,L"lyrics-bridge.cjs"),"fixture restart");
        state=finish(requestMatch({{"action","preview"},{"id","local:991"},{"session",session},{"candidate","0"}}));
        require(state["preview"]["lines"][0]["text"]=="IPC fixture","preview missing");
        require(!musxi::lyrics::readMatch(cloud::dataDir()/L"lyrics-matches",identity),"preview persisted without confirmation");
        state=requestMatch({{"action","adjust"},{"id","local:991"},{"session",session},{"offsetMs",1500}});
        require(state["preview"]["lines"][0]["timeMs"]==2500,"preview offset wrong");
        state=finish(requestMatch({{"action","save"},{"id","local:991"},{"session",session},{"offsetMs",1500}}));
        lyricsCancelled=false;
        auto lyrics=readLocalLyrics(audio,"local","local:991");
        require(lyrics["origin"]=="matched" && lyrics["lines"][0]["timeMs"]==2500,"selection did not override sidecar");
        // Restore using a new session, including a changed catalog ID for the same file.
        cancelMatch();localTracks[0]["id"]="local:992";
        auto begin=requestMatch({{"action","begin"},{"id","local:992"}});
        while(begin["status"]=="pending") {Sleep(5);begin=requestMatch({{"action","poll"},{"id","local:992"},{"session",begin["session"]},{"job",begin["job"]}});}
        require(begin["preview"]["offsetMs"]==1500,"catalog ID change lost binding");
        auto reset=requestMatch({{"action","reset"},{"id","local:992"},{"session",begin["session"]}});
        while(reset["status"]=="pending"){Sleep(5);reset=requestMatch({{"action","poll"},{"id","local:992"},{"session",reset["session"]},{"job",reset["job"]}});}
        lyricsCancelled=false;require(readLocalLyrics(audio,"local","local:992")["origin"]=="lrc-file","restore automatic source failed");
        bool rejected=false;try{requestMatch({{"action","save"},{"id","local:992"},{"session",session},{"offsetMs",0}});}catch(...){rejected=true;}
        require(rejected,"stale dialog saved selection");
        audioTrackId="local:other";matchTick();require(matchSession.empty(),"track switch retained matching session");
#endif
        stopMatch();stopLyrics();musxi::lyrics::removeMatch(cloud::dataDir()/L"lyrics-matches",identity);fs::remove_all(root);
        std::cout<<"Matching lifecycle passed\n";return 0;
    }catch(const std::exception& e){stopMatch();stopLyrics();if(identity.is_object())musxi::lyrics::removeMatch(cloud::dataDir()/L"lyrics-matches",identity);fs::remove_all(root);std::cerr<<e.what()<<'\n';return 1;}
}
