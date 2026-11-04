#include "../src/audio/wasapi_player.hpp"
#include "../src/audio/ffmpeg_backend.hpp"
#include <algorithm>
#include <chrono>
#include <filesystem>
#include <iostream>
#include <mutex>
#include <thread>
using namespace std::chrono_literals;
void require(bool value,const char* why){if(!value)throw std::runtime_error(why);}
template<class F> musxi::OutputSnapshot wait(musxi::WasapiPlayer& p,F condition) {
    const auto deadline=std::chrono::steady_clock::now()+6s;
    do {auto s=p.snapshot();require(s.phase!=musxi::OutputPhase::Failed,s.error.c_str());if(condition(s))return s;std::this_thread::sleep_for(2ms);}
    while(std::chrono::steady_clock::now()<deadline);
    const auto last=p.snapshot();std::cerr<<"Timeout phase="<<int(last.phase)<<" position="<<last.positionMs<<" token="<<last.transitionToken<<" buffered="<<last.bufferedFrames<<"\n";
    throw std::runtime_error("Gapless timeout");
}
std::vector<float> decode(const std::filesystem::path& path,musxi::PcmFormat format) {
    musxi::FfmpegDecoder d;d.open(path.wstring(),format);std::vector<float> result;
    for(;;){auto b=d.read();if(b.endOfStream)return result;result.insert(result.end(),b.samples.begin(),b.samples.end());}
}
int main(int argc,char** argv) {
    try {
        require(argc==2 || argc==3,"audio-gapless <fixtures> [authorized cloud cache]");const std::filesystem::path folder=argv[1];
        std::vector<float> submitted;std::mutex mutex;
        musxi::WasapiPlayer p([&](const float* samples,std::size_t size){std::lock_guard<std::mutex> lock(mutex);submitted.insert(submitted.end(),samples,samples+size);});
        p.setVolume(5);
        for(const auto extension:{"wav","flac","mp3","m4a","opus","wav-long"}) {
            const bool longer=std::string(extension)=="wav-long";
            const auto a=folder/(longer?"source.wav":std::string("gapless.")+extension),b=folder/"gapless-mono.wav";
            p.load(a.wstring());const auto initial=wait(p,[](auto s){return s.metadataReady && s.bufferedFrames;});
            auto expected=decode(a,initial.format),tail=decode(b,initial.format);
            if(!longer)require(expected.size()==static_cast<std::size_t>(initial.format.sampleRate/4)*initial.format.channels,"Encoder delay/padding was not removed");
            expected.insert(expected.end(),tail.begin(),tail.end());
            {std::lock_guard<std::mutex> lock(mutex);submitted.clear();}
            p.prepareNext(b.wstring(),11);std::this_thread::sleep_for(80ms);p.play();
            const auto crossed=wait(p,[](auto s){return s.transitionToken==11;});
            require(crossed.generation==initial.generation && crossed.underruns==0,"Transition reset output or underrun");
            require(crossed.positionMs<100 && crossed.sourcePath==b.wstring(),"Track identity/clock was not rebased at boundary");
            require(p.takeEvents().empty(),"Ended event between prepared tracks");
            wait(p,[](auto s){return s.phase==musxi::OutputPhase::Ended;});
            {std::lock_guard<std::mutex> lock(mutex);require(submitted==expected,"Submitted PCM inserted, dropped, repeated or modified samples");}
            require(p.takeEvents().size()==1,"Final end event missing");
            p.unload();std::cout<<"PASS "<<extension<<" -> 96k mono: exact PCM concatenation, continuous device, clock boundary\n";
        }
        p.load((folder/"gapless.wav").wstring());wait(p,[](auto s){return s.metadataReady;});
        p.prepareNext((folder/"missing.wav").wstring(),12);p.play();wait(p,[](auto s){return s.phase==musxi::OutputPhase::Ended;});
        require(p.snapshot().transitionToken==0,"Failed next source interrupted current source");
        p.load((folder/"gapless.wav").wstring());wait(p,[](auto s){return s.metadataReady;});
        p.prepareNext((folder/"gapless.flac").wstring(),13);p.cancelNext();p.play();
        wait(p,[](auto s){return s.phase==musxi::OutputPhase::Ended;});require(p.snapshot().transitionToken==0,"Cancelled source played");
        p.load((folder/"gapless.wav").wstring());wait(p,[](auto s){return s.metadataReady;});
        auto generation=p.snapshot().generation;
        for(std::uint64_t token=20;token<24;++token) {
            p.prepareNext((folder/"gapless.wav").wstring(),token);std::this_thread::sleep_for(30ms);p.play();
            if(token==20){p.pause();std::this_thread::sleep_for(30ms);require(p.snapshot().transitionToken==0,"Paused playback advanced");p.play();}
            wait(p,[&](auto s){return s.transitionToken==token;});require(p.snapshot().generation==generation,"Loop reset device");
        }
        p.unload();
        auto backend=musxi::makeFfmpegAudioBackend();backend->setVolume(5);backend->load((folder/"gapless.wav").wstring());backend->play();
        const auto deadline=std::chrono::steady_clock::now()+6s;
        while(backend->snapshot().pending && std::chrono::steady_clock::now()<deadline)std::this_thread::sleep_for(2ms);
        require(bool(backend->prepareNext((folder/"gapless.flac").wstring(),99)),"Backend prepare rejected");
        while(backend->snapshot().transitionToken!=99 && std::chrono::steady_clock::now()<deadline)std::this_thread::sleep_for(2ms);
        require(backend->snapshot().transitionToken==99,"Backend lost transition token");backend->unload();
        if(argc==3) {
            const auto cloud=std::filesystem::u8path(argv[2]).wstring();p.load(cloud);
            const auto info=wait(p,[](auto s){return s.metadataReady && s.durationMs>200;});
            p.seek(info.durationMs-150);p.prepareNext(cloud,501);std::this_thread::sleep_for(80ms);p.play();
            const auto loop=wait(p,[](auto s){return s.transitionToken==501;});
            require(loop.positionMs<100 && loop.underruns==0,"Authorized cloud cache did not loop gaplessly");p.unload();
            std::cout<<"PASS authorized cloud download: continuous real-device loop\n";
        }
        std::cout<<"PASS next failure/cancellation, repeated loop, pause and asynchronous backend transition\n";return 0;
    }catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<'\n';return 1;}
}


