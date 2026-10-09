#ifndef UNICODE
#define UNICODE
#endif
#ifndef _UNICODE
#define _UNICODE
#endif
#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include <shellapi.h>
#include <shlobj.h>
#include <shobjidl.h>
#include <algorithm>
#include <cmath>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <map>
#include <memory>
#include <optional>
#include <set>
#include <random>
#include <sstream>
#include <string>
#include <vector>
#include "application.hpp"
#include "../audio/ffmpeg_backend.hpp"
#include "../audio/ffmpeg_decoder.hpp"
#include "../cloud_bridge.hpp"
#include "../lyrics/lyrics.hpp"
#include "../cxx17_guard.hpp"
namespace fs=std::filesystem;
namespace {
musxi::HostHooks hostHooks;
bool applicationClosing=false;
constexpr UINT_PTR HostTick=0xCEF, Tick=1;
bool opened=false,playing=false,cloudConnected=false,cloudPolling=false,cloudInitialized=false;
bool cloudLoginAfterInit=false,cloudAutoNext=false;
int cloudCurrent=-1;
int cloudGeneration=0,requestGeneration=0,searchPage=1,searchTotal=0;
ULONGLONG toastUntil=0,cloudNextPoll=0,cloudPollDeadline=0;
std::wstring toast,cloudStatus=L"登录后，把收藏带到这里。",cloudUser,cloudPlaylistName;
std::wstring cloudNowTitle,cloudNowArtist,cloudTempPath;
std::string cloudNowCover,libraryQr,libraryAvatar,searchQuery,cloudPlaylistId,cloudOperation;
std::string libraryOperationStatus="idle",libraryOperationError,libraryOperationKind,libraryPlayTrackId;
bool libraryMenuRequested=false,libraryPlayPaused=false,searchMore=false;
int libraryPlayGeneration=-1;
std::uint64_t libraryOperationId=0;
Json libraryMenu=Json::object(),libraryNextPlay=nullptr;
Json libraryNowMenu=Json::object();
Json libraryQualities=Json::object();
std::string currentQuality;
std::string loginPlatform="kugou";
Json cloudAccounts=Json::array();
bool libraryQualityChange=false;
bool libraryPlatformChange=false;
struct PlatformRollback {
    Json track=nullptr,menu=Json::object();
    std::wstring path;
    std::string quality;
    std::uint32_t position=0;
};
PlatformRollback platformRollback;
std::string playbackOrder="sequential";
Json currentPlaybackTrack=nullptr;
std::wstring retainedPlaybackCache;
ULONGLONG playbackSaveAt=0;
Json cloudPlaylists=Json::array(),cloudTracks=Json::array(),cloudQueue=Json::array(),searchTracks=Json::array();
Json recentTracks=Json::array();
std::uint64_t recentRecordedGeneration=0;
std::string recentLastPlayedId;
std::size_t cloudFavoritesCount=0;
struct CoverImage {std::string data;ULONGLONG retryAt=0;};
std::map<std::string,std::unique_ptr<CoverImage>> coverCache;
std::vector<std::string> wantedCovers;
std::map<std::string,ULONGLONG> webWantedCovers;
cloud::Bridge coverBridge,cloudBridge;
std::future<Json> coverFuture,cloudFuture;
bool cloudBusy(){return cloudFuture.valid();}
void notice(const std::wstring& value){toast=value;toastUntil=GetTickCount64()+6000;}
std::string utf8(const std::wstring& str){
    int n=WideCharToMultiByte(CP_UTF8,0,str.data(),(int)str.size(),nullptr,0,nullptr,nullptr);
    std::string out(n,'\0');WideCharToMultiByte(CP_UTF8,0,str.data(),(int)str.size(),out.data(),n,nullptr,nullptr);return out;
}
void cloudPlay(int index,bool useQueue=false,bool search=false);
void enqueuePlayedTrack(const Json& track) {
    const auto id=track.value("id","");
    if(std::none_of(cloudQueue.begin(),cloudQueue.end(),[&](const Json& row){return row.value("id","")==id;}))
        cloudQueue.push_back(track);
}
std::unique_ptr<musxi::IAudioBackend>& audioBackendStorage() {
    static std::unique_ptr<musxi::IAudioBackend> backend;
    if(!backend)backend=musxi::makeFfmpegAudioBackend();
    return backend;
}
musxi::IAudioBackend& audioBackend() {return *audioBackendStorage();}
std::vector<std::wstring> retiredAudioCaches;
void collectAudioCaches(bool shutdown=false) {
    retiredAudioCaches.erase(std::remove_if(retiredAudioCaches.begin(),retiredAudioCaches.end(),[&](const std::wstring& path){
        if(path==platformRollback.path)return false;
        if(!retainedPlaybackCache.empty() && _wcsicmp(fs::path(path).lexically_normal().generic_wstring().c_str(),
            fs::path(retainedPlaybackCache).lexically_normal().generic_wstring().c_str())==0)return false;
        if(!shutdown && !audioBackend().sourceReleased(path))return false;
        return DeleteFileW(path.c_str()) || GetLastError()==ERROR_FILE_NOT_FOUND;
    }),retiredAudioCaches.end());
}
std::string audioTrackId;
void syncAudioView() {
    const auto state=audioBackend().snapshot();
    opened=state.opened;playing=state.playing;
}
musxi::PlayerResult playerResult(musxi::AudioResult result) {
    switch(result.error) {
    case musxi::AudioError::None:return {};
    case musxi::AudioError::NotReady:return {musxi::PlayerError::NotReady};
    case musxi::AudioError::InvalidArgument:return {musxi::PlayerError::InvalidArgument};
    default:return {musxi::PlayerError::BackendFailure};
    }
}
musxi::PlayerService& playerService();
musxi::PlayerResult setVolumeNative(int value) {
    auto result=audioBackend().setVolume(value);syncAudioView();
    if(!result) {notice(L"此音频暂不支持音量调节");return playerResult(result);}
    return {};
}
bool closeAudio() {
    const auto result=audioBackend().unload();syncAudioView();
    if(!result) {notice(L"无法释放音频，请重试");return false;}
    audioTrackId.clear();
    if (!cloudTempPath.empty()) {retiredAudioCaches.push_back(cloudTempPath);cloudTempPath.clear();collectAudioCaches();}
    return true;
}
musxi::AudioResult loadAndPlayAudio(const std::wstring& path,const std::string& trackId) {
    auto result=audioBackend().load(path);
    if(result && !(libraryOperationStatus=="pending" && libraryOperationKind=="audio" && libraryPlayPaused))result=audioBackend().play();
    if(result)audioTrackId=trackId;
    else {audioBackend().unload();audioTrackId.clear();}
    syncAudioView();return result;
}
void discardPlatformRollback() {
    if(!platformRollback.path.empty())retiredAudioCaches.push_back(platformRollback.path);
    platformRollback={};libraryPlatformChange=false;
}
bool restorePlatformSource() {
    if(platformRollback.path.empty())return false;
    const auto previous=platformRollback;
    const auto failedId=audioTrackId;
    if(!closeAudio())return false;
    platformRollback={};libraryPlatformChange=false;
    cloudTempPath=previous.path;
    const auto result=loadAndPlayAudio(previous.path,previous.track.value("id",""));
    if(!result)return false;
    audioBackend().seek(previous.position);
    currentPlaybackTrack=previous.track;currentQuality=previous.quality;libraryNowMenu=previous.menu;libraryQualities=Json::object();
    cloudNowTitle=cloud::toWide(previous.track.value("name",""));cloudNowArtist=cloud::toWide(previous.track.value("artist",""));cloudNowCover=previous.track.value("cover","");
    for(auto& row:cloudQueue)if(row.value("id","")==failedId)row=previous.track;
    cloudCurrent=-1;for(int i=0;i<(int)cloudQueue.size();++i)if(cloudQueue[i].value("id","")==audioTrackId)cloudCurrent=i;
    libraryPlayTrackId=audioTrackId;
    cloudStatus=L"平台切换失败，已恢复原播放源";notice(cloudStatus);
    libraryOperationStatus="failed";libraryOperationError=utf8(cloudStatus);
    return true;
}
musxi::PlayerResult setPlaying(bool requested) {
    if(libraryOperationStatus=="pending" && libraryOperationKind=="audio")libraryPlayPaused=!requested;
    const bool retry=requested && audioBackend().snapshot().phase=="failed" &&
        libraryOperationKind=="audio" && libraryOperationStatus=="failed" && audioTrackId==libraryPlayTrackId;
    auto result=requested?audioBackend().resume():audioBackend().pause();syncAudioView();
    if(result && retry){libraryOperationStatus="pending";libraryOperationError.clear();}
    if(!result && result.error!=musxi::AudioError::NotReady)notice(L"播放状态切换失败，请重新打开这首歌曲");
    return playerResult(result);
}
musxi::PlayerService& playerService();
bool skipLocal(int delta);
int nextQueueIndex(int delta,int current=-2) {
    if(current==-2)current=cloudCurrent;
    const int size=static_cast<int>(cloudQueue.size());
    if(!size)return -1;
    if(playbackOrder=="random" && size>1) {
        static std::mt19937 random{std::random_device{}()};
        if(current<0 || current>=size)return std::uniform_int_distribution<int>(0,size-1)(random);
        const int choice=std::uniform_int_distribution<int>(0,size-2)(random);
        return choice>=current?choice+1:choice;
    }
    return current<0?(delta>0?0:size-1):(current+delta+size)%size;
}
void skip(int delta) {
    if(skipLocal(delta))return;
    if(!cloudQueue.empty())cloudPlay(nextQueueIndex(delta),true);
}
musxi::PlayerResult seekMilliseconds(std::uint32_t milliseconds) {
    auto result=audioBackend().seek(milliseconds);syncAudioView();
    if(!result && result.error!=musxi::AudioError::NotReady)notice(L"此音频暂不支持跳转到该位置");
    return playerResult(result);
}
musxi::PlayerService& playerService() {
    static musxi::PlayerService service([] {
        const auto audio=audioBackend().snapshot();
        musxi::PlayerState state{audio.opened,audio.playing,audio.positionMs,audio.durationMs,audio.volumePercent,{}};
        if(audio.opened)state.trackId=audioTrackId;
        state.phase=audio.phase.empty()?(audio.opened?(audio.playing?"playing":"paused"):"empty"):audio.phase;
        state.error=audio.error;state.pending=audio.pending;
        state.requestedPlaying=audio.phase.empty()?audio.playing:audio.requestedPlaying;
        return state;
    },[](musxi::PlayerCommand cmd,std::uint32_t value)->musxi::PlayerResult {
        switch(cmd) {
        case musxi::PlayerCommand::Pause:return setPlaying(false);
        case musxi::PlayerCommand::Resume:return setPlaying(true);
        case musxi::PlayerCommand::Seek:return seekMilliseconds(value);
        case musxi::PlayerCommand::SetVolume:
            return setVolumeNative((int)value);
        case musxi::PlayerCommand::GetState:return {};
        }
        return {musxi::PlayerError::InvalidArgument};
    });
    return service;
}
#include "local_music.inc"
#include "playback_history.inc"
#include "lyrics.inc"
void advancePlayback() {
    if(playbackOrder=="repeat-one" && opened && !cloudQueue.empty()) {
        auto result=audioBackend().seek(0);
        if(result)result=audioBackend().resume();
        if(!result)notice(L"单曲循环失败，请重新播放这首歌曲");
        syncAudioView();
    } else skip(1);
}
void pollAudio() {
    audioBackend().poll();syncAudioView();
    collectAudioCaches();
    const auto audio=audioBackend().snapshot();
    if(audio.opened && audio.playing && !audio.pending && audio.phase!="failed" && recentRecordedGeneration!=cloudGeneration && libraryPlayGeneration==cloudGeneration &&
       audioTrackId==libraryPlayTrackId && currentPlaybackTrack.is_object() && currentPlaybackTrack.value("id","")==audioTrackId) {
        if(!libraryQualityChange || audioTrackId!=recentLastPlayedId) {
            auto track=currentPlaybackTrack;track["duration"]=audio.durationMs;recordRecentTrack(track);
        }
        recentLastPlayedId=audioTrackId;
        recentRecordedGeneration=cloudGeneration;
    }
    if(!platformRollback.path.empty() && libraryPlayGeneration==cloudGeneration && !audio.pending) {
        if(audio.phase=="failed") {restorePlatformSource();return;}
        discardPlatformRollback();
    }
    if(libraryOperationStatus=="pending" && libraryOperationKind=="audio" && libraryNextPlay.is_null() && !cloudBusy() &&
       libraryPlayGeneration==cloudGeneration && audioTrackId==libraryPlayTrackId && audio.opened && !audio.pending) {
        libraryOperationStatus=audio.phase=="failed"?"failed":"completed";
        libraryOperationError=audio.error;
        cloudStatus=audio.phase=="failed"?L"音频播放失败，请重试":audio.playing?L"正在播放 · "+cloudNowTitle:L"已暂停 · "+cloudNowTitle;
    }
    for(const auto& event:audioBackend().takeEvents()) {
        if(event.generation!=audioBackend().snapshot().generation)continue;
        if(event.kind==musxi::AudioEventKind::Ended) {
            if(!cloudQueue.empty() && cloudBusy())cloudAutoNext=true;
            else advancePlayback();
        } else notice(L"音频播放中断，请重新播放或选择其他歌曲");
    }
    syncAudioView();
}
std::wstring cloudText(const Json& value, const char* key, const char* fallback = "") {
    auto found=value.find(key);
    return cloud::toWide(found!=value.end() && found->is_string()?found->get<std::string>():fallback);
}
bool imageDataUri(const std::string& data) {
    return data.size()<4*1024*1024 &&
        (data.rfind("data:image/png;base64,",0)==0 || data.rfind("data:image/jpeg;base64,",0)==0 ||
         data.rfind("data:image/webp;base64,",0)==0 || data.rfind("data:image/gif;base64,",0)==0);
}
void clearQr() {libraryQr.clear();}
void loadQr(const std::string& data) {libraryQr=imageDataUri(data)?data:"";}
void stopCovers() {
    coverBridge.cancel();if(coverFuture.valid()) {try {coverFuture.get();} catch(...) {}}
    coverBridge.stop();coverCache.clear();wantedCovers.clear();webWantedCovers.clear();cloudNowCover.clear();
}
void coverTick() {
    wantedCovers.clear();
    for(auto it=webWantedCovers.begin();it!=webWantedCovers.end();) {
        if(GetTickCount64()>it->second) it=webWantedCovers.erase(it);
        else {if(std::find(wantedCovers.begin(),wantedCovers.end(),it->first)==wantedCovers.end()) wantedCovers.push_back(it->first);++it;}
    }
    if(coverFuture.valid()) {
        if(coverFuture.wait_for(std::chrono::milliseconds(0))!=std::future_status::ready) return;
        try {
            auto result=coverFuture.get();
            if(result.value("ok",false)) for(const auto& item:result.at("data").at("images")) {
                auto entry=std::make_unique<CoverImage>();entry->retryAt=GetTickCount64()+60000;
                auto image=item.value("image","");if(imageDataUri(image))entry->data=std::move(image);
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
    }
    Json urls=Json::array();
    for(const auto& url:wantedCovers) {
        auto found=coverCache.find(url);
        if(found==coverCache.end() || (found->second->data.empty() && GetTickCount64()>=found->second->retryAt)) {
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
}
void cloudConnect() {
    if(cloudBusy()) return;
    cloudStatus=L"正在连接本机接口…";
    auto tracks=Json::array();
    for(const auto& row:cloudQueue)if(row.value("id","").rfind("local:",0)!=0)tracks.push_back(playbackTrack(row));
    if(currentPlaybackTrack.is_object() && currentPlaybackTrack.value("id","").rfind("local:",0)!=0)tracks.push_back(playbackTrack(currentPlaybackTrack));
    for(const auto& row:recentTracks)if(row.value("id","").rfind("local:",0)!=0)tracks.push_back(playbackTrack(row));
    cloudRequest({{"op","init"},{"profileDir",cloud::dataDir().u8string()},{"session",cloud::readSession()},{"tracks",tracks}});
}
void cloudStop() {
    discardPlatformRollback();
    libraryNextPlay=nullptr;
    if(libraryOperationStatus=="pending") libraryOperationStatus="cancelled";
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
    cloudBridge.stop();clearQr();libraryAvatar.clear();
}
void cloudSaveSnapshot(const Json& data) {
    try {
        auto file=cloud::dataDir()/L"kugou-library.json";auto temp=file;temp+=L".tmp";
        Json value={{"user",utf8(cloudUser)},{"playlists",cloudPlaylists},{"playlistId",cloudPlaylistId},{"tracks",cloudTracks},{"syncedAt",data.value("syncedAt","")}};
        std::ofstream stream(temp,std::ios::binary);stream<<value.dump(2);stream.close();
        if(!stream.fail()) MoveFileExW(temp.c_str(),file.c_str(),MOVEFILE_REPLACE_EXISTING);
    } catch(...) {notice(L"已同步，但暂时无法保存本机歌单快照");}
}
void cloudPlay(int index, bool useQueue, bool search) {
    discardPlatformRollback();
    libraryQualityChange=false;
    auto& list=useQueue?cloudQueue:(search?searchTracks:cloudTracks);
    if(index<0 || index>=(int)list.size()) return;
    const auto id=list[index].value("id","");
    if(id.rfind("local:",0)==0) {
        const auto found=std::find_if(localTracks.begin(),localTracks.end(),[&](const Json& row){return row.value("id","")==id;});
        if(found!=localTracks.end())playLocal((int)std::distance(localTracks.begin(),found));
        return;
    }
    if(cloudBusy()) {notice(L"正在处理请求，请稍候再播放");return;}
    if(!cloudConnected) {notice(L"请点击头像扫码登录后播放");return;}
    localActive=false;localCurrent=-1;
    if(!useQueue) enqueuePlayedTrack(list[index]);
    ++cloudGeneration;
    cloudStatus=L"正在获取歌曲并准备播放…";
    try {
        // Session-specific directory avoids interfering with another running player.
        auto dir=cloud::dataDir()/L"cloud-cache"/std::to_wstring(GetCurrentProcessId());
        cloudRequest({{"op","audio"},{"id",list[index].value("id","")},{"index",index},{"cacheDir",dir.u8string()},{"sources",list[index].value("sources",Json::array())}});
        // Index is kept on the UI thread, outside the adapter's response schema.
    } catch(...) {notice(L"无法创建本机播放缓存");}
}
void cloudTick() {
    if(cloudFuture.valid() && cloudFuture.wait_for(std::chrono::milliseconds(0))==std::future_status::ready) {
        auto op=cloudOperation;Json result;bool applied=false;
        try {result=cloudFuture.get();} catch(...) {result={{"ok",false},{"error","本机接口请求失败"}};}
        if(!result.value("ok",false)) {
            if(op=="audio")libraryPlatformChange=false;
            cloudStatus=cloudText(result,"error","请求失败");
            if(op!="avatar" && op!="vip_auto") {cloudPolling=false;notice(cloudStatus);}
            if(result.value("fatal",false)) {cloudBridge.stop();cloudInitialized=false;cloudConnected=false;}
            else if(op=="sync" && cloudConnected) cloudRequest({{"op","avatar"}});
            else if(op=="vip_auto" && cloudConnected) cloudRequest({{"op","sync"}});
        } else try {
            const auto& data=result.at("data");
            if(data.contains("favoritesCount") && data["favoritesCount"].is_number_unsigned())cloudFavoritesCount=data["favoritesCount"].get<std::size_t>();
            if(data.contains("accounts")) {cloudAccounts=data["accounts"];cloudConnected=data.value("connected",false);}
            if(data.contains("session") && (data["session"].value("platform","")=="multi" || (data["session"].contains("cookie") && data["session"]["cookie"].contains("token")))) {
                if(!cloud::saveSession(data["session"])) notice(L"登录成功，但凭证未能加密保存，下次需要重新扫码");
            }
            if(op=="init") {
                cloudInitialized=true;cloudConnected=data.value("connected",false);
                if(cloudLoginAfterInit) {cloudUser=cloudText(data["profile"],"name");cloudRequest({{"op","qr"},{"platform",loginPlatform}});}
                else if(cloudConnected) {cloudUser=cloudText(data["profile"],"name");cloudStatus=L"正在检查概念版 VIP 权益…";cloudRequest({{"op","vip_auto"}});}
                else {
                    cloudStatus=data.value("verificationFailed",false)?L"暂时无法验证已有登录，请检查网络或重新扫码。":L"使用手机酷狗概念版扫码登录。";
                    if(cloudLoginAfterInit) cloudRequest({{"op","qr"},{"platform",loginPlatform}});
                }
                cloudLoginAfterInit=false;
            } else if(op=="logout") {
                cloudPolling=false;clearQr();cloudPlaylists=data.at("playlists");
                cloudUser=cloudText(data["profile"],"name");
                const auto platform=data.value("platform","");
                const auto belongs=[&](const std::string& id) {return platform=="netease"?id.rfind("netease:",0)==0:platform=="qq"?id.rfind("qq:",0)==0:id.rfind("netease:",0)!=0 && id.rfind("qq:",0)!=0 && id.rfind("local:",0)!=0;};
                if(opened && belongs(audioTrackId))closeAudio();
                if(cloudPlaylistId=="musxi:cloud-favorites" || belongs(cloudPlaylistId)) {cloudPlaylistId.clear();cloudTracks=Json::array();}
                cloudQueue.erase(std::remove_if(cloudQueue.begin(),cloudQueue.end(),[&](const Json& row){return belongs(row.value("id",""));}),cloudQueue.end());
                cloudCurrent=-1;for(int i=0;i<(int)cloudQueue.size();++i)if(cloudQueue[i].value("id","")==audioTrackId)cloudCurrent=i;
                if(belongs(audioTrackId))libraryNowMenu=Json::object();
                libraryMenu=Json::object();cloudStatus=L"已退出所选平台，其他账号保持登录。";
            } else if(op=="qr") {
                loadQr(data.value("image",""));cloudPolling=!libraryQr.empty();
                cloudNextPoll=GetTickCount64()+2000;cloudPollDeadline=GetTickCount64()+180000;
                cloudStatus=cloudPolling?L"请使用所选平台的手机应用扫描二维码。":L"二维码加载失败，请重新生成。";
            } else if(op=="poll") {
                auto state=data.value("status","");cloudNextPoll=GetTickCount64()+2000;
                if(state=="connected") {
                    cloudPolling=false;cloudConnected=true;clearQr();
                    cloudUser=cloudText(data["profile"],"name");cloudStatus=L"登录成功，正在检查概念版 VIP 权益…";
                    cloudRequest({{"op","vip_auto"}});
                } else if(state=="expired") {cloudPolling=false;clearQr();cloudStatus=L"二维码已过期，请点击扫码登录重新生成。";}
                else cloudStatus=state=="confirm"?L"已扫描，请在手机上确认登录。":L"等待扫码，请使用所选平台的手机应用。";
            } else if(op=="vip_auto") {
                if(data.value("outcome","")=="claimed" && data.value("notify",false)) notice(L"VIP 领取成功，今日概念版 VIP 已到账");
                cloudStatus=L"正在同步收藏歌单…";cloudRequest({{"op","sync"}});
            } else if(op=="sync") {
                cloudPlaylists=data.at("playlists");cloudPlaylistId.clear();cloudTracks=Json::array();
                cloudStatus=L"已同步 "+std::to_wstring(cloudPlaylists.size())+L" 个歌单 · 点击歌单查看歌曲";
                cloudSaveSnapshot(data);cloudRequest({{"op","avatar"}});
            } else if(op=="avatar") {
                auto image=data.value("image","");libraryAvatar=imageDataUri(image)?image:"";
            } else if(op=="search") {
                searchTracks=data.at("tracks");searchQuery=data.value("keywords","");searchPage=data.value("page",1);searchTotal=data.value("total",0);searchMore=data.value("hasMore",false);
            } else if(op=="tracks") {
                if(data.contains("playlists"))cloudPlaylists=data["playlists"];
                cloudTracks=data.at("tracks");cloudPlaylistId=data.at("playlist").value("id","");cloudPlaylistName=cloudText(data["playlist"],"name");
                cloudStatus=L"已加载 "+std::to_wstring(cloudTracks.size())+L" 首歌曲 · 双击播放";cloudSaveSnapshot(data);
            } else if(op=="song_menu") {
                libraryMenu=data;libraryMenuRequested=false;
                if(data.value("sourceId",data.value("id",std::string{}))==audioTrackId) {libraryNowMenu=data;libraryNowMenu["id"]=audioTrackId;}
            } else if(op=="qualities") {
                libraryQualities=data;
            } else if(op=="song_update") {
                cloudPlaylists=data.at("playlists");
                if(cloudPlaylistId==data.at("playlist").value("id","")) {
                    cloudTracks=data.at("tracks");
                }
                cloudStatus=cloudText(data,"message");notice(cloudStatus);cloudSaveSnapshot(data);
            } else if(op=="audio") {
                auto path=cloudText(data,"path");
                if(requestGeneration!=cloudGeneration) {if(!path.empty()) DeleteFileW(path.c_str());}
                else {
                    const auto previousAudio=audioBackend().snapshot();
                    const bool restorePosition=libraryQualityChange && (audioTrackId==data["track"].value("id","") || audioTrackId==data.value("requestedId",std::string{}));
                    if(libraryPlatformChange && restorePosition)platformRollback={currentPlaybackTrack,libraryNowMenu,cloudTempPath,currentQuality,previousAudio.positionMs};
                    if(!closeAudio()) {
                        if(!path.empty())DeleteFileW(path.c_str());
                        cloudStatus=L"无法释放上一首音频，请重试";notice(cloudStatus);
                    } else {
                    cloudCurrent=-1;
                    cloudTempPath=path;
                    auto playbackResult=loadAndPlayAudio(path,data["track"].value("id",""));
                    if(!playbackResult) {if(!restorePlatformSource()) {closeAudio();cloudStatus=L"Windows 无法播放该音频编码，请尝试其他歌曲";notice(cloudStatus);}}
                    else {
                        currentQuality=data.value("quality","");
                        if(libraryPlatformChange) {libraryNowMenu=Json::object();libraryQualities=Json::object();}
                        const auto sources=restorePosition && currentPlaybackTrack.is_object()?currentPlaybackTrack.value("sources",Json::array()):Json::array();
                        currentPlaybackTrack=data["track"];
                        if(!sources.empty() && !currentPlaybackTrack.contains("sources"))currentPlaybackTrack["sources"]=sources;
                        const auto requestedId=data.value("requestedId",std::string{});
                        if(!requestedId.empty()) {
                            if(libraryPlayTrackId==requestedId)libraryPlayTrackId=data["track"].value("id","");
                            for(auto& row:cloudQueue)if(row.value("id","")==requestedId)row=data["track"];
                            std::set<std::string> ids;
                            cloudQueue.erase(std::remove_if(cloudQueue.begin(),cloudQueue.end(),[&](const Json& row){return !ids.insert(row.value("id","")).second;}),cloudQueue.end());
                        }
                        if(restorePosition) {
                            const auto seekResult=audioBackend().seek(previousAudio.positionMs);
                            if(!seekResult)notice(L"已切换，但无法恢复播放位置");
                        }
                        cloudAutoNext=false;cloudNowTitle=cloudText(data["track"],"name");cloudNowArtist=cloudText(data["track"],"artist");cloudNowCover=data["track"].value("cover","");
                        auto id=data["track"].value("id","");
                        for(int i=0;i<(int)cloudQueue.size();++i) if(cloudQueue[i].value("id","")==id) {cloudCurrent=i;break;}
                        cloudStatus=(audioBackend().snapshot().pending?L"正在加载 · ":playing?L"正在播放 · ":L"已暂停 · ")+cloudNowTitle;
                    }
                    }
                }
            }
            if(data.contains("warnings"))for(const auto& warning:data["warnings"])if(warning.is_string())notice(cloudText(Json{{"warning",warning}},"warning"));
            applied=true;
        } catch(...) {cloudStatus=L"接口数据格式不兼容，请重新同步";cloudPolling=false;notice(cloudStatus);}
        if(libraryOperationStatus=="pending" && op==libraryOperationKind &&
           (op!="audio" || requestGeneration==libraryPlayGeneration)) {
            bool success=result.value("ok",false) && applied;
            if(op=="audio") success=success && requestGeneration==cloudGeneration && opened && (playing || libraryPlayPaused) && audioTrackId==libraryPlayTrackId;
            const bool audioPending=op=="audio" && result.value("ok",false) && applied && requestGeneration==cloudGeneration &&
                audioTrackId==libraryPlayTrackId && audioBackend().snapshot().pending;
            libraryOperationStatus=audioPending?"pending":success?"completed":"failed";
            libraryOperationError=(success || audioPending)?"":utf8(cloudStatus);
        }
        if(op=="song_menu") libraryMenuRequested=false;
    }
    if(cloudPolling && !cloudBusy() && GetTickCount64()>=cloudNextPoll) {
        if(GetTickCount64()>cloudPollDeadline) {cloudPolling=false;clearQr();cloudStatus=L"二维码已过期，请重新生成。";}
        else cloudRequest({{"op","poll"}});
    }
    if(!libraryNextPlay.is_null() && !cloudBusy()) {
        auto next=libraryNextPlay;libraryNextPlay=nullptr;
        auto& list=next.value("source","")=="search"?searchTracks:next.value("source","")=="queue"?cloudQueue:cloudTracks;
        auto found=std::find_if(list.begin(),list.end(),[&](const Json& row){return row.value("id","")==next.value("id","");});
        if(found!=list.end() && cloudConnected) {
            const bool useQueue=next.value("source","")=="queue";
            cloudPlay((int)std::distance(list.begin(),found),useQueue,next.value("source","")=="search");
            libraryPlayGeneration=cloudGeneration;
            if(!cloudBusy()) {libraryOperationStatus="failed";libraryOperationError=utf8(cloudStatus);}
        } else {libraryOperationStatus="failed";libraryOperationError="歌曲已不在当前列表中，请刷新后重试";}
    }
    if(cloudAutoNext && !cloudBusy()) {cloudAutoNext=false;advancePlayback();}
}
void accountLogout() {
    libraryNowMenu=Json::object();
    libraryQualities=Json::object();currentQuality.clear();
    cloudStop();cloudInitialized=false;cloudConnected=false;cloudAutoNext=false;++cloudGeneration;
    if(!localActive && opened) {closeAudio();cloudCurrent=-1;}
    searchTracks=Json::array();
    cloudPlaylists=Json::array();cloudTracks=Json::array();cloudQueue=Json::array();cloudUser.clear();cloudPlaylistId.clear();cloudFavoritesCount=0;
    try {cloud::forgetSession();std::error_code ec;fs::remove(cloud::dataDir()/L"kugou-library.json",ec);} catch(...) {}
    cloudStatus=L"已退出账号并清除本机登录信息。";
}
void accountLogin() {
    if(cloudBusy()) return;
    cloudPolling=false;clearQr();
    if(!cloudInitialized) {cloudLoginAfterInit=true;cloudConnect();}
    else {cloudStatus=L"正在生成登录二维码…";cloudRequest({{"op","qr"},{"platform",loginPlatform}});}
}
void accountSync() {
    if(!cloudBusy()) {cloudStatus=L"正在同步收藏歌单…";cloudRequest({{"op","sync"}});}
}
} // namespace
namespace musxi {
void setHostHooks(HostHooks hooks){hostHooks=hooks;}
PlayerState applicationPlayerState(){return playerService().state();}
PlayerResult applicationPlayerCommand(PlayerCommand command,std::uint32_t value){return playerService().invoke(command,value);}
void setApplicationPlayerEvents(PlayerService::Events sink){playerService().setEventSink(std::move(sink));}
#include "library_adapter.inc"
namespace {
LRESULT CALLBACK wndProc(HWND hwnd,UINT message,WPARAM wp,LPARAM lp){
    switch(message){
    case WM_CREATE: SetTimer(hwnd,Tick,150,nullptr);return 0;
    case WM_TIMER:
        if(wp==HostTick){if(hostHooks.tick)hostHooks.tick();return 0;}
        localTick();lyricsTick();cloudTick();coverTick();pollAudio();playerService().publish();
        if(hostHooks.connectCloud && GetTickCount64()>=playbackSaveAt) {
            savePlaybackHistory(cloud::dataDir()/L"playback-history.json");playbackSaveAt=GetTickCount64()+5000;
        }
        if(!toast.empty() && GetTickCount64()>=toastUntil)toast.clear();
        return 0;
    case WM_CLOSE: if(hostHooks.canClose && !hostHooks.canClose())return 0;break;
    case ImportLocalFolder: chooseLocalFolder();return 0;
    case WM_DESTROY:
        applicationClosing=true;
        if(hostHooks.connectCloud)savePlaybackHistory(cloud::dataDir()/L"playback-history.json");
        localCancelled=true;if(localScan.valid()){try{localScan.get();}catch(...){}}stopLyrics();applicationWindow=nullptr;
        KillTimer(hwnd,HostTick);KillTimer(hwnd,Tick);cloudStop();closeAudio();
        audioBackendStorage().reset();collectAudioCaches(true);PostQuitMessage(0);return 0;
    }
    return DefWindowProcW(hwnd,message,wp,lp);
}
}
int runApplication(void* nativeInstance){
    applicationClosing=false;
    cloud::testProfile=hostHooks.testProfile;
    if(hostHooks.connectCloud && !hostHooks.testProfile && !cloud::migrateTestProfile())
        notice(L"测试版账号数据暂时无法迁入，请检查本机存储空间");
    (void)playerService();
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    auto instance=static_cast<HINSTANCE>(nativeInstance);
    WNDCLASSW wc{};wc.lpfnWndProc=wndProc;wc.hInstance=instance;wc.lpszClassName=L"MusxiApplicationHost";
    RegisterClassW(&wc);
    HWND hwnd=CreateWindowExW(0,wc.lpszClassName,L"Musxi Application",0,0,0,0,0,HWND_MESSAGE,nullptr,instance,nullptr);
    if(!hwnd){CoUninitialize();return 1;}
    applicationWindow=hwnd;
    if(hostHooks.connectCloud) {
        loadLocalCatalog(cloud::dataDir()/L"local-music.json");
        restorePlaybackHistory(cloud::dataDir()/L"playback-history.json");playbackSaveAt=GetTickCount64()+5000;
    }
    if(hostHooks.ready)hostHooks.ready(hwnd);
    if(hostHooks.tick)SetTimer(hwnd,HostTick,10,nullptr);
    if(hostHooks.connectCloud)cloudConnect();
    // CEF's nested message pump can consume WM_QUIT during a timer callback.
    // Window destruction must also stop the outer application message loop.
    MSG msg{};while(!applicationClosing && GetMessageW(&msg,nullptr,0,0)>0){TranslateMessage(&msg);DispatchMessageW(&msg);}
    CoUninitialize();return applicationClosing?0:(int)msg.wParam;
}
}
