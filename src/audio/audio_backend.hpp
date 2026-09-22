#pragma once
#include <cstdint>
#include <string>
#include <vector>

namespace musxi {
enum class AudioError { None, NotReady, InvalidArgument, BackendFailure };
struct AudioResult {
    AudioError error=AudioError::None;
    std::uint32_t nativeCode=0;
    explicit operator bool() const { return error==AudioError::None; }
};
struct AudioState {
    bool opened=false, playing=false;
    std::uint32_t positionMs=0, durationMs=0;
    int volumePercent=75;
    std::uint64_t generation=0;
};
enum class AudioEventKind { Ended, Error };
struct AudioEvent {
    AudioEventKind kind;
    std::uint64_t generation;
    AudioResult result;
};
// Application-thread interface. No platform handles or third-party types cross it.
// Stop retains the source at position zero; unload releases it, never deletes it.
class IAudioBackend {
public:
    virtual ~IAudioBackend()=default;
    virtual AudioResult load(const std::wstring& localPath)=0;
    virtual AudioResult play()=0;
    virtual AudioResult pause()=0;
    virtual AudioResult resume()=0;
    virtual AudioResult stop()=0;
    virtual AudioResult seek(std::uint32_t positionMs)=0;
    virtual AudioResult setVolume(int percent)=0;
    virtual AudioResult unload()=0;
    virtual AudioState snapshot() const=0;
    virtual void poll()=0;
    virtual std::vector<AudioEvent> takeEvents()=0;
};
}
