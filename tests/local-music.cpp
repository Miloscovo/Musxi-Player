#include "../src/application/application.cpp"
#include <iostream>
void requireLocal(bool value,const char* message){if(!value)throw std::runtime_error(message);}
int main(int argc,char** argv) {
    if(argc!=2)return 1;
    const auto folder=fs::path(argv[1]).parent_path()/(L"local-music-check-"+std::to_wstring(GetCurrentProcessId()));
    try {
        const auto nested=folder/L"歌曲 子文件夹";fs::create_directories(nested);
        fs::copy_file(fs::path(argv[1])/L"source.wav",folder/L"测试音乐.WAV");
        fs::copy_file(fs::path(argv[1])/L"source.flac",nested/L"另一首歌.flac");
        {std::ofstream bad(folder/L"broken.mp3");bad<<"not audio";}
        {std::ofstream other(folder/L"readme.txt");other<<"ignored";}
        auto scan=std::async(std::launch::async,[&]{return scanLocalFolder(folder,Json::array());}).get();
        requireLocal(scan["added"]==2 && scan["skipped"]==1,"recursive filtering/probing failed");
        for(const auto& row:scan["tracks"])requireLocal(row["name"]==fs::u8path(row["path"].get<std::string>()).filename().u8string(),"imported filename lost its extension");
        requireLocal(scan["folders"].size()==1 && scan["folders"][0]["name"]==folder.filename().u8string(),"import root did not become a named playlist");
        auto repeat=scanLocalFolder(folder,scan["tracks"],scan["folders"]);
        requireLocal(repeat["added"]==0 && repeat["tracks"].size()==2,"duplicate import changed catalog");
        requireLocal(repeat["folders"].size()==1,"duplicate folder created a second playlist");
        auto overlap=scanLocalFolder(nested,repeat["tracks"],repeat["folders"]);
        requireLocal(overlap["folders"].size()==2 && overlap["tracks"].size()==2,"overlapping import duplicated songs or lost folder");
        const auto catalog=folder/L"local-music.json";
        requireLocal(saveLocalCatalog(overlap["tracks"],catalog,overlap["folders"]),"catalog save failed");
        localLoaded=false;localTracks=Json::array();loadLocalCatalog(catalog);
        requireLocal(localTracks==overlap["tracks"] && localFolders==overlap["folders"],"catalog did not survive reload");
        auto olderTracks=localTracks;
        for(auto& row:olderTracks)row["name"]=fs::u8path(row["path"].get<std::string>()).stem().u8string();
        requireLocal(saveLocalCatalog(olderTracks,catalog,localFolders),"older catalog save failed");
        localLoaded=false;loadLocalCatalog(catalog);
        requireLocal(localTracks==overlap["tracks"],"older catalog filenames did not regain extensions");
        localView=true;cloudConnected=false;
        auto snapshot=musxi::applicationLibrary("library.getState","{\"page\":1}");
        requireLocal(snapshot.code==0,"local catalog requires login");
        auto dto=Json::parse(snapshot.json);
        requireLocal(dto["playlistId"]=="musxi:local" && dto["trackCount"]==2 && !dto["tracks"][0].contains("path"),"public catalog/path boundary failed");
        requireLocal(dto["localPlaylists"].size()==2 && !dto["localPlaylists"][0].contains("path"),"public folder/path boundary failed");
        const auto nestedId=localFolders[1]["id"].get<std::string>();
        requireLocal(musxi::applicationLibrary("library.open",Json{{"id",nestedId}}.dump()).code==0,"local folder open failed");
        dto=Json::parse(musxi::applicationLibrary("library.getState","{}").json);
        requireLocal(dto["trackCount"]==1 && dto["playlistName"]==nested.filename().u8string(),"folder tracks were not filtered");
        requireLocal(musxi::applicationLibrary("library.open","{\"id\":\"local-folder:999\"}").code==404,"unknown folder accepted");
        musxi::applicationLibrary("library.open","{\"id\":\"musxi:local\"}");
        requireLocal(musxi::applicationLibrary("library.play","{\"source\":\"local\",\"id\":\"not-imported\"}").code==404,"unknown local file accepted");
        setVolumeNative(10);
        const auto id=localTracks[0]["id"].get<std::string>();
        requireLocal(musxi::applicationLibrary("library.play",Json{{"source","local"},{"id",id}}.dump()).code==0,"offline local play rejected");
        const auto until=GetTickCount64()+8000;
        while(GetTickCount64()<until){pollAudio();auto state=musxi::applicationPlayerState();if(state.playing && state.positionMs>50)break;Sleep(5);}
        auto state=musxi::applicationPlayerState();
        requireLocal(state.playing && state.positionMs>50 && state.trackId==id,"local FFmpeg/WASAPI playback failed");
        requireLocal(!recentTracks.empty() && recentTracks[0]["id"]==id,"successful local playback missing from recent history");
        requireLocal(!Json::parse(musxi::applicationLibrary("library.getState","{}").json)["now"]["name"].get<std::string>().empty(),"local now-playing title missing");
        dto=Json::parse(musxi::applicationLibrary("library.getState","{}").json);
        requireLocal(dto["queue"].size()==1 && dto["queueCurrentId"]==id && !dto["queue"][0].contains("path"),"local play enqueued the entire playlist or exposed a private path");
        requireLocal(musxi::applicationLibrary("library.qualities",Json{{"id",id}}.dump()).code==409,"local file offered converted quality");
        requireLocal(musxi::applicationLibrary("library.play",Json{{"source","local"},{"id",id}}.dump()).code==0 && cloudQueue.size()==1,"replaying a playlist song duplicated the queue entry");
        const auto secondId=localTracks[1]["id"].get<std::string>();
        requireLocal(musxi::applicationLibrary("library.queueNext",Json{{"source","local"},{"id",secondId}}.dump()).code==0 && cloudQueue.size()==2,"next-play duplicated local song");
        requireLocal(musxi::applicationLibrary("library.skip","{\"delta\":1}").code==0 && localCurrent==1,"offline next-track failed");
        requireLocal(musxi::applicationLibrary("library.skip","{\"delta\":-1}").code==0 && localCurrent==0,"offline previous-track failed");
        const auto generation=audioBackend().snapshot().generation;
        requireLocal(musxi::applicationLibrary("library.queueRemove",Json{{"id",id}}.dump()).code==0 && cloudQueue.size()==1,"current queue removal failed");
        requireLocal(audioTrackId==id && audioBackend().snapshot().generation==generation && opened,"removing current song interrupted playback");
        requireLocal(musxi::applicationLibrary("library.skip","{\"delta\":1}").code==0 && localCurrent==1,"next after current removal skipped the successor");
        requireLocal(musxi::applicationLibrary("library.queueClear","{}").code==0 && cloudQueue.empty() && opened && audioTrackId==secondId,"clearing queue interrupted playback");
        requireLocal(musxi::applicationLibrary("library.skip","{\"delta\":1}").code==409,"empty queue unexpectedly played a song");
        requireLocal(musxi::applicationLibrary("library.queueNext",Json{{"source","local"},{"id",id}}.dump()).code==0 && cloudQueue.size()==1 && audioTrackId==secondId,"enqueue into empty queue interrupted current song");
        requireLocal(musxi::applicationLibrary("library.play",Json{{"source","queue"},{"id",id}}.dump()).code==0 && localCurrent==0 && cloudQueue.size()==1,"queue play rebuilt the original playlist");
        cloudTracks=Json::array({{{"id","cloud-queue-fixture"},{"name","Cloud fixture"}}});
        requireLocal(musxi::applicationLibrary("library.queueNext","{\"source\":\"library\",\"id\":\"cloud-queue-fixture\"}").code==0 && cloudQueue.size()==2 && cloudQueue[1]["id"]=="cloud-queue-fixture","mixed queue insertion failed");
        requireLocal(musxi::applicationLibrary("library.skip","{\"delta\":1}").code==409 && audioTrackId==id,"offline cloud queue play was not rejected safely");
        requireLocal(musxi::applicationLibrary("library.queueRemove","{\"id\":\"missing\"}").code==404,"unknown queue row accepted");
        requireLocal(musxi::applicationLibrary("library.play",Json{{"source","local"},{"id",secondId}}.dump()).code==0 && cloudQueue.size()==3 && cloudQueue[0]["id"]==id && cloudQueue[1]["id"]=="cloud-queue-fixture" && cloudQueue[2]["id"]==secondId,"playing another playlist song replaced existing queue entries");
        requireLocal(musxi::applicationLibrary("library.playPlaylist","{\"id\":\"musxi:local\"}").code==0 && cloudQueue.size()==3 && localCurrent==0,"play-all did not preserve/deduplicate the queue or start from the first song");
        requireLocal(musxi::applicationLibrary("library.playPlaylist","{\"id\":\"stale-playlist\"}").code==409 && cloudQueue.size()==3,"stale playlist play changed the queue");
        requireLocal(musxi::applicationLibrary("library.open",Json{{"id",nestedId}}.dump()).code==0,"folder reopen failed");
        const auto folderFirst=localVisibleTracks()[0].value("id","");
        requireLocal(musxi::applicationLibrary("library.playPlaylist",Json{{"id",nestedId}}.dump()).code==0 && cloudQueue.size()==3 && audioTrackId==folderFirst,"folder play-all did not honor the selected folder");
        requireLocal(musxi::applicationLibrary("library.setPlaybackOrder","{\"order\":\"invalid\"}").code==400,"invalid playback order accepted");
        requireLocal(musxi::applicationLibrary("library.setPlaybackOrder","{\"order\":\"repeat-one\"}").code==0,"repeat mode rejected");
        const auto repeatedId=audioTrackId;
        advancePlayback();
        requireLocal(audioTrackId==repeatedId && opened,"repeat mode switched songs");
        requireLocal(musxi::applicationLibrary("library.setPlaybackOrder","{\"order\":\"random\"}").code==0,"random mode rejected");
        cloudCurrent=0;
        for(int attempt=0;attempt<20;++attempt)requireLocal(nextQueueIndex(1)>0 && nextQueueIndex(1)<static_cast<int>(cloudQueue.size()),"shuffle repeated current song or left queue bounds");
        requireLocal(musxi::applicationLibrary("library.setPlaybackOrder","{\"order\":\"sequential\"}").code==0 && nextQueueIndex(1)==1,"sequential order not restored");
        const auto orderState=Json::parse(musxi::applicationLibrary("library.getState","{}").json);
        requireLocal(orderState.at("playbackOrder")=="sequential","playback order missing from state");
        // A restart restores native queue/current identity and seek, without resuming sound.
        audioBackend().pause();audioBackend().seek(400);
        const auto seekDeadline=GetTickCount64()+8000;
        while(audioBackend().snapshot().pending && GetTickCount64()<seekDeadline){pollAudio();Sleep(5);}
        const auto restoredId=audioTrackId;
        cloudQueue=Json::array({localTracks[0],localTracks[1]});playbackOrder="repeat-one";
        const auto history=folder/L"playback-history.json";
        requireLocal(savePlaybackHistory(history),"playback history save failed");
        closeAudio();audioBackendStorage().reset();cloudQueue=Json::array();playbackOrder="sequential";currentPlaybackTrack=nullptr;
        requireLocal(restorePlaybackHistory(history),"playback history reload failed");
        const auto restoreDeadline=GetTickCount64()+8000;
        while(audioBackend().snapshot().pending && GetTickCount64()<restoreDeadline){pollAudio();Sleep(5);}
        const auto restored=audioBackend().snapshot();
        requireLocal(restored.opened && !restored.pending && !restored.playing && !restored.requestedPlaying && restored.positionMs==400 && audioTrackId==restoredId,"restart lost paused seek/current song");
        requireLocal(cloudQueue.size()==2 && playbackOrder=="repeat-one","restart lost queue/order");
        requireLocal(!cloudQueue[0].contains("path"),"history leaked local paths into queue metadata");
        cloudQueue=Json::array();requireLocal(savePlaybackHistory(history),"empty queue history save failed");
        closeAudio();audioBackendStorage().reset();requireLocal(restorePlaybackHistory(history),"removed current song restore failed");
        requireLocal(cloudQueue.empty() && audioTrackId==restoredId,"empty queue lost independently playing song");
        {std::ofstream bad(history);bad<<"{broken";}
        requireLocal(!restorePlaybackHistory(history) && audioTrackId==restoredId,"corrupt history changed current song");
        closeAudio();audioBackendStorage().reset();fs::remove_all(folder);
        std::cout<<"PASS: recursive import, Unicode, corrupt-file filtering, deduplication, persistence, path boundary and offline WASAPI playback/skip\n";return 0;
    } catch(const std::exception& e) {
        closeAudio();audioBackendStorage().reset();std::error_code error;fs::remove_all(folder,error);
        std::cerr<<e.what()<<"\n";return 1;
    }
}
