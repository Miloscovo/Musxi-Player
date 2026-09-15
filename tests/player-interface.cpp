#include "../src/main.cpp"
#include <iostream>
#include <stdexcept>
void require(bool ok,const char* message) {if(!ok) throw std::runtime_error(message);}
int main() {
    auto& service=playerService();
    require(service.invoke(musxi::PlayerCommand::Pause).error==musxi::PlayerError::NotReady,"pause without track");
    require(service.invoke(musxi::PlayerCommand::Seek,10).error==musxi::PlayerError::NotReady,"seek without track");
    int count=0;service.setEventSink([&](const char*,const musxi::PlayerState&){++count;});
    service.publish();require(count==4,"initial snapshot events");
    service.publish();require(count==4,"unchanged state emitted events");
    require(service.invoke(musxi::PlayerCommand::SetVolume,101).error==musxi::PlayerError::InvalidArgument,"invalid volume accepted");
    require(bool(service.invoke(musxi::PlayerCommand::SetVolume,42)),"volume command failed");
    require(service.state().volumePercent==42 && count==5,"volume snapshot/event mismatch");
    musxi::PlayerError error=musxi::PlayerError::None;
    std::thread worker([&]{error=service.invoke(musxi::PlayerCommand::Pause).error;});worker.join();
    require(error==musxi::PlayerError::WrongThread,"foreign thread accepted");
    service.setEventSink({});
    std::cout<<"PASS native player interface contracts\n";
}
