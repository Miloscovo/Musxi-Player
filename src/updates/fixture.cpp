#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include "fixture.hpp"
#include "download.hpp"
#include <algorithm>
#include <fstream>
#include <stdexcept>
#include <thread>

namespace musxi::updates {
namespace {
void fields(const Json& object,std::initializer_list<const char*> allowed) {
    if(!object.is_object())throw std::runtime_error("update_fixture_invalid_object");
    for(auto it=object.begin();it!=object.end();++it)
        if(std::none_of(allowed.begin(),allowed.end(),[&](const char* key){return it.key()==key;}))
            throw std::runtime_error("update_fixture_unknown_field: "+it.key());
}
std::int64_t seconds(const Json& object,const char* key,std::int64_t max) {
    if(!object.contains(key))return 0;
    if(!object[key].is_number_integer() || object[key]<0 || object[key]>max)
        throw std::runtime_error(std::string("update_fixture_invalid_seconds: ")+key);
    return object[key].get<std::int64_t>();
}
}
std::shared_ptr<Fixture> Fixture::load(const std::filesystem::path& path,BuildInfo compiled) {
    if(compiled.release)throw std::runtime_error("update_fixture_forbidden_in_release_build");
    constexpr std::size_t Limit=1024*1024;
    std::ifstream in(path,std::ios::binary);
    if(!in)throw std::runtime_error("update_fixture_unreadable");
    std::string body(Limit+1,'\0');in.read(body.data(),static_cast<std::streamsize>(body.size()));
    body.resize(static_cast<std::size_t>(in.gcount()));
    if(in.bad() || body.size()>Limit)throw std::runtime_error("update_fixture_too_large_or_unreadable");
    const auto data=Json::parse(body);
    fields(data,{"schemaVersion","current","releases","fetch","download"});
    if(!data.contains("schemaVersion") || !data["schemaVersion"].is_number_integer() || data["schemaVersion"]!=1)
        throw std::runtime_error("update_fixture_invalid_schema_version");
    if(data.contains("current")) {
        const auto& current=data["current"];fields(current,{"version","releaseBuild"});
        if(current.contains("version")) {
            if(!current["version"].is_string() || !compareVersions(current["version"].get<std::string>(),"0.0.0"))
                throw std::runtime_error("update_fixture_invalid_current_version");
            compiled.version=current["version"].get<std::string>();
        }
        if(current.contains("releaseBuild")) {
            if(!current["releaseBuild"].is_boolean())throw std::runtime_error("update_fixture_invalid_release_build");
            compiled.release=current["releaseBuild"].get<bool>();
        }
    }
    compiled.commit="update-fixture";
    const auto releases=data.value("releases",Json::array());
    (void)selectRelease(releases,compiled);
    Json steps=data.value("fetch",Json::object());
    if(steps.is_object())steps=Json::array({steps});
    if(!steps.is_array() || steps.empty() || steps.size()>32)throw std::runtime_error("update_fixture_invalid_fetch");
    auto fixture=std::shared_ptr<Fixture>(new Fixture(std::move(compiled)));
    if(data.contains("download")) {
        const auto& d=data["download"];fields(d,{"delayMilliseconds","reason","launchFails"});
        fixture->downloadEnabled_=true;
        fixture->downloadDelayMs_=static_cast<int>(seconds(d,"delayMilliseconds",30000));
        if(d.contains("reason")) {
            if(!d["reason"].is_string())throw std::runtime_error("update_fixture_invalid_download");
            fixture->downloadReason_=d["reason"].get<std::string>();
            const std::vector<std::string> allowed{"","timeout","network_error","rate_limited","disk_full","checksum_mismatch","size_mismatch"};
            if(std::find(allowed.begin(),allowed.end(),fixture->downloadReason_)==allowed.end())throw std::runtime_error("update_fixture_invalid_download");
        }
        if(d.contains("launchFails")) {
            if(!d["launchFails"].is_boolean())throw std::runtime_error("update_fixture_invalid_download");
            fixture->launchFails_=d["launchFails"].get<bool>();
        }
    }
    for(const auto& row:steps) {
        fields(row,{"reason","retryAfter","delaySeconds","releases"});
        Step step;
        if(row.contains("reason")) {
            if(!row["reason"].is_string())throw std::runtime_error("update_fixture_invalid_reason");
            step.reason=row["reason"].get<std::string>();
        }
        const std::vector<std::string> reasons{"","network_error","timeout","cancelled","rate_limited","http_error",
            "invalid_data","response_too_large","release_list_incomplete","network_disabled","internal_error"};
        if(std::find(reasons.begin(),reasons.end(),step.reason)==reasons.end())throw std::runtime_error("update_fixture_invalid_reason");
        step.retryAfter=seconds(row,"retryAfter",86400);
        step.delaySeconds=static_cast<int>(seconds(row,"delaySeconds",60));
        const auto selectedRows=row.value("releases",releases);
        const auto selection=selectRelease(selectedRows,fixture->info_);
        if(fixture->downloadEnabled_ && selection.latest) {
            const auto& r=*selection.latest;
            if(r.bytes!=3 || r.manifestId.empty())throw std::runtime_error("update_fixture_download_requires_three_byte_asset_and_manifest");
            fixture->downloadReleases_.push_back(r);
        }
        step.body=selectedRows.dump();fixture->steps_.push_back(std::move(step));
    }
    return fixture;
}
std::unique_ptr<Manager> Fixture::createManager(Manager::Clock clock,Manager::SteadyClock steady) {
    if(!clock)clock=[] {return std::chrono::duration_cast<std::chrono::seconds>(
        std::chrono::system_clock::now().time_since_epoch()).count();};
    auto self=shared_from_this();
    auto fetch=[self,clock](const std::atomic_bool& cancel){return self->fetch(cancel,clock);};
    auto open=[self](const std::string& url){return self->recordOpen(url);};
    auto manager=std::make_unique<Manager>(info_,std::filesystem::path{},true,std::move(fetch),std::move(open),
        std::move(clock),std::move(steady),Manager::Storage::Memory);
    if(downloadEnabled_)manager->setDownloader(createDownloader());
    return manager;
}
std::unique_ptr<Downloader> Fixture::createDownloader() {
    auto self=shared_from_this();auto downloadedId=std::make_shared<std::string>();
    auto transport=[self,downloadedId](const std::string& url,std::uint64_t,const std::atomic_bool& cancel,const DownloadSink& sink)->std::string {
        for(const auto& r:self->downloadReleases_) {
            if(url==manifestUrl(r)) {
                const auto body=Json{{"schemaVersion",1},{"version",r.version},{"architecture","x64"},{"assetName",r.assetName},{"bytes",3},
                    {"sha256","ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"}}.dump();
                return sink(body.data(),body.size())?"":"download_rejected";
            }
            if(url!=downloadUrl(r))continue;
            *downloadedId=r.id;
            // Keep a real interval for UI progress/cancel; no fake clock.
            const auto until=std::chrono::steady_clock::now()+std::chrono::milliseconds(self->downloadDelayMs_);
            if(!sink("a",1))return cancel?"cancelled":"download_rejected";
            while(std::chrono::steady_clock::now()<until) {
                if(cancel)return "cancelled";std::this_thread::sleep_for(std::chrono::milliseconds(10));
            }
            const auto& reason=self->downloadReason_;
            if(reason=="size_mismatch")return {};
            if(!reason.empty() && reason!="checksum_mismatch")return reason;
            return sink(reason=="checksum_mismatch"?"bd":"bc",2)?"":"download_rejected";
        }
        return "invalid_download";
    };
    auto launch=[self,downloadedId](const std::filesystem::path&) {
        if(self->launchFails_)return false;
        self->installedReleases_.push_back(*downloadedId);return true;
    };
    const auto root=std::filesystem::temp_directory_path()/("musxi-update-fixture-"+std::to_string(GetCurrentProcessId())+"-"+
        std::to_string(std::chrono::steady_clock::now().time_since_epoch().count()));
    return std::make_unique<Downloader>(root,std::move(transport),std::move(launch),false);
}
FetchResult Fixture::fetch(const std::atomic_bool& cancelled,const Manager::Clock& clock) {
    const auto index=fetchCount_.fetch_add(1);
    const auto& step=steps_[std::min(index,steps_.size()-1)];
    const auto until=std::chrono::steady_clock::now()+std::chrono::seconds(step.delaySeconds);
    while(std::chrono::steady_clock::now()<until) {
        if(cancelled)return {{},"cancelled",0};
        std::this_thread::sleep_for(std::chrono::milliseconds(10));
    }
    if(cancelled)return {{},"cancelled",0};
    return {step.body,step.reason,step.retryAfter?clock()+step.retryAfter:0};
}
bool Fixture::recordOpen(const std::string& url) {
    openedUrls_.push_back(url);
    OutputDebugStringA(("Musxi update fixture recorded URL: "+url+"\n").c_str());
    return true;
}
Json Fixture::diagnostics() const {
    return {{"fixtureMode",true},{"fetchCount",fetchCount_.load()},{"openedUrls",openedUrls_},{"installedReleases",installedReleases_}};
}
}
