#include "../src/cef/dynamic_wallpaper_store.hpp"
#include "../src/cef/wallpaper_store.hpp"
#include <windows.h>
#include <stdexcept>
using namespace musxi::wallpaper;
void check(bool value) {if(!value)throw std::runtime_error("dynamic wallpaper check failed");}
int main(int argc,char** argv) {
    check(argc==2);const auto fixtures=fs::u8path(argv[1]);
    for(const auto* name:{"scene/preview.gif","video/preview.webp","animated.png"}) {
        const auto image=thumbnailCopy(fixtures/name);
        check(image.width==160 && image.height==90 && (image.mime=="image/jpeg" || image.mime=="image/png"));
        bool rejected=false;try{read(fixtures/name);}catch(const std::exception&){rejected=true;}check(rejected);
    }
    const auto scaled=thumbnailCopy(fixtures/"../wallpaper/large.png");check(scaled.width==400 && scaled.height==200);
    bool invalid=false;try{thumbnailCopy(fixtures/"broken/preview.png");}catch(const std::exception&){invalid=true;}check(invalid);
    const auto root=fs::temp_directory_path()/(L"musxi-wallpaper-test-"+std::to_wstring(GetCurrentProcessId()));
    fs::create_directories(root/L"库/scene");fs::create_directories(root/L"库/video");fs::create_directories(root/L"库/web");fs::create_directories(root/L"库/broken");
    std::ofstream(root/L"库/scene/project.json")<<R"({"title":"Scene","type":"scene","file":"scene.json","preview":"preview.gif"})";
    std::ofstream(root/L"库/scene/scene.pkg")<<"fixture";
    std::ofstream(root/L"库/scene/preview.gif")<<"fixture";
    std::ofstream(root/L"库/video/project.json")<<R"({"title":"Video","type":"Video","file":"video.mp4","preview":"../scene/preview.gif"})";
    std::ofstream(root/L"库/video/video.mp4")<<"fixture";
    std::ofstream(root/L"库/web/project.json")<<R"({"type":"web","file":"index.html"})";
    std::ofstream(root/L"库/broken/project.json")<<R"({"type":42})";
    fs::create_directories(root/L"库/missing");std::ofstream(root/L"库/missing/project.json")<<R"({"type":"video","file":"missing.mp4"})";
    std::vector<ScanDirectory> reports;
    auto list=scanProjects({root/L"库",root/L"库/scene",root/L"absent"},&reports);check(list.size()==2);check(list[0].type=="scene");check(list[0].preview=="preview.gif");
    check(reports.size()==3 && reports[0].count==2 && reports[0].unsupported==1 && reports[0].invalid==1 && reports[0].missing==1 && !reports[2].error.empty());
    check(list[1].type=="video" && list[1].preview.empty());
    check(!containedFile(root/L"库/scene","scene.pkg").empty());
    for(const auto* name:{"../video/video.mp4","..\\video\\video.mp4","C:/secret","/secret","scene.pkg:secret","missing"})check(containedFile(root/L"库/scene",name).empty());
    check(containedFile(root/L"库/scene",std::string("scene.pkg\0secret",16)).empty());
    fs::create_directories(root/L"steam/steamapps");
    std::ofstream(root/L"steam/steamapps/libraryfolders.vdf")<<R"("libraryfolders" { "0" { "path" "D:\\SteamLibrary" } })";
    auto libs=steamLibraries(root/L"steam");check(libs.size()==2);check(libs[1]==fs::path(L"D:\\SteamLibrary"));
    const auto workshop=root/L"steam/steamapps/workshop/content/431960";
    const auto builtIn=root/L"steam/steamapps/common/wallpaper_engine/projects/defaultprojects";
    fs::create_directories(workshop);fs::create_directories(builtIn);
    for(const auto& destination:{workshop/L"fixture",builtIn/L"fixture"})fs::copy(root/L"库/scene",destination,fs::copy_options::recursive);
    check(scanProjects(wallpaperRoots(root/L"steam")).size()==2);
    check(scanProjects(wallpaperRoots(root/L"steam/steamapps")).size()==2);
    check(scanProjects(wallpaperRoots(root/L"steam/steamapps/common/wallpaper_engine")).size()==1);
    const auto cached=root/L"cache.bin";std::ofstream(cached)<<"1";
    ThumbnailCache cache;int decodes=0;
    auto decode=[&](const fs::path&){++decodes;Image image;image.bytes.resize(1024*1024);return image;};
    cache.get(cached,decode);cache.get(cached,decode);check(decodes==1);
    std::ofstream(cached)<<"22";cache.get(cached,decode);check(decodes==2);
    fs::last_write_time(cached,fs::last_write_time(cached)+std::chrono::seconds(2));cache.get(cached,decode);check(decodes==3);
    for(int i=0;i<33;++i){const auto file=root/(std::to_string(i)+".bin");std::ofstream(file)<<"1";cache.get(file,decode);}
    cache.get(cached,decode);check(decodes==37);
    cache.clear();cache.get(cached,decode);check(decodes==38);
    bool failed=false;try{cache.get(cached,[](const fs::path&)->Image{throw std::runtime_error("bad image");});}catch(...){failed=true;}
    check(!failed);cache.clear();
    try{cache.get(cached,[](const fs::path&)->Image{throw std::runtime_error("bad image");});}catch(...){failed=true;}
    check(failed);cache.get(cached,decode);check(decodes==39);
    fs::remove_all(root);
}
