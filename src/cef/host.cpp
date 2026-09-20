#include "bridge.hpp"
#include "include/cef_sandbox_win.h"
#include "include/cef_command_line.h"
#include "../application/application.hpp"
#include <filesystem>
#include <shlwapi.h>
#include <string>

namespace {
std::wstring pagePath;
HWND nativeWindow=nullptr;
bool smoke=false,createFailed=false;
ULONGLONG startTick=0;
void ready(void* window) {
    nativeWindow=static_cast<HWND>(window);
    wchar_t buffer[32768];DWORD size=32768;
    if(FAILED(UrlCreateFromPathW(pagePath.c_str(),buffer,&size,0))) {
        createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);return;
    }
    const auto url=CefString(buffer).ToString()+(smoke?"?smoke=1":"");
    CefWindowInfo info;
    info.SetAsPopup(nullptr,"Musxi Player - CEF Preview");
    info.runtime_style=CEF_RUNTIME_STYLE_ALLOY;
    info.bounds=CefRect(160,120,960,680);
    CefBrowserSettings settings;
    auto browser=CefBrowserHost::CreateBrowserSync(info,
        musxi::cef_adapter::makeClient(url,window,smoke),url,settings,nullptr,nullptr);
    if(!browser) {createFailed=true;PostMessageW(nativeWindow,WM_CLOSE,0,0);}
    else if(smoke)ShowWindow(browser->GetHost()->GetWindowHandle(),SW_HIDE);
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
    pagePath=(directory/(command->HasSwitch("cef-vue")?L"ui-vue":L"ui")/L"index.html").wstring();
    smoke=command->HasSwitch("cef-smoke");
    if(!std::filesystem::exists(pagePath))return 2;
    CefSettings settings;
    settings.no_sandbox=sandbox==nullptr;
    CefString(&settings.root_cache_path)=(directory/L"cef-cache").wstring();
    CefString(&settings.log_file)=(directory/L"cef.log").wstring();
    settings.log_severity=LOGSEVERITY_WARNING;
    // CEF Browser UI uses the application's main thread; Renderer uses IPC.
    // A singleton redirect/early exit is not a successful IPC smoke test.
    if(!CefInitialize(CefMainArgs(instance),settings,app,sandbox))
        return smoke?4:CefGetExitCode();
    musxi::setHostHooks({ready,tick,canClose,!smoke});
    const int result=musxi::runNativeApplication(instance,smoke?SW_HIDE:show);
    musxi::setHostHooks({});
    const bool ok=!createFailed && (!smoke || musxi::cef_adapter::smokePassed());
    CefShutdown();
    return ok?result:3;
}
}
CEF_BOOTSTRAP_EXPORT int RunWinMain(HINSTANCE instance,LPWSTR,int show,
                                    void* sandbox,cef_version_info_t*) {
    return run(instance,show,sandbox);
}
