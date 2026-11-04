#pragma once
#include "ffmpeg_decoder.hpp"
#include <memory>
#include <functional>

namespace musxi {
struct AudioOutputDevice { std::wstring id,name; };
std::vector<AudioOutputDevice> audioOutputDevices();
enum class OutputPhase { Empty, Ready, Buffering, Playing, Paused, Ended, Failed };
struct OutputSnapshot {
    OutputPhase phase=OutputPhase::Empty;
    std::int64_t positionMs=0,durationMs=-1;
    int volumePercent=75;
    PcmFormat format;
    std::uint32_t bufferedFrames=0,capacityFrames=0,underruns=0;
    std::uint64_t generation=0;
    std::string error;
    std::wstring outputDeviceId;
    std::int64_t errorCode=0;
    bool metadataReady=false;
    std::uint64_t transitionToken=0;
    std::wstring sourcePath;
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
    // Optional test probe observes exactly the PCM submitted; it must not block.
    explicit WasapiPlayer(std::function<void(const float*,std::size_t)> probe={});
    ~WasapiPlayer();
    WasapiPlayer(const WasapiPlayer&)=delete;
    WasapiPlayer& operator=(const WasapiPlayer&)=delete;
    void load(const std::wstring& path,const std::wstring& outputDevice=L"");
    void play();
    void pause();
    void stop();
    void seek(std::int64_t milliseconds);
    void setVolume(int percent);
    void unload();
    void prepareNext(const std::wstring& path,std::uint64_t token);
    void cancelNext();
    // Thread-safe cancellation signal only; resource release stays on the owner.
    void requestDecodeCancel() noexcept;
    OutputSnapshot snapshot();
    std::vector<OutputEvent> takeEvents();
private:
    struct Impl;
    std::unique_ptr<Impl> impl_;
};
}
