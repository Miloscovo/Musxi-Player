#include "../src/main.cpp"
#include <iostream>
#include <stdexcept>
void require(bool value,const char* what) {
    if(!value) throw std::runtime_error(what);
    std::cout<<"PASS "<<what<<'\n';
}
int wmain() {
    GdiplusStartupInput input;ULONG_PTR token;GdiplusStartup(&token,&input,nullptr);
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    WNDCLASSW wc{};wc.lpfnWndProc=wndProc;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"MintFrameTest";
    RegisterClassW(&wc);
    HWND hwnd=CreateWindowW(wc.lpszClassName,L"",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,100,100,1120,760,nullptr,nullptr,wc.hInstance,nullptr);
    int result=0;
    try {
        RECT frame{},client{};GetWindowRect(hwnd,&frame);GetClientRect(hwnd,&client);
        require(frame.right-frame.left==client.right && frame.bottom-frame.top==client.bottom,"UI fills entire window");
        auto hitAt=[&](int x,int y) {POINT p{x,y};ClientToScreen(hwnd,&p);return SendMessageW(hwnd,WM_NCHITTEST,0,MAKELPARAM(p.x,p.y));};
        require(hitAt(400,15)==HTCAPTION,"top blank area supports native dragging");
        require(hitAt(1,1)==HTTOPLEFT && hitAt(1119,759)==HTBOTTOMRIGHT,"corners support resizing");
        auto render=[&] {Bitmap b((int)width,(int)height);Graphics g(&b);paint(g);};
        auto click=[&](int id) {render();for(const auto& spot:spots) if(spot.id==id) {int x=(int)(spot.box.X+spot.box.Width/2),y=(int)(spot.box.Y+spot.box.Height/2);SendMessageW(hwnd,WM_LBUTTONDOWN,0,MAKELPARAM(x,y));return;}throw std::runtime_error("control missing");};
        require(hitAt(1100,22)==HTCLIENT,"window buttons receive client clicks");
        click(Theme);require(darkTheme && !transparentTheme,"light to dark theme");
        click(Theme);BYTE alpha=0;DWORD flags=0;
        require(transparentTheme && GetLayeredWindowAttributes(hwnd,nullptr,&alpha,&flags) && alpha==170 && flags==LWA_ALPHA,"dark transparent theme uses reduced nonzero opacity");
        require(!(GetWindowLongPtrW(hwnd,GWL_EXSTYLE)&WS_EX_TRANSPARENT) && hitAt(400,400)==HTCLIENT,"blank content receives pointer events without click-through");
        require(hitAt(400,15)==HTCAPTION,"transparent top blank area supports dragging without grip");
        click(Theme);require(!darkTheme && !(GetWindowLongPtrW(hwnd,GWL_EXSTYLE)&WS_EX_LAYERED),"return to fully opaque light theme");
        duration=240000;position=60000;render();
        SendMessageW(hwnd,WM_MOUSEMOVE,0,MAKELPARAM(280,(int)(height-112)));
        for(int i=0;i<20;++i) SendMessageW(hwnd,WM_TIMER,SeekAnimationTick,0);
        require(seekEmphasis==1 && seekHoverTime()==60000 && position==60000,"seek hover animates and previews time without seeking");
        seekHoverX=-100;require(seekHoverTime()==0,"hover time clamps at start");
        seekHoverX=width+100;require(seekHoverTime()==duration,"hover time clamps at end");
        SendMessageW(hwnd,WM_MOUSELEAVE,0,0);
        for(int i=0;i<20;++i) SendMessageW(hwnd,WM_TIMER,SeekAnimationTick,0);
        require(seekEmphasis==0,"seek bar returns to thin idle state");
        duration=position=0;
        searchTracks=Json::array();for(int i=0;i<100;++i) searchTracks.push_back({{"id",std::to_string(i)},{"name","fixture"},{"duration",0}});
        render();require(scrollThumbLength>=64 && scrollMaximum>0,"scroll thumb has a longer minimum length");
        int sx=(int)(width-16),sy=(int)(scrollVisual+scrollThumbLength/2);
        SendMessageW(hwnd,WM_MOUSEMOVE,0,MAKELPARAM(sx,sy));
        for(int i=0;i<24;++i) SendMessageW(hwnd,WM_TIMER,ScrollAnimationTick,0);
        require(scrollEmphasis==1,"scrollbar hover brightens and expands");
        SendMessageW(hwnd,WM_LBUTTONDOWN,0,MAKELPARAM(sx,sy));
        SendMessageW(hwnd,WM_MOUSEMOVE,MK_LBUTTON,MAKELPARAM(sx,(int)(scrollbarBox.GetBottom()+100)));
        SendMessageW(hwnd,WM_LBUTTONUP,0,MAKELPARAM(sx,(int)(scrollbarBox.GetBottom()+100)));
        require(searchScroll==scrollMaximum && !draggingScroll,"long thumb drag reaches last row");
        SendMessageW(hwnd,WM_MOUSELEAVE,0,0);
        for(int i=0;i<30;++i) SendMessageW(hwnd,WM_TIMER,ScrollAnimationTick,0);
        require(scrollEmphasis==0,"scrollbar fades after mouse leaves");
        click(WindowMaximize);require(IsZoomed(hwnd),"maximize button");
        GetWindowRect(hwnd,&frame);MONITORINFO monitor{};monitor.cbSize=sizeof(monitor);GetMonitorInfoW(MonitorFromWindow(hwnd,MONITOR_DEFAULTTONEAREST),&monitor);
        GetClientRect(hwnd,&client);MapWindowPoints(hwnd,nullptr,reinterpret_cast<POINT*>(&client),2);require(EqualRect(&client,&monitor.rcWork),"maximized window respects taskbar work area");
        click(WindowMaximize);require(!IsZoomed(hwnd),"restore button");
        click(WindowMinimize);require(IsIconic(hwnd),"minimize button");
        ShowWindow(hwnd,SW_RESTORE);click(WindowClose);
        MSG msg{};while(PeekMessageW(&msg,nullptr,0,0,PM_REMOVE)) DispatchMessageW(&msg);
        require(!IsWindow(hwnd),"close button destroys window");
    } catch(const std::exception& e) {std::cerr<<"FAIL "<<e.what()<<'\n';result=1;}
    if(IsWindow(hwnd)) DestroyWindow(hwnd);
    CoUninitialize();GdiplusShutdown(token);return result;
}
