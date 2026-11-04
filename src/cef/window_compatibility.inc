struct CompatibilityChoice {
    HWND window=nullptr;DWORD pid=0,thread=0;FILETIME created{};
    std::wstring title,program,windowClass;
};
std::map<std::string,CompatibilityChoice> compatibilityChoices;
std::unique_ptr<musxi::game_window::Window> compatibilityWindow;
ULONGLONG compatibilityCheck=0,compatibilitySequence=0;
bool describeCompatibilityWindow(HWND window,CompatibilityChoice& choice) {
    if(!musxi::game_window::candidate(window))return false;
    wchar_t title[513]{},windowClass[257]{},path[32768]{};
    if(!GetWindowTextW(window,title,513) || !GetClassNameW(window,windowClass,257))return false;
    choice.window=window;choice.thread=GetWindowThreadProcessId(window,&choice.pid);
    const auto process=OpenProcess(PROCESS_QUERY_LIMITED_INFORMATION,FALSE,choice.pid);if(!process)return false;
    FILETIME exit{},kernel{},user{};DWORD length=32768;
    const bool ok=GetProcessTimes(process,&choice.created,&exit,&kernel,&user) && QueryFullProcessImageNameW(process,0,path,&length);
    CloseHandle(process);if(!ok)return false;
    choice.title=title;choice.windowClass=windowClass;
    const auto slash=std::wstring(path).find_last_of(L"\\/");choice.program=path+(slash==std::wstring::npos?0:slash+1);
    return true;
}
bool restoreCompatibilityWindow() {
    if(compatibilityWindow && !compatibilityWindow->restore())return false;
    compatibilityWindow.reset();windowCompatibilityTarget.clear();
    windowCompatibilityStatus=windowCompatibility?"ready":"off";return true;
}
void refreshWindowCompatibility(bool force=false) {
    const auto now=GetTickCount64();if(!force && now<compatibilityCheck)return;compatibilityCheck=now+500;
    const auto previous=gameOverlayStateJson();
    if(hostClosing || !gameOverlayEnabled || !windowCompatibility) {
        if(restoreCompatibilityWindow()){windowCompatibility=false;windowCompatibilityStatus="off";compatibilityChoices.clear();}
        else windowCompatibilityStatus="error";
    } else if(compatibilityWindow) {
        if(!compatibilityWindow->alive())restoreCompatibilityWindow();
        else {
            const auto layout=compatibilityWindow->layout();
            if(layout==musxi::game_window::Window::Layout::changed)compatibilityWindow->respectUserChanges();
            windowCompatibilityStatus=compatibilityWindow->abandoned()?"userChanged":
                (compatibilityWindow->ready() && layout==musxi::game_window::Window::Layout::same)?"applied":"error";
        }
    }
    if(previous!=gameOverlayStateJson())publishGameOverlayState();
}
musxi::LibraryReply windowCompatibilityCommand(const std::string& command,const DesktopJson& data) {
    if(command=="gameOverlay.setWindowCompatibility") {
        if(data.size()!=1 || !data.contains("windowCompatibility") || !data.at("windowCompatibility").is_boolean())return {400,"无效通用窗口兼容参数",{}};
        const bool value=data.at("windowCompatibility").get<bool>();
        if(value && !gameOverlayEnabled)return {409,"请先开启游戏内悬浮组件扩展",{}};
        if(!value && !restoreCompatibilityWindow()) {
            windowCompatibilityStatus="error";publishGameOverlayState();return {500,"无法恢复所选游戏窗口，请检查权限或先关闭游戏后重试",{}};
        }
        windowCompatibility=value;windowCompatibilityStatus=value?"ready":"off";compatibilityChoices.clear();
        if(compatibilityWindow)refreshWindowCompatibility(true);
        publishGameOverlayState();return {0,{},gameOverlayStateJson()};
    }
    if(command=="gameOverlay.applyWindowCompatibility") {
        if(data.size()!=1 || !data.contains("id") || !data.at("id").is_string() || data.at("id").get_ref<const std::string&>().empty()
            || data.at("id").get_ref<const std::string&>().size()>48)return {400,"无效目标窗口",{}};
    }else if(!data.empty())return {400,"无效通用窗口兼容参数",{}};
    if(!gameOverlayEnabled || !windowCompatibility)return {409,"请先开启通用窗口兼容工具",{}};
    if(command=="gameOverlay.listCompatibilityWindows") {
        compatibilityChoices.clear();
        EnumWindows([](HWND window,LPARAM)->BOOL {
            if(compatibilityChoices.size()>=256)return FALSE;
            CompatibilityChoice choice;
            if(describeCompatibilityWindow(window,choice)) {
                const auto id=std::to_string(GetTickCount64())+"-"+std::to_string(++compatibilitySequence);
                compatibilityChoices.emplace(id,std::move(choice));
            }
            return TRUE;
        },0);
        auto windows=DesktopJson::array();
        for(const auto& [id,item]:compatibilityChoices)windows.push_back({{"id",id},{"title",CefString(item.title).ToString()},{"program",CefString(item.program).ToString()}});
        return {0,{},windows.dump()};
    }
    if(command=="gameOverlay.restoreWindowCompatibility") {
        if(!restoreCompatibilityWindow()) {
            windowCompatibilityStatus="error";publishGameOverlayState();return {500,"无法恢复所选游戏窗口，请检查权限或先关闭游戏后重试",{}};
        }
        publishGameOverlayState();return {0,{},gameOverlayStateJson()};
    }
    if(compatibilityWindow)return {409,"请先恢复当前窗口或结束管理，再选择另一个窗口",{}};
    const auto found=compatibilityChoices.find(data.at("id").get<std::string>());
    if(found==compatibilityChoices.end())return {409,"窗口列表已失效，请刷新后重新选择",{}};
    const auto selected=found->second;CompatibilityChoice current;
    if(!describeCompatibilityWindow(selected.window,current) || current.pid!=selected.pid || current.thread!=selected.thread
        || current.windowClass!=selected.windowClass || CompareFileTime(&current.created,&selected.created)!=0)
        return {409,"目标窗口已关闭、切换模式或不再可访问，请切到普通窗口模式并刷新列表",{}};
    auto item=std::make_unique<musxi::game_window::Window>(selected.window);
    const bool applied=item->apply();
    if(applied || !item->restore()) {
        windowCompatibilityTarget=CefString(current.title).ToString();compatibilityWindow=std::move(item);
    }
    compatibilityChoices.clear();windowCompatibilityStatus=applied?"applied":"error";publishGameOverlayState();
    if(!applied)return {500,"无法完整铺满所选窗口，已尝试恢复原布局；请确认游戏为普通窗口模式，并检查播放器与游戏权限",{}};
    return {0,{},gameOverlayStateJson()};
}
