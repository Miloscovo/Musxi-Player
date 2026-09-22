#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <windowsx.h>
#include <gdiplus.h>
#include <commdlg.h>
#include <shellapi.h>
#include <shlobj.h>
#include <mmsystem.h>
#include <mmdeviceapi.h>
#include <audiopolicy.h>
#include <dwmapi.h>
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <sstream>
#include <string>
#include <vector>
#include <map>
#include "cloud_bridge.hpp"

using namespace Gdiplus;
namespace fs = std::filesystem;
namespace {
constexpr UINT Tick = 1;
constexpr UINT SeekAnimationTick = 2;
constexpr UINT ScrollAnimationTick = 3;
bool darkTheme = false;
int themeIndex = 0; // Light, dark, transparent.
bool transparentTheme = false;
Color Bg(255,255,255,255), Side(255,247,249,248), Card(255,242,245,243);
Color Text(255,28,38,33), Muted(255,103,118,110), Mint(255,27,133,94);
Color Line(255,218,226,221), Active(255,225,242,233);
Color Dock(255,248,250,249), AccentHover(255,23,117,82), AccentText(255,255,255,255);
struct Song { std::wstring path, title, format; DWORD duration = 0; };
struct Hotspot { int id; RectF box; };
enum { Import = 1, Previous, Play, Next, Seek, Clear, Library, CloudHome = 20, CloudLogin, CloudSync, CloudLogout, CloudBack, LocalMusic = 25, SearchSubmit=30, SearchPrev, SearchNext, Avatar, Theme, WindowMinimize, WindowMaximize, WindowClose, SearchRow=140000, CloudRow = 20000, CloudTrack = 80000, Row = 1000 };
HWND window = nullptr;
float dpiScale = 1, width = 1120, height = 760;
std::vector<Song> songs;
std::vector<Hotspot> spots;
int selected = -1, current = -1, scroll = 0, hovered = 0;
bool localView = false, opened = false, playing = false, draggingSeek = false;
// Library state that survives a restart: two-step clear guard and the remembered resume point.
// The resume pair only tracks local playback so cloud tracks never overwrite it.
ULONGLONG clearArmedUntil = 0;
int resumeSong = -1;
DWORD resumePosition = 0, autosaveTicks = 0;
bool draggingScroll=false;
float scrollEmphasis=0,scrollVisual=-1,scrollTarget=0,scrollThumbLength=64,scrollGrab=0;
int scrollMaximum=0;
RectF scrollbarBox;
std::string scrollOwner;
constexpr int Scrollbar=90;
constexpr int VolumeMute=91,VolumeSlider=92;
int volumePercent=75,volumeBeforeMute=75;
bool draggingVolume=false;
RectF volumeBox;
void drawScrollbar(Graphics& g,int count,int rows,int offset);
void moveScrollbar(float y);
DWORD position = 0, duration = 0;
RectF seekBox;
float seekHoverX=0,seekEmphasis=0;
void updateSeekAnimation();
std::wstring toast;
ULONGLONG toastUntil = 0;
bool cloudView = false, cloudConnected = false, cloudPolling = false, cloudInitialized = false;
bool cloudLoginAfterInit = false, cloudAutoNext = false;
bool songMenuOpen=false;
POINT songMenuPoint{};
std::string songContextId;
void songContextMenu(LPARAM location);
void showSongMenu(const Json& data);
int cloudScroll = 0, cloudSelected = -1, cloudCurrent = -1, cloudGeneration = 0, requestGeneration = 0;
ULONGLONG cloudNextPoll = 0, cloudPollDeadline = 0;
std::wstring cloudStatus = L"登录后，把收藏带到这里。", cloudUser, cloudPlaylistName;
std::wstring cloudNowTitle, cloudNowArtist, cloudTempPath;
std::string cloudNowCover;
struct CoverImage {
    std::unique_ptr<Image> image;IStream* stream=nullptr;ULONGLONG retryAt=0;
    ~CoverImage() {image.reset();if(stream) stream->Release();}
};
std::map<std::string,std::unique_ptr<CoverImage>> coverCache;
std::vector<std::string> wantedCovers;
cloud::Bridge coverBridge;std::future<Json> coverFuture;
void drawCover(Graphics& g,const std::string& url,RectF box,int fallback=0);
void coverTick();void stopCovers();
Json cloudPlaylists = Json::array(), cloudTracks = Json::array(), cloudQueue = Json::array();
std::string cloudPlaylistId, cloudOperation;
cloud::Bridge cloudBridge;
std::future<Json> cloudFuture;
std::unique_ptr<Image> cloudQr;
IStream* cloudQrStream = nullptr;
HWND searchEdit=nullptr; HFONT searchFont=nullptr; HBRUSH searchBrush=nullptr; WNDPROC searchOriginal=nullptr;
Json searchTracks=Json::array(); std::wstring searchStatus; std::string searchQuery;
int searchPage=1,searchTotal=0,searchSelected=-1,searchScroll=0; bool searchMore=false,searchSubmitted=false;
std::unique_ptr<Image> userAvatar; IStream* avatarStream=nullptr;
void layoutSearch(); void runSearch(int page=1); void paintDiscover(Graphics& g);
int searchRows() {return std::max(1,(int)((height-445)/54));}
void cloudRequest(Json request);
void cloudTick();
void cloudStop();
void cloudPlay(int index, bool useQueue = false);
bool cloudClick(int id, bool doubleClick = false);
void paintCloud(Graphics& g);
int cloudRows() { return std::max(1, (int)((height - 390) / 54)); }
bool cloudBusy() { return cloudFuture.valid() || songMenuOpen; }

void invalidate() { InvalidateRect(window, nullptr, FALSE); }
void updateSeekAnimation() {SetTimer(window,SeekAnimationTick,16,nullptr);invalidate();}
void updateScrollAnimation() {SetTimer(window,ScrollAnimationTick,16,nullptr);invalidate();}
DWORD seekHoverTime() {
    return seekBox.Width>0?(DWORD)(std::clamp((seekHoverX-seekBox.X)/seekBox.Width,0.0f,1.0f)*duration):0;
}
void toggleTheme() {
    themeIndex=(themeIndex+1)%3;
    transparentTheme=themeIndex==2;
    darkTheme=themeIndex!=0;
    Bg=darkTheme?Color(255,18,22,25):Color(255,255,255,255);
    Side=darkTheme?Color(255,14,18,20):Color(255,247,249,248);
    Card=darkTheme?Color(255,25,31,34):Color(255,242,245,243);
    Text=darkTheme?Color(255,236,241,239):Color(255,28,38,33);
    Muted=darkTheme?Color(255,140,154,150):Color(255,103,118,110);
    Mint=darkTheme?Color(255,155,237,198):Color(255,27,133,94);
    Line=darkTheme?Color(255,43,52,53):Color(255,218,226,221);
    Active=darkTheme?Color(255,37,57,50):Color(255,225,242,233);
    Dock=darkTheme?Color(255,22,28,30):Color(255,248,250,249);
    AccentHover=darkTheme?Color(255,181,250,219):Color(255,23,117,82);
    AccentText=darkTheme?Side:Color::White;
    if(transparentTheme) {
        Bg=Color(255,16,17,19);Side=Color(255,11,12,14);Dock=Side;
        Card=Color(255,30,32,35);Active=Color(255,39,57,49);
        Text=Color(255,250,252,251);Muted=Color(255,189,199,194);
        Line=Color(255,88,99,94);Mint=Color(255,168,244,206);
        AccentHover=Color(255,204,255,229);AccentText=Color(255,15,28,22);
    }
    // Uniform nonzero opacity keeps all blank areas interactive (no color key).
    // Fully restore normal composition when leaving the transparent theme.
    LONG_PTR style=GetWindowLongPtrW(window,GWL_EXSTYLE);
    if(transparentTheme) {
        SetWindowLongPtrW(window,GWL_EXSTYLE,(style|WS_EX_LAYERED)&~WS_EX_TRANSPARENT);
        SetLayeredWindowAttributes(window,0,170,LWA_ALPHA);
    } else if(style&WS_EX_LAYERED) {
        SetWindowLongPtrW(window,GWL_EXSTYLE,style&~WS_EX_LAYERED);
        RedrawWindow(window,nullptr,nullptr,RDW_INVALIDATE|RDW_ERASE|RDW_FRAME|RDW_ALLCHILDREN);
    }
    if(searchBrush) DeleteObject(searchBrush);
    searchBrush=CreateSolidBrush(Card.ToCOLORREF());
    BOOL dark=darkTheme;DwmSetWindowAttribute(window,20,&dark,sizeof(dark));
    if(searchEdit) InvalidateRect(searchEdit,nullptr,TRUE);
    invalidate();
}
void notice(const std::wstring& value) { toast = value; toastUntil = GetTickCount64() + 6000; invalidate(); }
std::wstring lower(std::wstring s) { std::transform(s.begin(), s.end(), s.begin(), towlower); return s; }
bool supported(const fs::path& p) {
    const auto e = lower(p.extension().wstring());
    return e == L".mp3" || e == L".wav" || e == L".wma";
}
std::string utf8(const std::wstring& str) {
    int n = WideCharToMultiByte(CP_UTF8, 0, str.data(), (int)str.size(), nullptr, 0, nullptr, nullptr);
    std::string out(n, '\0');
    WideCharToMultiByte(CP_UTF8, 0, str.data(), (int)str.size(), out.data(), n, nullptr, nullptr);
    return out;
}
[[maybe_unused]] std::wstring wide(const std::string& str) {
    int n = MultiByteToWideChar(CP_UTF8, 0, str.data(), (int)str.size(), nullptr, 0);
    std::wstring out(n, L'\0');
    MultiByteToWideChar(CP_UTF8, 0, str.data(), (int)str.size(), out.data(), n);
    return out;
}
bool appendSong(const fs::path& p) {
    std::error_code ec;
    if (!supported(p) || !fs::is_regular_file(p, ec)) return false;
    auto absolute = fs::weakly_canonical(p, ec);
    if (ec) return false;
    auto path = absolute.wstring();
    for (const auto& s : songs) if (_wcsicmp(s.path.c_str(), path.c_str()) == 0) return false;
    std::wstring ext = absolute.extension().wstring().substr(1);
    std::transform(ext.begin(), ext.end(), ext.begin(), towupper);
    songs.push_back({path, absolute.stem().wstring(), ext, 0});
    if (selected < 0) selected = 0;
    return true;
}
// ---- Local library persistence -------------------------------------------------
// Stored beside the encrypted KuGou session so the player keeps a single data directory.
fs::path libraryFile() {
    try { return cloud::dataDir() / L"music-library.json"; } catch (...) { return {}; }
}
void saveLibrary() {
    try {
        auto file = libraryFile(); if (file.empty()) return;
        Json data;
        data["version"] = 1;
        auto list = Json::array();
        for (const auto& s : songs)
            list.push_back({{"path", utf8(s.path)}, {"title", utf8(s.title)}, {"format", utf8(s.format)}, {"duration", (unsigned long long)s.duration}});
        data["songs"] = std::move(list);
        data["current"] = resumeSong;
        data["position"] = (unsigned long long)resumePosition;
        auto text = data.dump();
        auto temp = file; temp += L".tmp";
        { std::ofstream out(temp, std::ios::binary | std::ios::trunc); out.write(text.data(), (std::streamsize)text.size()); if (out.fail()) return; }
        MoveFileExW(temp.c_str(), file.c_str(), MOVEFILE_REPLACE_EXISTING | MOVEFILE_WRITE_THROUGH);
    } catch (...) {}
}
void loadLibrary() {
    try {
        auto file = libraryFile(); if (file.empty() || !fs::exists(file)) return;
        std::ifstream in(file, std::ios::binary);
        std::string text((std::istreambuf_iterator<char>(in)), {});
        auto data = Json::parse(text, nullptr, false);
        if (data.is_discarded() || !data.is_object()) return;
        auto list = data.find("songs");
        if (list == data.end() || !list->is_array()) return;
        auto stringAt = [](const Json& row, const char* key, const std::wstring& fallback) {
            auto value = row.find(key);
            return value != row.end() && value->is_string() ? cloud::toWide(value->get<std::string>()) : fallback;
        };
        auto numberAt = [](const Json& row, const char* key) -> long long {
            auto value = row.find(key);
            return value != row.end() && value->is_number() ? (long long)value->get<double>() : 0;
        };
        std::error_code ec;
        for (const auto& row : *list) {
            if (!row.is_object()) continue;
            auto value = row.find("path");
            if (value == row.end() || !value->is_string()) continue;
            fs::path path = cloud::toWide(value->get<std::string>());
            // Files moved or deleted outside the player are dropped instead of leaving dead rows.
            if (!supported(path) || !fs::is_regular_file(path, ec)) continue;
            Song song;
            song.path = path.wstring();
            song.title = stringAt(row, "title", path.stem().wstring());
            song.format = stringAt(row, "format", lower(path.extension().wstring().substr(1)));
            song.duration = (DWORD)std::clamp<long long>(numberAt(row, "duration"), 0, 24LL * 60 * 60 * 1000);
            songs.push_back(std::move(song));
        }
        if (songs.empty()) return;
        int index = (int)numberAt(data, "current");
        if (index < 0 || index >= (int)songs.size()) return;
        // Restore the last song and its position without opening the audio device;
        // playback resumes from here on the first Play press.
        current = selected = index;
        resumeSong = index;
        duration = songs[index].duration;
        long long saved = numberAt(data, "position");
        resumePosition = (DWORD)std::clamp<long long>(saved, 0, duration ? (long long)duration - 1 : 0);
        position = resumePosition;
        int rows = searchRows();
        if (index < scroll) scroll = index;
        if (index >= scroll + rows) scroll = index - rows + 1;
    } catch (...) {}
}
// Native multi-select picker; the modern dialog handles long paths and file filters.
void importFiles() {
    std::vector<fs::path> picked;
    IFileOpenDialog* dialog = nullptr;
    if (SUCCEEDED(CoCreateInstance(__uuidof(FileOpenDialog), nullptr, CLSCTX_INPROC_SERVER, __uuidof(IFileOpenDialog), (void**)&dialog))) {
        DWORD options = 0;
        if (SUCCEEDED(dialog->GetOptions(&options)))
            dialog->SetOptions(options | FOS_FORCEFILESYSTEM | FOS_FILEMUSTEXIST | FOS_PATHMUSTEXIST | FOS_ALLOWMULTISELECT);
        COMDLG_FILTERSPEC filters[] = {{L"支持的音频", L"*.mp3;*.wav;*.wma"}, {L"全部文件", L"*.*"}};
        dialog->SetFileTypes(2, filters); dialog->SetTitle(L"导入音乐文件");
        if (SUCCEEDED(dialog->Show(window))) {
            IShellItemArray* items = nullptr;
            if (SUCCEEDED(dialog->GetResults(&items))) {
                DWORD count = 0; items->GetCount(&count);
                for (DWORD i = 0; i < count; ++i) {
                    IShellItem* item = nullptr;
                    if (FAILED(items->GetItemAt(i, &item))) continue;
                    PWSTR value = nullptr;
                    if (SUCCEEDED(item->GetDisplayName(SIGDN_FILESYSPATH, &value))) { picked.emplace_back(value); CoTaskMemFree(value); }
                    item->Release();
                }
                items->Release();
            }
        }
        dialog->Release();
    }
    if (picked.empty()) return;  // Cancelled: keep the current library untouched.
    int added = 0;
    for (const auto& path : picked) if (appendSong(path)) ++added;
    int skipped = (int)picked.size() - added;
    if (added) {
        saveLibrary();
        notice(L"已导入 " + std::to_wstring(added) + L" 首歌曲" + (skipped ? L"，跳过 " + std::to_wstring(skipped) + L" 个重复或不受支持的文件" : L""));
    } else notice(L"没有可导入的文件：仅支持 MP3、WAV、WMA，重复文件会被跳过");
    invalidate();
}
MCIERROR command(const std::wstring& value) { return mciSendStringW(value.c_str(), nullptr, 0, window); }
MCIERROR applyVolume() {
    if(!opened) return 0;
    IMMDeviceEnumerator* devices=nullptr;IMMDevice* device=nullptr;
    IAudioSessionManager2* manager=nullptr;IAudioSessionEnumerator* sessions=nullptr;
    bool applied=false;
    if(SUCCEEDED(CoCreateInstance(__uuidof(MMDeviceEnumerator),nullptr,CLSCTX_ALL,__uuidof(IMMDeviceEnumerator),(void**)&devices)) &&
       SUCCEEDED(devices->GetDefaultAudioEndpoint(eRender,eMultimedia,&device)) &&
       SUCCEEDED(device->Activate(__uuidof(IAudioSessionManager2),CLSCTX_ALL,nullptr,(void**)&manager)) &&
       SUCCEEDED(manager->GetSessionEnumerator(&sessions))) {
        int count=0;sessions->GetCount(&count);
        for(int i=0;i<count;++i) {
            IAudioSessionControl* session=nullptr;IAudioSessionControl2* info=nullptr;ISimpleAudioVolume* volume=nullptr;DWORD pid=0;
            if(SUCCEEDED(sessions->GetSession(i,&session)) &&
               SUCCEEDED(session->QueryInterface(__uuidof(IAudioSessionControl2),(void**)&info)) &&
               SUCCEEDED(info->GetProcessId(&pid)) && pid==GetCurrentProcessId() &&
               SUCCEEDED(session->QueryInterface(__uuidof(ISimpleAudioVolume),(void**)&volume))) {
                if(SUCCEEDED(volume->SetMasterVolume(volumePercent/100.0f,nullptr))) applied=true;
            }
            if(volume) volume->Release();
            if(info) info->Release();
            if(session) session->Release();
        }
    }
    if(sessions) sessions->Release();
    if(manager) manager->Release();
    if(device) device->Release();
    if(devices) devices->Release();
    return applied?0:MCIERR_UNSUPPORTED_FUNCTION;
}
void setVolume(int value) {
    int previous=volumePercent;volumePercent=std::clamp(value,0,100);
    if(applyVolume()) {volumePercent=previous;notice(L"此音频暂不支持音量调节");return;}
    if(volumePercent>0) volumeBeforeMute=volumePercent;
    invalidate();
}
void volumeAt(float x) {if(volumeBox.Width>0) setVolume((int)std::lround(std::clamp((x-volumeBox.X)/volumeBox.Width,0.0f,1.0f)*100));}
DWORD statusNumber(const wchar_t* what) {
    wchar_t value[64]{};
    if (mciSendStringW((std::wstring(L"status mint ") + what).c_str(), value, 64, nullptr)) return 0;
    return wcstoul(value, nullptr, 10);
}
void closeAudio() {
    if (opened) command(L"close mint");
    opened = false; playing = false; position = duration = 0;
    if (!cloudTempPath.empty()) { DeleteFileW(cloudTempPath.c_str()); cloudTempPath.clear(); }
}
void reveal(int index) {
    int count = searchRows();
    if (index < scroll) scroll = index;
    if (index >= scroll + count) scroll = index - count + 1;
}
void playSong(int index, DWORD startAt = 0) {
    if (index < 0 || index >= (int)songs.size()) return;
    ++cloudGeneration; cloudCurrent = -1; cloudAutoNext = false; cloudNowTitle.clear();
    closeAudio();
    current = -1; selected = index; position = startAt;
    resumeSong = index; resumePosition = startAt;
    auto error = command(L"open \"" + songs[index].path + L"\" alias mint");
    if (!error) { opened = true; error = command(L"set mint time format milliseconds"); }
    if (!error) applyVolume();
    if (!error) {
        duration = statusNumber(L"length");
        songs[index].duration = duration;
        // Resume support: seek first, then fall back to the start if the format refuses it.
        if (startAt && startAt < duration && command(L"seek mint to " + std::to_wstring(startAt))) position = 0;
        error = command(L"play mint notify");
    }
    if (error) {
        closeAudio();
        position = 0;
        wchar_t reason[256]{}; mciGetErrorStringW(error, reason, 256);
        notice(L"无法播放此文件：" + std::wstring(reason));
    } else { current = index; playing = true; applyVolume(); toast.clear(); resumePosition = 0; }
    reveal(index); saveLibrary(); invalidate();
}
void togglePlay() {
    if (!opened) {
        int index=cloudView?cloudSelected:searchSelected;
        if(!cloudView && !localView && index>=0) {cloudPlay(index);return;}
        if(current>=0 && current<(int)songs.size()) {playSong(resumeSong>=0?resumeSong:current,resumePosition);return;}
        if(cloudView && index>=0) {cloudPlay(index);return;}
        notice(L"请先导入音乐文件或选择一首歌曲");return;
    }
    MCIERROR error = playing ? command(L"pause mint") : command(L"play mint notify");
    if (!error) {
        playing = !playing;
        if(cloudCurrent<0 && current>=0) {resumeSong=current;resumePosition=position;}
        saveLibrary();
    }
    else notice(L"播放状态切换失败，请重新打开这首歌曲");
    invalidate();
}
void skip(int delta) {
    if (cloudCurrent >= 0 && !cloudQueue.empty()) {
        cloudPlay((cloudCurrent + delta + (int)cloudQueue.size()) % (int)cloudQueue.size(), true); return;
    }
    if (songs.empty()) return;
    int base = current >= 0 ? current : selected;
    if (base < 0) base = 0;
    playSong((base + delta + (int)songs.size()) % (int)songs.size());
}
void seekTo(float x) {
    if (!opened || !duration) return;
    auto target = (DWORD)(std::clamp((x - seekBox.X) / seekBox.Width, 0.0f, 1.0f) * (duration - 1));
    bool wasPlaying = playing;
    auto error = command(L"seek mint to " + std::to_wstring(target));
    if (!error && wasPlaying) error = command(L"play mint notify");
    if (error) notice(L"此音频暂不支持跳转到该位置");
    position = statusNumber(L"position");
    if(cloudCurrent<0 && current>=0) {resumeSong=current;resumePosition=position;saveLibrary();}
    invalidate();
}
std::wstring timeText(DWORD ms) {
    std::wostringstream s; s << ms / 60000 << L":" << std::setw(2) << std::setfill(L'0') << (ms / 1000) % 60;
    return s.str();
}
void rounded(Graphics& g, RectF r, float radius, Color color) {
    GraphicsPath p; float d = radius * 2;
    p.AddArc(r.X, r.Y, d, d, 180, 90); p.AddArc(r.GetRight() - d, r.Y, d, d, 270, 90);
    p.AddArc(r.GetRight() - d, r.GetBottom() - d, d, d, 0, 90); p.AddArc(r.X, r.GetBottom() - d, d, d, 90, 90);
    p.CloseFigure(); SolidBrush b(color); g.FillPath(&b, &p);
}
void label(Graphics& g, const std::wstring& text, RectF r, float size, Color color, bool bold = false, StringAlignment align = StringAlignmentNear) {
    Font font(L"Microsoft YaHei UI", size, bold ? FontStyleBold : FontStyleRegular, UnitPixel);
    SolidBrush brush(color); StringFormat fmt;
    fmt.SetAlignment(align); fmt.SetLineAlignment(StringAlignmentCenter);
    fmt.SetFormatFlags(StringFormatFlagsNoWrap); fmt.SetTrimming(StringTrimmingEllipsisCharacter);
    g.DrawString(text.c_str(), -1, &font, r, &fmt, &brush);
}
void stroke(Graphics& g, Color color, float thick, float x1, float y1, float x2, float y2) {
    Pen p(color, thick); p.SetStartCap(LineCapRound); p.SetEndCap(LineCapRound); g.DrawLine(&p, x1, y1, x2, y2);
}
void icon(Graphics& g, int kind, float x, float y, float s, Color color) {
    SolidBrush b(color);
    if (kind == Play) {
        if (playing) { rounded(g, {x+s*.28f,y+s*.22f,s*.15f,s*.56f},2,color); rounded(g,{x+s*.57f,y+s*.22f,s*.15f,s*.56f},2,color); }
        else { PointF p[] = {{x+s*.32f,y+s*.19f},{x+s*.78f,y+s*.5f},{x+s*.32f,y+s*.81f}}; g.FillPolygon(&b,p,3); }
    } else if (kind == Next || kind == Previous) {
        bool prev = kind == Previous;
        auto px = [&](float f) { return x + s * (prev ? 1-f : f); };
        PointF p[] = {{px(.25f),y+s*.22f},{px(.68f),y+s*.5f},{px(.25f),y+s*.78f}};
        g.FillPolygon(&b,p,3); stroke(g,color,2.5f,px(.75f),y+s*.23f,px(.75f),y+s*.77f);
    } else if (kind == Import) {
        stroke(g,color,2,x+s*.5f,y+s*.2f,x+s*.5f,y+s*.8f);
        stroke(g,color,2,x+s*.2f,y+s*.5f,x+s*.8f,y+s*.5f);
    } else if (kind == Library) {
        for (int i=0;i<3;++i) { float yy=y+s*(.25f+i*.25f); stroke(g,color,2,x+s*.35f,yy,x+s*.85f,yy); g.FillEllipse(&b,x+s*.08f,yy-1.5f,3.0f,3.0f); }
    } else {
        stroke(g,color,2,x+s*.61f,y+s*.2f,x+s*.61f,y+s*.7f);
        stroke(g,color,2,x+s*.61f,y+s*.2f,x+s*.83f,y+s*.26f);
        g.FillEllipse(&b,x+s*.27f,y+s*.6f,s*.35f,s*.23f);
    }
}
void hit(int id, RectF box) { spots.push_back({id, box}); }
int hitTest(float x, float y) { for (auto it=spots.rbegin();it!=spots.rend();++it) if(it->box.Contains(x,y)) return it->id; return 0; }
void button(Graphics& g, int id, RectF r, const std::wstring& title, bool accent=false) {
    rounded(g,r,10,accent ? (hovered==id ? AccentHover : Mint) : (hovered==id ? Active : Card));
    if (id==Import) icon(g,Import,r.X+13,r.Y+(r.Height-20)/2,20,accent?Side:Text);
    label(g,title,{r.X+(id==Import?38:8),r.Y,r.Width-(id==Import?46:16),r.Height},13,accent?AccentText:Text,true,id==Import?StringAlignmentNear:StringAlignmentCenter);
    hit(id,r);
}
#include "cloud_ui.inc"
void paint(Graphics& g) {
    g.SetSmoothingMode(SmoothingModeAntiAlias); g.SetTextRenderingHint(TextRenderingHintAntiAliasGridFit);
    g.Clear(Bg); spots.clear();wantedCovers.clear();
    if(cloudCurrent>=0 && !cloudNowCover.empty()) wantedCovers.push_back(cloudNowCover);
    const float sidebar=202, left=sidebar+32, right=width-32, content=right-left, bottom=height-112;
    SolidBrush side(Side); g.FillRectangle(&side,0.0f,0.0f,sidebar,bottom);
    SolidBrush avatarBg(Active);g.FillEllipse(&avatarBg,73.0f,28.0f,56.0f,56.0f);
    if(cloudConnected && userAvatar) {
        auto state=g.Save();GraphicsPath clip;clip.AddEllipse(73.0f,28.0f,56.0f,56.0f);g.SetClip(&clip);
        float side=std::min(userAvatar->GetWidth(),userAvatar->GetHeight());
        g.DrawImage(userAvatar.get(),RectF(73,28,56,56),(userAvatar->GetWidth()-side)/2,(userAvatar->GetHeight()-side)/2,side,side,UnitPixel);g.Restore(state);
    }
    hit(Avatar,{73,28,56,56});
    label(g,L"音乐空间",{26,116,150,20},11,Muted);
    bool discoverView=!cloudView && !localView;
    rounded(g,{16,151,170,46},10,discoverView?Active:Side);
    {Pen magnifier(Mint,1.8f);g.DrawEllipse(&magnifier,31.0f,165.0f,12.0f,12.0f);stroke(g,Mint,1.8f,41,175,48,182);}
    label(g,L"发现",{64,151,83,46},13,discoverView?Mint:Text,true);
    hit(Library,{16,151,170,46});
    // Local and cloud libraries are separate tabs so imported files never mix with account lists.
    rounded(g,{16,207,170,46},10,localView?Active:Side); icon(g,Library,29,219,22,localView?Mint:Muted);
    label(g,L"本地音乐",{64,207,112,46},13,localView?Mint:Text,true);
    hit(LocalMusic,{16,207,170,46});
    rounded(g,{16,263,170,46},10,cloudView?Active:Side); icon(g,0,29,275,22,Mint);
    label(g,L"云端音乐",{64,263,112,46},13,cloudView?Mint:Text,true);
    hit(CloudHome,{16,263,170,46});
    if (cloudView) paintCloud(g);
    else if (localView) paintLocal(g);
    else paintDiscover(g);
    // Caption controls are painted in the client area and follow the UI theme.
    for(int i=0;i<3;++i) {
        int id=WindowMinimize+i;RectF box(width-144+i*44.0f,8,40,28);
        bool hot=hovered==id;Color ink=hot&&id==WindowClose?Color::White:Muted;
        if(hot || transparentTheme) rounded(g,box,7,hot?(id==WindowClose?Color(255,220,62,72):Active):Card);
        float x=box.X+20,y=box.Y+14;
        if(id==WindowMinimize) stroke(g,ink,1.6f,x-5,y+3,x+5,y+3);
        else if(id==WindowClose) {
            stroke(g,ink,1.6f,x-4,y-4,x+4,y+4);stroke(g,ink,1.6f,x+4,y-4,x-4,y+4);
        } else {
            Pen pen(ink,1.5f);
            if(IsZoomed(window)) {
                g.DrawRectangle(&pen,x-3,y-6,9.0f,9.0f);
                SolidBrush fill(hot?Active:(transparentTheme?Card:Bg));g.FillRectangle(&fill,x-6,y-3,9.0f,9.0f);
                g.DrawRectangle(&pen,x-6,y-3,9.0f,9.0f);
            } else g.DrawRectangle(&pen,x-5,y-5,10.0f,10.0f);
        }
        hit(id,box);
    }
    // Show the current theme: sun, moon, or transparent glass layers.
    RectF themeBox(right-40,49,40,40);
    Color themeBg=hovered==Theme?Active:Card;
    rounded(g,themeBox,12,themeBg);
    float tx=themeBox.X+20,ty=themeBox.Y+20;
    if(!darkTheme) {
        Pen pen(Text,1.8f);g.DrawEllipse(&pen,tx-5,ty-5,10.0f,10.0f);
        for(int i=0;i<8;++i) {float a=i*3.14159265f/4;stroke(g,Text,1.8f,tx+9*std::cos(a),ty+9*std::sin(a),tx+12*std::cos(a),ty+12*std::sin(a));}
    } else if(transparentTheme) {
        Pen pen(Text,1.6f);SolidBrush glass(Color(75,Text.GetR(),Text.GetG(),Text.GetB()));
        g.DrawRectangle(&pen,tx-9,ty-9,13.0f,13.0f);
        g.FillRectangle(&glass,tx-3,ty-3,13.0f,13.0f);
        g.DrawRectangle(&pen,tx-3,ty-3,13.0f,13.0f);
    } else {
        SolidBrush moon(Text),cutout(themeBg);g.FillEllipse(&moon,tx-10,ty-10,20.0f,20.0f);
        g.FillEllipse(&cutout,tx-3,ty-13,18.0f,18.0f);
    }
    hit(Theme,themeBox);
    SolidBrush bar(Dock); g.FillRectangle(&bar,0.0f,bottom,width,112.0f);
    drawCover(g,cloudCurrent>=0?cloudNowCover:"",{24,bottom+25,58,58});
    label(g,cloudCurrent>=0?cloudNowTitle:(current>=0?songs[current].title:L"准备好听点什么？"),{96,bottom+27,200,27},13,Text,true);
    label(g,cloudCurrent>=0?cloudNowArtist:(current>=0?songs[current].format+L" · 本地音乐":std::wstring(L"搜索音乐，开启你的音乐时光")),{96,bottom+57,205,22},10,Muted);
    float center=(width+155)/2;
    RectF prev(center-86,bottom+37,38,38), play(center-24,bottom+32,48,48), next(center+48,bottom+37,38,38);
    if(hovered==Previous) rounded(g,prev,19,Active);
    if(hovered==Next) rounded(g,next,19,Active);
    rounded(g,play,24,hovered==Play?AccentHover:Mint);
    icon(g,Previous,prev.X+5,prev.Y+5,28,songs.empty()&&cloudCurrent<0?Line:Text);
    icon(g,Play,play.X+7,play.Y+7,34,AccentText);
    icon(g,Next,next.X+5,next.Y+5,28,songs.empty()&&cloudCurrent<0?Line:Text);
    hit(Previous,prev); hit(Play,play); hit(Next,next);
    float vx=center+104,vy=bottom+56;
    RectF muteBox(vx,vy-16,30,32);
    if(hovered==VolumeMute) rounded(g,muteBox,8,Active);
    Color speaker=volumePercent?Muted:Text;SolidBrush speakerBrush(speaker);
    PointF shape[]={{vx+4,vy-4},{vx+9,vy-4},{vx+15,vy-9},{vx+15,vy+9},{vx+9,vy+4},{vx+4,vy+4}};
    g.FillPolygon(&speakerBrush,shape,6);
    if(volumePercent) {
        Pen sound(speaker,1.7f);g.DrawArc(&sound,vx+11,vy-7,12.0f,14.0f,-55,110);
        if(volumePercent>50) g.DrawArc(&sound,vx+9,vy-11,20.0f,22.0f,-50,100);
    } else {stroke(g,speaker,1.7f,vx+20,vy-4,vx+27,vy+4);stroke(g,speaker,1.7f,vx+27,vy-4,vx+20,vy+4);}
    hit(VolumeMute,muteBox);
    volumeBox={vx+40,vy-12,98,24};
    rounded(g,{volumeBox.X,vy-3,volumeBox.Width,6},3,Line);
    float filled=volumeBox.Width*volumePercent/100.0f;
    if(filled>0) rounded(g,{volumeBox.X,vy-3,std::max(6.0f,filled),6},3,Mint);
    if(hovered==VolumeSlider || draggingVolume) {
        SolidBrush thumb(Mint);g.FillEllipse(&thumb,volumeBox.X+filled-5,vy-5,10.0f,10.0f);
        label(g,std::to_wstring(volumePercent)+L"%",{volumeBox.X,vy-38,98,22},10,Muted,false,StringAlignmentCenter);
    }
    hit(VolumeSlider,volumeBox);
    // Full-width seek bar sits on the top edge of the playback dock.
    seekBox={0,bottom-10,width,22};
    float trackHeight=2+3*seekEmphasis;
    SolidBrush seekTrack(Line);g.FillRectangle(&seekTrack,0.0f,bottom-trackHeight/2,width,trackHeight);
    if(duration) {
        float progress=draggingSeek?std::clamp(seekHoverX,0.0f,width):seekBox.Width*std::min(1.0f,(float)position/duration);
        SolidBrush fill(Mint);g.FillRectangle(&fill,0.0f,bottom-trackHeight/2,progress,trackHeight);
        if(seekEmphasis>.01f) {
            float radius=5*seekEmphasis,thumb=std::clamp(progress,radius,width-radius);
            g.FillEllipse(&fill,thumb-radius,bottom-radius,radius*2,radius*2);
            if(hovered==Seek || draggingSeek) {
                float pointer=std::clamp(seekHoverX,0.0f,width);
                float boxX=std::clamp(pointer-38,6.0f,width-82);
                BYTE opacity=(BYTE)(255*seekEmphasis);
                Color bubble(opacity,darkTheme?45:32,darkTheme?55:42,darkTheme?51:37);
                rounded(g,{boxX,bottom-44,76,28},8,bubble);
                float tip=std::clamp(pointer,boxX+10,boxX+66);
                PointF triangle[]={{tip-5,bottom-16},{tip+5,bottom-16},{tip,bottom-11}};
                SolidBrush arrow(bubble);g.FillPolygon(&arrow,triangle,3);
                label(g,timeText(seekHoverTime()),{boxX,bottom-44,76,28},12,Color(opacity,255,255,255),true,StringAlignmentCenter);
            }
        }
    }
    hit(Seek,seekBox);
    label(g,L"列表循环",{width-95,bottom+22,72,22},10,Muted,false,StringAlignmentFar);
    if(!toast.empty() && GetTickCount64()<toastUntil) {
        float tw=std::min(content,620.0f);
        rounded(g,{left+(content-tw)/2,bottom-51,tw,39},10,Active);
        label(g,toast,{left+(content-tw)/2+13,bottom-51,tw-26,39},11,Text);
    }
}
LRESULT CALLBACK wndProc(HWND hwnd, UINT message, WPARAM wp, LPARAM lp) {
    switch(message) {
    case WM_NCCALCSIZE:
        if(wp && IsZoomed(hwnd)) {
            auto params=reinterpret_cast<NCCALCSIZE_PARAMS*>(lp);
            MONITORINFO monitor{};monitor.cbSize=sizeof(monitor);
            if(GetMonitorInfoW(MonitorFromRect(&params->rgrc[0],MONITOR_DEFAULTTONEAREST),&monitor))
                params->rgrc[0]=monitor.rcWork;
        }
        return 0;
    case WM_NCHITTEST: {
        POINT point{GET_X_LPARAM(lp),GET_Y_LPARAM(lp)};ScreenToClient(hwnd,&point);
        RECT client{};GetClientRect(hwnd,&client);
        int edge=(int)(6*dpiScale);
        if(!IsZoomed(hwnd)) {
            bool left=point.x<edge,right=point.x>=client.right-edge;
            bool top=point.y<edge,bottom=point.y>=client.bottom-edge;
            if(top && left) return HTTOPLEFT;
            if(top && right) return HTTOPRIGHT;
            if(bottom && left) return HTBOTTOMLEFT;
            if(bottom && right) return HTBOTTOMRIGHT;
            if(left) return HTLEFT;
            if(right) return HTRIGHT;
            if(top) return HTTOP;
            if(bottom) return HTBOTTOM;
        }
        float x=point.x/dpiScale,y=point.y/dpiScale;
        if(y>=8 && y<36 && x>=width-144 && x<width-16) return HTCLIENT;
        if(y<28) return HTCAPTION;
        return HTCLIENT;
    }
    case WM_CREATE:
        window=hwnd;SetTimer(hwnd,Tick,150,nullptr);
        searchBrush=CreateSolidBrush(Card.ToCOLORREF());
        searchFont=CreateFontW(-(int)(16*dpiScale),0,0,0,FW_NORMAL,FALSE,FALSE,FALSE,DEFAULT_CHARSET,0,0,CLEARTYPE_QUALITY,0,L"Microsoft YaHei UI");
        searchEdit=CreateWindowExW(0,L"EDIT",L"",WS_CHILD|WS_VISIBLE|ES_AUTOHSCROLL,0,0,0,0,hwnd,nullptr,GetModuleHandleW(nullptr),nullptr);
        SendMessageW(searchEdit,WM_SETFONT,(WPARAM)searchFont,TRUE);SendMessageW(searchEdit,EM_LIMITTEXT,120,0);
        SendMessageW(searchEdit,0x1501,TRUE,(LPARAM)L"搜索歌曲、歌手");
        searchOriginal=(WNDPROC)SetWindowLongPtrW(searchEdit,GWLP_WNDPROC,(LONG_PTR)searchProc);layoutSearch();
        // Restore the saved library and resume point before the first paint.
        loadLibrary();
        if(!songs.empty()) {localView=true;layoutSearch();}
        return 0;
    case WM_CTLCOLOREDIT:
        SetTextColor((HDC)wp,Text.ToCOLORREF());SetBkColor((HDC)wp,Card.ToCOLORREF());return (LRESULT)searchBrush;
    case WM_GETMINMAXINFO: {
        auto m=reinterpret_cast<MINMAXINFO*>(lp); m->ptMinTrackSize={(LONG)(940*dpiScale),(LONG)(690*dpiScale)};
        MONITORINFO monitor{};monitor.cbSize=sizeof(monitor);
        if(GetMonitorInfoW(MonitorFromWindow(hwnd,MONITOR_DEFAULTTONEAREST),&monitor)) {
            m->ptMaxPosition={monitor.rcWork.left-monitor.rcMonitor.left,monitor.rcWork.top-monitor.rcMonitor.top};
            m->ptMaxSize={monitor.rcWork.right-monitor.rcWork.left,monitor.rcWork.bottom-monitor.rcWork.top};
        }
        return 0;
    }
    case WM_SIZE: if(wp==SIZE_MINIMIZED) return 0; width=LOWORD(lp)/dpiScale; height=HIWORD(lp)/dpiScale; layoutSearch(); invalidate(); return 0;
    case WM_ERASEBKGND: return 1;
    case WM_PAINT: {
        PAINTSTRUCT ps; HDC dc=BeginPaint(hwnd,&ps); RECT rect; GetClientRect(hwnd,&rect);
        if(rect.right>0 && rect.bottom>0) {
            Bitmap buffer(rect.right,rect.bottom,PixelFormat32bppPARGB);
            Graphics g(&buffer); g.ScaleTransform(dpiScale,dpiScale); paint(g);
            Graphics screen(dc); screen.DrawImage(&buffer,0,0);
        }
        EndPaint(hwnd,&ps); return 0;
    }
    case WM_MOUSEMOVE: {
        float x=GET_X_LPARAM(lp)/dpiScale,y=GET_Y_LPARAM(lp)/dpiScale;
        int id=hitTest(x,y);bool changed=id!=hovered;
        bool wasSeek=hovered==Seek,wasScroll=hovered==Scrollbar;hovered=id;
        if(draggingScroll) moveScrollbar(y);
        if(draggingVolume) volumeAt(x);
        if(changed && (wasScroll || id==Scrollbar)) updateScrollAnimation();
        if(id==Seek || draggingSeek) {seekHoverX=std::clamp(x,0.0f,width);invalidate();}
        if(changed) {if(wasSeek || id==Seek) updateSeekAnimation();else invalidate();}
        TRACKMOUSEEVENT track{sizeof(track),TME_LEAVE,hwnd,0}; TrackMouseEvent(&track);
        SetCursor(LoadCursorW(nullptr,id?IDC_HAND:IDC_ARROW));
        return 0;
    }
    case WM_MOUSELEAVE: {bool wasSeek=hovered==Seek,wasScroll=hovered==Scrollbar;hovered=0;if(wasScroll) updateScrollAnimation();if(wasSeek) updateSeekAnimation();else invalidate();return 0;}
    case WM_CONTEXTMENU: songContextMenu(lp);return 0;
    case WM_LBUTTONDOWN: {
        SetFocus(hwnd); float x=GET_X_LPARAM(lp)/dpiScale,y=GET_Y_LPARAM(lp)/dpiScale;
        int id=hitTest(x,y);
        if(id==VolumeMute) {setVolume(volumePercent?0:std::max(1,volumeBeforeMute));return 0;}
        if(id==VolumeSlider) {draggingVolume=true;SetCapture(hwnd);volumeAt(x);return 0;}
        if(id==Scrollbar && scrollMaximum>0) {
            draggingScroll=true;scrollGrab=(y>=scrollVisual && y<=scrollVisual+scrollThumbLength)?y-scrollVisual:scrollThumbLength/2;
            SetCapture(hwnd);moveScrollbar(y);updateScrollAnimation();return 0;
        }
        if(id==WindowMinimize) {ShowWindow(hwnd,SW_MINIMIZE);return 0;}
        if(id==WindowMaximize) {ShowWindow(hwnd,IsZoomed(hwnd)?SW_RESTORE:SW_MAXIMIZE);return 0;}
        if(id==WindowClose) {PostMessageW(hwnd,WM_CLOSE,0,0);return 0;}
        if(id==Theme) {toggleTheme();return 0;}
        if(cloudClick(id)) return 0;
        if(id==Import) {importFiles();return 0;}
        if(id==Clear) {
            if(songs.empty()) {notice(L"列表里还没有歌曲");return 0;}
            if(GetTickCount64()<clearArmedUntil) {
                clearArmedUntil=0;closeAudio();current=selected=-1;scroll=0;songs.clear();resumeSong=-1;resumePosition=0;saveLibrary();
                notice(L"已清空本地音乐列表，磁盘上的文件没有被删除");
            } else {clearArmedUntil=GetTickCount64()+6000;notice(L"再次点击“确认清空”才会移除列表中的全部歌曲，文件不会被删除");}
            invalidate();return 0;
        }
        if(localView && id>=Row && id<Row+(int)songs.size()) {selected=id-Row;invalidate();return 0;}
        if(id==Play) togglePlay();
        else if(id==Previous) skip(-1);
        else if(id==Next) skip(1);
        else if(id==Seek && opened && duration) {draggingSeek=true;seekHoverX=std::clamp(x,0.0f,width);SetCapture(hwnd);updateSeekAnimation();}
        return 0;
    }
    case WM_LBUTTONUP:
        if(draggingVolume) {volumeAt(GET_X_LPARAM(lp)/dpiScale);draggingVolume=false;ReleaseCapture();invalidate();return 0;}
        if(draggingScroll) {moveScrollbar(GET_Y_LPARAM(lp)/dpiScale);draggingScroll=false;ReleaseCapture();updateScrollAnimation();return 0;}
        if(draggingSeek) {seekHoverX=std::clamp(GET_X_LPARAM(lp)/dpiScale,0.0f,width);draggingSeek=false;ReleaseCapture();seekTo(seekHoverX);updateSeekAnimation();} return 0;
    case WM_CAPTURECHANGED: draggingVolume=false;draggingScroll=false;updateScrollAnimation();draggingSeek=false;updateSeekAnimation();return 0;
    case WM_LBUTTONDBLCLK: {
        int id=hitTest(GET_X_LPARAM(lp)/dpiScale,GET_Y_LPARAM(lp)/dpiScale);
        if(localView && id>=Row && id<Row+(int)songs.size()) {playSong(id-Row);return 0;}
        if(cloudView && id>=CloudTrack) {cloudClick(id,true);return 0;}
        if(!cloudView) cloudClick(id,true);
        return 0;
    }
    case WM_MOUSEWHEEL:
        if(cloudView) {
            int count=cloudPlaylistId.empty()?(int)cloudPlaylists.size():(int)cloudTracks.size();
            cloudScroll=std::clamp(cloudScroll-GET_WHEEL_DELTA_WPARAM(wp)/WHEEL_DELTA*3,0,std::max(0,count-cloudRows()));invalidate();return 0;
        }
        if(localView) {scroll=std::clamp(scroll-GET_WHEEL_DELTA_WPARAM(wp)/WHEEL_DELTA*3,0,std::max(0,(int)songs.size()-searchRows()));invalidate();return 0;}
        searchScroll=std::clamp(searchScroll-GET_WHEEL_DELTA_WPARAM(wp)/WHEEL_DELTA*3,0,std::max(0,(int)searchTracks.size()-searchRows())); invalidate(); return 0;
    case WM_KEYDOWN:
        if(wp==VK_SPACE) togglePlay();
        else if(wp==VK_LEFT) skip(-1);
        else if(wp==VK_RIGHT) skip(1);
        else if(wp==VK_RETURN && cloudView && cloudSelected>=0) cloudPlay(cloudSelected);
        else if(wp==VK_RETURN && localView && selected>=0) playSong(selected);
        else if(wp==VK_RETURN && !cloudView && !localView && searchSelected>=0) cloudPlay(searchSelected);
        else if((wp==VK_DOWN || wp==VK_UP) && cloudView && !cloudTracks.empty()) {
            cloudSelected=std::clamp(cloudSelected+(wp==VK_DOWN?1:-1),0,(int)cloudTracks.size()-1);
            if(cloudSelected<cloudScroll) cloudScroll=cloudSelected;
            if(cloudSelected>=cloudScroll+cloudRows()) cloudScroll=cloudSelected-cloudRows()+1;
            invalidate();
        }
        else if((wp==VK_DOWN || wp==VK_UP) && localView && !songs.empty()) {
            int rows=searchRows();
            selected=std::clamp(selected+(wp==VK_DOWN?1:-1),0,(int)songs.size()-1);
            if(selected<scroll) scroll=selected;
            if(selected>=scroll+rows) scroll=selected-rows+1;
            invalidate();
        }
        else if((wp==VK_DOWN || wp==VK_UP) && !cloudView && !localView && !searchTracks.empty()) {
            searchSelected=std::clamp(searchSelected+(wp==VK_DOWN?1:-1),0,(int)searchTracks.size()-1);
            searchScroll=std::clamp(searchScroll, std::max(0,searchSelected-searchRows()+1),searchSelected);invalidate();
        }
        return 0;
    case WM_TIMER:
        if(wp==ScrollAnimationTick) {
            float target=(hovered==Scrollbar || draggingScroll)?1.0f:0.0f;
            scrollEmphasis+=(target-scrollEmphasis)*.22f;
            scrollVisual+=(scrollTarget-scrollVisual)*.3f;
            if(std::abs(target-scrollEmphasis)<.01f && std::abs(scrollTarget-scrollVisual)<.2f) {
                scrollEmphasis=target;scrollVisual=scrollTarget;KillTimer(hwnd,ScrollAnimationTick);
            }
            RECT area{(LONG)((width-28)*dpiScale),(LONG)(270*dpiScale),(LONG)(width*dpiScale),(LONG)((height-112)*dpiScale)};
            InvalidateRect(hwnd,&area,FALSE);return 0;
        }
        if(wp==SeekAnimationTick) {
            float target=(hovered==Seek || draggingSeek)?1.0f:0.0f;
            seekEmphasis+=(target-seekEmphasis)*.25f;
            if(std::abs(target-seekEmphasis)<.01f) {seekEmphasis=target;KillTimer(hwnd,SeekAnimationTick);}
            // Only repaint the dock edge and time bubble during the hover transition.
            RECT area{0,(LONG)((height-162)*dpiScale),(LONG)(width*dpiScale),(LONG)((height-96)*dpiScale)};
            InvalidateRect(hwnd,&area,FALSE);return 0;
        }
        cloudTick();
        coverTick();
        if(opened && playing) {
            position=statusNumber(L"position");invalidate();
            // Persist the local resume point about every three seconds instead of on every tick.
            if(cloudCurrent<0 && current>=0) {
                resumeSong=current;resumePosition=position;
                if(++autosaveTicks>=20) {autosaveTicks=0;saveLibrary();}
            }
        }
        if(!toast.empty() && GetTickCount64()>=toastUntil) {toast.clear();invalidate();}
        return 0;
    case MM_MCINOTIFY:
        if(opened && (MCIDEVICEID)lp==mciGetDeviceIDW(L"mint")) {
            if(wp==MCI_NOTIFY_SUCCESSFUL && playing) {
                if(cloudCurrent>=0 && cloudBusy()) {playing=false;cloudAutoNext=true;}
                else skip(1);
            }
            else if(wp==MCI_NOTIFY_FAILURE) {playing=false;notice(L"音频播放中断，请重新播放或选择其他歌曲");}
        }
        return 0;
    // The resume point must be written before closeAudio() clears it.
    case WM_ENDSESSION: if(wp) saveLibrary(); return 0;
    case WM_DESTROY: saveLibrary();cloudStop();closeAudio();DeleteObject(searchFont);DeleteObject(searchBrush);KillTimer(hwnd,Tick);KillTimer(hwnd,SeekAnimationTick);KillTimer(hwnd,ScrollAnimationTick);PostQuitMessage(0);return 0;
    }
    return DefWindowProcW(hwnd,message,wp,lp);
}
} // namespace

int WINAPI wWinMain(HINSTANCE instance,HINSTANCE, PWSTR, int show) {
    SetProcessDPIAware();
    HDC screen=GetDC(nullptr);dpiScale=GetDeviceCaps(screen,LOGPIXELSX)/96.0f;ReleaseDC(nullptr,screen);
    GdiplusStartupInput input;ULONG_PTR token;
    if(GdiplusStartup(&token,&input,nullptr)!=Ok) return 1;
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    WNDCLASSEXW wc{};wc.cbSize=sizeof(wc);wc.style=CS_HREDRAW|CS_VREDRAW|CS_DBLCLKS;
    wc.lpfnWndProc=wndProc;wc.hInstance=instance;wc.hCursor=LoadCursorW(nullptr,IDC_ARROW);
    wc.hIcon=LoadIconW(nullptr,IDI_APPLICATION);wc.lpszClassName=L"MintPlayerWindow";
    RegisterClassExW(&wc);
    // Keep native window behaviors, but let WM_NCCALCSIZE extend our UI over the frame.
    RECT frame{0,0,(LONG)(1120*dpiScale),(LONG)(760*dpiScale)};
    HWND hwnd=CreateWindowExW(0,wc.lpszClassName,L"Musxi Player",WS_OVERLAPPEDWINDOW|WS_CLIPCHILDREN,
        CW_USEDEFAULT,CW_USEDEFAULT,frame.right-frame.left,frame.bottom-frame.top,nullptr,nullptr,instance,nullptr);
    if(!hwnd) {CoUninitialize();GdiplusShutdown(token);return 1;}
    BOOL dark=darkTheme;DwmSetWindowAttribute(hwnd,20,&dark,sizeof(dark));
    ShowWindow(hwnd,show);UpdateWindow(hwnd);
    if(wcsstr(GetCommandLineW(),L"--kugou")) {cloudLoginAfterInit=true;cloudClick(CloudHome);} else cloudConnect();
    MSG msg{};while(GetMessageW(&msg,nullptr,0,0)>0) {TranslateMessage(&msg);DispatchMessageW(&msg);}
    CoUninitialize();GdiplusShutdown(token);return (int)msg.wParam;
}
