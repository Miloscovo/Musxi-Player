#include "wasapi_player.hpp"
#include "pcm_output.hpp"
#include "output_drain.hpp"
#include "decoded_source.hpp"
#include "../cxx17_guard.hpp"
#define WIN32_LEAN_AND_MEAN
#define NOMINMAX
#include <windows.h>
#include <audioclient.h>
#include <mmdeviceapi.h>
#include <functiondiscoverykeys_devpkey.h>
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
std::vector<AudioOutputDevice> audioOutputDevices() {
    const auto hr=CoInitializeEx(nullptr,COINIT_MULTITHREADED);
    if(FAILED(hr) && hr!=RPC_E_CHANGED_MODE)check(hr,"Initialize device enumeration");
    struct Cleanup {bool initialized;~Cleanup(){if(initialized)CoUninitialize();}} cleanup{SUCCEEDED(hr)};
    ComPtr<IMMDeviceEnumerator> enumerator;
    check(CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,IID_PPV_ARGS(&enumerator)),"Create device enumerator");
    ComPtr<IMMDeviceCollection> devices;
    check(enumerator->EnumAudioEndpoints(eRender,DEVICE_STATE_ACTIVE,&devices),"List output devices");
    UINT count=0;check(devices->GetCount(&count),"Count output devices");
    std::vector<AudioOutputDevice> rows;
    for(UINT i=0;i<count;++i) {
        ComPtr<IMMDevice> device;check(devices->Item(i,&device),"Read output device");
        CoMemory id;check(device->GetId(reinterpret_cast<LPWSTR*>(&id.value)),"Read output ID");
        ComPtr<IPropertyStore> properties;check(device->OpenPropertyStore(STGM_READ,&properties),"Read output properties");
        PROPVARIANT name{};
        const auto result=properties->GetValue(PKEY_Device_FriendlyName,&name);
        const std::wstring label=SUCCEEDED(result) && name.vt==VT_LPWSTR && name.pwszVal?name.pwszVal:L"音频输出设备";
        PropVariantClear(&name);
        rows.push_back({static_cast<wchar_t*>(id.value),label});
    }
    return rows;
}
struct WasapiPlayer::Impl {
    Handle wake,audioReady;
    std::thread output;
    std::mutex commandMutex,callMutex,sourceMutex;
    std::function<void()> command;
    bool quitting=false,wanted=false,running=false;
    std::shared_ptr<DecodedSource> source,next;
    std::uint64_t nextToken=0,boundaryToken=0,boundaryFrame=0,trackStart=0;
    std::wstring boundaryPath;
    std::int64_t boundaryDuration=-1;
    PcmWriter pcmWriter;
    std::function<void(const float*,std::size_t)> probe;
    OutputSnapshot state;
    std::vector<OutputEvent> events;
    std::wstring path,deviceId,preferredDevice;
    bool usingDefault=true;
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
    OutputDrain drain;
    std::chrono::steady_clock::duration drainGrace{};

    explicit Impl(std::function<void(const float*,std::size_t)> observer):probe(std::move(observer)) {
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
        std::lock_guard<std::mutex> lock(sourceMutex);
        source.reset();next.reset();nextToken=boundaryToken=0;boundaryPath.clear();
    }
    void release() {
        drain.reset();
        if(client && running)client->Stop();
        running=false;cancelDecode();
        state.bufferedFrames=0;
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
        usingDefault=true;
        if(!preferredDevice.empty() && SUCCEEDED(enumerator->GetDevice(preferredDevice.c_str(),&device))) {
            DWORD status=0;
            usingDefault=FAILED(device->GetState(&status)) || status!=DEVICE_STATE_ACTIVE;
        }
        if(usingDefault) {device.Reset();check(enumerator->GetDefaultAudioEndpoint(eRender,eMultimedia,&device),"No default output device");}
        CoMemory id;check(device->GetId(reinterpret_cast<LPWSTR*>(&id.value)),"Get device ID");deviceId=static_cast<wchar_t*>(id.value);
        state.outputDeviceId=deviceId;
        check(device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,reinterpret_cast<void**>(client.GetAddressOf())),"Activate WASAPI");
        CoMemory mix;check(client->GetMixFormat(reinterpret_cast<WAVEFORMATEX**>(&mix.value)),"Get device format");
        auto f=static_cast<WAVEFORMATEX*>(mix.value);
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
        WAVEFORMATEXTENSIBLE floating{};
        const auto mixBits=bits,mixValidBits=validBits;
        if(integer) {
            floating.Format=*f;floating.Format.wFormatTag=WAVE_FORMAT_EXTENSIBLE;
            floating.Format.cbSize=22;floating.Format.wBitsPerSample=32;
            floating.Format.nBlockAlign=f->nChannels*4;
            floating.Format.nAvgBytesPerSec=f->nSamplesPerSec*floating.Format.nBlockAlign;
            floating.Samples.wValidBitsPerSample=32;floating.dwChannelMask=static_cast<DWORD>(mask);
            floating.SubFormat=KSDATAFORMAT_SUBTYPE_IEEE_FLOAT;
            CoMemory closest;
            const auto supported=client->IsFormatSupported(AUDCLNT_SHAREMODE_SHARED,&floating.Format,
                reinterpret_cast<WAVEFORMATEX**>(&closest.value));
            if(supported==S_OK){f=&floating.Format;floatDevice=true;bits=validBits=32;}
            else if(FAILED(supported) && supported!=AUDCLNT_E_UNSUPPORTED_FORMAT)check(supported,"Check shared float format");
        }
        state.format={static_cast<int>(f->nSamplesPerSec),f->nChannels,mask};
        auto initialized=client->Initialize(AUDCLNT_SHAREMODE_SHARED,AUDCLNT_STREAMFLAGS_EVENTCALLBACK,0,0,f,nullptr);
        if(initialized==AUDCLNT_E_UNSUPPORTED_FORMAT && f==&floating.Format) {
            // Some drivers accept the probe but reject initialization; retain the known mix fallback.
            client.Reset();
            check(device->Activate(__uuidof(IAudioClient),CLSCTX_ALL,nullptr,reinterpret_cast<void**>(client.GetAddressOf())),"Reactivate WASAPI");
            f=static_cast<WAVEFORMATEX*>(mix.value);floatDevice=false;bits=mixBits;validBits=mixValidBits;
            initialized=client->Initialize(AUDCLNT_SHAREMODE_SHARED,AUDCLNT_STREAMFLAGS_EVENTCALLBACK,0,0,f,nullptr);
        }
        check(initialized,"Initialize shared WASAPI");
        check(client->SetEventHandle(audioReady.value),"Set audio event");
        check(client->GetBufferSize(&endpointFrames),"Get endpoint buffer size");
        REFERENCE_TIME latency=2500000;
        REFERENCE_TIME reportedLatency=0;
        if(SUCCEEDED(client->GetStreamLatency(&reportedLatency)) && reportedLatency>=0)latency=reportedLatency;
        drainGrace=std::chrono::milliseconds(latency/10000+(endpointFrames*1000ULL+f->nSamplesPerSec-1)/f->nSamplesPerSec+50);
        check(client->GetService(IID_PPV_ARGS(&render)),"Get render client");
        check(client->GetService(IID_PPV_ARGS(&clock)),"Get audio clock");
        check(client->GetService(IID_PPV_ARGS(&volume)),"Get stream volume");
        check(clock->GetFrequency(&frequency),"Get clock frequency");
        if(!frequency)throw OutputError("Invalid audio clock frequency",E_FAIL);
        applyVolume();deviceCheck=std::chrono::steady_clock::now();
        state.capacityFrames=f->nSamplesPerSec/2; // 500 ms maximum decoded PCM.
    }
    void applyVolume() {
        if(volume){std::vector<float> levels(state.format.channels,state.volumePercent/100.f);
            check(volume->SetAllVolumes(static_cast<UINT32>(levels.size()),levels.data()),"Set stream volume");}
    }
    void startDecode(std::int64_t ms) {
        drain.reset();cancelDecode();state.metadataReady=false;pcmWriter={};
        std::lock_guard<std::mutex> lock(sourceMutex);
        source=std::make_shared<DecodedSource>(path,state.format,ms);
    }
    bool connectNext() {
        if(!next || boundaryToken)return false;
        std::unique_lock<std::mutex> lock(next->mutex,std::try_to_lock);
        if(!lock.owns_lock() || !next->ready || !next->error.empty() ||
           (!next->eof && next->count/static_cast<std::size_t>(state.format.channels)<std::max<UINT32>(endpointFrames,state.format.sampleRate/20)))return false;
        boundaryFrame=submitted;boundaryToken=nextToken;boundaryPath=next->path;boundaryDuration=next->duration;
        lock.unlock();
        {std::lock_guard<std::mutex> owner(sourceMutex);source=std::move(next);nextToken=0;}
        drain.reset();return true;
    }
    void cancelPrepared() {
        position();
        {std::lock_guard<std::mutex> lock(sourceMutex);next.reset();nextToken=0;}
        // Already submitted next-track frames must be flushed when the queue changes.
        if(boundaryToken)resetTo(state.positionMs);
    }
    void loadFile(const std::wstring& file) {
        release();path=file;wanted=false;submitted=baseFrame=trackStart=0;
        const auto generation=state.generation+1;const auto gain=state.volumePercent;
        state={};state.generation=generation;state.volumePercent=gain;events.clear();
        openDevice();state.phase=OutputPhase::Ready;startDecode(0);
    }
    void position() {
        if(!clock)return;
        UINT64 ticks=0;check(clock->GetPosition(&ticks,nullptr),"Read output clock");
        const auto frames=std::min(submitted,static_cast<UINT64>(static_cast<long double>(ticks)*state.format.sampleRate/frequency));
        if(boundaryToken && frames>=boundaryFrame) {
            trackStart=boundaryFrame;baseFrame=0;path=boundaryPath;state.durationMs=boundaryDuration;
            state.transitionToken=boundaryToken;boundaryToken=0;boundaryPath.clear();
        }
        state.positionMs=static_cast<std::int64_t>((baseFrame+frames-trackStart)*1000/state.format.sampleRate);
    }
    void resetTo(std::int64_t ms) {
        if(running)check(client->Stop(),"Stop output for seek");running=false;
        check(client->Reset(),"Reset output buffer");
        ++state.generation;events.clear();
        submitted=trackStart=0;baseFrame=static_cast<UINT64>(ms)*state.format.sampleRate/1000;state.positionMs=ms;
        startDecode(ms);state.phase=wanted?OutputPhase::Buffering:OutputPhase::Paused;
        starvedSince=std::chrono::steady_clock::now();
    }
    void tick() {
        if(!client)return;
        const auto now=std::chrono::steady_clock::now();
        if(now-deviceCheck>std::chrono::milliseconds(250)) {
            deviceCheck=now;
            if(usingDefault && currentDevice()!=deviceId)throw OutputError("Default output changed; replay to use the new device",AUDCLNT_E_DEVICE_INVALIDATED);
        }
        if(!source)return;
        position();
        UINT32 padding=0;check(client->GetCurrentPadding(&padding),"Read output padding");
        if(padding>endpointFrames)throw OutputError("Invalid endpoint padding",E_FAIL);
        for(;;) {
            auto current=source;
            std::unique_lock<std::mutex> lock(current->mutex,std::try_to_lock);
            if(!lock.owns_lock())return;
            if(!current->error.empty()){auto error=current->error;const auto code=current->errorCode;lock.unlock();failed(error,code);return;}
            if(!boundaryToken){state.metadataReady=current->ready;state.durationMs=current->duration;}
            state.bufferedFrames=static_cast<UINT32>(current->count/state.format.channels);
            if(!wanted || !current->ready)return;
            if(current->eof && !current->count) {
                lock.unlock();
                if(connectNext())continue;
                if(!running && padding){check(client->Start(),"Resume buffered tail");running=true;state.phase=OutputPhase::Playing;}
                if(!padding) {
                    UINT64 ticks=0;check(clock->GetPosition(&ticks,nullptr),"Read final output clock");
                    if(!drain.complete(true,static_cast<long double>(ticks)*state.format.sampleRate/frequency,submitted,now,drainGrace))return;
                    // The endpoint can drain with its reported clock slightly behind the boundary.
                    if(boundaryToken){trackStart=boundaryFrame;baseFrame=0;path=boundaryPath;state.durationMs=boundaryDuration;
                        state.transitionToken=boundaryToken;boundaryToken=0;boundaryPath.clear();}
                    if(running)check(client->Stop(),"Stop completed stream");running=false;wanted=false;
                    state.positionMs=static_cast<std::int64_t>((baseFrame+submitted-trackStart)*1000/state.format.sampleRate);
                    state.phase=OutputPhase::Ended;events.push_back({true,state.generation,{},0});
                }
                return;
            }
            if(running && !current->count && !padding && !current->eof) {
                if(boundaryToken) {
                    UINT64 ticks=0;check(clock->GetPosition(&ticks,nullptr),"Read starved boundary clock");
                    if(!drain.complete(true,static_cast<long double>(ticks)*state.format.sampleRate/frequency,submitted,now,drainGrace))return;
                    trackStart=boundaryFrame;baseFrame=0;path=boundaryPath;state.durationMs=boundaryDuration;
                    state.transitionToken=boundaryToken;boundaryToken=0;boundaryPath.clear();
                }
                check(client->Stop(),"Stop starved output");running=false;
                check(client->Reset(),"Reset starved output");baseFrame+=submitted-trackStart;submitted=trackStart=0;
                ++state.underruns;state.phase=OutputPhase::Buffering;starvedSince=now;
            }
            drain.reset();
            if(!running && !padding && !current->eof && current->count/static_cast<std::size_t>(state.format.channels)<std::max<UINT32>(endpointFrames,state.format.sampleRate/20)) {
                if(now-starvedSince>std::chrono::seconds(5))throw OutputError("Decode buffer did not refill",HRESULT_FROM_WIN32(ERROR_TIMEOUT));
                return;
            }
            const auto frames=std::min<UINT32>(endpointFrames-padding,static_cast<UINT32>(current->count/state.format.channels));
            if(!frames){if(!running && padding){check(client->Start(),"Resume buffered output");running=true;state.phase=OutputPhase::Playing;}return;}
            BYTE* data=nullptr;check(render->GetBuffer(frames,&data),"Acquire output buffer");
            const auto samples=static_cast<std::size_t>(frames)*state.format.channels;
            const bool dither=!current->exactIntegerBits || current->exactIntegerBits>validBits;
            const auto first=std::min(samples,current->pcm.size()-current->head);
            if(probe){probe(current->pcm.data()+current->head,first);if(first<samples)probe(current->pcm.data(),samples-first);}
            pcmWriter.write(data,current->pcm.data()+current->head,first,floatDevice,bits,validBits,dither);
            if(first<samples)pcmWriter.write(data+first*(bits/8),current->pcm.data(),samples-first,floatDevice,bits,validBits,dither);
            check(render->ReleaseBuffer(frames,0),"Submit output buffer");
            current->head=(current->head+samples)%current->pcm.size();current->count-=samples;submitted+=frames;padding+=frames;current->space.notify_one();
            lock.unlock();
            if(!running){check(client->Start(),"Start output");running=true;state.phase=OutputPhase::Playing;}
            if(padding>=endpointFrames)return;
        }
    }
};
WasapiPlayer::WasapiPlayer(std::function<void(const float*,std::size_t)> probe):impl_(std::make_unique<Impl>(std::move(probe))){}
WasapiPlayer::~WasapiPlayer()=default;
void WasapiPlayer::requestDecodeCancel() noexcept {
    std::lock_guard<std::mutex> lock(impl_->sourceMutex);
    if(impl_->source)impl_->source->cancel();if(impl_->next)impl_->next->cancel();
}
void WasapiPlayer::load(const std::wstring& path,const std::wstring& outputDevice){impl_->guarded([&]{impl_->preferredDevice=outputDevice;impl_->loadFile(path);});}
void WasapiPlayer::play(){impl_->guarded([&]{auto& s=*impl_;
    if(s.path.empty())throw std::logic_error("No loaded track");
    if(!s.client)s.loadFile(s.path);
    if(s.state.phase==OutputPhase::Ended)s.resetTo(0);
    s.wanted=true;s.starvedSince=std::chrono::steady_clock::now();
    if(!s.running)s.state.phase=OutputPhase::Buffering;
});}
void WasapiPlayer::pause(){impl_->guarded([&]{auto& s=*impl_;if(!s.client)return;
    if(s.state.phase==OutputPhase::Ended)return;
    if(s.running)check(s.client->Stop(),"Pause output");s.drain.reset();s.running=false;s.wanted=false;s.position();s.state.phase=OutputPhase::Paused;
});}
void WasapiPlayer::stop(){impl_->guarded([&]{auto& s=*impl_;if(!s.client)return;s.wanted=false;s.resetTo(0);s.state.phase=OutputPhase::Ready;});}
void WasapiPlayer::seek(std::int64_t ms){if(ms<0 || ms>INT64_MAX/192000)throw std::invalid_argument("Seek outside supported range");impl_->guarded([&]{auto& s=*impl_;
    if(!s.client)throw std::logic_error("No output stream");
    s.position();
    {std::lock_guard<std::mutex> lock(s.source->mutex);
        if(!s.source->ready)throw std::logic_error("Wait for source metadata before seek");
        if(s.state.durationMs>=0)ms=std::min(ms,s.state.durationMs);}
    s.resetTo(ms);
});}
void WasapiPlayer::prepareNext(const std::wstring& path,std::uint64_t token) {
    if(path.empty() || !token)throw std::invalid_argument("Invalid next track");
    impl_->call([&]{auto& s=*impl_;if(!s.client || s.boundaryToken)return;
        std::lock_guard<std::mutex> lock(s.sourceMutex);
        s.next=std::make_shared<DecodedSource>(path,s.state.format);s.nextToken=token;
    });
}
void WasapiPlayer::cancelNext(){impl_->guarded([&]{impl_->cancelPrepared();});}
void WasapiPlayer::setVolume(int percent){if(percent<0 || percent>100)throw std::invalid_argument("Volume outside 0..100");
    impl_->guarded([&]{impl_->state.volumePercent=percent;impl_->applyVolume();});}
void WasapiPlayer::unload(){impl_->guarded([&]{auto& s=*impl_;s.release();s.path.clear();s.wanted=false;
    const auto gain=s.state.volumePercent;const auto generation=s.state.generation+1;s.state={};s.state.volumePercent=gain;s.state.generation=generation;s.events.clear();});}
OutputSnapshot WasapiPlayer::snapshot(){OutputSnapshot value;impl_->call([&]{
    try{impl_->position();}catch(const OutputError& e){impl_->failed(e.what(),e.code);}
    value=impl_->state;value.sourcePath=impl_->path;
});return value;}
std::vector<OutputEvent> WasapiPlayer::takeEvents(){std::vector<OutputEvent> value;impl_->call([&]{value.swap(impl_->events);});return value;}
}
