#include <windows.h>
namespace {
RECT original{};
constexpr DWORD style=WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN|WS_CLIPSIBLINGS|WS_VISIBLE;
LRESULT CALLBACK procedure(HWND window,UINT message,WPARAM wp,LPARAM lp) {
    if(message==WM_APP+1 || message==WM_APP+2) {
        RECT bounds=original;
        if(message==WM_APP+1) {
            MONITORINFO monitor{sizeof(monitor)};GetMonitorInfoW(MonitorFromWindow(window,MONITOR_DEFAULTTONEAREST),&monitor);bounds=monitor.rcMonitor;
        }
        SetWindowLongPtrW(window,GWL_STYLE,message==WM_APP+1?WS_POPUP|WS_VISIBLE:style);
        SetWindowPos(window,nullptr,bounds.left,bounds.top,bounds.right-bounds.left,bounds.bottom-bounds.top,SWP_NOZORDER|SWP_NOACTIVATE|SWP_FRAMECHANGED);
        return 0;
    }
    if(message==WM_DESTROY){PostQuitMessage(0);return 0;}
    return DefWindowProcW(window,message,wp,lp);
}
}
int WINAPI wWinMain(HINSTANCE instance,HINSTANCE,PWSTR,int) {
    SetThreadDpiAwarenessContext(DPI_AWARENESS_CONTEXT_PER_MONITOR_AWARE_V2);
    WNDCLASSW wc{};wc.lpfnWndProc=procedure;wc.hInstance=instance;wc.lpszClassName=L"GLFW30";wc.hbrBackground=reinterpret_cast<HBRUSH>(COLOR_WINDOW+1);
    if(!RegisterClassW(&wc))return 1;
    MONITORINFO monitor{sizeof(monitor)};GetMonitorInfoW(MonitorFromPoint({0,0},MONITOR_DEFAULTTOPRIMARY),&monitor);
    original={monitor.rcWork.left+100,monitor.rcWork.top+100,monitor.rcWork.left+700,monitor.rcWork.top+500};
    const auto window=CreateWindowExW(WS_EX_APPWINDOW,wc.lpszClassName,L"Minecraft compatibility fixture",style,
        original.left,original.top,600,400,nullptr,nullptr,instance,nullptr);
    if(!window)return 1;
    ShowWindow(window,SW_SHOWNOACTIVATE);
    SetWindowPos(window,nullptr,0,0,0,0,SWP_NOZORDER|SWP_NOACTIVATE|SWP_NOMOVE|SWP_NOSIZE|SWP_SHOWWINDOW);
    MSG message{};while(GetMessageW(&message,nullptr,0,0)>0){TranslateMessage(&message);DispatchMessageW(&message);}
    return 0;
}
