#include <windows.h>
#include <shlobj.h>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <stdexcept>
#include <vector>
#include "../src/cloud_bridge.hpp"
namespace fs=std::filesystem;
int main(){
    auto base=fs::path(L"build")/(L"profile-migration-"+std::to_wstring(GetCurrentProcessId()));
    auto from=base/L"source",to=base/L"target";
    try {
        fs::create_directories(from);
        {std::ofstream file(from/L"kugou-session.dat",std::ios::binary);file<<"encrypted-session";}
        {std::ofstream file(from/L"kugou-library.json",std::ios::binary);file<<"playlist";}
        if(!cloud::importProfileOnce(from,to))throw std::runtime_error("initial import failed");
        if(!fs::exists(to/L"kugou-session.dat") || !fs::exists(to/L"kugou-library.json"))throw std::runtime_error("files missing");
        fs::remove(to/L"kugou-session.dat"); // Logout must not import the old login again.
        if(!cloud::importProfileOnce(from,to) || fs::exists(to/L"kugou-session.dat"))throw std::runtime_error("import repeated");
        fs::remove(from/L"kugou-session.dat");fs::remove(from/L"kugou-library.json");
        fs::remove(to/L"kugou-library.json");fs::remove(to/L".test-profile-imported");
        fs::remove(from);fs::remove(to);fs::remove(base);
        std::cout<<"PASS one-time profile import preserves logout\n";
        return 0;
    } catch(const std::exception& e){std::cerr<<"FAIL "<<e.what()<<'\n';return 1;}
}
