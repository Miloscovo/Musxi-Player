#include "../src/main.cpp"
#include <iostream>
#include <stdexcept>
void require(bool ok,const char* message) {if(!ok) throw std::runtime_error(message);}
int main() {
    auto& service=playerService();
    require(service.invoke(musxi::PlayerCommand::Pause).error==musxi::PlayerError::NotReady,"pause without track");
    require(service.invoke(musxi::PlayerCommand::Seek,10).error==musxi::PlayerError::NotReady,"seek without track");
    int count=0;musxi::setApplicationPlayerEvents([&](const char*,const musxi::PlayerState&){++count;});
    service.publish();require(count==4,"initial snapshot events");
    service.publish();require(count==4,"unchanged state emitted events");
    require(service.invoke(musxi::PlayerCommand::SetVolume,101).error==musxi::PlayerError::InvalidArgument,"invalid volume accepted");
    require(bool(musxi::applicationPlayerCommand(musxi::PlayerCommand::SetVolume,42)),"volume command failed");
    require(service.state().volumePercent==42 && count==5,"volume snapshot/event mismatch");
    musxi::PlayerError error=musxi::PlayerError::None;
    std::thread worker([&]{error=service.invoke(musxi::PlayerCommand::Pause).error;});worker.join();
    require(error==musxi::PlayerError::WrongThread,"foreign thread accepted");
    musxi::setApplicationPlayerEvents({});
    musxi::applicationPlayerCommand(musxi::PlayerCommand::SetVolume,43);
    require(count==5 && musxi::applicationPlayerState().volumePercent==43,"detached application observer received event");
    std::cout<<"PASS native player interface contracts\n";
}
