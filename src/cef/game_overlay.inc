struct GameOverlayWindow {
    const char* kind;const wchar_t* title;
    HWND window=nullptr;int x=INT_MIN,y=INT_MIN,width,height;
    HDC dc=nullptr;HBITMAP bitmap=nullptr;HGDIOBJ previous=nullptr;void* pixels=nullptr;int pixelWidth=0,pixelHeight=0;
    HMONITOR frameMonitor=nullptr;
    bool hoverHidden=false,moving=false,resizeHolding=false,resizePending=false,hoverEditing=false,hoverSuppressed=false;
    RECT resizeView{};
    std::vector<std::uint8_t> resizePixels;
    int resizePixelWidth=0,resizePixelHeight=0;
};
GameOverlayWindow gameLyrics{"lyrics",L"Musxi Player 歌词悬浮组件",nullptr,INT_MIN,INT_MIN,520,480};
GameOverlayWindow gameTrack{"track",L"Musxi Player 歌曲信息",nullptr,INT_MIN,INT_MIN,360,120};
bool gameOverlayEnabled=true,gameOverlayVisible=false,gameLyricsVisible=true,gameTrackVisible=true;
bool gameOverlayEditing=false;
bool gameOverlayHideOnHover=false,gameOverlayTopmost=true;
bool floatingLyricsPanel=false,floatingLyricsHover=false;
std::string floatingLyricsMode="multi";
int floatingLyricsFontSize=0; // Resolved from the existing lyrics-page preference on first UI connection.
ULONGLONG gameOverlayPointerCheck=0;
HWND gameOverlayEditReturnWindow=nullptr;
bool gameOverlayEditFocused=false;
HWND gameOverlayForeground=nullptr;
ULONGLONG gameOverlayOrderCheck=0;
std::filesystem::path gameOverlaySettingsPath;
bool windowCompatibility=false;
std::string windowCompatibilityStatus="off",windowCompatibilityTarget;
std::string gameOverlayStateJson() {
    return DesktopJson{{"available",testWindow!=nullptr},{"enabled",gameOverlayEnabled},{"visible",gameOverlayVisible},
        {"lyricsVisible",gameLyricsVisible},{"trackVisible",gameTrackVisible},{"editing",gameOverlayEditing},{"lyricsEditing",gameOverlayEditing || gameLyrics.hoverEditing},{"trackEditing",gameOverlayEditing || gameTrack.hoverEditing},{"hideOnHover",gameOverlayHideOnHover},{"topmost",gameOverlayTopmost},{"lyricsMode",floatingLyricsMode},{"lyricsFontSize",floatingLyricsFontSize},
        {"minecraftCompatibility",false},{"minecraftStatus","off"},{"windowCompatibility",windowCompatibility},
        {"windowCompatibilityStatus",windowCompatibilityStatus},{"windowCompatibilityTarget",windowCompatibilityTarget}}.dump();
}
std::string desktopStateJson() {
    return DesktopJson{{"enabled",testWindow!=nullptr},{"visible",gameOverlayEnabled && gameOverlayVisible && gameLyricsVisible},
        {"locked",!(gameOverlayEditing || gameLyrics.hoverEditing)},{"dual",desktopDual},{"panel",floatingLyricsPanel},{"hover",floatingLyricsHover},
        {"fontSize",floatingLyricsFontSize?floatingLyricsFontSize:20},{"color","theme"}}.dump();
}
void publishDesktop() {musxi::cef_adapter::publishDesktopLyrics(desktopStateJson());}
void publishGameOverlayState() {musxi::cef_adapter::publishGameOverlay(gameOverlayStateJson());publishDesktop();}
#include "window_compatibility.inc"
void clearGameOverlayPixels(GameOverlayWindow& item) {
    if(item.dc && item.previous)SelectObject(item.dc,item.previous);
    if(item.bitmap)DeleteObject(item.bitmap);if(item.dc)DeleteDC(item.dc);
    item.dc=nullptr;item.bitmap=nullptr;item.previous=nullptr;item.pixels=nullptr;item.pixelWidth=item.pixelHeight=0;
}
void saveGameOverlaySettings() {
    if(gameOverlaySettingsPath.empty())return;
    try {
        DesktopJson data{{"version",2},{"enabled",gameOverlayEnabled},{"visible",gameOverlayVisible},
            {"lyricsVisible",gameLyricsVisible},{"trackVisible",gameTrackVisible},{"hideOnHover",gameOverlayHideOnHover},
            {"topmost",gameOverlayTopmost},{"lyricsMode",floatingLyricsMode},{"lyricsFontSize",floatingLyricsFontSize}};
        for(auto* item:{&gameLyrics,&gameTrack}) {
            if(item->window) {
                RECT r{};GetWindowRect(item->window,&r);const int dpi=GetDpiForWindow(item->window);
                item->x=r.left;item->y=r.top;item->width=MulDiv(r.right-r.left,96,dpi);item->height=MulDiv(r.bottom-r.top,96,dpi);
            }
            data[item->kind]={{"x",item->x},{"y",item->y},{"width",item->width},{"height",item->height}};
        }
        auto temp=gameOverlaySettingsPath;temp+=L".tmp";
        {std::ofstream file(temp,std::ios::binary|std::ios::trunc);file<<data.dump();file.close();if(!file)return;}
        MoveFileExW(temp.c_str(),gameOverlaySettingsPath.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);
    }catch(...) {} // Preferences must never interrupt playback.
}
void loadGameOverlaySettings(const std::filesystem::path& profile) {
    gameOverlaySettingsPath=profile/(smoke?L"game-overlay-smoke.json":L"game-overlay.json");
    bool unified=false,oldLyricsVisible=false;
    if(!smoke)try {
        if(std::filesystem::is_regular_file(gameOverlaySettingsPath) && std::filesystem::file_size(gameOverlaySettingsPath)<=4096) {
            std::ifstream file(gameOverlaySettingsPath,std::ios::binary);const auto data=DesktopJson::parse(file);
            const int version=data.at("version").get<int>();if(version!=1 && version!=2)throw std::runtime_error("Invalid overlay preferences");
            const bool enabled=data.at("enabled").get<bool>(),visible=data.at("visible").get<bool>();
            const bool lyrics=data.at("lyricsVisible").get<bool>(),track=data.at("trackVisible").get<bool>();
            const bool hide=data.value("hideOnHover",false),topmost=data.value("topmost",true);
            const auto mode=data.value("lyricsMode",std::string{"multi"});const int size=data.value("lyricsFontSize",0);
            if((mode!="single" && mode!="multi") || (size!=0 && (size<16 || size>48)))throw std::runtime_error("Invalid lyrics preferences");
            // Parse everything before publishing migrated preferences.
            auto lyricsLayout=gameLyrics,trackLayout=gameTrack;
            for(auto* item:{&lyricsLayout,&trackLayout}) {
                const auto& value=data.at(item->kind);const int x=value.at("x").get<int>(),y=value.at("y").get<int>();
                item->x=x==INT_MIN?x:std::clamp(x,-1000000,1000000);item->y=y==INT_MIN?y:std::clamp(y,-1000000,1000000);
                item->width=std::clamp(value.at("width").get<int>(),300,1600);
                item->height=item==&lyricsLayout?std::clamp(value.at("height").get<int>(),120,1000):120;
            }
            gameLyrics=lyricsLayout;gameTrack=trackLayout;unified=version==2;
            oldLyricsVisible=enabled && visible && lyrics;
            gameOverlayVisible=unified?visible:(enabled && visible);
            gameLyricsVisible=lyrics;gameTrackVisible=track;
            gameOverlayHideOnHover=hide;gameOverlayTopmost=topmost;floatingLyricsMode=mode;floatingLyricsFontSize=size;
        }
    }catch(...) {} // Invalid preferences keep safe, hidden defaults.
    if(!unified && desktopVisible && !oldLyricsVisible) {
        gameLyrics.x=desktopX;gameLyrics.y=desktopY;gameLyrics.width=desktopWidth;gameLyrics.height=desktopHeight;
        floatingLyricsMode="single";floatingLyricsFontSize=desktopFontSize;
        if(!gameOverlayVisible)gameTrackVisible=false;
        gameOverlayVisible=true;gameLyricsVisible=true;
    }
    // Keep the legacy profile intact; version 2 makes this migration idempotent.
    saveGameOverlaySettings();
}
bool focusGameOverlayWindow(HWND window) {
    SetForegroundWindow(window);
    if(GetForegroundWindow()!=window) {
        const auto foreground=GetForegroundWindow();
        const auto thread=foreground?GetWindowThreadProcessId(foreground,nullptr):0;
        const auto current=GetCurrentThreadId();
        // Share input only for the explicit activation, then immediately detach.
        if(thread && thread!=current && AttachThreadInput(current,thread,TRUE)) {
            SetForegroundWindow(window);
            if(GetForegroundWindow()==window)SetFocus(window);
            AttachThreadInput(current,thread,FALSE);
        }
    }
    if(GetForegroundWindow()!=window)return false;
    SetFocus(window);return GetFocus()==window;
}
bool syncGameOverlayWindows();
bool gameOverlayWindowEditing(const GameOverlayWindow& item) {
    return gameOverlayEditing || item.hoverEditing;
}
void updateGameLyricsFrameRate(GameOverlayWindow& item,bool force=false) {
    if(&item!=&gameLyrics || !item.window)return;
    const auto browser=musxi::cef_adapter::gameOverlayBrowserHost(item.window);if(!browser)return;
    const auto monitor=MonitorFromWindow(item.window,MONITOR_DEFAULTTONEAREST);
    if(!force && monitor==item.frameMonitor)return;
    item.frameMonitor=monitor;
    const auto rate=monitorFrameRate(monitor);
    if(browser->GetWindowlessFrameRate()!=rate)browser->SetWindowlessFrameRate(rate);
}
void scheduleGameOverlayResize(GameOverlayWindow& item) {
    if(item.window && !item.resizePending)item.resizePending=PostMessageW(item.window,ResizeBrowserMessage,0,0)!=FALSE;
}
LRESULT CALLBACK gameOverlayProc(HWND hwnd,UINT msg,WPARAM wp,LPARAM lp) {
    auto* item=reinterpret_cast<GameOverlayWindow*>(GetWindowLongPtrW(hwnd,GWLP_USERDATA));
    if(msg==WM_NCCREATE) {
        item=static_cast<GameOverlayWindow*>(reinterpret_cast<CREATESTRUCTW*>(lp)->lpCreateParams);
        SetWindowLongPtrW(hwnd,GWLP_USERDATA,reinterpret_cast<LONG_PTR>(item));
    }
    if(!item)return DefWindowProcW(hwnd,msg,wp,lp);
    const auto browser=musxi::cef_adapter::gameOverlayBrowserHost(hwnd);
    const bool editing=gameOverlayWindowEditing(*item);
    switch(msg) {
    case WM_SYSCOMMAND:
        if((wp&0xfff0)==SC_SIZE || (wp&0xfff0)==SC_MOVE) {
            CefScopedSetNestableTasksAllowed allowTasks;
            return DefWindowProcW(hwnd,msg,wp,lp);
        }
        break;
    case WM_MOUSEACTIVATE:return editing?MA_ACTIVATE:MA_NOACTIVATE;
    case WM_SETFOCUS:if(browser)browser->SetFocus(true);return 0;
    case WM_KILLFOCUS:if(browser)browser->SetFocus(false);return 0;
    case WM_KEYDOWN:case WM_SYSKEYDOWN:case WM_KEYUP:case WM_SYSKEYUP:case WM_CHAR:case WM_SYSCHAR:
        if(browser && editing) {
            CefKeyEvent key;key.windows_key_code=static_cast<int>(wp);key.native_key_code=static_cast<int>(lp);key.modifiers=modifiers();
            key.is_system_key=msg==WM_SYSKEYDOWN || msg==WM_SYSKEYUP || msg==WM_SYSCHAR;
            key.type=(msg==WM_CHAR || msg==WM_SYSCHAR)?KEYEVENT_CHAR:(msg==WM_KEYUP || msg==WM_SYSKEYUP)?KEYEVENT_KEYUP:KEYEVENT_RAWKEYDOWN;
            if(key.type==KEYEVENT_CHAR)key.character=key.unmodified_character=static_cast<char16_t>(wp);
            browser->SendKeyEvent(key);
        }
        return 0;
    case WM_NCCALCSIZE:case WM_NCPAINT:return 0;
    case WM_ERASEBKGND:return 1;
    case WM_NCHITTEST: {
        if(!editing)return HTTRANSPARENT;
        const auto hit=hostHitTest(hwnd,lp);
        if(item==&gameTrack) {
            if(hit==HTTOPLEFT || hit==HTBOTTOMLEFT)return HTLEFT;
            if(hit==HTTOPRIGHT || hit==HTBOTTOMRIGHT)return HTRIGHT;
            if(hit==HTTOP || hit==HTBOTTOM)return HTCAPTION;
        }
        return hit;
    }
    case WM_SETCURSOR:if(LOWORD(lp)==HTCLIENT){SetCursor(LoadCursorW(nullptr,IDC_ARROW));return TRUE;}break;
    case WM_MOUSEMOVE:
        if(browser && editing){TRACKMOUSEEVENT track{sizeof(track),TME_LEAVE,hwnd,0};TrackMouseEvent(&track);browser->SendMouseMoveEvent(mouseEvent(hwnd,lp),false);}return 0;
    case WM_MOUSELEAVE:if(browser)browser->SendMouseMoveEvent(mouseEvent(hwnd,0),true);return 0;
    case WM_LBUTTONDOWN:case WM_LBUTTONUP:
        if(browser && editing){const bool up=msg==WM_LBUTTONUP;if(!up){SetFocus(hwnd);SetCapture(hwnd);}browser->SendMouseClickEvent(mouseEvent(hwnd,lp),MBT_LEFT,up,1);if(up)ReleaseCapture();}return 0;
    case WM_MOUSEWHEEL:if(browser && editing)browser->SendMouseWheelEvent(mouseEvent(hwnd,lp,true),0,GET_WHEEL_DELTA_WPARAM(wp));return 0;
    case WM_CAPTURECHANGED:if(browser)browser->SendCaptureLostEvent();return 0;
    case WM_RBUTTONUP:case WM_NCRBUTTONUP:case WM_RBUTTONDOWN:case WM_NCRBUTTONDOWN:case WM_NCLBUTTONDBLCLK:case WM_CONTEXTMENU:return 0;
    case WM_MOVE:if(browser){updateGameLyricsFrameRate(*item);browser->NotifyMoveOrResizeStarted();}break;
    case WM_SIZE:
        if(GetPropW(hwnd,musxi::cef_adapter::ResizeViewProperty)) {
            if(!item->resizePixels.empty())musxi::cef_adapter::presentGameOverlay(hwnd,item->resizePixels.data(),item->resizePixelWidth,item->resizePixelHeight);
        }else scheduleGameOverlayResize(*item);
        return 0;
    case ResizeBrowserMessage:
        item->resizePending=false;
        if(browser && IsWindowVisible(hwnd) && !IsIconic(hwnd) && !GetPropW(hwnd,musxi::cef_adapter::ResizeViewProperty)) {
            SetTimer(hwnd,102,50,nullptr);browser->WasResized();browser->Invalidate(PET_VIEW);
        }
        return 0;
    case WM_GETMINMAXINFO: {
        const int dpi=GetDpiForWindow(hwnd);auto* info=reinterpret_cast<MINMAXINFO*>(lp);
        info->ptMinTrackSize={MulDiv(300,dpi,96),MulDiv(item==&gameLyrics?120:80,dpi,96)};
        info->ptMaxTrackSize={MulDiv(1600,dpi,96),MulDiv(1000,dpi,96)};return 0;
    }
    case WM_DPICHANGED:placeDesktop(hwnd,*reinterpret_cast<RECT*>(lp));if(browser){updateGameLyricsFrameRate(*item,true);browser->NotifyScreenInfoChanged();browser->WasResized();}saveGameOverlaySettings();return 0;
    case WM_DISPLAYCHANGE:{RECT rect{};GetWindowRect(hwnd,&rect);placeDesktop(hwnd,rect);updateGameLyricsFrameRate(*item,true);saveGameOverlaySettings();return 0;}
    case WM_ENTERSIZEMOVE: {
        item->moving=true;item->resizeHolding=true;KillTimer(hwnd,102);
        GetClientRect(hwnd,&item->resizeView);const int dpi=GetDpiForWindow(hwnd);
        item->resizeView.right=std::max(1,MulDiv(item->resizeView.right,96,dpi));
        item->resizeView.bottom=std::max(1,MulDiv(item->resizeView.bottom,96,dpi));
        SetPropW(hwnd,musxi::cef_adapter::ResizeViewProperty,&item->resizeView);
        if(item->pixels) {
            item->resizePixelWidth=item->pixelWidth;item->resizePixelHeight=item->pixelHeight;
            const auto bytes=static_cast<std::size_t>(item->pixelWidth)*item->pixelHeight*4;
            item->resizePixels.resize(bytes);std::memcpy(item->resizePixels.data(),item->pixels,bytes);
        }
        SetTimer(hwnd,101,16,nullptr);return 0;
    }
    case WM_TIMER:if(wp==101){CefDoMessageLoopWork();return 0;}if(wp==102){if(browser)browser->Invalidate(PET_VIEW);else KillTimer(hwnd,102);return 0;}break;
    case WM_EXITSIZEMOVE:RemovePropW(hwnd,musxi::cef_adapter::ResizeViewProperty);item->moving=false;KillTimer(hwnd,101);scheduleGameOverlayResize(*item);saveGameOverlaySettings();return 0;
    case WM_CLOSE:
        if(item==&gameLyrics)gameLyricsVisible=false;else gameTrackVisible=false;
        syncGameOverlayWindows();saveGameOverlaySettings();publishGameOverlayState();return 0;
    case WM_DESTROY:RemovePropW(hwnd,musxi::cef_adapter::ResizeViewProperty);item->moving=false;item->resizePixels.clear();KillTimer(hwnd,101);KillTimer(hwnd,102);return 0;
    }
    return DefWindowProcW(hwnd,msg,wp,lp);
}
bool createGameOverlayWindow(GameOverlayWindow& item) {
    if(item.window)return true;
    WNDCLASSW wc{};wc.style=CS_DBLCLKS;wc.lpfnWndProc=gameOverlayProc;wc.hInstance=GetModuleHandleW(nullptr);
    wc.lpszClassName=L"MusxiGameOverlay";wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);RegisterClassW(&wc);
    POINT anchor{};GetCursorPos(&anchor);
    if(item.x!=INT_MIN && item.y!=INT_MIN)anchor={item.x,item.y};
    MONITORINFO monitor{sizeof(monitor)};if(!GetMonitorInfoW(MonitorFromPoint(anchor,MONITOR_DEFAULTTONEAREST),&monitor))return false;
    const int dpi=GetDpiForWindow(testWindow),width=MulDiv(item.width,dpi,96),height=MulDiv(item.height,dpi,96);
    const int x=item.x==INT_MIN?monitor.rcWork.left+MulDiv(&item==&gameLyrics?28:580,dpi,96):item.x;
    const int y=item.y==INT_MIN?monitor.rcWork.top+MulDiv(80,dpi,96):item.y;
    item.window=CreateWindowExW(WS_EX_LAYERED|(gameOverlayTopmost?WS_EX_TOPMOST:0)|WS_EX_TOOLWINDOW|(gameOverlayWindowEditing(item)?0:WS_EX_NOACTIVATE|WS_EX_TRANSPARENT),
        wc.lpszClassName,item.title,WS_POPUP|WS_THICKFRAME,x,y,width,height,nullptr,nullptr,wc.hInstance,&item);
    if(!item.window)return false;
    const int actualDpi=GetDpiForWindow(item.window);
    placeDesktop(item.window,{x,y,x+MulDiv(item.width,actualDpi,96),y+MulDiv(item.height,actualDpi,96)});
    CefWindowInfo info;info.SetAsWindowless(item.window);info.runtime_style=CEF_RUNTIME_STYLE_ALLOY;
    CefBrowserSettings settings;settings.background_color=0;
    settings.windowless_frame_rate=&item==&gameLyrics?monitorFrameRate(MonitorFromWindow(item.window,MONITOR_DEFAULTTONEAREST)):30;
    const auto url=mainUrl.substr(0,mainUrl.find('?'))+"?gameOverlay="+item.kind+(desktopSmoke && &item==&gameLyrics?"&desktopSmoke=1":"");
    if(!CefBrowserHost::CreateBrowserSync(info,musxi::cef_adapter::makeGameOverlayClient(url,nativeWindow,item.window),url,settings,nullptr,nullptr)) {
        DestroyWindow(item.window);item.window=nullptr;return false;
    }
    updateGameLyricsFrameRate(item,true);return true;
}
bool raiseGameOverlayWindows() {
    if(!gameOverlayEnabled || !gameOverlayVisible)return true;
    bool ok=true;
    for(auto* item:{&gameLyrics,&gameTrack})if(item->window && IsWindowVisible(item->window))
        if(!SetWindowPos(item->window,gameOverlayTopmost?HWND_TOPMOST:HWND_NOTOPMOST,0,0,0,0,SWP_NOMOVE|SWP_NOSIZE|SWP_NOACTIVATE))ok=false;
    return ok;
}
void refreshGameOverlayOrder() {
    if(!gameOverlayEnabled || !gameOverlayVisible || !gameOverlayTopmost)return;
    const auto foreground=GetForegroundWindow();
    const auto now=GetTickCount64();
    if(foreground==gameOverlayForeground && now<gameOverlayOrderCheck)return;
    gameOverlayForeground=foreground;gameOverlayOrderCheck=now+250;
    DWORD owner=0;
    if(!foreground || !GetWindowThreadProcessId(foreground,&owner) || owner==GetCurrentProcessId())return;
    bool lyricsAbove=!gameLyrics.window || !IsWindowVisible(gameLyrics.window);
    bool trackAbove=!gameTrack.window || !IsWindowVisible(gameTrack.window);
    for(auto window=GetWindow(foreground,GW_HWNDPREV);window && (!lyricsAbove || !trackAbove);window=GetWindow(window,GW_HWNDPREV)) {
        if(window==gameLyrics.window)lyricsAbove=true;
        if(window==gameTrack.window)trackAbove=true;
    }
    if(!lyricsAbove || !trackAbove)raiseGameOverlayWindows();
}
bool updateGameOverlayHover() {
    CURSORINFO cursor{sizeof(cursor)};DWORD process=0;
    const auto foreground=GetForegroundWindow();
    const bool active=gameOverlayHideOnHover && gameOverlayEnabled && gameOverlayVisible && !gameOverlayEditing && !gameLyrics.hoverEditing && !gameTrack.hoverEditing
        && foreground && GetWindowThreadProcessId(foreground,&process) && process!=GetCurrentProcessId()
        && GetCursorInfo(&cursor) && (cursor.flags&CURSOR_SHOWING);
    bool changed=false;
    for(auto* item:{&gameLyrics,&gameTrack}) {
        const bool hidden=active && (item==&gameLyrics?gameLyricsVisible:gameTrackVisible)
            && item->window;
        if(item->hoverHidden!=hidden){item->hoverHidden=hidden;changed=true;}
    }
    return changed;
}
bool syncGameOverlayWindows() {
    bool ok=true;
    if(!gameOverlayEnabled || !gameOverlayVisible || !gameLyricsVisible)gameLyrics.hoverEditing=false;
    if(!gameOverlayEnabled || !gameOverlayVisible || !gameTrackVisible)gameTrack.hoverEditing=false;
    floatingLyricsPanel=(gameOverlayEditing || gameLyrics.hoverEditing) && floatingLyricsHover;
    updateGameOverlayHover();
    const auto foreground=GetForegroundWindow();
    const bool ownedFocus=foreground && (foreground==gameLyrics.window || foreground==gameTrack.window);
    HWND editWindow=nullptr;
    for(auto* item:{&gameLyrics,&gameTrack}) {
        const bool visible=gameOverlayEnabled && gameOverlayVisible && (item==&gameLyrics?gameLyricsVisible:gameTrackVisible) && !item->hoverHidden;
        if(visible && !createGameOverlayWindow(*item)){ok=false;continue;}
        if(!item->window)continue;
        const bool editing=gameOverlayWindowEditing(*item);
        auto style=GetWindowLongPtrW(item->window,GWL_EXSTYLE);
        SetWindowLongPtrW(item->window,GWL_EXSTYLE,editing?style&~(WS_EX_TRANSPARENT|WS_EX_NOACTIVATE):style|WS_EX_TRANSPARENT|WS_EX_NOACTIVATE);
        if(!editing && GetCapture()==item->window)ReleaseCapture();
        if(auto browser=musxi::cef_adapter::gameOverlayBrowserHost(item->window))browser->WasHidden(!visible);
        ShowWindow(item->window,visible?SW_SHOWNOACTIVATE:SW_HIDE);
        if(!visible)KillTimer(item->window,102);
        else if(item->resizeHolding && !item->moving)scheduleGameOverlayResize(*item);
        if(visible && editing && (!editWindow || item->window==foreground))editWindow=item->window;
    }
    if(editWindow) {
        if(!gameOverlayEditFocused || (ownedFocus && foreground!=editWindow)) {
            if(!focusGameOverlayWindow(editWindow))return false;
            if(!gameOverlayEditFocused)gameOverlayEditReturnWindow=foreground;
            gameOverlayEditFocused=true;
        }
    } else if(gameOverlayEditFocused) {
        if(ownedFocus && IsWindow(gameOverlayEditReturnWindow) && IsWindowVisible(gameOverlayEditReturnWindow))
            focusGameOverlayWindow(gameOverlayEditReturnWindow);
        gameOverlayEditReturnWindow=nullptr;gameOverlayEditFocused=false;
    }
    publishDesktop();return raiseGameOverlayWindows() && ok;
}
void sampleGameOverlayPointer() {
    const auto now=GetTickCount64();if(now<gameOverlayPointerCheck)return;
    gameOverlayPointerCheck=now+80;
    if(updateGameOverlayHover())syncGameOverlayWindows();
    POINT point{};CURSORINFO cursor{sizeof(cursor)};
    const bool cursorVisible=GetCursorInfo(&cursor) && (cursor.flags&CURSOR_SHOWING) && GetCursorPos(&point);
    const bool oldHover=floatingLyricsHover;
    const bool interacting=gameLyrics.moving || gameTrack.moving || GetCapture()==gameLyrics.window && gameLyrics.window || GetCapture()==gameTrack.window && gameTrack.window;
    bool editChanged=false;
    for(auto* item:{&gameLyrics,&gameTrack}) {
        RECT rect{};
        const bool inside=item->window && IsWindowVisible(item->window) && cursorVisible && GetWindowRect(item->window,&rect) && PtInRect(&rect,point);
        if(item==&gameLyrics)floatingLyricsHover=inside;
        if(!inside)item->hoverSuppressed=false;
        const bool ownedFocus=item->window && GetForegroundWindow()==item->window;
        if(item->hoverEditing && !interacting && (!inside || !ownedFocus || hostClosing)) {
            item->hoverEditing=false;item->hoverSuppressed=inside;editChanged=true;
        }else if(inside && !item->hoverEditing && !interacting && !gameOverlayEditing && !item->hoverSuppressed && !hostClosing) {
            // A covered widget must not steal the foreground window's focus.
            const auto hit=GetAncestor(WindowFromPoint(point),GA_ROOT);
            bool covered=false;
            for(auto window=GetWindow(item->window,GW_HWNDPREV);window;window=GetWindow(window,GW_HWNDPREV)) {
                RECT above{};
                // Locked widgets are input-transparent, but still cover each other visually.
                const bool widgetAbove=(window==gameLyrics.window || window==gameTrack.window) && IsWindowVisible(window)
                    && GetWindowRect(window,&above) && PtInRect(&above,point);
                if(window==hit || widgetAbove){covered=true;break;}
            }
            if(!covered){item->hoverEditing=true;editChanged=true;}
        }
    }
    const bool panel=(gameOverlayEditing || gameLyrics.hoverEditing) && (floatingLyricsHover || gameLyrics.moving || gameLyrics.window && GetCapture()==gameLyrics.window);
    const bool panelChanged=floatingLyricsPanel!=panel;floatingLyricsPanel=panel;
    if(editChanged) {
        if(!syncGameOverlayWindows()) {
            for(auto* item:{&gameLyrics,&gameTrack}){item->hoverEditing=false;item->hoverSuppressed=true;}
            syncGameOverlayWindows();
        }
        publishGameOverlayState();
    }else if(oldHover!=floatingLyricsHover || panelChanged)publishDesktop();
}
musxi::LibraryReply executeGameOverlayShortcut(const std::string& action) {
    if(!gameOverlayEnabled)return {409,"请先在设置中开启游戏悬浮",{}};
    if(action=="overlayVisibility")return musxi::cef_adapter::gameOverlayCommand("gameOverlay.setVisible",DesktopJson{{"visible",!gameOverlayVisible}}.dump());
    if(action=="overlayEditing")return musxi::cef_adapter::gameOverlayCommand("gameOverlay.setEditing",DesktopJson{{"editing",!(gameOverlayEditing || gameLyrics.hoverEditing || gameTrack.hoverEditing)}}.dump());
    return {400,"无效游戏悬浮操作",{}};
}
