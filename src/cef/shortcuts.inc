using ShortcutJson=nlohmann::json;
musxi::shortcuts::Registry shortcutRegistry;
std::filesystem::path shortcutPath;
bool shortcutRecording=false,shortcutEditing=false;
int shortcutLastVolume=50;
HHOOK shortcutHook=nullptr;
std::array<bool,musxi::shortcuts::actions.size()> shortcutHooked{};
musxi::shortcuts::PressedKeys shortcutPressed;
std::array<musxi::shortcuts::Binding,2> shortcutFocusPending{},shortcutFocusReleased{};
std::array<ULONGLONG,2> shortcutFocusReleaseAt{};
LRESULT CALLBACK observeShortcut(int code,WPARAM message,LPARAM data) {
    std::array<musxi::shortcuts::Binding,2> released{};
    if(code==HC_ACTION) {
        const auto& event=*reinterpret_cast<const KBDLLHOOKSTRUCT*>(data);
        const bool pressed=message==WM_KEYDOWN || message==WM_SYSKEYDOWN;
        const bool first=shortcutPressed.update(event.vkCode,pressed);
        if(first) {
            const auto modifiers=shortcutPressed.modifiers();
            for(size_t i=0;i<shortcutHooked.size();++i) {
                const auto binding=shortcutRegistry.settings.bindings[i];
                if(shortcutHooked[i] && shortcutRegistry.registered[i] && binding.key==event.vkCode && binding.modifiers==modifiers) {
                    if(i==11 || i==12)shortcutFocusPending[i-11]=binding;
                    else PostMessageW(testWindow,WM_HOTKEY,0x5100+i,MAKELPARAM(modifiers,binding.key));
                }
            }
        }
        for(size_t i=0;i<released.size();++i)
            if(!pressed && event.vkCode==shortcutFocusPending[i].key){released[i]=shortcutFocusPending[i];shortcutFocusPending[i]={};}
    }
    const auto result=CallNextHookEx(shortcutHook,code,message,data);
    for(size_t i=0;i<released.size();++i)if(released[i].key && shortcutHooked[i+11] && shortcutRegistry.registered[i+11]) {
        shortcutFocusReleased[i]=released[i];
        // shortcut: allow 50ms for the game to process key-up; use acknowledged handoff if releases are still missed.
        shortcutFocusReleaseAt[i]=GetTickCount64()+50;
    }
    return result;
}
bool acquireShortcut(int index,musxi::shortcuts::Binding binding) {
    if(!gameOverlayEnabled)return RegisterHotKey(testWindow,0x5100+index,binding.modifiers|MOD_NOREPEAT,binding.key)!=FALSE;
    if(!shortcutHook) {
        for(unsigned key=0;key<256;++key)shortcutPressed.down[key]=(GetAsyncKeyState(key)&0x8000)!=0;
        shortcutPressed.down[VK_CONTROL]=shortcutPressed.down[VK_SHIFT]=shortcutPressed.down[VK_MENU]=false;
        HMODULE module=nullptr;
        if(!GetModuleHandleExW(GET_MODULE_HANDLE_EX_FLAG_FROM_ADDRESS|GET_MODULE_HANDLE_EX_FLAG_UNCHANGED_REFCOUNT,
                              reinterpret_cast<LPCWSTR>(&observeShortcut),&module))return false;
        shortcutHook=SetWindowsHookExW(WH_KEYBOARD_LL,observeShortcut,module,0);
        if(!shortcutHook)return false;
    }
    shortcutHooked[index]=true;return true;
}
void releaseShortcut(int index) {
    if(index==11 || index==12){shortcutFocusPending[index-11]={};shortcutFocusReleased[index-11]={};shortcutFocusReleaseAt[index-11]=0;}
    // Module state changes before restore releases the previous backend.
    if(!shortcutHooked[index]){UnregisterHotKey(testWindow,0x5100+index);return;}
    shortcutHooked[index]=false;
    if(std::none_of(shortcutHooked.begin(),shortcutHooked.end(),[](bool active){return active;})) {
        UnhookWindowsHookEx(shortcutHook);shortcutHook=nullptr;
    }
}
bool shortcutForeground() {return GetForegroundWindow()==testWindow;}
void refreshShortcutSuspension() {
    const bool suspended=shortcutRecording || (shortcutEditing && shortcutForeground());
    if(suspended!=shortcutRegistry.suspended || shortcutRecording!=shortcutRegistry.recording || gameOverlayEnabled!=shortcutRegistry.overlayEnabled) {
        shortcutRegistry.suspended=suspended;shortcutRegistry.recording=shortcutRecording;shortcutRegistry.overlayEnabled=gameOverlayEnabled;shortcutRegistry.restore();
    }
    for(size_t i=0;i<shortcutFocusReleased.size();++i)if(shortcutFocusReleased[i].key && GetTickCount64()>=shortcutFocusReleaseAt[i]) {
        const auto binding=shortcutFocusReleased[i];shortcutFocusReleased[i]={};shortcutFocusReleaseAt[i]=0;
        if(shortcutHooked[i+11] && shortcutRegistry.registered[i+11])PostMessageW(testWindow,WM_HOTKEY,0x5100+i+11,MAKELPARAM(binding.modifiers,binding.key));
    }
}
void initShortcuts(const std::filesystem::path& profile) {
    shortcutRegistry.overlayEnabled=gameOverlayEnabled;
    shortcutPath=profile/(smoke?L"shortcuts-smoke.json":L"shortcuts.json");
    shortcutRegistry.acquire=acquireShortcut;
    shortcutRegistry.release=releaseShortcut;
    if(!smoke)try {
        if(std::filesystem::is_regular_file(shortcutPath) && std::filesystem::file_size(shortcutPath)<=8192) {
            std::ifstream file(shortcutPath,std::ios::binary);shortcutRegistry.settings=musxi::shortcuts::parse(ShortcutJson::parse(file));
        }
    }catch(...) { /* Invalid settings keep every shortcut unbound. */ }
}
bool saveShortcuts(const musxi::shortcuts::Settings& settings) {
    auto temp=shortcutPath;temp+=L".tmp";
    try {
        {std::ofstream file(temp,std::ios::binary);file<<musxi::shortcuts::json(settings).dump();file.close();if(!file)throw std::runtime_error("write");}
        if(!MoveFileExW(temp.c_str(),shortcutPath.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("replace");
        return true;
    }catch(...) {DeleteFileW(temp.c_str());return false;}
}
ShortcutJson shortcutSnapshot() {
    auto data=musxi::shortcuts::json(shortcutRegistry.settings);data["failed"]=shortcutRegistry.failed();data["suspended"]=shortcutRegistry.suspended;return data;
}
musxi::LibraryReply executeShortcut(const std::string& action) {
    using namespace musxi;
    if(action=="overlayVisibility" || action=="overlayEditing" || action=="overlayPlayer")return executeGameOverlayShortcut(action);
    if(action=="visibility") {
        if(!testWindow)return {409,"窗口尚未就绪",{}};
        if(!IsWindowVisible(testWindow) || IsIconic(testWindow))showFromTray(testWindow);
        else if(!hideToTray(testWindow))return {500,"无法创建系统托盘图标",{}};
        return {0,{},"true"};
    }
    const auto state=applicationPlayerState();
    if(state.volumePercent>0)shortcutLastVolume=state.volumePercent;
    PlayerResult result;
    if(action=="volumeUp" || action=="volumeDown" || action=="mute") {
        const auto value=action=="mute"?(state.volumePercent?0:shortcutLastVolume):std::clamp(state.volumePercent+(action=="volumeUp"?5:-5),0,100);
        result=applicationPlayerCommand(PlayerCommand::SetVolume,value);
    } else {
        if(!state.opened)return {0,{},"true"};
        if(action=="playPause")result=applicationPlayerCommand(state.phase!="failed" && state.requestedPlaying?PlayerCommand::Pause:PlayerCommand::Resume);
        else if(action=="forward" || action=="backward") {
            const auto position=std::clamp<int64_t>(static_cast<int64_t>(state.positionMs)+(action=="forward"?5000:-5000),0,state.durationMs);
            result=applicationPlayerCommand(PlayerCommand::Seek,static_cast<uint32_t>(position));
        } else if(action=="next" || action=="previous") {
            return applicationLibrary("library.skip",ShortcutJson{{"delta",action=="next"?1:-1}}.dump());
        } else if(action=="playbackOrder") {
            const auto reply=applicationLibrary("library.getState","{}");if(reply.code)return reply;
            const auto order=ShortcutJson::parse(reply.json).value("playbackOrder","sequential");
            return applicationLibrary("library.setPlaybackOrder",ShortcutJson{{"order",order=="sequential"?"random":order=="random"?"repeat-one":"sequential"}}.dump());
        } else return {400,"无效快捷键功能",{}};
    }
    return result?LibraryReply{0,{},"true"}:LibraryReply{409,"暂时无法执行此操作",{}};
}
