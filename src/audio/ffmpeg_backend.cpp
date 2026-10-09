#include "ffmpeg_backend.hpp"
#include "wasapi_player.hpp"
#include "../cxx17_guard.hpp"
#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace musxi {
namespace {
class FfmpegBackend final:public IAudioBackend {
    struct Request {
        std::wstring path,device;
        std::uint64_t revision=0,source=0,seek=0,intent=0;
        std::uint32_t position=0;
        bool play=false;
        int volume=75;
    } desired_;
    mutable std::mutex mutex_;
    std::condition_variable wake_;
    bool exiting_=false;
    AudioState state_;
    std::vector<AudioEvent> events_;
    std::wstring activePath_,inflightPath_,preferredDevice_;
    WasapiPlayer player_;
    std::thread worker_;
    void changed(bool cancel=false) {
        ++desired_.revision;state_.pending=true;state_.error.clear();
        state_.requestedPlaying=desired_.play;
        if(cancel)player_.requestDecodeCancel();
        wake_.notify_one();
    }
    void run() {
        std::uint64_t source=0,seek=0,preparedSeek=0,intent=0,revision=0;
        int volume=-1;
        for(;;) {
            Request request;
            {std::unique_lock<std::mutex> lock(mutex_);
                wake_.wait_for(lock,std::chrono::milliseconds(15),[&]{return exiting_ || revision!=desired_.revision;});
                if(exiting_)break;request=desired_;revision=request.revision;
                if(request.source!=source || request.seek!=preparedSeek)inflightPath_=request.path;}
            try {
                if(request.source!=source || request.seek!=preparedSeek) {
                    if(request.path.empty())player_.unload();else player_.load(request.path,request.device);
                    {std::lock_guard<std::mutex> lock(mutex_);activePath_=request.path;inflightPath_.clear();}
                    source=request.source;preparedSeek=request.seek;seek=0;intent=0;
                }
                if(request.volume!=volume){player_.setVolume(request.volume);volume=request.volume;}
                auto output=player_.snapshot();
                if(!request.path.empty()) {
                    if(request.seek!=seek && output.metadataReady) {
                        player_.seek(request.position);seek=request.seek;output=player_.snapshot();
                    }
                    // Do not briefly resume the old position while a new seek is waiting.
                    if(request.intent!=intent && (request.seek==seek || !request.play)) {
                        if(request.play)player_.play();else player_.pause();intent=request.intent;
                    }
                    output=player_.snapshot();
                }
                player_.takeEvents(); // Terminal state is observed below, never on a CEF thread.
                std::lock_guard<std::mutex> lock(mutex_);
                if(request.revision!=desired_.revision)continue;
                const auto previous=state_.phase;
                state_.opened=!request.path.empty();state_.playing=output.phase==OutputPhase::Playing;
                state_.positionMs=static_cast<std::uint32_t>(std::clamp<std::int64_t>(output.positionMs,0,UINT32_MAX));
                state_.durationMs=static_cast<std::uint32_t>(std::clamp<std::int64_t>(output.durationMs,0,UINT32_MAX));
                state_.volumePercent=output.volumePercent;state_.requestedPlaying=request.play;
                const bool failed=output.phase==OutputPhase::Failed;
                state_.pending=!failed && !request.path.empty() && (!output.metadataReady || request.seek!=seek || request.intent!=intent || output.phase==OutputPhase::Buffering);
                state_.phase=request.path.empty()?"empty":failed?"failed":state_.pending?(request.seek!=seek?"seeking":"buffering"):
                    output.phase==OutputPhase::Playing?"playing":output.phase==OutputPhase::Ended?"ended":"paused";
                state_.error=output.error;
                if(failed && previous!="failed")events_.push_back({AudioEventKind::Error,state_.generation,{AudioError::BackendFailure,static_cast<std::uint32_t>(output.errorCode)}});
                if(state_.phase=="ended" && previous!="ended")events_.push_back({AudioEventKind::Ended,state_.generation,{}});
            } catch(const std::exception& e) {
                bool released=false;
                try{player_.unload();released=true;}catch(...){}
                std::lock_guard<std::mutex> lock(mutex_);
                // Mark the request handled; another explicit command may retry it.
                source=request.source;preparedSeek=seek=request.seek;intent=request.intent;
                if(released){activePath_.clear();inflightPath_.clear();}
                if(request.revision==desired_.revision) {
                    state_.playing=false;state_.pending=false;state_.phase="failed";state_.error=e.what();
                    events_.push_back({AudioEventKind::Error,state_.generation,{AudioError::BackendFailure,0}});
                }
            }
        }
        try{player_.unload();}catch(...){} // WasapiPlayer destructor still releases all resources.
    }
public:
    FfmpegBackend():worker_([this]{run();}){}
    ~FfmpegBackend() override {
        {std::lock_guard<std::mutex> lock(mutex_);exiting_=true;player_.requestDecodeCancel();}
        wake_.notify_one();worker_.join();
    }
    AudioResult load(const std::wstring& path) override {
        if(path.empty())return {AudioError::InvalidArgument,0};
        std::lock_guard<std::mutex> lock(mutex_);desired_.path=path;desired_.device=preferredDevice_;++desired_.source;desired_.seek=0;desired_.position=0;++desired_.intent;desired_.play=false;
        state_.opened=true;state_.playing=false;state_.positionMs=state_.durationMs=0;++state_.generation;state_.phase="loading";events_.clear();changed(true);return {};
    }
    AudioResult play() override {return resume();}
    AudioResult setOutputDevicePreference(const std::wstring& id) override {
        std::lock_guard<std::mutex> lock(mutex_);
        preferredDevice_=id;
        if(desired_.device==id)return {};
        desired_.device=id;
        if(!desired_.path.empty()) {
            // Keep an outstanding seek target; otherwise resume at the current clock.
            if(state_.phase!="loading" && state_.phase!="seeking")desired_.position=state_.positionMs;
            ++desired_.source;++desired_.seek;++desired_.intent;
            state_.phase="seeking";events_.clear();changed(true);
        }
        return {};
    }
    AudioResult resume() override {
        std::lock_guard<std::mutex> lock(mutex_);if(desired_.path.empty())return {AudioError::NotReady,0};
        if(state_.phase=="failed"){++desired_.source;state_.phase="loading";}
        desired_.play=true;++desired_.intent;changed();return {};
    }
    AudioResult pause() override {
        std::lock_guard<std::mutex> lock(mutex_);if(desired_.path.empty())return {AudioError::NotReady,0};
        desired_.play=false;++desired_.intent;changed();return {};
    }
    AudioResult seek(std::uint32_t position) override {
        std::lock_guard<std::mutex> lock(mutex_);if(desired_.path.empty())return {AudioError::NotReady,0};
        desired_.position=position;++desired_.seek;++desired_.intent;++state_.generation;events_.clear();state_.phase="seeking";changed(true);return {};
    }
    AudioResult stop() override {
        std::lock_guard<std::mutex> lock(mutex_);if(desired_.path.empty())return {AudioError::NotReady,0};
        desired_.play=false;desired_.position=0;++desired_.seek;++desired_.intent;++state_.generation;events_.clear();state_.phase="seeking";changed(true);return {};
    }
    AudioResult setVolume(int value) override {
        if(value<0 || value>100)return {AudioError::InvalidArgument,0};
        std::lock_guard<std::mutex> lock(mutex_);desired_.volume=value;changed();return {};
    }
    AudioResult unload() override {
        std::lock_guard<std::mutex> lock(mutex_);desired_.path.clear();++desired_.source;++desired_.intent;desired_.seek=0;desired_.play=false;
        state_.opened=state_.playing=false;state_.positionMs=state_.durationMs=0;++state_.generation;state_.phase="empty";events_.clear();changed(true);return {};
    }
    AudioState snapshot() const override {std::lock_guard<std::mutex> lock(mutex_);return state_;}
    void poll() override {}
    std::vector<AudioEvent> takeEvents() override {std::lock_guard<std::mutex> lock(mutex_);std::vector<AudioEvent> result;result.swap(events_);return result;}
    bool sourceReleased(const std::wstring& path) const override {
        std::lock_guard<std::mutex> lock(mutex_);return desired_.path!=path && activePath_!=path && inflightPath_!=path;
    }
};
}
std::unique_ptr<IAudioBackend> makeFfmpegAudioBackend(){return std::make_unique<FfmpegBackend>();}
}
