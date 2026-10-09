#include "../src/audio/wasapi_player.hpp"
#include <chrono>
#include <algorithm>
#include <filesystem>
#include <iostream>
#include <thread>
using namespace std::chrono_literals;
using musxi::OutputPhase;
void require(bool value,const char* text){if(!value)throw std::runtime_error(text);}
template<class F> musxi::OutputSnapshot waitFor(musxi::WasapiPlayer& player,F condition) {
    const auto deadline=std::chrono::steady_clock::now()+8s;
    do {
        const auto state=player.snapshot();
        if(state.phase==OutputPhase::Failed)throw std::runtime_error(state.error+" code="+std::to_string(state.errorCode));
        require(state.bufferedFrames<=state.capacityFrames,"PCM capacity exceeded");
        if(condition(state))return state;
        std::this_thread::sleep_for(10ms);
    }while(std::chrono::steady_clock::now()<deadline);
    throw std::runtime_error("Output test timed out");
}
int main(int argc,char** argv) {
    try {
        require(argc==2,"Usage: audio-output <audio-fixtures directory>; audible at 10% stream volume");
        const std::filesystem::path folder=argv[1];
        musxi::WasapiPlayer player;player.setVolume(10);
        player.load((folder/"source.wav").wstring());
        const auto initial=waitFor(player,[](auto s){return s.durationMs>0 && s.bufferedFrames==s.capacityFrames;});
        require(initial.positionMs==0 && initial.phase==OutputPhase::Ready,"load autoplayed");
        const auto devices=musxi::audioOutputDevices();
        require(std::any_of(devices.begin(),devices.end(),[&](const auto& device){return device.id==initial.outputDeviceId && !device.name.empty();}),"default endpoint missing from enumeration");
        std::cout<<"Device: "<<initial.format.sampleRate<<" Hz, "<<initial.format.channels<<" channels\n";
        player.play();waitFor(player,[](auto s){return s.positionMs>=180;});
        player.pause();const auto paused=player.snapshot().positionMs;
        std::this_thread::sleep_for(120ms);
        require(player.snapshot().positionMs==paused,"pause clock moved");
        player.seek(900);require(player.snapshot().phase==OutputPhase::Paused,"seek lost pause state");
        player.play();waitFor(player,[](auto s){return s.positionMs>=1050;});
        player.setVolume(0);require(player.snapshot().volumePercent==0,"mute state");
        player.setVolume(10);
        player.stop();require(player.snapshot().positionMs==0 && player.snapshot().phase==OutputPhase::Ready,"stop did not rewind/retain");
        player.play();waitFor(player,[](auto s){return s.positionMs>=100;});
        player.seek(2850);waitFor(player,[](auto s){return s.phase==OutputPhase::Ended;});
        auto events=player.takeEvents();require(events.size()==1 && events[0].ended,"terminal event missing or duplicated");
        std::this_thread::sleep_for(100ms);require(player.takeEvents().empty(),"repeated ended event");
        player.play();waitFor(player,[](auto s){return s.phase==OutputPhase::Playing && s.positionMs<1000;});
        // Repeat resets while PCM is full: cancellation must wake producer waits.
        for(int n=0;n<8;++n){player.stop();player.play();}
        player.unload();require(player.snapshot().phase==OutputPhase::Empty && player.takeEvents().empty(),"unload/stale event");
        player.load((folder/"source.wav").wstring(),initial.outputDeviceId);
        require(waitFor(player,[](auto s){return s.metadataReady;}).outputDeviceId==initial.outputDeviceId,"explicit output device ignored");
        player.unload();
        player.load((folder/"source.wav").wstring(),L"missing-output-device");
        require(waitFor(player,[](auto s){return s.metadataReady;}).outputDeviceId==initial.outputDeviceId,"missing device did not fall back to default");
        player.unload();
        for(const auto ext:{"mp3","flac","m4a","aac","ogg","opus","wma"}) {
            player.load((folder/(std::string("source.")+ext)).wstring());
            waitFor(player,[](auto s){return s.durationMs>0;});
            player.seek(2800);player.play();
            waitFor(player,[](auto s){return s.phase==OutputPhase::Ended;});
            events=player.takeEvents();require(events.size()==1 && events[0].ended,"format failed to drain");
            std::cout<<"PASS "<<ext<<" real output/drain\n";
        }
        player.load((folder/"missing.wav").wstring());
        const auto deadline=std::chrono::steady_clock::now()+2s;
        while(player.snapshot().phase!=OutputPhase::Failed && std::chrono::steady_clock::now()<deadline)std::this_thread::sleep_for(10ms);
        require(player.snapshot().phase==OutputPhase::Failed,"decode error not propagated");
        events=player.takeEvents();require(events.size()==1 && !events[0].ended && !events[0].error.empty(),"error event missing");
        player.load((folder/"source.wav").wstring());player.play();
        waitFor(player,[](auto s){return s.positionMs>50;});player.unload();
        player.load((folder/"long.wma").wstring());waitFor(player,[](auto s){return s.durationMs>0;});
        player.seek(170000);player.play();
        const auto cancelStart=std::chrono::steady_clock::now();player.unload();
        require(std::chrono::steady_clock::now()-cancelStart<1s,"long seek unload blocked");
        std::cout<<"PASS pause/resume, stop, seek, volume, bounded buffering, replacement, errors and lifecycle\n";
        return 0;
    }catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<'\n';return 1;}
}
