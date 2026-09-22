#pragma once
#include "ffmpeg_decoder.hpp"
#include <memory>

namespace musxi {
enum class OutputPhase { Empty, Ready, Buffering, Playing, Paused, Ended, Failed };
struct OutputSnapshot {
    OutputPhase phase=OutputPhase::Empty;
    std::int64_t positionMs=0,durationMs=-1;
    int volumePercent=75;
    PcmFormat format;
    std::uint32_t bufferedFrames=0,capacityFrames=0,underruns=0;
    std::uint64_t generation=0;
    std::string error;
    std::int64_t errorCode=0;
};
struct OutputEvent {
    bool ended=false;
    std::uint64_t generation=0;
    std::string error;
    std::int64_t errorCode=0;
};
// A3 standalone pipeline. Commands acknowledge on the WASAPI owner thread.
// load/seek start asynchronous decoding; use snapshot/events for completion.
// A4 must dispatch these synchronous control calls away from the UI thread.
class WasapiPlayer {
public:
    WasapiPlayer();
    ~WasapiPlayer();
    WasapiPlayer(const WasapiPlayer&)=delete;
    WasapiPlayer& operator=(const WasapiPlayer&)=delete;
    void load(const std::wstring& path);
    void play();
    void pause();
    void stop();
    void seek(std::int64_t milliseconds);
    void setVolume(int percent);
    void unload();
    OutputSnapshot snapshot();
    std::vector<OutputEvent> takeEvents();
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}
