#include "player_service.hpp"
#include "../cxx17_guard.hpp"

namespace musxi {
PlayerService::PlayerService(Snapshot snapshot,Execute execute):snapshot_(snapshot),execute_(execute),owner_(std::this_thread::get_id()) {}

PlayerResult PlayerService::invoke(PlayerCommand command,std::uint32_t value) {
        if(std::this_thread::get_id()!=owner_) return {PlayerError::WrongThread};
        if(command==PlayerCommand::SetVolume && value>100) return {PlayerError::InvalidArgument};
        auto result=execute_(command,value);publish();return result;
    }

PlayerState PlayerService::state() const {checkThread();return snapshot_();}

void PlayerService::setEventSink(Events sink) {checkThread();sink_=sink;initialized_=false;}

void PlayerService::publish() {
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

void PlayerService::checkThread() const {
        if(std::this_thread::get_id()!=owner_) throw std::logic_error("PlayerService requires application thread");
    }
}
