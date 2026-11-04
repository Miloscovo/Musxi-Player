#pragma once
#include "ffmpeg_decoder.hpp"
#include <algorithm>
#include <condition_variable>
#include <mutex>
#include <thread>

namespace musxi {
// Each track owns its decoder and a bounded half-second of PCM, including its SRC tail.
struct DecodedSource {
    std::mutex mutex;
    std::condition_variable space;
    std::atomic_bool cancelled{false};
    std::thread worker;
    std::vector<float> pcm;
    std::size_t head=0,count=0;
    bool ready=false,eof=false;
    std::string error;
    std::int64_t errorCode=0,duration=-1;
    int exactIntegerBits=0;
    const std::wstring path;
    DecodedSource(std::wstring file,PcmFormat format,std::int64_t ms=0):pcm(static_cast<std::size_t>(format.sampleRate/2)*format.channels),path(std::move(file)) {
        worker=std::thread([this,format,ms]{
            try {
                FfmpegDecoder decoder(&cancelled);decoder.open(path,format);
                if(ms)decoder.seek(ms);
                {std::lock_guard<std::mutex> lock(mutex);duration=decoder.info().durationMs;ready=true;}
                while(!cancelled) {
                    auto block=decoder.read(4096);
                    if(block.endOfStream){std::lock_guard<std::mutex> lock(mutex);eof=true;break;}
                    std::size_t at=0;
                    while(at<block.samples.size()) {
                        std::unique_lock<std::mutex> lock(mutex);
                        space.wait(lock,[this]{return cancelled || count<pcm.size();});
                        if(cancelled)return;
                        exactIntegerBits=decoder.info().exactIntegerBits;
                        const auto tail=(head+count)%pcm.size();
                        const auto size=std::min({block.samples.size()-at,pcm.size()-count,pcm.size()-tail});
                        std::copy_n(block.samples.data()+at,size,pcm.data()+tail);count+=size;at+=size;
                    }
                }
            } catch(const std::exception& e) {
                if(!cancelled){std::lock_guard<std::mutex> lock(mutex);error=e.what();
                    if(const auto d=dynamic_cast<const DecodeError*>(&e))errorCode=d->nativeCode;}
            }
        });
    }
    void cancel() noexcept {cancelled=true;space.notify_all();}
    ~DecodedSource(){cancel();if(worker.joinable())worker.join();}
};
}
