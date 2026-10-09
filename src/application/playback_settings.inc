std::string defaultAudioQuality="128",preferredOutputDevice;
bool validDefaultQuality(const std::string& quality) {return quality=="128" || quality=="320" || quality=="flac";}
void loadPlaybackSettings(const fs::path& file) {
    try {
        if(!fs::exists(file) || fs::file_size(file)>8192)return;
        std::ifstream stream(file,std::ios::binary);Json saved;stream>>saved;
        const auto quality=saved.at("defaultQuality").get<std::string>();
        const auto device=saved.at("outputDevice").get<std::string>();
        if(!validDefaultQuality(quality) || device.size()>1024 || device.find('\0')!=std::string::npos)return;
        defaultAudioQuality=quality;preferredOutputDevice=device;
    } catch(...) { /* Invalid or older settings leave playback defaults intact. */ }
}
bool savePlaybackSettings(const fs::path& file,const std::string& quality,const std::string& device) {
    auto temporary=file;temporary+=L".tmp";
    try {
        fs::create_directories(file.parent_path());
        {std::ofstream stream(temporary,std::ios::binary);stream<<Json{{"defaultQuality",quality},{"outputDevice",device}}.dump();stream.close();if(!stream)throw std::runtime_error("Settings write failed");}
        if(!MoveFileExW(temporary.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("Settings replace failed");
        return true;
    } catch(...) {DeleteFileW(temporary.c_str());return false;}
}
Json playbackSettingsSnapshot() {
    Json devices=Json::array();
    for(const auto& device:musxi::audioOutputDevices())devices.push_back({{"id",utf8(device.id)},{"name",utf8(device.name)}});
    return {{"defaultQuality",defaultAudioQuality},{"outputDevice",preferredOutputDevice},{"devices",devices}};
}
