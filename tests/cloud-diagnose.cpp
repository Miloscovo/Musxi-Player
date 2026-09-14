// Uses saved auth internally; emits only the adapter's sanitized response shape.
#include "../src/main.cpp"
#include <iostream>
int wmain() {
    cloud::Bridge bridge; std::wstring error;
    if (!bridge.start(error)) {std::cout << "START_FAILED\n";return 1;}
    auto saved = cloud::readSession();
    if (saved.is_null()) {std::cout << "NO_SAVED_LOGIN\n";return 1;}
    auto init = bridge.call({{"op","init"},{"session",saved}});
    if (!init.value("ok",false)) {std::cout << "INIT_FAILED\n";return 1;}
    if(wcsstr(GetCommandLineW(),L"--verify-vip-auto")) {
        auto r=bridge.call({{"op","vip_auto"}});std::cout<<r.dump()<<'\n';bridge.stop();return r.value("ok",false)?0:1;
    }
    if(wcsstr(GetCommandLineW(),L"--vip-status")) {
        auto r=bridge.call({{"op","vip_inspect"}});std::cout<<r.dump(2)<<'\n';bridge.stop();return r.value("ok",false)?0:1;
    }
    if(wcsstr(GetCommandLineW(),L"--verify-menu")) {
        auto search=bridge.call({{"op","search"},{"keywords","hello"}});
        if(!search.value("ok",false)||search["data"]["tracks"].empty()) return 1;
        auto menu=bridge.call({{"op","song_menu"},{"id",search["data"]["tracks"][0]["id"]}});
        if(!menu.value("ok",false)) {std::cout<<"MENU_FAILED\n";return 1;}
        std::cout<<"MENU_OK editable_playlists="<<menu["data"]["playlists"].size()<<" favorite_available="<<menu["data"].value("canFavorite",false)<<'\n';
        bridge.stop();return 0;
    }
    if (wcsstr(GetCommandLineW(), L"--verify-covers")) {
        auto sync=bridge.call({{"op","sync"}});auto search=bridge.call({{"op","search"},{"keywords","hello"}});
        if(!sync.value("ok",false)||!search.value("ok",false)) {std::cout<<"COVER_SETUP_FAILED sync="<<sync.value("ok",false)<<" search="<<search.value("ok",false)<<'\n';return 1;}
        Json urls=Json::array();int playlists=0,tracks=0,results=0;
        for(const auto& p:sync["data"]["playlists"]) if(!p.value("cover","").empty()) {++playlists;urls.push_back(p["cover"]);}
        if(!sync["data"]["playlists"].empty()) {
            auto r=bridge.call({{"op","tracks"},{"id",sync["data"]["playlists"][0]["id"]}});
            if(!r.value("ok",false)) return 1;
            for(const auto& t:r["data"]["tracks"]) if(!t.value("cover","").empty()) {++tracks;if(urls.size()<6) urls.push_back(t["cover"]);}
        }
        for(const auto& t:search["data"]["tracks"]) if(!t.value("cover","").empty()) {++results;if(urls.size()<8) urls.push_back(t["cover"]);}
        auto images=bridge.call({{"op","covers"},{"urls",urls}});int decoded=0;
        GdiplusStartupInput input;ULONG_PTR token;GdiplusStartup(&token,&input,nullptr);CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
        if(images.value("ok",false)) for(const auto& item:images["data"]["images"]) {
            auto entry=std::make_unique<CoverImage>();loadImage(item.value("image",""),entry->image,entry->stream);if(entry->image) ++decoded;
            coverCache[item.value("url","")]=std::move(entry);
        }
        cloudView=false;searchSubmitted=true;searchQuery="hello";searchTracks=search["data"]["tracks"];searchStatus=L"封面布局验证";searchTotal=(int)searchTracks.size();
        // Show downloaded search covers at the top of this private diagnostic preview.
        std::stable_sort(searchTracks.begin(),searchTracks.end(),[](const Json& a,const Json& b) {return coverCache.count(a.value("cover",""))>coverCache.count(b.value("cover",""));});
        {Bitmap preview(1120,760);{Graphics g(&preview);paint(g);}
        CLSID png{0x557cf406,0x1a04,0x11d3,{0x9a,0x73,0x00,0x00,0xf8,0x1e,0xf3,0x2e}};
        preview.Save(L"build/preview-covers.png",&png,nullptr);}coverCache.clear();
        CoUninitialize();GdiplusShutdown(token);
        std::cout<<"COVERS playlists="<<playlists<<" playlist_tracks="<<tracks<<" search_tracks="<<results<<" decoded="<<decoded<<'\n';
        bridge.stop();return playlists>0 && tracks>0 && results>0 && decoded>0?0:1;
    }
    if (wcsstr(GetCommandLineW(), L"--verify-discover")) {
        auto search=bridge.call({{"op","search"},{"keywords","周杰伦"},{"page",1}});
        std::cout << "SEARCH_OK " << search.value("ok",false) << " COUNT " << (search.value("ok",false)?search["data"]["tracks"].size():0) << '\n';
        if(!search.value("ok",false)) std::cout << search.value("error","") << '\n';
        auto avatar=bridge.call({{"op","avatar"}});
        std::cout << "AVATAR_OK " << avatar.value("ok",false) << " IMAGE_BYTES " << (avatar.value("ok",false)?avatar["data"].value("image","").size():0) << '\n';
        bridge.stop();return search.value("ok",false)?0:1;
    }
    if (wcsstr(GetCommandLineW(), L"--verify-sync")) {
        auto sync = bridge.call({{"op","sync"}});
        if (!sync.value("ok",false)) {std::cout << "SYNC_FAILED\n";return 1;}
        const auto& playlists = sync.at("data").at("playlists");
        size_t songs = 0;
        for (const auto& p : playlists) {
            auto tracks = bridge.call({{"op","tracks"},{"id",p.at("id")}});
            if (!tracks.value("ok",false)) {std::cout << "TRACKS_FAILED\n";return 1;}
            songs += tracks.at("data").at("tracks").size();
        }
        std::cout << "PASS complete authenticated sync: " << playlists.size() << " playlists, " << songs << " track entries\n";
        bridge.stop();return 0;
    }
    auto result = bridge.call({{"op","diagnose"}});
    std::cout << result.dump(2) << '\n';
    bridge.stop(); return result.value("ok",false)?0:1;
}
