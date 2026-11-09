// Read the legacy profile only for the one-time unified-window migration.
using DesktopJson=nlohmann::json;
bool desktopVisible=false,desktopLocked=false,desktopDual=true;
int desktopFontSize=30;std::string desktopColor="theme";
int desktopX=INT_MIN,desktopY=INT_MIN,desktopWidth=680,desktopHeight=180;
std::filesystem::path desktopSettingsPath;
std::string desktopStateJson();
void publishDesktop();
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
        desktopWidth=std::clamp(data.at("width").get<int>(),480,1600);desktopHeight=std::clamp(data.at("height").get<int>(),120,500);
    }catch(...) {
        desktopVisible=false;desktopLocked=false;desktopDual=true;desktopFontSize=30;desktopColor="theme";
        desktopX=desktopY=INT_MIN;desktopWidth=680;desktopHeight=180;
    }
}
void placeDesktop(HWND hwnd,RECT rect,UINT flags=0) {
    MONITORINFO monitor{sizeof(monitor)};
    GetMonitorInfoW(MonitorFromRect(&rect,MONITOR_DEFAULTTONEAREST),&monitor);
    const int width=std::min(rect.right-rect.left,monitor.rcWork.right-monitor.rcWork.left);
    const int height=std::min(rect.bottom-rect.top,monitor.rcWork.bottom-monitor.rcWork.top);
    const int x=std::clamp(rect.left,monitor.rcWork.left,monitor.rcWork.right-width);
    const int y=std::clamp(rect.top,monitor.rcWork.top,monitor.rcWork.bottom-height);
    SetWindowPos(hwnd,nullptr,x,y,width,height,SWP_NOACTIVATE|SWP_NOZORDER|flags);
}
