// Included inside main.cpp's application namespace, after drawing helpers.
void drawScrollbar(Graphics& g,int count,int rows,int offset) {
    scrollMaximum=std::max(0,count-rows);
    if(!scrollMaximum) {scrollbarBox={0,0,0,0};scrollVisual=scrollTarget=0;scrollEmphasis=0;KillTimer(window,ScrollAnimationTick);return;}
    float track=rows*54.0f;
    scrollbarBox={width-26,278,20,track};
    scrollThumbLength=std::min(track-12,std::max(std::max(64.0f,track*.18f),track*rows/count));
    scrollTarget=278+(track-scrollThumbLength)*offset/scrollMaximum;
    std::string owner=(cloudView?"cloud:"+cloudPlaylistId:localView?"local:"+std::to_string((int)songs.size()):"search:"+searchQuery+std::to_string(searchPage))+":"+std::to_string(count);
    if(owner!=scrollOwner || scrollVisual<0) {scrollOwner=owner;scrollVisual=scrollTarget;}
    if(draggingScroll) scrollVisual=scrollTarget;
    else if(std::abs(scrollVisual-scrollTarget)>.2f) SetTimer(window,ScrollAnimationTick,16,nullptr);
    float thick=3+5*scrollEmphasis;
    if(scrollEmphasis>.01f) rounded(g,{width-20,278,8,track},4,Color((BYTE)(60*scrollEmphasis),Muted.GetR(),Muted.GetG(),Muted.GetB()));
    Color color((BYTE)(100+155*scrollEmphasis),
        (BYTE)(Muted.GetR()+(Mint.GetR()-Muted.GetR())*scrollEmphasis),
        (BYTE)(Muted.GetG()+(Mint.GetG()-Muted.GetG())*scrollEmphasis),
        (BYTE)(Muted.GetB()+(Mint.GetB()-Muted.GetB())*scrollEmphasis));
    rounded(g,{width-16-thick/2,std::clamp(scrollVisual,278.0f,278+track-scrollThumbLength),thick,scrollThumbLength},thick/2,color);
    hit(Scrollbar,scrollbarBox);
}
void moveScrollbar(float y) {
    if(scrollMaximum<=0 || scrollbarBox.Height<=scrollThumbLength) return;
    int offset=(int)std::lround(std::clamp((y-scrollGrab-scrollbarBox.Y)/(scrollbarBox.Height-scrollThumbLength),0.0f,1.0f)*scrollMaximum);
    if(cloudView) cloudScroll=offset;else if(localView) scroll=offset;else searchScroll=offset;
    scrollTarget=scrollbarBox.Y+(scrollbarBox.Height-scrollThumbLength)*offset/scrollMaximum;scrollVisual=scrollTarget;invalidate();
}
void layoutSearch() {
    if(!searchEdit) return;
    MoveWindow(searchEdit,(int)(254*dpiScale),(int)(141*dpiScale),(int)(std::max(100.0f,width-430)*dpiScale),(int)(28*dpiScale),TRUE);
    ShowWindow(searchEdit,(cloudView||localView)?SW_HIDE:SW_SHOW);
}
void runSearch(int page) {
    if(cloudBusy()) {notice(L"正在处理请求，请稍候再搜索");return;}
    std::string query=searchQuery;
    if(page==1) {wchar_t value[121]{};GetWindowTextW(searchEdit,value,121);query=utf8(value);}
    if(query.find_first_not_of(" \t\r\n")==std::string::npos) {notice(L"请输入歌曲或歌手名称");SetFocus(searchEdit);return;}
    searchSubmitted=true;searchStatus=L"正在搜索…";
    cloudRequest({{"op","search"},{"keywords",query},{"page",page}});
}
LRESULT CALLBACK searchProc(HWND hwnd,UINT message,WPARAM wp,LPARAM lp) {
    if(message==WM_KEYDOWN && wp==VK_RETURN) {runSearch();return 0;}
    if(message==WM_CHAR && wp==VK_RETURN) return 0;
    return CallWindowProcW(searchOriginal,hwnd,message,wp,lp);
}
void paintDiscover(Graphics& g) {
    float left=234,right=width-32,content=right-left,bottom=height-112;
    label(g,L"FIND YOUR NEXT FAVORITE",{left,29,content,22},10,Mint,true);
    label(g,L"发现",{left,55,content,44},30,Text,true);
    rounded(g,{left,122,content-116,62},14,Card);
    if(!searchEdit) label(g,searchQuery.empty()?L"搜索歌曲、歌手":cloud::toWide(searchQuery),{left+20,136,content-160,34},16,Muted);
    button(g,SearchSubmit,{right-100,132,100,42},cloudBusy()&&cloudOperation=="search"?L"搜索中…":L"搜索",true);
    label(g,searchSubmitted?L"搜索结果":L"探索喜欢的音乐",{left,218,content-190,34},18,Text,true);
    label(g,searchSubmitted?std::to_wstring(searchTotal)+L" 首歌曲":L"酷狗概念版",{right-180,220,180,30},11,Muted,false,StringAlignmentFar);
    if(searchTracks.empty()) {
        float cy=std::max(294.0f,(bottom+250)/2-50);
        rounded(g,{left+content/2-30,cy,60,60},30,Active);
        Pen pen(Mint,3);g.DrawEllipse(&pen,left+content/2-13,cy+15,22.0f,22.0f);
        stroke(g,Mint,3,left+content/2+6,cy+35,left+content/2+16,cy+45);
        label(g,searchSubmitted?searchStatus:L"从一首喜欢的歌开始",{left,cy+76,content,30},16,Text,true,StringAlignmentCenter);
        label(g,L"输入歌曲或歌手名称，搜索酷狗概念版音乐",{left,cy+111,content,26},12,Muted,false,StringAlignmentCenter);
    }
    int rows=searchRows();searchScroll=std::clamp(searchScroll,0,std::max(0,(int)searchTracks.size()-rows));
    for(int row=0;row<rows && row+searchScroll<(int)searchTracks.size();++row) {
        int i=row+searchScroll,id=SearchRow+i;float y=278+row*54.0f;auto& t=searchTracks[i];
        RectF r(left,y,content,50);if(i==searchSelected||hovered==id) rounded(g,r,9,i==searchSelected?Active:Card);
        label(g,std::to_wstring(i+1+(searchPage-1)*30),{left+10,y,34,50},11,Muted);
        drawCover(g,t.value("cover",""),{left+48,y+7,36,36});
        label(g,cloud::toWide(t.value("name","")),{left+96,y+2,content-187,27},13,Text,true);
        label(g,cloud::toWide(t.value("artist","")),{left+96,y+28,content-187,18},10,Muted);
        label(g,timeText((DWORD)t.value("duration",0.0)),{right-80,y,66,50},11,Muted,false,StringAlignmentFar);hit(id,r);
    }
    if(!searchTracks.empty()) {
        label(g,searchStatus,{left,bottom-46,content-275,30},11,Muted);
        if(searchPage>1) button(g,SearchPrev,{right-240,bottom-47,80,32},L"上一页");
        label(g,std::to_wstring(searchPage),{right-153,bottom-47,62,32},12,Mint,false,StringAlignmentCenter);
        if(searchMore) button(g,SearchNext,{right-80,bottom-47,80,32},L"下一页");
    }
    drawScrollbar(g,(int)searchTracks.size(),rows,searchScroll);
}
// Local library: imported files live here, independent of the account-bound cloud lists.
void paintLocal(Graphics& g) {
    float left=234,right=width-32,content=right-left,bottom=height-112;
    label(g,L"LOCAL LIBRARY",{left,29,content,22},10,Mint,true);
    label(g,L"本地音乐",{left,55,content-300,44},30,Text,true);
    button(g,Import,{right-280,52,132,44},L"导入音乐",true);
    button(g,Clear,{right-140,52,92,44},GetTickCount64()<clearArmedUntil?L"确认清空":L"清空");
    label(g,songs.empty()?L"还没有导入歌曲":L"歌曲列表和播放位置都会保存在本机",{left,122,content,30},12,Muted);
    int rows=searchRows();
    if(songs.empty()) {
        float cy=std::max(294.0f,(bottom+250)/2-50);
        rounded(g,{left+content/2-30,cy,60,60},30,Active);
        icon(g,Import,left+content/2-16,cy+14,32,Mint);
        label(g,L"把本机音乐加进来",{left,cy+76,content,30},16,Text,true,StringAlignmentCenter);
        label(g,L"支持 MP3、WAV、WMA，重复文件会自动跳过",{left,cy+111,content,26},12,Muted,false,StringAlignmentCenter);
        drawScrollbar(g,0,rows,0);
        return;
    }
    label(g,L"全部歌曲",{left,218,content-200,34},18,Text,true);
    label(g,std::to_wstring(songs.size())+L" 首 · 双击播放",{right-200,220,200,30},11,Muted,false,StringAlignmentFar);
    scroll=std::clamp(scroll,0,std::max(0,(int)songs.size()-rows));
    for(int row=0;row<rows && row+scroll<(int)songs.size();++row) {
        int i=row+scroll,id=Row+i;float y=278+row*54.0f;auto& s=songs[i];
        RectF r(left,y,content,50);if(i==selected||hovered==id) rounded(g,r,9,i==selected?Active:Card);
        bool now=i==current;
        if(now) icon(g,Play,left+11,y+16,18,Mint);
        else label(g,std::to_wstring(i+1),{left+10,y,34,50},11,Muted);
        rounded(g,{left+48,y+7,36,36},9,Active);
        if(now) icon(g,Play,left+58,y+17,16,Mint);
        else label(g,s.format,{left+48,y+7,36,36},9,Mint,false,StringAlignmentCenter);
        label(g,s.title,{left+96,y+2,content-187,27},13,now?Mint:Text,true);
        label(g,s.format+L" · "+(now?L"正在播放":L"本地音乐"),{left+96,y+28,content-187,18},10,Muted);
        label(g,s.duration?timeText(s.duration):L"--:--",{right-80,y,66,50},11,Muted,false,StringAlignmentFar);
        hit(id,r);
    }
    label(g,L"双击列表中的歌曲即可播放",{left,bottom-46,content-40,30},11,Muted);
    drawScrollbar(g,(int)songs.size(),rows,scroll);
}
std::wstring cloudText(const Json& value, const char* key, const char* fallback = "") {
    auto found=value.find(key);
    return cloud::toWide(found!=value.end() && found->is_string()?found->get<std::string>():fallback);
}
void clearQr() { cloudQr.reset(); if(cloudQrStream) {cloudQrStream->Release();cloudQrStream=nullptr;} }
void loadImage(const std::string& data,std::unique_ptr<Image>& target,IStream*& stream) {
    target.reset();if(stream) {stream->Release();stream=nullptr;} auto comma=data.find(','); if(comma==std::string::npos) return;
    auto encoded=data.substr(comma+1); DWORD size=0;
    if(!CryptStringToBinaryA(encoded.c_str(),(DWORD)encoded.size(),CRYPT_STRING_BASE64,nullptr,&size,nullptr,nullptr) || size>3*1024*1024) return;
    HGLOBAL bytes=GlobalAlloc(GMEM_MOVEABLE,size); if(!bytes) return;
    auto ptr=static_cast<BYTE*>(GlobalLock(bytes));
    bool ok=CryptStringToBinaryA(encoded.c_str(),(DWORD)encoded.size(),CRYPT_STRING_BASE64,ptr,&size,nullptr,nullptr);
    GlobalUnlock(bytes);
    if(!ok || FAILED(CreateStreamOnHGlobal(bytes,TRUE,&stream))) {GlobalFree(bytes);return;}
    target.reset(Image::FromStream(stream));
    if(!target || target->GetLastStatus()!=Ok) {target.reset();stream->Release();stream=nullptr;}
}
void loadQr(const std::string& data) {loadImage(data,cloudQr,cloudQrStream);}
void drawCover(Graphics& g,const std::string& url,RectF box,int fallback) {
    if(!url.empty() && std::find(wantedCovers.begin(),wantedCovers.end(),url)==wantedCovers.end()) wantedCovers.push_back(url);
    rounded(g,box,8,Active);
    auto found=coverCache.find(url);
    if(!url.empty() && found!=coverCache.end() && found->second->image) {
        auto& img=found->second->image;
        auto state=g.Save();GraphicsPath clip;float d=16;
        clip.AddArc(box.X,box.Y,d,d,180,90);clip.AddArc(box.GetRight()-d,box.Y,d,d,270,90);
        clip.AddArc(box.GetRight()-d,box.GetBottom()-d,d,d,0,90);clip.AddArc(box.X,box.GetBottom()-d,d,d,90,90);clip.CloseFigure();g.SetClip(&clip);
        float side=std::min(img->GetWidth(),img->GetHeight());
        g.SetInterpolationMode(InterpolationModeHighQualityBicubic);
        g.DrawImage(img.get(),box,(img->GetWidth()-side)/2,(img->GetHeight()-side)/2,side,side,UnitPixel);g.Restore(state);
    } else {
        icon(g,fallback,box.X+box.Width*.14f,box.Y+box.Height*.14f,box.Width*.72f,Mint);
        if(!url.empty() && (found==coverCache.end() || GetTickCount64()>=found->second->retryAt))
            if(std::find(wantedCovers.begin(),wantedCovers.end(),url)==wantedCovers.end()) wantedCovers.push_back(url);
    }
}
void stopCovers() {
    coverBridge.cancel();if(coverFuture.valid()) {try {coverFuture.get();} catch(...) {}}
    coverBridge.stop();coverCache.clear();wantedCovers.clear();cloudNowCover.clear();
}
void coverTick() {
    if(coverFuture.valid()) {
        if(coverFuture.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready) return;
        try {
            auto result=coverFuture.get();
            if(result.value("ok",false)) for(const auto& item:result.at("data").at("images")) {
                auto entry=std::make_unique<CoverImage>();entry->retryAt=GetTickCount64()+60000;
                loadImage(item.value("image",""),entry->image,entry->stream);
                if(entry->image && (entry->image->GetWidth()>2048 || entry->image->GetHeight()>2048)) {
                    entry->image.reset();if(entry->stream) {entry->stream->Release();entry->stream=nullptr;}
                }
                coverCache[item.value("url","")]=std::move(entry);
            }
        } catch(...) {}
        while(coverCache.size()>256) {
            auto old=std::find_if(coverCache.begin(),coverCache.end(),[](const auto& pair) {
                return pair.first!=cloudNowCover && std::find(wantedCovers.begin(),wantedCovers.end(),pair.first)==wantedCovers.end();
            });
            if(old==coverCache.end()) break;
            coverCache.erase(old);
        }
        invalidate();
    }
    Json urls=Json::array();
    for(const auto& url:wantedCovers) {
        auto found=coverCache.find(url);
        if(found==coverCache.end() || (!found->second->image && GetTickCount64()>=found->second->retryAt)) {
            auto& entry=coverCache[url];if(!entry) entry=std::make_unique<CoverImage>();entry->retryAt=GetTickCount64()+60000;
            urls.push_back(url);if(urls.size()==8) break;
        }
    }
    if(urls.empty()) return;
    std::wstring error;if(!coverBridge.start(error)) return;
    coverFuture=std::async(std::launch::async,[urls=std::move(urls)] {return coverBridge.call({{"op","covers"},{"urls",urls}});});
}
void cloudRequest(Json request) {
    if(cloudBusy()) return;
    std::wstring error;
    if(!cloudBridge.start(error)) {cloudStatus=error;notice(error);return;}
    cloudOperation=request.value("op","");requestGeneration=cloudGeneration;
    cloudFuture=std::async(std::launch::async,[request=std::move(request)] {return cloudBridge.call(request);});
    invalidate();
}
void cloudConnect() {
    if(cloudBusy()) return;
    cloudStatus=L"正在连接本机接口…";
    cloudRequest({{"op","init"},{"session",cloud::readSession()}});
}
void cloudStop() {
    stopCovers();
    cloudPolling=false;cloudBridge.cancel();
    if(cloudFuture.valid()) {
        try {
            auto result=cloudFuture.get();
            if(cloudOperation=="audio" && result.value("ok",false)) {
                auto path=cloudText(result.at("data"),"path");if(!path.empty()) DeleteFileW(path.c_str());
            }
        } catch(...) {}
    }
    cloudBridge.stop();clearQr();userAvatar.reset();if(avatarStream) {avatarStream->Release();avatarStream=nullptr;}
}
void cloudSaveSnapshot(const Json& data) {
    try {
        auto file=cloud::dataDir()/L"kugou-library.json";auto temp=file;temp+=L".tmp";
        Json value={{"user",utf8(cloudUser)},{"playlists",cloudPlaylists},{"playlistId",cloudPlaylistId},{"tracks",cloudTracks},{"syncedAt",data.value("syncedAt","")}};
        std::ofstream stream(temp,std::ios::binary);stream<<value.dump(2);stream.close();
        if(!stream.fail()) MoveFileExW(temp.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING);
    } catch(...) {notice(L"已同步，但暂时无法保存本机歌单快照");}
}
void cloudPlay(int index, bool useQueue) {
    if(cloudBusy()) {notice(L"正在处理请求，请稍候再播放");return;}
    auto& list=useQueue?cloudQueue:(cloudView?cloudTracks:searchTracks);
    if(!cloudConnected) {notice(L"请点击头像扫码登录后播放");cloudView=true;layoutSearch();invalidate();return;}
    if(index<0 || index>=(int)list.size()) return;
    if(!useQueue) cloudQueue=list;
    ++cloudGeneration;
    cloudStatus=L"正在获取歌曲并准备播放…";
    try {
        // Session-specific directory avoids interfering with another running player.
        auto dir=cloud::dataDir()/L"cloud-cache"/std::to_wstring(GetCurrentProcessId());
        cloudRequest({{"op","audio"},{"id",list[index].value("id","")},{"index",index},{"cacheDir",dir.u8string()}});
        // Index is kept on the UI thread, outside the adapter's response schema.
        if(!useQueue) {if(cloudView) cloudSelected=index;else searchSelected=index;}
    } catch(...) {notice(L"无法创建本机播放缓存");}
}
void cloudTick() {
    if(songMenuOpen) return;
    if(cloudFuture.valid() && cloudFuture.wait_for(std::chrono::milliseconds(0))==std::future_status::ready) {
        auto op=cloudOperation;Json result;
        try {result=cloudFuture.get();} catch(...) {result={{"ok",false},{"error","本机接口请求失败"}};}
        if(!result.value("ok",false)) {
            cloudStatus=cloudText(result,"error","请求失败");if(op=="search") searchStatus=cloudStatus;
            if(op!="avatar" && op!="vip_auto") {cloudPolling=false;notice(cloudStatus);}
            if(result.value("fatal",false)) {cloudBridge.stop();cloudInitialized=false;cloudConnected=false;}
            else if(op=="sync" && cloudConnected) cloudRequest({{"op","avatar"}});
            else if(op=="vip_auto" && cloudConnected) cloudRequest({{"op","sync"}});
        } else try {
            const auto& data=result.at("data");
            if(data.contains("session") && data["session"].contains("cookie") && data["session"]["cookie"].contains("token")) {
                if(!cloud::saveSession(data["session"])) notice(L"登录成功，但凭证未能加密保存，下次需要重新扫码");
            }
            if(op=="init") {
                cloudInitialized=true;cloudConnected=data.value("connected",false);
                if(cloudConnected) {cloudUser=cloudText(data["profile"],"name");cloudStatus=L"正在检查概念版 VIP 权益…";cloudRequest({{"op","vip_auto"}});}
                else {
                    cloudStatus=data.value("verificationFailed",false)?L"暂时无法验证已有登录，请检查网络或重新扫码。":L"使用手机酷狗概念版扫码登录。";
                    if(cloudLoginAfterInit) cloudRequest({{"op","qr"}});
                }
                cloudLoginAfterInit=false;
            } else if(op=="qr") {
                loadQr(data.value("image",""));cloudPolling=cloudQr!=nullptr;
                cloudNextPoll=GetTickCount64()+2000;cloudPollDeadline=GetTickCount64()+180000;
                cloudStatus=cloudPolling?L"请使用手机酷狗概念版扫描二维码。":L"二维码加载失败，请重新生成。";
            } else if(op=="poll") {
                auto state=data.value("status","");cloudNextPoll=GetTickCount64()+2000;
                if(state=="connected") {
                    cloudPolling=false;cloudConnected=true;clearQr();
                    cloudUser=cloudText(data["profile"],"name");cloudStatus=L"登录成功，正在检查概念版 VIP 权益…";
                    cloudRequest({{"op","vip_auto"}});
                } else if(state=="expired") {cloudPolling=false;clearQr();cloudStatus=L"二维码已过期，请点击扫码登录重新生成。";}
                else cloudStatus=state=="confirm"?L"已扫描，请在手机上确认登录。":L"等待扫码，请使用手机酷狗概念版。";
            } else if(op=="vip_auto") {
                if(data.value("outcome","")=="claimed" && data.value("notify",false)) notice(L"VIP 领取成功，今日概念版 VIP 已到账");
                cloudStatus=L"正在同步收藏歌单…";cloudRequest({{"op","sync"}});
            } else if(op=="sync") {
                cloudPlaylists=data.at("playlists");cloudPlaylistId.clear();cloudTracks=Json::array();cloudScroll=0;cloudSelected=-1;
                cloudStatus=L"已同步 "+std::to_wstring(cloudPlaylists.size())+L" 个歌单 · 点击歌单查看歌曲";
                cloudSaveSnapshot(data);cloudRequest({{"op","avatar"}});
            } else if(op=="avatar") {
                loadImage(data.value("image",""),userAvatar,avatarStream);
            } else if(op=="search") {
                searchTracks=data.at("tracks");searchQuery=data.value("keywords","");searchPage=data.value("page",1);searchTotal=data.value("total",0);searchMore=data.value("hasMore",false);
                searchScroll=0;searchSelected=searchTracks.empty()?-1:0;searchStatus=searchTracks.empty()?L"没有找到相关歌曲，试试其他关键词":L"双击歌曲播放";
            } else if(op=="tracks") {
                cloudTracks=data.at("tracks");cloudPlaylistId=data.at("playlist").value("id","");cloudPlaylistName=cloudText(data["playlist"],"name");
                cloudScroll=0;cloudSelected=cloudTracks.empty()?-1:0;
                cloudStatus=L"已加载 "+std::to_wstring(cloudTracks.size())+L" 首歌曲 · 双击播放";cloudSaveSnapshot(data);
            } else if(op=="song_menu") {
                showSongMenu(data);
            } else if(op=="song_update") {
                cloudPlaylists=data.at("playlists");
                if(cloudPlaylistId==data.at("playlist").value("id","")) {
                    cloudTracks=data.at("tracks");cloudSelected=cloudTracks.empty()?-1:std::min(std::max(0,cloudSelected),(int)cloudTracks.size()-1);
                    cloudScroll=std::clamp(cloudScroll,0,std::max(0,(int)cloudTracks.size()-cloudRows()));
                }
                cloudStatus=cloudText(data,"message");notice(cloudStatus);cloudSaveSnapshot(data);
            } else if(op=="audio") {
                auto path=cloudText(data,"path");
                if(requestGeneration!=cloudGeneration) {if(!path.empty()) DeleteFileW(path.c_str());}
                else {
                    closeAudio();current=-1;cloudCurrent=-1;
                    cloudTempPath=path;
                    auto error=command(L"open \""+path+L"\" alias mint");
                    if(!error) {opened=true;error=command(L"set mint time format milliseconds");}
                    if(!error) applyVolume();
                    if(!error) {duration=statusNumber(L"length");error=command(L"play mint notify");}
                    if(error) {closeAudio();cloudStatus=L"Windows 无法播放该音频编码，请尝试其他歌曲";notice(cloudStatus);}
                    else {
                        applyVolume();
                        playing=true;cloudAutoNext=false;cloudNowTitle=cloudText(data["track"],"name");cloudNowArtist=cloudText(data["track"],"artist");cloudNowCover=data["track"].value("cover","");
                        auto id=data["track"].value("id","");
                        for(int i=0;i<(int)cloudQueue.size();++i) if(cloudQueue[i].value("id","")==id) {cloudCurrent=i;break;}
                        cloudStatus=L"正在播放 · "+cloudNowTitle;
                    }
                }
            }
        } catch(...) {cloudStatus=L"接口数据格式不兼容，请重新同步";cloudPolling=false;notice(cloudStatus);}
        invalidate();
    }
    if(cloudPolling && !cloudBusy() && GetTickCount64()>=cloudNextPoll) {
        if(GetTickCount64()>cloudPollDeadline) {cloudPolling=false;clearQr();cloudStatus=L"二维码已过期，请重新生成。";invalidate();}
        else cloudRequest({{"op","poll"}});
    }
    if(cloudAutoNext && !cloudBusy()) {cloudAutoNext=false;skip(1);}
}
bool cloudClick(int id, bool doubleClick) {
    if(id==Library) {songContextId.clear();cloudView=false;localView=false;layoutSearch();SetFocus(searchEdit);invalidate();return true;}
    if(id==LocalMusic) {songContextId.clear();cloudView=false;localView=true;clearArmedUntil=0;layoutSearch();invalidate();return true;}
    if(id==CloudHome || id==Avatar) {songContextId.clear();localView=false;cloudView=true;layoutSearch();if(!cloudInitialized) cloudConnect();invalidate();return true;}
    if(!cloudView) {
        if(id==SearchSubmit) {runSearch();return true;}
        if(id==SearchPrev) {if(searchPage>1) runSearch(searchPage-1);return true;}
        if(id==SearchNext) {if(searchMore) runSearch(searchPage+1);return true;}
        if(id>=SearchRow && id<SearchRow+(int)searchTracks.size()) {searchSelected=id-SearchRow;if(doubleClick) cloudPlay(searchSelected);invalidate();return true;}
        return false;
    }
    if(id==CloudLogout) {
        cloudStop();cloudInitialized=false;cloudConnected=false;cloudAutoNext=false;++cloudGeneration;
        if(cloudCurrent>=0) {closeAudio();cloudCurrent=-1;}
        searchTracks=Json::array();searchSelected=-1;searchSubmitted=false;searchStatus.clear();cloudPlaylists=Json::array();cloudTracks=Json::array();cloudQueue=Json::array();cloudUser.clear();cloudPlaylistId.clear();cloudSelected=-1;
        try {cloud::forgetSession();std::error_code ec;fs::remove(cloud::dataDir()/L"kugou-library.json",ec);} catch(...) {}
        cloudStatus=L"已退出账号并清除本机登录信息。";invalidate();return true;
    }
    if(id==CloudLogin) {
        if(!cloudBusy()) {
            cloudPolling=false;clearQr();
            if(!cloudInitialized) {cloudLoginAfterInit=true;cloudConnect();}
            else {cloudStatus=L"正在生成登录二维码…";cloudRequest({{"op","qr"}});}
        }
        return true;
    }
    if(id==CloudSync) {if(!cloudBusy()) {cloudStatus=L"正在同步收藏歌单…";cloudRequest({{"op","sync"}});}return true;}
    if(id==CloudBack) {cloudPlaylistId.clear();cloudTracks=Json::array();cloudScroll=0;cloudSelected=-1;invalidate();return true;}
    if(id>=CloudTrack && id<CloudTrack+(int)cloudTracks.size()) {cloudSelected=id-CloudTrack;if(doubleClick) cloudPlay(cloudSelected);invalidate();return true;}
    if(id>=CloudRow && id<CloudRow+(int)cloudPlaylists.size()) {
        if(!cloudBusy()) {cloudStatus=L"正在读取歌单歌曲…";cloudRequest({{"op","tracks"},{"id",cloudPlaylists[id-CloudRow].value("id","")}});}return true;
    }
    return false;
}
void songContextMenu(LPARAM location) {
    if(cloudBusy() || draggingSeek) {notice(L"正在处理请求，请稍候再打开歌曲菜单");return;}
    auto& list=cloudView?cloudTracks:searchTracks;
    if(cloudView && cloudPlaylistId.empty()) return;
    int index=-1;POINT screen{GET_X_LPARAM(location),GET_Y_LPARAM(location)};
    if(screen.x==-1 && screen.y==-1) {
        index=cloudView?cloudSelected:searchSelected;
        if(index<0 || index>=(int)list.size()) return;
        screen={(LONG)(330*dpiScale),(LONG)((303+(index-(cloudView?cloudScroll:searchScroll))*54)*dpiScale)};ClientToScreen(window,&screen);
    } else {
        POINT client=screen;ScreenToClient(window,&client);int id=hitTest(client.x/dpiScale,client.y/dpiScale);
        int base=cloudView?CloudTrack:SearchRow;
        if(id<base || id>=base+(int)list.size()) return;
        index=id-base;
    }
    if(!cloudConnected) {notice(L"请先点击头像扫码登录，再添加歌曲或收藏");return;}
    if(cloudView) cloudSelected=index;else searchSelected=index;
    songContextId=list[index].value("id","");songMenuPoint=screen;
    cloudRequest({{"op","song_menu"},{"id",songContextId}});invalidate();
}
void showSongMenu(const Json& data) {
    if(songContextId.empty() || songContextId!=data.value("id","") || IsIconic(window)) return;
    std::string trackId=songContextId;songContextId.clear();
    HMENU menu=CreatePopupMenu(),lists=CreatePopupMenu();
    if(!menu || !lists) {if(menu) DestroyMenu(menu);if(lists) DestroyMenu(lists);return;}
    bool liked=data.value("liked",false);
    AppendMenuW(menu,MF_STRING|(data.value("canFavorite",false)?MF_ENABLED:MF_GRAYED),1,liked?L"取消收藏":L"收藏到“我喜欢”");
    AppendMenuW(menu,MF_SEPARATOR,0,nullptr);
    const auto& playlists=data.at("playlists");
    for(size_t i=0;i<playlists.size();++i) {
        auto name=cloudText(playlists[i],"name");std::wstring escaped;
        for(wchar_t c:name) {escaped+=c;if(c==L'&') escaped+=c;}
        AppendMenuW(lists,MF_STRING,100+i,escaped.c_str());
    }
    if(playlists.empty()) AppendMenuW(lists,MF_STRING|MF_GRAYED,0,L"暂无可编辑歌单");
    AppendMenuW(menu,MF_POPUP,(UINT_PTR)lists,L"添加到歌单");
    songMenuOpen=true;
    UINT selected=TrackPopupMenu(menu,TPM_RETURNCMD|TPM_NONOTIFY|TPM_RIGHTBUTTON,songMenuPoint.x,songMenuPoint.y,0,window,nullptr);
    songMenuOpen=false;DestroyMenu(menu);
    if(selected==1) cloudRequest({{"op","song_update"},{"id",trackId},{"kind","favorite"},{"enabled",!liked}});
    else if(selected>=100 && selected<100+playlists.size()) cloudRequest({{"op","song_update"},{"id",trackId},{"kind","add"},{"playlistId",playlists[selected-100].value("id","")}});
}
void paintCloud(Graphics& g) {
    float left=234,right=width-32,content=right-left,bottom=height-112;
    label(g,L"YOUR MUSIC, CONNECTED",{left,29,content-210,22},10,Mint,true);
    label(g,L"音乐库",{left,55,content-250,44},30,Text,true);
    if(cloudConnected) {
        button(g,CloudSync,{right-272,49,116,40},cloudBusy()?L"同步中…":L"同步歌单",true);
        button(g,CloudLogout,{right-146,49,90,40},L"退出账号");
    } else button(g,CloudLogin,{right-194,49,138,40},cloudBusy()?L"连接中…":L"扫码登录",true);
    if(!cloudConnected) {
        drawScrollbar(g,0,cloudRows(),0);
        rounded(g,{left,122,content,std::max(325.0f,bottom-145)},18,Card);
        float center=left+content/2;
        if(cloudQr) {
            rounded(g,{center-100,144,200,200},12,Color::White);
            auto mode=g.GetInterpolationMode();g.SetInterpolationMode(InterpolationModeNearestNeighbor);
            g.DrawImage(cloudQr.get(),RectF(center-94,150,188,188));g.SetInterpolationMode(mode);
        } else {rounded(g,{center-34,201,68,68},18,Active);icon(g,0,center-23,212,46,Mint);}
        label(g,cloudStatus,{left+20,354,content-40,35},13,Text,true,StringAlignmentCenter);
        label(g,L"在手机上确认登录后，即可同步“我喜欢”和收藏歌单",{left+18,396,content-36,25},11,Muted,false,StringAlignmentCenter);
        label(g,L"第三方接口接入 · 登录信息仅在本机加密保存",{left+18,428,content-36,25},10,Muted,false,StringAlignmentCenter);
        return;
    }
    rounded(g,{left,120,content,97},16,Active);
    label(g,L"你好，"+cloudUser,{left+20,133,content-40,31},19,Text,true);
    label(g,cloudStatus,{left+20,172,content-40,26},11,Mint);
    bool tracks=!cloudPlaylistId.empty();
    if(tracks) button(g,CloudBack,{left,234,84,30},L"返回歌单");
    label(g,tracks?cloudPlaylistName:L"我的收藏与歌单",{left+(tracks?98:0),231,content-(tracks?250:140),36},16,Text,true);
    label(g,tracks?L"双击播放 · 账号可用音源":L"单击打开歌单",{right-184,235,184,28},10,Muted,false,StringAlignmentFar);
    auto& list=tracks?cloudTracks:cloudPlaylists;
    int rows=cloudRows();cloudScroll=std::clamp(cloudScroll,0,std::max(0,(int)list.size()-rows));
    if(list.empty()) label(g,cloudBusy()?L"正在读取…":L"这里还没有歌曲或歌单",{left,310,content,60},15,Muted,false,StringAlignmentCenter);
    for(int row=0;row<rows && row+cloudScroll<(int)list.size();++row) {
        int i=row+cloudScroll,id=(tracks?CloudTrack:CloudRow)+i;float y=278+row*54.0f;
        RectF rect(left,y,content,50);
        bool chosen=tracks && i==cloudSelected;
        if(chosen || hovered==id) rounded(g,rect,9,chosen?Active:Card);
        drawCover(g,list[i].value("cover",""),{left+10,y+7,36,36},tracks?0:Library);
        label(g,cloudText(list[i],"name"),{left+58,y+2,content-146,27},13,Text,true);
        auto detail=tracks?cloudText(list[i],"artist"):L"酷狗概念版 · 云端歌单";
        if(detail.empty() && !tracks) detail=L"酷狗概念版";
        label(g,detail,{left+58,y+28,content-146,17},10,Muted);
        std::wstring trailing;
        if(tracks) {auto d=list[i].value("duration",0.0);trailing=d>0?timeText((DWORD)d):L"—";}
        else trailing=std::to_wstring(list[i].value("count",0))+L" 首";
        label(g,trailing,{right-80,y,66,50},11,Muted,false,StringAlignmentFar);hit(id,rect);
    }
    drawScrollbar(g,(int)list.size(),rows,cloudScroll);
}
