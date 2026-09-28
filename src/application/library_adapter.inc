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
        if(name=="library.getState") {
            if(p.size()>1 || (!p.empty() && !p.contains("page"))) return {400,"无效参数",{}};
            if(p.contains("page") && !p["page"].is_number_integer()) return {400,"页码必须为整数",{}};
            const int page=p.value("page",1);if(page<1 || page>100000) return {400,"无效页码",{}};
            return reply({{"connected",cloudConnected},{"busy",cloudBusy() || !libraryNextPlay.is_null()},
                {"user",utf8(cloudUser)},{"status",utf8(cloudStatus)},{"notice",utf8(toast)},
                {"qr",libraryQr},{"avatar",libraryAvatar},{"playlists",publicRows(cloudPlaylists,0,10000)},
                {"tracks",publicRows(cloudTracks,(page-1)*50,50)},{"trackCount",cloudTracks.size()},
                {"playlistId",cloudPlaylistId},{"playlistName",utf8(cloudPlaylistName)},
                {"search",publicRows(searchTracks)},{"keywords",searchQuery},{"searchPage",searchPage},
                {"searchMore",searchMore},{"searchTotal",searchTotal},
                {"now",{{"name",opened && cloudCurrent>=0?utf8(cloudNowTitle):""},{"artist",opened && cloudCurrent>=0?utf8(cloudNowArtist):""},{"cover",opened && cloudCurrent>=0?cloudNowCover:""}}},
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
        if(name=="library.play" || name=="library.skip") {
            if(!cloudConnected) return {409,"请先扫码登录",{}};
            Json next;
            if(name=="library.skip") {
                if(p.size()!=1 || !p.contains("delta") || !p["delta"].is_number_integer()) return {400,"无效切歌参数",{}};
                const int delta=p["delta"].get<int>();if(delta!=1 && delta!=-1) return {400,"无效方向",{}};
                if(cloudQueue.empty()) return {409,"播放队列为空",{}};
                int base=std::max(0,cloudCurrent);
                if(libraryOperationStatus=="pending" && libraryOperationKind=="audio")
                    for(int i=0;i<(int)cloudQueue.size();++i)if(cloudQueue[i].value("id","")==libraryPlayTrackId){base=i;break;}
                const int index=(base+delta+(int)cloudQueue.size())%(int)cloudQueue.size();
                next={{"source","queue"},{"id",cloudQueue[index].value("id","")}};
            } else {
                if(p.size()!=2) return {400,"无效播放参数",{}};
                const auto source=requiredText(p,"source"),id=requiredText(p,"id");
                if(source!="search" && source!="library" && source!="queue") return {400,"无效列表",{}};
                const auto& list=source=="search"?searchTracks:source=="queue"?cloudQueue:cloudTracks;
                if(std::none_of(list.begin(),list.end(),[&](const Json& row){return row.value("id","")==id;})) return {404,"歌曲不在当前列表",{}};
                next={{"source",source},{"id",id}};
            }
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
