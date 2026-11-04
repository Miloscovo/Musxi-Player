#include <windows.h>
bool failPlacement=false;
bool failPosition=false;
bool failRead=false;
bool failReadAfterPlacement=false;
BOOL WINAPI testGetWindowPlacement(HWND window,WINDOWPLACEMENT* placement) {
    if(failRead){failRead=false;SetLastError(ERROR_ACCESS_DENIED);return FALSE;}
    return GetWindowPlacement(window,placement);
}
BOOL WINAPI testSetWindowPlacement(HWND window,const WINDOWPLACEMENT* placement) {
    if(failPlacement){failPlacement=false;if(failReadAfterPlacement){failReadAfterPlacement=false;failRead=true;}SetLastError(ERROR_ACCESS_DENIED);return FALSE;}
    return SetWindowPlacement(window,placement);
}
BOOL WINAPI testSetWindowPos(HWND window,HWND after,int x,int y,int width,int height,UINT flags) {
    if(failPosition){failPosition=false;SetLastError(ERROR_ACCESS_DENIED);return FALSE;}
    return SetWindowPos(window,after,x,y,width,height,flags);
}
#define SetWindowPlacement testSetWindowPlacement
#define SetWindowPos testSetWindowPos
#define GetWindowPlacement testGetWindowPlacement
#include "../src/cef/game_window.hpp"
#undef SetWindowPlacement
#undef SetWindowPos
#undef GetWindowPlacement
#include <iostream>
#include <stdexcept>

void require(bool value,const char* message) {if(!value)throw std::runtime_error(message);}
struct Fixture {
    HWND window=nullptr;
    Fixture() {
        WNDCLASSW wc{};wc.lpfnWndProc=DefWindowProcW;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"MusxiMinecraftLayoutTest";
        RegisterClassW(&wc);
        MONITORINFO monitor{sizeof(monitor)};GetMonitorInfoW(MonitorFromPoint({0,0},MONITOR_DEFAULTTOPRIMARY),&monitor);
        window=CreateWindowExW(WS_EX_TOOLWINDOW|WS_EX_NOACTIVATE,wc.lpszClassName,L"Minecraft layout test",WS_OVERLAPPEDWINDOW,
            monitor.rcWork.left+70,monitor.rcWork.top+70,480,320,nullptr,nullptr,wc.hInstance,nullptr);
        require(window!=nullptr,"create fixture");ShowWindow(window,SW_SHOWNOACTIVATE);
    }
    ~Fixture(){if(IsWindow(window))DestroyWindow(window);}
};
int main() {
    SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    try {
        Fixture fixture;const auto window=fixture.window;
        require(!musxi::game_window::candidate(window),"non Java/non GLFW windows must not match");
        const auto foreground=GetForegroundWindow();const auto style=GetWindowLongPtrW(window,GWL_STYLE);
        RECT original{};GetWindowRect(window,&original);
        {
            musxi::game_window::Window layout(window);failPosition=true;
            require(!layout.apply(),"position failure must fail application");
            RECT after{};GetWindowRect(window,&after);
            require(musxi::game_window::sameRect(after,original) && GetWindowLongPtrW(window,GWL_STYLE)==style,"failed application rolls back style and bounds");
            require(!layout.alive(),"successful rollback releases ownership marker");
        }
        {
            musxi::game_window::Window layout(window);
            require(layout.apply(),"apply border outside monitor");
            require(layout.unchanged(),"own layout recognized");
            MONITORINFO monitor{sizeof(monitor)};GetMonitorInfoW(MonitorFromWindow(window,MONITOR_DEFAULTTONEAREST),&monitor);
            RECT client{},outer{};POINT origin{};GetClientRect(window,&client);ClientToScreen(window,&origin);GetWindowRect(window,&outer);
            require(origin.x==monitor.rcMonitor.left && origin.y==monitor.rcMonitor.top,"exact client screen origin");
            require(client.right==monitor.rcMonitor.right-monitor.rcMonitor.left && client.bottom==monitor.rcMonitor.bottom-monitor.rcMonitor.top,"exact full monitor client, no crop or gap");
            require(outer.left<monitor.rcMonitor.left && outer.top<monitor.rcMonitor.top && outer.right>monitor.rcMonitor.right && outer.bottom>monitor.rcMonitor.bottom,"frame outside all four edges");
            require(GetForegroundWindow()==foreground,"apply must preserve foreground");
            require(layout.restore(),"restore layout");GetWindowRect(window,&outer);
            require(musxi::game_window::sameRect(outer,original),"restore original screen bounds");
            require(GetWindowLongPtrW(window,GWL_STYLE)==style,"restore original style");
            require(GetForegroundWindow()==foreground,"restore must preserve foreground");
        }
        {
            musxi::game_window::Window layout(window);require(layout.apply(),"apply before recovery failure");
            failRead=true;
            require(!layout.restore(),"unknown layout must not be treated as a user change or discarded");
            require(layout.alive() && layout.unchanged(),"read failure retains backup for later retry");
            failPlacement=true;
            require(!layout.restore(),"partial restoration failure must be reported");
            require(layout.alive() && layout.unchanged(),"partial failure retains identity and updated recovery snapshot");
            require(layout.restore(),"retry must restore rather than discard backup");
            RECT after{};GetWindowRect(window,&after);require(musxi::game_window::sameRect(after,original),"retry restored original bounds");
        }
        {
            musxi::game_window::Window layout(window);require(layout.apply(),"apply before combined recovery failure");
            failPlacement=true;failReadAfterPlacement=true;
            require(!layout.restore(),"failed write and snapshot read must preserve backup");
            require(layout.alive() && layout.unchanged(),"recovered reads can verify the saved recovery bounds");
            require(layout.restore(),"combined failure must not permanently block restoration");
            RECT after{};GetWindowRect(window,&after);require(musxi::game_window::sameRect(after,original),"combined failure retry restores original bounds");
        }
        {
            musxi::game_window::Window layout(window);require(layout.apply(),"second apply");
            RECT moved{};GetWindowRect(window,&moved);
            require(SetWindowPos(window,nullptr,moved.left+23,moved.top+19,0,0,SWP_NOSIZE|SWP_NOZORDER|SWP_NOACTIVATE)!=FALSE,"manual move");
            require(!layout.unchanged(),"manual move relinquishes ownership");
            require(layout.restore(),"manual move must not be undone");
            RECT after{};GetWindowRect(window,&after);require(after.left==moved.left+23 && after.top==moved.top+19,"preserve user's later layout");
        }
        // A popup is ambiguous with GLFW native full screen: wait for the user to press F11.
        SetWindowLongPtrW(window,GWL_STYLE,WS_POPUP|WS_VISIBLE);
        {musxi::game_window::Window layout(window);require(!layout.apply(),"do not modify native full screen popup");}
        SetWindowLongPtrW(window,GWL_STYLE,style);
        SetWindowPos(window,nullptr,original.left,original.top,original.right-original.left,original.bottom-original.top,SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED);
        {
            musxi::game_window::Window layout(window);require(layout.apply(),"apply before minimize");
            ShowWindow(window,SW_SHOWMINNOACTIVE);
            require(layout.unchanged(),"minimizing alone retains layout ownership");
            require(layout.restore(),"restore minimized window");
            require(IsIconic(window)!=FALSE,"restoring layout must not unminimize game");
            require(GetForegroundWindow()==foreground,"minimized restore preserves focus");
            ShowWindow(window,SW_SHOWNOACTIVATE);
        }
        {
            musxi::game_window::Window layout(window);require(layout.apply(),"apply before game closes");
            DestroyWindow(window);require(!layout.unchanged(),"closed window loses ownership");
            require(layout.restore(),"closed window needs no restoration");
        }
        {
            Fixture maximized;
            WINDOWPLACEMENT placement{sizeof(placement)};GetWindowPlacement(maximized.window,&placement);placement.showCmd=SW_SHOWMAXIMIZED;
            require(SetWindowPlacement(maximized.window,&placement)!=FALSE,"maximize fixture");
            const auto focus=GetForegroundWindow();
            RECT bounds{};GetWindowRect(maximized.window,&bounds);
            WINDOWPLACEMENT before{sizeof(before)};GetWindowPlacement(maximized.window,&before);
            musxi::game_window::Window layout(maximized.window);require(layout.apply(),"apply maximized window");
            require(layout.restore(),"restore maximized window");
            RECT after{};GetWindowRect(maximized.window,&after);
            WINDOWPLACEMENT restored{sizeof(restored)};GetWindowPlacement(maximized.window,&restored);
            require(IsZoomed(maximized.window)!=FALSE,"restore maximized state");
            require(musxi::game_window::sameRect(bounds,after),"restore maximized bounds");
            require(musxi::game_window::sameRect(before.rcNormalPosition,restored.rcNormalPosition),"preserve maximized window's normal placement");
            require(GetForegroundWindow()==focus,"maximized restoration preserves focus");
        }
        std::cout<<"Minecraft client bounds, restoration, manual changes, minimize, closed session and focus checks passed\n";
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
