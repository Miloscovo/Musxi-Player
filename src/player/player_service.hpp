#pragma once
#include <cstdint>
#include <functional>
#include <string>
#include <thread>
#include <stdexcept>

namespace musxi {
struct PlayerState {
    bool opened=false, playing=false;
    std::uint32_t positionMs=0, durationMs=0;
    int volumePercent=75;
    std::string trackId;
};
enum class PlayerCommand { Pause, Resume, Seek, SetVolume, GetState };
enum class PlayerError { None, NotReady, InvalidArgument, BackendFailure, WrongThread };
struct PlayerResult {
    PlayerError error=PlayerError::None;
    explicit operator bool() const {return error==PlayerError::None;}
};
// Phase-one facade: adapters own the existing state and audio implementation.
// All callbacks and commands execute on the application thread, never a CEF thread.
class PlayerService {
public:
    using Snapshot=std::function<PlayerState()>;
    using Execute=std::function<PlayerResult(PlayerCommand,std::uint32_t)>;
    using Events=std::function<void(const char*,const PlayerState&)>;
    PlayerService(Snapshot snapshot,Execute execute):snapshot_(snapshot),execute_(execute),owner_(std::this_thread::get_id()) {}
    PlayerResult invoke(PlayerCommand command,std::uint32_t value=0) {
        if(std::this_thread::get_id()!=owner_) return {PlayerError::WrongThread};
        if(command==PlayerCommand::SetVolume && value>100) return {PlayerError::InvalidArgument};
        auto result=execute_(command,value);publish();return result;
    }
    PlayerState state() const {checkThread();return snapshot_();}
    void setEventSink(Events sink) {checkThread();sink_=sink;initialized_=false;}
    void publish() {
        auto now=state();
        const bool track=!initialized_ || now.trackId!=last_.trackId;
        const bool playback=!initialized_ || now.opened!=last_.opened || now.playing!=last_.playing;
        const bool position=!initialized_ || track || now.positionMs!=last_.positionMs || now.durationMs!=last_.durationMs;
        const bool volume=!initialized_ || now.volumePercent!=last_.volumePercent;
        last_=now;initialized_=true;
        if(!sink_) return;
        if(track) sink_("player.trackChanged",now);
        if(playback || track) sink_("player.stateChanged",now);
        if(position) sink_("player.positionChanged",now);
        if(volume) sink_("player.volumeChanged",now);
    }
private:
    void checkThread() const {
        if(std::this_thread::get_id()!=owner_) throw std::logic_error("PlayerService requires application thread");
    }
    Snapshot snapshot_;Execute execute_;Events sink_;
    std::thread::id owner_;PlayerState last_;bool initialized_=false;
};
}
