#include "bridge.hpp"
#include "include/cef_sandbox_win.h"
#include "include/cef_command_line.h"
#include "include/cef_parser.h"
#include "../application/application.hpp"
#include "../updates/fixture.hpp"
#include <cstdio>
#include <commctrl.h>
#include <filesystem>
#include <shlwapi.h>
#include <shlobj.h>
#include <shellapi.h>
#include <algorithm>
#include <windowsx.h>
#include <string>
#include <imm.h>
#include "composition_surface.hpp"
#include "../../third_party/json.hpp"
#include <fstream>
#include <climits>
#include <map>
#include <vector>
#include "shortcuts.hpp"
#include "wallpaper_store.hpp"

namespace {
std::wstring pagePath;
HWND nativeWindow=nullptr;
HWND testWindow=nullptr;
HWND recoveryText=nullptr,retryButton=nullptr,exitButton=nullptr;
bool smoke=false,createFailed=false,testApp=false,preview=false,hostClosing=false,desktopSmoke=false;
COLORREF frameColor=RGB(255,255,255);
ULONGLONG startTick=0;
CompositionSurface surface;
RECT resizeView{};
HCURSOR webCursor=nullptr;
bool mouseTracked=false,imeActive=false,releasingCapture=false,renderFailed=false;
constexpr UINT TrayMessage=WM_APP+44;
constexpr UINT ResizeBrowserMessage=WM_APP+45;
bool resizeBrowserPending=false;
void scheduleBrowserResize(HWND hwnd) {
    if(!resizeBrowserPending)resizeBrowserPending=PostMessageW(hwnd,ResizeBrowserMessage,0,0)!=FALSE;
}
const UINT TaskbarCreated=RegisterWindowMessageW(L"TaskbarCreated");
bool trayAdded=false;
HICON trayIcon=nullptr;
HWND trayWindow=nullptr;
std::string mainUrl;
std::string updatesUiSmoke;
NOTIFYICONDATAW trayData(HWND hwnd) {
    NOTIFYICONDATAW data{};data.cbSize=sizeof(data);data.hWnd=hwnd;data.uID=1;
    data.uFlags=NIF_MESSAGE|NIF_ICON|NIF_TIP|NIF_SHOWTIP;
    data.uCallbackMessage=TrayMessage;data.hIcon=trayIcon;
    wcscpy_s(data.szTip,L"Musxi Player");return data;
}
bool addTray(HWND hwnd) {
    if(trayAdded)return true;
    if(!trayIcon) {
        wchar_t exe[32768];GetModuleFileNameW(nullptr,exe,32768);
        ExtractIconExW(exe,0,nullptr,&trayIcon,1);
        if(!trayIcon)trayIcon=CopyIcon(LoadIconW(nullptr,IDI_APPLICATION));
    }
    auto data=trayData(hwnd);
    if(!Shell_NotifyIconW(NIM_ADD,&data))return false;
    data.uVersion=NOTIFYICON_VERSION_4;Shell_NotifyIconW(NIM_SETVERSION,&data);
    trayAdded=true;return true;
}
void removeTray(HWND hwnd) {
    if(trayAdded){auto data=trayData(hwnd);Shell_NotifyIconW(NIM_DELETE,&data);trayAdded=false;}
    if(trayIcon){DestroyIcon(trayIcon);trayIcon=nullptr;}
}
void showFromTray(HWND hwnd) {
    ShowWindow(hwnd,IsIconic(hwnd)?SW_RESTORE:SW_SHOW);
    if(auto browser=musxi::cef_adapter::browserHost()){browser->WasHidden(false);scheduleBrowserResize(hwnd);}
    SetForegroundWindow(hwnd);
}
bool hideToTray(HWND hwnd) {
    // Never hide the only reachable window if the notification icon cannot be added.
    if(!addTray(hwnd))return false;
    KillTimer(hwnd,102);
    if(auto browser=musxi::cef_adapter::browserHost())browser->WasHidden(true);
    ShowWindow(hwnd,SW_HIDE);
    return true;
}
void exitFromTray() {
    if(nativeWindow)PostMessageW(nativeWindow,WM_CLOSE,0,0);
}
LRESULT CALLBACK trayProc(HWND hwnd,UINT message,WPARAM wp,LPARAM lp) {
    if(message==WM_CLOSE && musxi::cef_adapter::trayBrowserReadyToClose()) {DestroyWindow(hwnd);return 0;}
    if(message==WM_CLOSE || (message==WM_ACTIVATE && LOWORD(wp)==WA_INACTIVE && IsWindowVisible(hwnd))) {
        ShowWindow(hwnd,SW_HIDE);musxi::cef_adapter::closeTrayBrowser();return 0;
    }
    return DefWindowProcW(hwnd,message,wp,lp);
}
void trayMenu(HWND) {
    if(trayWindow){SetForegroundWindow(trayWindow);return;}
    POINT p{};GetCursorPos(&p);MONITORINFO monitor{sizeof(monitor)};
    GetMonitorInfoW(MonitorFromPoint(p,MONITOR_DEFAULTTONEAREST),&monitor);
    // Match the shadcn menu itself, without a visible backing-window margin.
    const int dpi=GetDpiForWindow(testWindow);
    const int width=MulDiv(208,dpi,96),height=MulDiv(156,dpi,96);
    p.x=std::clamp(p.x,monitor.rcWork.left,monitor.rcWork.right-width);
    p.y=std::clamp(p.y-height-MulDiv(4,dpi,96),monitor.rcWork.top,monitor.rcWork.bottom-height);
    WNDCLASSW wc{};wc.lpfnWndProc=trayProc;wc.hInstance=GetModuleHandleW(nullptr);
    wc.lpszClassName=L"MusxiTrayMenu";wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);RegisterClassW(&wc);
    trayWindow=CreateWindowExW(WS_EX_TOOLWINDOW|WS_EX_TOPMOST,wc.lpszClassName,L"Musxi Player",WS_POPUP|WS_CLIPCHILDREN,
        p.x,p.y,width,height,nullptr,nullptr,wc.hInstance,nullptr);
    if(!trayWindow)return;
    const int corner=MulDiv(20,dpi,96);
    auto region=CreateRoundRectRgn(0,0,width+1,height+1,corner,corner);
    if(region && !SetWindowRgn(trayWindow,region,FALSE))DeleteObject(region);
    CefWindowInfo info;info.SetAsChild(trayWindow,CefRect(0,0,width,height));info.runtime_style=CEF_RUNTIME_STYLE_ALLOY;
    CefBrowserSettings settings;settings.background_color=CefColorSetARGB(255,24,24,24);
    const auto url=mainUrl.substr(0,mainUrl.find('?'))+"?tray=1";
    auto browser=CefBrowserHost::CreateBrowserSync(info,musxi::cef_adapter::makeTrayClient(url,nativeWindow),url,settings,nullptr,nullptr);
    if(!browser){DestroyWindow(trayWindow);trayWindow=nullptr;return;}
    ShowWindow(trayWindow,SW_SHOW);SetForegroundWindow(trayWindow);
}
int modifiers() {
    int flags=0;
    if(GetKeyState(VK_SHIFT)&0x8000)flags|=EVENTFLAG_SHIFT_DOWN;
    if(GetKeyState(VK_CONTROL)&0x8000)flags|=EVENTFLAG_CONTROL_DOWN;
    if(GetKeyState(VK_MENU)&0x8000)flags|=EVENTFLAG_ALT_DOWN;
    if(GetKeyState(VK_LBUTTON)&0x8000)flags|=EVENTFLAG_LEFT_MOUSE_BUTTON;
    if(GetKeyState(VK_RBUTTON)&0x8000)flags|=EVENTFLAG_RIGHT_MOUSE_BUTTON;
    if(GetKeyState(VK_MBUTTON)&0x8000)flags|=EVENTFLAG_MIDDLE_MOUSE_BUTTON;
    if(GetKeyState(VK_CAPITAL)&1)flags|=EVENTFLAG_CAPS_LOCK_ON;
    if(GetKeyState(VK_NUMLOCK)&1)flags|=EVENTFLAG_NUM_LOCK_ON;
    return flags;
}
CefMouseEvent mouseEvent(HWND hwnd,LPARAM lp,bool screen=false) {
    POINT p{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};if(screen)ScreenToClient(hwnd,&p);
    const int dpi=GetDpiForWindow(hwnd);CefMouseEvent event;
    event.x=MulDiv(p.x,96,dpi);event.y=MulDiv(p.y,96,dpi);event.modifiers=modifiers();return event;
}
std::wstring imeText(HIMC context,DWORD type) {
    const LONG bytes=ImmGetCompositionStringW(context,type,nullptr,0);
    if(bytes<=0)return {};
    std::wstring text(static_cast<size_t>(bytes)/sizeof(wchar_t),L'\0');
    ImmGetCompositionStringW(context,type,text.data(),bytes);return text;
}
LRESULT hostHitTest(HWND hwnd,LPARAM lp) {
    RECT r;GetWindowRect(hwnd,&r);
    const int edge=IsZoomed(hwnd)?0:MulDiv(7,GetDpiForWindow(hwnd),96);
    const int x=GET_X_LPARAM(lp),y=GET_Y_LPARAM(lp);
    const bool l=x<r.left+edge,rr=x>=r.right-edge,t=y<r.top+edge,b=y>=r.bottom-edge;
    if(t)return l?HTTOPLEFT:rr?HTTOPRIGHT:HTTOP;
    if(b)return l?HTBOTTOMLEFT:rr?HTBOTTOMRIGHT:HTBOTTOM;
    if(l)return HTLEFT;if(rr)return HTRIGHT;
    POINT p{x,y};ScreenToClient(hwnd,&p);
    auto region=static_cast<HRGN>(GetPropW(hwnd,musxi::cef_adapter::DragRegionProperty));
    return region && PtInRegion(region,p.x,p.y)?HTCAPTION:HTCLIENT;
}
#include "desktop_lyrics.inc"
#include "shortcuts.inc"
#include "wallpaper.inc"
// Main window placement, remembered across launches in the native profile.
std::filesystem::path mainSettingsPath;
RECT mainBounds{};bool mainMaximized=false;
bool mainCompact=false;
int compactHeight=130;
WINDOWPLACEMENT expandedPlacement{sizeof(WINDOWPLACEMENT)};
POINT expandedWorkOffset{};
void loadMainWindow(const std::filesystem::path& profile) {
    mainSettingsPath=profile/L"window.json";
    if(smoke)return;
    try {
        if(!std::filesystem::is_regular_file(mainSettingsPath) || std::filesystem::file_size(mainSettingsPath)>1024)return;
        std::ifstream file(mainSettingsPath,std::ios::binary);const auto data=DesktopJson::parse(file);
        if(data.at("version")!=1)return;
        const auto x=data.at("x").get<std::int64_t>(),y=data.at("y").get<std::int64_t>();
        const auto width=data.at("width").get<std::int64_t>(),height=data.at("height").get<std::int64_t>();
        if(x < -1000000 || x > 1000000 || y < -1000000 || y > 1000000 || width<1 || width>100000 || height<1 || height>100000)return;
        mainBounds={static_cast<LONG>(x),static_cast<LONG>(y),static_cast<LONG>(x+width),static_cast<LONG>(y+height)};
        mainMaximized=data.at("maximized").get<bool>();
    }catch(...) {mainBounds={};mainMaximized=false;}
}
void saveMainWindow() {
    if(smoke || !testWindow || mainSettingsPath.empty())return;
    WINDOWPLACEMENT placement{sizeof(placement)};
    if(!GetWindowPlacement(testWindow,&placement))return;
    if(mainCompact)placement=expandedPlacement;
    // rcNormalPosition is in workspace coordinates; store screen coordinates.
    MONITORINFO monitor{sizeof(monitor)};GetMonitorInfoW(MonitorFromWindow(testWindow,MONITOR_DEFAULTTONEAREST),&monitor);
    auto r=placement.rcNormalPosition;
    OffsetRect(&r,mainCompact?expandedWorkOffset.x:monitor.rcWork.left-monitor.rcMonitor.left,
        mainCompact?expandedWorkOffset.y:monitor.rcWork.top-monitor.rcMonitor.top);
    try {
        const auto data=DesktopJson{{"version",1},{"x",r.left},{"y",r.top},{"width",r.right-r.left},{"height",r.bottom-r.top},
            {"maximized",placement.showCmd==SW_SHOWMAXIMIZED || (!mainCompact && IsZoomed(testWindow))}}.dump();
        auto temporary=mainSettingsPath;temporary+=L".tmp";
        {std::ofstream file(temporary,std::ios::binary|std::ios::trunc);file<<data;file.close();if(!file)return;}
        MoveFileExW(temporary.c_str(),mainSettingsPath.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);
    }catch(...) {}
}
// A saved placement is clamped to the nearest work area. Otherwise the default
// is scaled for the monitor's DPI: physical 1360x850 is only ~567 DIP tall at 150%.
RECT initialMainBounds() {
    const bool saved=mainBounds.right-mainBounds.left>0 && mainBounds.bottom-mainBounds.top>0;
    POINT anchor{};if(saved)anchor={mainBounds.left,mainBounds.top};else GetCursorPos(&anchor);
    const auto handle=saved?MonitorFromRect(&mainBounds,MONITOR_DEFAULTTONEAREST):MonitorFromPoint(anchor,MONITOR_DEFAULTTOPRIMARY);
    MONITORINFO monitor{sizeof(monitor)};if(!GetMonitorInfoW(handle,&monitor))return {CW_USEDEFAULT,CW_USEDEFAULT,1360,850};
    // Per-monitor DPI without linking Shcore: the process is per-monitor aware,
    // so the system DPI is a close start and WM_DPICHANGED fixes the rest.
    const UINT dpiX=GetDpiForSystem();
    const auto& work=monitor.rcWork;const int availableWidth=work.right-work.left,availableHeight=work.bottom-work.top;
    const int minWidth=MulDiv(800,dpiX,96),minHeight=MulDiv(560,dpiX,96);
    int width=saved?mainBounds.right-mainBounds.left:std::min(MulDiv(1440,dpiX,96),availableWidth*4/5);
    int height=saved?mainBounds.bottom-mainBounds.top:std::min(MulDiv(940,dpiX,96),availableHeight*22/25);
    width=std::min(std::max(width,minWidth),availableWidth);height=std::min(std::max(height,minHeight),availableHeight);
    const int x=saved?std::clamp<int>(mainBounds.left,work.left,work.right-width):work.left+(availableWidth-width)/2;
    const int y=saved?std::clamp<int>(mainBounds.top,work.top,work.bottom-height):work.top+(availableHeight-height)/2;
    return {x,y,x+width,y+height};
}
LRESULT CALLBACK testProc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp) {
    const auto browser=musxi::cef_adapter::browserHost();
    if(msg==TaskbarCreated && trayAdded) {
        trayAdded=false;
        if(!addTray(hwnd)){lockDesktopLyrics(false);saveDesktopSettings();publishDesktop();showFromTray(hwnd);}
        return 0;
    }
    switch(msg) {
    case TrayMessage:
        if(LOWORD(lp)==WM_LBUTTONDBLCLK || LOWORD(lp)==NIN_KEYSELECT)showFromTray(hwnd);
        if(LOWORD(lp)==WM_CONTEXTMENU || LOWORD(lp)==WM_RBUTTONUP)trayMenu(hwnd);
        return 0;
    case WM_DESTROY: shortcutRegistry.clear();removeTray(hwnd);return 0;
    case WM_HOTKEY: {
        const auto index=static_cast<int>(wp)-0x5100;
        if(index>=0 && index<10 && shortcutRegistry.registered[index] && !shortcutRegistry.suspended) {
            const auto binding=shortcutRegistry.settings.bindings[index];
            if(HIWORD(lp)==binding.key && LOWORD(lp)==binding.modifiers)executeShortcut(musxi::shortcuts::actions[index]);
        }
        return 0;
    }
    case WM_SETFOCUS: if(browser)browser->SetFocus(true);return 0;
    case WM_KILLFOCUS: if(browser){browser->SetFocus(false);if(imeActive)browser->ImeCancelComposition();}imeActive=false;return 0;
    case WM_SETCURSOR: if(LOWORD(lp)==HTCLIENT){SetCursor(webCursor?webCursor:LoadCursorW(nullptr,IDC_ARROW));return TRUE;}break;
    case WM_MOUSEMOVE:
        if(browser) {
            if(!mouseTracked){TRACKMOUSEEVENT track{sizeof(track),TME_LEAVE,hwnd,0};TrackMouseEvent(&track);mouseTracked=true;}
            browser->SendMouseMoveEvent(mouseEvent(hwnd,lp),false);
        }return 0;
    case WM_MOUSELEAVE: mouseTracked=false;if(browser)browser->SendMouseMoveEvent(mouseEvent(hwnd,0),true);return 0;
    case WM_LBUTTONDOWN: case WM_LBUTTONUP: case WM_LBUTTONDBLCLK:
    case WM_RBUTTONDOWN: case WM_RBUTTONUP: case WM_RBUTTONDBLCLK:
    case WM_MBUTTONDOWN: case WM_MBUTTONUP: case WM_MBUTTONDBLCLK:
        if(browser) {
            const bool up=msg==WM_LBUTTONUP || msg==WM_RBUTTONUP || msg==WM_MBUTTONUP;
            const auto button=(msg==WM_RBUTTONDOWN || msg==WM_RBUTTONUP || msg==WM_RBUTTONDBLCLK)?MBT_RIGHT:
                (msg==WM_MBUTTONDOWN || msg==WM_MBUTTONUP || msg==WM_MBUTTONDBLCLK)?MBT_MIDDLE:MBT_LEFT;
            static int clickCount=1;
            if(!up) {SetFocus(hwnd);SetCapture(hwnd);clickCount=(msg==WM_LBUTTONDBLCLK || msg==WM_RBUTTONDBLCLK || msg==WM_MBUTTONDBLCLK)?2:1;}
            browser->SendMouseClickEvent(mouseEvent(hwnd,lp),button,up,clickCount);
            if(up && !(GetKeyState(VK_LBUTTON)&0x8000) && !(GetKeyState(VK_RBUTTON)&0x8000) && !(GetKeyState(VK_MBUTTON)&0x8000)) {
                releasingCapture=true;ReleaseCapture();releasingCapture=false;
            }
        }return 0;
    case WM_CAPTURECHANGED: if(browser && !releasingCapture)browser->SendCaptureLostEvent();return 0;
    case WM_MOUSEWHEEL: case WM_MOUSEHWHEEL:
        if(browser) {const auto event=mouseEvent(hwnd,lp,true);const int delta=GET_WHEEL_DELTA_WPARAM(wp);
            browser->SendMouseWheelEvent(event,msg==WM_MOUSEHWHEEL?delta:0,msg==WM_MOUSEWHEEL?delta:0);}
        return 0;
    case WM_KEYDOWN: case WM_SYSKEYDOWN: case WM_KEYUP: case WM_SYSKEYUP: case WM_CHAR: case WM_SYSCHAR:
        if(browser) {
            CefKeyEvent key;key.windows_key_code=static_cast<int>(wp);key.native_key_code=static_cast<int>(lp);key.modifiers=modifiers();
            key.is_system_key=msg==WM_SYSKEYDOWN || msg==WM_SYSKEYUP || msg==WM_SYSCHAR;
            key.type=(msg==WM_CHAR || msg==WM_SYSCHAR)?KEYEVENT_CHAR:(msg==WM_KEYUP || msg==WM_SYSKEYUP)?KEYEVENT_KEYUP:KEYEVENT_RAWKEYDOWN;
            if(key.type==KEYEVENT_CHAR)key.character=key.unmodified_character=static_cast<char16_t>(wp);
            if(wp==VK_SHIFT || wp==VK_CONTROL || wp==VK_MENU) {
                const bool right=wp==VK_SHIFT?MapVirtualKeyW((lp>>16)&255,MAPVK_VSC_TO_VK_EX)==VK_RSHIFT:(lp&(1<<24))!=0;
                key.modifiers|=right?EVENTFLAG_IS_RIGHT:EVENTFLAG_IS_LEFT;
            }
            if((wp>=VK_NUMPAD0 && wp<=VK_DIVIDE) || (wp==VK_RETURN && (lp&(1<<24))))key.modifiers|=EVENTFLAG_IS_KEY_PAD;
            browser->SendKeyEvent(key);
        }
        if(msg==WM_SYSKEYDOWN && wp==VK_F4)break;
        return 0;
    case WM_IME_SETCONTEXT: lp&=~ISC_SHOWUICOMPOSITIONWINDOW;break;
    case WM_IME_STARTCOMPOSITION: imeActive=true;return 0;
    case WM_IME_ENDCOMPOSITION: if(browser && imeActive)browser->ImeCancelComposition();imeActive=false;return 0;
    case WM_IME_CHAR: return 0; // The result string is committed below, once.
    case WM_IME_COMPOSITION:
        if(browser) {
            const auto context=ImmGetContext(hwnd);
            if(context) {
                if(lp&GCS_RESULTSTR){browser->ImeCommitText(imeText(context,GCS_RESULTSTR),CefRange(UINT32_MAX,UINT32_MAX),0);imeActive=false;}
                if(lp&GCS_COMPSTR) {
                    const auto text=imeText(context,GCS_COMPSTR);const LONG caret=ImmGetCompositionStringW(context,GCS_CURSORPOS,nullptr,0);
                    CefCompositionUnderline underline;underline.range=CefRange(0,static_cast<uint32_t>(text.size()));underline.color=CefColorSetARGB(255,100,100,100);
                    browser->ImeSetComposition(text,{underline},CefRange(UINT32_MAX,UINT32_MAX),CefRange(std::max<LONG>(0,caret),std::max<LONG>(0,caret)));imeActive=true;
                }
                ImmReleaseContext(hwnd,context);
            }
        }return 0;
    case WM_MOVE: if(browser)browser->NotifyMoveOrResizeStarted();break;
    case WM_ENTERSIZEMOVE: {
        KillTimer(hwnd,102);
        GetClientRect(hwnd,&resizeView);const int dpi=GetDpiForWindow(hwnd);
        resizeView.right=std::max(1,MulDiv(resizeView.right,96,dpi));
        resizeView.bottom=std::max(1,MulDiv(resizeView.bottom,96,dpi));
        SetPropW(hwnd,musxi::cef_adapter::ResizeViewProperty,&resizeView);
        surface.setResizing(true);SetTimer(hwnd,101,16,nullptr);return 0;
    }
    case WM_EXITSIZEMOVE:
        RemovePropW(hwnd,musxi::cef_adapter::ResizeViewProperty);
        surface.setResizing(false);KillTimer(hwnd,101);
        scheduleBrowserResize(hwnd);return 0;
    case WM_TIMER:
        if(wp==101){CefDoMessageLoopWork();return 0;}
        if(wp==102){if(browser)browser->Invalidate(PET_VIEW);else KillTimer(hwnd,102);return 0;}
        break;
    case WM_ERASEBKGND: {if(GetWindowLongPtrW(hwnd,GWL_EXSTYLE)&WS_EX_NOREDIRECTIONBITMAP)return 1;RECT r;GetClientRect(hwnd,&r);auto brush=CreateSolidBrush(frameColor);FillRect(reinterpret_cast<HDC>(wp),&r,brush);DeleteObject(brush);return 1;}
    case WM_APP+43: frameColor=wp?RGB(16,18,20):RGB(255,255,255);InvalidateRect(hwnd,nullptr,TRUE);return 0;
    case WM_NCCALCSIZE: return 0;
    case WM_NCPAINT: return 0;
    case WM_NCHITTEST: return hostHitTest(hwnd,lp);
    case WM_NCLBUTTONDBLCLK: if(mainCompact && wp==HTCAPTION)return 0;break;
    case WM_SYSCOMMAND:
        if(mainCompact && (wp&0xfff0)==SC_MAXIMIZE)return 0;
        if((wp&0xfff0)==SC_SIZE || (wp&0xfff0)==SC_MOVE) {
            // Let Chromium complete resize frames inside Windows' sizing loop.
            CefScopedSetNestableTasksAllowed allowTasks;
            return DefWindowProcW(hwnd,msg,wp,lp);
        }
        break;
    case WM_GETMINMAXINFO: {
        auto m=reinterpret_cast<MINMAXINFO*>(lp);
        const auto height=MulDiv(mainCompact?compactHeight:560,GetDpiForWindow(hwnd),96);
        m->ptMinTrackSize={MulDiv(800,GetDpiForWindow(hwnd),96),height};
        m->ptMaxTrackSize.y=mainCompact?height:GetSystemMetricsForDpi(SM_CYMAXTRACK,GetDpiForWindow(hwnd));
        MONITORINFO info{sizeof(info)};
        if(GetMonitorInfoW(MonitorFromWindow(hwnd,MONITOR_DEFAULTTONEAREST),&info)) {
            m->ptMaxPosition={info.rcWork.left-info.rcMonitor.left,info.rcWork.top-info.rcMonitor.top};
            m->ptMaxSize={info.rcWork.right-info.rcWork.left,info.rcWork.bottom-info.rcWork.top};
        }return 0;
    }
    case WM_DPICHANGED: {auto r=reinterpret_cast<RECT*>(lp);SetWindowPos(hwnd,nullptr,r->left,r->top,r->right-r->left,mainCompact?MulDiv(compactHeight,HIWORD(wp),96):r->bottom-r->top,SWP_NOZORDER);if(browser){browser->NotifyScreenInfoChanged();browser->WasResized();}return 0;}
    case WM_SIZE: {
        if(wp==SIZE_MINIMIZED)KillTimer(hwnd,102);
        if(browser) {
            browser->WasHidden(wp==SIZE_MINIMIZED);
            if(wp!=SIZE_MINIMIZED) {
                if(GetPropW(hwnd,musxi::cef_adapter::ResizeViewProperty)) {
                    if(!surface.present(hwnd))PostMessageW(hwnd,musxi::cef_adapter::RecoveryFailed,0,0);
                } else {
                    scheduleBrowserResize(hwnd);
                }
            }
        }
        return 0;
    }
    case ResizeBrowserMessage:
        resizeBrowserPending=false;
        // Query the final bounds after synchronous restore/compact window changes finish.
        if(browser && !IsIconic(hwnd) && !GetPropW(hwnd,musxi::cef_adapter::ResizeViewProperty)) {
            // Keep requesting frames until CEF acknowledges the final size, releasing its resize hold.
            SetTimer(hwnd,102,50,nullptr);
            browser->WasResized();browser->Invalidate(PET_VIEW);
        }
        return 0;
    case musxi::cef_adapter::RecoveryFailed:
        KillTimer(hwnd,102);
        surface.clear();
        SetWindowLongPtrW(hwnd,GWL_EXSTYLE,GetWindowLongPtrW(hwnd,GWL_EXSTYLE)&~WS_EX_NOREDIRECTIONBITMAP);
        InvalidateRect(hwnd,nullptr,TRUE);
        if(!recoveryText) {
            const auto instance=GetModuleHandleW(nullptr);
            recoveryText=CreateWindowW(L"STATIC",L"界面恢复失败，后台播放不受影响。",WS_CHILD|WS_VISIBLE,40,80,600,40,hwnd,nullptr,instance,nullptr);
            retryButton=CreateWindowW(L"BUTTON",L"重试",WS_CHILD|WS_VISIBLE|WS_TABSTOP,40,140,140,44,hwnd,reinterpret_cast<HMENU>(1),instance,nullptr);
            exitButton=CreateWindowW(L"BUTTON",L"关闭播放器",WS_CHILD|WS_VISIBLE|WS_TABSTOP,200,140,140,44,hwnd,reinterpret_cast<HMENU>(2),instance,nullptr);
        }
        ShowWindow(recoveryText,SW_SHOW);ShowWindow(retryButton,SW_SHOW);ShowWindow(exitButton,SW_SHOW);SetFocus(retryButton);return 0;
    case WM_COMMAND:
        if(LOWORD(wp)==1) {
            renderFailed=false;
            ShowWindow(recoveryText,SW_HIDE);ShowWindow(retryButton,SW_HIDE);ShowWindow(exitButton,SW_HIDE);
            SetWindowLongPtrW(hwnd,GWL_EXSTYLE,GetWindowLongPtrW(hwnd,GWL_EXSTYLE)|WS_EX_NOREDIRECTIONBITMAP);
            musxi::cef_adapter::retryBrowser();return 0;
        }
        if(LOWORD(wp)==2)exitFromTray();return 0;
    case WM_CLOSE:
        if(musxi::cef_adapter::browserReadyToClose())DestroyWindow(hwnd);
        else if(nativeWindow)PostMessageW(nativeWindow,WM_CLOSE,0,0);
        return 0;
    }
    return DefWindowProcW(hwnd,msg,wp,lp);
}
void ready(void* window) {
    nativeWindow=static_cast<HWND>(window);
    wchar_t buffer[32768];DWORD size=32768;
    if(FAILED(UrlCreateFromPathW(pagePath.c_str(),buffer,&size,0))) {
        createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);return;
    }
    // Match Chromium's UTF-8 escaping before using an exact navigation/IPC allowlist.
    CefURLParts parts;
    const auto query=!updatesUiSmoke.empty()?"?smoke=1&updatesSmoke="+updatesUiSmoke:
        desktopSmoke?"?smoke=1&desktopSmoke=1":smoke?"?smoke=1":"";
    if(!CefParseURL(CefString(buffer).ToString()+query,parts)) {
        createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);return;
    }
    const auto url=CefString(&parts.spec).ToString();
    mainUrl=url;
    CefWindowInfo info;
    if(!preview) {
        WNDCLASSW wc{};wc.style=CS_DBLCLKS;wc.lpfnWndProc=testProc;wc.hInstance=GetModuleHandleW(nullptr);
        wc.hIcon=LoadIconW(wc.hInstance,IDI_APPLICATION);
        wc.lpszClassName=L"MusxiPlayerTestHost";wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);
        wc.hbrBackground=nullptr;RegisterClassW(&wc);
        const auto bounds=initialMainBounds();
        testWindow=CreateWindowExW(WS_EX_NOREDIRECTIONBITMAP,wc.lpszClassName,L"Musxi Player 测试版",WS_POPUP|WS_THICKFRAME|WS_MINIMIZEBOX|WS_MAXIMIZEBOX|WS_SYSMENU|WS_CLIPCHILDREN,
            bounds.left,bounds.top,bounds.right-bounds.left,bounds.bottom-bounds.top,nullptr,nullptr,wc.hInstance,nullptr);
        if(!testWindow){createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);return;}
        SendMessageW(testWindow,WM_SETICON,ICON_BIG,reinterpret_cast<LPARAM>(wc.hIcon));
        SendMessageW(testWindow,WM_SETICON,ICON_SMALL,reinterpret_cast<LPARAM>(LoadImageW(wc.hInstance,IDI_APPLICATION,
            IMAGE_ICON,GetSystemMetrics(SM_CXSMICON),GetSystemMetrics(SM_CYSMICON),LR_SHARED)));
        SetWindowPos(testWindow,nullptr,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOZORDER|SWP_FRAMECHANGED);
        info.SetAsWindowless(testWindow);
    } else info.SetAsPopup(nullptr,"Musxi Player - CEF Preview");
    info.runtime_style=CEF_RUNTIME_STYLE_ALLOY;
    if(preview)info.bounds=CefRect(160,120,960,680);
    CefBrowserSettings settings;
    if(!preview){settings.background_color=0;settings.windowless_frame_rate=60;}
    auto browser=CefBrowserHost::CreateBrowserSync(info,
        musxi::cef_adapter::makeClient(url,window,smoke,testWindow),url,settings,nullptr,nullptr);
    if(!browser) {createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);}
    else if(smoke && preview)ShowWindow(browser->GetHost()->GetWindowHandle(),SW_HIDE);
    if(browser && !preview) {
        shortcutRegistry.restore();
        SendMessageW(testWindow,WM_SIZE,0,0);
        if(!smoke) {
            ShowWindow(testWindow,mainMaximized?SW_SHOWMAXIMIZED:SW_SHOW);
            addTray(testWindow);
        }
    }
    startTick=GetTickCount64();
    if(browser && !preview && desktopVisible && !showDesktopLyrics())desktopVisible=false;
}
void tick() {
    if(testWindow)refreshShortcutSuspension();
    CefDoMessageLoopWork();
    if(smoke && GetTickCount64()-startTick>(updatesUiSmoke.empty()?30000:90000))PostMessageW(nativeWindow,WM_CLOSE,0,0);
}
bool canClose() {
    if(!hostClosing){shortcutRegistry.clear();saveDesktopSettings();saveMainWindow();hostClosing=true;}
    if(!musxi::cef_adapter::closeBrowsers())return false;
    // The windowless browser is gone. Remove its frozen surface before blocking
    // native cleanup and CefShutdown, rather than leaving a dead window visible.
    if(testWindow) {surface.clear();DestroyWindow(testWindow);testWindow=nullptr;}
    return true;
}
int run(HINSTANCE instance,int show,void* sandbox) {
    auto app=musxi::cef_adapter::makeApp();
    const int child=CefExecuteProcess(CefMainArgs(instance),app,sandbox);
    if(child>=0)return child;
    wchar_t exe[32768];GetModuleFileNameW(nullptr,exe,32768);
    const auto directory=std::filesystem::path(exe).parent_path();
    auto command=CefCommandLine::CreateCommandLine();command->InitFromString(GetCommandLineW());
    std::shared_ptr<musxi::updates::Fixture> updateFixture;
    if(command->HasSwitch("update-fixture")) {
        try {
            updateFixture=musxi::updates::Fixture::load(std::filesystem::path(command->GetSwitchValue("update-fixture").ToWString()));
        }catch(const std::exception& error) {
            const auto message=std::string("Musxi update fixture rejected: ")+error.what()+"\n";
            OutputDebugStringA(message.c_str());std::fputs(message.c_str(),stderr);
            return 2; // Never fall back to the real update source after a fixture error.
        }
    }
    if(command->HasSwitch("cef-updates-ui-smoke")) {
        // This selector only launches real UI assertions with validated local input.
        updatesUiSmoke=command->GetSwitchValue("cef-updates-ui-smoke").ToString();
        static const std::vector<std::string> scenarios{"available","current","empty","missing","development","checking","failed","stale","changed",
            "download","download-network","download-checksum","download-install-failed","download-changed"};
        if(!updateFixture || !command->HasSwitch("cef-smoke") ||
            std::find(scenarios.begin(),scenarios.end(),updatesUiSmoke)==scenarios.end() ||
            command->HasSwitch("cef-preview") || command->HasSwitch("cef-updates-smoke") ||
            command->HasSwitch("cef-desktop-smoke") || command->HasSwitch("cef-smoke-unicode"))return 2;
    }
    testApp=command->HasSwitch("test-app") || std::filesystem::path(exe).filename()==L"MusxiPlayerTest.exe";
    preview=command->HasSwitch("cef-preview");
    if(!preview)SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    pagePath=(directory/(preview?L"ui":L"ui-vue")/L"index.html").wstring();
    // Isolated bridge fixture; the smoke flag also disables cloud/update I/O.
    if(command->HasSwitch("cef-smoke") && command->HasSwitch("cef-updates-smoke"))
        pagePath=(directory/L"updates-smoke"/L"index.html").wstring();
    desktopSmoke=command->HasSwitch("cef-desktop-smoke");
    smoke=command->HasSwitch("cef-smoke") || desktopSmoke;
    if(smoke && command->HasSwitch("cef-smoke-unicode"))pagePath=(directory/L"测试 空格"/L"index.html").wstring();
    if(!std::filesystem::exists(pagePath))return 2;
    CefSettings settings;
    settings.no_sandbox=sandbox==nullptr;
    settings.windowless_rendering_enabled=!preview;
    wchar_t local[MAX_PATH]{};
    if(FAILED(SHGetFolderPathW(nullptr,CSIDL_LOCAL_APPDATA,nullptr,0,local)))return 2;
    const auto data=std::filesystem::path(local)/(preview?L"MusxiPlayer-Preview":testApp?L"MusxiPlayer-Test":L"MusxiPlayer");
    std::error_code ec;std::filesystem::create_directories(data,ec);if(ec)return 2;
    if(!preview && !updateFixture){loadDesktopSettings(data);loadMainWindow(data);}
    initShortcuts(data);
    initWallpaper(data);
    CefString(&settings.root_cache_path)=(data/(updateFixture?L"cef-update-fixture-cache":smoke?L"cef-smoke-cache":L"cef-cache")).wstring();
    CefString(&settings.log_file)=(data/L"cef.log").wstring();
    settings.log_severity=LOGSEVERITY_WARNING;
    // CEF Browser UI uses the application's main thread; Renderer uses IPC.
    // A singleton redirect/early exit is not a successful IPC smoke test.
    if(!CefInitialize(CefMainArgs(instance),settings,app,sandbox))
        return smoke?4:CefGetExitCode();
    musxi::HostHooks hooks{ready,tick,canClose,!smoke && !preview && !updateFixture,testApp,updateFixture};
    musxi::setHostHooks(hooks);
    const int result=musxi::runApplication(instance);
    musxi::setHostHooks({});
    const bool ok=!createFailed && (!smoke || musxi::cef_adapter::smokePassed());
    CefShutdown();
    surface.clear();
    if(testWindow)DestroyWindow(testWindow);
    return ok?result:3;
}
}
namespace musxi::cef_adapter {
musxi::LibraryReply wallpaperCommand(const std::string& command,const std::string& params) {
    using Json=nlohmann::json;
    try {
        if(wallpaperChoosing)return {409,"正在选择壁纸，请完成当前操作",{}};
        const auto data=Json::parse(params);
        if(!data.is_object())return {400,"无效壁纸参数",{}};
        if(smoke && command=="wallpaper.testNextChoice" && data.size()==1 && data.contains("format") && data["format"].is_string()) {
            const auto format=data["format"].get<std::string>();
            if(format!="png" && format!="jpg" && format!="webp" && format!="cancel" && format!="invalid")return {400,"无效测试图片",{}};
            wallpaperTestChoice=format;return {0,{},"true"};
        }
        if(command=="wallpaper.getState" && data.empty())return {0,{},wallpaperImage(wallpaperStore->current()).dump()};
        if(command=="wallpaper.choose" && data.empty()) {
            if(smoke && !wallpaperTestChoice.empty()) {
                const auto format=wallpaperTestChoice;wallpaperTestChoice.clear();if(format=="cancel")return {0,{},"null"};
                wchar_t executable[32768];GetModuleFileNameW(nullptr,executable,32768);
                const auto file=std::filesystem::path(executable).parent_path()/L"wallpaper-fixtures"/(format=="invalid"?"invalid.png":"test."+format);
                return {0,{},wallpaperImage(wallpaperStore->select(file)).dump()};
            }
            wallpaperChoosing=true;
            try {const auto reply=chooseWallpaper();wallpaperChoosing=false;return {0,{},reply.dump()};}
            catch(...){wallpaperChoosing=false;throw;}
        }
        if(command=="wallpaper.reset" && data.empty()){wallpaperStore->reset();return {0,{},"true"};}
        if((command=="wallpaper.accept" || command=="wallpaper.discard" || command=="wallpaper.apply") && data.size()==1 && data.contains("id") && data["id"].is_string()) {
            const auto id=data["id"].get<std::string>();if(id.size()>64)return {400,"无效壁纸标识",{}};
            if(command=="wallpaper.apply")return {0,{},wallpaperImage(wallpaperStore->apply(id)).dump()};
            if(command=="wallpaper.accept")wallpaperStore->accept(id);else wallpaperStore->discard(id);
            return {0,{},"true"};
        }
        return {400,"无效壁纸操作或参数",{}};
    }catch(const Json::exception&){return {400,"无效壁纸参数",{}};}
    catch(const std::filesystem::filesystem_error&){return {500,"无法读取或保存壁纸，请重新选择图片",{}};}
    catch(const std::exception& error){return {400,error.what(),{}};}
}
void rememberShortcutVolume(int volume) {if(volume>0)shortcutLastVolume=volume;}
void resetShortcutInput() {shortcutEditing=false;shortcutRecording=false;if(hostClosing)shortcutRegistry.clear();else refreshShortcutSuspension();}
musxi::LibraryReply shortcutsCommand(const std::string& command,const std::string& params) {
    try {
        const auto data=ShortcutJson::parse(params);
        if(command=="shortcuts.getState" && data.is_object() && data.empty())return {0,{},shortcutSnapshot().dump()};
        if(command=="shortcuts.setSettings") {
            const auto next=musxi::shortcuts::parse(data);
            if(next.global && !testWindow)return {409,"此预览窗口不支持全局快捷键",{}};
            if(!shortcutRegistry.change(next,saveShortcuts))return {409,"无法保存快捷键：组合键可能已被系统或其他程序占用，或配置无法写入；已保留原设置。",{}};
        } else if(command=="shortcuts.suspend") {
            if(!data.is_object() || data.size()!=2 || !data.at("recording").is_boolean() || !data.at("editing").is_boolean())return {400,"无效录制状态",{}};
            shortcutRecording=data.at("recording");shortcutEditing=data.at("editing");refreshShortcutSuspension();
        } else if(command=="shortcuts.execute") {
            if(!data.is_object() || data.size()!=1 || !data.at("action").is_string())return {400,"无效快捷键操作",{}};
            const auto action=data.at("action").get<std::string>();
            if(std::none_of(musxi::shortcuts::actions.begin(),musxi::shortcuts::actions.end(),[&](const char* item){return action==item;}))return {400,"无效快捷键功能",{}};
            return executeShortcut(action);
        } else return {404,"未知快捷键操作",{}};
        return {0,{},shortcutSnapshot().dump()};
    }catch(const std::exception&){return {400,"无效快捷键参数",{}};}
}
bool mainWindowCompact() {return mainCompact;}
bool setMainWindowCompact(bool compact,int height) {
    if(!testWindow)return false;
    if(compact==mainCompact)return true;
    if(compact) {
        if(!GetWindowPlacement(testWindow,&expandedPlacement))return false;
        RECT bounds{};if(!GetWindowRect(testWindow,&bounds))return false;
        MONITORINFO monitor{sizeof(monitor)};
        if(!GetMonitorInfoW(MonitorFromWindow(testWindow,MONITOR_DEFAULTTONEAREST),&monitor))return false;
        expandedWorkOffset={monitor.rcWork.left-monitor.rcMonitor.left,monitor.rcWork.top-monitor.rcMonitor.top};
        compactHeight=height;mainCompact=true;
        ShowWindow(testWindow,SW_RESTORE);
        const int pixels=MulDiv(compactHeight,GetDpiForWindow(testWindow),96);
        if(!SetWindowPos(testWindow,nullptr,bounds.left,bounds.bottom-pixels,bounds.right-bounds.left,
                pixels,SWP_NOZORDER|SWP_NOACTIVATE)) {
            mainCompact=false;SetWindowPlacement(testWindow,&expandedPlacement);return false;
        }
    } else {
        mainCompact=false;
        if(!SetWindowPlacement(testWindow,&expandedPlacement)){mainCompact=true;return false;}
    }
    scheduleBrowserResize(testWindow);
    return true;
}
void presentDesktopLyrics(const void* pixels,int width,int height) {paintDesktopLyrics(pixels,width,height);}
std::string desktopLyricsDiagnostics() {
    bool painted=false,transparent=false;
    if(desktopBits) {
        const auto pixels=static_cast<const unsigned char*>(desktopBits);
        for(std::size_t i=3;i<static_cast<std::size_t>(desktopPixelWidth)*desktopPixelHeight*4;i+=4) {
            if(pixels[i])painted=true;else transparent=true;
        }
    }
    const auto style=desktopWindow?GetWindowLongPtrW(desktopWindow,GWL_EXSTYLE):0;
    return DesktopJson{{"exists",desktopWindow!=nullptr},{"painted",painted},{"transparent",transparent},
        {"noActivate",bool(style&WS_EX_NOACTIVATE)},{"toolWindow",bool(style&WS_EX_TOOLWINDOW)},
        {"topmost",bool(style&WS_EX_TOPMOST)},{"clickThrough",bool(style&WS_EX_TRANSPARENT)}}.dump();
}
void desktopLyricsClosed() {
    if(!hostClosing)saveDesktopSettings();
    clearDesktopPixels();
    if(desktopWindow){DestroyWindow(desktopWindow);desktopWindow=nullptr;}
    if(desktopVisible && !desktopClosing && !hostClosing){desktopVisible=false;saveDesktopSettings();}
    desktopClosing=false;desktopPanel=desktopHover=false;
    if(!hostClosing)publishDesktop();
}
std::string desktopLyricsState() {return desktopStateJson();}
// One setter per field; each takes exactly {field: value}.
musxi::LibraryReply desktopLyricsCommand(const std::string& command,const std::string& params) {
    try {
        const auto data=DesktopJson::parse(params);
        if(!data.is_object())return {400,"无效桌面歌词参数",{}};
        if(command=="desktopLyrics.getState") {if(!data.empty())return {400,"无效桌面歌词参数",{}};return {0,{},desktopStateJson()};}
        if(!testWindow)return {409,"当前窗口不支持桌面歌词",{}};
        static const std::map<std::string,std::string> fields{{"desktopLyrics.setVisible","visible"},{"desktopLyrics.setLocked","locked"},
            {"desktopLyrics.setDual","dual"},{"desktopLyrics.setPanel","panel"},{"desktopLyrics.setFontSize","fontSize"},{"desktopLyrics.setColor","color"}};
        const auto found=fields.find(command);
        if(found==fields.end())return {404,"未知桌面歌词操作",{}};
        const auto& field=found->second;
        if(data.size()!=1 || !data.contains(field))return {400,"无效桌面歌词参数",{}};
        const auto& value=data.at(field);
        if(field=="fontSize") {
            if(!value.is_number_integer() || value.get<int>()<18 || value.get<int>()>48)return {400,"无效桌面歌词参数",{}};
            desktopFontSize=value.get<int>();
        } else if(field=="color") {
            if(!value.is_string() || !desktopColorValid(value.get<std::string>()))return {400,"无效桌面歌词参数",{}};
            desktopColor=value.get<std::string>();
        } else {
            if(!value.is_boolean())return {400,"无效桌面歌词参数",{}};
            const bool on=value.get<bool>();
            if(field=="visible") {
                if(on) {if(!showDesktopLyrics())return {500,"无法打开桌面歌词，请稍后重试",{}};desktopVisible=true;}
                else {desktopVisible=false;desktopPanel=false;saveDesktopSettings();desktopClosing=desktopWindow!=nullptr;closeDesktopLyricsBrowser();}
            } else if(field=="locked") {
                if(on && !addTray(testWindow))return {500,"托盘不可用，暂时无法锁定桌面歌词",{}};
                lockDesktopLyrics(on);
            } else if(field=="panel") {
                if(on && (desktopLocked || !desktopWindow))return {409,"桌面歌词已锁定",{}};
                desktopPanel=on;desktopInsideTick=GetTickCount64();
            } else desktopDual=on;
        }
        if(field!="panel")saveDesktopSettings();
        publishDesktop();
        return {0,{},desktopStateJson()};
    }catch(...){return {400,"无效桌面歌词参数",{}};}
}
bool minimizeToTray() {return testWindow && hideToTray(testWindow);}
void toggleTrayWindow() {if(testWindow){if(IsWindowVisible(testWindow))hideToTray(testWindow);else showFromTray(testWindow);}}
void dismissTrayMenu() {if(trayWindow)PostMessageW(trayWindow,WM_CLOSE,0,0);}
void trayMenuClosed() {if(trayWindow){DestroyWindow(trayWindow);trayWindow=nullptr;}}
void presentBrowser(bool popup,const void* pixels,int width,int height) {
    if(!testWindow || renderFailed)return;
    if(!popup) {
        RECT bounds{};GetClientRect(testWindow,&bounds);
        if(std::abs(bounds.right-width)<=1 && std::abs(bounds.bottom-height)<=1)KillTimer(testWindow,102);
    }
    if(surface.paint(testWindow,popup,pixels,width,height))return;
    surface.clear();
    if(!popup && surface.paint(testWindow,false,pixels,width,height))return;
    renderFailed=true;
    if(smoke)createFailed=true;
    PostMessageW(testWindow,RecoveryFailed,0,0);
}
void browserPopup(bool visible,const CefRect& bounds) {
    const int dpi=GetDpiForWindow(testWindow);
    surface.popup(visible,{MulDiv(bounds.x,dpi,96),MulDiv(bounds.y,dpi,96),MulDiv(bounds.x+bounds.width,dpi,96),MulDiv(bounds.y+bounds.height,dpi,96)});
    surface.present(testWindow);
}
void browserCursor(CefCursorHandle cursor) {webCursor=cursor;SetCursor(cursor);}
int browserPixelAlpha(int x,int y) {
    const int dpi=GetDpiForWindow(testWindow);return surface.alphaAt(MulDiv(x,dpi,96),MulDiv(y,dpi,96));
}
void browserImeBounds(const std::vector<CefRect>& bounds) {
    if(bounds.empty() || !testWindow)return;
    const auto context=ImmGetContext(testWindow);if(!context)return;
    const int dpi=GetDpiForWindow(testWindow);const auto& rect=bounds.back();
    CANDIDATEFORM candidate{};candidate.dwStyle=CFS_EXCLUDE;
    candidate.ptCurrentPos={MulDiv(rect.x,dpi,96),MulDiv(rect.y+rect.height,dpi,96)};
    candidate.rcArea={MulDiv(rect.x,dpi,96),MulDiv(rect.y,dpi,96),MulDiv(rect.x+rect.width,dpi,96),MulDiv(rect.y+rect.height,dpi,96)};
    ImmSetCandidateWindow(context,&candidate);
    COMPOSITIONFORM composition{};composition.dwStyle=CFS_POINT;composition.ptCurrentPos=candidate.ptCurrentPos;
    ImmSetCompositionWindow(context,&composition);ImmReleaseContext(testWindow,context);
}
}
CEF_BOOTSTRAP_EXPORT int RunWinMain(HINSTANCE instance,LPWSTR,int show,
                                    void* sandbox,cef_version_info_t*) {
    return run(instance,show,sandbox);
}
