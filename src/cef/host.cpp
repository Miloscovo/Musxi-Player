#include "bridge.hpp"
#include "include/cef_sandbox_win.h"
#include "include/cef_command_line.h"
#include "include/cef_parser.h"
#include "../application/application.hpp"
#include <filesystem>
#include <shlwapi.h>
#include <shlobj.h>
#include <windowsx.h>
#include <string>

namespace {
std::wstring pagePath;
HWND nativeWindow=nullptr;
HWND testWindow=nullptr,webWindow=nullptr;
HWND recoveryText=nullptr,retryButton=nullptr,exitButton=nullptr;
bool smoke=false,createFailed=false,testApp=false;
COLORREF frameColor=RGB(255,255,255);
ULONGLONG startTick=0;
LRESULT CALLBACK testProc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp) {
    switch(msg) {
    case WM_ERASEBKGND: {RECT r;GetClientRect(hwnd,&r);auto brush=CreateSolidBrush(frameColor);FillRect(reinterpret_cast<HDC>(wp),&r,brush);DeleteObject(brush);return 1;}
    case WM_APP+43: frameColor=wp?RGB(16,18,20):RGB(255,255,255);InvalidateRect(hwnd,nullptr,TRUE);return 0;
    case WM_NCCALCSIZE: if(wp)return 0;break;
    case WM_NCHITTEST: {
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
    case WM_GETMINMAXINFO: {
        auto m=reinterpret_cast<MINMAXINFO*>(lp);
        m->ptMinTrackSize={MulDiv(800,GetDpiForWindow(hwnd),96),MulDiv(560,GetDpiForWindow(hwnd),96)};
        MONITORINFO info{sizeof(info)};
        if(GetMonitorInfoW(MonitorFromWindow(hwnd,MONITOR_DEFAULTTONEAREST),&info)) {
            m->ptMaxPosition={info.rcWork.left-info.rcMonitor.left,info.rcWork.top-info.rcMonitor.top};
            m->ptMaxSize={info.rcWork.right-info.rcWork.left,info.rcWork.bottom-info.rcWork.top};
        }return 0;
    }
    case WM_DPICHANGED: {auto r=reinterpret_cast<RECT*>(lp);SetWindowPos(hwnd,nullptr,r->left,r->top,r->right-r->left,r->bottom-r->top,SWP_NOZORDER);return 0;}
    case WM_SIZE: {
        RECT r;GetClientRect(hwnd,&r);const int e=IsZoomed(hwnd)?0:MulDiv(7,GetDpiForWindow(hwnd),96);
        if(webWindow)MoveWindow(webWindow,e,e,r.right-2*e,r.bottom-2*e,TRUE);return 0;
    }
    case musxi::cef_adapter::RecoveryFailed:
        ShowWindow(webWindow,SW_HIDE);
        if(!recoveryText) {
            const auto instance=GetModuleHandleW(nullptr);
            recoveryText=CreateWindowW(L"STATIC",L"界面恢复失败，后台播放不受影响。",WS_CHILD|WS_VISIBLE,40,80,600,40,hwnd,nullptr,instance,nullptr);
            retryButton=CreateWindowW(L"BUTTON",L"重试",WS_CHILD|WS_VISIBLE|WS_TABSTOP,40,140,140,44,hwnd,reinterpret_cast<HMENU>(1),instance,nullptr);
            exitButton=CreateWindowW(L"BUTTON",L"关闭播放器",WS_CHILD|WS_VISIBLE|WS_TABSTOP,200,140,140,44,hwnd,reinterpret_cast<HMENU>(2),instance,nullptr);
        }
        ShowWindow(recoveryText,SW_SHOW);ShowWindow(retryButton,SW_SHOW);ShowWindow(exitButton,SW_SHOW);SetFocus(retryButton);return 0;
    case WM_COMMAND:
        if(LOWORD(wp)==1) {
            ShowWindow(recoveryText,SW_HIDE);ShowWindow(retryButton,SW_HIDE);ShowWindow(exitButton,SW_HIDE);
            ShowWindow(webWindow,SW_SHOW);musxi::cef_adapter::retryBrowser();return 0;
        }
        if(LOWORD(wp)==2)PostMessageW(hwnd,WM_CLOSE,0,0);return 0;
    case WM_CLOSE:
        if(musxi::cef_adapter::browserReadyToClose())DestroyWindow(hwnd);
        else if(nativeWindow)PostMessageW(nativeWindow,WM_CLOSE,0,0);return 0;
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
    CefWindowInfo info;
    if(testApp) {
        WNDCLASSW wc{};wc.style=CS_DBLCLKS;wc.lpfnWndProc=testProc;wc.hInstance=GetModuleHandleW(nullptr);
        wc.lpszClassName=L"MusxiPlayerTestHost";wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);
        wc.hbrBackground=static_cast<HBRUSH>(GetStockObject(BLACK_BRUSH));RegisterClassW(&wc);
        testWindow=CreateWindowExW(0,wc.lpszClassName,L"Musxi Player 测试版",WS_POPUP|WS_THICKFRAME|WS_MINIMIZEBOX|WS_MAXIMIZEBOX|WS_SYSMENU|WS_CLIPCHILDREN,
            CW_USEDEFAULT,CW_USEDEFAULT,1120,760,nullptr,nullptr,wc.hInstance,nullptr);
        if(!testWindow){createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);return;}
        SetWindowPos(testWindow,nullptr,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOZORDER|SWP_FRAMECHANGED);
        RECT r;GetClientRect(testWindow,&r);
        info.SetAsChild(testWindow,CefRect(7,7,r.right-14,r.bottom-14));
    } else info.SetAsPopup(nullptr,"Musxi Player - CEF Preview");
    info.runtime_style=CEF_RUNTIME_STYLE_ALLOY;
    if(!testApp)info.bounds=CefRect(160,120,960,680);
    CefBrowserSettings settings;
    auto browser=CefBrowserHost::CreateBrowserSync(info,
        musxi::cef_adapter::makeClient(url,window,smoke,testWindow),url,settings,nullptr,nullptr);
    if(!browser) {createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);}
    else if(smoke)ShowWindow(browser->GetHost()->GetWindowHandle(),SW_HIDE);
    if(browser && testApp) {
        webWindow=browser->GetHost()->GetWindowHandle();
        SendMessageW(testWindow,WM_SIZE,0,0);
        if(!smoke)ShowWindow(testWindow,SW_SHOW);
    }
    startTick=GetTickCount64();
}
void tick() {
    CefDoMessageLoopWork();
    if(smoke && GetTickCount64()-startTick>20000)PostMessageW(nativeWindow,WM_CLOSE,0,0);
}
bool canClose() {return musxi::cef_adapter::closeBrowsers();}
int run(HINSTANCE instance,int show,void* sandbox) {
    auto app=musxi::cef_adapter::makeApp();
    const int child=CefExecuteProcess(CefMainArgs(instance),app,sandbox);
    if(child>=0)return child;
    wchar_t exe[32768];GetModuleFileNameW(nullptr,exe,32768);
    const auto directory=std::filesystem::path(exe).parent_path();
    auto command=CefCommandLine::CreateCommandLine();command->InitFromString(GetCommandLineW());
    testApp=command->HasSwitch("test-app") || std::filesystem::path(exe).filename()==L"MusxiPlayerTest.exe";
    if(testApp)SetProcessDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    pagePath=(directory/((testApp || command->HasSwitch("cef-vue"))?L"ui-vue":L"ui")/L"index.html").wstring();
    smoke=command->HasSwitch("cef-smoke");
    if(smoke && command->HasSwitch("cef-smoke-unicode"))pagePath=(directory/L"测试 空格"/L"index.html").wstring();
    if(!std::filesystem::exists(pagePath))return 2;
    CefSettings settings;
    settings.no_sandbox=sandbox==nullptr;
    wchar_t local[MAX_PATH]{};
    if(FAILED(SHGetFolderPathW(nullptr,CSIDL_LOCAL_APPDATA,nullptr,0,local)))return 2;
    const auto data=std::filesystem::path(local)/(testApp?L"MusxiPlayer-Test":L"MusxiPlayer-Preview");
    std::error_code ec;std::filesystem::create_directories(data,ec);if(ec)return 2;
    CefString(&settings.root_cache_path)=(data/(smoke?L"cef-smoke-cache":L"cef-cache")).wstring();
    CefString(&settings.log_file)=(data/L"cef.log").wstring();
    settings.log_severity=LOGSEVERITY_WARNING;
    // CEF Browser UI uses the application's main thread; Renderer uses IPC.
    // A singleton redirect/early exit is not a successful IPC smoke test.
    if(!CefInitialize(CefMainArgs(instance),settings,app,sandbox))
        return smoke?4:CefGetExitCode();
    musxi::HostHooks hooks{ready,tick,canClose,!smoke,testApp};
#ifdef MUSXI_ENABLE_FFMPEG
    hooks.ffmpegAudio=testApp;
#endif
    musxi::setHostHooks(hooks);
    const int result=musxi::runNativeApplication(instance,(smoke || testApp)?SW_HIDE:show);
    musxi::setHostHooks({});
    const bool ok=!createFailed && (!smoke || musxi::cef_adapter::smokePassed());
    CefShutdown();
    if(testWindow)DestroyWindow(testWindow);
    return ok?result:3;
}
}
CEF_BOOTSTRAP_EXPORT int RunWinMain(HINSTANCE instance,LPWSTR,int show,
                                    void* sandbox,cef_version_info_t*) {
    return run(instance,show,sandbox);
}
