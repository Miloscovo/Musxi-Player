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
    std::string phase,error;
    bool pending=false,requestedPlaying=false;
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
    PlayerService(Snapshot snapshot,Execute execute);
    PlayerResult invoke(PlayerCommand command,std::uint32_t value=0);
    PlayerState state() const;
    void setEventSink(Events sink);
    void publish();
private:
    void checkThread() const;
    Snapshot snapshot_;Execute execute_;Events sink_;
    std::thread::id owner_;PlayerState last_;bool initialized_=false;
};
}
