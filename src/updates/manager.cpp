#ifndef NOMINMAX
#define NOMINMAX
#endif
#include <windows.h>
#include "updates.hpp"
#include "update_build_info.hpp"
#include <fstream>

namespace musxi::updates {
namespace {
constexpr std::int64_t Day=86400,ManualInterval=30;
std::int64_t nowSeconds(){return std::chrono::duration_cast<std::chrono::seconds>(std::chrono::system_clock::now().time_since_epoch()).count();}
Json cachedRelease(const Release& r) {
    Json assets=Json::array();
    if(!r.assetId.empty())assets.push_back({{"id",std::stoll(r.assetId)},{"name",r.assetName},{"state","uploaded"},
        {"size",r.bytes},{"browser_download_url",downloadUrl(r)}});
    return {{"id",std::stoll(r.id)},{"tag_name",r.tag},{"draft",false},{"prerelease",r.prerelease},
        {"published_at",r.publishedAt},{"body",r.notes},{"html_url",releaseUrl(r)},{"assets",assets}};
}
}
BuildInfo buildInfo(){return {configured::Version,configured::Commit,configured::Release};}
Manager::Manager(BuildInfo info,std::filesystem::path profile,bool automatic,Fetch fetch,Open open,Clock clock,SteadyClock steady,Storage storage)
    :info_(std::move(info)),file_(std::move(profile)/L"update-settings.json"),fetch_(std::move(fetch)),open_(std::move(open)),
     clock_(clock?std::move(clock):Clock(nowSeconds)),
     steady_(steady?std::move(steady):SteadyClock([]{return std::chrono::steady_clock::now();})),
     storage_(storage),automatic_(automatic),autoAt_(steady_()+std::chrono::seconds(5)) {
    if(!info_.release)selection_.result="development_build";
    load();
}
Manager::~Manager(){stop();}
void Manager::stop() {
    stopped_=true;cancelled_=true;
    if(future_.valid()){try{future_.get();}catch(...){}}
    if(status_=="checking"){status_="failed";reason_="cancelled";stale_=lastSuccess_!=0;}
}
void Manager::load() {
    if(storage_==Storage::Memory)return;
    try {
        if(file_.parent_path().empty() || !std::filesystem::exists(file_) || std::filesystem::file_size(file_)>131072)return;
        Json data;{std::ifstream in(file_,std::ios::binary);in>>data;}
        if(data.at("version")!=1 || !data.at("autoCheck").is_boolean())return;
        autoCheck_=data["autoCheck"].get<bool>();
        // Older version-1 files have no ignore preference. Discard malformed IDs.
        if(data.contains("ignoredReleaseId") && data["ignoredReleaseId"].is_string()) {
            const auto value=data["ignoredReleaseId"].get<std::string>();
            if(value.empty() || (value.size()<=32 && value[0]>='1' && value[0]<='9' &&
                value.find_first_not_of("0123456789")==value.npos))ignoredReleaseId_=value;
        }
        const auto now=clock_();
        auto attempt=data.at("lastAttemptAt").get<std::int64_t>();
        auto success=data.at("lastSuccessAt").get<std::int64_t>();
        auto retry=data.at("retryAt").get<std::int64_t>();
        if(attempt<0 || success<0 || success>now || attempt>now || retry<0 || retry>now+Day)return;
        // Cache is untrusted local input: run the same release/URL validation again.
        auto cached=selectRelease(data.at("releases"),info_);
        if(success>0){selection_=std::move(cached);lastSuccess_=success;stale_=true;}
        lastAttempt_=attempt;retryAt_=retry;
    }catch(...){ /* Broken cache cannot disable manual checks or supply URLs. */ }
}
bool Manager::save() {
    if(storage_==Storage::Memory)return true;
    auto temp=file_;temp+=L".tmp";
    try {
        if(file_.parent_path().empty())return false;
        std::filesystem::create_directories(file_.parent_path());
        Json rows=Json::array();if(selection_.latest)rows.push_back(cachedRelease(*selection_.latest));
        const Json data={{"version",1},{"autoCheck",autoCheck_},{"ignoredReleaseId",ignoredReleaseId_},{"lastAttemptAt",lastAttempt_},
            {"lastSuccessAt",lastSuccess_},{"retryAt",retryAt_},{"releases",rows}};
        {std::ofstream out(temp,std::ios::binary);out<<data.dump();out.close();if(!out)throw std::runtime_error("storage_error");}
        if(!MoveFileExW(temp.c_str(),file_.c_str(),MOVEFILE_REPLACE_EXISTING|MOVEFILE_WRITE_THROUGH))throw std::runtime_error("storage_error");
        return true;
    }catch(...){DeleteFileW(temp.c_str());return false;}
}
void Manager::start() {
    if(stopped_ || future_.valid())return;
    const auto previous=lastAttempt_;lastAttempt_=clock_();reason_.clear();status_="checking";
    retryAt_=lastAttempt_+ManualInterval;
    try {future_=std::async(std::launch::async,[this] {
        try{return fetch_(cancelled_);}catch(...){return FetchResult{{},"network_error",0};}
    });}catch(...){lastAttempt_=previous;status_="failed";reason_="internal_error";stale_=lastSuccess_!=0;}
}
void Manager::poll() {
    if(!future_.valid() || future_.wait_for(std::chrono::seconds(0))!=std::future_status::ready)return;
    FetchResult response;try{response=future_.get();}catch(...){response.reason="internal_error";}
    const auto now=clock_();
    reason_=response.reason;
    if(reason_.empty()) {
        try {
            if(response.body.size()>1024*1024)throw std::runtime_error("invalid_data");
            auto selected=selectRelease(Json::parse(response.body),info_);
            selection_=std::move(selected);lastSuccess_=now;stale_=false;
        }catch(...){reason_="invalid_data";}
    }
    status_=reason_.empty()?"succeeded":"failed";
    if(!reason_.empty())stale_=lastSuccess_!=0;
    if(response.retryAfter>now)retryAt_=std::max(retryAt_,std::min(response.retryAfter,now+Day));
    (void)save(); // Storage trouble must not turn a successful network check into 'no update'.
}
void Manager::tick() {
    poll();
    if(stopped_ || !automatic_ || !autoCheck_ || !info_.release || autoAttempted_ ||
        steady_()<autoAt_)return;
    const auto now=clock_();
    if(now<retryAt_ || (lastAttempt_>0 && now-lastAttempt_<3600))return;
    autoAttempted_=true;
    if(lastSuccess_>0 && now>=lastSuccess_ && now-lastSuccess_<Day)return;
    start();
}
Json Manager::state() {
    poll();const auto now=clock_();
    Json latest=nullptr;
    if(selection_.latest) {
        const auto& r=*selection_.latest;
        latest={{"releaseId",r.id},{"assetId",r.assetId},{"version",r.version},{"tag",r.tag},
            {"prerelease",r.prerelease},{"publishedAt",r.publishedAt},{"notes",r.notes},
            {"assetName",r.assetName},{"bytes",r.bytes},
            {"downloadAvailable",selection_.result=="update_available"}};
    }
    return {{"current",{{"version",info_.version},{"commit",info_.commit},{"releaseBuild",info_.release},{"architecture","x64"}}},
        {"source","Miloscovo/Musxi-Player"},{"includesPrereleases",true},
        {"preferences",{{"autoCheck",autoCheck_},{"ignoredReleaseId",ignoredReleaseId_}}},{"automaticCheckEnabled",automatic_ && autoCheck_ && info_.release && !stopped_},
        {"checkStatus",status_},{"result",selection_.result},{"reason",reason_},{"latest",latest},
        {"lastAttemptAt",lastAttempt_},{"lastSuccessAt",lastSuccess_},
        {"stale",stale_ || (lastSuccess_>0 && now-lastSuccess_>=Day)},{"retryAt",retryAt_},
        {"canCheck",!stopped_ && status_!="checking" && now>=retryAt_}};
}
Reply Manager::command(const std::string& name,const Json& params) {
    poll();
    if(!params.is_object())return {400,"invalid_params"};
    if(stopped_)return {409,"shutting_down"};
    if(name=="updates.getState" || name=="updates.check") {
        if(!params.empty())return {400,"invalid_params"};
        if(name=="updates.check" && !future_.valid()) {
            if(clock_()<retryAt_)return {429,"check_throttled",state()};
            autoAttempted_=true;start();
        }
    }else if(name=="updates.setPreferences") {
        if(params.size()!=1 || !params.contains("autoCheck") || !params["autoCheck"].is_boolean())return {400,"invalid_params"};
        const bool previous=autoCheck_;autoCheck_=params["autoCheck"].get<bool>();
        if(!save()){autoCheck_=previous;return {500,"storage_error"};}
    }else if(name=="updates.ignoreRelease") {
        if(params.size()!=1 || !params.contains("releaseId") || !params["releaseId"].is_string())return {400,"invalid_params"};
        const auto id=params["releaseId"].get<std::string>();
        if(!id.empty() && (!selection_.latest || id!=selection_.latest->id))return {409,"release_changed"};
        const auto previous=ignoredReleaseId_;ignoredReleaseId_=id;
        if(!save()){ignoredReleaseId_=previous;return {500,"storage_error"};}
    }else if(name=="updates.openDownload" || name=="updates.openReleaseNotes") {
        const bool download=name=="updates.openDownload";
        if(params.size()!=(download?2u:1u) || !params.contains("releaseId") || !params["releaseId"].is_string() ||
            (download && (!params.contains("assetId") || !params["assetId"].is_string())))return {400,"invalid_params"};
        if(!selection_.latest)return {409,"release_changed"};
        const auto& r=*selection_.latest;
        if(params["releaseId"]!=r.id || (download && (params["assetId"]!=r.assetId || selection_.result!="update_available")))return {409,"release_changed"};
        if(!open_(download?downloadUrl(r):releaseUrl(r)))return {500,"browser_open_failed"};
        return {0,{},{{"opened",true}}};
    }else return {404,"unknown_command"};
    return {0,{},state()};
}
}
