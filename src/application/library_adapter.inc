// Included in namespace musxi after the legacy application implementation.
// A narrow adapter over existing business operations; no CEF dependencies.
namespace {
Json publicRows(const Json& rows,size_t offset=0,size_t limit=100) {
    Json result=Json::array();
    for(size_t i=offset;i<rows.size() && i-offset<limit;++i) {
        const auto& row=rows[i];
        result.push_back({{"id",row.value("id","")},{"name",row.value("name","")},
            {"artist",row.value("artist","")},{"album",row.value("album","")},{"cover",row.value("cover","")},
            {"duration",row.value("duration",0.0)},{"count",row.value("count",0)},
            {"editable",row.value("editable",false)}});
    }
    return result;
}
std::string requiredText(const Json& p,const char* key,size_t limit=1024) {
    if(!p.contains(key) || !p[key].is_string()) throw std::invalid_argument("缺少字符串参数");
    auto s=p[key].get<std::string>();
    if(s.empty() || s.size()>limit) throw std::invalid_argument("参数长度无效");
    return s;
}
bool knownTrack(const std::string& id) {
    if(opened && id==audioTrackId)return true;
    for(const auto* list:{&searchTracks,&cloudTracks,&cloudQueue})
        for(const auto& row:*list) if(row.value("id","")==id) return true;
    return false;
}
}
LibraryReply applicationLibrary(const std::string& name,const std::string& parameters) {
    try {
        (void)applicationPlayerState(); // Assert Application thread affinity.
        const auto p=Json::parse(parameters);
        if(!p.is_object()) return {400,"参数必须为对象",{}};
        const auto reply=[](const Json& value)->LibraryReply {return {0,{},value.dump()};};
        if(name=="library.setPlaybackOrder") {
            if(p.size()!=1)return {400,"无效播放顺序参数",{}};
            const auto order=requiredText(p,"order");
            if(order!="sequential" && order!="random" && order!="repeat-one")return {400,"无效播放顺序",{}};
            playbackOrder=order;
            return reply({{"id",std::to_string(++libraryOperationId)}});
        }
        if(name=="library.qualities" || name=="library.setQuality") {
            if(p.size()!=(name=="library.qualities"?1:2))return {400,"无效音质参数",{}};
            const auto id=requiredText(p,"id");
            if(!opened || id!=audioTrackId)return {409,"当前歌曲已切换",{}};
            if(id.rfind("local:",0)==0)return {409,"本地歌曲使用原始音质",{}};
            if(!cloudConnected)return {409,"请先扫码登录",{}};
            if(cloudBusy() || !libraryNextPlay.is_null())return {409,"正在处理请求，请稍后重试",{}};
            Json request={{"op","qualities"},{"id",id}};
            if(name=="library.setQuality") {
                const auto quality=requiredText(p,"quality");
                if(quality!="128" && quality!="320" && quality!="flac")return {400,"无效音质",{}};
                if(libraryQualities.value("id","")!=id || !libraryQualities.contains("options") ||
                    std::none_of(libraryQualities["options"].begin(),libraryQualities["options"].end(),[&](const Json& option){return option.value("id","")==quality;}))return {409,"请选择已确认可用的音质",{}};
                const auto dir=cloud::dataDir()/L"cloud-cache"/std::to_wstring(GetCurrentProcessId());
                request={{"op","audio"},{"id",id},{"quality",quality},{"cacheDir",dir.u8string()}};
                ++cloudGeneration;libraryPlayGeneration=cloudGeneration;libraryPlayTrackId=id;
                libraryPlayPaused=!audioBackend().snapshot().requestedPlaying;libraryQualityChange=true;cloudAutoNext=false;
            } else libraryQualities=Json::object();
            cloudRequest(request);
            if(!cloudBusy())return {503,"无法请求音质信息",{}};
            ++libraryOperationId;libraryOperationKind=request.value("op","");libraryOperationStatus="pending";libraryOperationError.clear();
            return reply({{"id",std::to_string(libraryOperationId)}});
        }
        if(name=="library.importLocal") {
            if(!p.empty())return {400,"无效导入参数",{}};
            if(localImportPending || localScan.valid())return {409,"正在导入音乐，请稍后再试",{}};
            if(!applicationWindow)return {503,"文件夹选择器尚未就绪",{}};
            localImportPending=true;
            if(!PostMessageW(applicationWindow,ImportLocalFolder,0,0)) {localImportPending=false;return {500,"无法打开文件夹选择器",{}};}
            return reply({{"id",std::to_string(++libraryOperationId)}});
        }
        if(name=="library.open" && p.size()==1 && p.value("id","")=="musxi:local") {
            loadLocalCatalog(cloud::dataDir()/L"local-music.json");localView=true;
            localFolderId.clear();
            return reply({{"id",std::to_string(++libraryOperationId)}});
        }
        if(name=="library.open" && p.size()==1 && p.value("id","").rfind("local-folder:",0)==0) {
            loadLocalCatalog(cloud::dataDir()/L"local-music.json");
            const auto id=requiredText(p,"id");
            if(std::none_of(localFolders.begin(),localFolders.end(),[&](const Json& row){return row.at("id")==id;}))return {404,"本地歌单不存在",{}};
            localView=true;localFolderId=id;
            return reply({{"id",std::to_string(++libraryOperationId)}});
        }
        if(name=="library.playPlaylist") {
            if(p.size()!=1)return {400,"无效歌单播放参数",{}};
            const auto id=requiredText(p,"id");
            const auto currentId=localView?(localFolderId.empty()?"musxi:local":localFolderId):cloudPlaylistId;
            if(id!=currentId)return {409,"歌单已切换，请重试",{}};
            if(!localView && !cloudConnected)return {409,"请先扫码登录",{}};
            if(!libraryNextPlay.is_null() || (cloudBusy() && cloudOperation!="audio"))return {409,"正在处理请求，请稍后重试",{}};
            const auto tracks=localView?localVisibleTracks():cloudTracks;
            if(tracks.empty())return {409,"歌单中暂无歌曲",{}};
            const auto previousQueue=cloudQueue;
            std::set<std::string> ids;for(const auto& row:cloudQueue)ids.insert(row.value("id",""));
            for(const auto& row:tracks)if(ids.insert(row.value("id","")).second)cloudQueue.push_back(row);
            const auto result=applicationLibrary("library.play",Json{{"source","queue"},{"id",tracks[0].value("id","")}}.dump());
            if(result.code!=0)cloudQueue=previousQueue;
            return result;
        }
        if(name=="library.getState") {
            if(p.size()>1 || (!p.empty() && !p.contains("page"))) return {400,"无效参数",{}};
            if(p.contains("page") && !p["page"].is_number_integer()) return {400,"页码必须为整数",{}};
            const int page=p.value("page",1);if(page<1 || page>100000) return {400,"无效页码",{}};
            const auto tracks=localView?localVisibleTracks():cloudTracks;
            std::string localName="本地音乐";
            for(const auto& folder:localFolders)if(folder.at("id")==localFolderId)localName=folder.at("name").get<std::string>();
            return reply({{"connected",cloudConnected},{"busy",localView?(localImportPending || localScan.valid()):(cloudBusy() || !libraryNextPlay.is_null())},
                {"user",utf8(cloudUser)},{"status",utf8(cloudStatus)},{"notice",utf8(toast)},
                {"qr",libraryQr},{"avatar",libraryAvatar},{"playlists",publicRows(cloudPlaylists,0,10000)},{"localPlaylists",localPlaylistRows()},
                {"tracks",publicRows(tracks,(page-1)*50,50)},{"trackCount",tracks.size()},
                {"playlistId",localView?(localFolderId.empty()?"musxi:local":localFolderId):cloudPlaylistId},{"playlistName",localView?localName:utf8(cloudPlaylistName)},
                {"search",publicRows(searchTracks)},{"keywords",searchQuery},{"searchPage",searchPage},
                {"searchMore",searchMore},{"searchTotal",searchTotal},
                {"queue",publicRows(cloudQueue,0,cloudQueue.size())},{"queueCurrentId",opened?audioTrackId:""},
                {"playbackOrder",playbackOrder},
                {"qualities",{{"id",libraryQualities.value("id","")},{"current",opened?currentQuality:""},{"options",libraryQualities.value("options",Json::array())}}},
                {"now",{{"name",opened?utf8(cloudNowTitle):""},{"artist",opened?utf8(cloudNowArtist):""},{"cover",opened?cloudNowCover:""},
                    {"liked",opened && libraryNowMenu.value("id","")==audioTrackId && libraryNowMenu.value("liked",false)},
                    {"canFavorite",opened && libraryNowMenu.value("id","")==audioTrackId && libraryNowMenu.value("canFavorite",false)}}},
                {"menu",{{"id",libraryMenu.value("id","")},{"liked",libraryMenu.value("liked",false)},
                    {"canFavorite",libraryMenu.value("canFavorite",false)},{"playlists",publicRows(libraryMenu.value("playlists",Json::array()))}}},
                {"operation",{{"id",std::to_string(libraryOperationId)},{"status",libraryOperationStatus},
                    {"kind",libraryOperationKind},{"error",libraryOperationError}}}});
        }
        if(name=="library.image") {
            if(p.size()!=1) return {400,"无效图片参数",{}};
            const auto url=requiredText(p,"url",2048);bool known=url==cloudNowCover;
            for(const auto* rows:{&cloudTracks,&searchTracks,&cloudPlaylists,&cloudQueue})
                for(const auto& row:*rows) if(row.value("cover","")==url) known=true;
            if(!known) return {403,"只允许加载媒体库中的封面",{}};
            auto found=coverCache.find(url);
            if(found!=coverCache.end() && !found->second->data.empty()) return reply(found->second->data);
            if(webWantedCovers.size()<256 || webWantedCovers.count(url)) webWantedCovers[url]=GetTickCount64()+30000;
            return reply("");
        }
        if(name=="library.cancel") {
            if(!p.empty()) return {400,"无效取消参数",{}};
            if(libraryOperationStatus=="pending" && libraryOperationKind=="audio") {
                if(audioTrackId==libraryPlayTrackId && audioBackend().snapshot().pending)closeAudio();
                ++cloudGeneration;libraryNextPlay=nullptr;libraryOperationStatus="cancelled";
            }
            return reply({{"id",std::to_string(libraryOperationId)}});
        }
        if(name=="library.queueNext" || name=="library.queueRemove" || name=="library.queueClear") {
            if(name=="library.queueClear") {
                if(!p.empty())return {400,"无效清空参数",{}};
            } else if(p.size()!=(name=="library.queueNext"?2:1))return {400,"无效队列参数",{}};
            if(!libraryNextPlay.is_null())return {409,"正在准备播放，请稍后重试",{}};
            if(name=="library.queueClear") {cloudQueue=Json::array();cloudCurrent=-1;cloudAutoNext=false;}
            else {
                const auto id=requiredText(p,"id");
                auto found=std::find_if(cloudQueue.begin(),cloudQueue.end(),[&](const Json& row){return row.value("id","")==id;});
                if(name=="library.queueRemove") {
                    if(found==cloudQueue.end())return {404,"歌曲不在播放列表中",{}};
                    const int index=(int)std::distance(cloudQueue.begin(),found);
                    cloudQueue.erase(found);if(index<=cloudCurrent)--cloudCurrent;
                } else {
                    const auto source=requiredText(p,"source");
                    if(source!="search" && source!="library" && source!="local")return {400,"无效列表",{}};
                    const auto& list=source=="local"?localTracks:source=="search"?searchTracks:cloudTracks;
                    const auto row=std::find_if(list.begin(),list.end(),[&](const Json& value){return value.value("id","")==id;});
                    if(row==list.end())return {404,"歌曲不在当前列表",{}};
                    const auto track=*row;
                    if(found!=cloudQueue.end()) {
                        const int index=(int)std::distance(cloudQueue.begin(),found);
                        if(opened && id==audioTrackId)return {409,"这首歌曲正在播放",{}};
                        cloudQueue.erase(found);if(index<=cloudCurrent)--cloudCurrent;
                    }
                    cloudQueue.insert(cloudQueue.begin()+std::min(std::max(0,cloudCurrent+1),(int)cloudQueue.size()),track);
                    notice(L"已加入下一首播放");
                }
            }
            return reply({{"id",std::to_string(libraryOperationId)}});
        }
        if(name=="library.play" || name=="library.skip") {
            libraryQualityChange=false;
            const auto source=p.value("source","");
            const auto queued=source=="queue"?std::find_if(cloudQueue.begin(),cloudQueue.end(),[&](const Json& row){return row.value("id","")==p.value("id","");}):cloudQueue.end();
            if(name=="library.play" && (source=="local" || (queued!=cloudQueue.end() && queued->value("id","").rfind("local:",0)==0))) {
                if(p.size()!=2)return {400,"无效播放参数",{}};
                const auto id=requiredText(p,"id");
                const auto found=std::find_if(localTracks.begin(),localTracks.end(),[&](const Json& row){return row.value("id","")==id;});
                if(found==localTracks.end())return {404,"本地歌曲不存在",{}};
                if(source=="local")enqueuePlayedTrack(*found);
                playLocal(static_cast<int>(std::distance(localTracks.begin(),found)));
                return reply({{"id",std::to_string(libraryOperationId)}});
            }
            if(name=="library.play" && !cloudConnected)return {409,"请先扫码登录",{}};
            Json next;
            if(name=="library.skip") {
                if(p.size()!=1 || !p.contains("delta") || !p["delta"].is_number_integer()) return {400,"无效切歌参数",{}};
                const int delta=p["delta"].get<int>();if(delta!=1 && delta!=-1) return {400,"无效方向",{}};
                if(cloudQueue.empty()) return {409,"播放队列为空",{}};
                int base=cloudCurrent;
                if(libraryOperationStatus=="pending" && libraryOperationKind=="audio")
                    for(int i=0;i<(int)cloudQueue.size();++i)if(cloudQueue[i].value("id","")==libraryPlayTrackId){base=i;break;}
                const int index=nextQueueIndex(delta,base);
                if(cloudQueue[index].value("id","").rfind("local:",0)==0) {
                    const auto id=cloudQueue[index].value("id","");
                    const auto found=std::find_if(localTracks.begin(),localTracks.end(),[&](const Json& row){return row.value("id","")==id;});
                    if(found==localTracks.end())return {404,"本地歌曲不存在",{}};
                    playLocal((int)std::distance(localTracks.begin(),found));return reply({{"id",std::to_string(libraryOperationId)}});
                }
                next={{"source","queue"},{"id",cloudQueue[index].value("id","")}};
            } else {
                if(p.size()!=2) return {400,"无效播放参数",{}};
                const auto id=requiredText(p,"id");
                if(source!="search" && source!="library" && source!="queue") return {400,"无效列表",{}};
                const auto& list=source=="search"?searchTracks:source=="queue"?cloudQueue:cloudTracks;
                if(std::none_of(list.begin(),list.end(),[&](const Json& row){return row.value("id","")==id;})) return {404,"歌曲不在当前列表",{}};
                next={{"source",source},{"id",id}};
            }
            if(!cloudConnected) return {409,"请先扫码登录",{}};
            if(cloudBusy() && cloudOperation!="audio") return {409,"正在处理其他请求，请稍后重试",{}};
            if(libraryOperationStatus=="pending" && libraryOperationKind=="audio" &&
               audioTrackId==libraryPlayTrackId && audioBackend().snapshot().pending)closeAudio();
            ++cloudGeneration;++libraryOperationId;libraryOperationKind="audio";
            libraryPlayPaused=false;
            libraryOperationStatus="pending";libraryOperationError.clear();libraryPlayGeneration=-1;
            libraryPlayTrackId=next.value("id","");
            libraryNextPlay=next;cloudAutoNext=false;
            return reply({{"id",std::to_string(libraryOperationId)}});
        }
        if(cloudBusy() || !libraryNextPlay.is_null()) return {409,"正在处理请求，请稍后重试",{}};
        Json request;
        if(name=="library.search") {
            if(p.size()!=2) return {400,"无效搜索参数",{}};
            if(!p.contains("page") || !p["page"].is_number_integer()) return {400,"页码必须为整数",{}};
            auto query=requiredText(p,"keywords",360);int page=p.value("page",1);
            if(page<1 || page>10000 || query.find_first_not_of(" \t\r\n")==std::string::npos) return {400,"无效搜索内容",{}};
            request={{"op","search"},{"keywords",query},{"page",page}};
        } else if(name=="library.open") {
            if(p.size()!=1) return {400,"无效歌单参数",{}};
            auto id=requiredText(p,"id");
            if(std::none_of(cloudPlaylists.begin(),cloudPlaylists.end(),[&](const Json& r){return r.value("id","")==id;})) return {404,"歌单不存在",{}};
            localView=false;
            request={{"op","tracks"},{"id",id}};
        } else if(name=="library.menu" || name=="library.favorite" || name=="library.add") {
            auto id=requiredText(p,"id");if(!knownTrack(id)) return {404,"歌曲不存在",{}};
            if(name=="library.menu") {
                if(p.size()!=1) return {400,"无效菜单参数",{}};
                request={{"op","song_menu"},{"id",id}};libraryMenu=Json::object();libraryMenuRequested=true;
            } else {
                if(p.size()!=2) return {400,"无效歌曲操作参数",{}};
                request={{"op","song_update"},{"id",id}};
                if(name=="library.favorite") {
                    if(!p.contains("enabled") || !p["enabled"].is_boolean()) return {400,"无效收藏参数",{}};
                    request["kind"]="favorite";request["enabled"]=p["enabled"];
                } else {request["kind"]="add";request["playlistId"]=requiredText(p,"playlistId");}
            }
        } else if(name=="library.login" || name=="library.logout" || name=="library.sync") {
            if(!p.empty()) return {400,"无效账号参数",{}};
            if(name=="library.sync" && !cloudConnected) return {409,"请先扫码登录",{}};
            if(name=="library.login") accountLogin();
            else if(name=="library.logout") accountLogout();
            else accountSync();
            libraryMenu=Json::object();
            ++libraryOperationId;libraryOperationKind=cloudOperation;
            libraryOperationStatus=cloudBusy()?"pending":name=="library.logout"?"completed":"failed";
            libraryOperationError=libraryOperationStatus=="failed"?utf8(cloudStatus):"";
            return reply({{"id",std::to_string(libraryOperationId)}});
        } else return {404,"未知媒体库命令",{}};
        if(name!="library.search" && !cloudConnected) {libraryMenuRequested=false;return {409,"请先扫码登录",{}};}
        cloudRequest(request);
        if(!cloudBusy()) {libraryMenuRequested=false;return {503,utf8(cloudStatus),{}};}
        ++libraryOperationId;libraryOperationKind=request.value("op","");libraryOperationStatus="pending";libraryOperationError.clear();
        return reply({{"id",std::to_string(libraryOperationId)}});
    } catch(const std::exception& e) {return {400,e.what(),{}};}
}
