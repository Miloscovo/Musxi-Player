// Application-owned local catalog; UI receives public rows, never filesystem paths.
constexpr UINT ImportLocalFolder=WM_APP+47;
HWND applicationWindow=nullptr;
bool localView=false,localActive=false,localLoaded=false,localImportPending=false;
int localCurrent=-1;
Json localTracks=Json::array();
Json localFolders=Json::array();
size_t localIdHighWater=0;
std::string localFolderId;
std::future<Json> localScan;
std::atomic_bool localCancelled{false};
bool savePlaybackHistory(const fs::path& file);
bool localFormat(const fs::path& path) {
    auto extension=path.extension().wstring();CharLowerBuffW(extension.data(),static_cast<DWORD>(extension.size()));
    for(const auto* supported:{L".mp3",L".wav",L".flac",L".aac",L".m4a",L".mp4",L".ogg",L".oga",L".opus",L".wma"})
        if(extension==supported)return true;
    return false;
}
std::wstring localPathKey(const fs::path& path) {
    auto value=path.lexically_normal().wstring();CharLowerBuffW(value.data(),static_cast<DWORD>(value.size()));return value;
}
size_t localIdMaximum(const Json& tracks,const Json& folders,size_t number=0) {
    for(const auto* rows:{&tracks,&folders})for(const auto& row:*rows) {
        const auto id=row.value("id","");const auto colon=id.find(':');
        const auto digits=colon==std::string::npos?std::string{}:id.substr(colon+1);
        if(!digits.empty() && digits.find_first_not_of("0123456789")==std::string::npos)
            number=std::max(number,static_cast<size_t>(std::stoull(digits)));
    }
    return number;
}
void loadLocalCatalog(const fs::path& file) {
    if(localLoaded)return;localLoaded=true;
    try {
        if(!fs::exists(file))return;
        std::ifstream stream(file,std::ios::binary);Json saved;stream>>saved;
        if((saved.value("version",0)!=1 && saved.value("version",0)!=2) || !saved.at("tracks").is_array())throw std::runtime_error("Invalid catalog");
        for(const auto& row:saved.at("tracks")) {
            if(!row.at("id").is_string() || !row.at("path").is_string() || !row.at("name").is_string()
                || !row.at("duration").is_number() || !localFormat(fs::u8path(row.at("path").get<std::string>())))
                throw std::runtime_error("Invalid local track");
            const auto id=row.at("id").get<std::string>();
            if(id.rfind("local:",0)!=0 || id.size()<=6 || id.substr(6).find_first_not_of("0123456789")!=std::string::npos)
                throw std::runtime_error("Invalid local track ID");
            (void)std::stoull(id.substr(6));
        }
        localTracks=saved.at("tracks");
        for(auto& row:localTracks)row["name"]=fs::u8path(row.at("path").get<std::string>()).filename().u8string();
        localFolders=saved.value("folders",Json::array());
        if(!localFolders.is_array())throw std::runtime_error("Invalid folders");
        for(const auto& folder:localFolders)
            if(!folder.at("id").is_string() || !folder.at("path").is_string() || !folder.at("name").is_string())throw std::runtime_error("Invalid folder");
        // Older catalogs did not record import roots; retain songs under their containing folders.
        if(saved.value("version",0)==1)for(auto& row:localTracks) {
            const auto folder=fs::u8path(row.at("path").get<std::string>()).parent_path();
            auto found=std::find_if(localFolders.begin(),localFolders.end(),[&](const Json& item){return localPathKey(fs::u8path(item.at("path").get<std::string>()))==localPathKey(folder);});
            if(found==localFolders.end()) {
                localFolders.push_back({{"id","local-folder:"+std::to_string(localFolders.size()+1)},{"path",folder.u8string()},{"name",folder.filename().u8string()}});
                found=std::prev(localFolders.end());
            }
            row["folders"]=Json::array({found->at("id")});
        }
        localIdHighWater=localIdMaximum(localTracks,localFolders,std::max(localIdHighWater,saved.value("idHighWater",size_t{})));
    } catch(...) {notice(L"本地音乐记录读取失败，请重新导入文件夹");}
}
bool saveLocalCatalog(const Json& rows,const fs::path& file,const Json& folders=Json::array(),size_t highWater=0) {
    auto temporary=file;temporary+=L".tmp";
    std::ofstream stream(temporary,std::ios::binary);stream<<Json{{"version",2},{"tracks",rows},{"folders",folders},
        {"idHighWater",localIdMaximum(rows,folders,std::max(localIdHighWater,highWater))}}.dump(2);stream.close();
    return !stream.fail() && MoveFileExW(temporary.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH);
}
Json scanLocalFolder(const fs::path& folder,Json existing,Json folders=Json::array(),size_t number=0,bool rescan=false) {
    number=localIdMaximum(existing,folders,number);
    const auto nextId=[&] {if(number==SIZE_MAX)throw std::overflow_error("Local ID limit");return std::to_string(++number);};
    const auto folderKey=localPathKey(folder);
    auto found=std::find_if(folders.begin(),folders.end(),[&](const Json& item){return localPathKey(fs::u8path(item.at("path").get<std::string>()))==folderKey;});
    if(found==folders.end()) {
        const auto name=folder.filename().empty()?folder.root_name():folder.filename();
        folders.push_back({{"id","local-folder:"+nextId()},{"path",folder.u8string()},{"name",name.u8string()}});
        found=std::prev(folders.end());
    }
    const auto folderId=found->at("id").get<std::string>();
    std::map<std::wstring,bool> seen;
    for(const auto& row:existing) {
        seen[localPathKey(fs::u8path(row.at("path").get<std::string>()))]=true;
    }
    std::set<std::wstring> foundPaths;
    size_t added=0,skipped=0,removed=0;std::error_code error;
    fs::recursive_directory_iterator it(folder,rescan?fs::directory_options::none:fs::directory_options::skip_permission_denied,error),end;
    if(error)throw std::runtime_error("无法读取所选文件夹");
    while(it!=end && !localCancelled.load()) {
        const auto entry=*it;
        if(entry.is_symlink(error))it.disable_recursion_pending();
        if(!error && entry.is_regular_file(error) && !error && localFormat(entry.path())) {
            const auto key=localPathKey(entry.path());
            foundPaths.insert(key);
            if(!seen.count(key))try {
                musxi::FfmpegDecoder decoder(&localCancelled);decoder.open(entry.path().wstring());
                existing.push_back({{"id","local:"+nextId()},{"path",entry.path().u8string()},{"folders",Json::array({folderId})},
                    {"name",entry.path().filename().u8string()},{"artist","未知歌手"},{"album",""},{"cover",""},
                    {"duration",std::max<std::int64_t>(0,decoder.info().durationMs)},{"count",0},{"editable",false}});
                seen[key]=true;++added;
            } catch(...) {++skipped;}
            else for(auto& row:existing)if(localPathKey(fs::u8path(row.at("path").get<std::string>()))==key) {
                auto& memberships=row["folders"];if(!memberships.is_array())memberships=Json::array();
                if(std::find(memberships.begin(),memberships.end(),folderId)==memberships.end())memberships.push_back(folderId);
                break;
            }
        }
        if(error && rescan)throw std::runtime_error("无法读取文件夹内容");
        error.clear();it.increment(error);
        if(error){if(rescan)throw std::runtime_error("无法读取文件夹内容");++skipped;error.clear();}
    }
    if(rescan) {
        if(localCancelled.load())throw std::runtime_error("扫描已取消");
        for(auto& row:existing)if(!foundPaths.count(localPathKey(fs::u8path(row.at("path").get<std::string>())))) {
            auto& memberships=row["folders"];
            if(!memberships.is_array())continue;
            auto before=memberships.size();
            memberships.erase(std::remove(memberships.begin(),memberships.end(),Json(folderId)),memberships.end());
            removed+=before-memberships.size();
        }
        existing.erase(std::remove_if(existing.begin(),existing.end(),[](const Json& row){return row.value("folders",Json::array()).empty();}),existing.end());
    }
    return {{"tracks",existing},{"folders",folders},{"added",added},{"skipped",skipped},{"removed",removed},{"idHighWater",number}};
}
void startLocalScan(const fs::path& folder) {
    if(localScan.valid())throw std::runtime_error("正在导入音乐，请稍后再试");
    loadLocalCatalog(cloud::dataDir()/L"local-music.json");localCancelled=false;
    localScan=std::async(std::launch::async,[folder,rows=localTracks,folders=localFolders,number=localIdHighWater]{return scanLocalFolder(folder,rows,folders,number);});
}
void startLocalRescan(const std::string& id) {
    loadLocalCatalog(cloud::dataDir()/L"local-music.json");localCancelled=false;
    Json selected=Json::array();for(const auto& folder:localFolders)if(id=="musxi:local" || folder.at("id")==id)selected.push_back(folder);
    if(id!="musxi:local" && selected.empty())throw std::runtime_error("本地文件夹不存在");
    localScan=std::async(std::launch::async,[selected,rows=localTracks,folders=localFolders,number=localIdHighWater]() mutable {
        size_t added=0,removed=0,skipped=0;
        for(const auto& folder:selected) {
            auto result=scanLocalFolder(fs::u8path(folder.at("path").get<std::string>()),rows,folders,number,true);
            rows=std::move(result["tracks"]);number=result.at("idHighWater").get<size_t>();
            added+=result.value("added",size_t{});removed+=result.value("removed",size_t{});skipped+=result.value("skipped",size_t{});
        }
        return Json{{"tracks",rows},{"folders",folders},{"idHighWater",number},{"added",added},{"removed",removed},{"skipped",skipped},{"rescan",true}};
    });
}
void chooseLocalFolder() {
    IFileOpenDialog* dialog=nullptr;
    auto result=CoCreateInstance(CLSID_FileOpenDialog,nullptr,CLSCTX_INPROC_SERVER,IID_PPV_ARGS(&dialog));
    if(SUCCEEDED(result)) {
        FILEOPENDIALOGOPTIONS options{};result=dialog->GetOptions(&options);
        if(SUCCEEDED(result))result=dialog->SetOptions(options|FOS_PICKFOLDERS|FOS_FORCEFILESYSTEM|FOS_PATHMUSTEXIST);
        if(SUCCEEDED(result))result=dialog->SetTitle(L"导入音乐文件夹");
        if(SUCCEEDED(result))result=dialog->Show(GetActiveWindow());
        if(SUCCEEDED(result)) {
            IShellItem* item=nullptr;result=dialog->GetResult(&item);
            if(SUCCEEDED(result)) {
                PWSTR path=nullptr;result=item->GetDisplayName(SIGDN_FILESYSPATH,&path);
                if(SUCCEEDED(result))try {startLocalScan(fs::path(path));} catch(...) {result=E_FAIL;}
                CoTaskMemFree(path);item->Release();
            }
        }
        dialog->Release();
    }
    localImportPending=false;
    if(FAILED(result) && result!=HRESULT_FROM_WIN32(ERROR_CANCELLED))notice(L"无法导入音乐文件夹，请检查文件夹权限后重试");
}
void localTick() {
    if(!localScan.valid() || localScan.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready)return;
    try {
        auto result=localScan.get();auto rows=result.at("tracks");
        auto folders=result.at("folders");
        const auto highWater=result.at("idHighWater").get<size_t>();
        const bool saved=saveLocalCatalog(rows,cloud::dataDir()/L"local-music.json",folders,highWater);
        if(result.value("rescan",false) && !saved)throw std::runtime_error("保存失败");
        localIdHighWater=highWater;
        if(result.value("rescan",false)) {
            std::set<std::string> retained;for(const auto& row:rows)retained.insert(row.at("id").get<std::string>());
            std::set<std::string> removed;for(const auto& row:localTracks)if(!retained.count(row.at("id").get<std::string>()))removed.insert(row.at("id").get<std::string>());
            for(int index=(int)cloudQueue.size()-1;index>=0;--index) {
                const auto id=cloudQueue[index].value("id","");
                if(removed.count(id)) {cloudQueue.erase(cloudQueue.begin()+index);if(index<=cloudCurrent)--cloudCurrent;}
            }
            if(removed.count(unplayableTrackId))cancelUnplayableSkip();
        }
        localTracks=std::move(rows);
        localFolders=std::move(folders);
        if(result.value("rescan",false)) {
            localCurrent=-1;for(int i=0;i<(int)localTracks.size();++i)if(localTracks[i].value("id","")==audioTrackId)localCurrent=i;
            savePlaybackHistory(cloud::dataDir()/L"playback-history.json");
            notice(L"重新扫描完成：新增 "+std::to_wstring(result.value("added",size_t{}))+L" 首，移除 "+std::to_wstring(result.value("removed",size_t{}))+L" 首，跳过 "+std::to_wstring(result.value("skipped",size_t{}))+L" 个文件");return;
        }
        notice(L"已导入 "+std::to_wstring(result.value("added",size_t{}))+L" 首歌曲，跳过 "+std::to_wstring(result.value("skipped",size_t{}))+
            L" 个无法读取的文件"+(saved?L"":L"；本机记录保存失败，请检查存储空间"));
    } catch(...) {notice(L"音乐文件夹扫描失败，请检查文件夹权限后重试");}
}
bool inLocalFolder(const Json& row,const std::string& id) {
    const auto folders=row.value("folders",Json::array());
    return std::find(folders.begin(),folders.end(),id)!=folders.end();
}
Json localPlaylistRows() {
    auto result=Json::array();
    for(const auto& folder:localFolders) {
        const auto id=folder.at("id").get<std::string>();size_t count=0;
        for(const auto& row:localTracks)if(inLocalFolder(row,id))++count;
        result.push_back({{"id",id},{"name",folder.at("name")},{"artist",""},{"cover",""},{"duration",0},{"count",count},{"editable",false}});
    }
    return result;
}
Json localVisibleTracks() {
    if(localFolderId.empty())return localTracks;
    auto result=Json::array();for(const auto& row:localTracks)if(inLocalFolder(row,localFolderId))result.push_back(row);return result;
}
void playLocal(int index,bool resetFailures=true) {
    if(index<0 || index>=static_cast<int>(localTracks.size()))return;
    if(!closeAudio()){notice(L"无法释放上一首歌曲，请重试");return;}
    ++cloudGeneration;++libraryOperationId;libraryNextPlay=nullptr;cloudAutoNext=false;cloudCurrent=-1;
    if(resetFailures)cancelUnplayableSkip();else unplayableSkipAt=0;
    libraryQualityChange=false;currentQuality.clear();
    for(int i=0;i<(int)cloudQueue.size();++i)if(cloudQueue[i].value("id","")==localTracks[index].value("id","")){cloudCurrent=i;break;}
    localActive=true;localCurrent=index;const auto& row=localTracks[index];
    currentPlaybackTrack=row;
    libraryPlayPaused=false;libraryOperationKind="audio";libraryOperationStatus="pending";libraryOperationError.clear();
    libraryPlayGeneration=cloudGeneration;libraryPlayTrackId=row.at("id").get<std::string>();
    cloudNowTitle=cloud::toWide(row.value("name",""));cloudNowArtist=cloud::toWide(row.value("artist",""));cloudNowCover.clear();
    if(!loadAndPlayAudio(fs::u8path(row.at("path").get<std::string>()).wstring(),libraryPlayTrackId)) {
        libraryOperationStatus="failed";libraryOperationError="无法打开本地歌曲，文件可能已移动或删除";
        if(scheduleFailedTrackSkip(libraryPlayTrackId,0))notice(L"本地歌曲播放失败，正在尝试下一首");
    }
}
