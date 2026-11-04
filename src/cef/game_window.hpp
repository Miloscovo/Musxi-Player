#pragma once
#include <windows.h>
#include <string>
#include <dwmapi.h>

namespace musxi::game_window {
inline bool candidate(HWND window) {
    if(!IsWindowVisible(window) || !IsWindowEnabled(window) || IsIconic(window) || GetAncestor(window,GA_ROOT)!=window
        || GetWindow(window,GW_OWNER) || window==GetShellWindow() || window==GetDesktopWindow()
        || (GetWindowLongPtrW(window,GWL_EXSTYLE)&WS_EX_TOOLWINDOW)
        || (GetWindowLongPtrW(window,GWL_STYLE)&WS_CAPTION)!=WS_CAPTION)return false;
    DWORD cloaked=0;
    if(FAILED(DwmGetWindowAttribute(window,DWMWA_CLOAKED,&cloaked,sizeof(cloaked))) || cloaked)return false;
    DWORD pid=0;GetWindowThreadProcessId(window,&pid);
    if(!pid || pid==GetCurrentProcessId())return false;
    const auto process=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,pid);if(!process)return false;
    wchar_t path[32768]{};DWORD length=32768;
    const bool read=QueryFullProcessImageNameW(process,0,path,&length)!=FALSE;CloseHandle(process);
    if(!read)return false;
    const auto slash=std::wstring(path).find_last_of(L"\\/");
    const auto file=path+(slash==std::wstring::npos?0:slash+1);
    return _wcsicmp(file,L"MusxiPlayerWeb.exe")!=0 && _wcsicmp(file,L"MusxiPlayerTest.exe")!=0;
}
inline bool sameRect(const RECT& a,const RECT& b) {
    return a.left==b.left && a.top==b.top && a.right==b.right && a.bottom==b.bottom;
}
class Window {
    static constexpr const wchar_t* property=L"MusxiGameWindowCompatibility";
    HWND window_;DWORD pid_=0;HANDLE process_=nullptr;
    LONG_PTR style_=0,appliedStyle_=0;
    RECT bounds_{};WINDOWPLACEMENT placement_{sizeof(WINDOWPLACEMENT)},applied_{sizeof(WINDOWPLACEMENT)};
    bool owned_=false,ready_=false,abandoned_=false,expectedReadable_=true;
    RECT recoveryBounds_{};bool recoveryBoundsReadable_=false;
    struct Dpi {
        DPI_AWARENESS_CONTEXT previous=SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
        ~Dpi(){if(previous)SetThreadDpiAwarenessContext(previous);}
    };
    bool identity() const {
        DWORD pid=0,exit=0;GetWindowThreadProcessId(window_,&pid);
        return IsWindow(window_) && pid==pid_ && process_ && GetExitCodeProcess(process_,&exit) && exit==STILL_ACTIVE
            && GetPropW(window_,property)==reinterpret_cast<HANDLE>(const_cast<Window*>(this));
    }
    void release() {
        if(identity())RemovePropW(window_,property);
        owned_=false;
    }
    void captureRecovery() {
        appliedStyle_=GetWindowLongPtrW(window_,GWL_STYLE);
        expectedReadable_=GetWindowPlacement(window_,&applied_)!=FALSE;
        recoveryBoundsReadable_=GetWindowRect(window_,&recoveryBounds_)!=FALSE;
    }
    bool originalLayout() {
        const auto current=GetWindowLongPtrW(window_,GWL_STYLE);
        const auto style=(style_&~(WS_MINIMIZE|WS_VISIBLE))|(current&(WS_MINIMIZE|WS_VISIBLE));
        if(!SetWindowLongPtrW(window_,GWL_STYLE,style))return false;
        auto placement=placement_;placement.showCmd=IsIconic(window_)?SW_SHOWMINNOACTIVE:SW_SHOWNA;
        if(!SetWindowPlacement(window_,&placement))return false;
        if(!IsIconic(window_) && !SetWindowPos(window_,nullptr,bounds_.left,bounds_.top,bounds_.right-bounds_.left,bounds_.bottom-bounds_.top,
            SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED))return false;
        return true;
    }
public:
    enum class Layout { same, changed, unavailable };
    explicit Window(HWND window):window_(window) {}
    Window(const Window&)=delete;Window& operator=(const Window&)=delete;
    ~Window(){if(owned_)restore();if(process_)CloseHandle(process_);}
    HWND handle() const {return window_;}
    bool alive() const {return owned_ && identity();}
    bool ready() const {return ready_;}
    bool abandoned() const {return abandoned_;}
    void respectUserChanges(){abandoned_=true;}
    Layout layout() const {
        Dpi dpi;
        if(!owned_ || !identity())return Layout::changed;
        const auto style=GetWindowLongPtrW(window_,GWL_STYLE);
        WINDOWPLACEMENT now{sizeof(now)};
        if(!GetWindowPlacement(window_,&now))return Layout::unavailable;
        if(!expectedReadable_) {
            RECT bounds{};
            if(!recoveryBoundsReadable_ || !GetWindowRect(window_,&bounds))return Layout::unavailable;
            return style==appliedStyle_ && sameRect(bounds,recoveryBounds_)?Layout::same:Layout::changed;
        }
        return (style&~(WS_MINIMIZE|WS_VISIBLE))==(appliedStyle_&~(WS_MINIMIZE|WS_VISIBLE))
            && sameRect(now.rcNormalPosition,applied_.rcNormalPosition)?Layout::same:Layout::changed;
    }
    bool unchanged() const {return layout()==Layout::same;}
    bool apply() {
        Dpi dpi;
        if(owned_ || IsIconic(window_) || GetPropW(window_,property))return false;
        style_=GetWindowLongPtrW(window_,GWL_STYLE);
        // A popup does not reveal the engine's full screen state; require a normal window first.
        if((style_&WS_CAPTION)!=WS_CAPTION || !GetWindowRect(window_,&bounds_) || !GetWindowPlacement(window_,&placement_))return false;
        GetWindowThreadProcessId(window_,&pid_);
        process_=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,pid_);if(!process_)return false;
        if(!SetPropW(window_,property,reinterpret_cast<HANDLE>(this)))return false;
        owned_=true;
        MONITORINFO monitor{sizeof(monitor)};
        if(!GetMonitorInfoW(MonitorFromWindow(window_,MONITOR_DEFAULTTONEAREST),&monitor)){release();return false;}
        appliedStyle_=(style_&~(WS_POPUP|WS_OVERLAPPEDWINDOW|WS_MINIMIZE|WS_MAXIMIZE))|WS_BORDER;
        RECT outer=monitor.rcMonitor;
        const auto extended=static_cast<DWORD>(GetWindowLongPtrW(window_,GWL_EXSTYLE));
        if(!AdjustWindowRectExForDpi(&outer,static_cast<DWORD>(appliedStyle_),GetMenu(window_)!=nullptr,extended,GetDpiForWindow(window_))){release();return false;}
        bool ok=SetWindowLongPtrW(window_,GWL_STYLE,appliedStyle_)!=0;
        if(ok)ok=SetWindowPos(window_,nullptr,outer.left,outer.top,outer.right-outer.left,outer.bottom-outer.top,
            SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED)!=FALSE;
        RECT client{};POINT origin{};
        if(ok)ok=GetClientRect(window_,&client) && ClientToScreen(window_,&origin)
            && origin.x==monitor.rcMonitor.left && origin.y==monitor.rcMonitor.top
            && client.right==monitor.rcMonitor.right-monitor.rcMonitor.left && client.bottom==monitor.rcMonitor.bottom-monitor.rcMonitor.top
            && GetWindowPlacement(window_,&applied_);
        if(!ok){
            if(originalLayout())release();
            else captureRecovery();
        }
        ready_=ok;return ok;
    }
    bool restore() {
        Dpi dpi;
        if(!owned_)return true;
        if(abandoned_ || !identity()){release();return true;}
        const auto current=layout();
        if(current==Layout::unavailable)return false;
        if(current==Layout::changed){release();return true;} // Respect a later manual resize or F11 change.
        if(!originalLayout()) {
            captureRecovery();
            ready_=false;return false;
        }
        release();return true;
    }
};
}
