// Native integration checks: real Windows MCI playback with silent PCM fixtures.
#include "../src/main.cpp"
#include <iostream>
#include <stdexcept>

void check(bool value, const char* description) {
    if (!value) throw std::runtime_error(description);
    std::cout << "PASS " << description << '\n';
}
void wav(const fs::path& path, unsigned seconds) {
    std::ofstream out(path, std::ios::binary);
    auto u16 = [&](unsigned v) { out.put(char(v)); out.put(char(v >> 8)); };
    auto u32 = [&](unsigned v) { for(int i=0;i<4;++i) out.put(char(v >> (8*i))); };
    unsigned bytes=22050*2*seconds;
    out.write("RIFF",4);u32(36+bytes);out.write("WAVEfmt ",8);u32(16);u16(1);u16(1);
    u32(22050);u32(44100);u16(2);u16(16);out.write("data",4);u32(bytes);
    std::vector<char> silence(bytes,0);out.write(silence.data(),bytes);
}
void snapshot(const wchar_t* name) {
    Bitmap bitmap((int)width,(int)height,PixelFormat32bppPARGB);
    { Graphics graphics(&bitmap);paint(graphics); }
    CLSID png{0x557cf406,0x1a04,0x11d3,{0x9a,0x73,0x00,0x00,0xf8,0x1e,0xf3,0x2e}};
    check(bitmap.Save(name,&png,nullptr)==Ok,"render UI preview");
}
LRESULT CALLBACK testProc(HWND h,UINT m,WPARAM w,LPARAM l) {
    if(m==MM_MCINOTIFY) return wndProc(h,m,w,l);
    return DefWindowProcW(h,m,w,l);
}
int wmain() {
    GdiplusStartupInput input;ULONG_PTR token;GdiplusStartup(&token,&input,nullptr);
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    WNDCLASSW wc{};wc.lpfnWndProc=testProc;wc.hInstance=GetModuleHandleW(nullptr);wc.lpszClassName=L"MintTest";
    RegisterClassW(&wc);window=CreateWindowW(wc.lpszClassName,L"",0,0,0,1120,760,nullptr,nullptr,wc.hInstance,nullptr);
    int result=0;
    try {
        fs::create_directories(L"build/test-audio");
        wav(L"build/test-audio/清晨 · 测试音频.wav",3);
        wav(L"build/test-audio/Night Walk.wav",4);
        snapshot(L"build/preview-empty.png");
        check(appendSong(L"build/test-audio/清晨 · 测试音频.wav"),"import Unicode path");
        check(!appendSong(L"build/test-audio/清晨 · 测试音频.wav"),"deduplicate imports");
        check(!appendSong(L"build/test-audio/missing.mp3"),"reject missing file");
        check(appendSong(L"build/test-audio/Night Walk.wav"),"import second song");
        playSong(0);check(opened && playing && current==0,"open and play WAV through MCI");
        check(duration==3000,"read audio duration");
        check(applyVolume()==0,"audio device accepts volume control");
        setVolume(35);check(volumePercent==35,"adjust playback volume");
        setVolume(0);check(volumePercent==0 && volumeBeforeMute==35,"mute preserves previous volume");
        setVolume(volumeBeforeMute);check(volumePercent==35,"restore volume after mute");
        Sleep(200);check(statusNumber(L"position")>0,"playback advances");
        togglePlay();check(!playing,"pause playback");
        DWORD paused=statusNumber(L"position");Sleep(150);
        check(statusNumber(L"position")==paused,"paused position remains stable");
        { Bitmap b(1120,760);Graphics g(&b);paint(g); }
        seekTo(seekBox.X+seekBox.Width*.5f);
        check(!playing && statusNumber(L"position")>=1400,"seek while paused");
        togglePlay();check(playing,"resume playback");
        skip(1);check(current==1 && playing,"next track");
        skip(-1);check(current==0 && playing,"previous track");
        skip(-1);check(current==1,"wrap previous track");
        playSong(0);seekTo(seekBox.GetRight()-1);
        ULONGLONG deadline=GetTickCount64()+2000;
        while(current==0 && GetTickCount64()<deadline) {
            MSG msg;while(PeekMessageW(&msg,nullptr,0,0,PM_REMOVE)) {TranslateMessage(&msg);DispatchMessageW(&msg);} Sleep(10);
        }
        check(current==1 && playing,"automatically advance at end of track");
        position=statusNumber(L"position");snapshot(L"build/preview-library.png");
        width=940;height=650;snapshot(L"build/preview-compact.png");
        closeAudio();check(!opened && !playing,"release audio device");
        std::ofstream broken(L"build/test-audio/broken.wav");broken << "invalid audio";broken.close();
        appendSong(L"build/test-audio/broken.wav");playSong(2);
        check(!opened && !playing && current==-1 && !toast.empty(),"handle corrupt audio without crashing");
        toast.clear();width=1120;height=760;cloudView=true;cloudConnected=false;
        snapshot(L"build/preview-kugou-login.png");
        cloudConnected=true;cloudUser=L"测试用户（界面验证）";
        cloudStatus=L"测试数据 · 收藏歌单界面";
        cloudPlaylists=Json::array({{{"id","1"},{"name","我喜欢"},{"count",128}},{{"id","2"},{"name","晚间歌单"},{"count",23}}});
        snapshot(L"build/preview-kugou-library.png");
        cloudPlaylistId="1";cloudPlaylistName=L"我喜欢";
        cloudTracks=Json::array({{{"id","1:1"},{"name","界面测试歌曲"},{"artist","测试歌手"},{"duration",180000}}});
        snapshot(L"build/preview-kugou-tracks.png");
        cloudView=false;cloudConnected=false;cloudTracks=Json::array();cloudPlaylists=Json::array();cloudPlaylistId.clear();
        searchSubmitted=true;searchQuery="测试歌曲";searchTotal=60;searchMore=true;searchStatus=L"界面测试数据 · 双击歌曲播放";
        for(int i=0;i<30;++i) searchTracks.push_back({{"id",std::to_string(i)},{"name","搜索结果 · 测试歌曲"},{"artist","测试歌手"},{"duration",180000}});
        searchSelected=0;snapshot(L"build/preview-discover.png");
        check(hitTest(300,300)==SearchRow,"search result hit target");
        check(hitTest(100,56)==Avatar,"avatar hit target");
        width=940;height=650;snapshot(L"build/preview-discover-compact.png");
        toggleTheme();snapshot(L"build/preview-discover-dark.png");
        cloudView=true;cloudConnected=true;snapshot(L"build/preview-library-dark.png");
        toggleTheme();snapshot(L"build/preview-library-transparent.png");
        toggleTheme();snapshot(L"build/preview-library-light.png");
        duration=240000;position=60000;seekHoverX=width*.63f;hovered=Seek;seekEmphasis=1;
        snapshot(L"build/preview-seek-hover.png");
        duration=position=0;hovered=0;seekEmphasis=0;
        cloudView=false;cloudConnected=false;DeleteObject(searchBrush);searchBrush=nullptr;
        wndProc(window,WM_CREATE,0,0);
        check(searchEdit && (GetWindowLongPtrW(searchEdit,GWL_STYLE)&WS_VISIBLE),"create native search input");
        SetWindowTextW(searchEdit,L"周杰伦 夜曲");wchar_t query[121]{};GetWindowTextW(searchEdit,query,121);
        check(std::wstring(query)==L"周杰伦 夜曲","native input preserves Chinese and spaces");
        cloudInitialized=true;cloudClick(CloudHome);
        check(!(GetWindowLongPtrW(searchEdit,GWL_STYLE)&WS_VISIBLE),"hide search input in library");
        cloudClick(Library);check((GetWindowLongPtrW(searchEdit,GWL_STYLE)&WS_VISIBLE)!=0,"restore search input in discover");
        DestroyWindow(searchEdit);searchEdit=nullptr;DeleteObject(searchFont);DeleteObject(searchBrush);KillTimer(window,Tick);
    } catch(const std::exception& e) { std::cerr << "FAIL " << e.what() << '\n';result=1; }
    closeAudio();DestroyWindow(window);CoUninitialize();GdiplusShutdown(token);return result;
}
