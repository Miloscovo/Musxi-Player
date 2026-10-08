#include "../src/player/player_service.hpp"
#include <iostream>
#include <stdexcept>
#include <vector>
void require(bool ok,const char* message) {if(!ok) throw std::runtime_error(message);}
// Core-only contract test: a fake adapter, no FFmpeg, WASAPI or application state.
int main() {
    try {
    musxi::PlayerState fake;std::vector<std::string> events;int executed=0;
    musxi::PlayerService service([&]{return fake;},[&](musxi::PlayerCommand command,std::uint32_t value) {
        ++executed;
        if(command==musxi::PlayerCommand::SetVolume)fake.volumePercent=static_cast<int>(value);
        if(command==musxi::PlayerCommand::Seek)fake.positionMs=value;
        return musxi::PlayerResult{};
    });
    service.setEventSink([&](const char* name,const musxi::PlayerState&){events.push_back(name);});
    service.publish();require(events.size()==4,"initial snapshot events");
    service.publish();require(events.size()==4,"unchanged state emitted events");
    require(service.invoke(musxi::PlayerCommand::SetVolume,101).error==musxi::PlayerError::InvalidArgument && executed==0,"invalid volume reached adapter");
    require(bool(service.invoke(musxi::PlayerCommand::SetVolume,42)) && events.size()==5 && events.back()=="player.volumeChanged","volume event mismatch");
    require(bool(service.invoke(musxi::PlayerCommand::Seek,1000)) && events.size()==6 && events.back()=="player.positionChanged","position event mismatch");
    fake.trackId="next";service.publish();
    require(events.size()==9 && events[6]=="player.trackChanged" && events[7]=="player.stateChanged" && events[8]=="player.positionChanged","track change events");
    musxi::PlayerError error=musxi::PlayerError::None;bool threw=false;
    std::thread worker([&]{error=service.invoke(musxi::PlayerCommand::Pause).error;try{(void)service.state();}catch(const std::logic_error&){threw=true;}});worker.join();
    require(error==musxi::PlayerError::WrongThread && threw && executed==2,"foreign thread accepted");
    service.setEventSink({});fake.volumePercent=10;service.publish();
    require(events.size()==9,"detached sink received events");
    std::cout<<"PASS core player service contracts\n";
    } catch(const std::exception& error) {std::cerr<<"FAIL "<<error.what()<<'\n';return 1;}
}
