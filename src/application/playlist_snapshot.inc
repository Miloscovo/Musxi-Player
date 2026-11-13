// Only display metadata is persisted; session credentials stay in the protected store.
Json playlistSnapshots=Json::object();
Json snapshotRows(const Json& rows,const std::string& platform) {
    if(!rows.is_array() || rows.size()>10000)throw std::runtime_error("Invalid playlist cache");
    Json result=Json::array();std::set<std::string> ids;
    for(const auto& row:rows) {
        Json item={{"platform",platform}};
        for(const auto* key:{"id","name","cover"}) {
            const auto value=row.value(key,std::string{});
            if(value.size()>4096 || (std::string(key)=="id" && (value.empty() || !ids.insert(value).second)))throw std::runtime_error("Invalid playlist metadata");
            item[key]=value;
        }
        const auto id=item["id"].get<std::string>();
        const auto provider=id.rfind("netease:",0)==0?"netease":id.rfind("qq:",0)==0?"qq":"kugou";
        if(platform!=provider)throw std::runtime_error("Invalid playlist platform");
        const auto count=row.value("count",Json(0));
        if(!count.is_number_integer() || count<0 || count>10000000)throw std::runtime_error("Invalid playlist count");
        item["count"]=count;item["editable"]=row.value("editable",false);item["favorite"]=row.value("favorite",false);
        item["artist"]="";item["duration"]=0;result.push_back(std::move(item));
    }
    return result;
}
Json loadPlaylistSnapshots(const fs::path& file,const Json& session) {
    playlistSnapshots=Json::object();Json rows=Json::array();
    try {
        if(!fs::exists(file) || fs::file_size(file)>8*1024*1024)return rows;
        std::ifstream stream(file,std::ios::binary);Json saved;stream>>saved;
        if(saved.at("version")!=1)return rows;
        const auto accounts=session.value("platform","")=="multi"?session.value("accounts",Json::object()):Json{{"kugou",session}};
        for(const auto* platform:{"kugou","netease","qq"})try {
            const auto cached=saved.at("accounts").at(platform);
            const auto owner=cached.at("accountId").get<std::string>();
            if(owner.empty() || owner.size()>256 || owner!=accounts.at(platform).at("profile").value("id",std::string{}))continue;
            auto lists=snapshotRows(cached.at("playlists"),platform);
            playlistSnapshots[platform]={{"accountId",owner},{"syncedAt",cached.value("syncedAt","")},{"playlists",lists}};
            for(auto& row:lists)rows.push_back(std::move(row));
        }catch(...){}
    }catch(...){}
    return rows;
}
bool savePlaylistSnapshots(const fs::path& file,const Json& data) {
    try {
        auto next=playlistSnapshots;
        const auto verified=data.value("verifiedAccounts",Json::object());
        const auto snapshots=data.value("playlistSnapshots",Json::object());
        for(const auto* platform:{"kugou","netease","qq"}) {
            if(verified.contains(platform) && !verified[platform].is_string())throw std::runtime_error("Invalid verified account");
            if(verified.contains(platform) && next.contains(platform) && next[platform].value("accountId","")!=verified[platform])next.erase(platform);
            if(!snapshots.contains(platform))continue;
            const auto& snapshot=snapshots.at(platform);const auto owner=snapshot.at("accountId").get<std::string>();
            if(owner.empty() || owner.size()>256 || !verified.contains(platform) || verified[platform]!=owner)throw std::runtime_error("Invalid playlist owner");
            next[platform]={{"accountId",owner},{"syncedAt",snapshot.value("syncedAt","")},{"playlists",snapshotRows(snapshot.at("playlists"),platform)}};
        }
        if(next==playlistSnapshots)return true;
        const auto bytes=Json{{"version",1},{"accounts",next}}.dump();if(bytes.size()>8*1024*1024)return false;
        auto temp=file;temp+=L".tmp";
        {std::ofstream stream(temp,std::ios::binary);stream<<bytes;stream.close();if(!stream)return false;}
        if(!MoveFileExW(temp.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))return false;
        playlistSnapshots=std::move(next);return true;
    }catch(...){return false;}
}
