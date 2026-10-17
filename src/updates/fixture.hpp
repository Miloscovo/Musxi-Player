#pragma once
#include "updates.hpp"
#include <memory>
#include <vector>

namespace musxi::updates {
// Development-only input adapter. Production state/selection still runs in Manager.
class Fixture : public std::enable_shared_from_this<Fixture> {
public:
    // Check the compiled identity before reading any fixture or applying overrides.
    static std::shared_ptr<Fixture> load(const std::filesystem::path& path,BuildInfo compiled=buildInfo());
    std::unique_ptr<Manager> createManager(Manager::Clock clock={},Manager::SteadyClock steady={});
    // Application-thread only; no files, network or system browser are involved.
    Json diagnostics() const;
private:
    struct Step { std::string body,reason; std::int64_t retryAfter=0; int delaySeconds=0; };
    explicit Fixture(BuildInfo info):info_(std::move(info)){}
    FetchResult fetch(const std::atomic_bool& cancelled,const Manager::Clock& clock);
    bool recordOpen(const std::string& url);
    BuildInfo info_;
    std::vector<Step> steps_;
    std::atomic_size_t fetchCount_{0};
    std::vector<std::string> openedUrls_;
};
}
