const UINT TaskbarButtonCreated=RegisterWindowMessageW(L"TaskbarButtonCreated");
constexpr UINT TaskbarPrevious=0x5200,TaskbarPlayPause=0x5201,TaskbarNext=0x5202;
ITaskbarList3* taskbar=nullptr;
HICON taskbarIcons[4]{};
int taskbarState=-1;
bool taskbarAdded=false;
HICON makeTaskbarIcon(int kind) {
    BITMAPINFO info{};info.bmiHeader.biSize=sizeof(BITMAPINFOHEADER);
    info.bmiHeader.biWidth=32;info.bmiHeader.biHeight=-32;
    info.bmiHeader.biPlanes=1;info.bmiHeader.biBitCount=32;info.bmiHeader.biCompression=BI_RGB;
    void* pixels=nullptr;
    auto color=CreateDIBSection(nullptr,&info,DIB_RGB_COLORS,&pixels,nullptr,0);
    if(!color)return nullptr;
    auto data=static_cast<DWORD*>(pixels);
    for(int y=0;y<32;++y)for(int x=0;x<32;++x) {
        int px=kind==0?31-x:x;
        const bool triangle=y>=7 && y<25 && px>=8 && px<24 && abs(y-16)<=(23-px)/2;
        const bool bar=y>=7 && y<25 && px>=25 && px<28;
        const bool pause=y>=7 && y<25 && ((x>=9 && x<14)||(x>=19 && x<24));
        data[y*32+x]=(kind==2?pause:triangle || (kind!=1 && bar))?0xff909090:0;
    }
    const DWORD maskPixels[32]{};
    auto mask=CreateBitmap(32,32,1,1,maskPixels);
    ICONINFO icon{};icon.fIcon=TRUE;icon.hbmColor=color;icon.hbmMask=mask;
    auto result=mask?CreateIconIndirect(&icon):nullptr;
    if(mask)DeleteObject(mask);DeleteObject(color);return result;
}
void clearTaskbar() {
    if(taskbar){taskbar->Release();taskbar=nullptr;}
    for(auto& icon:taskbarIcons)if(icon){DestroyIcon(icon);icon=nullptr;}
    taskbarAdded=false;taskbarState=-1;
}
void updateTaskbar(HWND hwnd) {
    if(!taskbar)return;
    const auto state=musxi::applicationPlayerState();
    const int playback=state.opened?(state.requestedPlaying && state.phase!="failed"?2:1):0;
    const int value=playback | (state.opened && musxi::applicationHasQueuedTracks()?4:0);
    if(taskbarAdded && value==taskbarState)return;
    THUMBBUTTON buttons[3]{};
    const wchar_t* tips[]{L"上一首",playback==2?L"暂停":L"播放",L"下一首"};
    const int icons[]{0,playback==2?2:1,3};
    for(int i=0;i<3;++i) {
        auto& button=buttons[i];button.dwMask=THB_ICON|THB_TOOLTIP|THB_FLAGS;
        button.iId=TaskbarPrevious+i;button.hIcon=taskbarIcons[icons[i]];
        wcscpy_s(button.szTip,tips[i]);button.dwFlags=(i==1?playback:value&4)?THBF_ENABLED:THBF_DISABLED;
    }
    const auto result=taskbarAdded?taskbar->ThumbBarUpdateButtons(hwnd,3,buttons):taskbar->ThumbBarAddButtons(hwnd,3,buttons);
    if(SUCCEEDED(result)){taskbarAdded=true;taskbarState=value;}
}
void createTaskbar(HWND hwnd) {
    clearTaskbar();
    if(FAILED(CoCreateInstance(CLSID_TaskbarList,nullptr,CLSCTX_INPROC_SERVER,IID_ITaskbarList3,reinterpret_cast<void**>(&taskbar))))return;
    if(FAILED(taskbar->HrInit())){clearTaskbar();return;}
    for(int i=0;i<4;++i)if(!(taskbarIcons[i]=makeTaskbarIcon(i))){clearTaskbar();return;}
    updateTaskbar(hwnd);
}
bool taskbarCommand(HWND hwnd,WPARAM wp) {
    if(HIWORD(wp)!=THBN_CLICKED || LOWORD(wp)<TaskbarPrevious || LOWORD(wp)>TaskbarNext)return false;
    if(musxi::applicationPlayerState().opened && (LOWORD(wp)==TaskbarPlayPause || musxi::applicationHasQueuedTracks())) {
        const char* actions[]{"previous","playPause","next"};
        executeShortcut(actions[LOWORD(wp)-TaskbarPrevious]);
        updateTaskbar(hwnd);
    }
    return true;
}
