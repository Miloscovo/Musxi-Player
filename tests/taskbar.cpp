#include <windows.h>
#include <shobjidl.h>
#include <string>
#include <stdexcept>
#include "../src/player/player_service.hpp"
namespace {
void check(bool value){if(!value)throw std::runtime_error("Taskbar controls regression");}
musxi::PlayerState player;
bool queued=false;
std::string action;
struct FakeTaskbar {
    HRESULT result=S_OK;
    int added=0,updated=0,released=0;
    THUMBBUTTON buttons[3]{};
    HRESULT HrInit(){return result;}
    void Release(){++released;}
    HRESULT save(UINT count,THUMBBUTTON* values){check(count==3);for(int i=0;i<3;++i)buttons[i]=values[i];return result;}
    HRESULT ThumbBarAddButtons(HWND,UINT count,THUMBBUTTON* values){++added;return save(count,values);}
    HRESULT ThumbBarUpdateButtons(HWND,UINT count,THUMBBUTTON* values){++updated;return save(count,values);}
} shell;
HRESULT creationResult=S_OK;
HRESULT createInstance(REFCLSID,LPUNKNOWN,DWORD,REFIID,void** value){if(SUCCEEDED(creationResult))*value=&shell;return creationResult;}
void executeShortcut(const std::string& value){action=value;}
}
namespace musxi {
PlayerState applicationPlayerState(){return player;}
bool applicationHasQueuedTracks(){return queued;}
}
namespace {
#define ITaskbarList3 FakeTaskbar
#define CoCreateInstance createInstance
#include "../src/cef/taskbar.inc"
#undef CoCreateInstance
#undef ITaskbarList3
}
namespace shellIntegration {
#include "../src/cef/taskbar.inc"
LRESULT CALLBACK windowProc(HWND hwnd,UINT message,WPARAM wp,LPARAM lp) {
    if(message==TaskbarButtonCreated){createTaskbar(hwnd);return 0;}
    if(message==WM_COMMAND && taskbarCommand(hwnd,wp))return 0;
    if(message==WM_DESTROY)clearTaskbar();
    return DefWindowProcW(hwnd,message,wp,lp);
}
void run() {
    check(SUCCEEDED(CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED)));
    WNDCLASSW wc{};wc.lpfnWndProc=windowProc;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"MusxiTaskbarCheck";
    check(RegisterClassW(&wc)!=0);
    const auto foreground=GetForegroundWindow();
    auto hwnd=CreateWindowW(wc.lpszClassName,L"Musxi taskbar check",WS_OVERLAPPEDWINDOW,0,0,320,200,nullptr,nullptr,wc.hInstance,nullptr);
    check(hwnd!=nullptr);ShowWindow(hwnd,SW_SHOWMINNOACTIVE);
    const auto deadline=GetTickCount64()+10000;
    while(!taskbarAdded && GetTickCount64()<deadline) {
        MSG msg;while(PeekMessageW(&msg,nullptr,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}
        Sleep(10);
    }
    check(taskbarAdded && taskbarState==0);
    player.opened=true;queued=true;player.requestedPlaying=true;player.phase="playing";updateTaskbar(hwnd);check(taskbarState==6);
    for(int i=0;i<3;++i) {
        SendMessageW(hwnd,WM_COMMAND,MAKEWPARAM(TaskbarPrevious+i,THBN_CLICKED),0);
        check(action==std::string(i==0?"previous":i==1?"playPause":"next"));
        check(IsIconic(hwnd) && GetForegroundWindow()==foreground);
    }
    player.requestedPlaying=false;updateTaskbar(hwnd);check(taskbarState==5);
    player.opened=false;updateTaskbar(hwnd);check(taskbarState==0);
    DestroyWindow(hwnd);check(!taskbar);
    CoUninitialize();
}
}
int main(int argc,char**) {
    if(argc>1){shellIntegration::run();return 0;}
    createTaskbar(nullptr);
    check(shell.added==1 && taskbarAdded && taskbarState==0);
    for(int i=0;i<3;++i)check(shell.buttons[i].iId==TaskbarPrevious+i && shell.buttons[i].hIcon && shell.buttons[i].dwFlags==THBF_DISABLED);
    check(std::wstring(shell.buttons[0].szTip)==L"上一首" && std::wstring(shell.buttons[2].szTip)==L"下一首");
    check(taskbarCommand(nullptr,MAKEWPARAM(TaskbarPlayPause,THBN_CLICKED)) && action.empty());
    check(!taskbarCommand(nullptr,MAKEWPARAM(1,0)) && !taskbarCommand(nullptr,MAKEWPARAM(TaskbarNext+1,THBN_CLICKED)));
    player.opened=true;updateTaskbar(nullptr);
    check(taskbarState==1 && std::wstring(shell.buttons[1].szTip)==L"播放" && shell.buttons[1].dwFlags==THBF_ENABLED);
    const auto playIcon=shell.buttons[1].hIcon;
    player.requestedPlaying=true;player.phase="buffering";updateTaskbar(nullptr);
    check(taskbarState==2 && std::wstring(shell.buttons[1].szTip)==L"暂停" && shell.buttons[1].hIcon!=playIcon);
    const int updates=shell.updated;updateTaskbar(nullptr);check(shell.updated==updates);
    check(shell.buttons[0].dwFlags==THBF_DISABLED && shell.buttons[2].dwFlags==THBF_DISABLED);
    queued=true;updateTaskbar(nullptr);check(taskbarState==6 && shell.buttons[0].dwFlags==THBF_ENABLED && shell.buttons[2].dwFlags==THBF_ENABLED);
    for(int i=0;i<3;++i){check(taskbarCommand(nullptr,MAKEWPARAM(TaskbarPrevious+i,THBN_CLICKED)));check(action==std::string(i==0?"previous":i==1?"playPause":"next"));}
    queued=false;action.clear();updateTaskbar(nullptr);check(taskbarState==2 && shell.buttons[1].dwFlags==THBF_ENABLED);
    taskbarCommand(nullptr,MAKEWPARAM(TaskbarNext,THBN_CLICKED));check(action.empty());
    player.phase="failed";updateTaskbar(nullptr);check(taskbarState==1);
    shell.result=E_FAIL;player.opened=false;updateTaskbar(nullptr);check(taskbarState==1);
    shell.result=S_OK;updateTaskbar(nullptr);check(taskbarState==0);
    createTaskbar(nullptr);check(shell.added==2 && shell.released==1 && taskbarState==0);
    clearTaskbar();check(!taskbar && !taskbarAdded && !taskbarIcons[0] && shell.released==2);
    creationResult=E_FAIL;createTaskbar(nullptr);check(!taskbar);
    creationResult=S_OK;shell.result=E_FAIL;createTaskbar(nullptr);check(!taskbar && shell.released==3);
}
