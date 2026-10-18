using ShortcutJson=nlohmann::json;
musxi::shortcuts::Registry shortcutRegistry;
std::filesystem::path shortcutPath;
bool shortcutRecording=false,shortcutEditing=false;
int shortcutLastVolume=50;
bool shortcutForeground() {return GetForegroundWindow()==testWindow;}
void refreshShortcutSuspension() {
    const bool suspended=shortcutRecording || (shortcutEditing && shortcutForeground());
    if(suspended!=shortcutRegistry.suspended){shortcutRegistry.suspended=suspended;shortcutRegistry.restore();}
}
void initShortcuts(const std::filesystem::path& profile) {
    shortcutPath=profile/(smoke?L"shortcuts-smoke.json":L"shortcuts.json");
    shortcutRegistry.acquire=[](int i,musxi::shortcuts::Binding b){return RegisterHotKey(testWindow,0x5100+i,b.modifiers|MOD_NOREPEAT,b.key)!=FALSE;};
    shortcutRegistry.release=[](int i){UnregisterHotKey(testWindow,0x5100+i);};
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
