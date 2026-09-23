#include "wasapi_player.hpp"
#include "../cxx17_guard.hpp"
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <ksmedia.h>
#include <wrl/client.h>
#include <algorithm>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <functional>
#include <future>
#include <mutex>
#include <thread>

namespace musxi {
namespace {
using Microsoft::WRL::ComPtr;
struct OutputError:std::runtime_error {
    HRESULT code;
    OutputError(const char* text,HRESULT value):std::runtime_error(text),code(value) {}
};
void check(HRESULT value,const char* operation) {if(FAILED(value))throw OutputError(operation,value);}
struct Handle {
    HANDLE value=CreateEventW(nullptr,FALSE,FALSE,nullptr);
    Handle(){if(!value)throw OutputError("Create audio event",HRESULT_FROM_WIN32(GetLastError()));}
    ~Handle(){CloseHandle(value);}
};
struct CoMemory {void* value=nullptr;~CoMemory(){CoTaskMemFree(value);}};
}
struct WasapiPlayer::Impl {
    Handle wake,audioReady;
    std::thread output,decode;
    std::mutex commandMutex,callMutex,pcmMutex;
    std::function<void()> command;
    std::condition_variable space;
    std::atomic_bool cancelled{false};
    bool quitting=false,wanted=false,running=false,ready=false,eof=false;
    std::string decodeError;
    std::int64_t decodeErrorCode=0,decodedDuration=-1;
    std::vector<float> pcm;
    std::size_t head=0,count=0;
    OutputSnapshot state;
    std::vector<OutputEvent> events;
    std::wstring path,deviceId;
    ComPtr<IMMDeviceEnumerator> enumerator;
    ComPtr<IMMDevice> device;
    ComPtr<IAudioClient> client;
    ComPtr<IAudioRenderClient> render;
    ComPtr<IAudioClock> clock;
    ComPtr<IAudioStreamVolume> volume;
    UINT32 endpointFrames=0;
    UINT64 frequency=0,submitted=0,baseFrame=0;
    WORD bits=0,validBits=0;
    bool floatDevice=false;
    std::chrono::steady_clock::time_point deviceCheck{},starvedSince{};

    Impl() {
        std::promise<void> initialized;auto result=initialized.get_future();
        output=std::thread([this,&initialized]{
            const auto hr=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
            if(FAILED(hr)){initialized.set_exception(std::make_exception_ptr(OutputError("Audio COM initialization",hr)));return;}
            initialized.set_value();
            while(!quitting) {
                HANDLE handles[]={wake.value,audioReady.value};
                const auto wait=WaitForMultipleObjects(2,handles,FALSE,50);
                std::function<void()> work;
                {std::lock_guard<std::mutex> lock(commandMutex);work=std::move(command);command={};}
                if(work)work();
                if(quitting)break;
                try {
                    if(wait==WAIT_FAILED)throw OutputError("Wait for audio",HRESULT_FROM_WIN32(GetLastError()));
                    tick();
                } catch(const OutputError& e){failed(e.what(),e.code);}
                  catch(const std::exception& e){failed(e.what(),0);}
            }
            release();CoUninitialize();
        });
        try{result.get();}catch(...){output.join();throw;}
    }
    ~Impl(){call([this]{quitting=true;});output.join();}
    void call(std::function<void()> action) {
        std::lock_guard<std::mutex> serial(callMutex);
        auto task=std::make_shared<std::packaged_task<void()>>(std::move(action));auto result=task->get_future();
        {std::lock_guard<std::mutex> lock(commandMutex);command=[task]{(*task)();};}
        SetEvent(wake.value);result.get();
    }
    void cancelDecode() {
        cancelled=true;space.notify_all();
        if(decode.joinable())decode.join();
    }
    void release() {
        if(client && running)client->Stop();
        running=false;cancelDecode();
        head=count=0;state.bufferedFrames=0;
        volume.Reset();clock.Reset();render.Reset();client.Reset();device.Reset();enumerator.Reset();
    }
    void failed(const std::string& message,std::int64_t code) {
        release();wanted=false;
        if(state.phase!=OutputPhase::Failed)events.push_back({false,state.generation,message,code});
        state.phase=OutputPhase::Failed;state.error=message;state.errorCode=code;
    }
    void guarded(std::function<void()> action) {
        call([this,action]{try{action();}catch(const OutputError& e){failed(e.what(),e.code);throw;}
            catch(const std::exception& e){failed(e.what(),0);throw;}});
    }
    std::wstring currentDevice() {
        ComPtr<IMMDevice> current;
        check(enumerator->GetDefaultAudioEndpoint(eRender,eMultimedia,&current),"Get default output device");
        CoMemory id;check(current->GetId(reinterpret_cast<LPWSTR*>(&id.value)),"Get output device ID");
        return static_cast<wchar_t*>(id.value);
    }
    void openDevice() {
        check(CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,IID_PPV_ARGS(&enumerator)),"Create device enumerator");
        check(enumerator->GetDefaultAudioEndpoint(eRender,eMultimedia,&device),"No default output device");
        CoMemory id;check(device->GetId(reinterpret_cast<LPWSTR*>(&id.value)),"Get device ID");deviceId=static_cast<wchar_t*>(id.value);
        check(device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,reinterpret_cast<void**>(client.GetAddressOf())),"Activate WASAPI");
        CoMemory mix;check(client->GetMixFormat(reinterpret_cast<WAVEFORMATEX**>(&mix.value)),"Get device format");
        const auto f=static_cast<WAVEFORMATEX*>(mix.value);
        bits=validBits=f->wBitsPerSample;floatDevice=f->wFormatTag==WAVE_FORMAT_IEEE_FLOAT;
        bool integer=f->wFormatTag==WAVE_FORMAT_PCM;
        std::uint64_t mask=0;
        if(f->wFormatTag==WAVE_FORMAT_EXTENSIBLE && f->cbSize>=22) {
            const auto ex=reinterpret_cast<WAVEFORMATEXTENSIBLE*>(f);
            floatDevice=IsEqualGUID(ex->SubFormat,KSDATAFORMAT_SUBTYPE_IEEE_FLOAT);
            integer=IsEqualGUID(ex->SubFormat,KSDATAFORMAT_SUBTYPE_PCM);
            validBits=ex->Samples.wValidBitsPerSample;mask=ex->dwChannelMask;
        }
        if(f->nChannels<1 || f->nChannels>8 || f->nSamplesPerSec<8000 || f->nSamplesPerSec>192000 ||
           f->nBlockAlign!=f->nChannels*(bits/8) ||
           !(floatDevice?bits==32:integer && (bits==16 || bits==24 || bits==32) && validBits>0 && validBits<=bits))
            throw OutputError("Unsupported device mix format",AUDCLNT_E_UNSUPPORTED_FORMAT);
        state.format={static_cast<int>(f->nSamplesPerSec),f->nChannels,mask};
        check(client->Initialize(AUDCLNT_SHAREMODE_SHARED,AUDCLNT_STREAMFLAGS_EVENTCALLBACK,0,0,f,nullptr),"Initialize shared WASAPI");
        check(client->SetEventHandle(audioReady.value),"Set audio event");
        check(client->GetBufferSize(&endpointFrames),"Get endpoint buffer size");
        check(client->GetService(IID_PPV_ARGS(&render)),"Get render client");
        check(client->GetService(IID_PPV_ARGS(&clock)),"Get audio clock");
        check(client->GetService(IID_PPV_ARGS(&volume)),"Get stream volume");
        check(clock->GetFrequency(&frequency),"Get clock frequency");
        if(!frequency)throw OutputError("Invalid audio clock frequency",E_FAIL);
        applyVolume();deviceCheck=std::chrono::steady_clock::now();
        state.capacityFrames=f->nSamplesPerSec/2; // 500 ms maximum decoded PCM.
        pcm.resize(static_cast<std::size_t>(state.capacityFrames)*f->nChannels);
    }
    void applyVolume() {
        if(volume){std::vector<float> levels(state.format.channels,state.volumePercent/100.f);
            check(volume->SetAllVolumes(static_cast<UINT32>(levels.size()),levels.data()),"Set stream volume");}
    }
    void startDecode(std::int64_t ms) {
        cancelDecode();
        state.metadataReady=false;
        {std::lock_guard<std::mutex> lock(pcmMutex);head=count=0;ready=eof=false;decodeError.clear();decodeErrorCode=0;decodedDuration=state.durationMs;}
        cancelled=false;
        const auto file=path;const auto format=state.format;
        decode=std::thread([this,file,format,ms]{
            try {
                FfmpegDecoder decoder(&cancelled);decoder.open(file,format);
                const auto duration=decoder.info().durationMs;
                if(ms)decoder.seek(ms);
                {std::lock_guard<std::mutex> lock(pcmMutex);decodedDuration=duration;ready=true;}
                SetEvent(wake.value);
                while(!cancelled) {
                    auto block=decoder.read(4096);
                    if(block.endOfStream){std::lock_guard<std::mutex> lock(pcmMutex);eof=true;SetEvent(wake.value);break;}
                    std::size_t at=0;
                    while(at<block.samples.size()) {
                        std::unique_lock<std::mutex> lock(pcmMutex);
                        space.wait(lock,[this]{return cancelled || count<pcm.size();});
                        if(cancelled)return;
                        const auto tail=(head+count)%pcm.size();
                        const auto size=std::min({block.samples.size()-at,pcm.size()-count,pcm.size()-tail});
                        std::copy_n(block.samples.data()+at,size,pcm.data()+tail);count+=size;at+=size;
                        lock.unlock();SetEvent(wake.value);
                    }
                }
            } catch(const std::exception& e) {
                if(!cancelled){std::lock_guard<std::mutex> lock(pcmMutex);decodeError=e.what();
                    if(const auto d=dynamic_cast<const DecodeError*>(&e))decodeErrorCode=d->nativeCode;
                    SetEvent(wake.value);}
            }
        });
    }
    void loadFile(const std::wstring& file) {
        release();path=file;wanted=false;submitted=baseFrame=0;
        const auto generation=state.generation+1;const auto gain=state.volumePercent;
        state={};state.generation=generation;state.volumePercent=gain;events.clear();
        openDevice();state.phase=OutputPhase::Ready;startDecode(0);
    }
    void position() {
        if(!clock)return;
        UINT64 ticks=0;check(clock->GetPosition(&ticks,nullptr),"Read output clock");
        const auto frames=std::min(submitted,static_cast<UINT64>(static_cast<long double>(ticks)*state.format.sampleRate/frequency));
        state.positionMs=static_cast<std::int64_t>((baseFrame+frames)*1000/state.format.sampleRate);
    }
    void resetTo(std::int64_t ms) {
        if(running)check(client->Stop(),"Stop output for seek");running=false;
        check(client->Reset(),"Reset output buffer");
        ++state.generation;events.clear();
        submitted=0;baseFrame=static_cast<UINT64>(ms)*state.format.sampleRate/1000;state.positionMs=ms;
        startDecode(ms);state.phase=wanted?OutputPhase::Buffering:OutputPhase::Paused;
        starvedSince=std::chrono::steady_clock::now();
    }
    void tick() {
        if(!client)return;
        const auto now=std::chrono::steady_clock::now();
        if(now-deviceCheck>std::chrono::milliseconds(250)) {
            deviceCheck=now;
            if(currentDevice()!=deviceId)throw OutputError("Default output changed; replay to use the new device",AUDCLNT_E_DEVICE_INVALIDATED);
        }
        std::unique_lock<std::mutex> lock(pcmMutex,std::try_to_lock);
        if(!lock.owns_lock())return; // Output never waits for decoding.
        if(!decodeError.empty()){auto error=decodeError;const auto code=decodeErrorCode;lock.unlock();failed(error,code);return;}
        state.metadataReady=ready;state.durationMs=decodedDuration;state.bufferedFrames=static_cast<UINT32>(count/state.format.channels);
        if(!wanted || !ready)return;
        position();
        UINT32 padding=0;check(client->GetCurrentPadding(&padding),"Read output padding");
        if(padding>endpointFrames)throw OutputError("Invalid endpoint padding",E_FAIL);
        if(eof && !count && !padding) {
            UINT64 ticks=0;check(clock->GetPosition(&ticks,nullptr),"Read final output clock");
            if(submitted && static_cast<long double>(ticks)*state.format.sampleRate/frequency<submitted)return;
            if(running)check(client->Stop(),"Stop completed stream");running=false;wanted=false;
            state.positionMs=static_cast<std::int64_t>((baseFrame+submitted)*1000/state.format.sampleRate);
            state.phase=OutputPhase::Ended;events.push_back({true,state.generation,{},0});return;
        }
        if(running && !count && !padding && !eof) {
            check(client->Stop(),"Stop starved output");running=false;
            check(client->Reset(),"Reset starved output");baseFrame+=submitted;submitted=0;
            ++state.underruns;state.phase=OutputPhase::Buffering;starvedSince=now;
        }
        if(!running && !eof && count/static_cast<std::size_t>(state.format.channels)<std::max<UINT32>(endpointFrames,state.format.sampleRate/20)) {
            if(now-starvedSince>std::chrono::seconds(5))throw OutputError("Decode buffer did not refill",HRESULT_FROM_WIN32(ERROR_TIMEOUT));
            return;
        }
        const auto frames=std::min<UINT32>(endpointFrames-padding,static_cast<UINT32>(count/state.format.channels));
        if(frames) {
            BYTE* data=nullptr;check(render->GetBuffer(frames,&data),"Acquire output buffer");
            const auto samples=static_cast<std::size_t>(frames)*state.format.channels;
            for(std::size_t i=0;i<samples;++i) {
                const float raw=pcm[(head+i)%pcm.size()];
                const float sample=std::isfinite(raw)?raw:0.f;
                if(floatDevice)std::memcpy(data+i*4,&sample,4);
                else {
                    const auto scale=std::int64_t(1)<<(validBits-1);
                    const auto signedValue=std::clamp<std::int64_t>(static_cast<std::int64_t>(std::llround(std::clamp(double(sample),-1.,1.)*double(scale))),-scale,scale-1);
                    const auto value=static_cast<std::uint32_t>(signedValue)<<(bits-validBits);
                    for(unsigned b=0;b<static_cast<unsigned>(bits/8);++b)data[i*(bits/8)+b]=static_cast<BYTE>(value>>(b*8));
                }
            }
            check(render->ReleaseBuffer(frames,0),"Submit output buffer");
            head=(head+samples)%pcm.size();count-=samples;submitted+=frames;space.notify_one();
        }
        if(!running && (frames || padding)){check(client->Start(),"Start output");running=true;state.phase=OutputPhase::Playing;}
    }
};
WasapiPlayer::WasapiPlayer():impl_(std::make_unique<Impl>()){}
WasapiPlayer::~WasapiPlayer()=default;
void WasapiPlayer::requestDecodeCancel() noexcept {impl_->cancelled=true;impl_->space.notify_all();}
void WasapiPlayer::load(const std::wstring& path){impl_->guarded([&]{impl_->loadFile(path);});}
void WasapiPlayer::play(){impl_->guarded([&]{auto& s=*impl_;
    if(s.path.empty())throw std::logic_error("No loaded track");
    if(!s.client)s.loadFile(s.path);
    if(s.state.phase==OutputPhase::Ended)s.resetTo(0);
    s.wanted=true;s.starvedSince=std::chrono::steady_clock::now();
    if(!s.running)s.state.phase=OutputPhase::Buffering;
});}
void WasapiPlayer::pause(){impl_->guarded([&]{auto& s=*impl_;if(!s.client)return;
    if(s.state.phase==OutputPhase::Ended)return;
    if(s.running)check(s.client->Stop(),"Pause output");s.running=false;s.wanted=false;s.position();s.state.phase=OutputPhase::Paused;
});}
void WasapiPlayer::stop(){impl_->guarded([&]{auto& s=*impl_;if(!s.client)return;s.wanted=false;s.resetTo(0);s.state.phase=OutputPhase::Ready;});}
void WasapiPlayer::seek(std::int64_t ms){if(ms<0 || ms>INT64_MAX/192000)throw std::invalid_argument("Seek outside supported range");impl_->guarded([&]{auto& s=*impl_;
    if(!s.client)throw std::logic_error("No output stream");
    {std::lock_guard<std::mutex> lock(s.pcmMutex);
        if(!s.ready)throw std::logic_error("Wait for source metadata before seek");
        if(s.decodedDuration>=0)ms=std::min(ms,s.decodedDuration);}
    s.resetTo(ms);
});}
void WasapiPlayer::setVolume(int percent){if(percent<0 || percent>100)throw std::invalid_argument("Volume outside 0..100");
    impl_->guarded([&]{impl_->state.volumePercent=percent;impl_->applyVolume();});}
void WasapiPlayer::unload(){impl_->guarded([&]{auto& s=*impl_;s.release();s.path.clear();s.wanted=false;
    const auto gain=s.state.volumePercent;const auto generation=s.state.generation+1;s.state={};s.state.volumePercent=gain;s.state.generation=generation;s.events.clear();});}
OutputSnapshot WasapiPlayer::snapshot(){OutputSnapshot value;impl_->call([&]{
    try{impl_->position();}catch(const OutputError& e){impl_->failed(e.what(),e.code);}
    value=impl_->state;
});return value;}
std::vector<OutputEvent> WasapiPlayer::takeEvents(){std::vector<OutputEvent> value;impl_->call([&]{value.swap(impl_->events);});return value;}
}
