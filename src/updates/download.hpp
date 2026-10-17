#pragma once
#include "updates.hpp"
#include <memory>

namespace musxi::updates {
// Stream never receives a frontend URL. Each request is generated from Release;
// production transport validates every HTTPS redirect before sending it.
using DownloadSink=std::function<bool(const char*,std::size_t)>;
using DownloadTransport=std::function<std::string(const std::string&,std::uint64_t,const std::atomic_bool&,const DownloadSink&)>;
std::string streamOfficialAsset(const std::string& url,std::uint64_t limit,
    const std::atomic_bool& cancelled,const DownloadSink& sink);
bool launchInstaller(const std::filesystem::path& installer);
bool allowedAssetUrl(const std::string& url,bool redirected=false);

class Downloader {
public:
    using Launch=std::function<bool(const std::filesystem::path&)>;
    Downloader(std::filesystem::path root,DownloadTransport transport=streamOfficialAsset,Launch launch=launchInstaller,
        bool retainLaunched=true);
    ~Downloader();
    Downloader(const Downloader&)=delete;
    Downloader& operator=(const Downloader&)=delete;
    Json state();
    Reply start(const Release& release);
    Reply cancel();
    Reply install(const std::string& releaseId,const std::string& assetId);
    void stop();
    bool installing() const;
private:
    struct File;
    struct Outcome { std::string reason; std::shared_ptr<File> file; };
    Outcome run(Release release);
    void poll();
    void clear();
    std::filesystem::path root_,job_;
    DownloadTransport transport_;
    Launch launch_;
    std::future<Outcome> future_;
    std::shared_ptr<File> ready_;
    std::atomic_bool cancelled_{false};
    std::atomic_uint64_t received_{0};
    std::atomic_int phase_{0}; // 0 manifest, 1 download, 2 verification
    Release release_;
    std::string status_="idle",reason_;
    bool stopped_=false;
    bool retainLaunched_=true;
};
}
