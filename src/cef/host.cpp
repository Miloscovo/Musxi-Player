#include "bridge.hpp"
#include "include/cef_sandbox_win.h"
#include "include/cef_command_line.h"
#include "include/cef_parser.h"
#include "../application/application.hpp"
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

namespace {
std::wstring pagePath;
HWND nativeWindow=nullptr;
HWND testWindow=nullptr;
HWND recoveryText=nullptr,retryButton=nullptr,exitButton=nullptr;
bool smoke=false,createFailed=false,testApp=false,preview=false;
COLORREF frameColor=RGB(255,255,255);
ULONGLONG startTick=0;
CompositionSurface surface;
HCURSOR webCursor=nullptr;
bool mouseTracked=false,imeActive=false,releasingCapture=false,renderFailed=false;
constexpr UINT TrayMessage=WM_APP+44;
const UINT TaskbarCreated=RegisterWindowMessageW(L"TaskbarCreated");
bool trayAdded=false;
HICON trayIcon=nullptr;
HWND trayWindow=nullptr;
std::string mainUrl;
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
    if(auto browser=musxi::cef_adapter::browserHost()){browser->WasHidden(false);browser->WasResized();}
    SetForegroundWindow(hwnd);
}
bool hideToTray(HWND hwnd) {
    // Never hide the only reachable window if the notification icon cannot be added.
    if(!addTray(hwnd))return false;
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
    const int width=MulDiv(208,dpi,96),height=MulDiv(84,dpi,96);
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
LRESULT CALLBACK testProc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp) {
    const auto browser=musxi::cef_adapter::browserHost();
    if(msg==TaskbarCreated && trayAdded) {
        trayAdded=false;
        if(!addTray(hwnd))showFromTray(hwnd);
        return 0;
    }
    switch(msg) {
    case TrayMessage:
        if(LOWORD(lp)==WM_LBUTTONDBLCLK || LOWORD(lp)==NIN_KEYSELECT)showFromTray(hwnd);
        if(LOWORD(lp)==WM_CONTEXTMENU || LOWORD(lp)==WM_RBUTTONUP)trayMenu(hwnd);
        return 0;
    case WM_DESTROY: removeTray(hwnd);return 0;
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
    case WM_ENTERSIZEMOVE: SetTimer(hwnd,101,16,nullptr);return 0;
    case WM_EXITSIZEMOVE: KillTimer(hwnd,101);return 0;
    case WM_TIMER: if(wp==101){CefDoMessageLoopWork();return 0;}break;
    case WM_ERASEBKGND: {if(GetWindowLongPtrW(hwnd,GWL_EXSTYLE)&WS_EX_NOREDIRECTIONBITMAP)return 1;RECT r;GetClientRect(hwnd,&r);auto brush=CreateSolidBrush(frameColor);FillRect(reinterpret_cast<HDC>(wp),&r,brush);DeleteObject(brush);return 1;}
    case WM_APP+43: frameColor=wp?RGB(16,18,20):RGB(255,255,255);InvalidateRect(hwnd,nullptr,TRUE);return 0;
    case WM_NCCALCSIZE: return 0;
    case WM_NCPAINT: return 0;
    case WM_NCHITTEST: return hostHitTest(hwnd,lp);
    case WM_GETMINMAXINFO: {
        auto m=reinterpret_cast<MINMAXINFO*>(lp);
        m->ptMinTrackSize={MulDiv(800,GetDpiForWindow(hwnd),96),MulDiv(560,GetDpiForWindow(hwnd),96)};
        MONITORINFO info{sizeof(info)};
        if(GetMonitorInfoW(MonitorFromWindow(hwnd,MONITOR_DEFAULTTONEAREST),&info)) {
            m->ptMaxPosition={info.rcWork.left-info.rcMonitor.left,info.rcWork.top-info.rcMonitor.top};
            m->ptMaxSize={info.rcWork.right-info.rcWork.left,info.rcWork.bottom-info.rcWork.top};
        }return 0;
    }
    case WM_DPICHANGED: {auto r=reinterpret_cast<RECT*>(lp);SetWindowPos(hwnd,nullptr,r->left,r->top,r->right-r->left,r->bottom-r->top,SWP_NOZORDER);if(browser){browser->NotifyScreenInfoChanged();browser->WasResized();}return 0;}
    case WM_SIZE: {
        if(browser){browser->WasHidden(wp==SIZE_MINIMIZED);if(wp!=SIZE_MINIMIZED)browser->WasResized();}return 0;
    }
    case musxi::cef_adapter::RecoveryFailed:
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
    if(!CefParseURL(CefString(buffer).ToString()+(smoke?"?smoke=1":""),parts)) {
        createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);return;
    }
    const auto url=CefString(&parts.spec).ToString();
    mainUrl=url;
    CefWindowInfo info;
    if(!preview) {
        WNDCLASSW wc{};wc.style=CS_DBLCLKS;wc.lpfnWndProc=testProc;wc.hInstance=GetModuleHandleW(nullptr);
        wc.lpszClassName=L"MusxiPlayerTestHost";wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);
        wc.hbrBackground=nullptr;RegisterClassW(&wc);
        RECT work{};int x=CW_USEDEFAULT,y=CW_USEDEFAULT,width=1360,height=850;
        if(SystemParametersInfoW(SPI_GETWORKAREA,0,&work,0)) {
            const int availableWidth=work.right-work.left,availableHeight=work.bottom-work.top;
            if(availableWidth<width)width=availableWidth;
            if(availableHeight<height)height=availableHeight;
            x=work.left+(availableWidth-width)/2;y=work.top+(availableHeight-height)/2;
        }
        testWindow=CreateWindowExW(WS_EX_NOREDIRECTIONBITMAP,wc.lpszClassName,L"Musxi Player 测试版",WS_POPUP|WS_THICKFRAME|WS_MINIMIZEBOX|WS_MAXIMIZEBOX|WS_SYSMENU|WS_CLIPCHILDREN,
            x,y,width,height,nullptr,nullptr,wc.hInstance,nullptr);
        if(!testWindow){createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);return;}
        SetWindowPos(testWindow,nullptr,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOZORDER|SWP_FRAMECHANGED);
        info.SetAsWindowless(testWindow);
    } else info.SetAsPopup(nullptr,"Musxi Player - CEF Preview");
    info.runtime_style=CEF_RUNTIME_STYLE_ALLOY;
    if(preview)info.bounds=CefRect(160,120,960,680);
    CefBrowserSettings settings;
    if(!preview){settings.background_color=0;settings.windowless_frame_rate=30;}
    auto browser=CefBrowserHost::CreateBrowserSync(info,
        musxi::cef_adapter::makeClient(url,window,smoke,testWindow),url,settings,nullptr,nullptr);
    if(!browser) {createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);}
    else if(smoke && preview)ShowWindow(browser->GetHost()->GetWindowHandle(),SW_HIDE);
    if(browser && !preview) {
        SendMessageW(testWindow,WM_SIZE,0,0);
        if(!smoke) {
            ShowWindow(testWindow,SW_SHOW);
            addTray(testWindow);
        }
    }
    startTick=GetTickCount64();
}
void tick() {
    CefDoMessageLoopWork();
    if(smoke && GetTickCount64()-startTick>20000)PostMessageW(nativeWindow,WM_CLOSE,0,0);
}
bool canClose() {
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
    testApp=command->HasSwitch("test-app") || std::filesystem::path(exe).filename()==L"MusxiPlayerTest.exe";
    preview=command->HasSwitch("cef-preview");
    if(!preview)SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    pagePath=(directory/(preview?L"ui":L"ui-vue")/L"index.html").wstring();
    smoke=command->HasSwitch("cef-smoke");
    if(smoke && command->HasSwitch("cef-smoke-unicode"))pagePath=(directory/L"测试 空格"/L"index.html").wstring();
    if(!std::filesystem::exists(pagePath))return 2;
    CefSettings settings;
    settings.no_sandbox=sandbox==nullptr;
    settings.windowless_rendering_enabled=!preview;
    wchar_t local[MAX_PATH]{};
    if(FAILED(SHGetFolderPathW(nullptr,CSIDL_LOCAL_APPDATA,nullptr,0,local)))return 2;
    const auto data=std::filesystem::path(local)/(preview?L"MusxiPlayer-Preview":testApp?L"MusxiPlayer-Test":L"MusxiPlayer");
    std::error_code ec;std::filesystem::create_directories(data,ec);if(ec)return 2;
    CefString(&settings.root_cache_path)=(data/(smoke?L"cef-smoke-cache":L"cef-cache")).wstring();
    CefString(&settings.log_file)=(data/L"cef.log").wstring();
    settings.log_severity=LOGSEVERITY_WARNING;
    // CEF Browser UI uses the application's main thread; Renderer uses IPC.
    // A singleton redirect/early exit is not a successful IPC smoke test.
    if(!CefInitialize(CefMainArgs(instance),settings,app,sandbox))
        return smoke?4:CefGetExitCode();
    musxi::HostHooks hooks{ready,tick,canClose,!smoke && !preview,testApp};
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
bool minimizeToTray() {return testWindow && hideToTray(testWindow);}
void toggleTrayWindow() {if(testWindow){if(IsWindowVisible(testWindow))hideToTray(testWindow);else showFromTray(testWindow);}}
void dismissTrayMenu() {if(trayWindow)PostMessageW(trayWindow,WM_CLOSE,0,0);}
void trayMenuClosed() {if(trayWindow){DestroyWindow(trayWindow);trayWindow=nullptr;}}
void presentBrowser(bool popup,const void* pixels,int width,int height) {
    if(!testWindow || renderFailed)return;
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
