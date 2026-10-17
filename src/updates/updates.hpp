#pragma once
#include "../../third_party/json.hpp"
#include <atomic>
#include <chrono>
#include <filesystem>
#include <functional>
#include <future>
#include <optional>
#include <string>

namespace musxi::updates {
using Json=nlohmann::json;
struct BuildInfo { std::string version,commit; bool release=false; };
BuildInfo buildInfo();
// SemVer precedence, with an optional leading v. Invalid input has no ordering.
std::optional<int> compareVersions(const std::string& left,const std::string& right);
struct Release {
    std::string id,assetId,tag,version,publishedAt,notes,assetName;
    bool prerelease=false;
    std::uint64_t bytes=0;
};
struct Selection { std::string result; std::optional<Release> latest; };
Selection selectRelease(const Json& releases,const BuildInfo& current);
std::string releaseUrl(const Release& release);
std::string downloadUrl(const Release& release);
struct FetchResult { std::string body,reason; std::int64_t retryAfter=0; };
FetchResult fetchReleases(const std::atomic_bool& cancelled);
bool openBrowser(const std::string& url);
struct Reply { int code=0; std::string reason; Json state=nullptr; };

// All methods except the injected Fetch run on the Application thread.
// Fetch must honor cancellation and finite timeouts; it never touches Manager.
class Manager {
public:
    using Fetch=std::function<FetchResult(const std::atomic_bool&)>;
    using Open=std::function<bool(const std::string&)>;
    using Clock=std::function<std::int64_t()>;
    using SteadyClock=std::function<std::chrono::steady_clock::time_point()>;
    enum class Storage { Profile, Memory };
    Manager(BuildInfo info,std::filesystem::path profile,bool automatic,
            Fetch fetch=fetchReleases,Open open=openBrowser,Clock clock={},SteadyClock steady={},
            Storage storage=Storage::Profile);
    ~Manager();
    Manager(const Manager&)=delete;
    Manager& operator=(const Manager&)=delete;
    void tick();
    void stop();
    Json state();
    Reply command(const std::string& name,const Json& params);
private:
    void poll();
    void start();
    bool save();
    void load();
    BuildInfo info_;
    std::filesystem::path file_;
    Fetch fetch_;
    Open open_;
    Clock clock_;
    SteadyClock steady_;
    std::atomic_bool cancelled_{false};
    std::future<FetchResult> future_;
    Selection selection_{"not_checked",std::nullopt};
    std::string status_="idle",reason_,ignoredReleaseId_;
    Storage storage_;
    bool autoCheck_=true,automatic_=false,autoAttempted_=false,stopped_=false,stale_=false;
    std::int64_t lastAttempt_=0,lastSuccess_=0,retryAt_=0;
    std::chrono::steady_clock::time_point autoAt_;
};
}
