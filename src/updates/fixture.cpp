#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include "fixture.hpp"
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
    fields(data,{"schemaVersion","current","releases","fetch"});
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
        (void)selectRelease(selectedRows,fixture->info_);
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
    return std::make_unique<Manager>(info_,std::filesystem::path{},true,std::move(fetch),std::move(open),
        std::move(clock),std::move(steady),Manager::Storage::Memory);
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
    return {{"fixtureMode",true},{"fetchCount",fetchCount_.load()},{"openedUrls",openedUrls_}};
}
}
