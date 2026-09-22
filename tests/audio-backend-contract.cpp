#include "../src/audio/mci_backend.hpp"
#include <windows.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>

void require(bool ok,const char* what) {if(!ok)throw std::runtime_error(what);}
void wav(const std::filesystem::path& path) {
    std::ofstream out(path,std::ios::binary);
    auto u16=[&](unsigned n){out.put(char(n));out.put(char(n>>8));};
    auto u32=[&](unsigned n){for(int i=0;i<4;++i)out.put(char(n>>(8*i)));};
    constexpr unsigned bytes=22050*2;
    out.write("RIFF",4);u32(36+bytes);out.write("WAVEfmt ",8);u32(16);u16(1);u16(1);
    u32(22050);u32(44100);u16(2);u16(16);out.write("data",4);u32(bytes);
    std::vector<char> silence(bytes);out.write(silence.data(),bytes);
}
void pump() {MSG msg{};while(PeekMessageW(&msg,nullptr,0,0,PM_REMOVE)){TranslateMessage(&msg);DispatchMessageW(&msg);}}
int main() {
    CoInitializeEx(nullptr,COINIT_APARTMENTTHREADED);
    int result=0;
    try {
        const auto folder=std::filesystem::path(L"build/audio-contract");
        std::filesystem::create_directories(folder);
        const auto path=folder/L"暂停 seek 测试.wav";wav(path);
        auto backend=musxi::makeMciAudioBackend();
        require(backend->pause().error==musxi::AudioError::NotReady,"pause without source");
        require(backend->setVolume(101).error==musxi::AudioError::InvalidArgument,"volume validation");
        require(bool(backend->setVolume(42)),"preload volume");
        require(bool(backend->load(path.wstring())),"Unicode load");
        require(backend->snapshot().opened && !backend->snapshot().playing && backend->snapshot().durationMs==1000,"load without autoplay");
        const auto generation=backend->snapshot().generation;
        require(bool(backend->play()),"play");Sleep(120);backend->poll();
        require(backend->snapshot().positionMs>0,"advancing position");
        require(bool(backend->pause()) && bool(backend->pause()),"idempotent pause");
        const auto paused=backend->snapshot().positionMs;Sleep(100);backend->poll();
        require(backend->snapshot().positionMs==paused,"paused position stable");
        require(bool(backend->seek(500)) && !backend->snapshot().playing,"paused seek");
        require(bool(backend->resume()) && bool(backend->resume()),"idempotent resume");
        require(bool(backend->stop()) && bool(backend->stop()),"idempotent stop");
        auto state=backend->snapshot();
        require(state.opened && !state.playing && state.positionMs==0 && state.durationMs==1000,"stop retains source and rewinds");
        require(state.generation==generation && std::filesystem::exists(path),"stop retains generation and file");
        pump();require(backend->takeEvents().empty(),"stop emits no natural end");
        require(bool(backend->resume()),"resume after stop");
        require(bool(backend->seek(0xffffffffu)),"seek clamps");
        int ended=0;const auto deadline=GetTickCount64()+2500;
        while(GetTickCount64()<deadline && !ended) {
            pump();backend->poll();
            for(const auto& event:backend->takeEvents()) {
                require(event.generation==generation,"event generation");
                require(event.kind==musxi::AudioEventKind::Ended,"unexpected backend error");++ended;
            }
            Sleep(10);
        }
        require(ended==1 && !backend->snapshot().playing,"natural end exactly once");
        pump();require(backend->takeEvents().empty(),"no duplicate end");
        require(bool(backend->unload()) && bool(backend->unload()),"idempotent unload");
        require(!backend->snapshot().opened && backend->snapshot().volumePercent==42,"unload retains volume only");
        require(std::filesystem::exists(path),"backend never deletes caller file");
        require(!backend->load((folder/L"missing.wav").wstring()),"missing file fails");
        require(!backend->snapshot().opened,"failed load is not open");
        require(bool(backend->load(path.wstring())) && bool(backend->play()),"reload");
        require(bool(backend->seek(999)) && bool(backend->unload()),"unload during completion");
        require(bool(backend->load(path.wstring())),"load next generation");
        pump();require(backend->takeEvents().empty() && !backend->snapshot().playing,"old completion cannot affect next load");
        backend->unload();
        std::ofstream writable(path,std::ios::binary|std::ios::app);require(bool(writable),"file released after unload");
        std::cout<<"PASS isolated MCI backend contract\n";
    } catch(const std::exception& e) {std::cerr<<e.what()<<'\n';result=1;}
    CoUninitialize();return result;
}
