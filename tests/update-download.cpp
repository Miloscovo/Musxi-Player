#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include "../src/updates/download.hpp"
#include <fstream>
#include <iostream>
#include <thread>
using namespace musxi::updates;
namespace fs=std::filesystem;
void require(bool ok,const char* message){if(!ok)throw std::runtime_error(message);}
Json finished(Downloader& d) {
    const auto until=std::chrono::steady_clock::now()+std::chrono::seconds(3);
    for(;;){auto s=d.state();if(s["status"]!="preparing" && s["status"]!="downloading" && s["status"]!="verifying")return s;
        require(std::chrono::steady_clock::now()<until,"download worker timed out");std::this_thread::sleep_for(std::chrono::milliseconds(1));}
}
int main(int argc,char** argv) {
    if(argc==2 && std::string(argv[1])=="--live-cancel") {
        std::atomic_bool cancel{false};std::uint64_t received=0;
        auto task=std::async(std::launch::async,[&]{return streamOfficialAsset(
            "https://github.com/Miloscovo/Musxi-Player/releases/download/v0.4.0/MusxiPlayer-Setup-0.4.0.exe",
            2147483647,cancel,[&](const char*,std::size_t n){received+=n;cancel=true;return false;});});
        if(task.wait_for(std::chrono::seconds(20))!=std::future_status::ready)cancel=true;
        const auto result=task.get();std::cout<<result<<"; received "<<received<<" bytes, never saved or executed\n";
        return result=="cancelled" && received>0?0:1;
    }
    const auto root=fs::temp_directory_path()/("musxi-download-test-"+std::to_string(GetCurrentProcessId()));
    try {
        for(const auto* bad:{"http://github.com/Miloscovo/Musxi-Player/releases/download/v0.5.0/a.exe",
            "https://github.com.evil/Miloscovo/Musxi-Player/releases/download/v0.5.0/a.exe",
            "https://release-assets.githubusercontent.com.evil/github-production-release-asset/a?x=y",
            "https://user@release-assets.githubusercontent.com/github-production-release-asset/a?x=y",
            "https://github.com/Miloscovo/Musxi-Player/releases/download/v0.5.0/../a.exe",
            "https://release-assets.githubusercontent.com/github-production-release-asset/a?x=y#bad"})
            require(!allowedAssetUrl(bad,true),"untrusted asset redirect accepted");
        require(allowedAssetUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?token=x",true),"GitHub asset redirect rejected");
        require(!allowedAssetUrl("https://release-assets.githubusercontent.com/github-production-release-asset/123/abc?token=x"),"CDN accepted as initial URL");
        Release r;r.id="20";r.assetId="120";r.version="0.5.0";r.tag="v0.5.0";r.bytes=3;
        r.assetName="MusxiPlayer-Setup-0.5.0.exe";r.manifestId="121";r.manifestName="MusxiPlayer-Update-0.5.0.json";
        Json manifest={{"schemaVersion",1},{"version",r.version},{"architecture","x64"},{"assetName",r.assetName},{"bytes",r.bytes},
            {"sha256","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}};
        std::string body=manifest.dump(),payload="abc",transportError;bool slow=false,launchOk=false;int launches=0;
        r.manifestBytes=body.size();
        auto transport=[&](const std::string& url,std::uint64_t,const std::atomic_bool& cancel,const DownloadSink& sink)->std::string {
            if(url==manifestUrl(r))return sink(body.data(),body.size())?"":"download_rejected";
            require(url==downloadUrl(r),"unexpected transport URL");
            while(slow && !cancel)std::this_thread::sleep_for(std::chrono::milliseconds(1));
            if(cancel)return "cancelled";
            if(!transportError.empty())return transportError;
            return sink(payload.data(),payload.size())?"":"download_rejected";
        };
        auto launch=[&](const fs::path& path){++launches;
            require(path.is_absolute() && path.filename()==L"installer.exe","untrusted launcher path");
            HANDLE write=CreateFileW(path.c_str(),GENERIC_WRITE,FILE_SHARE_READ|FILE_SHARE_WRITE,nullptr,OPEN_EXISTING,0,nullptr);
            require(write==INVALID_HANDLE_VALUE,"verified installer was writable");if(write!=INVALID_HANDLE_VALUE)CloseHandle(write);
            return launchOk;
        };
        {
            Downloader d(root,transport,launch);
            require(d.state()["status"]=="idle" && d.install("20","120").reason=="release_changed","initial install state");
            auto legacy=r;legacy.manifestId.clear();require(d.start(legacy).reason=="download_unavailable","legacy release downloaded");
            require(!d.start(r).code,"download start failed");
            auto s=finished(d);require(s["status"]=="ready" && s["receivedBytes"]==3 && s["canInstall"]==true,"verified download not ready");
            require(d.install("19","120").reason=="release_changed" && launches==0,"wrong release installed");
            require(d.install("20","120").reason=="installer_start_failed" && d.state()["canInstall"]==true,"launch failure lost ready state");
            require(!d.cancel().code && d.state()["canInstall"]==false,"ready cancel failed");
            payload="abd";require(!d.start(r).code,"restart failed");
            require(finished(d)["reason"]=="checksum_mismatch","hash mismatch accepted");
            require(d.install("20","120").reason=="download_not_ready","corrupt installer launched");
            payload="ab";d.start(r);require(finished(d)["reason"]=="size_mismatch","short download accepted");
            payload="abcd";d.start(r);require(finished(d)["reason"]=="size_mismatch","oversized download accepted");
            payload="abc";transportError="timeout";d.start(r);require(finished(d)["reason"]=="timeout","timeout not surfaced");transportError.clear();
            auto valid=body;body="{}";d.start(r);require(finished(d)["reason"]=="invalid_manifest","bad manifest accepted");body=valid;
            slow=true;d.start(r);require(d.start(r).reason=="download_busy","parallel download accepted");
            require(!d.cancel().code,"cancel rejected");require(finished(d)["status"]=="cancelled","cancel did not terminate worker");slow=false;
            d.start(r);require(finished(d)["status"]=="ready","retry failed");
            launchOk=true;require(!d.install("20","120").code && d.installing(),"verified install failed");
            require(d.start(r).reason=="shutting_down" && d.cancel().reason=="shutting_down","install repeated");
        }
        // Successful launch retains the package for the running installer.
        std::size_t kept=0;
        for(const auto& entry:fs::directory_iterator(root)) {
            require(entry.is_directory(),"unexpected cache entry");
            require(fs::exists(entry.path()/L"installer.exe"),"launched installer deleted too early");++kept;
            fs::remove(entry.path()/L"installer.exe");fs::remove(entry.path());
        }
        require(kept==1,"failed/cancelled download cache leaked");fs::remove(root);
        {
            Downloader d(root,transport,launch);slow=true;d.start(r);d.stop();
            require(d.state()["canInstall"]==false,"shutdown allowed install");slow=false;
        }
        fs::remove(root);
        std::cout<<"Download, verification, cancellation, retry, URL policy and locked installer handoff passed\n";return 0;
    }catch(const std::exception& e){std::cerr<<e.what()<<'\n';return 1;}
}
