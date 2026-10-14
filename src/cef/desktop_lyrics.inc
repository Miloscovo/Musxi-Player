// Host-owned layered window: CEF supplies premultiplied pixels; native owns
// placement, click-through, the right-click panel and preferences. Every state
// change is pushed to subscribed pages instead of being polled.
using DesktopJson=nlohmann::json;
HWND desktopWindow=nullptr;
std::filesystem::path desktopSettingsPath;
bool desktopVisible=false,desktopLocked=false,desktopDual=true,desktopClosing=false;
bool desktopPanel=false,desktopHover=false; // Transient: never persisted.
int desktopFontSize=30;std::string desktopColor="theme";
int desktopX=INT_MIN,desktopY=INT_MIN,desktopWidth=680,desktopHeight=180;
ULONGLONG desktopInsideTick=0;
constexpr UINT_PTR DesktopPointerTimer=102;
HDC desktopDC=nullptr;HBITMAP desktopBitmap=nullptr;HGDIOBJ desktopOldBitmap=nullptr;
void* desktopBits=nullptr;int desktopPixelWidth=0,desktopPixelHeight=0;
void clearDesktopPixels() {
    if(desktopDC && desktopOldBitmap)SelectObject(desktopDC,desktopOldBitmap);
    if(desktopBitmap)DeleteObject(desktopBitmap);
    if(desktopDC)DeleteDC(desktopDC);
    desktopDC=nullptr;desktopBitmap=nullptr;desktopOldBitmap=nullptr;desktopBits=nullptr;
    desktopPixelWidth=desktopPixelHeight=0;
}
std::string desktopStateJson() {
    return DesktopJson{{"enabled",testWindow!=nullptr},{"visible",desktopVisible},{"locked",desktopLocked},{"dual",desktopDual},
        {"panel",desktopPanel},{"hover",desktopHover},{"fontSize",desktopFontSize},{"color",desktopColor}}.dump();
}
void publishDesktop() {musxi::cef_adapter::publishDesktopLyrics(desktopStateJson());}
void saveDesktopSettings() {
    if(desktopSettingsPath.empty())return;
    try {
        if(desktopWindow) {
            RECT r{};GetWindowRect(desktopWindow,&r);const auto dpi=GetDpiForWindow(desktopWindow);
            desktopX=r.left;desktopY=r.top;desktopWidth=MulDiv(r.right-r.left,96,dpi);desktopHeight=MulDiv(r.bottom-r.top,96,dpi);
        }
        const auto data=DesktopJson{{"version",1},{"visible",desktopVisible},{"locked",desktopLocked},{"dual",desktopDual},
            {"fontSize",desktopFontSize},{"color",desktopColor},
            {"x",desktopX},{"y",desktopY},{"width",desktopWidth},{"height",desktopHeight}}.dump();
        auto temporary=desktopSettingsPath;temporary+=L".tmp";
        {std::ofstream file(temporary,std::ios::binary|std::ios::trunc);file<<data;file.close();if(!file)return;}
        MoveFileExW(temporary.c_str(),desktopSettingsPath.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);
    }catch(...) {} // A preference write never interrupts music.
}
bool desktopColorValid(const std::string& color) {return color=="theme" || color=="light" || color=="dark";}
void loadDesktopSettings(const std::filesystem::path& profile) {
    desktopSettingsPath=profile/(smoke?L"desktop-lyrics-smoke.json":L"desktop-lyrics.json");
    if(smoke)return;
    try {
        if(!std::filesystem::is_regular_file(desktopSettingsPath) || std::filesystem::file_size(desktopSettingsPath)>4096)return;
        std::ifstream file(desktopSettingsPath,std::ios::binary);const auto data=DesktopJson::parse(file);
        if(data.at("version")!=1)return;
        desktopVisible=data.at("visible").get<bool>();desktopLocked=data.at("locked").get<bool>();desktopDual=data.at("dual").get<bool>();
        // Font size and color were added later; older files keep the defaults.
        desktopFontSize=std::clamp(data.value("fontSize",30),18,48);
        const auto color=data.value("color",std::string{"theme"});if(desktopColorValid(color))desktopColor=color;
        desktopX=data.at("x").get<int>();desktopY=data.at("y").get<int>();
        if(desktopX!=INT_MIN)desktopX=std::clamp(desktopX,-1000000,1000000);
        if(desktopY!=INT_MIN)desktopY=std::clamp(desktopY,-1000000,1000000);
        desktopWidth=std::clamp(data.at("width").get<int>(),320,1600);desktopHeight=std::clamp(data.at("height").get<int>(),120,500);
    }catch(...) {
        desktopVisible=false;desktopLocked=false;desktopDual=true;desktopFontSize=30;desktopColor="theme";
        desktopX=desktopY=INT_MIN;desktopWidth=680;desktopHeight=180;
    }
}
void placeDesktop(HWND hwnd,RECT rect) {
    MONITORINFO monitor{sizeof(monitor)};
    GetMonitorInfoW(MonitorFromRect(&rect,MONITOR_DEFAULTTONEAREST),&monitor);
    const int width=std::min(rect.right-rect.left,monitor.rcWork.right-monitor.rcWork.left);
    const int height=std::min(rect.bottom-rect.top,monitor.rcWork.bottom-monitor.rcWork.top);
    const int x=std::clamp(rect.left,monitor.rcWork.left,monitor.rcWork.right-width);
    const int y=std::clamp(rect.top,monitor.rcWork.top,monitor.rcWork.bottom-height);
    SetWindowPos(hwnd,HWND_TOPMOST,x,y,width,height,SWP_NOACTIVATE);
}
void setDesktopClickThrough(bool through) {
    const auto style=GetWindowLongPtrW(desktopWindow,GWL_EXSTYLE);
    if(bool(style&WS_EX_TRANSPARENT)==through)return;
    SetWindowLongPtrW(desktopWindow,GWL_EXSTYLE,through?style|WS_EX_TRANSPARENT:style&~WS_EX_TRANSPARENT);
}
void lockDesktopLyrics(bool locked) {
    desktopLocked=locked;if(locked)desktopPanel=false;
    if(desktopWindow)setDesktopClickThrough(locked);
}
// The page draws its unlock button in this logical-pixel box (top centre), see
// .desktop-lyrics-unlock in style.css. Only this box accepts input while locked.
bool desktopUnlockAt(POINT screen) {
    RECT r{};GetWindowRect(desktopWindow,&r);const int dpi=GetDpiForWindow(desktopWindow);
    const int center=(r.left+r.right)/2,half=MulDiv(44,dpi,96);
    return screen.x>=center-half && screen.x<center+half && screen.y>=r.top+MulDiv(4,dpi,96) && screen.y<r.top+MulDiv(42,dpi,96);
}
// Click-through windows receive no mouse input, so the pointer is sampled to
// show the hover state, open the unlock button and auto-hide the panel.
void sampleDesktopPointer() {
    POINT p{};GetCursorPos(&p);RECT r{};GetWindowRect(desktopWindow,&r);
    const bool inside=PtInRect(&r,p)!=FALSE;const auto now=GetTickCount64();
    bool changed=inside!=desktopHover;desktopHover=inside;
    if(inside)desktopInsideTick=now;
    else if(desktopPanel && now-desktopInsideTick>2500){desktopPanel=false;changed=true;}
    if(desktopLocked)setDesktopClickThrough(!(inside && desktopUnlockAt(p)));
    if(changed)publishDesktop();
}
void toggleDesktopPanel() {
    if(desktopLocked)return;
    desktopPanel=!desktopPanel;desktopInsideTick=GetTickCount64();publishDesktop();
}
LRESULT desktopHitTest(HWND hwnd,LPARAM lp) {
    if(desktopLocked)return desktopUnlockAt({GET_X_LPARAM(lp),GET_Y_LPARAM(lp)})?HTCLIENT:HTTRANSPARENT;
    RECT rect{};GetWindowRect(hwnd,&rect);
    const int edge=MulDiv(8,GetDpiForWindow(hwnd),96);
    const int x=GET_X_LPARAM(lp),y=GET_Y_LPARAM(lp);
    const bool left=x<rect.left+edge,right=x>=rect.right-edge,top=y<rect.top+edge,bottom=y>=rect.bottom-edge;
    if(top)return left?HTTOPLEFT:right?HTTOPRIGHT:HTTOP;
    if(bottom)return left?HTBOTTOMLEFT:right?HTBOTTOMRIGHT:HTBOTTOM;
    if(left)return HTLEFT;if(right)return HTRIGHT;
    return hostHitTest(hwnd,lp); // The page marks the lyrics as a drag region.
}
LRESULT CALLBACK desktopProc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp) {
    const auto browser=musxi::cef_adapter::desktopLyricsBrowserHost();
    switch(msg) {
    case WM_MOUSEACTIVATE:return MA_NOACTIVATE;
    case WM_NCCALCSIZE:return 0;
    case WM_NCHITTEST:return desktopHitTest(hwnd,lp);
    case WM_ERASEBKGND:return 1;
    case WM_SETCURSOR:if(LOWORD(lp)==HTCLIENT){SetCursor(LoadCursorW(nullptr,IDC_ARROW));return TRUE;}break;
    case WM_MOUSEMOVE:
        if(browser) {
            TRACKMOUSEEVENT track{sizeof(track),TME_LEAVE,hwnd,0};TrackMouseEvent(&track);
            browser->SendMouseMoveEvent(mouseEvent(hwnd,lp),false);
        }return 0;
    case WM_MOUSELEAVE:if(browser)browser->SendMouseMoveEvent(mouseEvent(hwnd,0),true);return 0;
    case WM_LBUTTONDOWN:case WM_LBUTTONUP:
        if(browser) {
            const bool up=msg==WM_LBUTTONUP;if(!up)SetCapture(hwnd);
            browser->SendMouseClickEvent(mouseEvent(hwnd,lp),MBT_LEFT,up,1);
            if(up)ReleaseCapture();
        }return 0;
    // Right click anywhere toggles the panel; the system caption menu and
    // caption double-click maximize never apply to the floating lyrics.
    case WM_RBUTTONUP:case WM_NCRBUTTONUP:toggleDesktopPanel();return 0;
    case WM_RBUTTONDOWN:case WM_NCRBUTTONDOWN:case WM_CONTEXTMENU:case WM_NCLBUTTONDBLCLK:return 0;
    // The window never takes focus; Windows delivers the wheel to the window under
    // the pointer ("scroll inactive windows"), which the size control relies on.
    case WM_MOUSEWHEEL:
        if(browser && !desktopLocked)browser->SendMouseWheelEvent(mouseEvent(hwnd,lp,true),0,GET_WHEEL_DELTA_WPARAM(wp));
        return 0;
    case WM_CAPTURECHANGED:if(browser)browser->SendCaptureLostEvent();return 0;
    case WM_MOVE:if(browser)browser->NotifyMoveOrResizeStarted();break;
    case WM_SIZE:if(browser)browser->WasResized();return 0;
    case WM_GETMINMAXINFO: {
        const auto dpi=GetDpiForWindow(hwnd);auto info=reinterpret_cast<MINMAXINFO*>(lp);
        info->ptMinTrackSize={MulDiv(320,dpi,96),MulDiv(120,dpi,96)};
        info->ptMaxTrackSize={MulDiv(1600,dpi,96),MulDiv(500,dpi,96)};return 0;
    }
    case WM_DPICHANGED:
        placeDesktop(hwnd,*reinterpret_cast<RECT*>(lp));
        if(browser){browser->NotifyScreenInfoChanged();browser->WasResized();}return 0;
    case WM_DISPLAYCHANGE: {RECT rect{};GetWindowRect(hwnd,&rect);placeDesktop(hwnd,rect);saveDesktopSettings();return 0;}
    case WM_ENTERSIZEMOVE:SetTimer(hwnd,101,16,nullptr);return 0;
    case WM_TIMER:
        if(wp==101){CefDoMessageLoopWork();return 0;}
        if(wp==DesktopPointerTimer){sampleDesktopPointer();return 0;}
        break;
    case WM_EXITSIZEMOVE:KillTimer(hwnd,101);saveDesktopSettings();return 0;
    case WM_CLOSE:
        if(musxi::cef_adapter::desktopLyricsBrowserReadyToClose())DestroyWindow(hwnd);
        else {desktopVisible=false;desktopClosing=true;saveDesktopSettings();musxi::cef_adapter::closeDesktopLyricsBrowser();publishDesktop();}
        return 0;
    case WM_DESTROY:KillTimer(hwnd,DesktopPointerTimer);return 0;
    }
    return DefWindowProcW(hwnd,msg,wp,lp);
}
bool showDesktopLyrics() {
    if(hostClosing || desktopClosing)return false;
    if(desktopLocked && !addTray(testWindow))desktopLocked=false;
    if(desktopWindow){ShowWindow(desktopWindow,SW_SHOWNOACTIVATE);return true;}
    WNDCLASSW wc{};wc.style=CS_DBLCLKS;wc.lpfnWndProc=desktopProc;wc.hInstance=GetModuleHandleW(nullptr);
    wc.lpszClassName=L"MusxiDesktopLyrics";wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);RegisterClassW(&wc);
    MONITORINFO monitor{sizeof(monitor)};
    POINT point{desktopX==INT_MIN?0:desktopX,desktopY==INT_MIN?0:desktopY};
    GetMonitorInfoW(MonitorFromPoint(point,MONITOR_DEFAULTTONEAREST),&monitor);
    const int dpi=GetDpiForWindow(testWindow),width=MulDiv(desktopWidth,dpi,96),height=MulDiv(desktopHeight,dpi,96);
    const int x=desktopX==INT_MIN?monitor.rcWork.left+(monitor.rcWork.right-monitor.rcWork.left-width)/2:desktopX;
    const int y=desktopY==INT_MIN?monitor.rcWork.bottom-height-MulDiv(48,dpi,96):desktopY;
    desktopWindow=CreateWindowExW(WS_EX_LAYERED|WS_EX_TOPMOST|WS_EX_TOOLWINDOW|WS_EX_NOACTIVATE|(desktopLocked?WS_EX_TRANSPARENT:0),
        wc.lpszClassName,L"Musxi Player 桌面歌词",WS_POPUP|WS_THICKFRAME,x,y,width,height,nullptr,nullptr,wc.hInstance,nullptr);
    if(!desktopWindow)return false;
    const int actualDpi=GetDpiForWindow(desktopWindow);
    placeDesktop(desktopWindow,{x,y,x+MulDiv(desktopWidth,actualDpi,96),y+MulDiv(desktopHeight,actualDpi,96)});
    CefWindowInfo info;info.SetAsWindowless(desktopWindow);info.runtime_style=CEF_RUNTIME_STYLE_ALLOY;
    CefBrowserSettings settings;settings.background_color=0;settings.windowless_frame_rate=30;
    const auto url=mainUrl.substr(0,mainUrl.find('?'))+(desktopSmoke?"?desktopLyrics=1&desktopSmoke=1":"?desktopLyrics=1");
    const auto browser=CefBrowserHost::CreateBrowserSync(info,musxi::cef_adapter::makeDesktopLyricsClient(url,nativeWindow,desktopWindow),url,settings,nullptr,nullptr);
    if(!browser){DestroyWindow(desktopWindow);desktopWindow=nullptr;return false;}
    desktopPanel=false;desktopHover=false;
    SetTimer(desktopWindow,DesktopPointerTimer,80,nullptr);
    ShowWindow(desktopWindow,SW_SHOWNOACTIVATE);return true;
}
void paintDesktopLyrics(const void* pixels,int width,int height) {
    if(!desktopWindow || width<=0 || height<=0 || width>16384 || height>16384)return;
    RECT rect{};GetWindowRect(desktopWindow,&rect);const int w=rect.right-rect.left,h=rect.bottom-rect.top;
    const int slack=MulDiv(2,GetDpiForWindow(desktopWindow),96);
    if(std::abs(width-w)>slack || std::abs(height-h)>slack)return;
    if(w!=desktopPixelWidth || h!=desktopPixelHeight) {
        clearDesktopPixels();desktopDC=CreateCompatibleDC(nullptr);
        BITMAPINFO info{};info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);info.bmiHeader.biWidth=w;info.bmiHeader.biHeight=-h;
        info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;info.bmiHeader.biCompression=BI_RGB;
        desktopBitmap=CreateDIBSection(desktopDC,&info,DIB_RGB_COLORS,&desktopBits,nullptr,0);
        if(!desktopDC || !desktopBitmap){clearDesktopPixels();PostMessageW(desktopWindow,WM_CLOSE,0,0);return;}
        desktopOldBitmap=SelectObject(desktopDC,desktopBitmap);desktopPixelWidth=w;desktopPixelHeight=h;
    }
    std::memset(desktopBits,0,static_cast<std::size_t>(w)*h*4);
    for(int y=0;y<std::min(h,height);++y)std::memcpy(static_cast<unsigned char*>(desktopBits)+static_cast<std::size_t>(y)*w*4,
        static_cast<const unsigned char*>(pixels)+static_cast<std::size_t>(y)*width*4,static_cast<std::size_t>(std::min(w,width))*4);
    POINT origin{rect.left,rect.top},source{};SIZE size{w,h};BLENDFUNCTION blend{AC_SRC_OVER,0,255,AC_SRC_ALPHA};
    if(!UpdateLayeredWindow(desktopWindow,nullptr,&origin,&size,desktopDC,&source,0,&blend,ULW_ALPHA))
        PostMessageW(desktopWindow,WM_CLOSE,0,0);
}
