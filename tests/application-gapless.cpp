#include "../src/application/application.cpp"
#include <iostream>
#include <stdexcept>

void require(bool condition,const char* message){if(!condition)throw std::runtime_error(message);}
class GaplessAudio final:public musxi::IAudioBackend {
public:
    musxi::AudioState state;
    std::wstring current,next;
    std::uint64_t token=0;
    int loads=0,cancels=0;
    musxi::AudioResult load(const std::wstring& path) override {++loads;current=path;state.opened=true;++state.generation;state.transitionToken=0;return {};}
    musxi::AudioResult play() override {state.playing=state.requestedPlaying=true;state.phase="playing";return {};}
    musxi::AudioResult pause() override {state.playing=state.requestedPlaying=false;return {};}
    musxi::AudioResult resume() override{return play();}
    musxi::AudioResult stop() override{return pause();}
    musxi::AudioResult seek(std::uint32_t ms) override {state.positionMs=ms;++state.generation;return {};}
    musxi::AudioResult setVolume(int volume) override {state.volumePercent=volume;return {};}
    musxi::AudioResult unload() override {current.clear();next.clear();state={};return {};}
    musxi::AudioResult prepareNext(const std::wstring& path,std::uint64_t value) override {next=path;token=value;return {};}
    musxi::AudioResult cancelNext() override {++cancels;next.clear();token=0;return {};}
    musxi::AudioState snapshot() const override {return state;}
    void poll() override {}
    std::vector<musxi::AudioEvent> takeEvents() override {return {};}
    bool sourceReleased(const std::wstring& path) const override {return path!=current && path!=next;}
    void transition() {current=next;next.clear();state.transitionToken=token;token=0;state.positionMs=5;}
};
int main() try {
    cloud::testProfile=true;
    auto backend=std::make_unique<GaplessAudio>();auto* output=backend.get();audioBackendStorage()=std::move(backend);
    const Json a={{"id","local:a"},{"name","A"},{"path","a.wav"}},b={{"id","local:b"},{"name","B"},{"path","b.wav"}};
    localTracks=cloudQueue=Json::array({a,b});currentPlaybackTrack=a;audioTrackId="local:a";localActive=true;
    cloudCurrent=0;cloudGeneration=libraryPlayGeneration=10;libraryPlayTrackId=audioTrackId;libraryOperationStatus="completed";
    output->current=L"a.wav";output->state.opened=output->state.playing=output->state.requestedPlaying=true;output->state.phase="playing";output->state.generation=1;
    pollAudio();require(gapless.prepared && output->next==L"b.wav","local successor not prepared");
    require(audioTrackId=="local:a" && currentPlaybackTrack==a,"prepared metadata appeared before boundary");
    output->transition();pollAudio();
    require(audioTrackId=="local:b" && localCurrent==1 && cloudCurrent==1 && output->loads==0,"boundary did not adopt local successor without reload");
    cancelGapless();playbackOrder="repeat-one";pollAudio();
    require(gapless.prepared && output->next==L"b.wav","single-track loop did not reuse local path");
    const auto loop=gapless.token;output->transition();pollAudio();
    require(audioTrackId=="local:b" && gapless.token!=loop && output->loads==0,"loop did not prepare another uninterrupted pass");
    cancelGapless();playbackOrder="sequential";pollAudio();
    const auto old=gapless.token;cloudQueue=Json::array({b});pollAudio();
    require(gapless.token!=old && output->cancels>0,"queue edit retained an obsolete preparation");
    seekMilliseconds(100);require(!gapless.token,"seek retained a prepared successor");

    cancelGapless();playbackOrder="random";cloudConnected=cloudInitialized=true;localActive=false;
    const Json remote={{"id","netease:1"},{"name","One"}},second={{"id","netease:2"},{"name","Two"}},third={{"id","netease:3"},{"name","Three"}};
    currentPlaybackTrack=remote;audioTrackId=libraryPlayTrackId="netease:1";cloudQueue=Json::array({remote,second,third});cloudCurrent=0;
    output->state.generation++;output->state.transitionToken=0;output->state.playing=true;
    pollAudio();const auto candidate=gapless.requestedId;gapless.failed=true;
    require(candidate!="netease:1","random candidate repeated current song");
    require(gaplessFallback() && libraryNextPlay["id"]==candidate,"fallback redrew a random successor");
    libraryNextPlay=nullptr;cancelGapless();gaplessCancelled.clear();
    libraryOperationStatus="completed";libraryOperationKind="audio";libraryPlayGeneration=cloudGeneration;
    currentPlaybackTrack=remote;audioTrackId=libraryPlayTrackId="netease:1";cloudQueue=Json::array({remote,second});cloudCurrent=0;playbackOrder="sequential";
    const auto directory=fs::temp_directory_path()/L"musxi-application-gapless";
    fs::create_directories(directory);const auto file=directory/L"ready.wav";
    {std::ofstream stream(file);stream<<"fixture";}
    int preparedPolls=0;std::vector<std::string> operations;
    cloudRequestOverride=[&](const Json& request) {
        const auto op=request.at("op").get<std::string>();operations.push_back(op);
        if(op=="audio_prepare")return Json{{"ok",true},{"data",{{"status","pending"},{"token",request.at("token")}}}};
        if(op=="audio_prepared") {
            ++preparedPolls;
            return Json{{"ok",true},{"data",{{"status","ready"},{"token",request.at("token")},
                {"data",{{"path",file.u8string()},{"track",{{"id","netease:20"},{"name","Resolved Two"}}},{"quality","netease:hires"},{"requestedId","netease:2"}}}}}};
        }
        return Json{{"ok",true},{"data",{{"status","cancelled"}}}};
    };
    pollAudio();cloudTick();
    require(!Json::parse(musxi::applicationLibrary("library.getState","{}").json).at("busy").get<bool>(),"background request disabled foreground controls");
    cloudTick();cloudTick();
    require(gapless.prepared && preparedPolls==1 && output->next==file.wstring(),"background service result did not prepare the decoder");
    require(libraryOperationStatus=="completed" && audioTrackId=="netease:1" && toast.empty(),"background preparation changed foreground operation or notices");
    output->transition();pollAudio();
    require(audioTrackId=="netease:20" && cloudQueue[1]["id"]=="netease:20" && currentQuality=="netease:hires" && cloudTempPath==file.wstring(),"remote boundary lost resolved identity, quality or cache ownership");
    require(output->loads==0,"remote transition reloaded output");
    cancelGapless();cloudRequestOverride={};cloudQueue=Json::array();cloudTempPath.clear();output->current.clear();collectAudioCaches();
    currentPlaybackTrack=a;audioTrackId=libraryPlayTrackId="local:a";localActive=true;cloudQueue=Json::array({a,b});cloudCurrent=0;
    libraryPlayGeneration=cloudGeneration;output->current=L"a.wav";output->state.generation++;output->state.transitionToken=0;
    pollAudio();const auto cancelledBoundary=gapless.token;cancelGapless();
    // Cancellation is asynchronous: the hardware may already have crossed the boundary.
    output->current=L"b.wav";output->state.transitionToken=cancelledBoundary;pollAudio();
    require(audioTrackId=="local:b" && localCurrent==1,"late clock boundary after cancel left stale metadata");
    cancelGapless();currentPlaybackTrack=remote;audioTrackId=libraryPlayTrackId="netease:1";localActive=false;
    cloudQueue=Json::array({remote,second});cloudCurrent=0;output->state.generation++;output->state.transitionToken=0;
    pollAudio();gaplessCloudToken=gapless.token;gapless.started=true;
    cancelGapless();
    const auto stale=directory/L"stale.wav";{std::ofstream stream(stale);stream<<"late reply";}
    gaplessCloudReply("audio_prepared",{{"ok",true},{"data",{{"status","ready"},{"data",{{"path",stale.u8string()},{"track",second}}}}}});
    require(audioTrackId=="netease:1" && !gapless.token,"late cloud reply replaced current song");
    collectAudioCaches();require(!fs::exists(stale),"late cloud cache was not collected");
    currentPlaybackTrack=a;audioTrackId=libraryPlayTrackId="local:a";localActive=true;cloudQueue=Json::array({a,b});cloudCurrent=0;
    output->current=L"a.wav";output->state.generation++;output->state.transitionToken=0;
    pollAudio();const auto obsolete=gapless.token;cancelGapless();
    output->state.generation++;output->state.transitionToken=obsolete;pollAudio();
    require(audioTrackId=="local:a","old generation boundary replaced the newer source metadata");
    cancelGapless();
    cloudQueue=Json::array();gaplessCancelled.clear();
    fs::create_directories(gaplessCacheDir());
    const auto unfinished=gaplessCacheDir()/L"interrupted.mp3";
    {std::ofstream stream(unfinished);stream<<"partial download";}
    collectStoppedPreparations();require(!fs::exists(unfinished),"stopped service left an unclaimed partial cache");
    fs::remove(file);fs::remove(directory);audioBackendStorage().reset();
    std::cout<<"PASS application gapless preparation, boundary identity, loop, cancellation, random fallback and cloud handoff\n";
    return 0;
} catch(const std::exception& error) {std::cerr<<"FAIL "<<error.what()<<'\n';return 1;}
