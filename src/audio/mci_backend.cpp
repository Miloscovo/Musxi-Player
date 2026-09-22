#include "mci_backend.hpp"
#include "../cxx17_guard.hpp"
#include <windows.h>
#include <mmsystem.h>
#include <mmdeviceapi.h>
#include <audiopolicy.h>
#include <algorithm>
#include <atomic>
#include <utility>

namespace musxi {
namespace {
class MciAudioBackend final : public IAudioBackend {
    AudioState state_;
    std::vector<AudioEvent> events_;
    HWND notify_=nullptr;
    std::wstring alias_;
    MCIERROR command(const std::wstring& text) {
        return mciSendStringW(text.c_str(),nullptr,0,notify_);
    }
    AudioResult result(MCIERROR code) const {
        return {code?AudioError::BackendFailure:AudioError::None,code};
    }
    MCIERROR number(const wchar_t* field,std::uint32_t& value) {
        wchar_t text[64]{};
        auto error=mciSendStringW((L"status "+alias_+L" "+field).c_str(),text,64,nullptr);
        if(!error)value=wcstoul(text,nullptr,10);
        return error;
    }
    void clearNotification() {
        if(!notify_)return;
        // Remove queued notifications before Windows can reuse this HWND.
        MSG message{};
        while(PeekMessageW(&message,notify_,MM_MCINOTIFY,MM_MCINOTIFY,PM_REMOVE)) {}
        DestroyWindow(notify_);notify_=nullptr;
    }
    static LRESULT CALLBACK windowProc(HWND hwnd,UINT message,WPARAM wp,LPARAM lp) {
        auto self=reinterpret_cast<MciAudioBackend*>(GetWindowLongPtrW(hwnd,GWLP_USERDATA));
        if(message==WM_NCCREATE) {
            self=static_cast<MciAudioBackend*>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams);
            SetWindowLongPtrW(hwnd,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(self));
        }
        if(message==MM_MCINOTIFY && self && self->state_.opened &&
           static_cast<MCIDEVICEID>(lp)==mciGetDeviceIDW(self->alias_.c_str())) {
            if(wp==MCI_NOTIFY_SUCCESSFUL && self->state_.playing) {
                self->state_.playing=false;
                self->state_.positionMs=self->state_.durationMs;
                self->events_.push_back({AudioEventKind::Ended,self->state_.generation,{}});
            } else if(wp==MCI_NOTIFY_FAILURE) {
                self->state_.playing=false;
                self->events_.push_back({AudioEventKind::Error,self->state_.generation,
                    {AudioError::BackendFailure,MCIERR_DRIVER_INTERNAL}});
            }
            return 0;
        }
        return DefWindowProcW(hwnd,message,wp,lp);
    }
    bool notificationWindow() {
        clearNotification();
        WNDCLASSW wc{};wc.lpfnWndProc=windowProc;wc.hInstance=GetModuleHandleW(nullptr);
        wc.lpszClassName=L"MusxiMciNotifications";
        if(!RegisterClassW(&wc) && GetLastError()!=ERROR_CLASS_ALREADY_EXISTS)return false;
        notify_=CreateWindowExW(0,wc.lpszClassName,L"",0,0,0,0,0,HWND_MESSAGE,nullptr,wc.hInstance,this);
        return notify_!=nullptr;
    }
    MCIERROR applyVolume(int percent) {
        if(!state_.opened)return 0;
        IMMDeviceEnumerator* devices=nullptr;IMMDevice* device=nullptr;
        IAudioSessionManager2* manager=nullptr;IAudioSessionEnumerator* sessions=nullptr;
        bool applied=false;
        if(SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&devices)) &&
           SUCCEEDED(devices->GetDefaultAudioEndpoint(eRender,eMultimedia,&device)) &&
           SUCCEEDED(device->Activate(__uuidof(IAudioSessionManager2),CLSCTX_ALL,nullptr,(void**)&manager)) &&
           SUCCEEDED(manager->GetSessionEnumerator(&sessions))) {
            int count=0;sessions->GetCount(&count);
            for(int i=0;i<count;++i) {
                IAudioSessionControl* session=nullptr;IAudioSessionControl2* info=nullptr;ISimpleAudioVolume* volume=nullptr;DWORD pid=0;
                if(SUCCEEDED(sessions->GetSession(i,&session)) &&
                   SUCCEEDED(session->QueryInterface(__uuidof(IAudioSessionControl2),(void**)&info)) &&
                   SUCCEEDED(info->GetProcessId(&pid)) && pid==GetCurrentProcessId() &&
                   SUCCEEDED(session->QueryInterface(__uuidof(ISimpleAudioVolume),(void**)&volume))) {
                    if(SUCCEEDED(volume->SetMasterVolume(percent/100.0f,nullptr)))applied=true;
                }
                if(volume)volume->Release();
                if(info)info->Release();
                if(session)session->Release();
            }
        }
        if(sessions)sessions->Release();
        if(manager)manager->Release();
        if(device)device->Release();
        if(devices)devices->Release();
        return applied?0:MCIERR_UNSUPPORTED_FUNCTION;
    }
public:
    MciAudioBackend() {
        static std::atomic<unsigned> next{0};alias_=L"musxi_audio_"+std::to_wstring(++next);
    }
    ~MciAudioBackend() override {unload();clearNotification();}
    AudioResult load(const std::wstring& path) override {
        if(path.empty() || path.find(L'"')!=std::wstring::npos || path.find(L'\0')!=std::wstring::npos)
            return {AudioError::InvalidArgument};
        auto closed=unload();if(!closed)return closed;
        auto error=command(L"open \""+path+L"\" alias "+alias_);
        if(error)return result(error);
        state_.opened=true;
        error=command(L"set "+alias_+L" time format milliseconds");
        if(!error)error=number(L"length",state_.durationMs);
        if(error){unload();return result(error);}
        // Preserve MCI's existing best-effort volume application during open.
        applyVolume(state_.volumePercent);
        return {};
    }
    AudioResult play() override {return resume();}
    AudioResult resume() override {
        if(!state_.opened)return {AudioError::NotReady};
        if(state_.playing)return {};
        if(!notificationWindow())return {AudioError::BackendFailure,GetLastError()};
        auto error=command(L"play "+alias_+L" notify");
        if(!error){state_.playing=true;applyVolume(state_.volumePercent);}
        return result(error);
    }
    AudioResult pause() override {
        if(!state_.opened)return {AudioError::NotReady};
        if(!state_.playing)return {};
        auto error=command(L"pause "+alias_);
        if(!error){state_.playing=false;clearNotification();poll();}
        return result(error);
    }
    AudioResult stop() override {
        if(!state_.opened)return {AudioError::NotReady};
        auto error=command(L"stop "+alias_);
        if(error)return result(error);
        state_.playing=false;clearNotification();events_.clear();
        error=command(L"seek "+alias_+L" to start");poll();
        return result(error);
    }
    AudioResult seek(std::uint32_t value) override {
        if(!state_.opened || !state_.durationMs)return {AudioError::NotReady};
        const bool resumeAfter=state_.playing;
        auto error=command(L"seek "+alias_+L" to "+std::to_wstring(std::min(value,state_.durationMs-1)));
        if(error)return result(error);
        state_.playing=false;clearNotification();events_.clear();poll();
        return resumeAfter?resume():AudioResult{};
    }
    AudioResult setVolume(int percent) override {
        if(percent<0 || percent>100)return {AudioError::InvalidArgument};
        auto error=applyVolume(percent);if(!error)state_.volumePercent=percent;
        return result(error);
    }
    AudioResult unload() override {
        if(state_.opened) {
            auto error=command(L"close "+alias_);if(error)return result(error);
        }
        clearNotification();events_.clear();
        const auto generation=state_.generation+1;const auto volume=state_.volumePercent;
        state_={};state_.generation=generation;state_.volumePercent=volume;
        return {};
    }
    AudioState snapshot() const override {return state_;}
    void poll() override {
        if(!state_.opened)return;
        auto error=number(L"position",state_.positionMs);
        if(error && events_.empty())events_.push_back({AudioEventKind::Error,state_.generation,result(error)});
    }
    std::vector<AudioEvent> takeEvents() override {return std::exchange(events_,{});}
};
}
std::unique_ptr<IAudioBackend> makeMciAudioBackend() {return std::make_unique<MciAudioBackend>();}
}
